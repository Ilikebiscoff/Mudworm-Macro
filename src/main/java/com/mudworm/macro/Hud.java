package com.mudworm.macro;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/** Status panel in the top left. */
final class Hud {
    private Hud() {}

    private static final int BG = 0xB0101418, HEADER = 0xD0182028, ACCENT = 0xFF30FF60;
    private static final int TITLE = 0xFFFFFFFF, LABEL = 0xFFA0A8B0, VALUE = 0xFFE8E8E8;

    static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        Macro m = Macro.INSTANCE;
        if (!Config.hud || mc.player == null || mc.options.hideGui) return;
        if (!m.running() && m.totalMs() == 0) return;
        var f = mc.font;
        long ms = m.totalMs();
        double hours = ms / 3_600_000.0;
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Status", m.status()});
        rows.add(new String[]{"Time", clock(ms)});
        rows.add(new String[]{"Spots in sight", "" + SpotScanner.SPOTS.size()});
        rows.add(new String[]{"Spots done", m.spotsDone + (hours > 0.01 ? "  (" + (int) (m.spotsDone / hours) + "/h)" : "")});
        rows.add(new String[]{"Floor drops", m.floorDrops + "  (" + m.shards + " mudworm)"});
        rows.add(new String[]{"Etherwarps", "" + m.warps});
        if (Config.blatant) rows.add(new String[]{"Mode", "BLATANT (silent aim)"});

        String title = "Mudworm Macro";
        int labelW = 0, valueW = 0;
        for (String[] r : rows) {
            labelW = Math.max(labelW, f.width(r[0]));
            valueW = Math.max(valueW, f.width(r[1]));
        }
        int pad = 6, gap = 10, lineH = 11, headerH = 16;
        int w = Math.max(f.width(title), labelW + gap + valueW) + pad * 2;
        int h = headerH + rows.size() * lineH + pad;
        int x = 4, y = 4;
        g.fill(x, y, x + w, y + h, BG);
        g.fill(x, y, x + w, y + headerH, HEADER);
        g.fill(x, y, x + 2, y + h, m.running() ? ACCENT : 0xFFFF6B6B);
        g.text(f, title, x + pad, y + 4, TITLE, false);
        int ty = y + headerH + 3;
        for (String[] r : rows) {
            g.text(f, r[0], x + pad, ty, LABEL, false);
            g.text(f, r[1], x + w - pad - f.width(r[1]), ty, VALUE, false);
            ty += lineH;
        }
    }

    private static String clock(long ms) {
        long s = ms / 1000;
        return String.format("%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }
}
