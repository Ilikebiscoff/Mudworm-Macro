package com.mudworm.macro.path;

import com.mudworm.macro.Config;
import com.mudworm.macro.Etherwarp;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import static com.mudworm.macro.path.Cell.*;

/**
 * One weighted A* over standing cells (feet positions) with two kinds of moves:
 * <ul>
 *   <li>walking: 8 directions, 1-block step ups (jump), drops of up to 3 blocks, through azalea leaves (they get
 *       broken on the way, so they cost extra)</li>
 *   <li>etherwarp: from the start, from every landing, and from walking cells on a 4x4 grid, to landing blocks in
 *       range that {@link Etherwarp#shot} proves reachable (no wall in front, same target for the whole aim cone)</li>
 * </ul>
 * Runs on its own thread over the {@link WorldCache} snapshot ({@link #run}), so it can never lag the game.
 * Costs are in walked blocks; one etherwarp costs {@link Config#etherCost}. Straight walking stretches are merged
 * into one long segment so the follower walks smooth lines instead of a staircase.
 */
public final class Pathfinder {
    public enum State { RUNNING, FOUND, FAILED }

    public enum Kind { WALK, JUMP, DROP, WARP }

    /** Where to go: next to a spot (in clicking reach with a clear view), or near a patrol point. */
    public record Goal(BlockPos spot, Vec3 center, double radius) {
        public static Goal spot(BlockPos s) {
            return new Goal(s, s.getCenter(), Config.reach);
        }

        public static Goal area(Vec3 c, double r) {
            return new Goal(null, c, r);
        }
    }

    /** One move. {@code kind == WARP}: get to {@code feet} by etherwarping with {@code shot} from the step before. */
    public record Step(BlockPos feet, Etherwarp.Shot shot, Kind kind) {}

    private static final class Node {
        final BlockPos pos;
        double g = Double.POSITIVE_INFINITY, f;
        Node parent;
        Etherwarp.Shot shot;
        Kind kind = Kind.WALK;
        boolean closed;

        Node(BlockPos pos) {
            this.pos = pos;
        }
    }

    private static final int MAX_EXPANSIONS = 60_000;
    private static final long TIMEOUT_NS = 2_500_000_000L;
    private static final int MAX_ETHER_EXPANSIONS = 220;
    private static final int CANDIDATES = 28;
    private static final double AVOID_COST = 25;
    private static final double LEAF_COST = 3;
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private final WorldCache.Reader c;
    private final Goal goal;
    private final double range;
    private final boolean ether, leaves;
    private final Vec3 startEye;
    private final Set<Long> avoid;
    private final Map<Long, Node> nodes = new HashMap<>();
    private final PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
    private final Node start;
    private int expansions, etherExpansions;
    private volatile State state = State.RUNNING;
    private volatile boolean cancelled;
    private volatile List<Step> result = List.of();

    /**
     * @param startEye the real sneaking eye of the player (used for teleports from the start)
     * @param range    etherwarp range for this item; ignored when {@code ether} is false
     * @param avoid    feet cells where we got stuck before (extra cost)
     */
    public Pathfinder(WorldCache cache, BlockPos startFeet, Vec3 startEye, Goal goal, double range, boolean ether,
                      Set<Long> avoid) {
        this.c = cache.reader();
        this.goal = goal;
        this.range = range;
        this.ether = ether;
        this.leaves = Config.breakLeaves;
        this.startEye = startEye;
        this.avoid = avoid;
        start = node(startFeet);
        start.g = 0;
        start.f = h(startFeet.getX(), startFeet.getY(), startFeet.getZ());
        open.add(start);
    }

    public State state() {
        return state;
    }

    public int expansions() {
        return expansions;
    }

    public void cancel() {
        cancelled = true;
    }

    /** The smoothed moves from the start (excluded) to the goal. Valid once {@link #state()} is FOUND. */
    public List<Step> result() {
        return result;
    }

    /** Runs the whole search. Call from the planner thread. */
    public void run() {
        long deadline = System.nanoTime() + TIMEOUT_NS;
        try {
            while (!cancelled) {
                if (System.nanoTime() > deadline) break;
                for (int i = 0; i < 256; i++) {
                    if (!stepOnce()) return;
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
        state = State.FAILED;
    }

    /** @return false when the search ended (state is set). */
    private boolean stepOnce() {
        Node n = open.poll();
        if (n == null || expansions >= MAX_EXPANSIONS) {
            state = State.FAILED;
            return false;
        }
        if (n.closed) return true;
        n.closed = true;
        expansions++;
        if (isGoal(n.pos)) {
            result = build(n);
            state = State.FOUND;
            return false;
        }
        expandWalk(n);
        if (ether && etherExpansions < MAX_ETHER_EXPANSIONS && wantsEther(n)) {
            etherExpansions++;
            expandEther(n);
        }
        return true;
    }

    // ---- goal / heuristic -----------------------------------------------------------------------------------

    private boolean isGoal(BlockPos feet) {
        if (goal.spot() == null) {
            double hd = Math.hypot(feet.getX() + 0.5 - goal.center().x, feet.getZ() + 0.5 - goal.center().z);
            return hd <= goal.radius() && Math.abs(feet.getY() - goal.center().y) <= 8;
        }
        return canClick(feet.getX(), feet.getY(), feet.getZ(), goal.spot());
    }

    /** Standing (not sneaking) at the cell, the spot block is in reach with a clear view of its top or middle. */
    private boolean canClick(int x, int y, int z, BlockPos spot) {
        Vec3 eye = new Vec3(x + 0.5, y + 1.62, z + 0.5);
        if (eye.distanceToSqr(spot.getCenter()) > Config.reach * Config.reach) return false;
        Vec3[] aims = {new Vec3(spot.getX() + 0.5, spot.getY() + 0.95, spot.getZ() + 0.5), spot.getCenter()};
        for (Vec3 a : aims) {
            BlockPos hit = Etherwarp.firstStop(c, eye, a.subtract(eye).normalize(), eye.distanceTo(a) + 0.5, true);
            if (spot.equals(hit)) return true;
        }
        return false;
    }

    private double h(int x, int y, int z) {
        double dx = x + 0.5 - goal.center().x, dy = y + 0.5 - goal.center().y, dz = z + 0.5 - goal.center().z;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz) - goal.radius();
        // with teleports a block can cost much less than walking it; keep the estimate low but not useless
        return Math.max(0, d) * (ether ? 0.45 : 1.0);
    }

    // ---- walking --------------------------------------------------------------------------------------------

    private void expandWalk(Node n) {
        int x = n.pos.getX(), y = n.pos.getY(), z = n.pos.getZ();
        for (int[] d : DIRS) {
            int nx = x + d[0], nz = z + d[1];
            boolean diag = d[0] != 0 && d[1] != 0;
            double base = diag ? 1.414 : 1.0;
            if (diag) { // no corner cutting, and no breaking leaves diagonally
                if (!(clean(x + d[0], y, z) && clean(x, y, z + d[1]) && clean(nx, y, nz))) continue;
            }
            if (standable(nx, y, nz)) {
                relax(n, nx, y, nz, base + wallCost(nx, y, nz) + leafCost(nx, y, nz), null, Kind.WALK);
                continue;
            }
            if (diag) continue; // step ups and drops only straight
            if (standable(nx, y + 1, nz) && passable(x, y + 2, z)) { // jump up one
                relax(n, nx, y + 1, nz, base + 1.0 + leafCost(nx, y + 1, nz) + leafCost(x, y + 2, z), null, Kind.JUMP);
                continue;
            }
            if (clear(nx, y, nz)) { // walk off the edge and fall
                for (int k = 1; k <= 3; k++) {
                    if (standable(nx, y - k, nz)) {
                        relax(n, nx, y - k, nz, base + 0.4 * k + leafCost(nx, y, nz), null, Kind.DROP);
                        break;
                    }
                    if (!passable(nx, y - k, nz)) break;
                }
            }
        }
    }

    /** Small extra cost next to walls so the path does not scrape along them. */
    private double wallCost(int x, int y, int z) {
        int walls = 0;
        if ((c.at(x + 1, y + 1, z) & COLL) != 0) walls++;
        if ((c.at(x - 1, y + 1, z) & COLL) != 0) walls++;
        if ((c.at(x, y + 1, z + 1) & COLL) != 0) walls++;
        if ((c.at(x, y + 1, z - 1) & COLL) != 0) walls++;
        return walls * 0.15;
    }

    /** Leaves to break to stand in this cell (feet and head). */
    private double leafCost(int x, int y, int z) {
        double cost = 0;
        if ((c.at(x, y, z) & BREAKABLE) != 0) cost += LEAF_COST;
        if ((c.at(x, y + 1, z) & BREAKABLE) != 0) cost += LEAF_COST;
        return cost;
    }

    // ---- etherwarp ------------------------------------------------------------------------------------------

    private boolean wantsEther(Node n) {
        if (n == start || n.shot != null) return true;
        return (n.pos.getX() & 3) == 0 && (n.pos.getZ() & 3) == 0;
    }

    private void expandEther(Node n) {
        Vec3 eye = n == start ? startEye : Etherwarp.eyeAt(n.pos);
        double here = Math.sqrt(n.pos.distToCenterSqr(goal.center()));
        int r = (int) Math.ceil(range);
        int cx0 = (int) Math.floor(eye.x - r) >> 4, cx1 = (int) Math.floor(eye.x + r) >> 4;
        int cz0 = (int) Math.floor(eye.z - r) >> 4, cz1 = (int) Math.floor(eye.z + r) >> 4;
        List<long[]> cand = new ArrayList<>(); // {packed target, distance-to-goal bits}
        double r2 = range * range;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                long[] ts = c.targets(cx, cz);
                if (ts == null) continue;
                for (long t : ts) {
                    int tx = BlockPos.getX(t), ty = BlockPos.getY(t), tz = BlockPos.getZ(t);
                    double ex = tx + 0.5 - eye.x, ey = ty + 0.5 - eye.y, ez = tz + 0.5 - eye.z;
                    if (ex * ex + ey * ey + ez * ez > r2) continue;
                    double gx = tx + 0.5 - goal.center().x, gy = ty + 1 - goal.center().y, gz = tz + 0.5 - goal.center().z;
                    double toGoal = Math.sqrt(gx * gx + gy * gy + gz * gz);
                    if (toGoal > here - 4) continue; // only hops that get us clearly closer
                    cand.add(new long[]{t, Double.doubleToLongBits(toGoal)});
                }
            }
        }
        cand.sort(Comparator.comparingDouble(a -> Double.longBitsToDouble(a[1])));
        // the best few by distance to the goal, then a spread of the rest (in case the best ones are behind a hill)
        List<long[]> pick = new ArrayList<>();
        int best = Math.min(cand.size(), CANDIDATES / 2);
        for (int i = 0; i < best; i++) pick.add(cand.get(i));
        int rest = cand.size() - best;
        int more = CANDIDATES - best;
        if (rest > 0 && more > 0) {
            int stride = Math.max(1, rest / more);
            for (int i = best; i < cand.size() && pick.size() < CANDIDATES; i += stride) pick.add(cand.get(i));
        }
        for (long[] p : pick) {
            BlockPos target = BlockPos.of(p[0]);
            Node to = nodes.get(target.above().asLong());
            if (to != null && to.closed) continue;
            Etherwarp.Shot shot = Etherwarp.shot(c, eye, target, range);
            if (shot != null) relax(n, target.getX(), target.getY() + 1, target.getZ(), Config.etherCost, shot, Kind.WARP);
        }
    }

    // ---- A* plumbing ----------------------------------------------------------------------------------------

    private Node node(BlockPos p) {
        return nodes.computeIfAbsent(p.asLong(), k -> new Node(p));
    }

    private void relax(Node from, int x, int y, int z, double cost, Etherwarp.Shot shot, Kind kind) {
        long key = BlockPos.asLong(x, y, z);
        Node m = nodes.get(key);
        if (m == null) {
            m = new Node(new BlockPos(x, y, z));
            nodes.put(key, m);
        }
        if (m.closed) return;
        if (!avoid.isEmpty() && avoid.contains(key)) cost += AVOID_COST;
        double g = from.g + cost;
        if (g >= m.g) return;
        m.g = g;
        m.f = g + h(x, y, z);
        m.parent = from;
        m.shot = shot;
        m.kind = kind;
        open.add(m);
    }

    // ---- result: merge straight walking stretches ------------------------------------------------------------

    private List<Step> build(Node end) {
        List<Node> chain = new ArrayList<>();
        for (Node n = end; n != null; n = n.parent) chain.add(n);
        Collections.reverse(chain);
        List<Step> out = new ArrayList<>();
        int n = chain.size();
        int i = 0;
        while (i < n - 1) {
            Node nx = chain.get(i + 1);
            if (nx.kind != Kind.WALK) {
                out.add(new Step(nx.pos, nx.shot, nx.kind));
                i++;
                continue;
            }
            int maxJ = i + 1;
            while (maxJ + 1 < n && chain.get(maxJ + 1).kind == Kind.WALK) maxJ++;
            int j = maxJ;
            while (j > i + 1 && !straight(chain.get(i).pos, chain.get(j).pos)) j--;
            out.add(new Step(chain.get(j).pos, null, Kind.WALK));
            i = j;
        }
        return out;
    }

    /** A player can walk the straight line a→b on one level: every cell under it (and 0.3 to each side) is clean ground. */
    private boolean straight(BlockPos a, BlockPos b) {
        int y = a.getY();
        if (b.getY() != y) return false;
        double ax = a.getX() + 0.5, az = a.getZ() + 0.5;
        double dx = b.getX() + 0.5 - ax, dz = b.getZ() + 0.5 - az, len = Math.hypot(dx, dz);
        if (len < 1e-6) return true;
        double px = -dz / len, pz = dx / len;
        int steps = (int) Math.ceil(len / 0.25);
        for (int s = 0; s <= steps; s++) {
            double t = (double) s / steps;
            for (double off : new double[]{-0.3, 0, 0.3}) {
                int cx = (int) Math.floor(ax + dx * t + px * off);
                int cz = (int) Math.floor(az + dz * t + pz * off);
                if (!cleanStand(cx, y, cz)) return false;
            }
        }
        return true;
    }

    // ---- world ----------------------------------------------------------------------------------------------

    /** The body can be in this cell (leaves count when breaking them is on). */
    private boolean passable(int x, int y, int z) {
        short f = c.at(x, y, z);
        if ((f & LOADED) == 0) return false;
        if ((f & (FLUID | HAZARD)) != 0) return false;
        return (f & COLL) == 0 || (leaves && (f & BREAKABLE) != 0);
    }

    private boolean clear(int x, int y, int z) {
        return passable(x, y, z) && passable(x, y + 1, z);
    }

    /** Feet and head free without breaking anything. */
    private boolean clean(int x, int y, int z) {
        return cleanCell(x, y, z) && cleanCell(x, y + 1, z);
    }

    private boolean cleanCell(int x, int y, int z) {
        short f = c.at(x, y, z);
        return (f & LOADED) != 0 && (f & (COLL | FLUID | HAZARD)) == 0;
    }

    private boolean groundBelow(int x, int y, int z) {
        short b = c.at(x, y - 1, z);
        return (b & COLL) != 0 && (b & (TALL | HAZARD)) == 0;
    }

    /** Solid ground under the feet and room for the body. */
    private boolean standable(int x, int y, int z) {
        return clear(x, y, z) && groundBelow(x, y, z);
    }

    private boolean cleanStand(int x, int y, int z) {
        return clean(x, y, z) && groundBelow(x, y, z);
    }
}
