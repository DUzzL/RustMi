package com.github.tatercertified.nalim.redstone;

import com.github.tatercertified.nalim.mixin.MinecraftServerThreadAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * R3.1 — persistent wire-network cache (the fix Ferrite proved essential:
 * walking + marshalling the network per burst ate the whole takeover win on
 * machines that fire hundreds of cascades per second over a STATIC network).
 *
 * <p>Topology (node set + read edges) is cached per network and indexed by
 * every member position, separately per level (a {@link BlockPos} alone is
 * ambiguous across dimensions). Values are kept live instead of re-read:
 *
 * <ul>
 *   <li><b>Wire POWER</b> — every wire set passes the mirror's setBlockState
 *       sentinel; member positions update {@code Node.power} in place (the
 *       engine's own script SETs, vanilla evaluator SETs after an abort or
 *       on bailed cascades — all paths go through setBlockState).</li>
 *   <li><b>strongPower</b> — a same-block VALUE change in the halo (lever
 *       toggling {@code powered}, observer pulsing, lamp lighting) marks the
 *       wire nodes within signal range sp-dirty; the next takeover re-reads
 *       just those via the evaluator's block-signal probe. Sources whose
 *       output changes WITHOUT a block-state write (comparator analog output
 *       held in its block entity, trapped-chest open count) only notify their
 *       neighbours: a member wire reached by such a notification is either the
 *       next takeover's trigger (every node near the trigger is re-read) or is
 *       evaluated by vanilla, which marks it sp-dirty
 *       ({@link #onVanillaEvaluate}).</li>
 *   <li><b>Topology change</b> in the halo (placed/broken/moved block, or a
 *       same-block state change that flips {@code isRedstoneConductor}, e.g.
 *       a slab becoming a double slab) — the network's read edges may have
 *       changed: the entry is dropped and the next cascade re-walks.</li>
 * </ul>
 *
 * <p>Server-thread confined (cascades run on the serial lane; a non-main caller
 * simply bypasses the cache and walks fresh, and its writes are queued).
 */
final class NetworkCache {

    private static final int MAX_NETWORKS = 1024;

    /** Manhattan radius around a takeover trigger whose nodes get their
     *  strong power re-read. A source that changes signal without a state
     *  write notifies wires adjacent to itself or to one block next to it
     *  (comparator front, trapped-chest floor); the wires it powers sit within
     *  3 blocks of the first one notified. */
    private static final int TRIGGER_SP_RADIUS = 4;

    /** Halos spanning more chunk columns than this are kept in a per-level
     *  scan list instead of the column index. */
    private static final int MAX_INDEXED_COLUMNS = 64;

    /** Script-cache key: trigger + entry discipline + a fingerprint of the
     *  network's full value state (powers + strong powers). A stale hit is
     *  harmless — the engine validates every EVAL marker against the live
     *  world and aborts to vanilla on the first divergence. */
    record ScriptKey(BlockPos trigger, int mode, long fingerprint) {}

    /** Per-level indices. Holds no reference to its {@link Level}, so the
     *  weak {@link #LEVELS} key can be collected with an unloaded level. */
    private static final class LevelIndex {
        final Map<BlockPos, Cached> byPos = new HashMap<>();
        /** Chunk column → networks whose halo overlaps it. */
        final Map<Long, List<Cached>> byColumn = new HashMap<>();
        /** Networks whose halo spans too many columns to index. */
        final List<Cached> wide = new ArrayList<>();
    }

    static final class Cached {
        final LevelIndex index;
        final Map<BlockPos, CascadeRecorder.Node> network;
        final Set<BlockPos> spDirty = new HashSet<>();
        /** Repeat cascades of a stationary machine: same trigger + same value
         *  state ⇒ same event script. Bounded; cleared wholesale when full. */
        final Map<ScriptKey, int[]> scripts = new HashMap<>(8);
        /** Bytes of {@link #scripts}, part of SCRIPT_BYTES. */
        long scriptBytes;
        final int x0, y0, z0, x1, y1, z1;   // halo bounds (+2)
        /** Chunk columns this entry is registered under; null = {@code wide}. */
        final long[] columns;

        Cached(LevelIndex index, Map<BlockPos, CascadeRecorder.Node> network) {
            this.index = index;
            this.network = network;
            int ax0 = Integer.MAX_VALUE, ay0 = Integer.MAX_VALUE, az0 = Integer.MAX_VALUE;
            int ax1 = Integer.MIN_VALUE, ay1 = Integer.MIN_VALUE, az1 = Integer.MIN_VALUE;
            for (BlockPos p : network.keySet()) {
                ax0 = Math.min(ax0, p.getX()); ay0 = Math.min(ay0, p.getY()); az0 = Math.min(az0, p.getZ());
                ax1 = Math.max(ax1, p.getX()); ay1 = Math.max(ay1, p.getY()); az1 = Math.max(az1, p.getZ());
            }
            x0 = ax0 - 2; y0 = ay0 - 2; z0 = az0 - 2;
            x1 = ax1 + 2; y1 = ay1 + 2; z1 = az1 + 2;
            int cx0 = x0 >> 4, cz0 = z0 >> 4, cx1 = x1 >> 4, cz1 = z1 >> 4;
            long count = (long) (cx1 - cx0 + 1) * (cz1 - cz0 + 1);
            if (count > MAX_INDEXED_COLUMNS) {
                columns = null;
            } else {
                columns = new long[(int) count];
                int i = 0;
                for (int cx = cx0; cx <= cx1; cx++) {
                    for (int cz = cz0; cz <= cz1; cz++) {
                        columns[i++] = ChunkPos.pack(cx, cz);
                    }
                }
            }
        }

        boolean inHalo(BlockPos p) {
            return p.getX() >= x0 && p.getX() <= x1
                    && p.getY() >= y0 && p.getY() <= y1
                    && p.getZ() >= z0 && p.getZ() <= z1;
        }
    }

    /** Level → its indices. Weak so unloaded levels (integrated-server world
     *  switches) are not pinned by the cache. */
    private static final Map<Level, LevelIndex> LEVELS = new WeakHashMap<>();
    /** Distinct cached networks across all levels, for the size cap. */
    private static final Set<Cached> ALL = new HashSet<>();

    /** Off-main setBlock events (random ticks and island-partitioned scheduled
     *  ticks run block handlers on Fabrolia workers). Silently ignoring them —
     *  the pre-2026-06-13 behavior — left cached networks blind to topology/sp
     *  changes written next to them and the engine then replayed a stale
     *  script. Buffered here (MPSC) and applied serially on main before ANY
     *  cache read. {@link #ALL_COUNT} mirrors ALL.size() so off-main writers
     *  can skip the buffer entirely while nothing is cached. */
    private static final java.util.concurrent.ConcurrentLinkedQueue<PendingSetBlock> OFF_MAIN_EVENTS =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static volatile int ALL_COUNT;
    /** Queued off-main events; past {@link #OFF_MAIN_CAP} new ones are dropped and the next
     *  main-thread drain clears the whole cache instead (a cache miss only costs a walk). */
    private static final java.util.concurrent.atomic.AtomicInteger OFF_MAIN_PENDING =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final int OFF_MAIN_CAP = 65_536;
    private static volatile boolean offMainOverflow;
    /** Bytes held by cached scripts across all networks (count caps alone allowed gigabytes). */
    private static final java.util.concurrent.atomic.AtomicLong SCRIPT_BYTES =
            new java.util.concurrent.atomic.AtomicLong();
    private static final long SCRIPT_BYTES_BUDGET = 64L << 20;

    /** {@code vanillaEval}: not a write — vanilla evaluated member wire {@code pos}
     *  ({@link #onVanillaEvaluate}); the state fields are unused. */
    private record PendingSetBlock(Level level, BlockPos pos, BlockState oldState, BlockState newState,
                                   boolean wireOnlyPowerChange, int newPower, boolean vanillaEval) {}

    private NetworkCache() {}

    /** The level's own physical server thread (an integrated server gets a new one per
     *  world it opens). Deliberately not {@code isSameThread()}: Fabrolia makes that
     *  true on region workers during player-actor turns, see
     *  {@link MinecraftServerThreadAccessor}. Workers' writes take the queue. */
    private static boolean onMain(Level level) {
        var server = level.getServer();
        return server != null
                && Thread.currentThread() == ((MinecraftServerThreadAccessor) server).rustmi$serverThread();
    }

    private static void queueOffMain(PendingSetBlock e) {
        if (OFF_MAIN_PENDING.incrementAndGet() > OFF_MAIN_CAP) {
            OFF_MAIN_PENDING.decrementAndGet();
            offMainOverflow = true;
            return;
        }
        OFF_MAIN_EVENTS.add(e);
    }

    private static void drainOffMainEvents() {
        if (offMainOverflow) {
            // Events were dropped: no cached entry can be trusted any more.
            offMainOverflow = false;
            while (OFF_MAIN_EVENTS.poll() != null) {
                OFF_MAIN_PENDING.decrementAndGet();
            }
            clear();
            return;
        }
        PendingSetBlock e;
        while ((e = OFF_MAIN_EVENTS.poll()) != null) {
            OFF_MAIN_PENDING.decrementAndGet();
            if (e.vanillaEval) {
                markSpDirty(e.level, e.pos);
            } else {
                applySetBlockOnMain(e.level, e.pos, e.oldState, e.newState, e.wireOnlyPowerChange, e.newPower);
            }
        }
    }

    /** A same-block state change that flips conductor status changes which
     *  diagonal neighbours a wire reads — a topology change, not a value one. */
    static boolean conductorChanged(Level level, BlockPos pos, BlockState oldState, BlockState newState) {
        return oldState.isRedstoneConductor(level, pos) != newState.isRedstoneConductor(level, pos);
    }

    /** Cached network containing {@code pos}, with sp-dirty nodes and every
     *  node near the trigger refreshed. */
    static Cached lookup(Level level, BlockPos pos,
                         CascadeRecorder.BlockSignalReader signals) {
        if (!onMain(level)) {
            return null;
        }
        drainOffMainEvents();
        LevelIndex idx = LEVELS.get(level);
        if (idx == null) {
            return null;
        }
        Cached c = idx.byPos.get(pos);
        if (c == null) {
            return null;
        }
        int tx = pos.getX(), ty = pos.getY(), tz = pos.getZ();
        for (CascadeRecorder.Node n : c.network.values()) {
            BlockPos p = n.pos;
            if (Math.abs(p.getX() - tx) + Math.abs(p.getY() - ty) + Math.abs(p.getZ() - tz) <= TRIGGER_SP_RADIUS) {
                n.strongPower = signals.rustmi$blockSignal(level, p);
                c.spDirty.remove(p);
            }
        }
        if (!c.spDirty.isEmpty()) {
            for (BlockPos p : c.spDirty) {
                CascadeRecorder.Node n = c.network.get(p);
                if (n != null) {
                    n.strongPower = signals.rustmi$blockSignal(level, p);
                }
            }
            c.spDirty.clear();
        }
        return c;
    }

    private static final int MAX_SCRIPTS_PER_NET = 8;
    private static final int MAX_CACHED_SCRIPT_EVENTS = 32_768;

    /** FNV-1a over the full value state, in the network map's (stable per map
     *  instance) iteration order. */
    static long fingerprint(Map<BlockPos, CascadeRecorder.Node> network) {
        long h = 0xcbf29ce484222325L;
        for (CascadeRecorder.Node n : network.values()) {
            h = (h ^ n.power) * 0x100000001b3L;
            h = (h ^ n.strongPower) * 0x100000001b3L;
        }
        return h;
    }

    static int[] scriptFor(Cached c, ScriptKey key) {
        return c.scripts.get(key);
    }

    static void storeScript(Cached c, ScriptKey key, int[] events) {
        if (events.length > MAX_CACHED_SCRIPT_EVENTS * 5) {
            return;
        }
        if (c.scripts.size() >= MAX_SCRIPTS_PER_NET) {
            SCRIPT_BYTES.addAndGet(-c.scriptBytes);
            c.scriptBytes = 0;
            c.scripts.clear();
        }
        long bytes = events.length * 4L;
        if (SCRIPT_BYTES.get() + bytes > SCRIPT_BYTES_BUDGET) {
            return;   // not cached: the next identical cascade simulates again
        }
        int[] prev = c.scripts.put(key, events);
        long delta = bytes - (prev == null ? 0 : prev.length * 4L);
        c.scriptBytes += delta;
        SCRIPT_BYTES.addAndGet(delta);
    }

    static Cached store(Level level, Map<BlockPos, CascadeRecorder.Node> network) {
        if (!onMain(level)) {
            return null;
        }
        if (ALL.size() >= MAX_NETWORKS) {
            clear();   // blunt cap; a busy server re-walks, correctness unaffected
        }
        LevelIndex idx = LEVELS.computeIfAbsent(level, k -> new LevelIndex());
        // A member already indexed under another entry means that entry's
        // shape is outdated — it must not stay reachable through its other
        // members with values this entry now owns.
        for (BlockPos p : network.keySet()) {
            Cached prev = idx.byPos.get(p);
            if (prev != null) {
                drop(prev);
            }
        }
        Cached c = new Cached(idx, network);
        ALL.add(c);
        ALL_COUNT = ALL.size();
        for (BlockPos p : network.keySet()) {
            idx.byPos.put(p, c);
        }
        if (c.columns == null) {
            idx.wide.add(c);
        } else {
            for (long col : c.columns) {
                idx.byColumn.computeIfAbsent(col, k -> new ArrayList<>(2)).add(c);
            }
        }
        return c;
    }

    /**
     * Mirror sentinel feed — every server-side setBlockState. Keeps cached
     * values live and drops entries whose topology may have changed.
     *
     * @param wireOnlyPowerChange the write is a wire POWER-value set (same
     *                            block, POWER differs) — the benign kind.
     */
    static void onSetBlock(Level level, BlockPos pos, BlockState oldState, BlockState newState,
                           boolean wireOnlyPowerChange, int newPower) {
        if (!onMain(level)) {
            // Worker-side write (random tick / island-sliced scheduled tick):
            // buffer for main. pos may be a MutableBlockPos on these paths.
            if (ALL_COUNT > 0) {
                queueOffMain(new PendingSetBlock(
                        level, pos.immutable(), oldState, newState, wireOnlyPowerChange, newPower, false));
            }
            return;
        }
        drainOffMainEvents();
        applySetBlockOnMain(level, pos, oldState, newState, wireOnlyPowerChange, newPower);
    }

    static boolean hasEntries() {
        return ALL_COUNT > 0;
    }

    static boolean isMainThread(Level level) {
        return onMain(level);
    }

    /**
     * A topology-changing write is about to happen (setBlockState HEAD, before its
     * removal/placement side effects run). Drops the entries it touches right away;
     * the RETURN sentinel ({@link #onSetBlock}) repeats the drop for anything cached
     * in between. Worker-side writes are left to that RETURN event: lookups only
     * happen on the main thread.
     */
    static void onTopologyWriteStart(Level level, BlockPos pos, BlockState oldState, BlockState newState) {
        if (!onMain(level)) {
            return;
        }
        drainOffMainEvents();
        applySetBlockOnMain(level, pos, oldState, newState, false, -1);
    }

    /**
     * Vanilla is about to evaluate the wire at {@code pos} (no takeover). If it is a
     * cached member, its strong power may have changed without any block write
     * (comparator analog output, trapped chest) and nothing else would mark it:
     * re-read it on the next lookup.
     */
    static void onVanillaEvaluate(Level level, BlockPos pos) {
        if (ALL_COUNT == 0) {
            return;
        }
        if (!onMain(level)) {
            queueOffMain(new PendingSetBlock(level, pos.immutable(), null, null, false, 0, true));
            return;
        }
        drainOffMainEvents();
        markSpDirty(level, pos);
    }

    private static void markSpDirty(Level level, BlockPos pos) {
        LevelIndex idx = LEVELS.get(level);
        Cached c = idx == null ? null : idx.byPos.get(pos);
        if (c != null) {
            c.spDirty.add(pos.immutable());
        }
    }

    private static void applySetBlockOnMain(Level level, BlockPos pos, BlockState oldState, BlockState newState,
                                            boolean wireOnlyPowerChange, int newPower) {
        if (ALL.isEmpty()) {
            return;
        }
        LevelIndex idx = LEVELS.get(level);
        if (idx == null) {
            return;
        }
        Cached c = idx.byPos.get(pos);
        if (wireOnlyPowerChange) {
            if (c != null) {
                CascadeRecorder.Node n = c.network.get(pos);
                if (n != null) {
                    n.power = newPower;
                }
            }
            return;
        }
        if (c != null) {
            // A network member stopped being (just) wire — topology changed.
            drop(c);
            return;
        }
        // Non-member write: affects any cached network whose halo covers pos.
        // Value-only change (lever/observer/lamp flipping a property) →
        // strongPower of nearby members may differ — mark dirty. Different
        // block or flipped conductor status → read edges may differ — drop.
        List<Cached> column = idx.byColumn.get(ChunkPos.pack(pos));
        if (column == null && idx.wide.isEmpty()) {
            return;
        }
        boolean topology = oldState.getBlock() != newState.getBlock()
                || conductorChanged(level, pos, oldState, newState);
        List<Cached> toDrop = null;
        if (column != null) {
            toDrop = visitHalo(column, pos, topology, toDrop);
        }
        toDrop = visitHalo(idx.wide, pos, topology, toDrop);
        if (toDrop != null) {
            for (Cached d : toDrop) {
                drop(d);
            }
        }
    }

    /** Marks sp-dirty members near {@code pos} (or collects the entry for a
     *  drop when {@code topology}) for every candidate whose halo covers it. */
    private static List<Cached> visitHalo(List<Cached> candidates, BlockPos pos, boolean topology,
                                          List<Cached> toDrop) {
        BlockPos.MutableBlockPos probe = null;
        for (int i = 0, n = candidates.size(); i < n; i++) {
            Cached cand = candidates.get(i);
            if (!cand.inHalo(pos)) {
                continue;
            }
            if (topology) {
                if (toDrop == null) {
                    toDrop = new ArrayList<>(2);
                }
                toDrop.add(cand);
                continue;
            }
            if (probe == null) {
                probe = new BlockPos.MutableBlockPos();
            }
            // sp reads reach 2 blocks (direct + through a conductor).
            for (int dx = -2; dx <= 2; dx++) {
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        probe.setWithOffset(pos, dx, dy, dz);
                        if (cand.network.containsKey(probe)) {
                            cand.spDirty.add(probe.immutable());
                        }
                    }
                }
            }
        }
        return toDrop;
    }

    private static void drop(Cached c) {
        if (!ALL.remove(c)) {
            return;
        }
        ALL_COUNT = ALL.size();
        SCRIPT_BYTES.addAndGet(-c.scriptBytes);
        c.scriptBytes = 0;
        LevelIndex idx = c.index;
        for (BlockPos p : c.network.keySet()) {
            idx.byPos.remove(p, c);
        }
        if (c.columns == null) {
            idx.wide.remove(c);
        } else {
            for (long col : c.columns) {
                List<Cached> list = idx.byColumn.get(col);
                if (list != null && list.remove(c) && list.isEmpty()) {
                    idx.byColumn.remove(col);
                }
            }
        }
    }

    static void clear() {
        LEVELS.clear();
        ALL.clear();
        ALL_COUNT = 0;
        SCRIPT_BYTES.set(0);
    }

    static int size() {
        return ALL.size();
    }

    /** Paranoia path for aborts: cached powers for the network containing
     *  {@code pos} may be stale — drop the entry, the next cascade re-walks. */
    static void dropContaining(Level level, BlockPos pos) {
        if (!onMain(level)) {
            return;
        }
        LevelIndex idx = LEVELS.get(level);
        Cached c = idx == null ? null : idx.byPos.get(pos);
        if (c != null) {
            drop(c);
        }
    }
}
