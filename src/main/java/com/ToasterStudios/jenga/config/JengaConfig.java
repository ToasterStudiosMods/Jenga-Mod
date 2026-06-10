package com.ToasterStudios.jenga.config;

import com.ToasterStudios.jenga.JengaMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.EmptyBlockView;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * JSON-backed config for the Jenga block list. Single source of truth on disk
 * at {@code <minecraft>/config/jengamod.json}. Loaded by {@link JengaMod} on
 * server init <em>and</em> by {@code JengaModClient} on client init — duplicate
 * calls are idempotent (whichever side runs first wins, the second is a no-op).
 *
 * <p>In singleplayer / LAN host the integrated server reads the same file the
 * client GUI edits, so changes apply immediately on the next {@code /stack}.
 * On dedicated servers the server file is authoritative; client-side GUI
 * changes only touch the client's local file. There is no in-game sync today.
 */
public final class JengaConfig {

    private static final Path PATH =
        FabricLoader.getInstance().getConfigDir().resolve("jengamod.json");

    private static final List<String> DEFAULT_IDS = List.of(
        "minecraft:stripped_oak_log",
        "minecraft:stripped_birch_log",
        "minecraft:stripped_spruce_log",
        "minecraft:stripped_jungle_log",
        "minecraft:stripped_acacia_log",
        "minecraft:stripped_dark_oak_log",
        "minecraft:stripped_mangrove_log",
        "minecraft:stripped_cherry_log"
    );

    /** Mutable, ordered, deduped. Insertion order preserved for stable UI. */
    private static final LinkedHashSet<Identifier> BLOCKS = new LinkedHashSet<>();

    /** Master switch for the /jenga turn-based system. When false, anyone can pull. */
    private static boolean turnEnforcementEnabled = true;

    /**
     * "Lazy drag" mode (default): right-click toggles grab/release.
     * When false: hold right-click to drag, release to drop. Hold-mode requires
     * the client mod to ship the right-button state via {@code RightHoldPayload}.
     */
    private static boolean lazyDragEnabled = true;

    /**
     * Real-Jenga rule: pieces in the top 3 layers can't be pulled. When false,
     * any piece is grabbable. Default true.
     */
    private static boolean topLayersProtectionEnabled = true;

    /**
     * Watch the tower for a collapse (top-Y drops by more than ~1 layer from
     * its high-water-mark). On detection: broadcast and stop the active game.
     * Default true.
     */
    private static boolean fallDetectionEnabled = true;

    /**
     * How many blocks the tower's top has to drop below its high-water-mark to
     * count as a fall. Lower = more sensitive (catches smaller collapses).
     * Range {@link #FALL_SENSITIVITY_MIN}..{@link #FALL_SENSITIVITY_MAX},
     * default 3.0 (≈ 2 layers).
     */
    private static double fallSensitivity = 3.0;

    public static final double FALL_SENSITIVITY_MIN = 1.0;
    public static final double FALL_SENSITIVITY_MAX = 6.0;
    public static final double FALL_SENSITIVITY_DEFAULT = 3.0;

    /**
     * Placement sensitivity, expressed as the yaw tolerance in degrees. Lower
     * = stricter (catches smaller misalignments). Center tolerance is
     * proportional: {@code centerTolerance = placementSensitivity * 0.15}, so
     * the default 10° gives the original 1.5 blocks.
     */
    private static double placementSensitivity = 10.0;

    public static final double PLACEMENT_SENSITIVITY_MIN = 2.0;
    public static final double PLACEMENT_SENSITIVITY_MAX = 20.0;
    public static final double PLACEMENT_SENSITIVITY_DEFAULT = 10.0;
    /**
     * Center-tolerance scaling factor; multiplied by placement sensitivity.
     * 0.5 means default 10° yaw → 5.0-block center tolerance, generous enough
     * to accept all three Jenga stripe positions (each ±3 from tower center).
     * Lower values (e.g. 0.15) reject outer-stripe placements as "off the
     * edge" — that was the previous bug.
     */
    public static final double PLACEMENT_CENTER_RATIO = 0.5;

    /** When true, validate placements immediately (after the grace tick) without
     *  waiting for the piece to come to rest. Useful for impatient play. */
    private static boolean ignoreWobbling = false;

    /** How many seconds the validator waits for a piece to stop wobbling before
     *  giving up. Only used when {@link #ignoreWobbling} is false. */
    private static double wobbleTime = 5.0;

    public static final double WOBBLE_TIME_MIN = 1.0;
    public static final double WOBBLE_TIME_MAX = 10.0;
    public static final double WOBBLE_TIME_DEFAULT = 5.0;

    private static boolean loaded = false;

    private JengaConfig() {}

    // ── Lifecycle ─────────────────────────────────────────────────────────

    /** Idempotent — first call loads the file (or writes defaults). Subsequent calls no-op. */
    public static synchronized void load() {
        if (loaded) return;
        loaded = true;

        try {
            if (!Files.exists(PATH)) {
                seedDefaults();
                save();
                JengaMod.LOGGER.info("[Jenga Mod] Wrote default config to {}", PATH);
                return;
            }

            String json = Files.readString(PATH);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray arr = root.has("blocks") && root.get("blocks").isJsonArray()
                ? root.getAsJsonArray("blocks")
                : new JsonArray();

            BLOCKS.clear();
            for (JsonElement el : arr) {
                if (!el.isJsonPrimitive()) continue;
                Identifier id = Identifier.tryParse(el.getAsString());
                if (id != null && Registries.BLOCK.containsId(id)) {
                    BLOCKS.add(id);
                }
            }

            // Optional: turn enforcement flag. Default true if missing.
            if (root.has("turnEnforcement") && root.get("turnEnforcement").isJsonPrimitive()) {
                turnEnforcementEnabled = root.get("turnEnforcement").getAsBoolean();
            } else {
                turnEnforcementEnabled = true;
            }

            // Optional: lazy-drag flag. Default true (right-click toggle).
            if (root.has("lazyDrag") && root.get("lazyDrag").isJsonPrimitive()) {
                lazyDragEnabled = root.get("lazyDrag").getAsBoolean();
            } else {
                lazyDragEnabled = true;
            }

            // Optional: protect the top 3 layers from being grabbed. Default true.
            if (root.has("topLayersProtection") && root.get("topLayersProtection").isJsonPrimitive()) {
                topLayersProtectionEnabled = root.get("topLayersProtection").getAsBoolean();
            } else {
                topLayersProtectionEnabled = true;
            }

            // Optional: fall detection. Default true.
            if (root.has("fallDetection") && root.get("fallDetection").isJsonPrimitive()) {
                fallDetectionEnabled = root.get("fallDetection").getAsBoolean();
            } else {
                fallDetectionEnabled = true;
            }

            // Optional: fall sensitivity (drop threshold in blocks). Default 3.0.
            if (root.has("fallSensitivity") && root.get("fallSensitivity").isJsonPrimitive()) {
                double v = root.get("fallSensitivity").getAsDouble();
                fallSensitivity = clampSensitivity(v);
            } else {
                fallSensitivity = FALL_SENSITIVITY_DEFAULT;
            }

            // Optional: placement sensitivity (yaw tolerance in degrees). Default 10.
            if (root.has("placementSensitivity") && root.get("placementSensitivity").isJsonPrimitive()) {
                double v = root.get("placementSensitivity").getAsDouble();
                placementSensitivity = clampPlacement(v);
            } else {
                placementSensitivity = PLACEMENT_SENSITIVITY_DEFAULT;
            }

            // Optional: ignore-wobbling toggle. Default false.
            if (root.has("ignoreWobbling") && root.get("ignoreWobbling").isJsonPrimitive()) {
                ignoreWobbling = root.get("ignoreWobbling").getAsBoolean();
            } else {
                ignoreWobbling = false;
            }

            // Optional: wobble-time seconds. Default 5.0.
            if (root.has("wobbleTime") && root.get("wobbleTime").isJsonPrimitive()) {
                wobbleTime = clampWobbleTime(root.get("wobbleTime").getAsDouble());
            } else {
                wobbleTime = WOBBLE_TIME_DEFAULT;
            }

            // Empty file or all entries unresolved → restore defaults so the
            // build never silently produces no pieces.
            if (BLOCKS.isEmpty()) {
                JengaMod.LOGGER.warn("[Jenga Mod] config/jengamod.json had no resolvable blocks; restoring defaults");
                seedDefaults();
                save();
            }
        } catch (Exception e) {
            JengaMod.LOGGER.error("[Jenga Mod] Failed to load config; using defaults: {}", e.getMessage(), e);
            seedDefaults();
        }
    }

    public static synchronized void save() {
        try {
            Files.createDirectories(PATH.getParent());
            JsonObject root = new JsonObject();
            JsonArray arr = new JsonArray();
            for (Identifier id : BLOCKS) arr.add(id.toString());
            root.add("blocks", arr);
            root.addProperty("turnEnforcement", turnEnforcementEnabled);
            root.addProperty("lazyDrag", lazyDragEnabled);
            root.addProperty("topLayersProtection", topLayersProtectionEnabled);
            root.addProperty("fallDetection", fallDetectionEnabled);
            root.addProperty("fallSensitivity", fallSensitivity);
            root.addProperty("placementSensitivity", placementSensitivity);
            root.addProperty("ignoreWobbling", ignoreWobbling);
            root.addProperty("wobbleTime", wobbleTime);

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            Files.writeString(PATH, gson.toJson(root));
        } catch (Exception e) {
            JengaMod.LOGGER.error("[Jenga Mod] Failed to save config: {}", e.getMessage(), e);
        }
    }

    // ── Turn enforcement toggle ──────────────────────────────────────────

    public static synchronized boolean isTurnEnforcementEnabled() {
        return turnEnforcementEnabled;
    }

    public static synchronized void setTurnEnforcementEnabled(boolean enabled) {
        if (turnEnforcementEnabled == enabled) return;
        turnEnforcementEnabled = enabled;
        save();
    }

    // ── Lazy-drag toggle ─────────────────────────────────────────────────

    public static synchronized boolean isLazyDragEnabled() {
        return lazyDragEnabled;
    }

    public static synchronized void setLazyDragEnabled(boolean enabled) {
        if (lazyDragEnabled == enabled) return;
        lazyDragEnabled = enabled;
        save();
    }

    // ── Top-layer protection toggle ──────────────────────────────────────

    public static synchronized boolean isTopLayersProtectionEnabled() {
        return topLayersProtectionEnabled;
    }

    public static synchronized void setTopLayersProtectionEnabled(boolean enabled) {
        if (topLayersProtectionEnabled == enabled) return;
        topLayersProtectionEnabled = enabled;
        save();
    }

    // ── Fall detection toggle ────────────────────────────────────────────

    public static synchronized boolean isFallDetectionEnabled() {
        return fallDetectionEnabled;
    }

    public static synchronized void setFallDetectionEnabled(boolean enabled) {
        if (fallDetectionEnabled == enabled) return;
        fallDetectionEnabled = enabled;
        save();
    }

    // ── Fall sensitivity (drop-threshold in blocks) ──────────────────────

    public static synchronized double getFallSensitivity() {
        return fallSensitivity;
    }

    public static synchronized void setFallSensitivity(double value) {
        double clamped = clampSensitivity(value);
        if (Math.abs(clamped - fallSensitivity) < 0.001) return;
        fallSensitivity = clamped;
        save();
    }

    private static double clampSensitivity(double v) {
        if (v < FALL_SENSITIVITY_MIN) return FALL_SENSITIVITY_MIN;
        if (v > FALL_SENSITIVITY_MAX) return FALL_SENSITIVITY_MAX;
        return v;
    }

    // ── Placement sensitivity (yaw degrees + scaled center tolerance) ────

    public static synchronized double getPlacementSensitivity() {
        return placementSensitivity;
    }

    public static synchronized void setPlacementSensitivity(double value) {
        double clamped = clampPlacement(value);
        if (Math.abs(clamped - placementSensitivity) < 0.001) return;
        placementSensitivity = clamped;
        save();
    }

    /** Center-tolerance derived from the same slider. Default 10° → 1.5 blocks. */
    public static synchronized double getPlacementCenterTolerance() {
        return placementSensitivity * PLACEMENT_CENTER_RATIO;
    }

    private static double clampPlacement(double v) {
        if (v < PLACEMENT_SENSITIVITY_MIN) return PLACEMENT_SENSITIVITY_MIN;
        if (v > PLACEMENT_SENSITIVITY_MAX) return PLACEMENT_SENSITIVITY_MAX;
        return v;
    }

    // ── Wobbling (settle-check) controls ─────────────────────────────────

    public static synchronized boolean isIgnoreWobbling() {
        return ignoreWobbling;
    }

    public static synchronized void setIgnoreWobbling(boolean enabled) {
        if (ignoreWobbling == enabled) return;
        ignoreWobbling = enabled;
        save();
    }

    public static synchronized double getWobbleTime() {
        return wobbleTime;
    }

    public static synchronized void setWobbleTime(double seconds) {
        double clamped = clampWobbleTime(seconds);
        if (Math.abs(clamped - wobbleTime) < 0.001) return;
        wobbleTime = clamped;
        save();
    }

    private static double clampWobbleTime(double v) {
        if (v < WOBBLE_TIME_MIN) return WOBBLE_TIME_MIN;
        if (v > WOBBLE_TIME_MAX) return WOBBLE_TIME_MAX;
        return v;
    }

    private static void seedDefaults() {
        BLOCKS.clear();
        for (String s : DEFAULT_IDS) {
            Identifier id = Identifier.tryParse(s);
            if (id != null) BLOCKS.add(id);
        }
    }

    // ── Public API used by the screen + builder ──────────────────────────

    /** Snapshot, immutable, in insertion order. */
    public static synchronized List<Identifier> getIdentifiers() {
        return List.copyOf(BLOCKS);
    }

    /**
     * Resolve identifiers to live {@link Block} references for the builder.
     * Skips entries that no longer resolve (e.g. mod uninstalled). Falls back
     * to the vanilla defaults if everything fails to resolve, so {@code /stack}
     * is never broken by a corrupt config.
     */
    public static synchronized List<Block> resolveBlocks() {
        List<Block> out = new ArrayList<>(BLOCKS.size());
        for (Identifier id : BLOCKS) {
            if (!Registries.BLOCK.containsId(id)) continue;
            if (!isFullBlock(id)) {
                // Old configs (pre-full-block-filter) might still have these.
                // Skip with a log line rather than silently producing weird pieces.
                JengaMod.LOGGER.warn("[Jenga Mod] Skipping non-full block {} from config", id);
                continue;
            }
            out.add(Registries.BLOCK.get(id));
        }
        if (out.isEmpty()) {
            for (String s : DEFAULT_IDS) {
                Identifier id = Identifier.tryParse(s);
                if (id != null && Registries.BLOCK.containsId(id)) {
                    out.add(Registries.BLOCK.get(id));
                }
            }
        }
        // If even defaults fail (registry not initialized?), return AIR as a
        // last-ditch fallback so callers never get an empty list.
        if (out.isEmpty()) out.add(Blocks.AIR);
        return Collections.unmodifiableList(out);
    }

    public static synchronized boolean isValidBlock(Identifier id) {
        return id != null && Registries.BLOCK.containsId(id);
    }

    /**
     * True iff the block's default state has a collision shape that fills an
     * entire 1×1×1 cube. Excludes doors, trapdoors, walls, slabs, stairs,
     * fences, fence gates, panes, carpets, buttons, levers, pressure plates,
     * redstone wire, anvils, scaffolding, end rods, chains, candles, etc.
     * Includes opaque cubes (stone, dirt) <em>and</em> transparent cubes
     * (glass, ice) — anything that physically takes up the whole block.
     */
    public static synchronized boolean isFullBlock(Identifier id) {
        if (!isValidBlock(id)) return false;
        Block block = Registries.BLOCK.get(id);
        BlockState state = block.getDefaultState();
        try {
            VoxelShape shape = state.getCollisionShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
            return Block.isShapeFullCube(shape);
        } catch (Exception e) {
            // A few exotic blocks throw if their shape needs world context.
            // Treat anything we can't statically classify as not-full.
            return false;
        }
    }

    public static synchronized boolean contains(Identifier id) {
        return BLOCKS.contains(id);
    }

    /** Returns true iff the block was added (i.e. wasn't already present and is valid). */
    public static synchronized boolean addBlock(Identifier id) {
        if (!isValidBlock(id)) return false;
        boolean added = BLOCKS.add(id);
        if (added) save();
        return added;
    }

    /** Returns true iff a block was removed. */
    public static synchronized boolean removeBlock(Identifier id) {
        boolean removed = BLOCKS.remove(id);
        if (removed) save();
        return removed;
    }

    /** Replace the entire list with the vanilla defaults and persist. */
    public static synchronized void resetToDefaults() {
        seedDefaults();
        save();
    }
}
