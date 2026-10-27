package com.github.tatercertified.nalim.collision;

import com.github.tatercertified.nalim.cramming.RustmiCramming;
import com.github.tatercertified.nalim.rust.RustmiLib;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * C2 of the collision campaign: full-cube fast path for {@code Entity.collide}.
 * The classifier mirror (per-chunk section bitsets, see {@link CubeMirror})
 * answers "is every candidate collider a plain unit cube?" — if yes, the
 * bit-exact Rust kernel ({@code oxi_collide}, 400/400 fixtures) resolves the
 * movement; any partial/context shape in range, entity colliders, the world
 * border, an unloaded chunk, or a sub-epsilon movement component falls back
 * to vanilla untouched.
 *
 * <p>Thread model: called concurrently from 16 Fabrolia region workers + main.
 * Mirror reads are plain (stale-tolerant — the same data race class as the
 * concurrent block access region ticking already performs); per-call buffers
 * and diagnostic counters are thread-local. Status reads their volatile
 * snapshots without putting atomic read-modify-write operations on movement.
 */
public final class CollisionEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger("Rustmi-Collision");

    private CollisionEngine() {}

    // ── classification ───────────────────────────────────────────────────

    public static final int CLS_EMPTY = 0, CLS_FULL = 1, CLS_PARTIAL = 2, CLS_BOXES = 3;

    /**
     * Vanilla classes whose own {@code getCollisionShape} body reads only the state (audited
     * against 26.3: no level, pos, context or offset access).
     */
    private static final java.util.Set<Class<?>> STATE_ONLY_COLLISION = java.util.Set.of(
            net.minecraft.world.level.block.CrossCollisionBlock.class,   // fences, panes, bars
            net.minecraft.world.level.block.WallBlock.class,
            net.minecraft.world.level.block.FenceGateBlock.class,
            net.minecraft.world.level.block.SnowLayerBlock.class,
            net.minecraft.world.level.block.ComposterBlock.class,
            net.minecraft.world.level.block.SoulSandBlock.class,
            net.minecraft.world.level.block.MudBlock.class,
            net.minecraft.world.level.block.HoneyBlock.class,
            net.minecraft.world.level.block.CactusBlock.class,
            net.minecraft.world.level.block.LecternBlock.class,
            net.minecraft.world.level.block.BellBlock.class,
            net.minecraft.world.level.block.GrindstoneBlock.class,
            net.minecraft.world.level.block.BigDripleafBlock.class,
            net.minecraft.world.level.block.SculkShriekerBlock.class,
            net.minecraft.world.level.block.SkullBlock.class,
            net.minecraft.world.level.block.WallHangingSignBlock.class,
            net.minecraft.world.level.block.PitcherCropBlock.class,
            net.minecraft.world.level.block.MossyCarpetBlock.class);

    /**
     * Vanilla classes whose own {@code getShape} body reads only the state, for blocks that
     * keep BlockBehaviour's default {@code getCollisionShape} (= {@code getShape(level, pos)}).
     */
    private static final java.util.Set<Class<?>> STATE_ONLY_SHAPE = java.util.Set.of(
            net.minecraft.world.level.block.SlabBlock.class,
            net.minecraft.world.level.block.StairBlock.class,
            net.minecraft.world.level.block.FarmlandBlock.class,
            net.minecraft.world.level.block.PathBlock.class,
            net.minecraft.world.level.block.DoorBlock.class,
            net.minecraft.world.level.block.TrapDoorBlock.class,
            net.minecraft.world.level.block.CarpetBlock.class,
            net.minecraft.world.level.block.BedBlock.class,
            net.minecraft.world.level.block.StrawBedBlock.class,
            net.minecraft.world.level.block.ChestBlock.class,
            net.minecraft.world.level.block.EnderChestBlock.class,
            net.minecraft.world.level.block.AnvilBlock.class,
            net.minecraft.world.level.block.LanternBlock.class,
            net.minecraft.world.level.block.WallSkullBlock.class,
            net.minecraft.world.level.block.PiglinWallSkullBlock.class);

    /**
     * State-determined collision shapes, safe to evaluate with EmptyBlockGetter. Decided by the
     * class that actually DECLARES the shape method the collision path reaches — not by
     * instanceof — so a mod subclass that overrides it with world reads stays CLS_PARTIAL.
     */
    private static final ClassValue<Boolean> STATE_SHAPED = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            Class<?> collision = nearestDeclarer(type, "getCollisionShape");
            if (collision != BlockBehaviour.class) {
                return STATE_ONLY_COLLISION.contains(collision);
            }
            return STATE_ONLY_SHAPE.contains(nearestDeclarer(type, "getShape"));
        }
    };

    private static Class<?> nearestDeclarer(Class<?> type, String name) {
        for (Class<?> k = type; k != null && k != BlockBehaviour.class; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    return k;
                }
            }
        }
        return BlockBehaviour.class;
    }

    private static boolean oxiIsStateShaped(net.minecraft.world.level.block.Block bl) {
        return STATE_SHAPED.get(bl.getClass());
    }

    /** C3: vanilla merged voxel grid of a multi-box state — per-axis point
     *  positions (shape-local) + occupancy, idx = (x*ny + y)*nz + z. */
    static final class GridPayload {
        final int nx, ny, nz, nWords;
        final double[] cx, cy, cz;
        final long[] bits;

        GridPayload(int nx, int ny, int nz, double[] cx, double[] cy, double[] cz, long[] bits) {
            this.nx = nx; this.ny = ny; this.nz = nz;
            this.cx = cx; this.cy = cy; this.cz = cz;
            this.bits = bits;
            this.nWords = bits.length;
        }

        int byteSize() {
            return 40 + (cx.length + cy.length + cz.length) * 8 + bits.length * 8;
        }
    }

    private static final ConcurrentHashMap<BlockState, GridPayload> STATE_GRIDS = new ConcurrentHashMap<>(256);

    /** Flattened collider AABBs per CLS_BOXES state (shape-local coords). */
    private static final ConcurrentHashMap<BlockState, double[]> STATE_BOXES = new ConcurrentHashMap<>(1024);

    public static double[] boxesFor(BlockState s) {
        return STATE_BOXES.get(s);
    }

    private static final ConcurrentHashMap<BlockState, Byte> STATE_CLS = new ConcurrentHashMap<>(4096);
    private static final ConcurrentHashMap<Class<?>, Boolean> SHAPE_CTX = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Boolean> SHAPE_BE = new ConcurrentHashMap<>();

    private static boolean declaresBelow(Class<?> c, Class<?> base, String name) {
        for (Class<?> k = c; k != null && k != base; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** C3: pull the merged voxel grid out of a multi-box VoxelShape. Null →
     *  too big / unexpected layout → vanilla quarantine. */
    private static GridPayload extractGrid(net.minecraft.world.phys.shapes.VoxelShape shape) {
        try {
            var acc = (com.github.tatercertified.nalim.mixin.VoxelShapeAccessor) shape;
            var vox = acc.oxi$voxels();
            int nx = vox.getXSize(), ny = vox.getYSize(), nz = vox.getZSize();
            if (nx <= 0 || ny <= 0 || nz <= 0 || nx > 16 || ny > 16 || nz > 16) {
                return null;
            }
            double[] cx = acc.oxi$coords(net.minecraft.core.Direction.Axis.X).toDoubleArray();
            double[] cy = acc.oxi$coords(net.minecraft.core.Direction.Axis.Y).toDoubleArray();
            double[] cz = acc.oxi$coords(net.minecraft.core.Direction.Axis.Z).toDoubleArray();
            if (cx.length != nx + 1 || cy.length != ny + 1 || cz.length != nz + 1) {
                return null;
            }
            long[] bits = new long[((nx * ny * nz) + 63) >> 6];
            for (int x = 0; x < nx; x++) {
                for (int y = 0; y < ny; y++) {
                    for (int z = 0; z < nz; z++) {
                        if (vox.isFull(x, y, z)) {
                            int idx = (x * ny + y) * nz + z;
                            bits[idx >> 6] |= 1L << (idx & 63);
                        }
                    }
                }
            }
            return new GridPayload(nx, ny, nz, cx, cy, cz, bits);
        } catch (Throwable t) {
            return null;
        }
    }

    /** True when the shape's voxel grid holds ≥2 consecutive full cells along
     *  some axis — i.e. VoxelShape.calculateMaxDistance has internal walls a
     *  penetrating box collides with (lastFull is exclusive). Unknown layout →
     *  true, so the grid extractor decides (and quarantines on failure). */
    private static boolean hasInternalCellWalls(net.minecraft.world.phys.shapes.VoxelShape shape) {
        try {
            var vox = ((com.github.tatercertified.nalim.mixin.VoxelShapeAccessor) shape).oxi$voxels();
            for (net.minecraft.core.Direction.Axis axis : net.minecraft.core.Direction.Axis.values()) {
                if (vox.lastFull(axis) - vox.firstFull(axis) > 1) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    /** Gather coordinates at or beyond this magnitude go to vanilla (int-range safety). */
    private static final double COORD_LIMIT = 1.0E9;

    public static int classify(BlockState s) {
        if (s == null) {
            // A palette slot read while another thread resizes the container (Fabrolia
            // regions): unknown cell, so every query touching it runs vanilla.
            return CLS_PARTIAL;
        }
        Byte cached = STATE_CLS.get(s);
        if (cached != null) {
            return cached;
        }
        int cls;
        try {
            if (oxiIsStateShaped(s.getBlock())) {
                var shape = s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                if (shape.isEmpty()) {
                    cls = CLS_EMPTY;
                } else {
                    var aabbs = shape.toAabbs();
                    if (aabbs.size() > 1 || hasInternalCellWalls(shape)) {
                        // C3: multi-box shapes (fences/stairs/walls) ship their
                        // ACTUAL merged voxel grid to the kernel — per-box
                        // flattening diverged from vanilla at internal faces
                        // (5e-9-class clamps, 60/23.6M shadow); the grid port
                        // reproduces calculateMaxDistance exactly. Single boxes
                        // whose grid has ≥2 full cells along an axis (farmland,
                        // paths, chests, deep snow) take the same route: vanilla
                        // stops a box already inside the shape at the next
                        // INTERNAL cell wall, which the flat box cannot model.
                        GridPayload g = extractGrid(shape);
                        if (g == null) {
                            cls = CLS_PARTIAL;
                        } else {
                            STATE_GRIDS.put(s, g);
                            cls = CLS_BOXES;
                        }
                        STATE_CLS.put(s, (byte) cls);
                        return cls;
                    }
                    double[] flat = new double[aabbs.size() * 6];
                    int fi = 0;
                    for (var ab : aabbs) {
                        flat[fi++] = ab.minX; flat[fi++] = ab.minY; flat[fi++] = ab.minZ;
                        flat[fi++] = ab.maxX; flat[fi++] = ab.maxY; flat[fi++] = ab.maxZ;
                    }
                    if (shape == net.minecraft.world.phys.shapes.Shapes.block()) {
                        // FULL means the singleton: vanilla tests it with box.intersects,
                        // an equal-content copy with Shapes.joinIsNotEmpty (1e-7 merging).
                        cls = CLS_FULL;
                    } else {
                        STATE_BOXES.put(s, flat);
                        cls = CLS_BOXES;
                    }
                }
            } else if (SHAPE_CTX.computeIfAbsent(s.getBlock().getClass(),
                    k -> declaresBelow(k, BlockBehaviour.class, "getCollisionShape"))) {
                cls = CLS_PARTIAL;   // context-capable — conservative, vanilla path
            } else if (s.hasBlockEntity() && SHAPE_BE.computeIfAbsent(s.getBlock().getClass(),
                    k -> declaresBelow(k, BlockBehaviour.class, "getShape"))) {
                // Default getCollisionShape is getShape(level, pos), and an EntityBlock's
                // getShape may read its block entity (shulker box lid) — EmptyBlockGetter
                // would see the closed shape only.
                cls = CLS_PARTIAL;
            } else if (s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) {
                cls = CLS_EMPTY;
            } else if (s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
                    == net.minecraft.world.phys.shapes.Shapes.block()) {
                cls = CLS_FULL;   // singleton only; equal-content composites may have inner walls
            } else {
                cls = CLS_PARTIAL;
            }
        } catch (Throwable t) {
            cls = CLS_PARTIAL;
        }
        STATE_CLS.put(s, (byte) cls);
        return cls;
    }

    // ── persist (same pattern as ExplosionEngine) ────────────────────────

    private static final Path PERSIST_DIR = Paths.get("config", "rustmi-runtime");

    private static boolean loadPersisted(String name) {
        return loadPersisted(name, false);
    }

    /** Optimizations default ON for fresh installs (user directive 2026-06-12);
     *  a persisted flag, once written by a toggle command, always wins. */
    private static boolean loadPersisted(String name, boolean def) {
        try {
            Path p = PERSIST_DIR.resolve(name + ".flag");
            if (!Files.exists(p)) {
                return def;
            }
            return "1".equals(Files.readString(p, StandardCharsets.UTF_8).trim());
        } catch (IOException e) {
            return def;
        }
    }

    private static void persist(String name, boolean v) {
        try {
            Files.createDirectories(PERSIST_DIR);
            Files.writeString(PERSIST_DIR.resolve(name + ".flag"), v ? "1" : "0", StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("persist {} failed", name, e);
        }
    }

    // ── toggles + stats ──────────────────────────────────────────────────

    private static volatile boolean shadow = loadPersisted("collision-shadow");
    private static volatile boolean engine = loadPersisted("collision-engine", true);

    private static final class Stats {
        volatile long epoch = Long.MIN_VALUE;
        volatile long calls;
        volatile long fast;
        volatile long bailPartial;
        volatile long bailOther;
        volatile long shadowOk;
        volatile long shadowBad;
        /** bailOther split: precision, cellCap, noChunk, noMirror, solidsCap, gridsCap, kernel, negZero. */
        final long[] why = new long[WHY.length];

        void bail(int reason) {
            bailOther++;
            why[reason]++;
        }

        void reset(long newEpoch) {
            calls = fast = bailPartial = bailOther = shadowOk = shadowBad = 0;
            java.util.Arrays.fill(why, 0);
            epoch = newEpoch;
        }
    }

    private static final String[] WHY = {
            "precision", "cellCap", "noChunk", "noMirror", "solidsCap", "gridsCap", "kernel", "negZero"};

    private static volatile long statsEpoch;
    private static final ConcurrentLinkedQueue<Stats> ALL_STATS = new ConcurrentLinkedQueue<>();
    private static final ThreadLocal<Stats> STATS = ThreadLocal.withInitial(() -> {
        Stats stats = new Stats();
        ALL_STATS.add(stats);
        return stats;
    });
    private static final ConcurrentLinkedQueue<String> EXAMPLES = new ConcurrentLinkedQueue<>();

    public static boolean isActive() {
        return engine || shadow;
    }

    public static synchronized void setShadow(boolean on) {
        if (on) {
            resetStats();
            engine = false;
            persist("collision-engine", false);
        }
        shadow = on;
        persist("collision-shadow", on);
    }

    public static synchronized void setEngine(boolean on) {
        if (on) {
            resetStats();
            shadow = false;
            persist("collision-shadow", false);
        }
        engine = on;
        persist("collision-engine", on);
        LOGGER.info("[collision-engine] {}", on ? "ENABLED" : "DISABLED");
    }

    private static void resetStats() {
        statsEpoch++;
        EXAMPLES.clear();
    }

    private static Stats currentStats() {
        Stats stats = STATS.get();
        long epoch = statsEpoch;
        if (stats.epoch != epoch) {
            stats.reset(epoch);
        }
        return stats;
    }

    public static String status() {
        long epoch = statsEpoch;
        long calls = 0;
        long fast = 0;
        long bailPartial = 0;
        long bailOther = 0;
        long shadowOk = 0;
        long shadowBad = 0;
        long[] why = new long[WHY.length];
        for (Stats stats : ALL_STATS) {
            if (stats.epoch == epoch) {
                for (int i = 0; i < why.length; i++) {
                    why[i] += stats.why[i];
                }
                calls += stats.calls;
                fast += stats.fast;
                bailPartial += stats.bailPartial;
                bailOther += stats.bailOther;
                shadowOk += stats.shadowOk;
                shadowBad += stats.shadowBad;
            }
        }
        StringBuilder sb = new StringBuilder(String.format(
                "collision shadow=%s engine=%s | calls=%d fast=%d bailPartial=%d bailOther=%d | shadow ok=%d bad=%d",
                shadow, engine, calls, fast, bailPartial, bailOther, shadowOk, shadowBad));
        sb.append(" | why{");
        for (int i = 0; i < WHY.length; i++) {
            sb.append(i == 0 ? "" : " ").append(WHY[i]).append('=').append(why[i]);
        }
        sb.append('}');
        if (!EXAMPLES.isEmpty()) {
            sb.append(" | ").append(String.join("; ", EXAMPLES));
        }
        return sb.toString();
    }

    // ── per-thread FFI buffers ───────────────────────────────────────────

    private static final int SOLIDS_CAP = 4096;
    /** C3: voxel-grid colliders per call (multi-box states). */
    private static final int GRIDS_CAP = 256;
    /** Max marshalled grid record: 40B header + 3×17×8B coords + 512B bits. */
    private static final int GRID_RECORD_CAP = 1024;

    private static final class Bufs {
        final ByteBuffer header = ByteBuffer.allocateDirect(96).order(ByteOrder.nativeOrder());
        final ByteBuffer solids = ByteBuffer.allocateDirect(SOLIDS_CAP * 48).order(ByteOrder.nativeOrder());
        final ByteBuffer grids = ByteBuffer.allocateDirect(GRIDS_CAP * GRID_RECORD_CAP).order(ByteOrder.nativeOrder());
        final ByteBuffer tags = ByteBuffer.allocateDirect(SOLIDS_CAP + GRIDS_CAP).order(ByteOrder.nativeOrder());
        final ByteBuffer out = ByteBuffer.allocateDirect(24).order(ByteOrder.nativeOrder());
        final long headerAddr = RustmiCramming.addressOf(header);
        final long solidsAddr = RustmiCramming.addressOf(solids);
        final long gridsAddr = RustmiCramming.addressOf(grids);
        final long tagsAddr = RustmiCramming.addressOf(tags);
        final long outAddr = RustmiCramming.addressOf(out);
    }

    private static final ThreadLocal<Bufs> BUFS = ThreadLocal.withInitial(Bufs::new);

    // ── the fast path ────────────────────────────────────────────────────

    /**
     * Lithium can resolve a supporting collision without retaining that shape
     * in its lazy iterable for later axes. In that case it preserves an
     * unconstrained non-zero component at or below Shapes.EPSILON, while the
     * native gathered-collider loop can make that value order-dependent. Such
     * boundary inputs must run the transformed reference path untouched.
     */
    public static boolean requiresVanillaPrecision(Vec3 movement) {
        return isNonZeroEpsilonBoundary(movement.x)
                || isNonZeroEpsilonBoundary(movement.y)
                || isNonZeroEpsilonBoundary(movement.z);
    }

    /**
     * Sub-epsilon movement (see above), non-finite components, and -0.0 components: vanilla's
     * collideWithShapes returns the movement vector itself when no shape is in range
     * (keeping -0.0) but rebuilds untouched axes from Vec3.ZERO (+0.0) otherwise, so the
     * sign of a zero component depends on the exact shape set.
     */
    /** Counts a precision fallback taken before {@link #tryCollide} was reached. */
    public static void countPrecisionBail(Vec3 movement) {
        Stats stats = currentStats();
        stats.calls++;
        stats.bail(hasSubEpsilon(movement) ? 0 : 7);
    }

    private static boolean hasSubEpsilon(Vec3 m) {
        return (m.x != 0.0 && Math.abs(m.x) <= 1.0E-7)
                || (m.y != 0.0 && Math.abs(m.y) <= 1.0E-7)
                || (m.z != 0.0 && Math.abs(m.z) <= 1.0E-7);
    }

    private static boolean isNonZeroEpsilonBoundary(double movement) {
        return (movement != 0.0 && Math.abs(movement) <= 1.0E-7)
                || Double.doubleToRawLongBits(movement) == Long.MIN_VALUE
                || !Double.isFinite(movement);   // NaN/Inf would floor the gather window to 0
    }

    /**
     * Attempts the full-cube fast path for the entity's collide call.
     * @param step the entity's {@code maxUpStep()} for this call (the caller
     *             already read it for the entity-collider gate)
     * @return resolved movement, or null → caller must run vanilla.
     */
    public static Vec3 tryCollide(Entity entity, Vec3 movement, AABB box, float step) {
        Stats stats = currentStats();
        stats.calls++;
        if (requiresVanillaPrecision(movement)) {   // callers gate earlier; kept as the contract
            stats.bail(hasSubEpsilon(movement) ? 0 : 7);
            return null;
        }
        // Query region: stretched box, extended up by the step height (step-up
        // probe), partial-scan padded by 1 (shapes poking out of their block,
        // e.g. fences). Any partial in the padded range → vanilla.
        double minX = Math.min(box.minX, box.minX + movement.x);
        double minY = Math.min(box.minY, box.minY + movement.y);
        double minZ = Math.min(box.minZ, box.minZ + movement.z);
        double maxX = Math.max(box.maxX, box.maxX + movement.x);
        double maxY = Math.max(box.maxY, box.maxY + movement.y) + step + 1.0E-5;
        double maxZ = Math.max(box.maxZ, box.maxZ + movement.z);
        // Mth.floor saturates/wraps beyond the int range and the padded bounds below would
        // overflow, making the cell cap pass with an empty chunk loop: vanilla handles those.
        if (!(Math.max(Math.abs(minX), Math.abs(maxX)) < COORD_LIMIT
                && Math.max(Math.abs(minY), Math.abs(maxY)) < COORD_LIMIT
                && Math.max(Math.abs(minZ), Math.abs(maxZ)) < COORD_LIMIT)) {
            stats.bail(1);
            return null;
        }

        int gx0 = Mth.floor(minX - 1.0E-7), gx1 = Mth.floor(maxX + 1.0E-7);
        int gy0 = Mth.floor(minY - 1.0E-7), gy1 = Mth.floor(maxY + 1.0E-7);
        int gz0 = Mth.floor(minZ - 1.0E-7), gz1 = Mth.floor(maxZ + 1.0E-7);
        // padded ring for the partial scan
        int px0 = gx0 - 1, px1 = gx1 + 1;
        int py0 = gy0 - 1, py1 = gy1 + 1;
        int pz0 = gz0 - 1, pz1 = gz1 + 1;

        long cells = (long) (px1 - px0 + 1) * (py1 - py0 + 1) * (pz1 - pz0 + 1);
        if (cells > SOLIDS_CAP * 3L) {
            stats.bail(1);
            return null;
        }

        var level = entity.level();
        int minBuildY = level.getMinY();
        int sectionCount = ((level.getMaxY() + 1) - minBuildY) >> 4;

        Bufs b = BUFS.get();
        var u = RustmiCramming.theUnsafe();
        int solidCount = 0;
        int gridCount = 0;
        long gridsCursor = b.gridsAddr;
        int colliderCount = 0;

        // Lithium's transformed sweeper advances chunk X first, then chunk Z;
        // section and in-section loops below already match its Y / Z / X order.
        int cMinX = px0 >> 4, cMaxX = px1 >> 4, cMinZ = pz0 >> 4, cMaxZ = pz1 >> 4;
        for (int cz = cMinZ; cz <= cMaxZ; cz++) {
            for (int cx = cMinX; cx <= cMaxX; cx++) {
                ChunkAccess chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (!(chunk instanceof LevelChunk lc)) {
                    stats.bail(2);
                    return null;
                }
                CubeMirror mirror = (CubeMirror) lc;
                int bx0 = Math.max(px0, cx << 4), bx1 = Math.min(px1, (cx << 4) + 15);
                int bz0 = Math.max(pz0, cz << 4), bz1 = Math.min(pz1, (cz << 4) + 15);
                int sy0 = Math.max(0, (py0 - minBuildY) >> 4);
                int sy1 = Math.min(sectionCount - 1, (py1 - minBuildY) >> 4);
                for (int sy = sy0; sy <= sy1; sy++) {
                    // One immutable snapshot per section (see CubeMirror): all three class
                    // bitsets come from the same publication, so they are mutually consistent.
                    long[] bits = mirror.oxi$bits(sy);
                    if (bits == null) {
                        stats.bail(3);
                        return null;
                    }
                    if (bits == CubeMirror.Snapshots.EMPTY) {
                        continue;   // nothing collides anywhere in this section
                    }
                    if (bits == CubeMirror.Snapshots.ALL_PARTIAL) {
                        stats.bailPartial++;   // every in-range cell is partial
                        return null;
                    }
                    int secBase = minBuildY + (sy << 4);
                    int by0 = Math.max(py0, secBase), by1 = Math.min(py1, secBase + 15);
                    boolean inGather; // cube collection only inside the strict range
                    for (int y = by0; y <= by1; y++) {
                        int ly = y & 15;
                        boolean yIn = y >= gy0 && y <= gy1;
                        for (int z = bz0; z <= bz1; z++) {
                            int lz = z & 15;
                            boolean zIn = yIn && z >= gz0 && z <= gz1;
                            for (int x = bx0; x <= bx1; x++) {
                                int cell = (ly << 8) | (lz << 4) | (x & 15);
                                long bit = 1L << (cell & 63);
                                int word = cell >> 6;
                                if ((bits[CubeMirror.PARTIAL_OFF + word] & bit) != 0) {
                                    stats.bailPartial++;
                                    return null;
                                }
                                inGather = zIn && x >= gx0 && x <= gx1;
                                if (inGather && (bits[CubeMirror.FULL_OFF + word] & bit) != 0) {
                                    if (solidCount >= SOLIDS_CAP) {
                                        stats.bail(4);
                                        return null;
                                    }
                                    long base = b.solidsAddr + (long) solidCount * 48;
                                    u.putDouble(base, x);
                                    u.putDouble(base + 8, y);
                                    u.putDouble(base + 16, z);
                                    u.putDouble(base + 24, x + 1.0);
                                    u.putDouble(base + 32, y + 1.0);
                                    u.putDouble(base + 40, z + 1.0);
                                    solidCount++;
                                    if (gridCount > 0) {
                                        u.putByte(b.tagsAddr + colliderCount, (byte) 0);
                                    }
                                    colliderCount++;
                                } else if ((bits[CubeMirror.BOXES_OFF + word] & bit) != 0) {
                                    // C3: shaped states are collected over the PADDED
                                    // ring too — fences/walls poke past their cell
                                    // (vanilla's hasLargeCollisionShape ring rule);
                                    // small shapes from the ring can't bind and the
                                    // kernel's voxel-precise filter drops them, so
                                    // the superset is exact.
                                    BlockState st = lc.getBlockState(new BlockPos(x, y, z));
                                    double[] flat = boxesFor(st);
                                    if (flat != null) {
                                        if (solidCount >= SOLIDS_CAP) {
                                            stats.bail(4);
                                            return null;
                                        }
                                        long base = b.solidsAddr + (long) solidCount * 48;
                                        u.putDouble(base, x + flat[0]);
                                        u.putDouble(base + 8, y + flat[1]);
                                        u.putDouble(base + 16, z + flat[2]);
                                        u.putDouble(base + 24, x + flat[3]);
                                        u.putDouble(base + 32, y + flat[4]);
                                        u.putDouble(base + 40, z + flat[5]);
                                        solidCount++;
                                        if (gridCount > 0) {
                                            u.putByte(b.tagsAddr + colliderCount, (byte) 0);
                                        }
                                        colliderCount++;
                                    } else {
                                        GridPayload g = STATE_GRIDS.get(st);
                                        if (g == null) {
                                            stats.bailPartial++;
                                            return null;
                                        }
                                        if (gridCount >= GRIDS_CAP
                                                || gridsCursor + g.byteSize() > b.gridsAddr + (long) GRIDS_CAP * GRID_RECORD_CAP) {
                                            stats.bail(5);
                                            return null;
                                        }
                                        u.putDouble(gridsCursor, x);
                                        u.putDouble(gridsCursor + 8, y);
                                        u.putDouble(gridsCursor + 16, z);
                                        u.putInt(gridsCursor + 24, g.nx);
                                        u.putInt(gridsCursor + 28, g.ny);
                                        u.putInt(gridsCursor + 32, g.nz);
                                        u.putInt(gridsCursor + 36, g.nWords);
                                        long q = gridsCursor + 40;
                                        for (double v : g.cx) { u.putDouble(q, v); q += 8; }
                                        for (double v : g.cy) { u.putDouble(q, v); q += 8; }
                                        for (double v : g.cz) { u.putDouble(q, v); q += 8; }
                                        for (long w : g.bits) { u.putLong(q, w); q += 8; }
                                        gridsCursor = q;
                                        if (gridCount == 0 && colliderCount > 0) {
                                            u.setMemory(b.tagsAddr, colliderCount, (byte) 0);
                                        }
                                        gridCount++;
                                        u.putByte(b.tagsAddr + colliderCount, (byte) 1);
                                        colliderCount++;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        u.putDouble(b.headerAddr, box.minX);
        u.putDouble(b.headerAddr + 8, box.minY);
        u.putDouble(b.headerAddr + 16, box.minZ);
        u.putDouble(b.headerAddr + 24, box.maxX);
        u.putDouble(b.headerAddr + 32, box.maxY);
        u.putDouble(b.headerAddr + 40, box.maxZ);
        u.putDouble(b.headerAddr + 48, movement.x);
        u.putDouble(b.headerAddr + 56, movement.y);
        u.putDouble(b.headerAddr + 64, movement.z);
        u.putFloat(b.headerAddr + 72, step);
        u.putInt(b.headerAddr + 76, entity.onGround() ? 1 : 0);
        u.putInt(b.headerAddr + 80, solidCount);
        u.putInt(b.headerAddr + 84, gridCount);
        u.putInt(b.headerAddr + 88, colliderCount);
        int rc = gridCount == 0
                ? RustmiLib.oxi_collide(b.headerAddr, b.solidsAddr, b.outAddr)
                : RustmiLib.oxi_collide2(
                        b.headerAddr, b.solidsAddr, b.gridsAddr, b.tagsAddr, b.outAddr);
        if (rc != 0) {
            stats.bail(6);
            return null;
        }
        stats.fast++;
        return new Vec3(u.getDouble(b.outAddr), u.getDouble(b.outAddr + 8), u.getDouble(b.outAddr + 16));
    }

    public static void recordShadow(Vec3 vanilla, Vec3 fast, Entity entity, Vec3 movement) {
        // Raw bits: the result must be vanilla's exact vector, signed zeros included
        // (-0.0 inputs go to vanilla, see requiresVanillaPrecision).
        Stats stats = currentStats();
        boolean ok = Double.doubleToRawLongBits(vanilla.x) == Double.doubleToRawLongBits(fast.x)
                && Double.doubleToRawLongBits(vanilla.y) == Double.doubleToRawLongBits(fast.y)
                && Double.doubleToRawLongBits(vanilla.z) == Double.doubleToRawLongBits(fast.z);
        if (ok) {
            stats.shadowOk++;
        } else {
            stats.shadowBad++;
            if (EXAMPLES.size() < 5) {
                BlockPos below = entity.blockPosition().below();
                // Cached class at the failure site: differing from cls (live) means the
                // snapshot went stale — an invalidation leak, not a kernel bug.
                String cached = "?";
                try {
                    LevelChunk lc = entity.level().getChunkSource()
                            .getChunkNow(below.getX() >> 4, below.getZ() >> 4);
                    if (lc != null) {
                        long[] bits = ((CubeMirror) lc).oxi$bits((below.getY() - entity.level().getMinY()) >> 4);
                        if (bits != null) {
                            int cell = ((below.getY() & 15) << 8) | ((below.getZ() & 15) << 4) | (below.getX() & 15);
                            long bit = 1L << (cell & 63);
                            int word = cell >> 6;
                            cached = (bits[CubeMirror.FULL_OFF + word] & bit) != 0 ? "full"
                                    : (bits[CubeMirror.PARTIAL_OFF + word] & bit) != 0 ? "partial"
                                    : (bits[CubeMirror.BOXES_OFF + word] & bit) != 0 ? "boxes" : "empty";
                        }
                    }
                } catch (Throwable ignored) {
                }
                String example = entity.getType().toShortString() + "@" + entity.blockPosition().toShortString()
                        + " mv=" + movement + " van=" + vanilla + " rust=" + fast
                        + " under=" + entity.level().getBlockState(below)
                        + " cls=" + classify(entity.level().getBlockState(below))
                        + " cached=" + cached;
                EXAMPLES.add(example);
                LOGGER.error("[collision-shadow] VANILLA DIVERGENCE {}", example);
            }
        }
    }

    /** Collision-shadow comparison for {@link SupportingBlock} (same ok/bad counters). */
    public static void recordSupportShadow(java.util.Optional<BlockPos> vanilla, java.util.Optional<BlockPos> fast,
                                           Entity entity) {
        Stats stats = currentStats();
        if (vanilla.equals(fast)) {
            stats.shadowOk++;
        } else {
            stats.shadowBad++;
            if (EXAMPLES.size() < 5) {
                String example = "support " + entity.getType().toShortString() + "@" + entity.blockPosition().toShortString()
                        + " van=" + vanilla + " rust=" + fast;
                EXAMPLES.add(example);
                LOGGER.error("[collision-shadow] VANILLA DIVERGENCE {}", example);
            }
        }
    }

    /** Shadow result of a vanilla-predicate shortcut (same ok/bad counters). */
    public static void recordPredicateShadow(boolean ok, String example) {
        Stats stats = currentStats();
        if (ok) {
            stats.shadowOk++;
        } else {
            stats.shadowBad++;
            if (EXAMPLES.size() < 5) {
                EXAMPLES.add(example);
                LOGGER.error("[collision-shadow] VANILLA DIVERGENCE {}", example);
            }
        }
    }

    public static boolean isShadow() {
        return shadow;
    }

    public static boolean isEngine() {
        return engine;
    }

}
