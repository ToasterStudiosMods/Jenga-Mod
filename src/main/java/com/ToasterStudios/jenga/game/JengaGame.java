package com.ToasterStudios.jenga.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Server-side game state for /jenga turn-based play.
 *
 * <p>Holds the active flag, the turn order (parallel UUID + name lists), and
 * the current index. Thread-safe via {@code synchronized} since command
 * handlers, packet receivers, and the drag handler may all touch it.
 *
 * <p>Singleton-style — there's one game per server. If you want per-world or
 * per-party games later, this is the file to refactor.
 */
public final class JengaGame {

    private static boolean active = false;

    /** Player UUIDs in turn order. Source of truth for {@link #canPull}. */
    private static final List<UUID> ORDER_UUIDS = new ArrayList<>();

    /** Display names captured at order-set time. May be stale if a player renames. */
    private static final List<String> ORDER_NAMES = new ArrayList<>();

    private static int currentIndex = 0;

    private JengaGame() {}

    // ── Lifecycle ──────────────────────────────────────────────────────────

    public static synchronized void start(List<UUID> uuids, List<String> names) {
        active = true;
        setOrder(uuids, names);
    }

    public static synchronized void stop() {
        active = false;
        ORDER_UUIDS.clear();
        ORDER_NAMES.clear();
        currentIndex = 0;
    }

    public static synchronized void setOrder(List<UUID> uuids, List<String> names) {
        ORDER_UUIDS.clear();
        ORDER_NAMES.clear();
        ORDER_UUIDS.addAll(uuids);
        ORDER_NAMES.addAll(names);
        currentIndex = 0;
    }

    /** Advances to the next player. Returns false if no order is set. */
    public static synchronized boolean nextTurn() {
        if (!active || ORDER_UUIDS.isEmpty()) return false;
        currentIndex = (currentIndex + 1) % ORDER_UUIDS.size();
        return true;
    }

    /** Rolls back to the previous player. Returns false if no order is set. */
    public static synchronized boolean previousTurn() {
        if (!active || ORDER_UUIDS.isEmpty()) return false;
        int n = ORDER_UUIDS.size();
        currentIndex = (currentIndex - 1 + n) % n;
        return true;
    }

    // ── Queries ────────────────────────────────────────────────────────────

    public static synchronized boolean isActive() { return active; }

    /**
     * The core authorization check used by {@link com.ToasterStudios.jenga.JengaDragHandler}.
     * <ul>
     *   <li>Game inactive → anyone can pull (preserves classic behavior).</li>
     *   <li>Game active, no order set → nobody can pull.</li>
     *   <li>Game active, order set → only the current player can pull.</li>
     * </ul>
     */
    public static synchronized boolean canPull(UUID playerId) {
        if (!active) return true;
        if (ORDER_UUIDS.isEmpty()) return false;
        return ORDER_UUIDS.get(currentIndex).equals(playerId);
    }

    public static synchronized UUID currentPlayer() {
        if (!active || ORDER_UUIDS.isEmpty()) return null;
        return ORDER_UUIDS.get(currentIndex);
    }

    public static synchronized String currentPlayerName() {
        if (!active || ORDER_NAMES.isEmpty()) return null;
        return ORDER_NAMES.get(currentIndex);
    }

    /** Snapshot of the current turn order names, in order. Empty if none set. */
    public static synchronized List<String> orderNames() {
        return Collections.unmodifiableList(new ArrayList<>(ORDER_NAMES));
    }

    public static synchronized int currentIndex() {
        return currentIndex;
    }
}
