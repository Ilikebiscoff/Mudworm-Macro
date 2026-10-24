package com.mudworm.macro.path;

import com.mudworm.macro.Config;
import com.mudworm.macro.Etherwarp;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Heads straight for the goal with etherwarp hops; no route search. Each hop is found by looking at what a real look
 * ray does instead of picking a block first: many yaw/pitch angles around the direction of the goal are traced with
 * the same ray the server uses, every ray that ends on a valid etherwarp target (never a wall) is a possible hop, and
 * the one that lands closest to the goal wins. The angles of the best few are then refined in small steps. This works
 * on flat ground (where the ray you aim at a block lands several blocks before it) and in forests alike.
 * <p>
 * Hops are chained until the goal is reached (a landing from which the spot can be clicked, or near the patrol point)
 * or no hop gets closer. A partial route is still returned: the macro plans again from wherever it ends up.
 * Runs on the planner thread over the {@link WorldCache} snapshot ({@link #run}), so it can never lag the game.
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

    private record Cand(double score, float yaw, float pitch, BlockPos target) {}

    private static final int MAX_HOPS = 14;
    private static final long TIMEOUT_NS = 2_000_000_000L;
    /** A hop must bring the landing at least this much closer, except for a few sideways hops to get around things. */
    private static final double MIN_PROGRESS = 1.5;
    private static final int MAX_SIDEWAYS = 2;
    private static final double[] YAW_OFFSETS = {0, 2, -2, 4, -4, 7, -7, 11, -11, 16, -16, 24, -24, 36, -36, 55, -55, 80, -80, 120, -120, 180};
    private static final int KEEP = 6;

    private final WorldCache.Reader c;
    private final Goal goal;
    private final double range;
    private final BlockPos startFeet;
    private final Vec3 startEye;
    private final Set<Long> avoid;
    private volatile State state = State.RUNNING;
    private volatile boolean cancelled;
    private volatile List<Step> result = List.of();

    // statistics for /mudworm dev
    private int rays, hits, hopsFound;
    private long elapsedMs;
    private volatile String outcome = "running";
    private String endNote = "";

    /**
     * @param startEye the real sneaking eye of the player (used for the first hop)
     * @param range    etherwarp range for this item
     * @param avoid    landing cells that failed before
     */
    public Pathfinder(WorldCache cache, BlockPos startFeet, Vec3 startEye, Goal goal, double range, Set<Long> avoid) {
        this.c = cache.reader();
        this.goal = goal;
        this.range = range;
        this.startFeet = startFeet;
        this.startEye = startEye;
        this.avoid = avoid;
    }

    public State state() {
        return state;
    }

    public int expansions() {
        return hopsFound;
    }

    public void cancel() {
        cancelled = true;
    }

    /** The hops to take (possibly only part of the way). Valid once {@link #state()} is FOUND. */
    public List<Step> result() {
        return result;
    }

    /** One line about how the search went (for /mudworm dev). */
    public String report() {
        return String.format("%s%s | %d ms | %d hops planned, %d rays traced, %d ended on a valid landing",
                outcome, endNote, elapsedMs, hopsFound, rays, hits);
    }

    /** Runs the whole search. Call from the planner thread. */
    public void run() {
        long t0 = System.nanoTime();
        long deadline = t0 + TIMEOUT_NS;
        try {
            List<Step> steps = new ArrayList<>();
            BlockPos feet = startFeet;
            Vec3 eye = startEye;
            double here = score(feet);
            int sideways = 0;
            boolean reached = isGoal(feet);
            while (!reached && steps.size() < MAX_HOPS && !cancelled && System.nanoTime() < deadline) {
                List<Cand> best = bestHops(eye, feet, steps);
                Cand pick = null;
                boolean side = false;
                for (Cand cd : best) {
                    if (cd.score() <= here - MIN_PROGRESS) {
                        pick = cd;
                        break;
                    }
                }
                if (pick == null && sideways < MAX_SIDEWAYS) { // nothing gets closer: one hop sideways to get around it
                    for (Cand cd : best) {
                        BlockPos land = cd.target().above();
                        if (farFromRoute(land, steps) && cd.score() < here + 30) {
                            pick = cd;
                            side = true;
                            break;
                        }
                    }
                }
                if (pick == null) {
                    endNote = " (no hop gets closer, " + best.size() + " candidates)";
                    break;
                }
                if (side) sideways++;
                BlockPos land = pick.target().above();
                steps.add(new Step(land, new Etherwarp.Shot(pick.target(), pick.yaw(), pick.pitch())));
                hopsFound = steps.size();
                feet = land;
                eye = Etherwarp.eyeAt(land);
                here = score(land);
                reached = isGoal(land);
            }
            result = steps;
            elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            if (cancelled) {
                outcome = "cancelled";
                state = State.FAILED;
            } else if (steps.isEmpty() && !reached) {
                outcome = "FAILED: no hop gets closer to the goal";
                state = State.FAILED;
            } else {
                outcome = (reached ? "FOUND " : "PARTIAL ") + steps.size() + " hops";
                state = State.FOUND;
            }
            return;
        } catch (Throwable t) {
            outcome = "ERROR " + t;
            t.printStackTrace();
        }
        elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        state = State.FAILED;
    }

    // ---- scoring --------------------------------------------------------------------------------------------

    private boolean isGoal(BlockPos feet) {
        if (goal.spot() == null) {
            double hd = Math.hypot(feet.getX() + 0.5 - goal.center().x, feet.getZ() + 0.5 - goal.center().z);
            return hd <= goal.radius() && Math.abs(feet.getY() - goal.center().y) <= 8;
        }
        return canClick(feet.getX(), feet.getY(), feet.getZ(), goal.spot());
    }

    /** Standing (not sneaking) at the cell, the spot block is in reach with a clear view of its top or middle (plants do not block it). */
    private boolean canClick(int x, int y, int z, BlockPos spot) {
        Vec3 eye = new Vec3(x + 0.5, y + 1.62, z + 0.5);
        if (eye.distanceToSqr(spot.getCenter()) > Config.reach * Config.reach) return false;
        Vec3[] aims = {new Vec3(spot.getX() + 0.5, spot.getY() + 0.95, spot.getZ() + 0.5), spot.getCenter()};
        for (Vec3 a : aims) {
            BlockPos hit = Etherwarp.firstSolid(c, eye, a.subtract(eye).normalize(), eye.distanceTo(a) + 0.5);
            if (spot.equals(hit)) return true;
        }
        return false;
    }

    /** How far a landing is from being done: distance from its eye to the goal minus the goal radius (below 0 = in reach). */
    private double score(BlockPos feet) {
        if (isGoal(feet)) return -1;
        double dx = feet.getX() + 0.5 - goal.center().x, dy = feet.getY() + 1.62 - goal.center().y, dz = feet.getZ() + 0.5 - goal.center().z;
        return Math.max(0, Math.sqrt(dx * dx + dy * dy + dz * dz) - goal.radius());
    }

    private boolean farFromRoute(BlockPos land, List<Step> steps) {
        if (land.distSqr(startFeet) < 9) return false;
        for (Step s : steps) if (land.distSqr(s.feet()) < 9) return false;
        return true;
    }

    // ---- finding a hop --------------------------------------------------------------------------------------

    /** The best few hops from {@code eye}, best first. Every one ends on a valid target with a safe aim cone. */
    private List<Cand> bestHops(Vec3 eye, BlockPos feet, List<Step> route) {
        float baseYaw = Etherwarp.angles(eye, goal.center())[0];
        List<Cand> found = new ArrayList<>();
        for (double yo : YAW_OFFSETS) {
            if (cancelled) break;
            float yaw = (float) (baseYaw + yo);
            for (float pitch = -85f; pitch <= 60f; pitch += 1.0f) {
                Cand cd = trace(eye, yaw, pitch);
                if (cd != null) found.add(cd);
            }
        }
        found.sort((a, b) -> Double.compare(a.score(), b.score()));
        // distinct landings only, then refine the best few in small angle steps
        List<Cand> top = new ArrayList<>();
        for (Cand cd : found) {
            boolean dup = false;
            for (Cand t : top) if (t.target().equals(cd.target())) dup = true;
            if (!dup) top.add(cd);
            if (top.size() >= KEEP) break;
        }
        List<Cand> refined = new ArrayList<>();
        for (Cand cd : top) refined.add(refine(eye, cd));
        refined.sort((a, b) -> Double.compare(a.score(), b.score()));
        List<Cand> out = new ArrayList<>();
        for (Cand cd : refined) {
            if (!robust(eye, cd)) continue;
            out.add(cd);
        }
        return out;
    }

    /** Traces one look ray; a candidate if it ends on a valid etherwarp target in range. */
    private Cand trace(Vec3 eye, float yaw, float pitch) {
        rays++;
        BlockPos hit = Etherwarp.firstStop(c, eye, Etherwarp.dir(yaw, pitch), range, false);
        if (hit == null || !Etherwarp.validTarget(c, hit.getX(), hit.getY(), hit.getZ())) return null;
        BlockPos land = hit.above();
        if (!avoid.isEmpty() && avoid.contains(land.asLong())) return null;
        hits++;
        return new Cand(score(land), yaw, pitch, hit);
    }

    /** Small angle steps around a hop to land a little closer to the goal. */
    private Cand refine(Vec3 eye, Cand start) {
        Cand best = start;
        float[][] passes = {{0.25f, 6}, {0.05f, 4}};
        for (float[] pass : passes) {
            float step = pass[0];
            int n = (int) pass[1];
            Cand center = best;
            for (int iy = -n; iy <= n; iy++) {
                for (int ip = -n; ip <= n; ip++) {
                    if (iy == 0 && ip == 0) continue;
                    Cand cd = trace(eye, center.yaw() + iy * step, center.pitch() + ip * step);
                    if (cd != null && cd.score() < best.score()) best = cd;
                }
            }
        }
        return best;
    }

    /** The aim cone around the hop must not hit a wall: every ray ends on a valid target, or flies out of range. */
    private boolean robust(Vec3 eye, Cand cd) {
        return Etherwarp.coneSafe(c, eye, cd.yaw(), cd.pitch(), range);
    }
}
