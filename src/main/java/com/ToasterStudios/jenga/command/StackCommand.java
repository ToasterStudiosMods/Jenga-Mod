package com.ToasterStudios.jenga.command;

import com.ToasterStudios.jenga.JengaBuildQueue;
import com.ToasterStudios.jenga.JengaGameRules;
import com.ToasterStudios.jenga.JengaMod;
import com.ToasterStudios.jenga.builder.JengaBuilder;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.Deque;

/**
 * Registers the {@code /stack} command tree:
 *
 * <pre>
 *   /stack &lt;layers&gt;    — spawn N-layer Jenga tower (async, one layer per tick)
 *   /stack cancel       — abort an in-progress build
 *   /stack status       — report whether a build is running
 * </pre>
 *
 * <h3>Layer cap</h3>
 * The {@code jengaMaxLayers} gamerule is the only ceiling (default 999).
 * Brigadier just enforces the minimum {@code 1}. Example:
 * <pre>
 *   /gamerule jengaMaxLayers 5000
 *   /stack 1500          ← builds 1500 layers
 *   /gamerule jengaMaxLayers 50
 *   /stack 200           ← builds only 50 layers, warns the player
 * </pre>
 *
 * <h3>Positioning</h3>
 * The tower's bottom-north-west corner spawns one block below the player's feet.
 *
 * <h3>Permissions</h3>
 * By default, any player may use /stack.  To restrict to operators, add
 * {@code .requires(src -> src.hasPermissionLevel(2))} before the {@code .then(…)}.
 */
public final class StackCommand {

    // ── Registration ──────────────────────────────────────────────────────

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
            CommandManager.literal("stack")
                // /stack <N>  — only enforced minimum is 1; upper bound is the
                // jengaMaxLayers gamerule (default 999, but raise it freely).
                .then(
                    CommandManager.argument("layers", IntegerArgumentType.integer(1))
                        .executes(StackCommand::executeBuild)
                )
                // /stack cancel
                .then(
                    CommandManager.literal("cancel")
                        .executes(StackCommand::executeCancel)
                )
                // /stack status
                .then(
                    CommandManager.literal("status")
                        .executes(StackCommand::executeStatus)
                )
                // /stack clear  — shortcut for `/sable remove @e`
                .then(
                    CommandManager.literal("clear")
                        .executes(StackCommand::executeClear)
                )
        );
    }

    // ── /stack <layers> ───────────────────────────────────────────────────

    private static int executeBuild(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();

        // Require a real player (not command block / console for positioning)
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal("[Jenga Mod] /stack must be run by a player."));
            return 0;
        }

        // Reject if a build is already running for this player
        if (JengaBuildQueue.isBusy(player.getUuid())) {
            source.sendError(Text.literal(
                "[Jenga Mod] A tower is already being built for you. "
                + "Use '/stack cancel' to stop it first."
            ));
            return 0;
        }

        ServerWorld world = source.getWorld();

        // ── Resolve layer count ──────────────────────────────────────────
        // jengaMaxLayers gamerule is the only upper cap. Default is 999 — set it
        // higher (or lower) at runtime via /gamerule jengaMaxLayers <N>.
        int requested  = IntegerArgumentType.getInteger(ctx, "layers");
        int ruleMax    = world.getGameRules().getInt(JengaGameRules.JENGA_MAX_LAYERS);
        int effective  = Math.min(requested, ruleMax);

        if (effective != requested) {
            source.sendMessage(Text.literal(
                "[Jenga Mod] Requested " + requested + " layers, but jengaMaxLayers = "
                + ruleMax + ". Capping at " + effective + "."
            ));
        }

        // ── Resolve spawn position ───────────────────────────────────────
        // Bottom corner = one block below the player's feet.
        BlockPos origin = BlockPos.ofFloored(source.getPosition());

        JengaMod.LOGGER.info(
            "[Jenga Mod] {} requested /stack {} → {} layers at {}",
            player.getName().getString(), requested, effective, origin
        );

        // ── Build async queue and start ──────────────────────────────────
        Deque<Runnable> queue = JengaBuilder.createBuildQueue(world, origin, effective);

        JengaBuildQueue.enqueue(player, effective, queue);

        source.sendMessage(Text.literal(
            "[Jenga Mod] Starting " + effective + "-layer Jenga tower at "
            + origin.getX() + ", " + origin.getY() + ", " + origin.getZ()
            + ". Progress updates every 10 layers. Use '/stack cancel' to abort."
        ));

        return effective; // Brigadier: positive int = success
    }

    // ── /stack cancel ─────────────────────────────────────────────────────

    private static int executeCancel(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal("[Jenga Mod] /stack cancel must be run by a player."));
            return 0;
        }

        boolean wasCancelled = JengaBuildQueue.cancel(player.getUuid());
        if (wasCancelled) {
            source.sendMessage(Text.literal("[Jenga Mod] Build cancelled. Partial tower may remain."));
            return 1;
        } else {
            source.sendMessage(Text.literal("[Jenga Mod] No build in progress."));
            return 0;
        }
    }

    // ── /stack status ─────────────────────────────────────────────────────

    private static int executeStatus(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal("[Jenga Mod] /stack status must be run by a player."));
            return 0;
        }

        boolean busy = JengaBuildQueue.isBusy(player.getUuid());
        source.sendMessage(Text.literal(
            busy ? "[Jenga Mod] A tower is currently being built for you."
                 : "[Jenga Mod] No build in progress."
        ));
        return busy ? 1 : 0;
    }

    // ── /stack clear ──────────────────────────────────────────────────────
    // Shortcut for `/sable remove @e` — removes every sub-level in the world.

    private static int executeClear(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        try {
            source.getServer().getCommandManager().executeWithPrefix(source, "sable remove @e");
            return 1;
        } catch (Exception e) {
            JengaMod.LOGGER.error("[Jenga Mod] /stack clear failed: {}", e.getMessage(), e);
            return 0;
        }
    }

    // Static-only class
    private StackCommand() {}
}
