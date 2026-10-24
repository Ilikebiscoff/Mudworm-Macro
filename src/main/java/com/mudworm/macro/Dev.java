package com.mudworm.macro;

import com.mudworm.macro.path.Cell;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@code /mudworm dev}: collects everything needed to find out why the macro does not do what it should, copies it to
 * the clipboard, writes it to config/mudworm-dev.txt, and prints a short summary in chat.
 */
final class Dev {
    private Dev() {}

    static void run(Minecraft mc) {
        String text;
        try {
            text = build(mc);
        } catch (Throwable t) {
            StringBuilder sb = new StringBuilder("dev dump failed: " + t + "\n");
            for (StackTraceElement e : t.getStackTrace()) sb.append("  at ").append(e).append('\n');
            text = sb.toString();
        }
        Path file = FabricLoader.getInstance().getConfigDir().resolve("mudworm-dev.txt");
        try {
            Files.writeString(file, text);
        } catch (IOException ignored) {
        }
        try {
            mc.keyboardHandler.setClipboard(text);
        } catch (Throwable ignored) {
        }
        Chat.msg("Dev info copied to the clipboard (paste it into the chat with Claude) and saved to " + file + ".");
        int shown = 0;
        for (String line : text.split("\n")) {
            if (line.startsWith("== ") || line.startsWith("stage:") || line.startsWith("running:") || line.startsWith("last plan:")
                    || line.startsWith("result:") || line.startsWith("PROBLEM")) {
                Chat.msg("§7" + (line.length() > 230 ? line.substring(0, 230) + "..." : line));
                if (++shown > 14) break;
            }
        }
    }

    private static String build(Minecraft mc) {
        long now = System.currentTimeMillis();
        StringBuilder b = new StringBuilder();
        var p = mc.player;
        var lv = mc.level;
        Macro m = Macro.INSTANCE;
        List<String> problems = new ArrayList<>();

        b.append("== Mudworm Macro dev dump ==\n");
        b.append("mod ").append(Updater.currentVersion()).append("   MC ").append(FabricLoader.getInstance().getModContainer("minecraft")
                        .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?"))
                .append("   fabric loader ").append(FabricLoader.getInstance().getModContainer("fabricloader")
                        .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?")).append('\n');
        if (p == null || lv == null) return b.append("not in a world\n").toString();

        b.append("\n== Player ==\n");
        b.append(String.format("pos %.2f %.2f %.2f  block %s  yaw %.2f pitch %.2f\n", p.getX(), p.getY(), p.getZ(),
                p.blockPosition().toShortString(), p.getYRot(), p.getXRot()));
        b.append("onGround ").append(p.onGround()).append("  sneaking(player) ").append(p.isShiftKeyDown())
                .append("  sneakKeyDown ").append(mc.options.keyShift.isDown()).append("  screen ")
                .append(mc.screen == null ? "none" : mc.screen.getClass().getSimpleName()).append('\n');
        b.append("selected hotbar slot ").append(p.getInventory().getSelectedSlot()).append('\n');
        b.append("sidebar:\n");
        List<String> side = sidebar(mc);
        if (side == null) b.append("  (none)\n");
        else for (String l : side) b.append("  ").append(l).append('\n');
        boolean galatea = side != null && side.stream().anyMatch(l -> l.toLowerCase().contains("galatea"));
        if (!galatea) problems.add("sidebar does not mention Galatea - are you on Galatea?");

        b.append("\n== Hotbar ==\n");
        int aotv = Macro.aotvSlot(p);
        for (int i = 0; i < 9; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.isEmpty()) continue;
            b.append(i).append(": ").append(st.getHoverName().getString()).append("  ether=").append(Etherwarp.hasEther(st))
                    .append(i == aotv ? "  <- used for etherwarp" : "").append('\n');
        }
        if (aotv < 0) {
            problems.add("no Aspect of the Void / Aspect of the End with ethermerge==1 found in the hotbar by name+tag (see custom data below)");
            for (int i = 0; i < 9; i++) {
                ItemStack st = p.getInventory().getItem(i);
                String n = st.isEmpty() ? "" : st.getHoverName().getString().toLowerCase();
                if (n.contains("aspect of the")) {
                    CustomData cd = st.get(DataComponents.CUSTOM_DATA);
                    String raw = cd == null ? "(no custom data)" : cd.copyTag().toString();
                    b.append("custom data of slot ").append(i).append(": ").append(raw.length() > 900 ? raw.substring(0, 900) + "..." : raw).append('\n');
                }
            }
        } else {
            ItemStack st = p.getInventory().getItem(aotv);
            CustomData cd = st.get(DataComponents.CUSTOM_DATA);
            String raw = cd == null ? "(no custom data)" : cd.copyTag().toString();
            b.append("etherwarp range used: ").append(String.format("%.1f", Etherwarp.range(st))).append("   custom data: ")
                    .append(raw.length() > 500 ? raw.substring(0, 500) + "..." : raw).append('\n');
        }

        b.append("\n== Spots ==\n");
        b.append("live spots: ").append(SpotScanner.SPOTS.size()).append("   remembered sites: ").append(SpotScanner.SITES.size()).append('\n');
        List<BlockPos> spots = new ArrayList<>(SpotScanner.SPOTS.keySet());
        spots.sort(Comparator.comparingDouble(s -> s.distToCenterSqr(p.position())));
        for (int i = 0; i < Math.min(8, spots.size()); i++) {
            BlockPos s = spots.get(i);
            b.append("  ").append(s.toShortString()).append(String.format("  %.1f blocks away  strings=%d\n",
                    Math.sqrt(s.distToCenterSqr(p.position())), SpotScanner.countStrings(lv, s)));
        }
        if (spots.isEmpty()) problems.add("no spots known: the macro can only patrol. Stand near a spot and check /mudworm spots");

        b.append("\n== Terrain snapshot ==\n");
        var cache = m.cache();
        cache.warm(mc, 1500); // the snapshot is only kept up to date while the macro runs: fill it now (freezes briefly)
        b.append("columns cached: ").append(cache.columns()).append("   column under you: ").append(cache.has(p.blockPosition())).append('\n');
        if (!cache.has(p.blockPosition())) problems.add("terrain snapshot has no column under you (macro waits on 'Scanning terrain')");
        var rd = cache.reader();
        BlockPos fp = p.blockPosition();
        b.append("cells at feet-1 / feet / head: ").append(flags(rd.at(fp.getX(), fp.getY() - 1, fp.getZ()))).append(" | ")
                .append(flags(rd.at(fp.getX(), fp.getY(), fp.getZ()))).append(" | ")
                .append(flags(rd.at(fp.getX(), fp.getY() + 1, fp.getZ()))).append('\n');
        long[] here = rd.targets(p.getBlockX() >> 4, p.getBlockZ() >> 4);
        b.append("etherwarp landing blocks in your chunk: ").append(here == null ? "chunk not cached" : "" + here.length).append('\n');

        b.append("\n== Look ray right now ==\n");
        Cell.Live live = new Cell.Live(lv);
        Vec3 eye = new Vec3(p.getX(), p.getY() + Etherwarp.SNEAK_EYE, p.getZ());
        double range = aotv >= 0 ? Etherwarp.range(p.getInventory().getItem(aotv)) : Config.baseRange - Config.rangeMargin;
        BlockPos stop = Etherwarp.firstStop(live, eye, Etherwarp.dir(p.getYRot(), p.getXRot()), range, false);
        b.append("sneak eye ").append(String.format("%.2f %.2f %.2f", eye.x, eye.y, eye.z)).append("   ray stops at ")
                .append(stop == null ? "nothing within " + (int) range + " blocks (or unloaded)" : stop.toShortString()
                        + " (" + lv.getBlockState(stop).getBlock().getName().getString() + ", " + flags(live.at(stop.getX(), stop.getY(), stop.getZ())) + ")")
                .append("   valid etherwarp target: ").append(stop != null && Etherwarp.validTarget(live, stop.getX(), stop.getY(), stop.getZ())).append('\n');

        b.append("\n== Macro ==\n");
        b.append(m.devState(mc, now));
        if (!m.running()) problems.add("macro is not running (press K or /mudworm start)");

        b.append("\n== Planner test (runs the planner now from your position) ==\n");
        b.append(m.devPlanTest(mc));

        b.append("\n== Settings ==\n");
        b.append("baseRange ").append(Config.baseRange).append("  rangeMargin ").append(Config.rangeMargin).append("  etherCost ").append(Config.etherCost)
                .append("  strictRay ").append(Config.strictRay).append("  coneDeg ").append(Config.coneDeg).append("  reach ").append(Config.reach).append('\n');
        b.append("stuck: ").append(Config.unstuckWarp).append(" after ").append(Config.stuckSeconds).append(" s -> /").append(Config.stuckCommand)
                .append("  cacheChunks ").append(Config.cacheChunks).append("  scanY ").append(Config.scanMinY).append("..").append(Config.scanMaxY).append('\n');
        b.append("patrol area x ").append(Config.areaMinX).append("..").append(Config.areaMaxX).append(" z ").append(Config.areaMinZ).append("..").append(Config.areaMaxZ)
                .append(" step ").append(Config.patrolStep).append("  failsafe(foreign teleport) ").append(Config.stopOnForeignTeleport).append('\n');
        if (p.getY() < Config.scanMinY || p.getY() > Config.scanMaxY)
            problems.add("your Y (" + (int) p.getY() + ") is outside the scanned range " + Config.scanMinY + ".." + Config.scanMaxY);

        b.append("\n== Recent events (oldest first) ==\n");
        for (String l : Chat.recent()) b.append(l).append('\n');

        StringBuilder head = new StringBuilder();
        for (String pr : problems) head.append("PROBLEM: ").append(pr).append('\n');
        return head + b.toString();
    }

    private static String flags(short f) {
        if (f == 0) return "unknown";
        StringBuilder s = new StringBuilder();
        String[] names = {"LOADED", "AIR", "RAYPASS", "TARGET", "COLL", "LOW", "TALL", "FLUID", "HAZARD", "SLOW", "LEAF"};
        for (int i = 0; i < names.length; i++) if ((f & (1 << i)) != 0) s.append(names[i]).append(' ');
        return s.toString().trim();
    }

    private static List<String> sidebar(Minecraft mc) {
        if (mc.level == null) return null;
        Scoreboard sb = mc.level.getScoreboard();
        Objective obj = sb.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (obj == null) return null;
        List<String> out = new ArrayList<>();
        for (PlayerScoreEntry e : sb.listPlayerScores(obj)) {
            if (e.isHidden()) continue;
            PlayerTeam team = sb.getPlayersTeam(e.owner());
            String line = team == null ? e.ownerName().getString()
                    : PlayerTeam.formatNameForTeam(team, e.ownerName()).getString();
            out.add(line.replaceAll("§.", "").trim());
        }
        return out;
    }
}
