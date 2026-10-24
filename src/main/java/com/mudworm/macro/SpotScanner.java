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
        /** The solid block to click (the ground under the strings). */
        public final BlockPos pos;
        /** The block cell the string displays are in (the same, or the air cell above the ground). */
        public BlockPos cube;
        public long lastSeen;

        Spot(BlockPos pos, BlockPos cube, long now) {
            this.pos = pos;
            this.cube = cube;
            this.lastSeen = now;
        }
    }

    /** Live spots. Only touched on the game thread. */
    public static final Map<BlockPos, Spot> SPOTS = new LinkedHashMap<>();
    /** Spots the macro could not get in 2 s. Forgotten when the lobby changes. */
    public static final Set<BlockPos> BLACKLIST = new HashSet<>();
    /** Every block a spot has ever been seen on (patrol targets). */
    public static final Set<BlockPos> SITES = new HashSet<>();

    private static final Path SITES_FILE = FabricLoader.getInstance().getConfigDir().resolve("mudworm-sites.txt");
    private static boolean sitesDirty;
    private static long lastScan, lastSave;
    private static ClientLevel lastLevel;
    private static String lobbyId;
    private static long lastLobbyCheck;
    private static final java.util.regex.Pattern SERVER_ID = java.util.regex.Pattern.compile("\\d{1,2}/\\d{1,2}/\\d{2,4}\\s+(\\S+)");

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
        if (level != lastLevel) { // new world object: spots are found again from the entities. The blacklist stays
            if (!SPOTS.isEmpty()) Chat.log("Spot list cleared: world object changed (blacklist kept until the lobby changes)");
            SPOTS.clear();
            lastLevel = level;
        }
        if (level == null || mc.player == null) return;
        long now = System.currentTimeMillis();
        if (now - lastLobbyCheck > 1000) {
            lastLobbyCheck = now;
            checkLobby(mc);
        }
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

    /** Forget the spots and the blacklist (new lobby or world). */
    public static volatile int resets;

    public static void reset(String why) {
        resets++;
        if (!SPOTS.isEmpty() || !BLACKLIST.isEmpty()) Chat.log("Spots and blacklist cleared: " + why);
        SPOTS.clear();
        BLACKLIST.clear();
    }

    /** The lobby id is the short code next to the date on the sidebar (e.g. "10/14/26 m195H"). */
    private static void checkLobby(Minecraft mc) {
        List<String> lines = sidebar(mc);
        if (lines == null) return;
        for (String l : lines) {
            var m = SERVER_ID.matcher(l);
            if (!m.find()) continue;
            String id = m.group(1);
            if (lobbyId != null && !lobbyId.equals(id)) reset("lobby " + lobbyId + " -> " + id);
            lobbyId = id;
            return;
        }
    }

    public static List<String> sidebar(Minecraft mc) {
        if (mc.level == null) return null;
        var sb = mc.level.getScoreboard();
        var obj = sb.getDisplayObjective(net.minecraft.world.scores.DisplaySlot.SIDEBAR);
        if (obj == null) return null;
        List<String> out = new ArrayList<>();
        for (var e : sb.listPlayerScores(obj)) {
            if (e.isHidden()) continue;
            var team = sb.getPlayersTeam(e.owner());
            String line = team == null ? e.ownerName().getString()
                    : net.minecraft.world.scores.PlayerTeam.formatNameForTeam(team, e.ownerName()).getString();
            out.add(line.replaceAll("§.", "").trim());
        }
        return out;
    }

    /** The solid block at or just under the cell that holds the strings: that is the block to click. */
    private static BlockPos groundOf(ClientLevel level, BlockPos cube) {
        for (int i = 0; i <= 2; i++) {
            BlockPos c = cube.below(i);
            if (!level.getBlockState(c).getCollisionShape(level, c).isEmpty()) return c;
        }
        return null;
    }

    private static void seen(BlockPos cube, long now) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        BlockPos ground = groundOf(level, cube);
        if (ground == null) return;
        Spot s = SPOTS.get(ground);
        if (s == null) {
            SPOTS.put(ground, new Spot(ground, cube, now));
            Chat.debug("Spot found at " + ground.toShortString() + (ground.equals(cube) ? "" : " (strings at " + cube.toShortString() + ")"));
        } else {
            s.lastSeen = now;
            s.cube = cube;
        }
        if (SITES.add(ground)) sitesDirty = true;
    }

    /** Number of string displays inside the block (Skyblocker's check). */
    public static int countStrings(ClientLevel level, BlockPos pos) {
        List<Display.ItemDisplay> list = level.getEntitiesOfClass(Display.ItemDisplay.class,
                AABB.ofSize(Vec3.atCenterOf(pos), 1.0, 1.0, 1.0), e -> true);
        int n = 0;
        for (Display.ItemDisplay d : list) if (isString(d)) n++;
        return n;
    }

    /** Strings shown by the spot whose ground block is {@code ground}. */
    public static int stringsOf(ClientLevel level, BlockPos ground) {
        Spot s = SPOTS.get(ground);
        return countStrings(level, s != null ? s.cube : ground);
    }

    /** True while the spot still shows its strings (false once it was used). */
    public static boolean alive(ClientLevel level, BlockPos ground) {
        return stringsOf(level, ground) >= STRINGS;
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
