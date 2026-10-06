package com.github.tatercertified.nalim.redstone;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RedstoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase R0 of the vanilla-exact redstone campaign
 * (docs/REDSTONE_VANILLA_PLAN.md): a ground-truth recorder around vanilla's
 * {@code DefaultRedstoneWireEvaluator}. Captures, per neighbor-update drain
 * burst, the wire network snapshot at first wire evaluation plus the exact
 * ordered event stream the cascade produced:
 * <pre>
 *   EVAL pos oldPower          — evaluator.updatePowerStrength entered
 *   SET  pos newPower          — the wire state write (intermediate values!)
 *   UPD  pos                   — Level.updateNeighborsAt emission centre
 * </pre>
 * These fixtures are the R1 kernel's unit-test oracle: the Rust replay must
 * reproduce the SET/UPD subsequence bit-for-bit (including the
 * HashSet-iteration order and CollectingNeighborUpdater queue discipline that
 * shape vanilla's locational behavior).
 *
 * <p>Off by default; toggle at runtime with
 * {@code /rustmi redstone record on|off|status} (ops). The state persists
 * to {@code config/rustmi/redstone-recorder.properties} across restarts —
 * no JVM flags involved. When disabled, every hook is a single volatile-read
 * early-out (~1 ns). Recording state is per-thread — Fabrolia region workers
 * each record their own bursts independently; a runtime toggle bumps a
 * generation counter that invalidates any mid-flight per-thread burst.
 *
 * <p>Vanilla 26.1 mechanics this is designed around: cascades are NOT
 * recursive — {@code CollectingNeighborUpdater} flattens them into an
 * iterative drain, so one logical cascade is a SEQUENCE of depth-0 evaluator
 * calls inside one outermost {@code addAndRun}. Burst boundaries therefore
 * come from the updater mixin, not from evaluator nesting. A burst whose
 * later evaluator calls touch wires OUTSIDE the first call's network closure
 * is flagged {@code multiNetwork} (R1 v1 discards those fixtures).
 */
public final class CascadeRecorder {

    private static final int MAX_FIXTURES = 500;
    private static final int MAX_NETWORK = 4096;
    private static final int MAX_EVENTS = 200_000;

    // Runtime-toggleable (/rustmi redstone record on|off|status), persisted
    // across restarts. No JVM flags — launch lines stay clean.
    private static final Path STATE_FILE = Paths.get("config", "rustmi", "redstone-recorder.properties");
    private static final Path SHADOW_STATE_FILE = Paths.get("config", "rustmi", "redstone-shadow.properties");
    private static volatile boolean enabled = loadPersisted(STATE_FILE);
    /** R2 shadow engine: capture bursts and diff them against the Rust sim. */
    private static volatile boolean shadowEnabled = loadPersisted(SHADOW_STATE_FILE);
    /** Bumped on every toggle: per-thread bursts from a previous generation are stale. */
    private static volatile int generation;

    public static boolean isEnabled() {
        return enabled;
    }

    /** Burst capture is alive for either consumer (fixtures or shadow diff). */
    private static boolean capturing() {
        return enabled || shadowEnabled;
    }

    public static synchronized void setEnabled(boolean on) {
        if (enabled == on) return;
        enabled = on;
        generation++;
        persist(STATE_FILE, "redstone cascade recorder (R0)", on);
        LOGGER.info("[redstone-rec] recording {}", on ? "ENABLED" : "DISABLED");
    }

    public static boolean isShadowEnabled() {
        return shadowEnabled;
    }

    public static synchronized void setShadowEnabled(boolean on) {
        if (shadowEnabled == on) return;
        if (on) {
            CascadeShadow.resetStats();
        }
        shadowEnabled = on;
        generation++;
        persist(SHADOW_STATE_FILE, "redstone shadow engine (R2)", on);
        LOGGER.info("[redstone-shadow] {}", on ? "ENABLED" : "DISABLED");
    }

    public static String shadowStatus() {
        return "shadow=" + shadowEnabled + " | " + CascadeShadow.status();
    }

    static int maxNetwork() {
        return MAX_NETWORK;
    }

    static int maxEvents() {
        return MAX_EVENTS;
    }

    public static String status() {
        return "recording=" + enabled
                + " bursts=" + BURSTS_SEEN.get()
                + " events=" + EVENTS_SEEN.get()
                + " fixtures=" + FIXTURES_WRITTEN.get() + "/" + MAX_FIXTURES
                + " dir=" + OUT_DIR.toAbsolutePath();
    }

    /** Shared with {@link CascadeEngine} — same persistence format. */
    static boolean loadPersistedState(Path file) {
        return loadPersistedState(file, false);
    }

    /** Optimizations default ON for fresh installs (user directive 2026-06-12);
     *  the persisted file, once written by a toggle command, always wins. */
    static boolean loadPersistedState(Path file, boolean def) {
        if (!java.nio.file.Files.exists(file)) {
            return def;
        }
        return loadPersisted(file);
    }

    /** Shared with {@link CascadeEngine}. */
    static void persistState(Path file, String title, boolean on) {
        persist(file, title, on);
    }

    /**
     * Engine takeover poisons the in-flight burst: the recorder/shadow never
     * see script-handled SET/UPD calls (they happen outside the evaluator
     * body), so the burst would otherwise look like vanilla produced nothing
     * and pollute fixtures/shadow stats. multiNetwork is the existing
     * "skip this burst" flag for both consumers.
     */
    static void poisonCurrentBurst() {
        if (!capturing()) return;
        Burst b = burst();
        if (b.active) {
            b.multiNetwork = true;
        }
    }

    private static boolean loadPersisted(Path file) {
        try {
            if (Files.exists(file)) {
                for (String line : Files.readAllLines(file)) {
                    String t = line.trim();
                    if (t.startsWith("enabled=")) {
                        return Boolean.parseBoolean(t.substring("enabled=".length()));
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return false;
    }

    private static void persist(Path file, String title, boolean on) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file,
                    ("# Rustmi " + title + "\nenabled=" + on + "\n")
                            .getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOGGER.warn("could not persist toggle state", e);
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("Rustmi-RedstoneRec");
    private static final Path OUT_DIR = Paths.get("rustmi-redstone-fixtures");

    private static final AtomicInteger FIXTURES_WRITTEN = new AtomicInteger();
    private static final AtomicLong BURSTS_SEEN = new AtomicLong();
    private static final AtomicLong EVENTS_SEEN = new AtomicLong();
    private static final AtomicLong LAST_STATS_NS = new AtomicLong(System.nanoTime());

    /** Implemented by DefaultRedstoneEvaluatorMixin — exposes the protected block-signal read. */
    public interface BlockSignalReader {
        int rustmi$blockSignal(Level level, BlockPos pos);
    }

    private CascadeRecorder() {}

    // ─────────────────────────────────────────────────────────────────────
    // Per-thread burst state
    // ─────────────────────────────────────────────────────────────────────

    static final class Node {
        final BlockPos pos;
        /** Mutable: the engine's network cache keeps these live across bursts
         *  (wire POWER sets and source-value changes update them in place). */
        int power;
        int strongPower;
        final List<BlockPos> edges = new ArrayList<>(4);

        Node(BlockPos pos, int power, int strongPower) {
            this.pos = pos;
            this.power = power;
            this.strongPower = strongPower;
        }
    }

    static final class Burst {
        int gen = -1;
        int drainDepth;
        int evalDepth;
        boolean active;
        boolean multiNetwork;
        boolean overflow;
        /** First evaluator call happened at collector depth 0 (block place/
         *  remove path) — picks the sim's first entry discipline. */
        boolean entryDepth0;
        Level level;
        BlockPos trigger;
        final Map<BlockPos, Node> network = new HashMap<>();
        final List<String> events = new ArrayList<>(256);
        long startNanos;

        void reset() {
            active = false;
            multiNetwork = false;
            overflow = false;
            level = null;
            trigger = null;
            network.clear();
            events.clear();
        }

        /** Toggle happened since this thread last recorded — drop everything. */
        void hardReset(int newGen) {
            gen = newGen;
            drainDepth = 0;
            evalDepth = 0;
            reset();
        }
    }

    private static final ThreadLocal<Burst> BURST = ThreadLocal.withInitial(Burst::new);

    /** Per-thread burst, generation-checked against runtime toggles. */
    private static Burst burst() {
        Burst b = BURST.get();
        int g = generation;
        if (b.gen != g) {
            b.hardReset(g);
        }
        return b;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Hooks (called from mixins; all early-out when disabled)
    // ─────────────────────────────────────────────────────────────────────

    /** CollectingNeighborUpdater.addAndRun HEAD. */
    public static void onDrainEnter() {
        if (!capturing()) return;
        burst().drainDepth++;
    }

    /** CollectingNeighborUpdater.addAndRun RETURN. */
    public static void onDrainExit() {
        if (!capturing()) return;
        Burst b = burst();
        if (--b.drainDepth <= 0) {
            b.drainDepth = 0;
            maybeFinalize(b);
        }
    }

    /** DefaultRedstoneWireEvaluator.updatePowerStrength HEAD. */
    public static void onEvaluatorEnter(Level level, BlockPos pos, BlockState state,
                                        RedstoneWireBlock wire, BlockSignalReader signals) {
        if (!capturing()) return;
        if (level.isClientSide()) return;
        Burst b = burst();
        b.evalDepth++;
        if (!b.active) {
            b.active = true;
            b.entryDepth0 = b.drainDepth == 0;
            b.level = level;
            b.trigger = pos.immutable();
            b.startNanos = System.nanoTime();
            Map<BlockPos, Node> net = walkNetwork(level, b.trigger, wire, signals, MAX_NETWORK);
            if (net == null) {
                b.overflow = true;
            } else {
                b.network.putAll(net);
            }
        } else if (!b.network.containsKey(pos)) {
            b.multiNetwork = true;
        }
        addEvent(b, "EVAL " + fmt(pos) + " " + state.getValue(RedstoneWireBlock.POWER));
    }

    /** DefaultRedstoneWireEvaluator.updatePowerStrength RETURN. */
    public static void onEvaluatorExit(Level level) {
        if (!capturing()) return;
        if (level.isClientSide()) return;
        Burst b = burst();
        if (b.evalDepth > 0) b.evalDepth--;
        if (b.drainDepth == 0) {
            maybeFinalize(b);
        }
    }

    /** Wire setBlock inside updatePowerStrength (WrapOperation). */
    public static void onWireSet(Level level, BlockPos pos, BlockState newState) {
        if (!capturing()) return;
        if (level.isClientSide()) return;
        Burst b = burst();
        if (!b.active) return;
        int power = newState.hasProperty(RedstoneWireBlock.POWER)
                ? newState.getValue(RedstoneWireBlock.POWER) : -1;
        addEvent(b, "SET " + fmt(pos) + " " + power);
    }

    /** updateNeighborsAt emission inside updatePowerStrength (WrapOperation). */
    public static void onUpdateNeighbors(Level level, BlockPos pos) {
        if (!capturing()) return;
        if (level.isClientSide()) return;
        Burst b = burst();
        if (!b.active) return;
        addEvent(b, "UPD " + fmt(pos));
    }

    private static void addEvent(Burst b, String event) {
        if (b.events.size() >= MAX_EVENTS) {
            b.overflow = true;
            return;
        }
        b.events.add(event);
        EVENTS_SEEN.incrementAndGet();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Network closure walk — mirrors getIncomingWireSignal's read rules
    // ─────────────────────────────────────────────────────────────────────

    /** Shared by the recorder, the shadow comparator and the R3 engine.
     *  Returns the closure network, or null when it exceeds {@code maxNodes}. */
    static Map<BlockPos, Node> walkNetwork(Level level, BlockPos start,
                                           RedstoneWireBlock wire, BlockSignalReader signals,
                                           int maxNodes) {
        Map<BlockPos, Node> network = new HashMap<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        Set<BlockPos> seen = new HashSet<>();
        seen.add(start);

        while (!queue.isEmpty()) {
            if (network.size() >= maxNodes) {
                return null;
            }
            BlockPos p = queue.poll();
            BlockState state = level.getBlockState(p);
            if (!state.is(wire)) {
                continue;
            }
            Node node = new Node(p, state.getValue(RedstoneWireBlock.POWER),
                    signals.rustmi$blockSignal(level, p));
            network.put(p, node);

            // Read-edges of p, exactly as getIncomingWireSignal scans:
            // horizontal neighbour wire; +up diagonal when the neighbour is a
            // conductor and the block above p is not; +down diagonal when the
            // neighbour is not a conductor.
            boolean aboveSelfSolid = level.getBlockState(p.above()).isRedstoneConductor(level, p.above());
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos n = p.relative(dir);
                BlockState nState = level.getBlockState(n);
                considerEdge(level, wire, node, n, nState, queue, seen);
                boolean nSolid = nState.isRedstoneConductor(level, n);
                if (nSolid && !aboveSelfSolid) {
                    BlockPos up = n.above();
                    considerEdge(level, wire, node, up, level.getBlockState(up), queue, seen);
                } else if (!nSolid) {
                    BlockPos down = n.below();
                    considerEdge(level, wire, node, down, level.getBlockState(down), queue, seen);
                }
            }
        }
        return network;
    }

    private static void considerEdge(Level level, RedstoneWireBlock wire, Node from,
                                     BlockPos to, BlockState toState,
                                     ArrayDeque<BlockPos> queue, Set<BlockPos> seen) {
        if (!toState.is(wire)) {
            return;
        }
        BlockPos imm = to.immutable();
        from.edges.add(imm);
        // Closure over the undirected reachability: anything p reads can also
        // change and re-trigger p, and vice versa — walk both ways. (The
        // reverse-read edges are discovered when the neighbour node is walked;
        // enqueueing here just guarantees the neighbour IS walked.)
        if (seen.add(imm)) {
            queue.add(imm);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Finalize + dump
    // ─────────────────────────────────────────────────────────────────────

    private static void maybeFinalize(Burst b) {
        if (!b.active || b.evalDepth > 0 || b.drainDepth > 0) {
            return;
        }
        try {
            BURSTS_SEEN.incrementAndGet();
            long durationNanos = System.nanoTime() - b.startNanos;
            if (shadowEnabled) {
                try {
                    CascadeShadow.compare(b, durationNanos);
                } catch (Throwable t) {
                    shadowEnabled = false;
                    LOGGER.error("[redstone-shadow] comparator failed — shadow disabled for this session", t);
                }
            }
            if (enabled) {
                finalizeAndDump(b, durationNanos);
            }
        } catch (Throwable t) {
            LOGGER.error("fixture dump failed", t);
        } finally {
            b.reset();
            maybeLogStats();
        }
    }

    private static void finalizeAndDump(Burst b, long durationNanos) throws IOException {
        int n = FIXTURES_WRITTEN.get();
        if (n >= MAX_FIXTURES) {
            return;   // stats still tick; files capped
        }
        // Skip the noise: single-wire no-op bursts (EVAL with no SET) carry
        // no replay information.
        boolean anySet = false;
        for (String e : b.events) {
            if (e.startsWith("SET")) { anySet = true; break; }
        }
        if (!anySet) {
            return;
        }
        if (!FIXTURES_WRITTEN.compareAndSet(n, n + 1)) {
            return;   // racing thread took the slot; cheap to just drop ours
        }

        StringBuilder sb = new StringBuilder(b.events.size() * 24 + b.network.size() * 96 + 256);
        sb.append("{\"v\":1,\"dim\":\"").append(b.level.dimension().identifier())
          .append("\",\"trigger\":").append(arr(b.trigger))
          .append(",\"multiNetwork\":").append(b.multiNetwork)
          .append(",\"overflow\":").append(b.overflow)
          .append(",\"durationNanos\":").append(durationNanos)
          .append(",\"nodes\":[");
        boolean first = true;
        for (Node node : b.network.values()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"p\":").append(arr(node.pos))
              .append(",\"pw\":").append(node.power)
              .append(",\"sp\":").append(node.strongPower)
              .append(",\"edges\":[");
            for (int i = 0; i < node.edges.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(arr(node.edges.get(i)));
            }
            sb.append("]}");
        }
        sb.append("],\"finalPowers\":[");
        first = true;
        for (Node node : b.network.values()) {
            BlockState now = b.level.getBlockState(node.pos);
            int pw = now.hasProperty(RedstoneWireBlock.POWER)
                    ? now.getValue(RedstoneWireBlock.POWER) : -1;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"p\":").append(arr(node.pos)).append(",\"pw\":").append(pw).append('}');
        }
        sb.append("],\"events\":[");
        for (int i = 0; i < b.events.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(b.events.get(i)).append('"');
        }
        sb.append("]}");

        Files.createDirectories(OUT_DIR);
        Path file = OUT_DIR.resolve("cascade-" + (n + 1) + ".json");
        Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String fmt(BlockPos p) {
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }

    private static String arr(BlockPos p) {
        return "[" + p.getX() + "," + p.getY() + "," + p.getZ() + "]";
    }

    private static void maybeLogStats() {
        long now = System.nanoTime();
        long last = LAST_STATS_NS.get();
        if (now - last < 5_000_000_000L) return;
        if (!LAST_STATS_NS.compareAndSet(last, now)) return;
        LOGGER.info("[redstone-rec] bursts={} events={} fixtures={}/{}",
                BURSTS_SEEN.get(), EVENTS_SEEN.get(), FIXTURES_WRITTEN.get(), MAX_FIXTURES);
    }
}
