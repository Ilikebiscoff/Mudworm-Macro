package com.mudworm.macro;

import com.mudworm.macro.path.Cell;
import com.mudworm.macro.path.Pathfinder;
import com.mudworm.macro.path.Pathfinder.Kind;
import com.mudworm.macro.path.Pathfinder.Step;
import com.mudworm.macro.path.WorldCache;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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
 * The macro: pick the nearest spot (or a patrol point when none is known), plan a walk + etherwarp path to a cell
 * from which the spot can be clicked, follow it, and left-click the spot block.
 * <ul>
 *   <li>Planning runs on its own thread over a terrain snapshot ({@link WorldCache}), so it never lags the game.</li>
 *   <li>Walking follows a smoothed path: straight stretches are one segment, corners are cut, W stays held.</li>
 *   <li>Azalea leaves in the way are broken.</li>
 *   <li>Every 3 s it checks for a closer spot and switches to it.</li>
 *   <li>No real movement for {@link Config#stuckSeconds} s: {@link Config#stuckCommand} (default /warp murk).</li>
 * </ul>
 * Etherwarp safety: before every right-click the real look ray (from the real position, sneaking eye 1.27, the real
 * yaw/pitch) is traced again with {@link Etherwarp#robust}. If the first block it stops at is not the planned target
 * (a wall, a tree, a player-placed block, anything) the click is not sent and the path is planned again.
 */
public final class Macro {
    enum Stage { CHOOSE, PLAN, FOLLOW, WARP_PREP, WARP_WAIT, DIG, DIG_WAIT }

    public static final Macro INSTANCE = new Macro();

    private static final long STUCK_WALK_MS = 1500;
    private static final long WARP_TIMEOUT_MS = 1500;
    private static final long SKIP_MS = 60_000;
    private static final long PATROL_REVISIT_MS = 120_000;
    private static final long CLOSER_CHECK_MS = 3000;
    /** A different spot must be this many blocks closer than the current goal before we switch. */
    private static final double CLOSER_MARGIN = 4.0;
    private static final double PATROL_RADIUS = 10;
    /** Patrol goals further than this are pulled closer (the far end is not loaded yet). */
    private static final double MAX_GOAL_DIST = 90;
    private static final long PLAN_TIMEOUT_MS = 6000;

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
    private Pathfinder planner;
    private long planStart;
    private volatile List<Step> steps = new ArrayList<>();
    private volatile int stepIdx;
    private BlockPos patrolPoint;
    private int replans;
    private final Set<Long> avoid = new HashSet<>();

    private final Map<BlockPos, Long> skipUntil = new HashMap<>();
    private final Map<BlockPos, Long> patrolVisited = new HashMap<>();
    private long lastCloserCheck;

    // walking
    private double bestDist;
    private long progressAt;
    private BlockPos leafTarget;

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
    private long prepSince, sneakSince, warpAt;
    private Vec3 warpFrom;
    private int warpFails;
    private long etherOffUntil, noManaUntil, nextSlotChange;

    // digging
    private long digAt;
    private int digTries;
    private long lastFloorDrop;

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
        if (aotvSlot(mc.player) < 0 && !Config.walkOnly) {
            Chat.msg("No Aspect of the Void with Ether Transmission in the hotbar - walking only.");
        }
        running = true;
        startLevel = mc.level;
        stage = Stage.CHOOSE;
        runStart = System.currentTimeMillis();
        lastPos = mc.player.position();
        skipUntil.clear();
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
        if (s.contains("FLOOR DROP!")) {
            floorDrops++;
            lastFloorDrop = System.currentTimeMillis();
            if (lower.contains("mudworm")) shards++;
        }
    }

    // ---- tick -----------------------------------------------------------------------------------------------

    public void tick(Minecraft mc) {
        if (!running) return;
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
        switch (stage) {
            case CHOOSE -> choose(mc, p, now);
            case PLAN -> plan(mc, p, now);
            case FOLLOW -> follow(mc, p, now);
            case WARP_PREP -> warpPrep(mc, p, now);
            case WARP_WAIT -> warpWait(mc, p, now);
            case DIG -> dig(mc, p, now);
            case DIG_WAIT -> digWait(mc, p, now);
        }
        if (running && Config.unstuckWarp && stuck(p, now)) stuckWarp(mc, p, now);
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

    private void stuckWarp(Minecraft mc, LocalPlayer p, long now) {
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
        Chat.msg("Did not move for " + Config.stuckSeconds + " s - /" + cmd);
    }

    // ---- choosing a goal ------------------------------------------------------------------------------------

    private void choose(Minecraft mc, LocalPlayer p, long now) {
        release(mc);
        aim.loose();
        replans = 0;
        if (!cache.has(p.blockPosition())) {
            terrainWait = true;
            status = "Scanning terrain";
            return;
        }
        BlockPos spot = nearestSpot(p);
        if (spot != null) {
            goal = Pathfinder.Goal.spot(spot);
            patrolPoint = null;
            if (clickPoint(mc.level, p, p.getEyePosition(), spot) != null) {
                stage = Stage.DIG;
                digTries = 0;
                return;
            }
            beginPlan(mc, p, now);
            return;
        }
        BlockPos pp = nextPatrolPoint(p, now);
        if (pp == null) {
            status = "No spots and nowhere to patrol";
            return;
        }
        patrolPoint = pp;
        Vec3 c = pp.getCenter();
        Vec3 me = p.position();
        if (c.distanceTo(me) > MAX_GOAL_DIST) c = me.add(c.subtract(me).normalize().scale(MAX_GOAL_DIST));
        goal = Pathfinder.Goal.area(c, PATROL_RADIUS);
        beginPlan(mc, p, now);
    }

    private BlockPos nearestSpot(LocalPlayer p) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos s : SpotScanner.SPOTS.keySet()) {
            if (skipUntil.containsKey(s)) continue;
            double d = s.distToCenterSqr(p.position());
            if (d < bd) {
                bd = d;
                best = s;
            }
        }
        return best;
    }

    /**
     * Every 3 s: is there a spot closer than the one we are heading to (or any spot while we only patrol)? Then go
     * there instead. The margin keeps it from flip-flopping between two spots at a similar distance.
     */
    private boolean closerSpotExists(LocalPlayer p, long now) {
        if (now - lastCloserCheck < CLOSER_CHECK_MS) return false;
        lastCloserCheck = now;
        BlockPos best = nearestSpot(p);
        if (best == null) return false;
        if (goal == null || goal.spot() == null) return true; // patrolling and a spot is known
        if (best.equals(goal.spot())) return false;
        double now2 = Math.sqrt(best.distToCenterSqr(p.position()));
        double cur = Math.sqrt(goal.spot().distToCenterSqr(p.position()));
        return now2 + CLOSER_MARGIN < cur;
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
        boolean ether = !Config.walkOnly && slot >= 0 && now >= etherOffUntil;
        double range = slot >= 0 ? Etherwarp.range(p.getInventory().getItem(slot)) : 0;
        Vec3 eye = new Vec3(p.getX(), p.getY() + Etherwarp.SNEAK_EYE, p.getZ());
        planner = new Pathfinder(cache, p.blockPosition(), eye, goal, range, ether, new HashSet<>(avoid));
        exec.execute(planner::run);
        planStart = now;
        stage = Stage.PLAN;
        status = goal.spot() != null ? "Planning to spot" : "Planning patrol";
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
        if (st == Pathfinder.State.FOUND) {
            steps = planner.result();
            stepIdx = 0;
            planner = null;
            resetProgress(now);
            lastCloserCheck = now;
            int hops = 0;
            for (Step s : steps) if (s.kind() == Kind.WARP) hops++;
            Chat.debug("Path: " + steps.size() + " steps, " + hops + " etherwarps (" + (now - planStart) + " ms)");
            stage = Stage.FOLLOW;
            return;
        }
        Chat.debug("No path (" + (planner == null ? 0 : planner.expansions()) + " nodes) - skipping this goal for a minute.");
        cancelPlan();
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
        return goal != null && goal.spot() != null && !SpotScanner.SPOTS.containsKey(goal.spot());
    }

    // ---- following the path ---------------------------------------------------------------------------------

    private void follow(Minecraft mc, LocalPlayer p, long now) {
        if (goalGone()) {
            stage = Stage.CHOOSE;
            return;
        }
        if (goal.spot() != null && clickPoint(mc.level, p, p.getEyePosition(), goal.spot()) != null) {
            release(mc);
            stage = Stage.DIG;
            digTries = 0;
            return;
        }
        List<Step> list = steps;
        if (stepIdx >= list.size()) {
            if (goal.spot() != null) {
                stage = Stage.DIG;
                digTries = 0;
            } else {
                if (patrolPoint != null) patrolVisited.put(patrolPoint, now);
                stage = Stage.CHOOSE;
            }
            return;
        }
        Step step = list.get(stepIdx);
        if (step.kind() == Kind.WARP) { // we are on the cell to warp from
            release(mc);
            shot = step.shot();
            prepSince = now;
            sneakSince = 0;
            aim.precise();
            stage = Stage.WARP_PREP;
            return;
        }
        // a closer spot showed up: go there instead (not while a warp is next, that needs a clean start)
        boolean nextIsWarp = stepIdx + 1 < list.size() && list.get(stepIdx + 1).kind() == Kind.WARP;
        if (!nextIsWarp && closerSpotExists(p, now)) {
            Chat.debug("A closer spot is known - switching to it.");
            release(mc);
            stage = Stage.CHOOSE;
            return;
        }

        Step next = stepIdx + 1 < list.size() ? list.get(stepIdx + 1) : null;
        boolean last = next == null;
        BlockPos t = step.feet();
        double hd = Math.hypot(t.getX() + 0.5 - p.getX(), t.getZ() + 0.5 - p.getZ());
        double dy = p.getY() - t.getY();
        double arrive = nextIsWarp ? 0.3 : last ? 0.5 : step.kind() == Kind.WALK ? 0.9 : 0.55;
        if (hd < arrive && Math.abs(dy) < 0.9 && (!nextIsWarp || p.onGround())) {
            stepIdx++;
            resetProgress(now);
            if (nextIsWarp) release(mc);
            return;
        }

        // leaves in the way: stop and break them
        BlockPos leaf = leafInTheWay(mc.level, p, t, hd);
        if (leaf != null) {
            breakLeaf(mc, p, leaf);
            status = "Breaking leaves";
            progressAt = now;
            return;
        }
        mc.options.keyAttack.setDown(false);
        leafTarget = null;

        if (hd < bestDist - 0.1) {
            bestDist = hd;
            progressAt = now;
        } else if (now - progressAt > STUCK_WALK_MS) {
            avoid.add(p.blockPosition().asLong());
            avoid.add(t.asLong());
            replan(mc, p, now, "stuck walking");
            return;
        }
        status = goal.spot() != null ? "Walking to spot" : "Patrolling";
        walkToward(mc, p, step, next, hd, nextIsWarp || last);
    }

    private void walkToward(Minecraft mc, LocalPlayer p, Step step, Step next, double hd, boolean careful) {
        BlockPos t = step.feet();
        Vec3 aimPt = new Vec3(t.getX() + 0.5, p.getEyeY(), t.getZ() + 0.5);
        // start turning into the next segment before the corner, so the path is a curve, not a polyline
        if (next != null && next.kind() == Kind.WALK && step.kind() == Kind.WALK && hd < 2.2) {
            BlockPos n = next.feet();
            aimPt = new Vec3(n.getX() + 0.5, p.getEyeY(), n.getZ() + 0.5);
        }
        float[] a = Etherwarp.angles(p.getEyePosition(), aimPt);
        aim.setTarget(a[0], 10f, 420f, 1f);
        float err = Math.abs(Mth.wrapDegrees(a[0] - p.getYRot()));
        boolean forward = err < 70f && !(careful && hd < 0.3);
        mc.options.keyUp.setDown(forward);
        mc.options.keySprint.setDown(forward && step.kind() == Kind.WALK && !careful && hd > 2.5 && err < 15f);
        boolean up = step.kind() == Kind.JUMP && hd < 1.5 && t.getY() > p.getY() + 0.5;
        mc.options.keyJump.setDown(p.onGround() && (up || (p.horizontalCollision && forward)));
        mc.options.keyShift.setDown(false);
    }

    private void resetProgress(long now) {
        bestDist = Double.MAX_VALUE;
        progressAt = now;
    }

    // ---- azalea leaves --------------------------------------------------------------------------------------

    private static boolean isLeaf(BlockState s) {
        return s.is(Blocks.AZALEA_LEAVES) || s.is(Blocks.FLOWERING_AZALEA_LEAVES);
    }

    /** An azalea leaf block we have to break to reach the next step (its cell, or right in front of us). */
    private BlockPos leafInTheWay(Level level, LocalPlayer p, BlockPos t, double hd) {
        if (!Config.breakLeaves || hd > 3.0) return null;
        BlockPos feet = p.blockPosition();
        // the step's own cell (feet and head height)
        for (BlockPos c : new BlockPos[]{t, t.above()}) {
            if (isLeaf(level.getBlockState(c)) && p.getEyePosition().distanceToSqr(c.getCenter()) < 4.5 * 4.5) return c;
        }
        // walking into something: the cell in front of us
        if (p.horizontalCollision) {
            Vec3 d = Etherwarp.dir(p.getYRot(), 0f);
            BlockPos front = BlockPos.containing(p.getX() + d.x * 0.7, p.getY(), p.getZ() + d.z * 0.7);
            if (!front.equals(feet)) {
                for (BlockPos c : new BlockPos[]{front, front.above()}) {
                    if (isLeaf(level.getBlockState(c))) return c;
                }
            }
        }
        return null;
    }

    private void breakLeaf(Minecraft mc, LocalPlayer p, BlockPos leaf) {
        mc.options.keyUp.setDown(false);
        mc.options.keySprint.setDown(false);
        mc.options.keyJump.setDown(false);
        float[] a = Etherwarp.angles(p.getEyePosition(), leaf.getCenter());
        aim.setTarget(a[0], a[1], 700f, 1.2f);
        if (!leaf.equals(leafTarget)) {
            leafTarget = leaf;
            aim.retargetFast();
        }
        // hold the attack button only while the crosshair is on a leaf, vanilla keeps breaking it
        boolean onLeaf = mc.hitResult instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK
                && isLeaf(mc.level.getBlockState(bhr.getBlockPos()));
        mc.options.keyAttack.setDown(onLeaf && aim.settled(p, 6f));
    }

    // ---- etherwarp ------------------------------------------------------------------------------------------

    private void warpPrep(Minecraft mc, LocalPlayer p, long now) {
        status = "Etherwarp: aiming";
        stopMoving(mc);
        int slot = aotvSlot(p);
        if (slot < 0) {
            etherOffUntil = now + 30_000;
            replan(mc, p, now, "no Aspect of the Void in the hotbar");
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
        aim.setTarget(shot.yaw(), shot.pitch(), 900f, 1.3f);
        if (now - prepSince > 3000) {
            replan(mc, p, now, "could not line up the etherwarp");
            return;
        }
        if (now < noManaUntil) {
            status = "Etherwarp: waiting for mana";
            return;
        }
        boolean still = p.onGround() && p.getDeltaMovement().horizontalDistanceSqr() < 1e-4;
        if (sneakSince == 0 || now - sneakSince < 200 || !still || nextSlotChange > now) return; // let the server see the sneak
        if (!aim.settled(p, 0.35f)) return;

        // the safety check: trace the REAL look ray from the REAL sneaking eye, exactly as the server will
        Cell.Live live = new Cell.Live(mc.level);
        double range = Etherwarp.range(p.getInventory().getItem(slot));
        Vec3 eye = new Vec3(p.getX(), p.getY() + Etherwarp.SNEAK_EYE, p.getZ());
        float yaw = p.getYRot(), pitch = p.getXRot();
        BlockPos hit = Etherwarp.predict(live, eye, yaw, pitch, range);
        if (shot.target().equals(hit) && Etherwarp.robust(live, eye, yaw, pitch, shot.target(), range)) {
            warpFrom = p.position();
            warpAt = now;
            mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
            warps++;
            stage = Stage.WARP_WAIT;
            status = "Etherwarp: teleporting";
            return;
        }
        // not exactly where the plan said (we stopped a bit off-centre): aim again from where we really are
        Etherwarp.Shot again = Etherwarp.shot(live, eye, shot.target(), range);
        if (again == null) {
            replan(mc, p, now, "target not visible from here");
            return;
        }
        shot = again;
    }

    private void warpWait(Minecraft mc, LocalPlayer p, long now) {
        stopMoving(mc);
        mc.options.keyShift.setDown(true);
        if (p.position().distanceTo(warpFrom) > 1.5) { // teleported
            mc.options.keyShift.setDown(false);
            aim.loose();
            lastPos = p.position();
            BlockPos land = shot.landing();
            double off = Math.hypot(land.getX() + 0.5 - p.getX(), land.getZ() + 0.5 - p.getZ());
            if (off > 1.5 || Math.abs(p.getY() - land.getY()) > 1.5) {
                replan(mc, p, now, "landed somewhere else");
                return;
            }
            warpFails = 0;
            stepIdx++;
            resetProgress(now);
            stage = Stage.FOLLOW;
            return;
        }
        if (now - warpAt > WARP_TIMEOUT_MS) {
            mc.options.keyShift.setDown(false);
            if (++warpFails >= 3) {
                warpFails = 0;
                etherOffUntil = now + 20_000;
                Chat.msg("Etherwarp did nothing 3 times (mana? item?) - walking for 20 s.");
            }
            replan(mc, p, now, "no teleport happened");
        }
    }

    // ---- clicking the spot ----------------------------------------------------------------------------------

    private void dig(Minecraft mc, LocalPlayer p, long now) {
        stopMoving(mc);
        mc.options.keyShift.setDown(false);
        mc.options.keyAttack.setDown(false);
        BlockPos spot = goal.spot();
        if (spot == null || goalGone()) {
            stage = Stage.CHOOSE;
            return;
        }
        BlockHitResult hit = clickPoint(mc.level, p, p.getEyePosition(), spot);
        if (hit == null) {
            replan(mc, p, now, "spot not clickable from here");
            return;
        }
        status = "Clicking spot";
        float[] a = Etherwarp.angles(p.getEyePosition(), hit.getLocation());
        aim.setTarget(a[0], a[1], 700f, 1.2f);
        if (!aim.settled(p, 3f)) return;
        // the crosshair must really be on the spot block
        BlockHitResult again = clickPoint(mc.level, p, p.getEyePosition(), spot);
        if (again == null) return;
        mc.gameMode.startDestroyBlock(spot, again.getDirection());
        p.swing(InteractionHand.MAIN_HAND);
        digAt = now;
        digTries++;
        stage = Stage.DIG_WAIT;
    }

    private void digWait(Minecraft mc, LocalPlayer p, long now) {
        if (now - digAt > 60) mc.gameMode.stopDestroyBlock();
        BlockPos spot = goal.spot();
        boolean gone = !SpotScanner.alive(mc.level, spot);
        if (gone || lastFloorDrop >= digAt) {
            spotsDone++;
            warpsSinceSpot = 0;
            SpotScanner.remove(spot);
            skipUntil.put(spot, now + 30_000);
            stage = Stage.CHOOSE;
            return;
        }
        if (now - digAt > 1200) {
            if (digTries >= 3) {
                Chat.debug("Spot did not react to 3 clicks - skipping it.");
                skipUntil.put(spot, now + SKIP_MS);
                stage = Stage.CHOOSE;
            } else {
                stage = Stage.DIG;
            }
        }
    }

    /** A point on the spot block that the crosshair can reach from {@code eye}, or null. */
    private static BlockHitResult clickPoint(Level level, LocalPlayer player, Vec3 eye, BlockPos spot) {
        Vec3[] aims = {new Vec3(spot.getX() + 0.5, spot.getY() + 0.95, spot.getZ() + 0.5), spot.getCenter()};
        for (Vec3 a : aims) {
            BlockHitResult hit = level.clip(new ClipContext(eye, a, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(spot)
                    && eye.distanceTo(hit.getLocation()) <= Config.reach) return hit;
        }
        return null;
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private void faceGoalLoosely(LocalPlayer p) {
        if (goal == null) return;
        float[] a = Etherwarp.angles(p.getEyePosition(), goal.center());
        aim.setTarget(a[0], 8f, 400f, 0.8f);
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
