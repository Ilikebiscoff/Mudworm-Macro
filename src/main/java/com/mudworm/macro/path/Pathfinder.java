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

/**
 * Etherwarp-only route planner. Every move is one Ether Transmission hop from the block you stand on to another
 * block in range; there is no walking. A* over landing blocks with a constant cost per hop (so it finds the route
 * with the fewest hops), where every hop must pass {@link Etherwarp#shot}: no wall in front, the same target block
 * for the whole aim cone, two clear blocks above the landing.
 * <p>
 * The goal is a landing from which the spot block can be clicked (in reach, clear view), or, for patrolling, any
 * landing near a point. Runs on the planner thread over the {@link WorldCache} snapshot ({@link #run}), so it can
 * never lag the game.
 */
public final class Pathfinder {
    public enum State { RUNNING, FOUND, FAILED }

    /** Where to go: next to a spot (in clicking reach with a clear view), or near a patrol point. */
    public record Goal(BlockPos spot, Vec3 center, double radius) {
        public static Goal spot(BlockPos s) {
            return new Goal(s, s.getCenter(), Config.reach);
        }

        public static Goal area(Vec3 c, double r) {
            return new Goal(null, c, r);
        }
    }

    /** One hop: etherwarp with {@code shot}; you end up standing on {@code feet}. */
    public record Step(BlockPos feet, Etherwarp.Shot shot) {}

    private static final class Node {
        final BlockPos pos;
        double g = Double.POSITIVE_INFINITY, f;
        Node parent;
        Etherwarp.Shot shot;
        boolean closed;

        Node(BlockPos pos) {
            this.pos = pos;
        }
    }

    private static final int MAX_EXPANSIONS = 600;
    /** A hop rarely goes the full range straight at the goal; assume about this share (makes the search greedy). */
    private static final double HOP_EFFICIENCY = 0.55;
    private static final long TIMEOUT_NS = 2_500_000_000L;
    /** Hops tried per node: the best ones by distance to the goal, then a spread of the rest. */
    private static final int BEST = 24, SPREAD = 16;
    private static final double AVOID_COST = 25;

    private final WorldCache.Reader c;
    private final Goal goal;
    private final double range;
    private final Vec3 startEye;
    private final Set<Long> avoid;
    private final Map<Long, Node> nodes = new HashMap<>();
    private final PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
    private final Node start;
    private int expansions;
    private volatile State state = State.RUNNING;
    private volatile boolean cancelled;
    private volatile List<Step> result = List.of();

    // statistics for /mudworm dev
    private int tried, accepted, rejRange, rejTarget, rejCone, noCandidates;
    private double bestGoalDist = Double.MAX_VALUE;
    private BlockPos bestNode;
    private long elapsedMs;
    private volatile String outcome = "running";

    /**
     * @param startEye the real sneaking eye of the player (used for the first hop)
     * @param range    etherwarp range for this item
     * @param avoid    landing cells that failed before (extra cost)
     */
    public Pathfinder(WorldCache cache, BlockPos startFeet, Vec3 startEye, Goal goal, double range, Set<Long> avoid) {
        this.c = cache.reader();
        this.goal = goal;
        this.range = range;
        this.startEye = startEye;
        this.avoid = avoid;
        start = new Node(startFeet);
        nodes.put(startFeet.asLong(), start);
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

    /** The hops from the start to the goal (empty if already there). Valid once {@link #state()} is FOUND. */
    public List<Step> result() {
        return result;
    }

    /** One line about how the search went (for /mudworm dev). */
    public String report() {
        return String.format("%s | %d ms, %d expansions, %d nodes | hops tried %d: ok %d, out of range %d, "
                        + "bad target %d, cone unsafe %d | expansions with no candidate %d | closest landing to goal: %s (%.1f blocks)",
                outcome, elapsedMs, expansions, nodes.size(), tried, accepted, rejRange, rejTarget, rejCone,
                noCandidates, bestNode == null ? "none" : bestNode.toShortString(),
                bestGoalDist == Double.MAX_VALUE ? -1.0 : bestGoalDist);
    }

    /** Runs the whole search. Call from the planner thread. */
    public void run() {
        long t0 = System.nanoTime();
        long deadline = t0 + TIMEOUT_NS;
        try {
            while (!cancelled) {
                if (System.nanoTime() > deadline) {
                    outcome = "TIMEOUT";
                    break;
                }
                if (!stepOnce()) {
                    elapsedMs = (System.nanoTime() - t0) / 1_000_000;
                    return;
                }
            }
            if (cancelled) outcome = "cancelled";
        } catch (Throwable t) {
            outcome = "ERROR " + t;
            t.printStackTrace();
        }
        elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        state = State.FAILED;
    }

    /** @return false when the search ended (state is set). */
    private boolean stepOnce() {
        Node n = open.poll();
        if (n == null || expansions >= MAX_EXPANSIONS) {
            outcome = n == null ? "FAILED: no more hops to try" : "FAILED: expansion limit";
            state = State.FAILED;
            return false;
        }
        if (n.closed) return true;
        n.closed = true;
        expansions++;
        double gd = Math.sqrt(n.pos.distToCenterSqr(goal.center()));
        if (gd < bestGoalDist) {
            bestGoalDist = gd;
            bestNode = n.pos;
        }
        if (isGoal(n.pos)) {
            result = build(n);
            outcome = "FOUND " + result.size() + " hops";
            state = State.FOUND;
            return false;
        }
        expand(n);
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

    /** Hops still needed if every hop went the full range straight at the goal. */
    private double h(int x, int y, int z) {
        double dx = x + 0.5 - goal.center().x, dy = y + 0.5 - goal.center().y, dz = z + 0.5 - goal.center().z;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz) - goal.radius();
        return Math.max(0, d) / (range * HOP_EFFICIENCY) * Config.etherCost;
    }

    // ---- hops -----------------------------------------------------------------------------------------------

    private void expand(Node n) {
        Vec3 eye = n == start ? startEye : Etherwarp.eyeAt(n.pos);
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
                    if (tx == n.pos.getX() && tz == n.pos.getZ() && ty + 1 == n.pos.getY()) continue; // where we stand
                    double ex = tx + 0.5 - eye.x, ey = ty + 0.5 - eye.y, ez = tz + 0.5 - eye.z;
                    if (ex * ex + ey * ey + ez * ez > r2) continue;
                    double gx = tx + 0.5 - goal.center().x, gy = ty + 1 - goal.center().y, gz = tz + 0.5 - goal.center().z;
                    cand.add(new long[]{t, Double.doubleToLongBits(Math.sqrt(gx * gx + gy * gy + gz * gz))});
                }
            }
        }
        if (cand.isEmpty()) noCandidates++;
        cand.sort(Comparator.comparingDouble(a -> Double.longBitsToDouble(a[1])));
        // the best by distance to the goal, then a spread of the rest (in case the best ones are behind a hill)
        List<long[]> pick = new ArrayList<>();
        int best = Math.min(cand.size(), BEST);
        for (int i = 0; i < best; i++) pick.add(cand.get(i));
        int rest = cand.size() - best;
        if (rest > 0) {
            int stride = Math.max(1, rest / SPREAD);
            for (int i = best; i < cand.size() && pick.size() < BEST + SPREAD; i += stride) pick.add(cand.get(i));
        }
        for (long[] p : pick) {
            BlockPos target = BlockPos.of(p[0]);
            long key = target.above().asLong();
            Node to = nodes.get(key);
            if (to != null && to.closed) continue;
            tried++;
            Etherwarp.Shot shot = Etherwarp.shot(c, eye, target, range);
            if (shot != null) {
                accepted++;
                relax(n, target.above(), shot);
            } else {
                switch (Etherwarp.reject(c, eye, target, range)) {
                    case 1 -> rejRange++;
                    case 2 -> rejTarget++;
                    default -> rejCone++;
                }
            }
        }
    }

    private void relax(Node from, BlockPos feet, Etherwarp.Shot shot) {
        long key = feet.asLong();
        Node m = nodes.computeIfAbsent(key, k -> new Node(feet));
        if (m.closed) return;
        double cost = Config.etherCost;
        if (!avoid.isEmpty() && avoid.contains(key)) cost += AVOID_COST;
        double g = from.g + cost;
        if (g >= m.g) return;
        m.g = g;
        m.f = g + h(feet.getX(), feet.getY(), feet.getZ());
        m.parent = from;
        m.shot = shot;
        open.add(m);
    }

    private static List<Step> build(Node end) {
        List<Step> out = new ArrayList<>();
        for (Node n = end; n != null && n.shot != null; n = n.parent) out.add(new Step(n.pos, n.shot));
        Collections.reverse(out);
        return out;
    }
}
