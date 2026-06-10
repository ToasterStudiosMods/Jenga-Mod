package com.ToasterStudios.jenga.placement;

import com.ToasterStudios.jenga.JengaDragHandler;
import com.ToasterStudios.jenga.JengaMod;
import com.ToasterStudios.jenga.config.JengaConfig;
import com.ToasterStudios.jenga.game.JengaGame;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

/**
 * Detects when the Jenga tower has collapsed.
 *
 * <p>Runs on a periodic tick regardless of {@link JengaGame} state — collapses
 * happen during free play too, not just during {@code /jenga} games. Tracks
 * {@code maxTopY} — the highest Y any Jenga piece has ever reached. Real Jenga
 * only ever stacks UP, so the high-water-mark only goes up. If the current top
 * Y suddenly drops by more than {@link #FALL_THRESHOLD} (≈1 layer), the tower
 * must have lost a layer → collapsed.
 *
 * <p>On detection: broadcast a message (blaming the current-turn player if a
 * game is running). If a {@link JengaGame} is active, stop it so the now-stale
 * turn order doesn't keep blocking interactions.
 *
 * <p>State management:
 * <ul>
 *   <li>{@code maxTopY} resets when no Jenga pieces exist in any world (e.g.
 *       after {@code /stack clear} or {@code /sable remove @e}).</li>
 *   <li>{@code alreadyFell} re-arms whenever new pieces push {@code topY}
 *       beyond the prior high-water-mark — building back up after a collapse
 *       starts a fresh detection window.</li>
 * </ul>
 */
public final class JengaFallDetector {

    /** How often to check (server ticks). 20 = once per second. */
    private static final int CHECK_INTERVAL = 20;

    // The drop-threshold is now configurable: see JengaConfig.getFallSensitivity().
    // Lower = more sensitive. Range clamped to [1.0, 6.0] in JengaConfig.

    /** Minimum jenga-piece count before we even consider detecting a fall. */
    private static final int MIN_PIECES_FOR_FALL = 9; // 3 layers worth

    /** Settle period after pieces first appear before we trust the high-water-mark.
     *  Avoids capturing transient peaks during build + initial physics settle. */
    private static final int WARMUP_CHECKS = 3; // 3 × CHECK_INTERVAL = ~3 seconds

    /** Number of consecutive bad checks required to call it a fall. */
    private static final int FALL_CONFIRM_CHECKS = 2;

    private static int tickCounter = 0;
    private static double maxTopY = Double.NEGATIVE_INFINITY;
    /** Latched once a fall is announced — re-armed when topY grows past the old max again. */
    private static boolean alreadyFell = false;
    /** Number of CHECK_INTERVAL ticks since the tower first reached MIN_PIECES_FOR_FALL. */
    private static int checksSinceTowerReady = 0;
    /** Consecutive checks where topY < maxTopY - FALL_THRESHOLD. */
    private static int consecutiveBadChecks = 0;

    private JengaFallDetector() {}

    public static void tick(MinecraftServer server) {
        if (!JengaConfig.isFallDetectionEnabled()) return;
        if (++tickCounter % CHECK_INTERVAL != 0) return;

        // Pause ALL processing while any player has a piece grabbed. The held
        // piece's logicalPose follows the player (could be high in the sky if
        // they're flying or looking up), and using that to update maxTopY
        // contaminates the baseline — drop checks then false-fire when the
        // piece is set back down on the tower. Critical: skip BEFORE peak
        // update, not after. Returning here also preserves all counters so
        // the warmup doesn't spuriously advance during a long grab.
        if (JengaDragHandler.isAnyGrabActive()) {
            consecutiveBadChecks = 0;
            return;
        }

        Snapshot snap = scan(server);

        // No Jenga pieces → full reset.
        if (snap.count == 0) {
            maxTopY = Double.NEGATIVE_INFINITY;
            alreadyFell = false;
            checksSinceTowerReady = 0;
            consecutiveBadChecks = 0;
            return;
        }

        // Tower too small to be a real Jenga tower yet.
        if (snap.count < MIN_PIECES_FOR_FALL) {
            if (snap.topY > maxTopY) maxTopY = snap.topY;
            checksSinceTowerReady = 0;
            consecutiveBadChecks = 0;
            return;
        }

        // Tower grew past the old peak → re-arm and update.
        if (snap.topY > maxTopY) {
            maxTopY = snap.topY;
            alreadyFell = false;
            consecutiveBadChecks = 0;
        }

        // Warmup: physics settle window before we trust the baseline.
        if (checksSinceTowerReady < WARMUP_CHECKS) {
            checksSinceTowerReady++;
            consecutiveBadChecks = 0;
            return;
        }

        if (alreadyFell) return;

        double threshold = JengaConfig.getFallSensitivity();
        boolean badThisCheck = snap.topY < maxTopY - threshold;
        if (!badThisCheck) {
            consecutiveBadChecks = 0;
            return;
        }

        consecutiveBadChecks++;
        if (consecutiveBadChecks >= FALL_CONFIRM_CHECKS) {
            announceFall(server, snap);
        }
    }

    private static void announceFall(MinecraftServer server, Snapshot snap) {
        alreadyFell = true;
        consecutiveBadChecks = 0;

        // Diagnostic log line — paste this if a fall ever feels wrong.
        JengaMod.LOGGER.info(
            "[Jenga Mod] Fall detected: topY={}, maxTopY={}, drop={}, pieceCount={}",
            String.format("%.2f", snap.topY),
            String.format("%.2f", maxTopY),
            String.format("%.2f", maxTopY - snap.topY),
            snap.count);

        String culprit = JengaGame.isActive() ? JengaGame.currentPlayerName() : null;
        String msg = culprit != null
            ? "[Jenga Mod] ✗ The tower fell! " + culprit + " loses."
            : "[Jenga Mod] ✗ The tower fell!";

        try {
            server.getPlayerManager().broadcast(Text.literal(msg), false);
        } catch (Exception e) {
            JengaMod.LOGGER.warn("[Jenga Mod] fall-broadcast failed: {}", e.getMessage());
        }

        if (JengaGame.isActive()) {
            JengaGame.stop();
        }
    }

    /** One-pass scan over every Sable world's Jenga sub-levels. */
    private static Snapshot scan(MinecraftServer server) {
        double max = Double.NEGATIVE_INFINITY;
        int count = 0;
        for (ServerWorld world : server.getWorlds()) {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(world);
            if (container == null) continue;
            for (ServerSubLevel sl : container.getAllSubLevels()) {
                if (!"jenga".equals(sl.getName())) continue;
                count++;
                double y = sl.logicalPose().position().y();
                if (y > max) max = y;
            }
        }
        return new Snapshot(max, count);
    }

    private record Snapshot(double topY, int count) {}
}
