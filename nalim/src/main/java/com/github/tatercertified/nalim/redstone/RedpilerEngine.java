package com.github.tatercertified.nalim.redstone;

import com.github.tatercertified.nalim.rust.RustmiLib;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RedstoneWireBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ComparatorMode;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Java lifecycle facade for the MCHPRS-derived direct backend. The
 * {@link RedpilerHost} and walker build a graph through {@link Builder},
 * compile it into a {@link Session}, then drive input changes, ticks, drains,
 * and teardown from the owning server thread. Claimed areas use this engine;
 * unclaimed redstone continues through vanilla or {@link CascadeEngine}.
 */
public final class RedpilerEngine {

    // NodeType discriminants — must match decode_node_type() in
    // native/.../redpiler/ffi.rs. Keep this enum in lockstep with Rust.
    public static final byte TAG_REPEATER = 0;
    public static final byte TAG_TORCH = 1;
    public static final byte TAG_COMPARATOR = 2;
    public static final byte TAG_LAMP = 3;
    public static final byte TAG_BUTTON = 4;
    public static final byte TAG_LEVER = 5;
    public static final byte TAG_PRESSURE_PLATE = 6;
    public static final byte TAG_TRAPDOOR = 7;
    public static final byte TAG_WIRE = 8;
    public static final byte TAG_CONSTANT = 9;
    public static final byte TAG_NOTEBLOCK = 10;

    public static final byte LINK_DEFAULT = 0;
    public static final byte LINK_SIDE = 1;

    public static final byte FLAG_IS_INPUT = 0b01;
    public static final byte FLAG_IS_OUTPUT = 0b10;

    public static final byte PRIO_HIGHEST = 0;
    public static final byte PRIO_HIGHER = 1;
    public static final byte PRIO_HIGH = 2;
    public static final byte PRIO_NORMAL = 3;

    private static final AtomicLong HANDLES = new AtomicLong(0x4ED41DL);

    /**
     * Pack the node descriptor into one native argument. Keeping the FFI call
     * at two integer arguments avoids relying on stack-passthrough calling
     * convention details in JVMCI linkers.
     */
    private static long packNode(byte tag, byte p1, byte p2, byte p3,
                                 boolean powered, boolean locked,
                                 byte outputStrength, byte flags) {
        return Byte.toUnsignedLong(tag)
                | (Byte.toUnsignedLong(p1) << 8)
                | (Byte.toUnsignedLong(p2) << 16)
                | (Byte.toUnsignedLong(p3) << 24)
                | ((powered ? 1L : 0L) << 32)
                | ((locked ? 1L : 0L) << 40)
                | (Byte.toUnsignedLong(outputStrength) << 48)
                | (Byte.toUnsignedLong(flags) << 56);
    }

    private RedpilerEngine() {}

    /**
     * Builder facade for assembling a network into the native graph.
     * Returned by {@link #begin()}; the caller adds nodes/blocks/edges,
     * then calls {@link Builder#compile()} which returns a {@link Session}.
     */
    public static final class Builder implements AutoCloseable {
        private final long handle;
        private boolean ownsHandle = true;
        private final Map<BlockPos, Integer> posToIdx = new HashMap<>();

        private Builder(long handle) {
            this.handle = handle;
        }

        public long handle() { return handle; }

        /**
         * Add one node. Returns the assigned `0..N` node index. Caller MUST
         * follow with {@link #addBlock} for every world-block this node
         * represents (typically 1 — wire/repeater/torch — but comparator+
         * dust pairs need 2).
         */
        public int addNode(byte tag, byte p1, byte p2, byte p3,
                           boolean powered, boolean locked, byte outputStrength,
                           boolean isInput, boolean isOutput) {
            byte flags = 0;
            if (isInput) flags |= FLAG_IS_INPUT;
            if (isOutput) flags |= FLAG_IS_OUTPUT;
            long packed = packNode(tag, p1, p2, p3, powered, locked, outputStrength, flags);
            int idx = RustmiLib.oxi_redpiler_add_node(handle, packed);
            if (idx < 0) {
                throw new IllegalStateException("oxi_redpiler_add_node failed: " + idx);
            }
            return idx;
        }

        /** Convenience wrapper that records the pos→idx mapping locally
         *  (the native side also keeps one, but the builder may need it
         *  before compile to wire edges). */
        public int addBlock(int nodeIdx, BlockPos pos, int blockId) {
            int rc = RustmiLib.oxi_redpiler_node_add_block(handle, nodeIdx,
                    pos.getX(), pos.getY(), pos.getZ(), blockId);
            if (rc < 0) {
                throw new IllegalStateException("oxi_redpiler_node_add_block failed: " + rc);
            }
            posToIdx.put(pos.immutable(), nodeIdx);
            return rc;
        }

        public int addEdge(int from, int to, boolean side, byte ss) {
            int rc = RustmiLib.oxi_redpiler_add_edge(handle, from, to,
                    side ? LINK_SIDE : LINK_DEFAULT, ss);
            if (rc < 0) {
                throw new IllegalStateException("oxi_redpiler_add_edge failed: " + rc);
            }
            return rc;
        }

        public Integer indexOf(BlockPos pos) {
            return posToIdx.get(pos);
        }

        /** Lower the graph → runtime backend. Returns a {@link Session}
         *  the caller drives with ticks and input toggles. Builder is
         *  consumed; further calls panic. */
        public Session compile() {
            int rc = RustmiLib.oxi_redpiler_compile(handle);
            if (rc < 0) {
                throw new IllegalStateException("oxi_redpiler_compile failed: " + rc);
            }
            ownsHandle = false;
            return new Session(handle);
        }

        @Override
        public void close() {
            if (ownsHandle) {
                RustmiLib.oxi_redpiler_destroy(handle);
            }
        }
    }

    /**
     * A compiled network session: drives ticks, accepts input toggles,
     * drains change/event sets. The host calls {@link #close()} when the
     * network is invalidated (block edit in halo, world unload).
     */
    public static final class Session implements AutoCloseable {
        private final long handle;
        private final Unsafe unsafe;
        // Reusable off-heap scratch buffers — sized at first drain that
        // exceeds capacity; never freed until close() (one Session per
        // network, lifetime ≈ network lifetime).
        private long changesBuf = 0;
        private int changesCap = 0;
        private long eventsBuf = 0;
        private int eventsCap = 0;

        Session(long handle) {
            this.handle = handle;
            this.unsafe = unsafeInstance();
        }

        /** Adopt an already-compiled FFI handle (used by host-side wiring
         *  that walked the network itself and called compile() directly). */
        public static Session adopt(long handle) {
            return new Session(handle);
        }

        public long handle() { return handle; }

        public void scheduleTick(int nodeIdx, int delay, byte priority) {
            int rc = RustmiLib.oxi_redpiler_schedule_tick(handle, nodeIdx, delay, priority);
            if (rc < 0) {
                throw new IllegalStateException("oxi_redpiler_schedule_tick failed: " + rc);
            }
        }

        /** Fire {@code update_node} on every gate once. Reconciles each
         *  gate's walk-time POWERED snapshot with its newly populated input
         *  histogram, scheduling pending ticks where the two disagree.
         *
         *  <p>Call this AFTER all {@link #scheduleTick} replays for the
         *  current session. The native warm-up pass skips nodes already
         *  marked with a pending tick before it calls {@code update_node},
         *  preventing duplicate schedules. */
        public void warmup() {
            int rc = RustmiLib.oxi_redpiler_warmup(handle);
            if (rc < 0) {
                throw new IllegalStateException("oxi_redpiler_warmup failed: " + rc);
            }
        }

        public void setInput(int nodeIdx, boolean powered, byte power) {
            int rc = RustmiLib.oxi_redpiler_set_input(handle, nodeIdx,
                    (byte) (powered ? 1 : 0), power);
            if (rc < 0) {
                throw new IllegalStateException("oxi_redpiler_set_input failed: " + rc);
            }
        }

        public void tick() {
            int rc = RustmiLib.oxi_redpiler_tick(handle);
            if (rc < 0) {
                throw new IllegalStateException("oxi_redpiler_tick failed: " + rc);
            }
        }

        /**
         * Drain changed-since-last-flush nodes through {@code sink}. Each
         * call: `sink.accept(NodeChange)`; the change records are reused
         * across calls so don't retain references past the lambda return.
         */
        public int drainChanges(Consumer<NodeChange> sink) {
            // Grow scratch buffer; native returns ERR_OUT_CAP (-6) if too
            // small. Initial guess: 16 records (80 ints).
            int cap = Math.max(changesCap, 16 * 5);
            while (true) {
                ensureChangesBuf(cap);
                int rc = RustmiLib.oxi_redpiler_drain_changes(handle, changesBuf, cap);
                if (rc == -6) {           // ERR_OUT_CAP
                    cap *= 2;
                    continue;
                }
                if (rc < 0) {
                    throw new IllegalStateException("oxi_redpiler_drain_changes failed: " + rc);
                }
                NodeChange ch = new NodeChange();
                for (int i = 0; i < rc; i++) {
                    long off = changesBuf + (long) i * 5L * 4L;
                    ch.idx = unsafe.getInt(off);
                    ch.powered = unsafe.getInt(off + 4) != 0;
                    ch.locked = unsafe.getInt(off + 8) != 0;
                    ch.outputPower = (byte) unsafe.getInt(off + 12);
                    sink.accept(ch);
                }
                return rc;
            }
        }

        public int drainEvents(Consumer<NoteBlockEvent> sink) {
            int cap = Math.max(eventsCap, 16);
            while (true) {
                ensureEventsBuf(cap);
                int rc = RustmiLib.oxi_redpiler_drain_events(handle, eventsBuf, cap);
                if (rc == -6) {
                    cap *= 2;
                    continue;
                }
                if (rc < 0) {
                    throw new IllegalStateException("oxi_redpiler_drain_events failed: " + rc);
                }
                NoteBlockEvent ev = new NoteBlockEvent();
                for (int i = 0; i < rc; i++) {
                    ev.noteblockId = unsafe.getInt(eventsBuf + (long) i * 4L);
                    sink.accept(ev);
                }
                return rc;
            }
        }

        public int lookupPos(BlockPos pos) {
            return RustmiLib.oxi_redpiler_lookup_pos(handle, pos.getX(), pos.getY(), pos.getZ());
        }

        /** Drain the queued torch-burnout list. Sink receives each node
         *  index that just entered the burnt state since the last drain.
         *  Reuses the same growable buffer as {@link #drainEvents}. */
        public int drainBurnouts(java.util.function.IntConsumer sink) {
            int cap = Math.max(eventsCap, 16);
            while (true) {
                ensureEventsBuf(cap);
                int rc = RustmiLib.oxi_redpiler_drain_burnouts(handle, eventsBuf, cap);
                if (rc == -6) {
                    cap *= 2;
                    continue;
                }
                if (rc < 0) {
                    throw new IllegalStateException("oxi_redpiler_drain_burnouts failed: " + rc);
                }
                for (int i = 0; i < rc; i++) {
                    sink.accept(unsafe.getInt(eventsBuf + (long) i * 4L));
                }
                return rc;
            }
        }

        @Override
        public void close() {
            if (changesBuf != 0) {
                unsafe.freeMemory(changesBuf);
                changesBuf = 0;
            }
            if (eventsBuf != 0) {
                unsafe.freeMemory(eventsBuf);
                eventsBuf = 0;
            }
            RustmiLib.oxi_redpiler_destroy(handle);
        }

        private void ensureChangesBuf(int cap) {
            if (changesCap >= cap) return;
            if (changesBuf != 0) unsafe.freeMemory(changesBuf);
            changesBuf = unsafe.allocateMemory((long) cap * 4L);
            changesCap = cap;
        }

        private void ensureEventsBuf(int cap) {
            if (eventsCap >= cap) return;
            if (eventsBuf != 0) unsafe.freeMemory(eventsBuf);
            eventsBuf = unsafe.allocateMemory((long) cap * 4L);
            eventsCap = cap;
        }
    }

    /** Mutable view into one drained change record (reused per drain). */
    public static final class NodeChange {
        public int idx;
        public boolean powered;
        public boolean locked;
        public byte outputPower;
    }

    public static final class NoteBlockEvent {
        public int noteblockId;
    }

    // ── frontend: BlockState → NodeType helpers ─────────────────────────
    //
    // These translate vanilla MC block states into the redpiler NodeType
    // tag + per-tag params. The host walks its network (via
    // [`CascadeRecorder.walkNetwork`] or an equivalent multi-block walk
    // that includes repeaters/torches/comparators) and calls these to
    // populate the builder.
    //
    // Helpers intentionally narrow — the caller is the frontend pass that
    // KNOWS each pos is a valid redstone component. Non-redstone states
    // would mis-classify; the caller filters.

    /** Returns 0..10 NodeType tag for the given state, or -1 if not a
     *  recognised redstone component. p1/p2/p3 are written into [0]/[1]/[2]
     *  of {@code outParams} (caller-supplied 3-byte buffer). */
    public static int classify(BlockState state, byte[] outParams) {
        outParams[0] = 0;
        outParams[1] = 0;
        outParams[2] = 0;

        Block block = state.getBlock();
        if (block instanceof RedstoneWireBlock) {
            return TAG_WIRE;
        }
        if (block instanceof RedstoneTorchBlock) {
            return TAG_TORCH;
        }
        if (block instanceof RepeaterBlock) {
            // delay 1..4 mapped to 1..4 ticks.
            outParams[0] = (byte) state.getValue(RepeaterBlock.DELAY).intValue();
            // facing_diode detection requires neighbor lookup — defer to
            // the frontend pass which has the level context.
            outParams[1] = 0;
            return TAG_REPEATER;
        }
        if (block instanceof ComparatorBlock) {
            outParams[0] = state.getValue(ComparatorBlock.MODE) == ComparatorMode.SUBTRACT
                    ? (byte) 1 : (byte) 0;
            // far_input: 0=None; frontend pass sets after container/wire
            // analysis.
            outParams[1] = 0;
            outParams[2] = 0;
            return TAG_COMPARATOR;
        }
        if (block instanceof RedstoneLampBlock) {
            return TAG_LAMP;
        }
        if (block instanceof LeverBlock) {
            return TAG_LEVER;
        }
        if (block instanceof ButtonBlock) {
            return TAG_BUTTON;
        }
        if (block instanceof BasePressurePlateBlock) {
            return TAG_PRESSURE_PLATE;
        }
        if (block instanceof TrapDoorBlock) {
            return TAG_TRAPDOOR;
        }
        return -1;
    }

    /** Open a builder under a fresh handle. */
    public static Builder begin() {
        long h = HANDLES.incrementAndGet();
        int rc = RustmiLib.oxi_redpiler_create(h);
        if (rc < 0) {
            throw new IllegalStateException("oxi_redpiler_create failed: " + rc);
        }
        return new Builder(h);
    }

    // ── Unsafe ──────────────────────────────────────────────────────────

    private static Unsafe unsafeInstance() {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (Unsafe) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Unsafe unavailable", e);
        }
    }
}
