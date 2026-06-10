package com.ToasterStudios.jenga;

import com.ToasterStudios.jenga.command.JengaCommand;
import com.ToasterStudios.jenga.command.StackCommand;
import com.ToasterStudios.jenga.config.JengaConfig;
import com.ToasterStudios.jenga.network.ModifierKeyPayload;
import com.ToasterStudios.jenga.network.RightHoldPayload;
import com.ToasterStudios.jenga.placement.JengaFallDetector;
import com.ToasterStudios.jenga.placement.JengaPlacementValidator;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JengaMod implements ModInitializer {

    public static final String MOD_ID = "jengamod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        JengaConfig.load();
        JengaGameRules.register();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            StackCommand.register(dispatcher);
            JengaCommand.register(dispatcher);
        });

        // Async tower build queue — 3 pieces per tick
        ServerTickEvents.END_SERVER_TICK.register(JengaBuildQueue::tick);

        // Right-click drag mechanic
        JengaDragHandler.register();
        ServerTickEvents.END_SERVER_TICK.register(JengaDragHandler::tick);

        // Settle-and-validate every released piece for "good placement" detection.
        ServerTickEvents.END_SERVER_TICK.register(JengaPlacementValidator::tick);

        // Watch for tower collapse during /jenga games.
        ServerTickEvents.END_SERVER_TICK.register(JengaFallDetector::tick);

        // ── Custom packet: drag-distance modifier-key held state (client → server) ──
        PayloadTypeRegistry.playC2S().register(ModifierKeyPayload.ID, ModifierKeyPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ModifierKeyPayload.ID, (payload, ctx) -> {
            JengaDragHandler.setModifierHeld(ctx.player().getUuid(), payload.held());
        });

        // ── Custom packet: right-mouse-button held state (drives hold-to-drag mode) ──
        PayloadTypeRegistry.playC2S().register(RightHoldPayload.ID, RightHoldPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(RightHoldPayload.ID, (payload, ctx) -> {
            // We only care about the release edge: when the button comes up
            // and the player has a grab open in hold-mode, release it.
            if (!payload.held()) {
                JengaDragHandler.releaseIfGrabbing(ctx.player());
            }
        });

        // Clean up grabs when players disconnect
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
            JengaDragHandler.onPlayerLeave(handler.player.getUuid())
        );

        LOGGER.info("[Jenga Mod] Loaded. /stack <N> to spawn a Jenga tower (cap = jengaMaxLayers gamerule).");
        LOGGER.info("[Jenga Mod] Right-click a piece to grab; scroll to rotate; hold modifier key (default Ctrl) + scroll to change distance.");
    }
}
