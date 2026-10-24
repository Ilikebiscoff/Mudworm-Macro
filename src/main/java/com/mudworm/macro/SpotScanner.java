package com.mudworm.macro;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds Galatea floor drops ("mudworm spots"). A spot is a block holding three item display entities that show
 * {@code minecraft:string}, with {@code happy_villager} particles above it (the signals Skyblocker's Floor Drop
 * Highlighter uses).
 * <p>
 * "Scanning the whole island": every half second all item displays the client knows about are grouped by block, so
 * every spot inside the server's entity range is found, not only the ones close enough to send particles. The server
 * only sends entities near the player, so the rest of the island is covered by patrolling, and every block a spot was
 * ever seen on is remembered in {@code config/mudworm-sites.txt} so later patrols visit those first.
 */
public final class SpotScanner {
    private SpotScanner() {}

    /** Strings shown by one spot. */
    private static final int STRINGS = 3;
    /** Inside this range a spot must keep its strings, or it is gone. */
    private static final double CONFIRM_RANGE = 40;
    private static final long GONE_MS = 4000;
    /** Out of range we cannot see a spot, so it is forgotten after this long. */
    private static final long FAR_FORGET_MS = 180_000;

    public static final class Spot {
        public final BlockPos pos;
        public long lastSeen;

        Spot(BlockPos pos, long now) {
            this.pos = pos;
            this.lastSeen = now;
        }
    }

    /** Live spots. Only touched on the game thread. */
    public static final Map<BlockPos, Spot> SPOTS = new LinkedHashMap<>();
    /** Every block a spot has ever been seen on (patrol targets). */
    public static final Set<BlockPos> SITES = new HashSet<>();

    private static final Path SITES_FILE = FabricLoader.getInstance().getConfigDir().resolve("mudworm-sites.txt");
    private static boolean sitesDirty;
    private static long lastScan, lastSave;
    private static ClientLevel lastLevel;

    /** From the packet mixin (game thread). Adds the spot under a happy_villager particle once its strings check out. */
    public static void onParticle(ClientboundLevelParticlesPacket packet) {
        if (packet.getParticle().getType() != ParticleTypes.HAPPY_VILLAGER) return;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        BlockPos pos = BlockPos.containing(packet.getX(), packet.getY() - 1, packet.getZ());
        if (countStrings(level, pos) >= STRINGS) seen(pos, System.currentTimeMillis());
    }

    public static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level != lastLevel) { // new lobby / island: everything we knew is stale
            SPOTS.clear();
            lastLevel = level;
        }
        if (level == null || mc.player == null) return;
        long now = System.currentTimeMillis();
        if (now - lastScan >= 500) {
            lastScan = now;
            scanEntities(level, now);
            expire(mc, now);
        }
        if (sitesDirty && now - lastSave > 10_000) saveSites();
    }

    /** Groups every loaded string item display by block. */
    private static void scanEntities(ClientLevel level, long now) {
        Map<BlockPos, Integer> count = new HashMap<>();
        for (Entity e : level.entitiesForRendering()) {
            if (!(e instanceof Display.ItemDisplay d) || !isString(d)) continue;
            count.merge(BlockPos.containing(d.getX(), d.getY(), d.getZ()), 1, Integer::sum);
        }
        for (var en : count.entrySet()) {
            if (en.getValue() >= STRINGS) seen(en.getKey(), now);
        }
    }

    private static void expire(Minecraft mc, long now) {
        Vec3 me = mc.player.position();
        for (Iterator<Spot> it = SPOTS.values().iterator(); it.hasNext(); ) {
            Spot s = it.next();
            boolean near = me.distanceTo(s.pos.getCenter()) < CONFIRM_RANGE;
            if (near && now - s.lastSeen > GONE_MS) it.remove();
            else if (!near && now - s.lastSeen > FAR_FORGET_MS) it.remove();
        }
    }

    private static void seen(BlockPos pos, long now) {
        Spot s = SPOTS.get(pos);
        if (s == null) {
            SPOTS.put(pos, new Spot(pos, now));
            Chat.debug("Spot found at " + pos.toShortString());
        } else {
            s.lastSeen = now;
        }
        if (SITES.add(pos)) sitesDirty = true;
    }

    /** Number of string displays inside the block (Skyblocker's check). */
    public static int countStrings(ClientLevel level, BlockPos pos) {
        List<Display.ItemDisplay> list = level.getEntitiesOfClass(Display.ItemDisplay.class,
                AABB.ofSize(Vec3.atCenterOf(pos), 1.0, 1.0, 1.0), e -> true);
        int n = 0;
        for (Display.ItemDisplay d : list) if (isString(d)) n++;
        return n;
    }

    /** True while the spot still shows its strings (false once it was used). */
    public static boolean alive(ClientLevel level, BlockPos pos) {
        return countStrings(level, pos) >= STRINGS;
    }

    private static boolean isString(Display.ItemDisplay d) {
        var state = d.itemRenderState();
        return state != null && !state.itemStack().isEmpty() && state.itemStack().is(Items.STRING);
    }

    public static void remove(BlockPos pos) {
        SPOTS.remove(pos);
    }

    // ---- remembered sites -----------------------------------------------------------------------------------

    public static void loadSites() {
        if (!Files.exists(SITES_FILE)) return;
        try {
            for (String line : Files.readAllLines(SITES_FILE)) {
                String[] p = line.trim().split(",");
                if (p.length != 3) continue;
                try {
                    SITES.add(new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])));
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
    }

    public static void saveSites() {
        sitesDirty = false;
        lastSave = System.currentTimeMillis();
        List<String> lines = new ArrayList<>();
        for (BlockPos b : SITES) lines.add(b.getX() + "," + b.getY() + "," + b.getZ());
        try {
            Files.write(SITES_FILE, lines);
        } catch (IOException ignored) {
        }
    }

    public static void clearSites() {
        SITES.clear();
        saveSites();
    }
}
