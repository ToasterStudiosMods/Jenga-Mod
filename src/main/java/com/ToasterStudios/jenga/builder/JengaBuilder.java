package com.ToasterStudios.jenga.builder;

import com.ToasterStudios.jenga.JengaMod;
import com.ToasterStudios.jenga.config.JengaConfig;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.PillarBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import org.joml.Vector3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Builds Jenga towers as a queue of per-piece assembly tasks.
 *
 * <p>To bypass the Minecraft world-height limit (~y=320 on 1.21.1), each piece
 * is <strong>placed at a fixed staging Y near world bottom</strong>, assembled
 * into a Sable sub-level (which clears the staging cells), then teleported via
 * the physics pipeline to its target tower position. Sub-levels live in
 * Sable's plot space, so the final Y has no MC world-height ceiling — towers
 * can grow as tall as the user requests.
 */
public final class JengaBuilder {

    public static final int PIECE_WIDTH      = 3;
    public static final int PIECE_LENGTH     = 9;
    public static final int PIECES_PER_LAYER = 3;

    /** Offset above {@code world.getBottomY()} where pieces are temporarily placed before teleport. */
    private static final int STAGING_Y_OFFSET = 5;

    public static Deque<Runnable> createBuildQueue(ServerWorld world, BlockPos origin, int layers) {
        Random rng = world.getRandom();
        Deque<Runnable> queue = new ArrayDeque<>(layers * PIECES_PER_LAYER);
        int stagingY = world.getBottomY() + STAGING_Y_OFFSET;

        // Block list comes from the user-editable config (Options → Jenga Blocks)
        // with vanilla logs as fallback if it's empty.
        List<Block> blockChoices = JengaConfig.resolveBlocks();

        for (int layer = 0; layer < layers; layer++) {
            final boolean xOriented = (layer % 2 == 0);
            final int finalLayer = layer;
            for (int p = 0; p < PIECES_PER_LAYER; p++) {
                int dx = xOriented ? 0 : p * PIECE_WIDTH;
                int dz = xOriented ? p * PIECE_WIDTH : 0;

                // Where blocks get physically placed before assembly. Same X/Z
                // as the target so block-axis logic is identical, just at a
                // safe staging Y.
                final BlockPos stagingOrigin = new BlockPos(
                    origin.getX() + dx, stagingY, origin.getZ() + dz);

                // Where the assembled sub-level should end up after teleport.
                final BlockPos targetOrigin = origin.add(dx, finalLayer, dz);

                final Block log = blockChoices.get(rng.nextInt(blockChoices.size()));
                queue.add(() -> assemblePiece(world, stagingOrigin, targetOrigin, log, xOriented));
            }
        }
        return queue;
    }

    private static void assemblePiece(ServerWorld world,
                                      BlockPos stagingOrigin,
                                      BlockPos targetOrigin,
                                      Block log,
                                      boolean xOriented) {
        // ── 1. Place blocks at staging ──────────────────────────────────────
        Direction.Axis logAxis = xOriented ? Direction.Axis.X : Direction.Axis.Z;
        BlockState logState = log.getDefaultState();
        // Only set AXIS if the block actually has it (user-configured blocks
        // may be planks, glass, stone — anything in the BLOCK registry).
        if (logState.contains(PillarBlock.AXIS)) {
            logState = logState.with(PillarBlock.AXIS, logAxis);
        }

        List<BlockPos> positions = new ArrayList<>(PIECE_LENGTH * PIECE_WIDTH);
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        final BlockState placedState = logState;
        for (int len = 0; len < PIECE_LENGTH; len++) {
            for (int wid = 0; wid < PIECE_WIDTH; wid++) {
                BlockPos pos = stagingOrigin.add(
                    xOriented ? len : wid, 0, xOriented ? wid : len);
                world.setBlockState(pos, placedState, Block.NOTIFY_ALL);
                positions.add(pos);
                if (pos.getX() < minX) minX = pos.getX();
                if (pos.getY() < minY) minY = pos.getY();
                if (pos.getZ() < minZ) minZ = pos.getZ();
                if (pos.getX() > maxX) maxX = pos.getX();
                if (pos.getY() > maxY) maxY = pos.getY();
                if (pos.getZ() > maxZ) maxZ = pos.getZ();
            }
        }

        BlockPos anchor = positions.get(positions.size() / 2);
        BoundingBox3i bounds = new BoundingBox3i(minX, minY, minZ, maxX, maxY, maxZ);

        // ── 2. Assemble into a sub-level ────────────────────────────────────
        ServerSubLevel subLevel;
        try {
            subLevel = SubLevelAssemblyHelper.assembleBlocks(world, anchor, positions, bounds);
        } catch (Exception e) {
            JengaMod.LOGGER.error("[Jenga Mod] assembleBlocks failed at {}: {}", anchor, e.getMessage(), e);
            return;
        }
        if (subLevel == null) {
            JengaMod.LOGGER.warn("[Jenga Mod] assembleBlocks returned null at {}", anchor);
            return;
        }
        // Tag this sub-level so the placement validator can filter Jenga
        // pieces from other Sable mods (ships, vehicles) sharing the world.
        subLevel.setName("jenga");

        // ── 3. Teleport the sub-level to its target tower position ──────────
        // Compute the world-space center of where the piece *should* be in the
        // finished tower.
        double targetCenterX, targetCenterZ;
        if (xOriented) {
            targetCenterX = targetOrigin.getX() + PIECE_LENGTH / 2.0;
            targetCenterZ = targetOrigin.getZ() + PIECE_WIDTH  / 2.0;
        } else {
            targetCenterX = targetOrigin.getX() + PIECE_WIDTH  / 2.0;
            targetCenterZ = targetOrigin.getZ() + PIECE_LENGTH / 2.0;
        }
        double targetCenterY = targetOrigin.getY() + 0.5; // pieces are 1 block tall

        try {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(world);
            if (container == null) {
                JengaMod.LOGGER.warn("[Jenga Mod] no SubLevelContainer for world; piece left at staging");
                return;
            }
            PhysicsPipeline pipeline = container.physicsSystem().getPipeline();
            // Sable's own teleport command pattern: reset velocity first, otherwise
            // accumulated falling velocity carries through the teleport.
            pipeline.resetVelocity(subLevel);
            pipeline.teleport(
                subLevel,
                new Vector3d(targetCenterX, targetCenterY, targetCenterZ),
                subLevel.logicalPose().orientation()
            );
        } catch (Throwable t) {
            JengaMod.LOGGER.warn("[Jenga Mod] teleport-to-target failed: {}", t.toString());
        }
    }

    private JengaBuilder() {}
}
