package com.mudworm.macro.path;

import com.mudworm.macro.Config;
import com.mudworm.macro.Etherwarp;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * One weighted A* over standing cells (feet positions) with two kinds of moves:
 * <ul>
 *   <li>walking: 8 directions, 1-block step up (jump), drops of up to 3 blocks</li>
 *   <li>etherwarp: from the start, from every landing, and from walking cells on a 4x4 grid, to landing blocks in
 *       range that {@link Etherwarp#shot} proves reachable (no wall in front, same target for the whole aim cone)</li>
 * </ul>
 * Costs are in walked blocks; one etherwarp costs {@link Config#etherCost}. It runs a few milliseconds per tick
 * ({@link #step}) so the game never freezes.
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

    /** One move. {@code shot != null}: get to {@code feet} by etherwarping with that shot from the step before. */
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

    private static final int MAX_EXPANSIONS = 60_000;
    private static final int MAX_ETHER_EXPANSIONS = 220;
    private static final int CANDIDATES = 28;
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private final Level level;
    private final Entity player;
    private final Goal goal;
    private final double range;
    private final boolean ether;
    private final LandingIndex index;
    private final Vec3 startEye;
    private final Map<Long, Node> nodes = new HashMap<>();
    private final PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
    private final Node start;
    private int expansions, etherExpansions;
    private State state = State.RUNNING;
    private Node found;

    /**
     * @param startEye the real sneaking eye of the player (used for teleports from the start)
     * @param range    etherwarp range for this item; ignored when {@code ether} is false
     */
    public Pathfinder(Level level, Entity player, BlockPos startFeet, Vec3 startEye, Goal goal, double range,
                      boolean ether, LandingIndex index) {
        this.level = level;
        this.player = player;
        this.goal = goal;
        this.range = range;
        this.ether = ether;
        this.index = index;
        this.startEye = startEye;
        start = node(startFeet);
        start.g = 0;
        start.f = h(startFeet);
        open.add(start);
    }

    public State state() {
        return state;
    }

    public int expansions() {
        return expansions;
    }

    /** Runs the search for up to {@code nanos}. */
    public State step(long nanos) {
        if (state != State.RUNNING) return state;
        long end = System.nanoTime() + nanos;
        while (System.nanoTime() < end) {
            Node n = open.poll();
            if (n == null || expansions >= MAX_EXPANSIONS) {
                state = State.FAILED;
                return state;
            }
            if (n.closed) continue;
            n.closed = true;
            expansions++;
            if (isGoal(n.pos)) {
                found = n;
                state = State.FOUND;
                return state;
            }
            expandWalk(n);
            if (ether && etherExpansions < MAX_ETHER_EXPANSIONS && wantsEther(n)) {
                etherExpansions++;
                expandEther(n);
            }
        }
        return state;
    }

    /** The moves from the start (excluded) to the goal. */
    public List<Step> result() {
        List<Step> out = new ArrayList<>();
        for (Node n = found; n != null && n != start; n = n.parent) out.add(new Step(n.pos, n.shot));
        Collections.reverse(out);
        return out;
    }

    // ---- goal / heuristic -----------------------------------------------------------------------------------

    private boolean isGoal(BlockPos feet) {
        if (goal.spot() == null) {
            double hd = Math.hypot(feet.getX() + 0.5 - goal.center().x, feet.getZ() + 0.5 - goal.center().z);
            return hd <= goal.radius() && Math.abs(feet.getY() - goal.center().y) <= 8;
        }
        return canClickFrom(level, player, feet, goal.spot());
    }

    /** Standing (not sneaking) at {@code feet}, the spot block is in reach with a clear view of one of its faces. */
    public static boolean canClickFrom(Level level, Entity player, BlockPos feet, BlockPos spot) {
        Vec3 eye = new Vec3(feet.getX() + 0.5, feet.getY() + 1.62, feet.getZ() + 0.5);
        if (eye.distanceTo(spot.getCenter()) > Config.reach) return false;
        return clickPoint(level, player, eye, spot) != null;
    }

    /** A point on the spot block that the crosshair can reach from {@code eye}, or null. */
    public static BlockHitResult clickPoint(Level level, Entity player, Vec3 eye, BlockPos spot) {
        Vec3[] aims = {new Vec3(spot.getX() + 0.5, spot.getY() + 0.95, spot.getZ() + 0.5), spot.getCenter()};
        for (Vec3 a : aims) {
            BlockHitResult hit = level.clip(new ClipContext(eye, a, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(spot)
                    && eye.distanceTo(hit.getLocation()) <= Config.reach) return hit;
        }
        return null;
    }

    private double h(BlockPos p) {
        double d = Math.sqrt(p.distToCenterSqr(goal.center())) - goal.radius();
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
            if (diag && !(clear(x + d[0], y, z) && clear(x, y, z + d[1]))) continue; // no corner cutting
            if (standable(nx, y, nz)) {
                relax(n, new BlockPos(nx, y, nz), base + wallCost(nx, y, nz), null);
                continue;
            }
            if (diag) continue; // step ups and drops only straight
            if (standable(nx, y + 1, nz) && passable(x, y + 2, z)) { // jump up one
                relax(n, new BlockPos(nx, y + 1, nz), base + 1.0, null);
                continue;
            }
            if (clear(nx, y, nz)) { // walk off the edge and fall
                for (int k = 1; k <= 3; k++) {
                    if (standable(nx, y - k, nz)) {
                        relax(n, new BlockPos(nx, y - k, nz), base + 0.4 * k, null);
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
        if (!passable(x + 1, y + 1, z)) walls++;
        if (!passable(x - 1, y + 1, z)) walls++;
        if (!passable(x, y + 1, z + 1)) walls++;
        if (!passable(x, y + 1, z - 1)) walls++;
        return walls * 0.15;
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
        List<long[]> cand = new ArrayList<>(); // {packed target, score bits}
        double r2 = range * range;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                long[] ts = index.chunk(level, cx, cz);
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
        for (long[] c : pick) {
            BlockPos target = BlockPos.of(c[0]);
            Node to = nodes.get(target.above().asLong());
            if (to != null && to.closed) continue;
            Etherwarp.Shot shot = Etherwarp.shot(level, eye, target, range);
            if (shot != null) relax(n, target.above(), Config.etherCost, shot);
        }
    }

    // ---- A* plumbing ----------------------------------------------------------------------------------------

    private Node node(BlockPos p) {
        return nodes.computeIfAbsent(p.asLong(), k -> new Node(p));
    }

    private void relax(Node from, BlockPos to, double cost, Etherwarp.Shot shot) {
        Node m = node(to);
        if (m.closed) return;
        double g = from.g + cost;
        if (g >= m.g) return;
        m.g = g;
        m.f = g + h(to);
        m.parent = from;
        m.shot = shot;
        open.add(m);
    }

    // ---- world ----------------------------------------------------------------------------------------------

    private boolean loaded(int x, int y, int z) {
        BlockPos p = new BlockPos(x, y, z);
        return !level.isOutsideBuildHeight(p) && level.isLoaded(p);
    }

    private static boolean hazard(BlockState s) {
        var b = s.getBlock();
        return b == Blocks.FIRE || b == Blocks.SOUL_FIRE || b == Blocks.MAGMA_BLOCK || b == Blocks.CACTUS
                || b == Blocks.SWEET_BERRY_BUSH || b == Blocks.WITHER_ROSE || b == Blocks.CAMPFIRE
                || b == Blocks.SOUL_CAMPFIRE || b == Blocks.POWDER_SNOW || b == Blocks.COBWEB
                || s.getFluidState().is(FluidTags.LAVA);
    }

    /** The body can be in this cell: no collision, no fluid, nothing that hurts or slows. */
    boolean passable(int x, int y, int z) {
        if (!loaded(x, y, z)) return false;
        BlockPos p = new BlockPos(x, y, z);
        BlockState s = level.getBlockState(p);
        return s.getCollisionShape(level, p).isEmpty() && s.getFluidState().isEmpty() && !hazard(s);
    }

    /** Feet and head cell free. */
    boolean clear(int x, int y, int z) {
        return passable(x, y, z) && passable(x, y + 1, z);
    }

    /** Solid ground under the feet and room for the body. */
    boolean standable(int x, int y, int z) {
        if (!loaded(x, y - 1, z)) return false;
        BlockPos below = new BlockPos(x, y - 1, z);
        BlockState s = level.getBlockState(below);
        if (s.getCollisionShape(level, below).isEmpty() || hazard(s)) return false;
        return clear(x, y, z);
    }
}
