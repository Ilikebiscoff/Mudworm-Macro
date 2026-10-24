package com.mudworm.macro;

import com.mudworm.macro.path.LandingIndex;
import com.mudworm.macro.path.Pathfinder;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The macro: pick the nearest spot (or a patrol point when none is known), plan a walk + etherwarp path to a cell
 * from which the spot can be clicked, follow it, and left-click the spot block.
 * <p>
 * Etherwarp safety: before every right-click the real look ray (from the real position, sneaking eye 1.27, the real
 * yaw/pitch) is traced again with {@link Etherwarp#robust}. If the first block it stops at is not the planned target
 * (a wall, a tree, a player-placed block, anything) the click is not sent and the path is planned again.
 */
public final class Macro {
    enum Stage { CHOOSE, PLAN, FOLLOW, WARP_PREP, WARP_WAIT, DIG, DIG_WAIT }

    public static final Macro INSTANCE = new Macro();

    private static final long PLAN_NANOS_PER_TICK = 5_000_000L;
    private static final long PLAN_TIMEOUT_MS = 5000;
    private static final long STUCK_MS = 2500;
    private static final long WARP_TIMEOUT_MS = 1500;
    private static final long SKIP_MS = 60_000;
    private static final long PATROL_REVISIT_MS = 120_000;
    private static final double PATROL_RADIUS = 10;
    /** Patrol goals further than this are pulled closer (the far end is not loaded yet). */
    private static final double MAX_GOAL_DIST = 90;

    public final HumanAim aim = new HumanAim();
    private final LandingIndex index = new LandingIndex();

    private boolean running;
    private Stage stage = Stage.CHOOSE;
    private String status = "Stopped";
    private ClientLevel startLevel;

    private Pathfinder.Goal goal;
    private Pathfinder planner;
    private long planStart;
    private List<Pathfinder.Step> steps = new ArrayList<>();
    private int stepIdx;
    private BlockPos patrolPoint;
    private int replans;

    private final Map<BlockPos, Long> skipUntil = new HashMap<>();
    private final Map<BlockPos, Long> patrolVisited = new HashMap<>();

    // walking
    private double bestDist;
    private long progressAt;

    // etherwarp
    private Etherwarp.Shot shot;
    private long prepSince, sneakSince, warpAt;
    private Vec3 warpFrom;
    private int warpFails;
    private long etherOffUntil, noManaUntil, nextSlotChange;

    // digging
    private BlockHitResult digHit;
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

    public List<Pathfinder.Step> steps() {
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
        warpFails = 0;
        aim.reset();
        aim.retarget();
        index.clear();
        Chat.msg("Started. Hypixel bans macros - you use this at your own risk.");
    }

    public void stop(Minecraft mc, String why) {
        if (!running) return;
        running = false;
        runMs += System.currentTimeMillis() - runStart;
        release(mc);
        planner = null;
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
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null) {
            stop(mc, "left the world");
            return;
        }
        if (mc.level != startLevel) {
            stop(mc, "the world changed (warp / lobby swap)");
            return;
        }
        long now = System.currentTimeMillis();
        Vec3 pos = p.position();
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

        switch (stage) {
            case CHOOSE -> choose(mc, p, now);
            case PLAN -> plan(mc, p, now);
            case FOLLOW -> follow(mc, p, now);
            case WARP_PREP -> warpPrep(mc, p, now);
            case WARP_WAIT -> warpWait(mc, p, now);
            case DIG -> dig(mc, p, now);
            case DIG_WAIT -> digWait(mc, p, now);
        }
    }

    // ---- choosing a goal ------------------------------------------------------------------------------------

    private void choose(Minecraft mc, LocalPlayer p, long now) {
        release(mc);
        aim.loose();
        replans = 0;
        BlockPos spot = nearestSpot(p);
        if (spot != null) {
            goal = Pathfinder.Goal.spot(spot);
            patrolPoint = null;
            if (Pathfinder.canClickFrom(mc.level, p, p.blockPosition(), spot)) {
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

    // ---- planning -------------------------------------------------------------------------------------------

    private void beginPlan(Minecraft mc, LocalPlayer p, long now) {
        int slot = aotvSlot(p);
        boolean ether = !Config.walkOnly && slot >= 0 && now >= etherOffUntil;
        double range = slot >= 0 ? Etherwarp.range(p.getInventory().getItem(slot)) : 0;
        Vec3 eye = new Vec3(p.getX(), p.getY() + Etherwarp.SNEAK_EYE, p.getZ());
        planner = new Pathfinder(mc.level, p, p.blockPosition(), eye, goal, range, ether, index);
        planStart = now;
        stage = Stage.PLAN;
        status = goal.spot() != null ? "Planning to spot" : "Planning patrol";
    }

    private void plan(Minecraft mc, LocalPlayer p, long now) {
        release(mc);
        if (goalGone()) {
            stage = Stage.CHOOSE;
            return;
        }
        faceGoalLoosely(p);
        Pathfinder.State st = planner.step(PLAN_NANOS_PER_TICK);
        if (st == Pathfinder.State.RUNNING && now - planStart < PLAN_TIMEOUT_MS) return;
        if (st == Pathfinder.State.FOUND) {
            steps = planner.result();
            stepIdx = 0;
            planner = null;
            resetProgress(now);
            int hops = 0;
            for (Pathfinder.Step s : steps) if (s.shot() != null) hops++;
            Chat.debug("Path: " + steps.size() + " steps, " + hops + " etherwarps (" + (now - planStart) + " ms)");
            stage = Stage.FOLLOW;
            return;
        }
        Chat.debug("No path (" + planner.expansions() + " nodes) - skipping this goal for a minute.");
        planner = null;
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
        if (goal.spot() != null && Pathfinder.clickPoint(mc.level, p, p.getEyePosition(), goal.spot()) != null) {
            release(mc);
            stage = Stage.DIG;
            digTries = 0;
            return;
        }
        if (stepIdx >= steps.size()) {
            if (goal.spot() != null) {
                stage = Stage.DIG;
                digTries = 0;
            } else {
                if (patrolPoint != null) patrolVisited.put(patrolPoint, now);
                stage = Stage.CHOOSE;
            }
            return;
        }
        Pathfinder.Step step = steps.get(stepIdx);
        if (step.shot() != null) { // we are on the cell to warp from
            release(mc);
            shot = step.shot();
            prepSince = now;
            sneakSince = 0;
            aim.precise();
            stage = Stage.WARP_PREP;
            return;
        }
        boolean nextIsWarp = stepIdx + 1 < steps.size() && steps.get(stepIdx + 1).shot() != null;
        boolean last = stepIdx + 1 >= steps.size();
        BlockPos t = step.feet();
        double hd = Math.hypot(t.getX() + 0.5 - p.getX(), t.getZ() + 0.5 - p.getZ());
        double dy = p.getY() - t.getY();
        double arrive = nextIsWarp ? 0.3 : last ? 0.5 : 0.75;
        if (hd < arrive && Math.abs(dy) < 0.8 && (!nextIsWarp || p.onGround())) {
            stepIdx++;
            resetProgress(now);
            if (nextIsWarp) release(mc);
            return;
        }
        if (hd < bestDist - 0.1) {
            bestDist = hd;
            progressAt = now;
        } else if (now - progressAt > STUCK_MS) {
            replan(mc, p, now, "stuck walking");
            return;
        }
        status = goal.spot() != null ? "Walking to spot" : "Patrolling";
        walkToward(mc, p, t, hd, nextIsWarp || last);
    }

    private void walkToward(Minecraft mc, LocalPlayer p, BlockPos t, double hd, boolean careful) {
        float[] a = Etherwarp.angles(p.getEyePosition(), new Vec3(t.getX() + 0.5, p.getEyeY(), t.getZ() + 0.5));
        aim.setTarget(a[0], 10f, 540f, 1f);
        float err = Math.abs(net.minecraft.util.Mth.wrapDegrees(a[0] - p.getYRot()));
        boolean go = err < 40f;
        mc.options.keyUp.setDown(go && !(careful && hd < 0.3));
        mc.options.keySprint.setDown(go && !careful && hd > 2.5 && err < 15f);
        boolean up = t.getY() > p.getY() + 0.5 && hd < 1.6;
        mc.options.keyJump.setDown(p.onGround() && (up || (p.horizontalCollision && go)));
        mc.options.keyShift.setDown(false);
    }

    private void resetProgress(long now) {
        bestDist = Double.MAX_VALUE;
        progressAt = now;
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
        double range = Etherwarp.range(p.getInventory().getItem(slot));
        Vec3 eye = new Vec3(p.getX(), p.getY() + Etherwarp.SNEAK_EYE, p.getZ());
        float yaw = p.getYRot(), pitch = p.getXRot();
        BlockPos hit = Etherwarp.predict(mc.level, eye, yaw, pitch, range);
        if (shot.target().equals(hit) && Etherwarp.robust(mc.level, eye, yaw, pitch, shot.target(), range)) {
            warpFrom = p.position();
            warpAt = now;
            mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
            warps++;
            stage = Stage.WARP_WAIT;
            status = "Etherwarp: teleporting";
            return;
        }
        // not exactly where the plan said (we stopped a bit off-centre): aim again from where we really are
        Etherwarp.Shot again = Etherwarp.shot(mc.level, eye, shot.target(), range);
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
        BlockPos spot = goal.spot();
        if (spot == null || goalGone()) {
            stage = Stage.CHOOSE;
            return;
        }
        BlockHitResult hit = Pathfinder.clickPoint(mc.level, p, p.getEyePosition(), spot);
        if (hit == null) {
            replan(mc, p, now, "spot not clickable from here");
            return;
        }
        status = "Clicking spot";
        float[] a = Etherwarp.angles(p.getEyePosition(), hit.getLocation());
        aim.setTarget(a[0], a[1], 700f, 1.2f);
        if (!aim.settled(p, 3f)) return;
        // the crosshair must really be on the spot block
        BlockHitResult now2 = Pathfinder.clickPoint(mc.level, p, p.getEyePosition(), spot);
        if (now2 == null) return;
        digHit = now2;
        mc.gameMode.startDestroyBlock(spot, digHit.getDirection());
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
    }
}
