package com.ToasterStudios.jenga;

import com.ToasterStudios.jenga.config.JengaConfig;
import com.ToasterStudios.jenga.game.JengaGame;
import com.ToasterStudios.jenga.placement.JengaPlacementValidator;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class JengaDragHandler {

    /** Teleport every N server ticks. 1 = maximum safe rate (20 Hz). Sub-tick
     *  rates would require hooking Rapier's physics substep events, which is
     *  unsafe (mid-simulation) and unlikely to look smoother than the existing
     *  client-side interpolation. */
    private static final int TP_INTERVAL = 1;
    private static int tickCounter = 0;

    /** Hard floor on the drag distance to prevent the piece engulfing the camera. */
    private static final double MIN_DISTANCE = 1.0;

    private static final Map<UUID, GrabState> GRABS = new ConcurrentHashMap<>();

    /** Tracks players currently holding the "drag distance modifier" keybind (default LEFT_CTRL). */
    private static final Set<UUID> MODIFIER_HELD = ConcurrentHashMap.newKeySet();

    /**
     * We deliberately do NOT cache a {@link ServerSubLevel} reference here.
     * Sub-levels can be removed from the container (chunk unload, dispose,
     * fast travel) while a player is mid-drag. Calling
     * {@code pipeline.teleport(deadSubLevel, …)} on a body that's been removed
     * from Rapier triggers a Rust panic ("no entry found for key") that can't
     * be caught from Java — it crashes the JVM. Storing the UUID and re-looking
     * up the live reference each tick lets us bail safely if it's gone.
     */
    private static final class GrabState {
        final UUID subLevelId;
        double distance;
        double rotationDeg;

        GrabState(UUID subLevelId, double distance) {
            this.subLevelId = subLevelId;
            this.distance = distance;
            this.rotationDeg = 0.0;
        }
    }

    public static void register() {
        UseBlockCallback.EVENT.register(JengaDragHandler::onUseBlock);
        UseItemCallback.EVENT.register(JengaDragHandler::onUseItem);
    }

    // ── Right-click toggle: press once to grab, press again (block or air) to release ──

    private static ActionResult onUseBlock(PlayerEntity player, World world,
                                           Hand hand, BlockHitResult hit) {
        if (world.isClient() || hand != Hand.MAIN_HAND) return ActionResult.PASS;
        UUID uid = player.getUuid();

        // Already grabbing → behaviour depends on lazy-drag mode:
        //   - lazy on (default): right-click toggles release
        //   - lazy off (hold-mode): ignore. Release is driven by the
        //     RightHoldPayload mouse-up event, not by repeated clicks.
        if (GRABS.containsKey(uid)) {
            if (JengaConfig.isLazyDragEnabled()) {
                release((ServerPlayerEntity) player);
                return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
        }

        SubLevel subLevel = Sable.HELPER.getContaining((ServerWorld) world, hit.getBlockPos());
        if (!(subLevel instanceof ServerSubLevel server)) return ActionResult.PASS;

        // Turn-based gate: if the master toggle is on AND a /jenga game is
        // active, only the current player can grab a piece. The gate only
        // fires when we know the click was on a sub-level (above), so it
        // never blocks normal world interactions.
        if (JengaConfig.isTurnEnforcementEnabled() && JengaGame.isActive()
            && !JengaGame.canPull(player.getUuid())) {
            String currentName = JengaGame.currentPlayerName();
            String msg = currentName != null
                ? "[Jenga Mod] It's " + currentName + "'s turn."
                : "[Jenga Mod] No turn order set yet.";
            ((ServerPlayerEntity) player).sendMessage(Text.literal(msg), true);
            return ActionResult.PASS;
        }

        // Real-Jenga rule: pieces in the top 3 layers AND within the tower's
        // XZ footprint can't be pulled. Pieces that are at high Y but off to
        // the side (a misplaced piece sitting on the edge or beyond) aren't
        // part of the tower anymore — those are draggable.
        //
        // EXEMPTION: any piece the placement validator most recently rejected
        // (failed placement → "not on top of tower" / "off the edge" / etc.)
        // is exempt from this check. The player needs to be able to re-grab
        // and fix a bad placement even when the rejected piece happens to
        // land in the top 3 Y window of the tower.
        //
        // The footprint check uses the centroid of the tower BODY (pieces
        // below the top 3), so the candidate piece can't pull the centroid
        // onto itself and false-block. If there's no reliable body (tower
        // is < 4 layers), we skip the check entirely.
        if (JengaConfig.isTopLayersProtectionEnabled() && "jenga".equals(server.getName())
            && !JengaPlacementValidator.isRecentlyFailed(server.getUniqueId())) {
            TowerStats stats = computeTowerStats((ServerWorld) world);
            if (stats.exists && stats.centerReliable) {
                Vector3dc piecePos = server.logicalPose().position();
                boolean inTopY = piecePos.y() > stats.topY - 2.5;
                // 5.0 = half tower footprint (4.5 for a 9×9 base) + a little
                // leeway. Pieces drifted further than this from center aren't
                // really on the tower anymore.
                boolean inFootprint =
                    Math.abs(piecePos.x() - stats.centerX) < 5.0
                    && Math.abs(piecePos.z() - stats.centerZ) < 5.0;
                if (inTopY && inFootprint) {
                    ((ServerPlayerEntity) player).sendMessage(
                        Text.literal("[Jenga Mod] Can't pull from the top 3 layers."), true);
                    return ActionResult.PASS;
                }
            }
        }

        // Distance must be measured against the sub-level's RENDERED world-space
        // position, not hit.getPos(). For sub-level blocks, hit.getPos() returns
        // coordinates in Sable's internal plot space (block coords around
        // 1,280,000+) — using that gives a distance of ~1.3 million, which when
        // multiplied by the look vector during teleport produces extreme Y and
        // Sable's safety check ("extreme Y coordinate range") deletes the piece.
        Vector3dc subLevelPos = server.logicalPose().position();
        Vec3d eye = player.getEyePos();
        double dx = subLevelPos.x() - eye.x;
        double dz = subLevelPos.z() - eye.z;
        double dist = Math.max(MIN_DISTANCE, Math.sqrt(dx * dx + dz * dz));

        GRABS.put(uid, new GrabState(server.getUniqueId(), dist));
        // Clear any in-flight settle watch and the "recently failed" flag —
        // the player is re-handling the piece, so its prior placement state
        // is irrelevant.
        JengaPlacementValidator.cancel(server.getUniqueId());
        String hint = JengaConfig.isLazyDragEnabled()
            ? "[Jenga] Grabbed! Right-click again to release."
            : "[Jenga] Grabbed! Release right-click to drop.";
        ((ServerPlayerEntity) player).sendMessage(Text.literal(hint), true);
        return ActionResult.SUCCESS;
    }

    private static TypedActionResult<ItemStack> onUseItem(PlayerEntity player, World world, Hand hand) {
        if (world.isClient() || hand != Hand.MAIN_HAND)
            return TypedActionResult.pass(player.getStackInHand(hand));
        if (!GRABS.containsKey(player.getUuid()))
            return TypedActionResult.pass(player.getStackInHand(hand));
        release((ServerPlayerEntity) player);
        return TypedActionResult.success(player.getStackInHand(hand));
    }

    // ── Scroll-handler entry points (called from ScrollMixin) ──────────────

    public static void adjustRotation(UUID uid, int direction) {
        GrabState state = GRABS.get(uid);
        if (state == null) return;
        state.rotationDeg += direction * 90.0;
    }

    public static void adjustDistance(UUID uid, int direction) {
        GrabState state = GRABS.get(uid);
        if (state == null) return;
        state.distance = Math.max(MIN_DISTANCE, state.distance + direction);
    }

    // ── Modifier-key held-state (called by network packet receiver) ────────

    public static void setModifierHeld(UUID uid, boolean held) {
        if (held) MODIFIER_HELD.add(uid);
        else MODIFIER_HELD.remove(uid);
    }

    public static boolean isModifierHeld(UUID uid) {
        return MODIFIER_HELD.contains(uid);
    }

    // ── Tick: teleport via Sable's PhysicsPipeline ─────────────────────────

    public static void tick(MinecraftServer server) {
        if (GRABS.isEmpty()) return;
        if (++tickCounter % TP_INTERVAL != 0) return;

        GRABS.entrySet().removeIf(entry -> {
            UUID uid = entry.getKey();
            GrabState state = entry.getValue();
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uid);
            if (player == null) return true;

            ServerWorld world = player.getServerWorld();

            ServerSubLevel live = findLiveSubLevel(world, state.subLevelId);
            if (live == null) {
                player.sendMessage(Text.literal("[Jenga] Piece is no longer loaded — released."), true);
                return true;
            }

            Vec3d eye = player.getEyePos();
            Vec3d look = player.getRotationVector();
            Vector3d targetPos = new Vector3d(
                eye.x + look.x * state.distance,
                eye.y + look.y * state.distance,
                eye.z + look.z * state.distance
            );

            // Yaw-only orientation. Sign-flipped: JOML rotateY is CCW (looking down -Y),
            // MC yaw is CW. Flipping keeps scroll direction intuitive.
            Quaterniond orientation = new Quaterniond().rotateY(Math.toRadians(-state.rotationDeg));

            try {
                ServerSubLevelContainer container = SubLevelContainer.getContainer(world);
                if (container == null) return false;
                PhysicsPipeline pipeline = container.physicsSystem().getPipeline();
                pipeline.resetVelocity(live);
                pipeline.teleport(live, targetPos, orientation);
            } catch (Throwable t) {
                JengaMod.LOGGER.warn("[Jenga Mod] teleport failed, releasing: {}", t.toString());
                player.sendMessage(Text.literal("[Jenga] Drag failed — released."), true);
                return true;
            }

            return false;
        });
    }

    /**
     * Snapshot of the tower's top + stable body centroid.
     *
     * <p>The XZ centroid is computed from pieces <strong>below</strong> the top
     * 3 Y window — the "tower body" — so the candidate piece (which usually
     * lives in the top 3 zone we're testing against) can't bias the centroid
     * onto itself. This is the bug fix for "bad-placement piece blocked by
     * top-3 protection": with a top-3-only centroid, a misplaced top piece
     * was the centroid, so the distance check always said "in footprint".
     *
     * <p>{@code centerReliable} is false when there are too few body pieces
     * to compute a meaningful centroid (e.g. tower has &lt; 4 layers). Caller
     * should skip top-3 protection in that case — small towers don't have
     * anything legal to pull from anyway.
     */
    private static final class TowerStats {
        final double topY;
        final double centerX;
        final double centerZ;
        final boolean exists;
        final boolean centerReliable;

        TowerStats(double topY, double centerX, double centerZ,
                   boolean exists, boolean centerReliable) {
            this.topY = topY;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.exists = exists;
            this.centerReliable = centerReliable;
        }

        static TowerStats none() { return new TowerStats(0, 0, 0, false, false); }
    }

    private static TowerStats computeTowerStats(ServerWorld world) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(world);
        if (container == null) return TowerStats.none();

        // Pass 1: top Y across every Jenga piece.
        double maxY = Double.NEGATIVE_INFINITY;
        for (ServerSubLevel sl : container.getAllSubLevels()) {
            if (!"jenga".equals(sl.getName())) continue;
            double y = sl.logicalPose().position().y();
            if (y > maxY) maxY = y;
        }
        if (maxY == Double.NEGATIVE_INFINITY) return TowerStats.none();

        // Pass 2: centroid of the BODY — pieces BELOW the top 3 Y window.
        // These are the stable, never-the-candidate pieces, so the centroid
        // honestly represents the tower's true XZ.
        double sumX = 0, sumZ = 0;
        int count = 0;
        for (ServerSubLevel sl : container.getAllSubLevels()) {
            if (!"jenga".equals(sl.getName())) continue;
            Vector3dc p = sl.logicalPose().position();
            if (p.y() >= maxY - 2.5) continue;
            sumX += p.x();
            sumZ += p.z();
            count++;
        }

        // Need at least one full layer (3 pieces) of body to trust the centroid.
        boolean reliable = count >= 3;
        if (count == 0) return new TowerStats(maxY, 0, 0, true, false);
        return new TowerStats(maxY, sumX / count, sumZ / count, true, reliable);
    }

    /**
     * Walks the container's live sub-levels and returns the one with a matching
     * UUID, or null if it's no longer there.
     */
    private static ServerSubLevel findLiveSubLevel(ServerWorld world, UUID id) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(world);
        if (container == null) return null;
        for (ServerSubLevel sl : container.getAllSubLevels()) {
            if (id.equals(sl.getUniqueId())) return sl;
        }
        return null;
    }

    private static void release(ServerPlayerEntity player) {
        GrabState state = GRABS.remove(player.getUuid());
        player.sendMessage(Text.literal("[Jenga] Released."), true);
        // Schedule a settle-then-validate watch for whatever piece they just let go of.
        if (state != null) {
            JengaPlacementValidator.schedule(state.subLevelId, player);
        }
    }

    public static boolean isGrabbing(UUID uid) { return GRABS.containsKey(uid); }

    /** True if any player currently has a piece grabbed. Used by the fall detector
     *  to pause while a piece is legitimately moving. */
    public static boolean isAnyGrabActive() { return !GRABS.isEmpty(); }

    /**
     * Called from the {@code RightHoldPayload} receiver: if hold-to-drag mode
     * is active and this player has a grab open, release it. No-op in lazy
     * mode — clicks alone control the toggle there.
     */
    public static void releaseIfGrabbing(ServerPlayerEntity player) {
        if (player == null) return;
        if (JengaConfig.isLazyDragEnabled()) return;
        if (GRABS.containsKey(player.getUuid())) {
            release(player);
        }
    }

    public static void onPlayerLeave(UUID uid) {
        GRABS.remove(uid);
        MODIFIER_HELD.remove(uid);
    }
    private JengaDragHandler() {}
}
