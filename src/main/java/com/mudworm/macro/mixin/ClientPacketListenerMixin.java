package com.mudworm.macro.mixin;

import com.mudworm.macro.SpotScanner;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sees every particle packet. Hooked at RETURN: the network-thread call bounces to the game thread by throwing,
 * so this only runs once, on the game thread.
 */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {
    @Inject(method = "handleParticleEvent", at = @At("RETURN"))
    private void mudwormmacro$onParticle(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
        SpotScanner.onParticle(packet);
    }
}
