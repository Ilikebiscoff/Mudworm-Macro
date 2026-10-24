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

    /** Where hops may land: a Y band and an XZ box. Keeps the macro off other levels and islands. */
    public record Limits(int minY, int maxY, int minX, int maxX, int minZ, int maxZ) {
        public boolean contains(BlockPos p) {
            return p.getY() >= minY && p.getY() <= maxY && p.getX() >= minX && p.getX() <= maxX
                    && p.getZ() >= minZ && p.getZ() <= maxZ;
        }

        @Override
        public String toString() {
            return "y " + minY + ".." + maxY + ", x " + minX + ".." + maxX + ", z " + minZ + ".." + maxZ;
        }
    }

    private record Cand(double score, float yaw, float pitch, BlockPos target) {}

    private static final int MAX_HOPS = 14;
    private static final long TIMEOUT_NS = 2_000_000_000L;
    /** A hop must bring the landing at least this much closer, except for a few sideways hops to get around things. */
    private static final double MIN_PROGRESS = 1.5;
    private static final int MAX_SIDEWAYS = 1;
    private static final double[] YAW_OFFSETS = {0, 2, -2, 4, -4, 7, -7, 11, -11, 16, -16, 24, -24, 36, -36, 55, -55, 80, -80, 120, -120, 180};
    private static final int KEEP = 8;

    private final WorldCache.Reader c;
    private final Goal goal;
    private final double range;
    private final BlockPos startFeet;
    private final Vec3 startEye;
    private final Set<Long> avoid;
    private final Limits limits;
    /** Look angles the mouse can really reach: the rotation at planning time plus whole multiples of {@code step}. */
    private final float refYaw, refPitch, step;
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
     * @param limits   where hops may land
     * @param refYaw   current look angles and the smallest turn the mouse can make (degrees): hop angles are snapped to that grid
     */
    public Pathfinder(WorldCache cache, BlockPos startFeet, Vec3 startEye, Goal goal, double range, Set<Long> avoid,
                      Limits limits, float refYaw, float refPitch, float step) {
        this.c = cache.reader();
        this.goal = goal;
        this.range = range;
        this.startFeet = startFeet;
        this.startEye = startEye;
        this.avoid = avoid;
        this.limits = limits;
        this.refYaw = refYaw;
        this.refPitch = refPitch;
        this.step = step;
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
                    if (!awayFromRoute(cd.target().above(), steps, 9)) continue; // never go back to where we were
                    if (cd.score() <= here - MIN_PROGRESS) {
                        pick = cd;
                        break;
                    }
                }
                if (pick == null && goal.spot() != null && sideways < MAX_SIDEWAYS) { // nothing gets closer: one real hop sideways
                    for (Cand cd : best) {
                        if (awayFromRoute(cd.target().above(), steps, 64) && cd.score() < here + 8) {
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
            if (!reached && !steps.isEmpty() && here > score(startFeet) - MIN_PROGRESS) {
                steps.clear(); // the route does not end any closer than it started: not worth the mana
                endNote = " (route made no progress)";
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

    /** The landing is at least sqrt({@code minDistSqr}) blocks from the start and from every earlier landing. */
    private boolean awayFromRoute(BlockPos land, List<Step> steps, int minDistSqr) {
        if (land.distSqr(startFeet) < minDistSqr) return false;
        for (Step s : steps) if (land.distSqr(s.feet()) < minDistSqr) return false;
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
            for (float pitch = -85f; pitch <= 89f; pitch += 1.0f) {
                Cand cd = trace(eye, yaw, pitch);
                if (cd != null) found.add(cd);
            }
        }
        found.sort((a, b) -> Double.compare(a.score(), b.score()));
        // the best distinct landings whose aim cone is safe (the very best ones graze edges and are usually not)
        List<Cand> top = new ArrayList<>();
        java.util.Set<BlockPos> seen = new java.util.HashSet<>();
        int tested = 0;
        for (Cand cd : found) {
            if (!seen.add(cd.target())) continue;
            if (++tested > 700) break;
            if (!robust(eye, cd)) continue;
            top.add(cd);
            if (top.size() >= KEEP) break;
        }
        // refine them in small mouse steps (keep the refined aim only if it is still safe)
        List<Cand> out = new ArrayList<>();
        for (Cand cd : top) {
            Cand r = refine(eye, cd);
            out.add(r != cd && robust(eye, r) ? r : cd);
        }
        out.sort((a, b) -> Double.compare(a.score(), b.score()));
        return out;
    }

    /** Traces one look ray; a candidate if it ends on a valid etherwarp target in range. */
    private Cand trace(Vec3 eye, float yaw, float pitch) {
        if (step > 0) { // only angles the mouse can really reach
            yaw = refYaw + Math.round((yaw - refYaw) / step) * step;
            pitch = refPitch + Math.round((pitch - refPitch) / step) * step;
        }
        pitch = Math.max(-90f, Math.min(90f, pitch));
        rays++;
        BlockPos hit = Etherwarp.firstStop(c, eye, Etherwarp.dir(yaw, pitch), range, false);
        if (hit == null || !Etherwarp.validTarget(c, hit.getX(), hit.getY(), hit.getZ())) return null;
        BlockPos land = hit.above();
        if (!limits.contains(land)) return null; // another level / island: never go there
        if (!avoid.isEmpty() && avoid.contains(land.asLong())) return null;
        hits++;
        return new Cand(score(land), yaw, pitch, hit);
    }

    /** Small angle steps (whole mouse steps) around a hop to land a little closer to the goal. */
    private Cand refine(Vec3 eye, Cand start) {
        Cand best = start;
        float g = step > 0 ? step : 0.05f;
        float[][] passes = {{4 * g, 6}, {g, 4}};
        for (float[] pass : passes) {
            float st = pass[0];
            int n = (int) pass[1];
            Cand center = best;
            for (int iy = -n; iy <= n; iy++) {
                for (int ip = -n; ip <= n; ip++) {
                    if (iy == 0 && ip == 0) continue;
                    Cand cd = trace(eye, center.yaw() + iy * st, center.pitch() + ip * st);
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
