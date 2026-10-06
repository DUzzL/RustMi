package com.github.tatercertified.nalim.cramming;

import com.github.tatercertified.nalim.rust.RustmiLib;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Field;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Driver for the Rust-side cramming candidate-search kernel ("Rust finds WHO,
 * vanilla decides WHAT"). One instance per server; owns a pair of
 * {@link ByteBuffer#allocateDirect(int)} buffers reused tick to tick.
 * <p>
 * Flow:
 * <ol>
 *   <li>Pre-tick phase calls {@link #beginCollect()}.</li>
 *   <li>Per-level entity iteration calls {@link #addEntity} for every
 *       collision-relevant entity (snapshot of pos/AABB and conservative
 *       candidate/origin flags). Multi-threaded collection races workers into
 *       disjoint slots. The Entity reference is retained in a slot-indexed array so
 *       candidates resolve without any id lookups.</li>
 *   <li>Pre-tick phase calls {@link #runBatch()} — one Nalim call fills, for
 *       every ORIGIN row, the slots of all entities whose PADDED snapshot
 *       AABBs intersect it (a guaranteed superset of the live neighbour set).</li>
 *   <li>During the entity's own tick, {@code LivingEntityCrammingMixin} reads
 *       the candidate list, re-checks LIVE AABB intersection and the real
 *       vanilla {@code EntitySelector.pushableBy} predicate, then runs the
 *       exact vanilla tickCramming body (damage + doPush) over the survivors.
 *       Live positions → vanilla sub-tick feedback dynamics, no snapshot
 *       staleness in the physics.</li>
 * </ol>
 */
public final class RustmiCramming implements CandidateBatch {

    // Layout constants — MUST stay in sync with Rust's #[repr(C)] structs
    // (native/src/util/cramming/mod.rs has compile-time asserts; runtime
    // cross-check via oxi_cramming_input_size()/oxi_cramming_output_size()).
    public static final int INPUT_STRIDE  = 64;
    public static final int OUTPUT_STRIDE = 512;
    public static final int MAX_CANDIDATES = 127;

    /** count == OVERFLOW → re-query the retained snapshot without the record cap. */
    public static final int OVERFLOW = -1;

    // Field offsets inside one CrammingInput record.
    private static final int IN_POS_X    = 0;
    private static final int IN_POS_Y    = 8;
    private static final int IN_POS_Z    = 16;
    private static final int IN_HALF_X   = 24;
    private static final int IN_HALF_Y   = 32;
    private static final int IN_HALF_Z   = 40;
    private static final int IN_DIM_ID   = 48;
    private static final int IN_FLAGS    = 52;
    private static final int IN_PAD      = 56;     // 8 bytes, zeroed

    // CrammingOutput: i32 count at 0, then MAX_CANDIDATES slot i32s.
    private static final int OUT_COUNT = 0;
    private static final int OUT_SLOTS = 4;

    // Flags — mirror native/src/util/cramming/mod.rs.
    public static final int FLAG_IS_PUSHABLE = 1 << 0;   // conservative candidate prefilter
    public static final int FLAG_IS_ORIGIN   = 1 << 5;   // batch-cancelled pushEntities
    public static final int FLAG_IS_LIVING   = 1 << 6;   // LivingEntity && isAlive() —
    // sensor-visible: vanilla's nearest-living query sees spectators, armor
    // stands and climbing mobs that are neither pushable nor origins.

    // UNSAFE / BUFFER_ADDRESS_OFFSET MUST be declared (and therefore initialised)
    // BEFORE the INSTANCE field below — INSTANCE's constructor calls resize(),
    // which calls directAddress(), which reads UNSAFE. JLS guarantees field
    // initializers run in textual order, so put the foundational ones first.
    static {
        one.nalim.ModuleOpener.opensPackage("java.base", "java.nio");
    }

    private static final sun.misc.Unsafe UNSAFE = getUnsafe();
    private static final long BUFFER_ADDRESS_OFFSET = bufferAddressOffset();

    private static sun.misc.Unsafe getUnsafe() {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (sun.misc.Unsafe) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static long bufferAddressOffset() {
        try {
            Field f = Buffer.class.getDeclaredField("address");
            f.setAccessible(true);
            return UNSAFE.objectFieldOffset(f);
        } catch (ReflectiveOperationException | RuntimeException e) {
            ExceptionInInitializerError err = new ExceptionInInitializerError(
                "Could not access java.nio.Buffer.address — add JVM flag " +
                "--add-opens java.base/java.nio=ALL-UNNAMED " +
                "(or wait for Nalim's ModuleOpener to take care of it). Cause: " + e);
            err.initCause(e);
            throw err;
        }
    }

    // ── retained-snapshot query plumbing (shared by the global batch and
    // RegionalCramming's per-region batches) ────────────────────────────────

    /** Registry keys for the native retention — unique and constant per batch
     *  instance, so a re-begin after a missed end simply overwrites stale state. */
    public static final AtomicLong QUERY_HANDLES = new AtomicLong(1);

    /** Per-thread off-heap scratch for query results (coordinator AND dense
     *  fan-out helpers query concurrently). Overflow → -1 → vanilla query. */
    static final int QUERY_SCRATCH_CAP = 4096;
    private static final ThreadLocal<ByteBuffer> QUERY_SCRATCH = ThreadLocal.withInitial(
            () -> ByteBuffer.allocateDirect(QUERY_SCRATCH_CAP * 4).order(ByteOrder.nativeOrder()));

    /** Address of this thread's scratch buffer. */
    static long scratchAddr() {
        return directAddress(QUERY_SCRATCH.get());
    }

    /** Run the unbounded OVERFLOW re-query into this thread's scratch. */
    static int nativeOverflowQuery(long handle, int slot) {
        return RustmiLib.oxi_cramming_query(handle, slot, scratchAddr(), QUERY_SCRATCH_CAP);
    }

    /** i-th slot from this thread's scratch (valid after nativeOverflowQuery). */
    static int scratchSlotAt(int i) {
        return UNSAFE.getInt(scratchAddr() + 4L * i);
    }

    private static final RustmiCramming INSTANCE = new RustmiCramming();
    public static RustmiCramming get() { return INSTANCE; }

    /**
     * Collection-time candidate prefilter. {@link LivingEntity#isPushable()}
     * consults {@code onClimbable()}, which can synchronously admit its chunk.
     * Avoid that call for every living row: alive rows are a conservative
     * superset, dead rows cannot be vanilla push candidates, and the mixin
     * re-tests the real live predicate before damage or pushing. Non-living
     * entities such as boats and minecarts retain the exact snapshot check.
     */
    static boolean snapshotPushable(Entity entity, boolean aliveLiving) {
        return aliveLiving || (!(entity instanceof LivingEntity) && entity.isPushable());
    }

    /** Native retention key of the global batch. */
    private final long queryHandle = QUERY_HANDLES.getAndIncrement();

    // Backing buffers + slot-indexed entity references.
    private ByteBuffer inputs;
    private ByteBuffer outputs;
    private long inputsAddr;
    private long outputsAddr;
    private volatile Entity[] entities;
    private volatile long[] seqs;
    private int capacity;
    /** Cleared by any level collected without vanilla section order this tick. */
    private volatile boolean vanillaOrdered;

    /**
     * Entity identity → slot, ORIGINS ONLY. The mixin must never consume a
     * non-origin row (a rider that dismounts mid-tick falls back to vanilla,
     * which is exactly the behaviour the candidate model expects).
     */
    private final ConcurrentHashMap<Entity, Integer> originSlots = new ConcurrentHashMap<>(1024);

    /** Next free slot — workers race here. */
    private final AtomicInteger nextSlot = new AtomicInteger();

    private RustmiCramming() {
        resize(2048);
    }

    private void resize(int newCapacity) {
        this.capacity = newCapacity;
        this.inputs   = allocAligned(newCapacity * INPUT_STRIDE);
        this.outputs  = allocAligned(newCapacity * OUTPUT_STRIDE);
        this.inputsAddr  = directAddress(this.inputs);
        this.outputsAddr = directAddress(this.outputs);
        this.entities = new Entity[newCapacity];
        this.seqs = new long[newCapacity];
    }

    private static ByteBuffer allocAligned(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
    }

    private static long directAddress(ByteBuffer direct) {
        return UNSAFE.getLong(direct, BUFFER_ADDRESS_OFFSET);
    }

    // ── shared plumbing for RegionalCramming.RegionBatch ────────────────────
    // (kept here so the Unsafe bootstrap + ModuleOpener static-init run once,
    // and the input record layout has exactly one writer implementation)

    public static sun.misc.Unsafe theUnsafe() {
        return UNSAFE;
    }

    public static long addressOf(ByteBuffer direct) {
        return directAddress(direct);
    }

    /** Write one CrammingInput record — single source of truth for the layout. */
    static void writeInputRecord(long inputsAddr, int slot, int dimId, int flags,
                                 double x, double y, double z,
                                 double halfX, double halfY, double halfZ) {
        long base = inputsAddr + (long) slot * INPUT_STRIDE;
        UNSAFE.putDouble(base + IN_POS_X,  x);
        UNSAFE.putDouble(base + IN_POS_Y,  y);
        UNSAFE.putDouble(base + IN_POS_Z,  z);
        UNSAFE.putDouble(base + IN_HALF_X, halfX);
        UNSAFE.putDouble(base + IN_HALF_Y, halfY);
        UNSAFE.putDouble(base + IN_HALF_Z, halfZ);
        UNSAFE.putInt   (base + IN_DIM_ID, dimId);
        UNSAFE.putInt   (base + IN_FLAGS,  flags);
        UNSAFE.putLong  (base + IN_PAD,    0L);
    }

    /** Called once per server tick BEFORE the collect loop starts. */
    public void beginCollect() {
        nextSlot.set(0);
        vanillaOrdered = true;
    }

    /** More rows were offered than the buffers hold this tick. */
    boolean overCapacity() {
        return nextSlot.get() > capacity;
    }

    /** No origin may consume this tick's batch (every pushEntities runs vanilla). */
    void dropOrigins() {
        originSlots.clear();
    }

    /** A level was collected in non-vanilla order (storage layout fallback). */
    void markUnordered() {
        vanillaOrdered = false;
    }

    @Override
    public boolean slotOrderIsVanilla() {
        return vanillaOrdered;
    }

    @Override
    public long collectedSeq(int slot) {
        long[] arr = seqs;
        return slot >= 0 && slot < arr.length ? arr[slot] : Long.MIN_VALUE;
    }

    /**
     * Reserve a slot for the given entity and copy its snapshot into the
     * input buffer. Thread-safe — multiple workers may call this in parallel.
     *
     * @return the slot index, or -1 if capacity was exceeded (the entity
     *         skips the batch this tick and uses the vanilla path — safe).
     */
    public int addEntity(Entity entity, boolean origin, int dimId,
                         double x, double y, double z,
                         double halfX, double halfY, double halfZ,
                         int flags, long collectedStamp) {
        int slot = nextSlot.getAndIncrement();
        if (slot >= capacity) {
            return -1;   // grow at endTick; this tick this entity is vanilla
        }
        writeInputRecord(inputsAddr, slot, dimId, flags, x, y, z, halfX, halfY, halfZ);
        entities[slot] = entity;
        seqs[slot] = collectedStamp;
        if (origin) {
            originSlots.put(entity, slot);
        }
        return slot;
    }

    /** Fire the single Nalim call into the Rust kernel. Returns nanos elapsed. */
    public long runBatch() {
        int n = nextSlot.get();
        if (n > capacity) n = capacity;
        if (n == 0) return 0L;
        long t0 = System.nanoTime();
        RustmiLib.oxi_cramming_batch(inputsAddr, outputsAddr, n);
        return System.nanoTime() - t0;
    }

    /**
     * Retain this tick's input snapshot for OVERFLOW re-queries during the
     * entity tick phase. Called right after {@link #runBatch}; dropped at
     * {@link #endTick} (which runs on every path, including the pre-phase
     * failure catch).
     */
    public void retainForQueries() {
        int n = nextSlot.get();
        if (n > capacity) n = capacity;
        if (n > 0) {
            RustmiLib.oxi_sensor_begin(queryHandle, inputsAddr, n);
        }
    }

    @Override
    public int overflowCandidates(int slot) {
        return nativeOverflowQuery(queryHandle, slot);
    }

    @Override
    public int scratchCandidateSlot(int i) {
        return scratchSlotAt(i);
    }

    /** Slot of an origin row, or -1 when the entity must use vanilla. */
    public int originSlot(Entity entity) {
        Integer slot = originSlots.get(entity);
        return slot == null ? -1 : slot;
    }

    /**
     * Candidate count for an origin row; {@link #OVERFLOW} means the
     * neighbourhood was denser than {@link #MAX_CANDIDATES}. The caller first
     * re-queries the retained snapshot without that record cap and only uses
     * the full vanilla section query if retention or scratch capacity fails.
     */
    public int candidateCount(int slot) {
        return UNSAFE.getInt(outputsAddr + (long) slot * OUTPUT_STRIDE + OUT_COUNT);
    }

    /** i-th candidate slot of an origin row. */
    public int candidateSlot(int slot, int i) {
        return UNSAFE.getInt(outputsAddr + (long) slot * OUTPUT_STRIDE + OUT_SLOTS + 4L * i);
    }

    /** Entity registered at the given slot this tick (null if none). */
    public Entity entityAt(int slot) {
        Entity[] arr = entities;
        return slot >= 0 && slot < arr.length ? arr[slot] : null;
    }

    /**
     * Called at end-of-tick (and on pre-phase failure). Clears per-tick state
     * so stale entries never leak into the next tick; grows the buffers if
     * this tick saturated capacity.
     */
    public void endTick() {
        RustmiLib.oxi_sensor_end(queryHandle);   // before clear/resize touch the buffer
        int used = nextSlot.get();
        originSlots.clear();
        Entity[] arr = entities;
        int clear = Math.min(used, arr.length);
        Arrays.fill(arr, 0, clear, null); // GC hygiene — drop refs to removed entities
        if (used >= capacity) {
            int newCap = Math.max(capacity * 2, used + capacity / 2);
            resize(newCap);
        }
    }
}
