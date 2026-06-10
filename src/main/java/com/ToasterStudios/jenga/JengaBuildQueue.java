package com.ToasterStudios.jenga;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages in-progress Jenga tower builds so they execute asynchronously
 * relative to the player's command — multiple layers per server tick.
 * This keeps the server responsive even for large towers while letting
 * `/stack 999` complete in seconds rather than minutes.
 *
 * <p>Call {@link #tick(MinecraftServer)} from {@code ServerTickEvents.END_SERVER_TICK}.
 */
public final class JengaBuildQueue {

    /**
     * How many pieces to process per tick. 12 = four full layers (each layer
     * has exactly 3 pieces). Each piece does ~27 setBlockState calls plus one
     * {@code SubLevelAssemblyHelper.assembleBlocks} + one {@code teleport};
     * 12 of those per tick is fast but still well within a single server
     * tick's budget. Crank higher (24, 60) at the cost of brief lag spikes
     * if you want even faster builds.
     */
    private static final int PIECES_PER_TICK = 12;

    /** Per-player queue of pending piece-assembly tasks. */
    private static final Map<UUID, BuildJob> JOBS = new ConcurrentHashMap<>();

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Enqueues a tower build for {@code player}.
     * If a build is already in progress for this player it is replaced.
     *
     * @param player      The player who issued /stack.
     * @param totalLayers Total number of layers (for progress messages).
     * @param pieces      Ordered list of assembly tasks (piece by piece).
     */
    public static void enqueue(ServerPlayerEntity player, int totalLayers, Deque<Runnable> pieces) {
        JOBS.put(player.getUuid(), new BuildJob(player, totalLayers, pieces));
    }

    /**
     * Cancels any in-progress build for {@code player}.
     *
     * @return {@code true} if a build was actually cancelled.
     */
    public static boolean cancel(UUID playerUuid) {
        return JOBS.remove(playerUuid) != null;
    }

    /**
     * Returns {@code true} if {@code player} has a build in progress.
     */
    public static boolean isBusy(UUID playerUuid) {
        return JOBS.containsKey(playerUuid);
    }

    /**
     * Called every server tick.  Processes up to {@link #PIECES_PER_TICK} pieces
     * from every active build job and sends progress/completion messages.
     */
    public static void tick(MinecraftServer server) {
        if (JOBS.isEmpty()) return;

        for (var it = JOBS.entrySet().iterator(); it.hasNext(); ) {
            BuildJob job = it.next().getValue();
            boolean done = job.processTick();
            if (done) it.remove();
        }
    }

    // ── BuildJob ──────────────────────────────────────────────────────────

    private static final class BuildJob {

        private final ServerPlayerEntity player;
        private final int totalLayers;
        private final int totalPieces;
        private final Deque<Runnable> queue;
        private int completedPieces = 0;

        BuildJob(ServerPlayerEntity player, int totalLayers, Deque<Runnable> queue) {
            this.player      = player;
            this.totalLayers = totalLayers;
            this.totalPieces = queue.size();
            this.queue       = queue;
        }

        /**
         * Runs up to {@link #PIECES_PER_TICK} pieces.
         *
         * @return {@code true} when the build is finished.
         */
        boolean processTick() {
            // Build loop — process up to PIECES_PER_TICK every tick, no delay
            // between batches.
            for (int i = 0; i < PIECES_PER_TICK && !queue.isEmpty(); i++) {
                try {
                    queue.poll().run();
                } catch (Exception e) {
                    JengaMod.LOGGER.error("[Jenga Mod] Piece assembly error: {}", e.getMessage(), e);
                }
                completedPieces++;
            }

            // Progress messages
            int completedLayers = completedPieces / 3;
            if (completedPieces % 3 == 0 && completedLayers > 0) {
                // Only print every 10 layers to avoid chat spam on large towers.
                if (completedLayers % 10 == 0 || completedLayers == totalLayers) {
                    sendProgress(completedLayers);
                }
            }

            // Completion check
            if (queue.isEmpty()) {
                player.sendMessage(Text.literal(
                    "[Jenga Mod] ✓ Tower complete! " + totalLayers
                    + " layers assembled. Pull carefully!"
                ));
                return true;
            }

            return false;
        }

        private void sendProgress(int completedLayers) {
            int pct = (int) Math.round(completedLayers * 100.0 / totalLayers);
            player.sendMessage(Text.literal(
                "[Jenga Mod] Building… layer " + completedLayers + "/" + totalLayers
                + " (" + pct + "%)"
            ));
        }
    }

    // Static-only utility class
    private JengaBuildQueue() {}
}
