package com.ToasterStudios.jenga.client;

import com.ToasterStudios.jenga.config.JengaConfig;
import com.ToasterStudios.jenga.network.ModifierKeyPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * Client-side initializer.
 *
 * <p>Registers the "Drag Distance Modifier" keybind (default {@code LEFT_CTRL},
 * rebindable in vanilla Controls), polls its state every client tick, and ships
 * any change up to the server as a {@link ModifierKeyPayload}. Server uses that
 * state in {@code ScrollMixin} to branch scroll behaviour between yaw and
 * distance.
 */
@Environment(EnvType.CLIENT)
public final class JengaModClient implements ClientModInitializer {

    public static KeyBinding distanceModifierKey;

    private boolean lastState = false;

    @Override
    public void onInitializeClient() {
        // Idempotent: if main entrypoint already loaded the config, this is a no-op.
        // Ensures the screen can read the block list even before the integrated
        // server boots (e.g. from the title-screen Options button).
        JengaConfig.load();

        distanceModifierKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.jengamod.distance_modifier",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_LEFT_CONTROL,
            "category.jengamod.general"
        ));

        // Poll keybind each tick. We can't use a mixin reliably for non-tap
        // keys (KeyBinding only fires wasPressed events, not held-state
        // edges), so we just diff against last frame and send on change.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean held = distanceModifierKey.isPressed();
            if (held != lastState) {
                lastState = held;
                if (ClientPlayNetworking.canSend(ModifierKeyPayload.ID)) {
                    ClientPlayNetworking.send(new ModifierKeyPayload(held));
                }
            }
        });
    }

}
