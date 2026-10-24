package com.mudworm.macro;

import com.mudworm.macro.path.Cell;
import com.mudworm.macro.path.Pathfinder;
import com.mudworm.macro.path.Pathfinder.Step;
import com.mudworm.macro.path.WorldCache;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The macro: pick the nearest spot (or a patrol point when none is known), plan a chain of etherwarps to a cell
 * from which the spot can be clicked, follow it, and left-click the spot block.
 * <ul>
 *   <li>No walking: every move is an etherwarp, chained while staying crouched.</li>
 *   <li>Planning runs on its own thread over a terrain snapshot ({@link WorldCache}), so it never lags the game.</li>
 *   <li>Every 3 s it checks for a closer spot and switches to it.</li>
 *   <li>No real movement for {@link Config#stuckSeconds} s: {@link Config#stuckCommand} (default /warp murk).</li>
 * </ul>
 * Etherwarp safety: before every right-click the real look ray (from the real position, sneaking eye 1.27, the real
 * yaw/pitch) is traced again with {@link Etherwarp#robust}. If the first block it stops at is not the planned target
 * (a wall, a tree, a player-placed block, anything) the click is not sent and the path is planned again.
 */
public final class Macro {
    enum Stage { CHOOSE, PLAN, FOLLOW, WARP_PREP, WARP_WAIT, DIG, DIG_WAIT, SCOOT }

    public static final Macro INSTANCE = new Macro();

    private static final long WARP_TIMEOUT_MS = 1500;
    private static final long SKIP_MS = 60_000;
    private static final long PATROL_REVISIT_MS = 120_000;
    private static final double PATROL_RADIUS = 10;
    /** Patrol goals further than this are pulled closer (the far end is not loaded yet). */
    private static final double MAX_GOAL_DIST = 90;
    private static final long PLAN_TIMEOUT_MS = 6000;
    /** One break every this many client ticks (3 ticks = 150 ms). */
    private static final int NUKE_GAP_TICKS = 3;
    /** Do not send another break for the same spot sooner than this (waiting for the server). */
    private static final long NUKE_RETRY_MS = 500;
    /** A different spot must be at least this many blocks nearer to count (so equal distances do not flip-flop). */
    private static final double SWITCH_MARGIN = 1.0;
    /** A route may end short of the spot; plan again from there at most this many times per spot. */
    private static final int MAX_ROUTE_ROUNDS = 6;
    /** Time allowed at the final point to get a spot before it is blacklisted. */
    private static final long DIG_LIMIT_MS = 2000;

    public final HumanAim aim = new HumanAim();
    private final WorldCache cache = new WorldCache();
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mudworm-planner");
        t.setDaemon(true);
        return t;
    });

    private boolean running;
    private Stage stage = Stage.CHOOSE;
    private String status = "Stopped";
    private ClientLevel startLevel;
    private boolean terrainWait;

    private Pathfinder.Goal goal;
    /** Set while hopping to the area around a spot we could not plan a click position for. */
    private BlockPos approaching;
    private final Map<BlockPos, Integer> planFails = new HashMap<>();
    private Pathfinder planner;
    private Pathfinder.Limits lastLimits;
    private long blockedAt;
    private long planStart;
    private volatile List<Step> steps = new ArrayList<>();
    private volatile int stepIdx;
    private BlockPos patrolPoint;
    private int replans, routeRounds;
    private final Set<Long> avoid = new HashSet<>();

    private final Map<BlockPos, Long> skipUntil = new HashMap<>();
    private final Map<BlockPos, Long> patrolVisited = new HashMap<>();


    // stuck / warp
    private Vec3 moveRef;
    private long moveRefAt;
    private boolean warping;
    private Vec3 warpPos;
    private ClientLevel warpLevel;
    private long warpWaitUntil, resumeAt;
    private int warpsSinceSpot;

    // etherwarp
    private Etherwarp.Shot shot;
    private long prepSince, sneakSince, warpAt, readySince;
    private Vec3 warpFrom;
    private int warpFails;
    private long etherOffUntil, noManaUntil, nextSlotChange;

    // digging
    private long digAt, digSince, lastSwing, idleSince;
    // scoot: walk the last few blocks with W/A/S/D, camera untouched
    private long scootStart, scootProgressAt;
    private double scootBest;
    private final Map<BlockPos, Long> scootBlocked = new HashMap<>();
    // nuker: breaks ANY spot in reach, one every few ticks
    private final Map<BlockPos, Long> nukeFirst = new HashMap<>(), nukeLast = new HashMap<>();
    private int tickCount, lastNukeTick = -100;
    private int digTries;
    private long lastFloorDrop;

    // diagnostics (/mudworm dev)
    private String lastPlanReport = "no plan yet";
    private String prepWhy = "";
    private Stage loggedStage = Stage.CHOOSE;
    private int seenResets;

    // stats
    public long runStart, runMs;
    public int spotsDone, warps, floorDrops, shards;

    private Vec3 lastPos;

    private Macro() {}

    public boolean running() {
        return running;
    }

    public String status() {
        return status;
    }

    public Pathfinder.Goal goal() {
        return goal;
    }

    public List<Step> steps() {
        return steps;
    }

    public int stepIdx() {
        return stepIdx;
    }

    // ---- start / stop ---------------------------------------------------------------------------------------

    public void start(Minecraft mc) {
        if (running || mc.player == null) return;
        if (aotvSlot(mc.player) < 0) {
            Chat.msg("Put an Aspect of the Void with Ether Transmission in your hotbar first.");
            return;
        }
        running = true;
        startLevel = mc.level;
        stage = Stage.CHOOSE;
        runStart = System.currentTimeMillis();
        lastPos = mc.player.position();
        skipUntil.clear();
        planFails.clear();
        avoid.clear();
        warpFails = 0;
        warpsSinceSpot = 0;
        warping = false;
        resumeAt = 0;
        moveRef = null;
        aim.reset();
        aim.retarget();
        Chat.msg("Started. Hypixel bans macros - you use this at your own risk.");
    }

    public void stop(Minecraft mc, String why) {
        if (!running) return;
        running = false;
        runMs += System.currentTimeMillis() - runStart;
        release(mc);
        cancelPlan();
        steps = new ArrayList<>();
        goal = null;
        status = "Stopped";
        aim.loose();
        Chat.msg("Stopped" + (why == null ? "." : ": " + why));
    }

    public void toggle(Minecraft mc) {
        if (running) stop(mc, null);
        else start(mc);
    }

    public long totalMs() {
        return runMs + (running ? System.currentTimeMillis() - runStart : 0);
    }

    // ---- chat -----------------------------------------------------------------------------------------------

    public void onChat(String raw) {
        String s = raw.replaceAll("§.", "");
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.contains("enough mana")) noManaUntil = System.currentTimeMillis() + 3000;
        if (running && s.contains("There are blocks in the way")) blockedAt = System.currentTimeMillis();
        if (s.contains("FLOOR DROP!")) {
            floorDrops++;
            lastFloorDrop = System.currentTimeMillis();
            if (lower.contains("mudworm")) shards++;
        }
    }

    // ---- tick -----------------------------------------------------------------------------------------------

    public void tick(Minecraft mc) {
        if (!running) return;
        tickCount++;
        long now = System.currentTimeMillis();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null) {
            if (warping) return; // loading screen after /warp
            stop(mc, "left the world");
            return;
        }
        if (mc.level != startLevel) {
            if (warping) startLevel = mc.level;
            else {
                stop(mc, "the world changed (warp / lobby swap)");
                return;
            }
        }
        cache.tick(mc, 3_000_000L);
        if (seenResets != SpotScanner.resets) { // new lobby: the blacklist and everything we learned about spots is stale
            seenResets = SpotScanner.resets;
            skipUntil.clear();
            planFails.clear();
            nukeFirst.clear();
            nukeLast.clear();
            scootBlocked.clear();
            patrolVisited.clear();
            avoid.clear();
        }
        Vec3 pos = p.position();

        if (warping) { // waiting for our own /warp to take effect
            status = "Warping";
            release(mc);
            boolean moved = pos.distanceTo(warpPos) > 6 || mc.level != warpLevel;
            if (moved || now > warpWaitUntil) {
                warping = false;
                resumeAt = now + (moved ? 2000 : 500);
                afterTeleport(pos, now);
            }
            return;
        }
        if (now < resumeAt) {
            status = "Settling";
            release(mc);
            lastPos = pos;
            return;
        }
        if (lastPos != null && pos.distanceTo(lastPos) > 6 && stage != Stage.WARP_WAIT && Config.stopOnForeignTeleport) {
            lastPos = pos;
            stop(mc, "something teleported you (staff check?)");
            return;
        }
        lastPos = pos;
        if (mc.screen != null) {
            release(mc);
            status = "Paused (screen open)";
            return;
        }
        skipUntil.values().removeIf(t -> t < now);

        terrainWait = false;
        nukeNearby(mc, p, now);
        switch (stage) {
            case CHOOSE -> choose(mc, p, now);
            case PLAN -> plan(mc, p, now);
            case FOLLOW -> follow(mc, p, now);
            case WARP_PREP -> warpPrep(mc, p, now);
            case WARP_WAIT -> warpWait(mc, p, now);
            case DIG -> dig(mc, p, now);
            case DIG_WAIT -> digWait(mc, p, now);
            case SCOOT -> scoot(mc, p, now);
        }
        if (stage != loggedStage) {
            Chat.log("stage " + loggedStage + " -> " + stage + " (" + status + ")");
            loggedStage = stage;
        }
        if (running && Config.unstuckWarp && stuck(p, now)) {
            stuckWarp(mc, p, now, "Did not move for " + Config.stuckSeconds + " s");
        }
    }

    private void afterTeleport(Vec3 pos, long now) {
        cancelPlan();
        steps = new ArrayList<>();
        stepIdx = 0;
        goal = null;
        stage = Stage.CHOOSE;
        lastPos = pos;
        moveRef = pos;
        moveRefAt = now;
        avoid.clear();
        skipUntil.clear();
        aim.reset();
        aim.retarget();
    }

    // ---- stuck: no movement for N seconds -> /warp murk -------------------------------------------------------

    private boolean stuck(LocalPlayer p, long now) {
        boolean expectMove = !terrainWait && (stage == Stage.CHOOSE || stage == Stage.PLAN || stage == Stage.FOLLOW);
        Vec3 pos = p.position();
        if (!expectMove || moveRef == null || pos.distanceToSqr(moveRef) >= 1.0) {
            moveRef = pos;
            moveRefAt = now;
            return false;
        }
        return now - moveRefAt >= Config.stuckSeconds * 1000L;
    }

    private void stuckWarp(Minecraft mc, LocalPlayer p, long now, String reason) {
        String cmd = Config.stuckCommand.trim();
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        moveRefAt = now;
        if (cmd.isBlank()) return;
        if (++warpsSinceSpot > 3) {
            stop(mc, "still stuck after /" + cmd + " 3 times");
            return;
        }
        release(mc);
        cancelPlan();
        steps = new ArrayList<>();
        goal = null;
        p.connection.sendCommand(cmd);
        warping = true;
        warpPos = p.position();
        warpLevel = mc.level;
        warpWaitUntil = now + 15_000;
        Chat.msg(reason + " - /" + cmd + " (stage " + stage + ", " + status + ")");
    }

    // ---- choosing a goal ------------------------------------------------------------------------------------

    private void choose(Minecraft mc, LocalPlayer p, long now) {
        release(mc);
        aim.loose();
        replans = 0;
        routeRounds = 0;
        approaching = null;
        if (now < etherOffUntil) {
            terrainWait = true;
            status = "Etherwarp not working - waiting";
            return;
        }
        if (!cache.has(p.blockPosition())) {
            terrainWait = true;
            status = "Scanning terrain";
            return;
        }
        BlockPos near = spotInReach(mc, p);
        if (near != null || nearestSpot(p) != null) idleSince = 0;
        if (near != null) { // already close enough to another spot: no hop needed
            goal = Pathfinder.Goal.spot(near);
            patrolPoint = null;
            enterDig(now);
            return;
        }
        BlockPos sc = scootCandidate(mc, p, now);
        if (sc != null) { // a spot a few blocks away: just walk the rest, no teleport needed
            goal = Pathfinder.Goal.spot(sc);
            patrolPoint = null;
            startScoot(mc, now);
            return;
        }
        BlockPos spot = nearestSpot(p);
        if (spot != null) {
            goal = Pathfinder.Goal.spot(spot);
            patrolPoint = null;
            beginPlan(mc, p, now);
            return;
        }
        // No spot selected: do not teleport. Wait until one shows up (the scan finds every spot in view).
        terrainWait = true; // standing still is expected, not "stuck"
        status = "Waiting for a mudworm spot";
        goal = null;
        patrolPoint = null;
        if (idleSince == 0) idleSince = now;
        if (Config.unstuckWarp && now - idleSince > Config.stuckSeconds * 1000L) {
            idleSince = 0;
            stuckWarp(mc, p, now, "No spot in view and not moving for " + Config.stuckSeconds + " s");
        }
    }

    /**
     * Where hops may land for this goal: the corridor between where we stand and where we are going (Y band and XZ box
     * around both), plus a margin. It can go up or down to a spot, but never wanders off onto trees, cliffs or islands
     * that are not on the way.
     */
    private Pathfinder.Limits limitsFor(LocalPlayer p, Pathfinder.Goal g) {
        double gy = g.center().y, py = p.getY();
        int m = Config.areaMargin;
        lastLimits = new Pathfinder.Limits(
                (int) Math.floor(Math.min(py, gy)) - Config.landBelow, (int) Math.ceil(Math.max(py, gy)) + Config.landAbove,
                (int) Math.floor(Math.min(p.getX(), g.center().x)) - m, (int) Math.ceil(Math.max(p.getX(), g.center().x)) + m,
                (int) Math.floor(Math.min(p.getZ(), g.center().z)) - m, (int) Math.ceil(Math.max(p.getZ(), g.center().z)) + m);
        return lastLimits;
    }

    /** The nearest known spot we can click right now without moving (so no teleport is spent on it), or null. */
    private BlockPos spotInReach(Minecraft mc, LocalPlayer p) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        Vec3 eye = p.getEyePosition();
        for (BlockPos s : SpotScanner.SPOTS.keySet()) {
            if (skipUntil.containsKey(s) || SpotScanner.BLACKLIST.contains(s)) continue;
            double d = s.getCenter().distanceToSqr(eye);
            if (d > (Config.reach + 0.5) * (Config.reach + 0.5) || d >= bd) continue;
            if (clickPoint(mc.level, p, eye, s) == null) continue;
            bd = d;
            best = s;
        }
        return best;
    }

    /** Extra distance (blocks) counted against a spot that is walled in: spots with a clear view are preferred. */
    private static final double WALLED_PENALTY = 25;
    private final Map<BlockPos, long[]> exposedCache = new HashMap<>();

    /** True when some landing within reach has a clear view of the spot (it is not walled in). Unknown terrain counts as yes. */
    public boolean isExposed(BlockPos s) {
        long now = System.currentTimeMillis();
        long[] c = exposedCache.get(s);
        if (c != null && now - c[0] < 8000) return c[1] == 1;
        boolean r = computeExposed(s);
        exposedCache.put(s, new long[]{now, r ? 1 : 0});
        return r;
    }

    private boolean computeExposed(BlockPos s) {
        var rd = cache.reader();
        double reach = Config.reach - 0.3;
        int rad = (int) Math.ceil(reach) + 1;
        boolean sawChunk = false;
        Vec3[] aims = {new Vec3(s.getX() + 0.5, s.getY() + 0.95, s.getZ() + 0.5), s.getCenter()};
        for (int cx = (s.getX() - rad) >> 4; cx <= (s.getX() + rad) >> 4; cx++) {
            for (int cz = (s.getZ() - rad) >> 4; cz <= (s.getZ() + rad) >> 4; cz++) {
                long[] ts = rd.targets(cx, cz);
                if (ts == null) continue;
                sawChunk = true;
                for (long t : ts) {
                    int tx = BlockPos.getX(t), ty = BlockPos.getY(t), tz = BlockPos.getZ(t);
                    if (Math.abs(tx - s.getX()) > rad || Math.abs(tz - s.getZ()) > rad || Math.abs(ty - s.getY()) > rad) continue;
                    Vec3 eye = new Vec3(tx + 0.5, ty + 2.5, tz + 0.5); // standing on that block
                    if (eye.distanceToSqr(s.getCenter()) > reach * reach) continue;
                    for (Vec3 a : aims) {
                        if (s.equals(Etherwarp.firstSolid(rd, eye, a.subtract(eye).normalize(), eye.distanceTo(a) + 0.5))) return true;
                    }
                }
            }
        }
        return !sawChunk;
    }

    /**
     * The nearest spot we can see is alive right now (its strings are in view). Remembered spots we cannot see are not
     * targets: they may be used up already. We stay on the chosen spot until it is collected or given up.
     */
    private BlockPos nearestSpot(LocalPlayer p) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        ClientLevel lv = Minecraft.getInstance().level;
        if (lv == null) return null;
        for (BlockPos s : SpotScanner.SPOTS.keySet()) {
            if (skipUntil.containsKey(s) || SpotScanner.BLACKLIST.contains(s) || !SpotScanner.alive(lv, s)) continue;
            double d = Math.sqrt(s.distToCenterSqr(p.position()));
            if (!isExposed(s)) d += WALLED_PENALTY;
            if (d < bd) {
                bd = d;
                best = s;
            }
        }
        return best;
    }

    /** After every etherwarp: is the nearest visible spot a different one from the one we are heading for? Then go for that one. */
    private boolean nearerSpotNow(LocalPlayer p, long now) {
        BlockPos best = nearestSpot(p);
        if (best == null) return false;
        if (goal == null || goal.spot() == null) return true; // patrolling and a spot is in view
        if (best.equals(goal.spot())) return false;
        double dBest = Math.sqrt(best.distToCenterSqr(p.position()));
        double dCur = Math.sqrt(goal.spot().distToCenterSqr(p.position()));
        if (dBest + SWITCH_MARGIN >= dCur) return false;
        Chat.debug("Nearest spot is now " + best.toShortString() + " (" + (int) dBest + " blocks) instead of "
                + goal.spot().toShortString() + " (" + (int) dCur + ") - switching.");
        return true;
    }

    /** Known spot sites first (nearest not visited lately), then a grid over the patrol area. */
    private BlockPos nextPatrolPoint(LocalPlayer p, long now) {
        for (var it = patrolVisited.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue() > PATROL_REVISIT_MS) it.remove();
        }
        List<BlockPos> pts = new ArrayList<>();
        for (BlockPos s : SpotScanner.SITES) pts.add(s.above());
        if (pts.isEmpty() || allVisited(pts)) {
            int y = p.blockPosition().getY();
            for (int x = Config.areaMinX; x <= Config.areaMaxX; x += Config.patrolStep)
                for (int z = Config.areaMinZ; z <= Config.areaMaxZ; z += Config.patrolStep) pts.add(new BlockPos(x, y, z));
        }
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos b : pts) {
            if (patrolVisited.containsKey(b) || skipUntil.containsKey(b)) continue;
            double d = Math.hypot(b.getX() + 0.5 - p.getX(), b.getZ() + 0.5 - p.getZ());
            if (d < PATROL_RADIUS) {
                patrolVisited.put(b, now);
                continue;
            }
            if (d < bd) {
                bd = d;
                best = b;
            }
        }
        if (best == null && !patrolVisited.isEmpty()) patrolVisited.clear(); // whole area done: start a new lap
        return best;
    }

    private boolean allVisited(List<BlockPos> pts) {
        for (BlockPos b : pts) if (!patrolVisited.containsKey(b) && !skipUntil.containsKey(b)) return false;
        return true;
    }

    // ---- planning (on the planner thread) -------------------------------------------------------------------

    private void beginPlan(Minecraft mc, LocalPlayer p, long now) {
        cancelPlan();
        int slot = aotvSlot(p);
        if (slot < 0) {
            stop(mc, "no Aspect of the Void with Ether Transmission in the hotbar");
            return;
        }
        double range = Etherwarp.range(p.getInventory().getItem(slot));
        Vec3 eye = new Vec3(p.getX(), p.getY() + Etherwarp.SNEAK_EYE, p.getZ());
        Chat.log("Planning from " + p.blockPosition().toShortString() + " to " + describeGoal() + ", range "
                + String.format("%.1f", range));
        planner = new Pathfinder(cache, p.blockPosition(), eye, goal, range, new HashSet<>(avoid), limitsFor(p, goal),
                p.getYRot(), p.getXRot(), aimStep(mc));
        exec.execute(planner::run);
        planStart = now;
        stage = Stage.PLAN;
        status = goal.spot() != null ? "Planning to spot" : "Planning patrol";
    }

    /** Degrees the camera turns per mouse pixel at the current sensitivity (the smallest turn possible). */
    private static float aimStep(Minecraft mc) {
        double f = mc.options.sensitivity().get() * 0.6 + 0.2;
        return (float) (f * f * f * 8.0) * 0.15f;
    }

    private void cancelPlan() {
        if (planner != null) planner.cancel();
        planner = null;
    }

    private void plan(Minecraft mc, LocalPlayer p, long now) {
        release(mc);
        if (goalGone()) {
            cancelPlan();
            stage = Stage.CHOOSE;
            return;
        }
        faceGoalLoosely(p);
        Pathfinder.State st = planner.state();
        if (st == Pathfinder.State.RUNNING && now - planStart < PLAN_TIMEOUT_MS) return;
        lastPlanReport = planner.report() + " | goal " + describeGoal() + " | " + (now - planStart) + " ms wall";
        Chat.log("Plan: " + lastPlanReport);
        if (st == Pathfinder.State.FOUND) {
            steps = planner.result();
            stepIdx = 0;
            planner = null;
            Chat.debug("Path: " + steps.size() + " etherwarps (" + (now - planStart) + " ms)");
            stage = Stage.FOLLOW;
            return;
        }
        cancelPlan();
        Chat.debug("No way to " + describeGoal() + " - giving it up for a minute, going for the next one.");
        skipGoal(now);
        stage = Stage.CHOOSE;
    }

    private void replan(Minecraft mc, LocalPlayer p, long now, String why) {
        Chat.debug("Re-planning: " + why);
        release(mc);
        aim.loose();
        if (++replans > 4) {
            skipGoal(now);
            stage = Stage.CHOOSE;
            return;
        }
        beginPlan(mc, p, now);
    }

    private void skipGoal(long now) {
        if (goal == null) return;
        if (goal.spot() != null) skipUntil.put(goal.spot(), now + SKIP_MS);
        if (patrolPoint != null) skipUntil.put(patrolPoint, now + SKIP_MS);
    }

    private boolean goalGone() {
        return goal != null && goal.spot() != null
                && (!SpotScanner.SPOTS.containsKey(goal.spot()) || SpotScanner.BLACKLIST.contains(goal.spot()));
    }

    // ---- following the path ---------------------------------------------------------------------------------

    /** Between hops: the next hop is lined up from the current landing. */
    private void follow(Minecraft mc, LocalPlayer p, long now) {
        if (goalGone()) {
            release(mc);
            stage = Stage.CHOOSE;
            return;
        }
        BlockPos inReach = spotInReach(mc, p);
        if (inReach != null) { // this landing already reaches a spot (maybe not the one we planned for): take it
            if (goal.spot() == null || !goal.spot().equals(inReach)) {
                Chat.debug("Spot " + inReach.toShortString() + " is already in reach - taking it without more hops.");
                goal = Pathfinder.Goal.spot(inReach);
                patrolPoint = null;
                approaching = null;
            }
            release(mc);
            enterDig(now);
            return;
        }
        BlockPos sc = scootCandidate(mc, p, now);
        if (sc != null) { // landed a few blocks from a spot: walk the rest instead of another teleport
            goal = Pathfinder.Goal.spot(sc);
            patrolPoint = null;
            release(mc);
            startScoot(mc, now);
            return;
        }
        if (nearerSpotNow(p, now)) { // checked after every etherwarp: another spot is the nearest now
            release(mc);
            stage = Stage.CHOOSE;
            return;
        }
        List<Step> list = steps;
        if (stepIdx >= list.size()) {
            release(mc);
            if (goal.spot() != null) {
                if (clickPoint(mc.level, p, p.getEyePosition(), goal.spot()) != null) {
                    enterDig(now);
                } else if (++routeRounds > MAX_ROUTE_ROUNDS) {
                    Chat.debug("Still not in reach after " + MAX_ROUTE_ROUNDS + " routes - skipping this spot for a minute.");
                    skipGoal(now);
                    stage = Stage.CHOOSE;
                } else {
                    beginPlan(mc, p, now); // partial route: continue from where we landed
                }
            } else {
                if (patrolPoint != null) patrolVisited.put(patrolPoint, now);
                stage = Stage.CHOOSE;
            }
            return;
        }
        shot = list.get(stepIdx).shot();
        prepSince = now;
        if (!p.isShiftKeyDown()) sneakSince = 0; // already sneaking from the last hop: no need to wait for it again
        aim.precise();
        readySince = 0;
        stage = Stage.WARP_PREP;
    }

    // ---- etherwarp ------------------------------------------------------------------------------------------

    private void warpPrep(Minecraft mc, LocalPlayer p, long now) {
        status = "Etherwarp: aiming";
        stopMoving(mc);
        int slot = aotvSlot(p);
        if (slot < 0) {
            stop(mc, "no Aspect of the Void with Ether Transmission in the hotbar");
            return;
        }
        if (p.getInventory().getSelectedSlot() != slot) {
            if (now >= nextSlotChange) {
                p.getInventory().setSelectedSlot(slot);
                nextSlotChange = now + 150;
            }
            return;
        }
        mc.options.keyShift.setDown(true);
        if (p.isShiftKeyDown()) {
            if (sneakSince == 0) sneakSince = now;
        } else {
            sneakSince = 0;
        }
        if (Config.blatant) { // BLATANT: the camera stays where it is, the shot angles are sent silently at the click
            aim.precise();
            aim.setTarget(p.getYRot(), p.getXRot(), 100f, 1f);
        } else {
            aim.setTarget(shot.yaw(), shot.pitch(), 2500f, 1.6f);
        }
        if (now - prepSince > 3000 + Config.hopDelayMs) {
            replan(mc, p, now, "could not line up the etherwarp");
            return;
        }
        if (now < noManaUntil) {
            status = "Etherwarp: waiting for mana";
            return;
        }
        boolean still = p.onGround() && p.getDeltaMovement().horizontalDistanceSqr() < 1e-4;
        boolean sneakOk = sneakSince != 0 && now - sneakSince >= 200 && nextSlotChange <= now;
        float yawErr = Math.abs(net.minecraft.util.Mth.wrapDegrees(shot.yaw() - p.getYRot()));
        float pitchErr = Math.abs(shot.pitch() - p.getXRot());
        boolean aimed = Config.blatant || aim.settled(p, Math.max(0.1f, aimStep(mc) * 0.75f));
        prepWhy = (sneakOk ? "sneak ok" : "waiting for sneak") + ", " + (still ? "still" : "moving/airborne")
                + ", " + (Config.blatant ? "BLATANT (no head movement)" : aimed ? "aimed" : String.format("aiming (err yaw %.2f pitch %.2f)", yawErr, pitchErr));
        if (!sneakOk || !still || !aimed) { // let the server see the sneak, stand still, hold the aim
            readySince = 0;
            return;
        }
        if (readySince == 0) readySince = now;
        if (now - readySince < Config.hopDelayMs) { // extra wait between teleports: saves mana
            prepWhy += ", waiting " + (Config.hopDelayMs - (now - readySince)) + " ms (hop delay)";
            return;
        }

        // the safety check: trace the REAL look ray from the REAL sneaking eye, exactly as the server will.
        // It has to end on a valid target close to the planned one (never a wall); after landing we re-plan anyway.
        Cell.Live live = new Cell.Live(mc.level);
        double range = Etherwarp.range(p.getInventory().getItem(slot));
        Vec3 eye = new Vec3(p.getX(), p.getY() + Etherwarp.SNEAK_EYE, p.getZ());
        float yaw = Config.blatant ? shot.yaw() : p.getYRot(), pitch = Config.blatant ? shot.pitch() : p.getXRot();
        BlockPos hit = Etherwarp.predict(live, eye, yaw, pitch, range);
        if (hit != null && hit.distSqr(shot.target()) <= 100 && Etherwarp.coneSafe(live, eye, yaw, pitch, range)) {
            warpFrom = p.position();
            warpAt = now;
            if (Config.blatant) { // silent aim: the use packet carries the shot angles, the camera never moves
                float oy = p.getYRot(), op = p.getXRot();
                p.setYRot(yaw);
                p.setXRot(pitch);
                mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
                p.setYRot(oy);
                p.setXRot(op);
            } else {
                mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
            }
            warps++;
            Chat.log("Etherwarp #" + warps + " click: aiming at " + shot.target().toShortString() + ", ray ends on "
                    + hit.toShortString() + " from " + p.blockPosition().toShortString());
            stage = Stage.WARP_WAIT;
            status = "Etherwarp: teleporting";
            return;
        }
        // the real angles do not end where the plan said (small aim error on a shallow ray): plan again from here
        Chat.log("Etherwarp refused: the ray ends on " + (hit == null ? "an invalid block / nothing" : hit.toShortString())
                + " but the plan wanted " + shot.target().toShortString());
        replan(mc, p, now, "aim did not match the plan");
    }

    private void warpWait(Minecraft mc, LocalPlayer p, long now) {
        stopMoving(mc);
        mc.options.keyShift.setDown(true);
        if (p.position().distanceTo(warpFrom) > 1.5) { // teleported
            lastPos = p.position();
            BlockPos land = shot.landing();
            double off = Math.hypot(land.getX() + 0.5 - p.getX(), land.getZ() + 0.5 - p.getZ());
            warpFails = 0;
            Chat.log("Teleported to " + p.blockPosition().toShortString() + " (planned " + land.toShortString() + ")");
            if (off > 1.5 || Math.abs(p.getY() - land.getY()) > 1.5) { // landed elsewhere: plan again from here
                replans = 0;
                aim.precise();
                beginPlan(mc, p, now);
                return;
            }
            stepIdx++;
            if (stepIdx >= steps.size()) { // last hop: stand up and go click
                mc.options.keyShift.setDown(false);
                aim.loose();
            }
            stage = Stage.FOLLOW;
            return;
        }
        boolean refused = blockedAt >= warpAt;
        if (refused || now - warpAt > WARP_TIMEOUT_MS) {
            Chat.log(refused ? "Server said \"There are blocks in the way!\" for the hop to " + shot.target().toShortString()
                    : "Etherwarp click did nothing for " + WARP_TIMEOUT_MS + " ms (mana? server refused?)");
            mc.options.keyShift.setDown(false);
            avoid.add(shot.landing().asLong());
            if (++warpFails >= 3) {
                warpFails = 0;
                etherOffUntil = now + 10_000;
                release(mc);
                if (Config.unstuckWarp) { // the etherwarp keeps doing nothing: warp away and start over
                    stuckWarp(mc, p, now, "Etherwarp did nothing 3 times");
                    return;
                }
                Chat.msg("Etherwarp did nothing 3 times (mana? item?) - waiting 10 s.");
                stage = Stage.CHOOSE;
                return;
            }
            replan(mc, p, now, "no teleport happened");
        }
    }

    // ---- clicking the spot ----------------------------------------------------------------------------------

    /** We are at the final point: the 2 s clock for getting this spot starts now. */
    private void enterDig(long now) {
        stage = Stage.DIG;
        digTries = 0;
        digSince = now;
    }

    /** 2 s at the final point without getting the spot: blacklist it for this lobby and move on. */
    private boolean digTimedOut(Minecraft mc, long now) {
        if (now - digSince < DIG_LIMIT_MS) return false;
        BlockPos spot = goal.spot();
        SpotScanner.BLACKLIST.add(spot);
        nukeFirst.remove(spot);
        nukeLast.remove(spot);
        Chat.msg("Could not get the spot at " + spot.toShortString() + " within 2 s - blacklisted until the lobby changes.");
        release(mc);
        mc.gameMode.stopDestroyBlock();
        stage = Stage.CHOOSE;
        return true;
    }

    // ---- scoot: walk the last blocks with W/A/S/D, camera untouched -----------------------------------------

    /**
     * A visible spot within {@link Config#scootRange} blocks (flat distance) that is not in nuke reach yet and can be
     * walked to in a straight line: solid ground all the way, nothing in the way, no hazards. Null if none.
     */
    private BlockPos scootCandidate(Minecraft mc, LocalPlayer p, long now) {
        if (Config.scootRange <= 0) return null;
        ClientLevel lv = mc.level;
        if (lv == null) return null;
        Vec3 eye = p.getEyePosition();
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos s : SpotScanner.SPOTS.keySet()) {
            if (SpotScanner.BLACKLIST.contains(s) || !SpotScanner.alive(lv, s)) continue;
            Long until = scootBlocked.get(s);
            if (until != null && until > now) continue;
            if (eye.distanceTo(s.getCenter()) <= Config.reach - 0.3) continue; // already in reach: the nuker has it
            double hd = Math.hypot(s.getX() + 0.5 - p.getX(), s.getZ() + 0.5 - p.getZ());
            if (hd > Config.scootRange || Math.abs(s.getY() + 1 - p.getY()) > 2.5) continue;
            if (!scootPathSafe(p, s)) {
                scootBlocked.put(s, now + 4000);
                continue;
            }
            if (hd < bd) {
                bd = hd;
                best = s;
            }
        }
        return best;
    }

    /** Solid ground under every step of the straight line, and room for the body: it cannot walk us off an edge. */
    private boolean scootPathSafe(LocalPlayer p, BlockPos spot) {
        var rd = cache.reader();
        double dx = spot.getX() + 0.5 - p.getX(), dz = spot.getZ() + 0.5 - p.getZ();
        double hd = Math.hypot(dx, dz);
        double walk = Math.max(0, hd - (Config.reach - 0.6)); // only as far as needed to get into reach
        int feetY = (int) Math.floor(p.getY() + 0.01);
        double px = -dz / Math.max(hd, 1e-6), pz = dx / Math.max(hd, 1e-6);
        int n = (int) Math.ceil(walk / 0.4) + 1;
        for (int i = 0; i <= n; i++) {
            double t = Math.min(walk, i * 0.4) / Math.max(hd, 1e-6);
            for (double off : new double[]{-0.3, 0, 0.3}) {
                int x = (int) Math.floor(p.getX() + dx * t + px * off), z = (int) Math.floor(p.getZ() + dz * t + pz * off);
                short below = rd.at(x, feetY - 1, z), feet = rd.at(x, feetY, z), head = rd.at(x, feetY + 1, z);
                if ((below & Cell.COLL) == 0 || (below & (Cell.HAZARD | Cell.FLUID | Cell.TALL)) != 0) return false;
                for (short f : new short[]{feet, head}) {
                    if ((f & Cell.LOADED) == 0 || (f & (Cell.COLL | Cell.FLUID | Cell.HAZARD)) != 0) return false;
                }
            }
        }
        return true;
    }

    /** The scoot counts as movement: the "not moving for N s -> /warp murk" timer starts again from now. */
    private void restartStuckTimer(long now) {
        var pl = Minecraft.getInstance().player;
        moveRef = pl == null ? null : pl.position();
        moveRefAt = now;
    }

    private void startScoot(Minecraft mc, long now) {
        release(mc); // stand up: no sneaking while walking
        aim.precise();
        stage = Stage.SCOOT;
        scootStart = now;
        scootProgressAt = now;
        scootBest = Double.MAX_VALUE;
        status = "Scooting to spot";
        restartStuckTimer(now);
        Chat.log("Scooting to " + goal.spot().toShortString() + " (within " + Config.scootRange + " blocks, max " + Config.scootSeconds + " s, no teleport)");
    }

    private void giveUpScoot(Minecraft mc, long now, String why) {
        stopMoving(mc);
        restartStuckTimer(now);
        if (goal != null && goal.spot() != null) scootBlocked.put(goal.spot(), now + 10_000);
        Chat.debug("Scoot stopped: " + why);
        aim.loose();
        stage = Stage.CHOOSE;
    }

    /** Holds W/A/S/D (whatever combination points at the spot, relative to where we already look). Never turns the head. */
    private void scoot(Minecraft mc, LocalPlayer p, long now) {
        BlockPos spot = goal == null ? null : goal.spot();
        if (spot == null || goalGone()) {
            stopMoving(mc);
            stage = Stage.CHOOSE;
            return;
        }
        status = "Scooting to spot";
        aim.precise();
        aim.setTarget(p.getYRot(), p.getXRot(), 100f, 1f); // hold the camera where it is
        if (clickPoint(mc.level, p, p.getEyePosition(), spot) != null) { // in nuke reach now
            stopMoving(mc);
            restartStuckTimer(now);
            aim.loose();
            enterDig(now);
            return;
        }
        double dx = spot.getX() + 0.5 - p.getX(), dz = spot.getZ() + 0.5 - p.getZ();
        double hd = Math.hypot(dx, dz);
        if (hd > Config.scootRange + 1.5 || now - scootStart > (long) (Config.scootSeconds * 1000)) {
            giveUpScoot(mc, now, "scoot time is up (" + Config.scootSeconds + " s) / moved away");
            return;
        }
        if (hd < scootBest - 0.05) {
            scootBest = hd;
            scootProgressAt = now;
        } else if (now - scootProgressAt > 700) {
            giveUpScoot(mc, now, "no progress (blocked?)");
            return;
        }
        // split the way to the spot into "forward/back" and "right/left" along where we face
        double yaw = Math.toRadians(p.getYRot());
        double fwd = dx * -Math.sin(yaw) + dz * Math.cos(yaw);
        double rgt = dx * -Math.cos(yaw) + dz * -Math.sin(yaw);
        double dead = 0.3;
        mc.options.keyUp.setDown(fwd > dead);
        mc.options.keyDown.setDown(fwd < -dead);
        mc.options.keyRight.setDown(rgt > dead);
        mc.options.keyLeft.setDown(rgt < -dead);
        mc.options.keySprint.setDown(false);
        mc.options.keyJump.setDown(false);
        mc.options.keyShift.setDown(false);
    }

    /** At the final point: the nuker ({@link #nukeNearby}) does the breaking; this stage waits for the spot to be gone. */
    private void dig(Minecraft mc, LocalPlayer p, long now) {
        stopMoving(mc);
        mc.options.keyShift.setDown(false);
        mc.options.keyAttack.setDown(false);
        BlockPos spot = goal.spot();
        if (spot == null || goalGone()) {
            stage = Stage.CHOOSE;
            return;
        }
        if (digTimedOut(mc, now)) return;
        if (clickPoint(mc.level, p, p.getEyePosition(), spot) == null) {
            status = "Spot not in reach from here";
            replan(mc, p, now, "spot not in reach from here");
            return;
        }
        status = "Nuking spot";
    }

    /**
     * The nuker: breaks ANY known spot within reach, not only the one we are heading for, one break every
     * {@link #NUKE_GAP_TICKS} ticks (nearest first). No aiming, no turning the head, no line-of-sight check. A spot
     * that is still there {@code DIG_LIMIT_MS} after the first break is blacklisted for the lobby.
     */
    private void nukeNearby(Minecraft mc, LocalPlayer p, long now) {
        ClientLevel lv = mc.level;
        if (lv == null || mc.gameMode == null || mc.screen != null) return;
        // spots we broke that are gone now: collected
        for (BlockPos s : new ArrayList<>(nukeFirst.keySet())) {
            boolean gone = !SpotScanner.SPOTS.containsKey(s) || !SpotScanner.alive(lv, s) || lv.getBlockState(s).isAir();
            if (!gone) continue;
            nukeFirst.remove(s);
            nukeLast.remove(s);
            if (SpotScanner.SPOTS.containsKey(s)) {
                spotsDone++;
                warpsSinceSpot = 0;
                Chat.log("Spot " + s.toShortString() + " collected");
                SpotScanner.remove(s);
            }
            skipUntil.put(s, now + 30_000);
            planFails.remove(s);
        }
        if (tickCount - lastNukeTick < NUKE_GAP_TICKS) return;
        Vec3 eye = p.getEyePosition();
        BlockPos pick = null;
        double pickD = Double.MAX_VALUE;
        for (BlockPos s : SpotScanner.SPOTS.keySet()) {
            if (SpotScanner.BLACKLIST.contains(s)) continue;
            double d = eye.distanceToSqr(s.getCenter());
            if (d > Config.reach * Config.reach || d >= pickD) continue;
            Long first = nukeFirst.get(s);
            if (first != null && now - first > DIG_LIMIT_MS) { // still there long after we broke it: give up on it
                SpotScanner.BLACKLIST.add(s);
                nukeFirst.remove(s);
                nukeLast.remove(s);
                Chat.msg("Spot " + s.toShortString() + " did not break within 2 s - blacklisted until the lobby changes.");
                continue;
            }
            Long last = nukeLast.get(s);
            if (last != null && now - last < NUKE_RETRY_MS) continue; // waiting for the server
            if (!SpotScanner.alive(lv, s)) continue; // strings gone: already used
            pickD = d;
            pick = s;
        }
        if (pick == null) return;
        nukeFirst.putIfAbsent(pick, now);
        nukeLast.put(pick, now);
        if (Config.useAxe && stage != Stage.WARP_PREP && stage != Stage.WARP_WAIT) { // hold the axe for breaking
            int ax = slotByName(p, Config.axeName);
            if (ax >= 0 && p.getInventory().getSelectedSlot() != ax) p.getInventory().setSelectedSlot(ax);
        }
        mc.gameMode.startDestroyBlock(pick, Direction.UP);
        p.swing(InteractionHand.MAIN_HAND);
        lastNukeTick = tickCount;
        Chat.log("Nuking spot " + pick.toShortString() + " from " + p.blockPosition().toShortString()
                + (goal != null && pick.equals(goal.spot()) ? " (target)" : " (other spot in reach)"));
    }

    /** Keeps breaking the spot block every tick until it is gone (or its strings are, or the 2 s are up). */
    private void digWait(Minecraft mc, LocalPlayer p, long now) {
        BlockPos spot = goal.spot();
        boolean strings = !SpotScanner.alive(mc.level, spot);
        boolean broken = mc.level.getBlockState(spot).isAir(); // the block is nuked
        if (strings || broken || lastFloorDrop >= digAt) {
            mc.gameMode.stopDestroyBlock();
            spotsDone++;
            warpsSinceSpot = 0;
            Chat.log("Spot " + spot.toShortString() + " collected (" + (broken ? "block broken" : strings ? "strings gone" : "floor drop message")
                    + ", " + (now - digAt) + " ms)");
            planFails.remove(spot);
            SpotScanner.remove(spot);
            skipUntil.put(spot, now + 30_000);
            stage = Stage.CHOOSE;
            return;
        }
        if (digTimedOut(mc, now)) return;
        // keep breaking it, camera untouched
        mc.gameMode.continueDestroyBlock(spot, Direction.UP);
        if (now - lastSwing > 200) {
            p.swing(InteractionHand.MAIN_HAND);
            lastSwing = now;
        }
        status = "Breaking spot block";
    }

    /**
     * Non-null when the spot block is within nuker reach of {@code eye}. No line-of-sight check: the break packet names
     * the block, it does not depend on where the player looks.
     */
    private static BlockHitResult clickPoint(Level level, LocalPlayer player, Vec3 eye, BlockPos spot) {
        if (eye.distanceToSqr(spot.getCenter()) > Config.reach * Config.reach) return null;
        return new BlockHitResult(spot.getCenter(), Direction.UP, spot, false);
    }

    // ---- diagnostics (/mudworm dev) -------------------------------------------------------------------------

    public WorldCache cache() {
        return cache;
    }

    private String describeGoal() {
        if (goal == null) return "none";
        return goal.spot() != null ? "spot " + goal.spot().toShortString()
                : "area " + BlockPos.containing(goal.center()).toShortString() + " r" + (int) goal.radius();
    }

    /** What the macro is thinking right now. */
    public String devState(Minecraft mc, long now) {
        StringBuilder b = new StringBuilder();
        b.append("running: ").append(running).append("   stage: ").append(stage).append("   status: ").append(status).append('\n');
        b.append("hops limited to (last plan): ").append(lastLimits == null ? "none yet" : lastLimits).append('\n');
        b.append("goal: ").append(describeGoal()).append("   patrolPoint: ").append(patrolPoint == null ? "none" : patrolPoint.toShortString()).append('\n');
        b.append("planner: ").append(planner == null ? "idle" : planner.state() + " (" + planner.expansions() + " expansions)").append('\n');
        b.append("last plan: ").append(lastPlanReport).append('\n');
        b.append("steps: ").append(steps.size()).append("   stepIdx: ").append(stepIdx).append('\n');
        int i = 0;
        for (Step st : steps) {
            if (i++ >= 12) {
                b.append("  ...\n");
                break;
            }
            b.append("  ").append(i - 1).append(": land ").append(st.feet().toShortString()).append(" target ")
                    .append(st.shot().target().toShortString()).append(String.format(" yaw %.2f pitch %.2f", st.shot().yaw(), st.shot().pitch())).append('\n');
        }
        b.append("warp prep: ").append(prepWhy).append("   warps: ").append(warps).append("   warpFails: ").append(warpFails)
                .append("   noManaFor: ").append(Math.max(0, noManaUntil - now)).append(" ms   etherPauseFor: ").append(Math.max(0, etherOffUntil - now)).append(" ms\n");
        b.append("shot: ").append(shot == null ? "none" : shot.target().toShortString() + String.format(" yaw %.2f pitch %.2f", shot.yaw(), shot.pitch())).append('\n');
        b.append("sneakSince: ").append(sneakSince == 0 ? "not sneaking" : (now - sneakSince) + " ms").append("   terrainWait: ").append(terrainWait).append('\n');
        b.append("stuck: still for ").append(moveRef == null ? 0 : now - moveRefAt).append(" ms (warps at ").append(Config.stuckSeconds * 1000)
                .append(" ms)   warping: ").append(warping).append("   warpsSinceSpot: ").append(warpsSinceSpot).append('\n');
        b.append("blacklisted spots: ").append(SpotScanner.BLACKLIST.size()).append(SpotScanner.BLACKLIST.isEmpty() ? "" : " " + SpotScanner.BLACKLIST)
                .append("   plan failures per spot: ").append(planFails).append('\n');
        b.append("axe: ").append(Config.useAxe ? "ON" : "off").append(" name '").append(Config.axeName).append("'")
                .append(Config.useAxe ? " (hotbar slot " + slotByName(Minecraft.getInstance().player, Config.axeName) + ")" : "")
                .append("   BLATANT mode: ").append(Config.blatant ? "ON" : "off").append('\n');
        b.append("scoot range: ").append(Config.scootRange).append(" blocks   scoot-blocked spots: ").append(scootBlocked.size()).append('\n');
        b.append("skipped spots: ").append(skipUntil.size()).append("   avoid cells: ").append(avoid.size()).append("   replans: ").append(replans).append('\n');
        b.append("done: ").append(spotsDone).append("   floor drops: ").append(floorDrops).append("   mudworm shards: ").append(shards).append('\n');
        return b.toString();
    }

    /** Runs the planner right now (blocking, up to 2.5 s) from the player's position and says what it finds. */
    public String devPlanTest(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) return "no player";
        int slot = aotvSlot(p);
        if (slot < 0) return "cannot test: no Aspect of the Void with Ether Transmission in the hotbar";
        BlockPos spot = nearestSpotIgnoringSkips(p);
        Pathfinder.Goal g;
        if (spot != null) g = Pathfinder.Goal.spot(spot);
        else {
            Vec3 c = new Vec3((Config.areaMinX + Config.areaMaxX) / 2.0, p.getY(), (Config.areaMinZ + Config.areaMaxZ) / 2.0);
            Vec3 me = p.position();
            if (c.distanceTo(me) > MAX_GOAL_DIST) c = me.add(c.subtract(me).normalize().scale(MAX_GOAL_DIST));
            g = Pathfinder.Goal.area(c, PATROL_RADIUS);
        }
        double range = Etherwarp.range(p.getInventory().getItem(slot));
        Vec3 eye = new Vec3(p.getX(), p.getY() + Etherwarp.SNEAK_EYE, p.getZ());
        Pathfinder pf = new Pathfinder(cache, p.blockPosition(), eye, g, range, new HashSet<>(), limitsFor(p, g),
                p.getYRot(), p.getXRot(), aimStep(mc));
        pf.run();
        StringBuilder b = new StringBuilder();
        b.append("test goal: ").append(g.spot() != null ? "nearest spot " + g.spot().toShortString()
                : "patrol area centre " + BlockPos.containing(g.center()).toShortString()).append('\n');
        b.append("result: ").append(pf.report()).append('\n');
        int i = 0;
        for (Step st : pf.result()) {
            b.append("  hop ").append(i++).append(": land ").append(st.feet().toShortString()).append(" target ")
                    .append(st.shot().target().toShortString()).append('\n');
        }
        return b.toString();
    }

    private BlockPos nearestSpotIgnoringSkips(LocalPlayer p) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos s : SpotScanner.SPOTS.keySet()) {
            if (SpotScanner.BLACKLIST.contains(s)) continue;
            double d = s.distToCenterSqr(p.position());
            if (d < bd) {
                bd = d;
                best = s;
            }
        }
        return best;
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private void faceGoalLoosely(LocalPlayer p) {
        if (goal == null) return;
        float[] a = Etherwarp.angles(p.getEyePosition(), goal.center());
        aim.setTarget(a[0], 8f, 400f, 0.8f);
    }

    /** Hotbar slot of the first item whose name contains {@code name} (case-insensitive, never the AOTV), or -1. */
    public static int slotByName(LocalPlayer p, String name) {
        if (name == null || name.isBlank()) return -1;
        String want = name.toLowerCase(Locale.ROOT).trim();
        int aotv = aotvSlot(p);
        for (int i = 0; i < 9; i++) {
            if (i == aotv) continue;
            ItemStack st = p.getInventory().getItem(i);
            if (!st.isEmpty() && st.getHoverName().getString().toLowerCase(Locale.ROOT).contains(want)) return i;
        }
        return -1;
    }

    /** Hotbar slot of an AOTE/AOTV with Ether Transmission, or -1. */
    public static int aotvSlot(LocalPlayer p) {
        for (int i = 0; i < 9; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.isEmpty()) continue;
            String name = st.getHoverName().getString().toLowerCase(Locale.ROOT);
            if ((name.contains("aspect of the void") || name.contains("aspect of the end")) && Etherwarp.hasEther(st)) return i;
        }
        return -1;
    }

    private static void stopMoving(Minecraft mc) {
        for (KeyMapping k : new KeyMapping[]{mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft,
                mc.options.keyRight, mc.options.keyJump, mc.options.keySprint}) k.setDown(false);
    }

    private static void release(Minecraft mc) {
        stopMoving(mc);
        mc.options.keyShift.setDown(false);
        mc.options.keyAttack.setDown(false);
    }
}
