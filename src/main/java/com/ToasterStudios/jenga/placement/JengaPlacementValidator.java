package com.ToasterStudios.jenga.placement;

import com.ToasterStudios.jenga.JengaMod;
import com.ToasterStudios.jenga.config.JengaConfig;
import com.ToasterStudios.jenga.game.JengaGame;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides whether a freshly-released piece counts as a "good placement" on top
 * of the tower. Scheduled by {@link com.ToasterStudios.jenga.JengaDragHandler}
 * the moment a player ends a drag; the watch then ticks until either the piece
 * comes to rest (validate) or the timeout expires (fail as "still wobbling").
 *
 * <p>The "current top layer" is re-derived from live sub-levels each check, so
 * a successful placement automatically shifts the reference for the next one
 * — no separate cache to keep in sync.
 */
public final class JengaPlacementValidator {

    /** Sub-level name tag used to distinguish Jenga pieces from other Sable mods sharing the world. */
    public static final String JENGA_NAME = "jenga";

    /** Hard cap on settling time is now configurable via JengaConfig.getWobbleTime() (seconds × 20 TPS). */

    /** Minimum wait before checking velocity, so we don't validate the first frame after release. */
    private static final int SETTLE_GRACE_TICKS = 10;

    // Yaw tolerance and center tolerance are now driven by the placement
    // sensitivity slider in JengaConfig — read fresh on each validation so
    // changes apply live.

    /** Rest-detection thresholds. Squared so we can compare without sqrt. */
    private static final double LIN_VEL_EPS = 0.05;
    private static final double ANG_VEL_EPS = 0.05;

    /** Half-height of a piece. Layers cluster within this distance of their centroid Y. */
    private static final double LAYER_BUCKET_HALF = 0.5;

    private static final Map<UUID, SettleWatch> WATCHES = new ConcurrentHashMap<>();

    /**
     * Pieces the validator has recently rejected. The drag handler treats
     * these as exempt from top-3-layer protection so the player can always
     * re-grab and fix a failed placement. Cleared on success, on cancel,
     * or when the piece is grabbed again.
     */
    private static final java.util.Set<UUID> RECENT_FAILURES =
        ConcurrentHashMap.newKeySet();

    private JengaPlacementValidator() {}

    private static final class SettleWatch {
        final UUID subLevelId;
        final UUID playerId;
        final String playerName;
        final ServerWorld world;
        int elapsed = 0;

        SettleWatch(UUID subLevelId, UUID playerId, String playerName, ServerWorld world) {
            this.subLevelId = subLevelId;
            this.playerId = playerId;
            this.playerName = playerName;
            this.world = world;
        }
    }

    /** Begins watching a released piece. Called from {@code JengaDragHandler.release}. */
    public static void schedule(UUID subLevelId, ServerPlayerEntity player) {
        WATCHES.put(subLevelId, new SettleWatch(
            subLevelId, player.getUuid(),
            player.getName().getString(), player.getServerWorld()));
    }

    public static void cancel(UUID subLevelId) {
        WATCHES.remove(subLevelId);
        RECENT_FAILURES.remove(subLevelId);
    }

    /** True if the validator most recently failed this piece's placement. The
     *  drag handler uses this to skip top-3 protection so the player can
     *  re-grab and retry. */
    public static boolean isRecentlyFailed(UUID subLevelId) {
        return RECENT_FAILURES.contains(subLevelId);
    }

    /** Wired in {@link JengaMod#onInitialize} as a {@code ServerTickEvents.END_SERVER_TICK} handler. */
    public static void tick(MinecraftServer server) {
        if (WATCHES.isEmpty()) return;
        WATCHES.entrySet().removeIf(entry -> tickOne(server, entry.getValue()));
    }

    /** @return true iff this watch is finished and should be removed. */
    private static boolean tickOne(MinecraftServer server, SettleWatch w) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(w.world);
        if (container == null) return true;

        ServerSubLevel piece = findLive(container, w.subLevelId);
        if (piece == null) return true; // chunk unloaded / piece despawned

        w.elapsed++;
        if (w.elapsed < SETTLE_GRACE_TICKS) return false;

        // Ignore-wobbling mode: skip the velocity check entirely. The grace
        // period gave the piece a moment to land; we validate from where it
        // is right now, however shaky it might be.
        if (JengaConfig.isIgnoreWobbling()) {
            validate(server, w, piece, container);
            return true;
        }

        PhysicsPipeline pipeline = container.physicsSystem().getPipeline();
        Vector3d lin = new Vector3d();
        Vector3d ang = new Vector3d();
        try {
            pipeline.getLinearVelocity(piece, lin);
            pipeline.getAngularVelocity(piece, ang);
        } catch (Throwable t) {
            JengaMod.LOGGER.warn("[Jenga Mod] velocity read failed: {}", t.toString());
            return true;
        }

        boolean settled = lin.lengthSquared() < LIN_VEL_EPS * LIN_VEL_EPS
                       && ang.lengthSquared() < ANG_VEL_EPS * ANG_VEL_EPS;

        if (settled) {
            validate(server, w, piece, container);
            return true;
        }
        // Configurable timeout: wobbleTime seconds × 20 TPS.
        int timeoutTicks = (int) Math.round(JengaConfig.getWobbleTime() * 20.0);
        if (w.elapsed >= timeoutTicks) {
            fail(server, w, "still wobbling — the tower didn't settle in time");
            return true;
        }
        return false;
    }

    private static void validate(MinecraftServer server, SettleWatch w,
                                 ServerSubLevel piece, ServerSubLevelContainer container) {
        // Read tolerances live from the slider so adjustments take effect immediately.
        double yawTolerance = JengaConfig.getPlacementSensitivity();
        double centerTolerance = JengaConfig.getPlacementCenterTolerance();

        Vector3dc pos = piece.logicalPose().position();
        double pX = pos.x(), pY = pos.y(), pZ = pos.z();

        // Other Jenga pieces, ignoring the one we're validating.
        List<ServerSubLevel> others = new ArrayList<>();
        for (ServerSubLevel sl : container.getAllSubLevels()) {
            if (sl == piece) continue;
            if (!JENGA_NAME.equals(sl.getName())) continue;
            others.add(sl);
        }
        if (others.isEmpty()) {
            // First piece in the world / no tower context to validate against.
            success(server, w);
            return;
        }

        // Highest centroid Y among the rest of the tower.
        double maxY = Double.NEGATIVE_INFINITY;
        for (ServerSubLevel sl : others) {
            double y = sl.logicalPose().position().y();
            if (y > maxY) maxY = y;
        }

        // 1. On top of the tower = either at the same Y as the existing top
        //    (completing the top layer) or above it (starting a new layer).
        //    This is the actual Jenga rule: you can place to fill the current
        //    top layer, or start the next one once the current is full.
        if (pY < maxY - LAYER_BUCKET_HALF) {
            fail(server, w, "not on top of the tower");
            return;
        }

        boolean isNewLayer = pY > maxY + LAYER_BUCKET_HALF;

        // 2. Centered over the tower BODY (pieces below the placed piece's
        //    layer). Using the body — not the layer at pY — means a layer-
        //    mate can't bias the centroid onto itself, so the second and
        //    third pieces of a new layer don't false-fail "off the edge".
        double bodyX = 0, bodyZ = 0;
        int bodyCount = 0;
        for (ServerSubLevel sl : others) {
            double y = sl.logicalPose().position().y();
            if (y >= pY - LAYER_BUCKET_HALF) continue; // skip the candidate's layer + above
            Vector3dc p = sl.logicalPose().position();
            bodyX += p.x();
            bodyZ += p.z();
            bodyCount++;
        }
        // Edge case: tower is exactly 1 layer tall (no body below). Fall back
        // to the centroid of all other pieces so we still have a tower-center.
        if (bodyCount == 0) {
            for (ServerSubLevel sl : others) {
                Vector3dc p = sl.logicalPose().position();
                bodyX += p.x();
                bodyZ += p.z();
            }
            bodyCount = others.size();
        }
        double towerX = bodyX / bodyCount;
        double towerZ = bodyZ / bodyCount;
        if (Math.abs(pX - towerX) > centerTolerance || Math.abs(pZ - towerZ) > centerTolerance) {
            fail(server, w, "off the edge of the tower");
            return;
        }

        // 3. Yaw aligned to a cardinal direction.
        double yawDeg = extractYawDeg(piece.logicalPose().orientation());
        double yawErr = cardinalError(yawDeg);
        if (yawErr > yawTolerance) {
            fail(server, w, "crooked (" + (int) Math.round(yawErr) + "° off)");
            return;
        }

        // 4. Perpendicular check — ONLY when starting a new layer.
        //    When adding to the existing top layer (same Y as a layer-mate),
        //    the new piece must share orientation with its mates, not be
        //    perpendicular. Skipping this check in that case avoids false
        //    "wrong direction" rejections on legitimate Jenga moves.
        if (isNewLayer) {
            List<ServerSubLevel> layerBelow = new ArrayList<>();
            for (ServerSubLevel sl : others) {
                if (Math.abs(sl.logicalPose().position().y() - maxY) <= LAYER_BUCKET_HALF) {
                    layerBelow.add(sl);
                }
            }
            if (!layerBelow.isEmpty()) {
                boolean placedX = isLongAxisX(piece.boundingBox());
                boolean belowX = isLayerLongAxisX(layerBelow);
                if (placedX == belowX) {
                    fail(server, w, "wrong direction — must be perpendicular to the layer below");
                    return;
                }
            }
        }

        success(server, w);
    }

    private static boolean isLongAxisX(BoundingBox3dc bb) {
        return (bb.maxX() - bb.minX()) > (bb.maxZ() - bb.minZ());
    }

    private static boolean isLayerLongAxisX(List<ServerSubLevel> layer) {
        int votesX = 0, votesZ = 0;
        for (ServerSubLevel sl : layer) {
            if (isLongAxisX(sl.boundingBox())) votesX++;
            else votesZ++;
        }
        return votesX >= votesZ;
    }

    /** Extract Y-axis rotation from a quaternion, in degrees, normalized to [0, 360). */
    private static double extractYawDeg(Quaterniondc q) {
        double yaw = Math.toDegrees(Math.atan2(
            2.0 * (q.w() * q.y() + q.x() * q.z()),
            1.0 - 2.0 * (q.y() * q.y() + q.x() * q.x())
        ));
        return ((yaw % 360.0) + 360.0) % 360.0;
    }

    /** Smallest angular distance from {@code yawDeg} to any of {0, 90, 180, 270}. */
    private static double cardinalError(double yawDeg) {
        double err = Double.MAX_VALUE;
        for (double card : new double[]{0, 90, 180, 270, 360}) {
            err = Math.min(err, Math.abs(yawDeg - card));
        }
        return err;
    }

    private static void success(MinecraftServer server, SettleWatch w) {
        // Successfully placed → no longer in a failure state.
        RECENT_FAILURES.remove(w.subLevelId);
        broadcast(server, "[Jenga Mod] ✓ Good placement, " + w.playerName + "!");
        // Only auto-advance if the same player still holds the turn — avoids
        // double-advancing if an admin already ran /jenga turn next manually.
        if (JengaGame.isActive() && w.playerId.equals(JengaGame.currentPlayer())) {
            if (JengaGame.nextTurn()) {
                String next = JengaGame.currentPlayerName();
                if (next != null) {
                    broadcast(server, "[Jenga Mod] It's now " + next + "'s turn.");
                }
            }
        }
    }

    private static void fail(MinecraftServer server, SettleWatch w, String reason) {
        // Mark as failed so the drag handler exempts it from top-3 protection.
        // Cleared by cancel() on re-grab or by success() on a good re-placement.
        RECENT_FAILURES.add(w.subLevelId);
        ServerPlayerEntity p = server.getPlayerManager().getPlayer(w.playerId);
        if (p != null) {
            p.sendMessage(Text.literal("[Jenga Mod] ✗ " + reason), false);
        }
    }

    private static void broadcast(MinecraftServer server, String text) {
        try {
            server.getPlayerManager().broadcast(Text.literal(text), false);
        } catch (Exception e) {
            JengaMod.LOGGER.warn("[Jenga Mod] broadcast failed: {}", e.getMessage());
        }
    }

    private static ServerSubLevel findLive(ServerSubLevelContainer container, UUID id) {
        for (ServerSubLevel sl : container.getAllSubLevels()) {
            if (id.equals(sl.getUniqueId())) return sl;
        }
        return null;
    }
}
