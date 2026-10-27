package com.github.tatercertified.nalim.cramming;

import com.axalotl.fabrolia.common.regions.Region;
import com.axalotl.fabrolia.common.regions.RegionGrid;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionTickHooks;
import com.axalotl.fabrolia.common.regions.RegionWorkerThread;
import com.axalotl.fabrolia.common.utils.SensorCandidateBridge;
import com.github.tatercertified.nalim.rust.RustmiLib;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Region-scoped cramming batches for Fabrolia's region scheduler (Tier 0 of
 * "регион = граница батча"). Hooked via {@link RegionTickHooks}: the
 * coordinating worker collects its region's snapshot, runs one Nalim kernel
 * call, and the region's entities consume candidate lists during their tick —
 * all on region workers, zero global barriers, snapshot age ≈ 0 for
 * intra-region pairs.
 *
 * <p><b>Cross-region boundaries (the "куча вылетела в соседний регион" cases):</b>
 * <ul>
 *   <li><b>Halo</b>: one actual-AABB section query over the union of
 *       admitted origins' cramming and covered-sensor envelopes retrieves
 *       every collider, independent of its centre-owning region or legal
 *       dimensions.</li>
 *   <li><b>Stray members</b>: an entity still owned by this region (lazy
 *       re-route) but positioned deeper than {@value #ORIGIN_MARGIN} blocks
 *       outside gets NO origin row — its own tick falls back to the full
 *       vanilla query (correct, one tick). Optimized origins are limited to
 *       horizontal half-extents ≤2; wider AABBs remain candidates through
 *       the actual-AABB query.</li>
 *   <li><b>Boundary pair races</b>: two regions concurrently doPush-ing each
 *       other's entities is the same deltaMovement race class Fabrolia already
 *       accepts everywhere in parallel entity ticking — unchanged here.</li>
 * </ul>
 *
 * <p>This class references Fabrolia types directly (compileOnly) — it must only
 * be class-loaded behind {@code CrammingHooks}' successful
 * {@code Class.forName} probe.
 */
public final class RegionalCramming implements RegionTickHooks.Hook {

    /** Covered XZ rectangle for fast origins and candidate snapshots. */
    private static final double HALO = RegionalCrammingGeometry.HALO;
    /**
     * Origins: region members positioned up to this far outside the bounds.
     * HALO − ORIGIN_MARGIN = 5.0 ≥ two width-4 ghast half-extents plus the
     * native 0.5 snapshot pad. Wider live AABBs never become origins and
     * therefore take the exact vanilla query on their own tick.
     */
    private static final double ORIGIN_MARGIN = RegionalCrammingGeometry.ORIGIN_MARGIN;
    private static final double SNAPSHOT_PAD = RegionalCrammingGeometry.SNAPSHOT_PAD;
    private static final double SENSOR_PAD = RegionalCrammingGeometry.SENSOR_PAD;
    private static final RegionalCramming INSTANCE = new RegionalCramming();
    private static final ActiveRegionBatches<RegionBatch> ACTIVE = new ActiveRegionBatches<>();
    /** Every pooled batch is post-clear; collect relies on empty identity tables. */
    private static final ConcurrentLinkedQueue<RegionBatch> POOL = new ConcurrentLinkedQueue<>();

    private RegionalCramming() {}

    /** Called once from CrammingHooks after the Class.forName probe succeeds. */
    public static void register() {
        RegionTickHooks.register(INSTANCE);
        registerSensorBridge();
    }

    /**
     * Separate method so the SensorCandidateBridge constant-pool reference
     * resolves HERE — an Fabrolia jar that has RegionTickHooks but predates
     * the bridge keeps cramming and just leaves sensors on the vanilla query.
     */
    private static void registerSensorBridge() {
        try {
            SensorCandidateBridge.register(RegionalCramming::nearestLivingForSensor);
        } catch (NoClassDefFoundError e) {
            // older Fabrolia — no sensor bridge, nothing to register
        }
    }

    /**
     * {@link SensorCandidateBridge.Provider}: serve the nearest-living sensor
     * query from the current region batch. Null (→ vanilla query) when the
     * thread holds no region claim, the body has no origin row, the sensor
     * box exceeds the halo-covered area, or candidates overflow.
     */
    private static List<LivingEntity> nearestLivingForSensor(ServerLevel level, LivingEntity body, double range) {
        if (!(Thread.currentThread() instanceof RegionWorkerThread w)) {
            return null;
        }
        Region r = w.currentRegion;
        if (r == null || r.level != level) {
            return null;
        }
        RegionBatch b = ACTIVE.get(r.id, r);
        return b == null ? null : b.nearestLiving(level, body, range);
    }

    /** Is the region scheduler ticking entities right now (runtime-toggleable)? */
    public static boolean regionsActive() {
        return RegionScheduler.isActive();
    }

    /** Region batch for the current thread, or null (not a region worker / no batch). */
    public static CandidateBatch currentThreadBatch() {
        if (Thread.currentThread() instanceof RegionWorkerThread w) {
            Region r = w.currentRegion;
            if (r != null) {
                return ACTIVE.get(r.id, r);
            }
        }
        return null;
    }

    /** Set when the tick-head Fabrolia probe failed: a still-registered hook does no work. */
    private static volatile boolean disabled;

    static void disable() {
        disabled = true;
    }

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("Rustmi-Cramming");
    private static final java.util.concurrent.atomic.AtomicLong LAST_FAILURE_LOG_MS =
            new java.util.concurrent.atomic.AtomicLong();

    @Override
    public void preEntityTick(Region region) {
        // Escape tracking is fixed at the tick head; a batch collected without it (cramming
        // switched on mid-tick) could serve rows of entities that moved since.
        if (disabled || !CrammingEscapes.tracking()) {
            return;   // no batch: the mixin falls through to vanilla
        }
        RegionBatch b = null;
        boolean sensorBegun = false;
        try {
            b = POOL.poll();
            if (b == null) {
                b = new RegionBatch();
            }
            b.collect(region);
            if (b.size() == 0) {
                b.clear();
                POOL.add(b);
                return;
            }
            b.runKernel();
            b.sensorBegin();
            sensorBegun = true;
            // The volatile slot write releases the completed batch to serial and
            // dense-fanout workers before any entity of this region can tick.
            ACTIVE.publish(region.id, region, b);
        } catch (Throwable t) {
            // Fabrolia treats an exception from this hook as a fatal scheduler failure: run
            // the region vanilla instead (no batch published, the possibly broken one dropped).
            ACTIVE.remove(region.id, region);
            if (sensorBegun) {
                try {
                    b.sensorEnd();
                } catch (Throwable ignored) {
                    // the batch is discarded anyway
                }
            }
            logFailure("collect", t);
        }
    }

    @Override
    public void postEntityTick(Region region) {
        RegionBatch b = ACTIVE.remove(region.id, region);
        if (b != null) {
            try {
                b.sensorEnd();   // before clear/grow can touch the retained buffer
                b.clear();
                POOL.add(b);
            } catch (Throwable t) {
                logFailure("release", t);
            }
        }
    }

    private static void logFailure(String phase, Throwable t) {
        long now = System.currentTimeMillis();
        long last = LAST_FAILURE_LOG_MS.get();
        if (now - last >= 1000 && LAST_FAILURE_LOG_MS.compareAndSet(last, now)) {
            LOGGER.error("Regional cramming {} failed; this region runs vanilla", phase, t);
        }
    }

    /**
     * One region's snapshot + kernel I/O. Single-writer (the coordinating
     * worker) during collect; read-only for everyone afterwards.
     */
    static final class RegionBatch implements CandidateBatch {
        private ByteBuffer inputs;
        private ByteBuffer outputs;
        private long inputsAddr;
        private long outputsAddr;
        /** Same-size twin of {@code inputs}: records are permuted into it, then swapped. */
        private ByteBuffer inputsAlt;
        private long inputsAltAddr;
        private Entity[] entities;
        /** Section insertion stamp of each slot's entity at collection (vanilla-order proof). */
        private long[] seqs;
        private long[] keys;
        private int[] perm;
        private Entity[] permEntities;
        private double[] permRanges;
        private boolean ordered;
        private int[] inv;
        private int capacity;
        private int used;
        private final Reference2IntOpenHashMap<Entity> originSlots =
                new Reference2IntOpenHashMap<>(256);
        private final ReferenceOpenHashSet<Entity> seen = new ReferenceOpenHashSet<>(256);
        /** Largest sensor range represented by the supplement for each origin slot. */
        private double[] sensorRanges;
        /** Union of admitted-origin cramming and covered-sensor query envelopes. */
        private double queryMinX, queryMinY, queryMinZ, queryMaxX, queryMaxY, queryMaxZ;
        /** Registry key for the native retention (stable per batch). */
        private final long sensorHandle = RustmiCramming.QUERY_HANDLES.getAndIncrement();
        /** XZ area the batch is a guaranteed superset over (region + halo). */
        private double coveredLoX, coveredHiX, coveredLoZ, coveredHiZ;

        RegionBatch() {
            originSlots.defaultReturnValue(-1);
            allocate(1024);
        }

        private void allocate(int cap) {
            this.capacity = cap;
            this.inputs = ByteBuffer.allocateDirect(cap * RustmiCramming.INPUT_STRIDE)
                    .order(ByteOrder.nativeOrder());
            this.outputs = ByteBuffer.allocateDirect(cap * RustmiCramming.OUTPUT_STRIDE)
                    .order(ByteOrder.nativeOrder());
            this.inputsAddr = RustmiCramming.addressOf(this.inputs);
            this.outputsAddr = RustmiCramming.addressOf(this.outputs);
            this.inputsAlt = ByteBuffer.allocateDirect(cap * RustmiCramming.INPUT_STRIDE)
                    .order(ByteOrder.nativeOrder());
            this.inputsAltAddr = RustmiCramming.addressOf(this.inputsAlt);
            this.seqs = new long[cap];
            this.keys = new long[cap];
            this.perm = new int[cap];
            this.permEntities = new Entity[cap];
            this.permRanges = new double[cap];
            this.entities = entities == null ? new Entity[cap] : Arrays.copyOf(entities, cap);
            this.sensorRanges = sensorRanges == null
                    ? new double[cap] : Arrays.copyOf(sensorRanges, cap);
        }

        /** Mid-collect growth: copy filled input records, keep entity refs. */
        private void grow() {
            // Keep the old buffer strongly reachable until the copy is done:
            // allocate() drops this.inputs, and allocateDirect may run
            // System.gc() under direct-memory pressure, whose Cleaner would
            // free oldAddr before copyMemory reads it.
            ByteBuffer oldInputs = this.inputs;
            long oldAddr = this.inputsAddr;
            int oldUsed = this.used;
            allocate(capacity * 2);
            RustmiCramming.theUnsafe().copyMemory(
                    oldAddr, this.inputsAddr, (long) oldUsed * RustmiCramming.INPUT_STRIDE);
            java.lang.ref.Reference.reachabilityFence(oldInputs);
        }

        int size() {
            return used;
        }

        /** Per-collect memo for isPositionEntityTicking: a 600-mob pen shares a
         *  handful of chunks, and the per-entity path was a BlockPos allocation
         *  plus a concurrent-map get each (4.0 core-ms on the 2026-07-11
         *  profiles). One region's collect freezes the answer per chunk for the
         *  batch — MORE coherent than interleaved live reads, and the kernel
         *  result is a candidate superset re-checked live anyway. */
        private final it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap chunkTickMemo =
                new it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap(32);

        void collect(Region region) {
            used = 0;
            ordered = false;
            chunkTickMemo.clear();
            queryMinX = queryMinY = queryMinZ = Double.POSITIVE_INFINITY;
            queryMaxX = queryMaxY = queryMaxZ = Double.NEGATIVE_INFINITY;

            RegionGrid grid = RegionScheduler.grid();
            if (grid == null) {
                return;
            }
            ServerLevel level = region.level;
            double span = 16L << grid.shift;
            double minX = grid.regionX(region.gridKey) * span;
            double minZ = grid.regionZ(region.gridKey) * span;
            double loX = minX - HALO,          hiX = minX + span + HALO;
            double loZ = minZ - HALO,          hiZ = minZ + span + HALO;
            this.coveredLoX = loX; this.coveredHiX = hiX;
            this.coveredLoZ = loZ; this.coveredHiZ = hiZ;
            double ogLoX = minX - ORIGIN_MARGIN, ogHiX = minX + span + ORIGIN_MARGIN;
            double ogLoZ = minZ - ORIGIN_MARGIN, ogHiZ = minZ + span + ORIGIN_MARGIN;
            int dimId = level.dimension().identifier().hashCode();

            // Members — origins (when inside the origin margin) + colliders.
            for (Entity e : region.entities) {
                if (seen.add(e)) {
                    add(e, true, level, dimId,
                            loX, hiX, loZ, hiZ, ogLoX, ogHiX, ogLoZ, ogHiZ);
                }
            }

            // No member can consume a kernel or sensor result. Candidate-only
            // rows would otherwise be marshalled, published, and cleared even
            // though every entity tick necessarily takes the vanilla fallback.
            if (originSlots.isEmpty()) {
                Arrays.fill(entities, 0, used, null);
                used = 0;
                return;
            }
            // Region membership is centre-owned, but vanilla's section index
            // is keyed by actual AABBs. This one union query therefore
            // retrieves every cramming and covered-sensor candidate without
            // walking neighbouring centre-owner sets. Current-region rows are
            // identity-deduplicated.
            AABB envelope = new AABB(
                    queryMinX, queryMinY, queryMinZ,
                    queryMaxX, queryMaxY, queryMaxZ);
            for (Entity e : level.getEntities((Entity) null, envelope, ignored -> true)) {
                if (seen.add(e)) {
                    add(e, false, level, dimId,
                            loX, hiX, loZ, hiZ, ogLoX, ogHiX, ogLoZ, ogHiZ);
                }
            }
            reorderVanilla();
        }

        /**
         * Permutes the collected rows into vanilla entity-section order (section key, then
         * insertion stamp) so the kernel's ascending candidate slots are vanilla's iteration
         * order — the consumers then skip their per-query sort. Runs before the kernel and
         * the native retention read the input records.
         */
        private void reorderVanilla() {
            int n = used;
            long[] k = keys;
            long[] q = seqs;
            boolean sorted = true;
            for (int i = 0; i < n; i++) {
                Entity e = entities[i];
                // Stamp before position (the writer stores position, then the section stamp):
                // a torn pair is (old stamp, new key), which the query-time stamp check rejects.
                q[i] = ((CrammingTracked) e).rustmi$sectionSeq();
                net.minecraft.core.BlockPos p = e.blockPosition();
                k[i] = net.minecraft.core.SectionPos.asLong(p.getX() >> 4, p.getY() >> 4, p.getZ() >> 4);
                perm[i] = i;
                if (i > 0 && (k[i] < k[i - 1] || (k[i] == k[i - 1] && q[i] < q[i - 1]))) {
                    sorted = false;
                }
            }
            if (!sorted) {
                it.unimi.dsi.fastutil.ints.IntArrays.quickSort(perm, 0, n,
                        (a, b) -> k[a] != k[b] ? Long.compare(k[a], k[b]) : Long.compare(q[a], q[b]));
                var u = RustmiCramming.theUnsafe();
                long stride = RustmiCramming.INPUT_STRIDE;
                for (int i = 0; i < n; i++) {
                    u.copyMemory(inputsAddr + perm[i] * stride, inputsAltAddr + i * stride, stride);
                    permEntities[i] = entities[perm[i]];
                    permRanges[i] = sensorRanges[perm[i]];
                }
                ByteBuffer swap = inputs;
                inputs = inputsAlt;
                inputsAlt = swap;
                long swapAddr = inputsAddr;
                inputsAddr = inputsAltAddr;
                inputsAltAddr = swapAddr;
                for (int i = 0; i < n; i++) {
                    long seq = q[perm[i]];
                    keys[i] = seq;   // reuse as scratch for the permuted stamps
                }
                System.arraycopy(keys, 0, seqs, 0, n);
                System.arraycopy(permEntities, 0, entities, 0, n);
                System.arraycopy(permRanges, 0, sensorRanges, 0, n);
                Arrays.fill(permEntities, 0, n, null);
                int[] inv = keysAsInverse(n);
                for (var it = originSlots.reference2IntEntrySet().fastIterator(); it.hasNext(); ) {
                    var en = it.next();
                    en.setValue(inv[en.getIntValue()]);
                }
            }
            ordered = true;
        }

        /** old slot → new slot for the permutation just applied. */
        private int[] keysAsInverse(int n) {
            if (inv == null || inv.length < perm.length) {
                inv = new int[perm.length];
            }
            for (int i = 0; i < n; i++) {
                inv[perm[i]] = i;
            }
            return inv;
        }

        @Override
        public boolean slotOrderIsVanilla() {
            return ordered;
        }

        @Override
        public long collectedSeq(int slot) {
            return slot >= 0 && slot < used ? seqs[slot] : Long.MIN_VALUE;
        }

        private void add(Entity e, boolean member, ServerLevel level, int dimId,
                         double loX, double hiX, double loZ, double hiZ,
                         double ogLoX, double ogHiX, double ogLoZ, double ogHiZ) {
            double x = e.getX();
            double z = e.getZ();
            AABB bb = e.getBoundingBox();
            double centerX = (bb.maxX + bb.minX) * 0.5;
            double centerY = (bb.maxY + bb.minY) * 0.5;
            double centerZ = (bb.maxZ + bb.minZ) * 0.5;
            double halfX = (bb.maxX - bb.minX) * 0.5;
            double halfY = (bb.maxY - bb.minY) * 0.5;
            double halfZ = (bb.maxZ - bb.minZ) * 0.5;
            if (!RegionalCrammingGeometry.colliderWithinHalo(
                    centerX, centerZ, halfX, halfZ, loX, hiX, loZ, hiZ)) {
                return;   // cannot reach any covered origin, even with a wide AABB
            }
            boolean living = e instanceof LivingEntity le && le.isAlive();
            boolean pushable = RustmiCramming.snapshotPushable(e, living);
            boolean origin = member && living
                    && RegionalCrammingGeometry.originWithinCoveredArea(
                            centerX, centerZ, halfX, halfZ, ogLoX, ogHiX, ogLoZ, ogHiZ)
                    && !e.isPassenger()
                    && !e.isSpectator()
                    && fabrolia$chunkTicking(level, x, z);
            // Alive living rows are conservative cramming candidates without
            // evaluating their context-sensitive isPushable(), and also stay
            // sensor-visible exactly like vanilla's nearest-living query.
            if (!pushable && !origin && !living) {
                return;
            }
            if (used == capacity) {
                grow();
            }
            int slot = used++;
            sensorRanges[slot] = -1.0;
            int flags = (pushable ? RustmiCramming.FLAG_IS_PUSHABLE : 0)
                      | (origin ? RustmiCramming.FLAG_IS_ORIGIN : 0)
                      | (living ? RustmiCramming.FLAG_IS_LIVING : 0);
            RustmiCramming.writeInputRecord(inputsAddr, slot, dimId, flags,
                    centerX, centerY, centerZ, halfX, halfY, halfZ);
            entities[slot] = e;
            CrammingEscapes.snapshotted(e, bb);
            if (origin) {
                originSlots.put(e, slot);
                includeQueryEnvelope(bb, SNAPSHOT_PAD);

                LivingEntity body = (LivingEntity) e;
                var followRange = body.getAttribute(
                        net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE);
                if (followRange != null) {
                    double range = followRange.getValue();
                    AABB sensorBox = bb.inflate(range, range, range);
                    if (RegionalCrammingGeometry.sensorBoxCovered(
                            sensorBox.minX, sensorBox.maxX, sensorBox.minZ, sensorBox.maxZ,
                            coveredLoX, coveredHiX, coveredLoZ, coveredHiZ)) {
                        sensorRanges[slot] = range;
                        includeQueryEnvelope(bb, range + SENSOR_PAD);
                    }
                }
            }
        }

        private void includeQueryEnvelope(AABB box, double inflate) {
            queryMinX = Math.min(queryMinX, box.minX - inflate);
            queryMinY = Math.min(queryMinY, box.minY - inflate);
            queryMinZ = Math.min(queryMinZ, box.minZ - inflate);
            queryMaxX = Math.max(queryMaxX, box.maxX + inflate);
            queryMaxY = Math.max(queryMaxY, box.maxY + inflate);
            queryMaxZ = Math.max(queryMaxZ, box.maxZ + inflate);
        }

        private boolean fabrolia$chunkTicking(ServerLevel level, double x, double z) {
            long key = net.minecraft.world.level.ChunkPos.pack(
                    net.minecraft.util.Mth.floor(x) >> 4, net.minecraft.util.Mth.floor(z) >> 4);
            byte v = chunkTickMemo.get(key);   // 0 unknown, 1 ticking, 2 not
            if (v == 0) {
                v = level.isPositionEntityTicking(net.minecraft.core.BlockPos.containing(x, 0, z)) ? (byte) 1 : (byte) 2;
                chunkTickMemo.put(key, v);
            }
            return v == 1;
        }

        void runKernel() {
            RustmiLib.oxi_cramming_batch(inputsAddr, outputsAddr, used);
        }

        void sensorBegin() {
            if (used > 0) {
                RustmiLib.oxi_sensor_begin(sensorHandle, inputsAddr, used);
            }
        }

        void sensorEnd() {
            RustmiLib.oxi_sensor_end(sensorHandle);
        }

        /**
         * Serve one nearest-living sensor query: Rust returns a padded
         * snapshot SUPERSET of living candidates; re-filtering against live
         * state below reproduces the vanilla query verbatim
         * ({@code e != body && e.isAlive()}, live box intersection). Null →
         * caller runs the vanilla section walk.
         *
         * <p>Entities that entered the area after this tick's snapshot are
         * missed for ≤1 tick on a 10-tick sense cadence — the same accepted
         * staleness class as the cramming candidates (and teleport-class
         * movement, see the kernel's SENSOR_PAD note).
         */
        List<LivingEntity> nearestLiving(ServerLevel level, LivingEntity body, double range) {
            int slot = originSlots.getInt(body);
            if (slot < 0 || !CrammingEscapes.originReliable(body)) {
                return null;
            }
            // Collection records the exact FOLLOW_RANGE represented by its
            // AABB supplement. An increase or an inward move after collection
            // falls back rather than consuming an incomplete snapshot.
            if (!(range <= sensorRanges[slot])) {
                return null;
            }
            AABB box = body.getBoundingBox().inflate(range, range, range);
            if (!RegionalCrammingGeometry.sensorBoxCovered(
                    box.minX, box.maxX, box.minZ, box.maxZ,
                    coveredLoX, coveredHiX, coveredLoZ, coveredHiZ)) {
                return null;
            }
            long outAddr = RustmiCramming.scratchAddr();
            int n = RustmiLib.oxi_sensor_query(sensorHandle, slot, range, outAddr, RustmiCramming.QUERY_SCRATCH_CAP);
            if (n < 0) {
                return null;
            }
            List<LivingEntity> out = new ArrayList<>(n);
            var u = RustmiCramming.theUnsafe();
            boolean inOrder = ordered;   // ascending slots = vanilla order for unmoved rows
            CrammingEscapes.SectionWindow window = CrammingEscapes.SectionWindow.of(box);
            int prevSlot = -1;
            for (int i = 0; i < n; i++) {
                int candidateSlot = u.getInt(outAddr + 4L * i);
                Entity e = entityAt(candidateSlot);
                if (e instanceof LivingEntity le && le != body && le.isAlive()
                        && le.level() == level && ((CrammingTracked) le).rustmi$inLevel()
                        && box.intersects(le.getBoundingBox())
                        && window.contains(le)) {
                    out.add(le);
                    // Native output contract: ascending slots. Verify instead of trusting it.
                    if (inOrder && (candidateSlot <= prevSlot
                            || ((CrammingTracked) le).rustmi$sectionSeq() != seqs[candidateSlot])) {
                        inOrder = false;
                    }
                    prevSlot = candidateSlot;
                }
            }
            int kernelCount = out.size();
            out = CrammingEscapes.addEscapees(out, LivingEntity.class, body, box, LivingEntity::isAlive);
            if (!inOrder || out.size() != kernelCount) {
                CrammingEscapes.sortVanilla(out);
            }
            if (CrammingShadow.isEnabled()) {
                CrammingShadow.compareSensor(body, box, out);
            }
            return out;
        }

        void clear() {
            Arrays.fill(entities, 0, used, null);
            originSlots.clear();
            seen.clear();
            used = 0;
        }

        @Override
        public int overflowCandidates(int slot) {
            return RustmiCramming.nativeOverflowQuery(sensorHandle, slot);
        }

        @Override
        public int scratchCandidateSlot(int i) {
            return RustmiCramming.scratchSlotAt(i);
        }

        @Override
        public int originSlot(Entity entity) {
            return originSlots.getInt(entity);
        }

        @Override
        public int candidateCount(int slot) {
            return RustmiCramming.theUnsafe()
                    .getInt(outputsAddr + (long) slot * RustmiCramming.OUTPUT_STRIDE);
        }

        @Override
        public int candidateSlot(int slot, int i) {
            return RustmiCramming.theUnsafe()
                    .getInt(outputsAddr + (long) slot * RustmiCramming.OUTPUT_STRIDE + 4 + 4L * i);
        }

        @Override
        public Entity entityAt(int slot) {
            return slot >= 0 && slot < used ? entities[slot] : null;
        }
    }
}
