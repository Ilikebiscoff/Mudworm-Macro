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
    /** How far (degrees) the aim may be off and every ray in that cone must still hit the same block. */
    public static double jitterDeg = 0.6;
    /** Reach used to click a spot. */
    public static double reach = 4.0;
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
        jitterDeg = d(p, "jitterDeg", jitterDeg);
        reach = d(p, "reach", reach);
        esp = b(p, "esp", esp);
        hud = b(p, "hud", hud);
        debug = b(p, "debug", debug);
        stopOnForeignTeleport = b(p, "stopOnForeignTeleport", stopOnForeignTeleport);
        unstuckWarp = b(p, "unstuckWarp", unstuckWarp);
        stuckSeconds = Math.max(1, i(p, "stuckSeconds", stuckSeconds));
        stuckCommand = p.getProperty("stuckCommand", stuckCommand).trim();
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
        p.setProperty("jitterDeg", "" + jitterDeg);
        p.setProperty("reach", "" + reach);
        p.setProperty("esp", "" + esp);
        p.setProperty("hud", "" + hud);
        p.setProperty("debug", "" + debug);
        p.setProperty("stopOnForeignTeleport", "" + stopOnForeignTeleport);
        p.setProperty("unstuckWarp", "" + unstuckWarp);
        p.setProperty("stuckSeconds", "" + stuckSeconds);
        p.setProperty("stuckCommand", stuckCommand);
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
