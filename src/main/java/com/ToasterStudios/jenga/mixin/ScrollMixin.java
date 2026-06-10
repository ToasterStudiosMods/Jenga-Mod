package com.ToasterStudios.jenga.mixin;

import com.ToasterStudios.jenga.JengaDragHandler;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayNetworkHandler.class)
public class ScrollMixin {

    @Shadow public ServerPlayerEntity player;

    private int lastSlot = -1;

    @Inject(method = "onUpdateSelectedSlot", at = @At("HEAD"))
    private void onScroll(UpdateSelectedSlotC2SPacket packet, CallbackInfo ci) {
        if (!JengaDragHandler.isGrabbing(player.getUuid())) return;

        int newSlot = packet.getSelectedSlot();
        if (lastSlot == -1) { lastSlot = newSlot; return; }

        int delta = newSlot - lastSlot;
        if (delta > 4)  delta -= 9;
        if (delta < -4) delta += 9;

        if (delta != 0) {
            int direction = delta > 0 ? 1 : -1;
            // Modifier key held → adjust drag distance instead of yaw.
            if (JengaDragHandler.isModifierHeld(player.getUuid())) {
                JengaDragHandler.adjustDistance(player.getUuid(), direction);
            } else {
                JengaDragHandler.adjustRotation(player.getUuid(), direction);
            }
        }
        lastSlot = newSlot;
    }
}
