package com.mudworm.macro;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Chat lines from the mod. */
public final class Chat {
    private Chat() {}

    public static void msg(String s) {
        var mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal("§2[Mudworm] §f" + s));
    }

    public static void debug(String s) {
        if (Config.debug) msg("§7" + s);
    }
}
