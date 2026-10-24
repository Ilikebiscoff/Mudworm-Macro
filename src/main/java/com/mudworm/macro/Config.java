package com.mudworm.macro;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Settings, saved to config/mudworm-macro.properties. Changed with /mudworm commands. */
public final class Config {
    private Config() {}

    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("mudworm-macro.properties");

    /** Etherwarp range without tuners. The item's tuned_transmission is added on top (max 61). */
    public static int baseRange = 57;
    /** Blocks taken off the range so a target right at the edge is never tried. */
    public static double rangeMargin = 1.5;
    /** Cost of one hop when planning (fewest hops wins). Raise it to make the planner prefer short routes. */
    public static double etherCost = 10;
    /** Only air lets the etherwarp ray through (plants count as blocking). Safest, finds fewer hops. */
    public static boolean strictRay = false;
    /**
     * Half-angle (degrees) of the aim cone. The centre ray must stop at the planned block, and rays this far off in
     * every direction must still stop at a valid etherwarp target (never a wall). They may land a few blocks away from
     * the planned block (shallow angles over flat ground magnify tiny aim errors); the route is re-planned after landing.
     */
    public static double coneDeg = 0.1;
    /** Nuker reach: the spot block is broken from this far away (eye to block centre), no aiming needed. */
    public static double reach = 4.6;
    /** Draw boxes on spots and the planned path. */
    public static boolean esp = true;
    /** Show the status panel. */
    public static boolean hud = true;
    /** Chat output of every planning step. */
    public static boolean debug = false;
    /** Stop the macro when something teleports the player that was not our own etherwarp (staff check / warp). */
    public static boolean stopOnForeignTeleport = true;

    /** No real movement for this many seconds while the macro should be moving: run {@link #stuckCommand}. */
    public static boolean unstuckWarp = true;
    public static int stuckSeconds = 3;
    public static String stuckCommand = "warp murk";
    /**
     * Hops may only land in the corridor between where you stand and the goal: inside the box around both (plus
     * {@code areaMargin} blocks) and between their heights (minus {@code landBelow}, plus {@code landAbove}). This
     * keeps the macro off trees, cliffs and islands that are not on the way.
     */
    public static int landBelow = 10, landAbove = 12, areaMargin = 30;
    /**
     * Scoot: a visible spot this close (flat blocks) that is not in nuke reach yet is reached by holding W/A/S/D,
     * whatever combination points at it, without turning the head. 0 = off.
     */
    public static double scootRange = 6.0;
    /**
     * Only spots at most this many blocks away (3D, from you) are chosen as targets. Farther ones are ignored: the
     * game keeps their strings loaded from 100+ blocks away, and hopping towards them (or towards ones on another
     * level) looks like teleporting around for no reason.
     */
    public static double maxTargetDistance = 50;
    /**
     * Fewer than this many live spots in the lobby: refresh the lobby (/is, then /warp murk). 0 = never.
     * At least {@link #refreshCooldownSeconds} must have passed since the last lobby switch.
     */
    public static int minSpots = 3;
    public static int refreshCooldownSeconds = 45;
    /** How long one scoot (walking the last blocks) may last, in seconds. */
    public static double scootSeconds = 1.5;
    /** Use an axe for breaking the spot blocks: the hotbar item whose name contains {@link #axeName} is held while nuking. */
    public static boolean useAxe = false;
    public static String axeName = "Helix Chopper";
    /**
     * BLATANT MODE (never saved, off at every start): etherwarps send the aim to the server without moving the camera.
     * Turned on with a two-step confirmation: /mudworm blatant unlock, then /mudworm blatant on <phrase>.
     */
    public static volatile boolean blatant = false;
    /** Extra wait (ms) before every etherwarp once it is lined up. Slower hops cost less mana per minute. */
    public static int hopDelayMs = 250;
    /** Terrain snapshot radius in chunks around the player. */
    public static int cacheChunks = 7;

    /** Patrol area (x/z box). Default: Moonglade Marsh, from SkyHanni's island map (approximate). */
    public static int areaMinX = -708, areaMaxX = -603, areaMinZ = -46, areaMaxZ = 77;
    /** Distance between patrol points. */
    public static int patrolStep = 28;
    /** Vertical block range scanned for landing blocks. */
    public static int scanMinY = 60, scanMaxY = 200;

    public static void load() {
        if (!Files.exists(FILE)) {
            save();
            return;
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(FILE)) {
            p.load(r);
        } catch (IOException e) {
            return;
        }
        baseRange = i(p, "baseRange", baseRange);
        rangeMargin = d(p, "rangeMargin", rangeMargin);
        etherCost = d(p, "etherCost", etherCost);
        strictRay = b(p, "strictRay", strictRay);
        coneDeg = d(p, "coneDegrees", coneDeg);
        reach = d(p, "nukeReach", reach);
        esp = b(p, "esp", esp);
        hud = b(p, "hud", hud);
        debug = b(p, "debug", debug);
        stopOnForeignTeleport = b(p, "stopOnForeignTeleport", stopOnForeignTeleport);
        unstuckWarp = b(p, "unstuckWarp", unstuckWarp);
        stuckSeconds = Math.max(1, i(p, "stuckSeconds", stuckSeconds));
        stuckCommand = p.getProperty("stuckCommand", stuckCommand).trim();
        landBelow = Math.max(0, i(p, "landBelow", landBelow));
        landAbove = Math.max(0, i(p, "landAbove", landAbove));
        areaMargin = Math.max(0, i(p, "areaMargin", areaMargin));
        scootRange = Math.max(0, Math.min(30, d(p, "scootRange", scootRange)));
        maxTargetDistance = Math.max(10, Math.min(400, d(p, "maxTargetDistance", maxTargetDistance)));
        maxTargetDistance = Math.max(10, Math.min(400, d(p, "maxTargetDistance", maxTargetDistance)));
        minSpots = Math.max(0, Math.min(50, i(p, "minSpots", minSpots)));
        refreshCooldownSeconds = Math.max(5, Math.min(600, i(p, "refreshCooldown", refreshCooldownSeconds)));
        scootSeconds = Math.max(0.2, Math.min(10, d(p, "scootSeconds", scootSeconds)));
        useAxe = b(p, "useAxe", useAxe);
        axeName = p.getProperty("axeName", axeName).trim();
        hopDelayMs = Math.max(0, Math.min(5000, i(p, "hopDelay", hopDelayMs)));
        cacheChunks = Math.max(2, Math.min(12, i(p, "cacheChunks", cacheChunks)));
        areaMinX = i(p, "areaMinX", areaMinX);
        areaMaxX = i(p, "areaMaxX", areaMaxX);
        areaMinZ = i(p, "areaMinZ", areaMinZ);
        areaMaxZ = i(p, "areaMaxZ", areaMaxZ);
        patrolStep = Math.max(8, i(p, "patrolStep", patrolStep));
        scanMinY = i(p, "scanMinY", scanMinY);
        scanMaxY = i(p, "scanMaxY", scanMaxY);
    }

    public static void save() {
        Properties p = new Properties();
        p.setProperty("baseRange", "" + baseRange);
        p.setProperty("rangeMargin", "" + rangeMargin);
        p.setProperty("etherCost", "" + etherCost);
        p.setProperty("strictRay", "" + strictRay);
        p.setProperty("coneDegrees", "" + coneDeg);
        p.setProperty("nukeReach", "" + reach);
        p.setProperty("esp", "" + esp);
        p.setProperty("hud", "" + hud);
        p.setProperty("debug", "" + debug);
        p.setProperty("stopOnForeignTeleport", "" + stopOnForeignTeleport);
        p.setProperty("unstuckWarp", "" + unstuckWarp);
        p.setProperty("stuckSeconds", "" + stuckSeconds);
        p.setProperty("stuckCommand", stuckCommand);
        p.setProperty("landBelow", "" + landBelow);
        p.setProperty("landAbove", "" + landAbove);
        p.setProperty("areaMargin", "" + areaMargin);
        p.setProperty("scootRange", "" + scootRange);
        p.setProperty("maxTargetDistance", "" + maxTargetDistance);
        p.setProperty("maxTargetDistance", "" + maxTargetDistance);
        p.setProperty("minSpots", "" + minSpots);
        p.setProperty("refreshCooldown", "" + refreshCooldownSeconds);
        p.setProperty("scootSeconds", "" + scootSeconds);
        p.setProperty("useAxe", "" + useAxe);
        p.setProperty("axeName", axeName);
        p.setProperty("hopDelay", "" + hopDelayMs);
        p.setProperty("cacheChunks", "" + cacheChunks);
        p.setProperty("areaMinX", "" + areaMinX);
        p.setProperty("areaMaxX", "" + areaMaxX);
        p.setProperty("areaMinZ", "" + areaMinZ);
        p.setProperty("areaMaxZ", "" + areaMaxZ);
        p.setProperty("patrolStep", "" + patrolStep);
        p.setProperty("scanMinY", "" + scanMinY);
        p.setProperty("scanMaxY", "" + scanMaxY);
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer w = Files.newBufferedWriter(FILE)) {
                p.store(w, "Mudworm Macro settings");
            }
        } catch (IOException ignored) {
        }
    }

    private static int i(Properties p, String k, int def) {
        try {
            return Integer.parseInt(p.getProperty(k, "" + def).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double d(Properties p, String k, double def) {
        try {
            return Double.parseDouble(p.getProperty(k, "" + def).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean b(Properties p, String k, boolean def) {
        return Boolean.parseBoolean(p.getProperty(k, "" + def).trim());
    }
}
