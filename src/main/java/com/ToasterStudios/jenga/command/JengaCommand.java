package com.ToasterStudios.jenga.command;

import com.ToasterStudios.jenga.JengaMod;
import com.ToasterStudios.jenga.game.JengaGame;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The {@code /jenga} command tree:
 *
 * <pre>
 *   /jenga start                        admin — auto-fills turn order with all online players
 *   /jenga stop                         admin — clears game state
 *   /jenga status                       any   — shows current turn + order
 *   /jenga turn next                    current player OR admin — advances
 *   /jenga turn last                    admin — rolls back
 *   /jenga turn &lt;name1&gt; &lt;name2&gt; ...     admin — sets turn order (greedy, space-separated)
 * </pre>
 *
 * <p>Brigadier resolves literals before falling through to the greedy
 * {@code players} string, so {@code /jenga turn next} always parses as the
 * literal subcommand even though "next" is also a valid greedy-string token.
 */
public final class JengaCommand {

    private JengaCommand() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
            CommandManager.literal("jenga")
                .then(CommandManager.literal("start")
                    .requires(s -> s.hasPermissionLevel(2))
                    .executes(JengaCommand::executeStart))
                .then(CommandManager.literal("stop")
                    .requires(s -> s.hasPermissionLevel(2))
                    .executes(JengaCommand::executeStop))
                .then(CommandManager.literal("status")
                    .executes(JengaCommand::executeStatus))
                .then(CommandManager.literal("turn")
                    .then(CommandManager.literal("next")
                        // Runtime check inside executor: current player OR admin.
                        .executes(JengaCommand::executeNext))
                    .then(CommandManager.literal("last")
                        .requires(s -> s.hasPermissionLevel(2))
                        .executes(JengaCommand::executeLast))
                    .then(CommandManager.argument("players", StringArgumentType.greedyString())
                        .requires(s -> s.hasPermissionLevel(2))
                        .executes(JengaCommand::executeSetOrder)))
        );
    }

    // ── /jenga start ──────────────────────────────────────────────────────

    private static int executeStart(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        MinecraftServer server = source.getServer();

        // Default order: all currently online players, sorted by name.
        List<ServerPlayerEntity> online = new ArrayList<>(server.getPlayerManager().getPlayerList());
        online.sort(Comparator.comparing(p -> p.getName().getString()));

        List<UUID> uuids = new ArrayList<>(online.size());
        List<String> names = new ArrayList<>(online.size());
        for (ServerPlayerEntity p : online) {
            uuids.add(p.getUuid());
            names.add(p.getName().getString());
        }

        JengaGame.start(uuids, names);

        if (uuids.isEmpty()) {
            source.sendMessage(Text.literal(
                "[Jenga Mod] Game started, but no players online. Use /jenga turn <name>... to set order."));
        } else {
            broadcast(server, "[Jenga Mod] Game started! Turn order: " + String.join(", ", names)
                + ". First up: " + names.get(0) + ".");
        }
        return 1;
    }

    // ── /jenga stop ───────────────────────────────────────────────────────

    private static int executeStop(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        if (!JengaGame.isActive()) {
            source.sendError(Text.literal("[Jenga Mod] No game is active."));
            return 0;
        }
        JengaGame.stop();
        broadcast(source.getServer(), "[Jenga Mod] Game stopped. Anyone can pull again.");
        return 1;
    }

    // ── /jenga status ─────────────────────────────────────────────────────

    private static int executeStatus(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        if (!JengaGame.isActive()) {
            source.sendMessage(Text.literal("[Jenga Mod] No game active."));
            return 0;
        }
        List<String> order = JengaGame.orderNames();
        if (order.isEmpty()) {
            source.sendMessage(Text.literal(
                "[Jenga Mod] Game active, but no turn order set. Use /jenga turn <name>..."));
            return 1;
        }
        String current = JengaGame.currentPlayerName();
        source.sendMessage(Text.literal(
            "[Jenga Mod] Order: " + String.join(", ", order) + ". Current: " + current + "."));
        return 1;
    }

    // ── /jenga turn next ──────────────────────────────────────────────────

    private static int executeNext(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();

        if (!JengaGame.isActive()) {
            source.sendError(Text.literal("[Jenga Mod] No game active. Use /jenga start first."));
            return 0;
        }
        if (JengaGame.currentPlayer() == null) {
            source.sendError(Text.literal("[Jenga Mod] No turn order set yet."));
            return 0;
        }

        ServerPlayerEntity executor = source.getPlayer();
        boolean isCurrent = executor != null && executor.getUuid().equals(JengaGame.currentPlayer());
        boolean isAdmin = source.hasPermissionLevel(2);
        if (!isCurrent && !isAdmin) {
            source.sendError(Text.literal("[Jenga Mod] It's not your turn — you can't advance."));
            return 0;
        }

        if (!JengaGame.nextTurn()) {
            source.sendError(Text.literal("[Jenga Mod] Couldn't advance turn."));
            return 0;
        }
        broadcast(source.getServer(),
            "[Jenga Mod] It's now " + JengaGame.currentPlayerName() + "'s turn.");
        return 1;
    }

    // ── /jenga turn last ──────────────────────────────────────────────────

    private static int executeLast(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        if (!JengaGame.isActive()) {
            source.sendError(Text.literal("[Jenga Mod] No game active."));
            return 0;
        }
        if (JengaGame.currentPlayer() == null) {
            source.sendError(Text.literal("[Jenga Mod] No turn order set yet."));
            return 0;
        }
        if (!JengaGame.previousTurn()) {
            source.sendError(Text.literal("[Jenga Mod] Couldn't roll back turn."));
            return 0;
        }
        broadcast(source.getServer(),
            "[Jenga Mod] Turn rolled back. It's now " + JengaGame.currentPlayerName() + "'s turn.");
        return 1;
    }

    // ── /jenga turn <names...> ────────────────────────────────────────────

    private static int executeSetOrder(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource source = ctx.getSource();
        MinecraftServer server = source.getServer();

        String raw = StringArgumentType.getString(ctx, "players").trim();
        if (raw.isEmpty()) {
            source.sendError(Text.literal("[Jenga Mod] Provide at least one player name."));
            return 0;
        }

        String[] tokens = raw.split("\\s+");
        List<UUID> uuids = new ArrayList<>(tokens.length);
        List<String> names = new ArrayList<>(tokens.length);
        List<String> missing = new ArrayList<>();

        for (String name : tokens) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(name);
            if (p == null) {
                missing.add(name);
                continue;
            }
            // Skip duplicates (player listed twice).
            if (uuids.contains(p.getUuid())) continue;
            uuids.add(p.getUuid());
            names.add(p.getName().getString());
        }

        if (!missing.isEmpty()) {
            source.sendError(Text.literal(
                "[Jenga Mod] Not online (or not found): " + String.join(", ", missing)
                + ". Turn order unchanged."));
            return 0;
        }
        if (uuids.isEmpty()) {
            source.sendError(Text.literal("[Jenga Mod] No valid players in list."));
            return 0;
        }

        // setOrder leaves active state alone — pair it with /jenga start if not yet active.
        if (!JengaGame.isActive()) {
            JengaGame.start(uuids, names);
            broadcast(server, "[Jenga Mod] Game started. Turn order: " + String.join(", ", names)
                + ". First up: " + names.get(0) + ".");
        } else {
            JengaGame.setOrder(uuids, names);
            broadcast(server, "[Jenga Mod] Turn order set to: " + String.join(", ", names)
                + ". First up: " + names.get(0) + ".");
        }
        return 1;
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static void broadcast(MinecraftServer server, String text) {
        try {
            server.getPlayerManager().broadcast(Text.literal(text), false);
        } catch (Exception e) {
            JengaMod.LOGGER.warn("[Jenga Mod] broadcast failed: {}", e.getMessage());
        }
    }
}
