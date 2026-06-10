package com.ToasterStudios.jenga.mixin.client;

import com.ToasterStudios.jenga.network.RightHoldPayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Mouse;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Watches the GLFW mouse-button stream for press/release of the right mouse
 * button and ships the new state up to the server via {@link RightHoldPayload}.
 * The server uses the released signal to end a grab in hold-to-drag mode.
 *
 * <p>Does <em>not</em> cancel the vanilla event — normal place-block /
 * use-item still fires. We piggy-back on the input.
 */
@Environment(EnvType.CLIENT)
@Mixin(Mouse.class)
public class MouseRightHoldMixin {

    @Inject(method = "onMouseButton", at = @At("HEAD"))
    private void jengamod$captureRightButton(long window, int button, int action, int mods,
                                              CallbackInfo ci) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return;
        if (action != GLFW.GLFW_PRESS && action != GLFW.GLFW_RELEASE) return;

        if (!ClientPlayNetworking.canSend(RightHoldPayload.ID)) return;

        ClientPlayNetworking.send(new RightHoldPayload(action == GLFW.GLFW_PRESS));
    }
}
