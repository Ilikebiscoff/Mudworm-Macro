package com.mudworm.macro;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Chat lines from the mod, plus a ring buffer of recent events that /mudworm dev prints. */
public final class Chat {
    private Chat() {}

    private static final int KEEP = 80;
    private static final ArrayDeque<String> LOG = new ArrayDeque<>();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    public static void msg(String s) {
        log(s);
        var mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal("§2[Mudworm] §f" + s));
    }

    /** Always recorded for /mudworm dev; only shown in chat with /mudworm debug. */
    public static void debug(String s) {
        log(s);
        if (!Config.debug) return;
        var mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal("§2[Mudworm] §7" + s));
    }

    /** Record an event without showing it. */
    public static synchronized void log(String s) {
        if (LOG.size() >= KEEP) LOG.removeFirst();
        LOG.addLast(LocalTime.now().format(TIME) + "  " + s.replaceAll("§.", ""));
    }

    public static synchronized List<String> recent() {
        return new ArrayList<>(LOG);
    }
}
