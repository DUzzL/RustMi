package com.github.tatercertified.nalim.redstone;

import com.github.tatercertified.nalim.cramming.RustmiCramming;
import com.github.tatercertified.nalim.rust.RustmiLib;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RedstoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * R3 takeover engine (docs/CODEMAP.md, "Redstone wire"): replace vanilla wire
 * cascades with the Rust simulator's precomputed event script, emitted through
 * the SAME world calls vanilla makes — {@code setBlock(pos, POWER, flags=2)}
 * and {@code updateNeighborsAt(pos, wire)} — in the SAME order. Non-wire
 * neighbors (pistons, lamps, comparators…) receive their neighborChanged
 * dispatches from the live {@code CollectingNeighborUpdater} exactly as in
 * vanilla; only the ~25-block-read recomputation per wire evaluation is
 * replaced by the script.
 *
 * <p><b>Continuation-point structure.</b> The script is a sequence of
 * {@code EVAL pos oldPower} markers, each followed by that evaluation's
 * {@code SET}/{@code UPD} span. The takeover emits the first span inside the
 * triggering evaluator call; every later span is emitted when the live drain
 * dispatches the corresponding (suppressed) wire evaluation — the EVAL marker
 * is verified against the live position AND the wire's current power before
 * each span, so any divergence (sim bug, concurrent world change) aborts to
 * vanilla mid-cascade with the world in a valid intermediate state.
 *
 * <p><b>Abort = conservative re-derivation.</b> On any mismatch, foreign
 * mutation in the network halo, or script leftover: suppression stops (wire
 * evals run vanilla again) and every network wire whose current power differs
 * from the script's final value gets a {@code neighborChanged}, re-deriving
 * its power from the actual world. Wires already at their final value are
 * re-evaluated as no-ops (vanilla emits nothing when power is unchanged), so
 * over-enqueueing is observably safe.
 *
 * <p>Bails to untouched vanilla: engine off, client side, network overflow
 * (&gt; {@link CascadeRecorder#maxNetwork()}), network smaller than
 * {@link #MIN_NETWORK} (setup cost beats the win — Ferrite's threshold
 * lesson), nested foreign-network evaluations, sim returning no events.
 *
 * <p>Same-thread mutation sentinel only: cross-thread setBlock during a
 * cascade is a pre-existing Fabrolia condition (vanilla reads race region
 * workers identically), not something the script changes.
 */
public final class CascadeEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger("Rustmi-RedstoneEngine");

    private static final int MIN_NETWORK = 8;
    private static final int EVENT_STRIDE = 20;
    private static final int TAG_EVAL = 0, TAG_SET = 1, TAG_UPD = 2;

    private static final Path STATE_FILE = Paths.get("config", "rustmi", "redstone-engine.properties");
    private static volatile boolean enabled = CascadeRecorder.loadPersistedState(STATE_FILE, true);

    private static final AtomicLong HANDLES = new AtomicLong(0xE9617E);

    private static final AtomicLong TAKEOVERS = new AtomicLong();
    private static final AtomicLong EVALS_SCRIPTED = new AtomicLong();
    private static final AtomicLong BAIL_SMALL = new AtomicLong();
    private static final AtomicLong BAIL_OVERFLOW = new AtomicLong();
    private static final AtomicLong BAIL_FOREIGN = new AtomicLong();
    private static final AtomicLong BAIL_SIM = new AtomicLong();
    private static final AtomicLong ABORT_MISMATCH = new AtomicLong();
    private static final AtomicLong ABORT_MUTATION = new AtomicLong();
    private static final AtomicLong ABORT_LEFTOVER = new AtomicLong();
    private static final AtomicLong SETUP_NANOS = new AtomicLong();
    private static final AtomicLong CACHE_HITS = new AtomicLong();
    private static final AtomicLong CACHE_MISSES = new AtomicLong();
    private static final AtomicLong SCRIPT_HITS = new AtomicLong();
    private static final AtomicLong SCRIPT_MISSES = new AtomicLong();

    private CascadeEngine() {}

    public static boolean isEnabled() {
        return enabled;
    }

    public static synchronized void setEnabled(boolean on) {
        if (enabled == on) return;
        enabled = on;
        if (!on) {
            NetworkCache.clear();
        }
        CascadeRecorder.persistState(STATE_FILE, "redstone takeover engine (R3)", on);
        LOGGER.info("[redstone-engine] {}", on ? "ENABLED" : "DISABLED");
    }

    public static String status() {
        return "engine=" + enabled
                + " | takeovers=" + TAKEOVERS.get()
                + " scriptedEvals=" + EVALS_SCRIPTED.get()
                + " cache{hits=" + CACHE_HITS.get()
                + " misses=" + CACHE_MISSES.get()
                + " nets=" + NetworkCache.size() + "}"
                + " script{hits=" + SCRIPT_HITS.get()
                + " misses=" + SCRIPT_MISSES.get() + "}"
                + " bail{small=" + BAIL_SMALL.get()
                + " overflow=" + BAIL_OVERFLOW.get()
                + " foreign=" + BAIL_FOREIGN.get()
                + " sim=" + BAIL_SIM.get() + "}"
                + " abort{evalMismatch=" + ABORT_MISMATCH.get()
                + " mutation=" + ABORT_MUTATION.get()
                + " leftover=" + ABORT_LEFTOVER.get() + "}"
                + String.format(" | setup %.2fms", SETUP_NANOS.get() / 1e6);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Per-thread session
    // ─────────────────────────────────────────────────────────────────────

    private static final class Session {
        final Level level;
        final RedstoneWireBlock wire;
        final Map<BlockPos, CascadeRecorder.Node> network;
        final Map<BlockPos, Integer> finalPower;
        final long outAddr;
        final int count;
        int cursor;
        boolean aborted;
        boolean emittingSet;
        /** A foreign topology write in the halo has started (HEAD of setBlockState) and
         *  its side effects are running; evaluations go vanilla until its RETURN aborts. */
        boolean foreignWrite;
        /** The takeover's own emission frame is on the stack: a depth-0 entry
         *  drains each emitted UPD synchronously (depth 1→0 between UPDs),
         *  and those transient exits must not end the session. */
        boolean entryEmitting;
        /** Entry mode (0=InDrain, 1=Depth0) — what discipline Rust simulated. */
        final int entryMode;
        /** DRAIN_DEPTH at the moment of takeover (for diagnostics). */
        final int entryDrainDepth;
        /** Counts shortcut+evaluator dispatches the script accepted (cursor moves). */
        int dispatchesAccepted;
        /** Counts dispatches that fell through (foreign, mismatch, etc). */
        int dispatchesBailed;
        // network bounds + read halo (wire reads reach 2 blocks: diagonals
        // and strong-power probes of conductor neighbours)
        final int x0, y0, z0, x1, y1, z1;

        Session(Level level, RedstoneWireBlock wire,
                Map<BlockPos, CascadeRecorder.Node> network, Map<BlockPos, Integer> finalPower,
                long outAddr, int count, int entryMode, int entryDrainDepth) {
            this.entryMode = entryMode;
            this.entryDrainDepth = entryDrainDepth;
            this.level = level;
            this.wire = wire;
            this.network = network;
            this.finalPower = finalPower;
            this.outAddr = outAddr;
            this.count = count;
            int ax0 = Integer.MAX_VALUE, ay0 = Integer.MAX_VALUE, az0 = Integer.MAX_VALUE;
            int ax1 = Integer.MIN_VALUE, ay1 = Integer.MIN_VALUE, az1 = Integer.MIN_VALUE;
            for (BlockPos p : network.keySet()) {
                ax0 = Math.min(ax0, p.getX()); ay0 = Math.min(ay0, p.getY()); az0 = Math.min(az0, p.getZ());
                ax1 = Math.max(ax1, p.getX()); ay1 = Math.max(ay1, p.getY()); az1 = Math.max(az1, p.getZ());
            }
            this.x0 = ax0 - 2; this.y0 = ay0 - 2; this.z0 = az0 - 2;
            this.x1 = ax1 + 2; this.y1 = ay1 + 2; this.z1 = az1 + 2;
        }

        boolean inHalo(BlockPos p) {
            return p.getX() >= x0 && p.getX() <= x1
                    && p.getY() >= y0 && p.getY() <= y1
                    && p.getZ() >= z0 && p.getZ() <= z1;
        }
    }

    private static final ThreadLocal<Session> SESSION = new ThreadLocal<>();
    /** The chain budget native/src/util/redstone/sim.rs simulates (VANILLA_MAX_CHAIN). */
    private static final int SIM_MAX_CHAINED_UPDATES = 1_000_000;
    /** Live CollectingNeighborUpdater.addAndRun depth on this thread (engine's
     *  own counter — the recorder's is gated on its capture toggles). */
    private static final ThreadLocal<int[]> DRAIN_DEPTH = ThreadLocal.withInitial(() -> new int[1]);
    /** Sticky reject latch — the R3v1 post-mortem lesson: a vanilla cascade
     *  over a non-qualifying network calls the evaluator THOUSANDS of times,
     *  and re-running the walk on every one is quadratic (87k walks, +17s).
     *  Any bail latches takeover attempts off until the drain unwinds. */
    private static final ThreadLocal<boolean[]> REJECT_LATCH = ThreadLocal.withInitial(() -> new boolean[1]);

    // ─────────────────────────────────────────────────────────────────────
    // Hooks
    // ─────────────────────────────────────────────────────────────────────

    /** CollectingNeighborUpdater.addAndRun HEAD. Counted even while the
     *  engine is off: a toggle between a drain's HEAD and RETURN must not
     *  leave this thread's depth permanently skewed. */
    public static void onDrainEnter() {
        DRAIN_DEPTH.get()[0]++;
    }

    /** CollectingNeighborUpdater.addAndRun RETURN (ungated, see onDrainEnter). */
    public static void onDrainExit() {
        int[] d = DRAIN_DEPTH.get();
        if (d[0] > 0 && --d[0] == 0) {
            REJECT_LATCH.get()[0] = false;
            Session s = SESSION.get();
            if (s != null && !s.entryEmitting) {
                endSession(s);
            }
        }
    }

    /**
     * Dispatch-chain shortcut (item 1 of the post-R3 squeeze): intercepts a
     * network wire's neighborChanged BEFORE the canSurvive check, the carpet
     * redirect and the evaluator entry — when the next script event is the
     * EVAL marker for exactly this position, the span is emitted right here
     * and the whole vanilla chain is skipped. ~68M suppressed evaluations per
     * session each paid that chain for nothing.
     *
     * <p>Safe to skip canSurvive: a support-block change in the halo is a
     * block-identity change — the mutation sentinel aborts the script before
     * any such wire could be dispatched. Non-matching cases fall through to
     * vanilla, where the evaluator-entry hook keeps the old validation path.
     */
    public static boolean shortcutNeighborChanged(Level level, BlockPos pos, BlockState state) {
        if (!enabled) {
            return false;
        }
        Session s = SESSION.get();
        if (s == null || s.aborted || s.foreignWrite || s.level != level || s.cursor >= s.count
                || !s.network.containsKey(pos)) {
            return false;
        }
        var u = RustmiCramming.theUnsafe();
        long base = s.outAddr + (long) s.cursor * EVENT_STRIDE;
        if (u.getInt(base) != TAG_EVAL
                || u.getInt(base + 4) != pos.getX()
                || u.getInt(base + 8) != pos.getY()
                || u.getInt(base + 12) != pos.getZ()) {
            s.dispatchesBailed++;
            return false;   // not this wire's turn — vanilla path validates
        }
        CascadeRecorder.poisonCurrentBurst();
        s.dispatchesAccepted++;
        return continueScript(s, pos, state);
    }

    /**
     * Evaluator entry. Returns true when the evaluation is handled by the
     * script (caller cancels the vanilla body).
     */
    public static boolean onEvaluatorEnter(Level level, BlockPos pos, BlockState state,
                                           RedstoneWireBlock wire,
                                           CascadeRecorder.BlockSignalReader signals) {
        if (!enabled || level.isClientSide()) {
            return false;
        }
        if (scriptOrTakeover(level, pos, state, wire, signals)) {
            return true;
        }
        NetworkCache.onVanillaEvaluate(level, pos);
        return false;
    }

    private static boolean scriptOrTakeover(Level level, BlockPos pos, BlockState state,
                                            RedstoneWireBlock wire,
                                            CascadeRecorder.BlockSignalReader signals) {
        Session s = SESSION.get();
        if (s != null) {
            if (s.aborted || s.foreignWrite || s.level != level) {
                return false;          // vanilla finishes the cascade
            }
            if (!s.network.containsKey(pos)) {
                BAIL_FOREIGN.incrementAndGet();
                s.dispatchesBailed++;
                return false;          // foreign network — no nested takeover
            }
            if (s.cursor >= s.count) {
                // Script fully consumed: a SECOND trigger hit the network
                // within the same drain (e.g. two levers in one tick). The
                // finished session retires cleanly; this evaluation starts a
                // fresh takeover from the current world state.
                SESSION.remove();
                return tryTakeover(level, pos, state, wire, signals);
            }
            CascadeRecorder.poisonCurrentBurst();
            s.dispatchesAccepted++;
            return continueScript(s, pos, state);
        }
        if (REJECT_LATCH.get()[0]) {
            return false;   // this drain already failed to qualify — vanilla
        }
        // Depth-0 entries (block place/remove → onPlace → updatePowerStrength)
        // are fundamentally fragile under takeover: each emitted UPD opens its
        // own fresh drain, and any divergence from the Rust sim's predicted
        // dispatch — chunk unloaded, concurrent worker write, mod side-effect
        // between walk and live emission — leaves the script stuck waiting
        // for a TAG_EVAL that the live world never produces (cursor=3 with
        // accepted/bailed=0/0 in production; see 2026-06-13). The vanilla
        // evaluator handles these cases identically and without ceremony, so
        // we bail. The InDrain discipline (DRAIN_DEPTH ≥ 1) where the script
        // and the live drain share one collector keeps the engine's win.
        if (DRAIN_DEPTH.get()[0] == 0) {
            return false;
        }
        return tryTakeover(level, pos, state, wire, signals);
    }

    private static boolean tryTakeover(Level level, BlockPos pos, BlockState state,
                                       RedstoneWireBlock wire,
                                       CascadeRecorder.BlockSignalReader signals) {
        // The removal path evaluates the wire with its old state after the block is gone
        // (affectNeighborsAfterRemoval); the network model cannot describe that position.
        if (level.getBlockState(pos) != state) {
            return false;
        }
        // The Rust collector simulates vanilla's default chain budget; with another
        // max-chained-neighbor-updates the live drain drops updates the script expects.
        var server = level.getServer();
        if (server == null || server.getMaxChainedNeighborUpdates() != SIM_MAX_CHAINED_UPDATES) {
            return false;
        }
        long t0 = System.nanoTime();
        NetworkCache.Cached cached = NetworkCache.lookup(level, pos, signals);
        Map<BlockPos, CascadeRecorder.Node> network;
        if (cached != null) {
            CACHE_HITS.incrementAndGet();
            network = cached.network;
        } else {
            CACHE_MISSES.incrementAndGet();
            network = CascadeRecorder.walkNetwork(level, pos, wire, signals, CascadeRecorder.maxNetwork());
            if (network == null) {
                BAIL_OVERFLOW.incrementAndGet();
                REJECT_LATCH.get()[0] = true;
                return false;
            }
            if (network.size() < MIN_NETWORK) {
                BAIL_SMALL.incrementAndGet();
                // No latch: tiny walks are cheap, and unrelated small nets
                // inside one drain must each get their own chance.
                return false;
            }
            cached = NetworkCache.store(level, network);
        }

        // Entry discipline from the LIVE collector depth: inside a running
        // drain → batch mode (0); depth 0 (block place/remove path) → each
        // updateNeighborsAt drains immediately (1). The recorder had to guess
        // adaptively; the engine reads the truth.
        int mode = DRAIN_DEPTH.get()[0] > 0 ? 0 : 1;
        ReplayBuffers buf = ReplayBuffers.get();
        var u0 = RustmiCramming.theUnsafe();
        int count;

        // Item-2 script cache: a stationary machine repeats the same cascade
        // every cycle — same trigger + same value state ⇒ same event script.
        // A stale hit is caught by the per-EVAL marker validation (abort →
        // vanilla re-derive), so the fingerprint needs no cryptographic care.
        NetworkCache.ScriptKey key = cached == null ? null
                : new NetworkCache.ScriptKey(pos.immutable(), mode, NetworkCache.fingerprint(network));
        int[] script = cached == null ? null : NetworkCache.scriptFor(cached, key);
        if (script != null) {
            SCRIPT_HITS.incrementAndGet();
            count = script.length / 5;
            u0.copyMemory(script, sun.misc.Unsafe.ARRAY_INT_BASE_OFFSET,
                    null, buf.outAddr, (long) script.length * 4);
        } else {
            SCRIPT_MISSES.incrementAndGet();
            List<CascadeRecorder.Node> nodes = new ArrayList<>(network.size());
            Map<BlockPos, Integer> index = new HashMap<>(network.size() * 2);
            int edgeCursor = buf.marshal(network, nodes, index);
            Integer trigger = index.get(pos);
            if (trigger == null) {
                BAIL_SIM.incrementAndGet();
                return false;
            }
            long handle = HANDLES.incrementAndGet();
            if (RustmiLib.oxi_redstone_begin(handle, buf.nodesAddr, nodes.size(), buf.edgesAddr, edgeCursor) != 0) {
                BAIL_SIM.incrementAndGet();
                return false;
            }
            count = RustmiLib.oxi_redstone_entry(handle, trigger, mode, buf.outAddr, CascadeRecorder.maxEvents());
            // The script now lives in buf.out; the native network is not
            // needed for the rest of the session.
            RustmiLib.oxi_redstone_end(handle);
            if (count <= 0) {
                BAIL_SIM.incrementAndGet();
                REJECT_LATCH.get()[0] = true;   // the same network would fail every eval of this drain
                return false;
            }
            if (cached != null && key != null) {
                int[] copy = new int[count * 5];
                u0.copyMemory(null, buf.outAddr, copy, sun.misc.Unsafe.ARRAY_INT_BASE_OFFSET,
                        (long) copy.length * 4);
                NetworkCache.storeScript(cached, key, copy);
            }
        }

        var u = RustmiCramming.theUnsafe();
        Map<BlockPos, Integer> finalPower = new HashMap<>(network.size() * 2);
        for (int i = 0; i < count; i++) {
            long base = buf.outAddr + (long) i * EVENT_STRIDE;
            if (u.getInt(base) == TAG_SET) {
                finalPower.put(new BlockPos(u.getInt(base + 4), u.getInt(base + 8), u.getInt(base + 12)),
                        u.getInt(base + 16));
            }
        }

        Session s = new Session(level, wire, network, finalPower, buf.outAddr, count,
                mode, DRAIN_DEPTH.get()[0]);
        SESSION.set(s);
        SETUP_NANOS.addAndGet(System.nanoTime() - t0);
        TAKEOVERS.incrementAndGet();
        CascadeRecorder.poisonCurrentBurst();

        s.entryEmitting = true;
        boolean handled;
        try {
            handled = continueScript(s, pos, state);
        } finally {
            s.entryEmitting = false;
        }
        // Depth-0 entry: all spans drained synchronously inside the emission
        // above — the session is complete here and no enclosing drain will
        // fire onDrainExit for us. (In-drain entries end via onDrainExit.)
        if (SESSION.get() == s && DRAIN_DEPTH.get()[0] == 0) {
            endSession(s);
        }
        return handled;
    }

    /**
     * Verify the EVAL marker at the cursor against the live evaluation, then
     * emit its span (SETs and UPDs up to the next EVAL marker). Returns true
     * when the vanilla evaluation must be skipped.
     */
    private static boolean continueScript(Session s, BlockPos pos, BlockState state) {
        var u = RustmiCramming.theUnsafe();
        if (s.cursor >= s.count) {
            abort(s, ABORT_LEFTOVER, "eval after script end @ " + pos.toShortString());
            return false;
        }
        long base = s.outAddr + (long) s.cursor * EVENT_STRIDE;
        int tag = u.getInt(base);
        int ex = u.getInt(base + 4), ey = u.getInt(base + 8), ez = u.getInt(base + 12);
        int oldPower = u.getInt(base + 16);
        int livePower = state.hasProperty(RedstoneWireBlock.POWER)
                ? state.getValue(RedstoneWireBlock.POWER) : -1;
        if (tag != TAG_EVAL || ex != pos.getX() || ey != pos.getY() || ez != pos.getZ()
                || oldPower != livePower) {
            abort(s, ABORT_MISMATCH, "expected EVAL " + ex + " " + ey + " " + ez + " " + oldPower
                    + ", live " + pos.toShortString() + " " + livePower);
            return false;          // this evaluation runs vanilla from current state
        }
        s.cursor++;
        EVALS_SCRIPTED.incrementAndGet();

        BlockPos.MutableBlockPos cursorPos = new BlockPos.MutableBlockPos();
        while (s.cursor < s.count && !s.aborted) {
            base = s.outAddr + (long) s.cursor * EVENT_STRIDE;
            tag = u.getInt(base);
            if (tag == TAG_EVAL) {
                break;             // next continuation point — emitted at its dispatch
            }
            int x = u.getInt(base + 4), y = u.getInt(base + 8), z = u.getInt(base + 12);
            s.cursor++;
            if (tag == TAG_SET) {
                int power = u.getInt(base + 16);
                BlockPos p = cursorPos.set(x, y, z).immutable();
                BlockState cur = s.level.getBlockState(p);
                if (!cur.is(s.wire) || !cur.hasProperty(RedstoneWireBlock.POWER)) {
                    abort(s, ABORT_MUTATION, "SET target no longer wire @ " + p.toShortString());
                    return true;   // partial span emitted — vanilla must NOT rerun this eval
                }
                s.emittingSet = true;
                try {
                    s.level.setBlock(p, cur.setValue(RedstoneWireBlock.POWER, power), 2);
                } finally {
                    s.emittingSet = false;
                }
            } else if (tag == TAG_UPD) {
                // May synchronously recurse into continueScript (depth-0
                // discipline) or enqueue into the live drain (in-drain).
                s.level.updateNeighborsAt(cursorPos.set(x, y, z).immutable(), s.wire);
            } else {
                abort(s, ABORT_MISMATCH, "unknown tag " + tag);
                return true;
            }
        }
        return true;
    }

    /**
     * LevelChunk.setBlockState sentinel — every block write (client-side
     * chunks are ignored). Feeds the network cache (wire POWER values,
     * sp-dirty marks, topology drops) and aborts a running script on
     * topology-relevant foreign writes in the session's own level.
     *
     * <p>Same-BLOCK value changes (lamp lighting, door opening, rail reshaping
     * mid-cascade) do NOT abort unless they flip the block's conductor
     * status: signal-source value flips (lever/observer/comparator) are
     * scheduled or player-driven and never happen inside a drain, and the
     * rest carry no signal. The cache still marks nearby nodes sp-dirty as a
     * safety net for the next walk-free takeover.
     */
    public static void onWorldSetBlock(Level level, BlockPos pos, BlockState oldState, BlockState newState) {
        if (!enabled || level.isClientSide()) {
            return;
        }
        boolean wirePowerOnly = oldState.getBlock() == newState.getBlock()
                && oldState.getBlock() instanceof RedstoneWireBlock;
        NetworkCache.onSetBlock(level, pos, oldState, newState, wirePowerOnly,
                wirePowerOnly ? newState.getValue(RedstoneWireBlock.POWER) : -1);

        Session s = SESSION.get();
        if (s == null || s.aborted || s.emittingSet || s.level != level) {
            return;
        }
        if (!s.inHalo(pos)) {
            return;
        }
        if (oldState.getBlock() == newState.getBlock()
                && !NetworkCache.conductorChanged(level, pos, oldState, newState)) {
            return;   // benign value-only change — script stays valid
        }
        abort(s, ABORT_MUTATION, "foreign setBlock " + oldState.getBlock().getDescriptionId()
                + "→" + newState.getBlock().getDescriptionId() + " @ " + pos.toShortString());
    }

    /**
     * LevelChunk.setBlockState HEAD — before the section write and before the
     * removal/placement side effects ({@code affectNeighborsAfterRemoval},
     * {@code onPlace}) that run neighbor drains and wire evaluations inside the
     * same call. A write that changes a block's identity or conductor status
     * drops the cached networks it touches now, so those evaluations re-walk the
     * live world, and stops a running script in its halo from emitting until the
     * RETURN sentinel aborts it.
     */
    public static void onWorldSetBlockHead(LevelChunk chunk, BlockPos pos, BlockState newState) {
        if (!enabled) {
            return;
        }
        Level level = chunk.getLevel();
        if (level.isClientSide()) {
            return;
        }
        Session s = SESSION.get();
        boolean inSession = s != null && !s.aborted && !s.emittingSet && s.level == level && s.inHalo(pos);
        if (!inSession && !NetworkCache.hasEntries()) {
            return;
        }
        BlockState oldState = chunk.getBlockState(pos);
        if (oldState == newState) {
            return;
        }
        if (oldState.getBlock() == newState.getBlock()
                && !NetworkCache.conductorChanged(level, pos, oldState, newState)) {
            return;   // value-only change — the RETURN sentinel keeps cached values live
        }
        NetworkCache.onTopologyWriteStart(level, pos, oldState, newState);
        if (inSession) {
            s.foreignWrite = true;
        }
    }

    /**
     * Stop the script, let vanilla evaluations resume, and re-derive every
     * wire that has not reached its scripted final power. Re-derivation goes
     * through the live collector in deterministic network order; wires already
     * correct re-evaluate as no-ops.
     */
    private static void abort(Session s, AtomicLong counter, String why) {
        if (s.aborted) {
            return;
        }
        s.aborted = true;
        counter.incrementAndGet();
        // Cached powers for this network may now disagree with the world —
        // drop the entry; the post-abort vanilla tail re-walks next time.
        for (BlockPos member : s.network.keySet()) {
            NetworkCache.dropContaining(s.level, member);
            break;   // one member is enough — entries are per-network
        }
        LOGGER.warn("[redstone-engine] abort: {} (cursor {}/{})", why, s.cursor, s.count);
        for (Map.Entry<BlockPos, CascadeRecorder.Node> e : s.network.entrySet()) {
            Integer want = s.finalPower.get(e.getKey());
            if (want == null) {
                continue;
            }
            BlockState cur = s.level.getBlockState(e.getKey());
            int power = cur.is(s.wire) && cur.hasProperty(RedstoneWireBlock.POWER)
                    ? cur.getValue(RedstoneWireBlock.POWER) : Integer.MIN_VALUE;
            if (power != want) {
                s.level.neighborChanged(e.getKey(), s.wire, null);
            }
        }
    }

    /** Rate-limit ABORT_LEFTOVER warns: 1 line/sec across the server (most
     *  bursts come from the same lag-machine and produce identical context;
     *  the counter in {@code /rustmi} status tracks the true total). */
    private static final java.util.concurrent.atomic.AtomicLong LAST_LEFTOVER_WARN_MS =
            new java.util.concurrent.atomic.AtomicLong();

    private static void endSession(Session s) {
        if (!s.aborted && s.cursor < s.count) {
            // ABORT_LEFTOVER diagnostic: include the entry discipline, the
            // EVAL position the script expected next (which never arrived
            // through the live shortcut/evaluator path), and the live drain
            // depth at endSession time. These together pinpoint which
            // dispatch the script anticipated but the live drain missed.
            var u = RustmiCramming.theUnsafe();
            long base = s.outAddr + (long) s.cursor * EVENT_STRIDE;
            int expectedTag = u.getInt(base);
            int ex = u.getInt(base + 4), ey = u.getInt(base + 8), ez = u.getInt(base + 12);
            int expectedPower = u.getInt(base + 16);
            String why = "drain ended with " + (s.count - s.cursor) + " events left";
            // abort() already does the dedup/log; suppress its warn and emit
            // our richer one with rate-limiting just below.
            s.aborted = true;
            ABORT_LEFTOVER.incrementAndGet();
            for (BlockPos m : s.network.keySet()) {
                NetworkCache.dropContaining(s.level, m);
                break;
            }
            long nowMs = java.lang.System.currentTimeMillis();
            long last = LAST_LEFTOVER_WARN_MS.get();
            if (nowMs - last >= 1000 && LAST_LEFTOVER_WARN_MS.compareAndSet(last, nowMs)) {
                String modeName = s.entryMode == 0 ? "InDrain" : "Depth0";
                String tagName = expectedTag == 0 ? "EVAL" : expectedTag == 1 ? "SET"
                        : expectedTag == 2 ? "UPD" : "tag=" + expectedTag;
                LOGGER.warn(
                        "[redstone-engine] abort: {} (cursor {}/{}, mode={}, entryDepth={}, "
                                + "drainDepthNow={}, network={}, dispatches accepted/bailed={}/{}, "
                                + "next={} {},{},{} pow={})",
                        why, s.cursor, s.count, modeName, s.entryDrainDepth,
                        DRAIN_DEPTH.get()[0], s.network.size(),
                        s.dispatchesAccepted, s.dispatchesBailed,
                        tagName, ex, ey, ez, expectedPower);
            }
            // post-abort: re-derive wires not at scripted final power
            for (Map.Entry<BlockPos, CascadeRecorder.Node> e : s.network.entrySet()) {
                Integer want = s.finalPower.get(e.getKey());
                if (want == null) continue;
                BlockState cur = s.level.getBlockState(e.getKey());
                int power = cur.is(s.wire) && cur.hasProperty(RedstoneWireBlock.POWER)
                        ? cur.getValue(RedstoneWireBlock.POWER) : Integer.MIN_VALUE;
                if (power != want) {
                    s.level.neighborChanged(e.getKey(), s.wire, null);
                }
            }
        }
        SESSION.remove();
    }
}
