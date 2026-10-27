package com.github.tatercertified.nalim.explosion;

import com.github.tatercertified.nalim.cramming.RustmiCramming;
import com.github.tatercertified.nalim.rust.RustmiLib;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.EntityBasedExplosionDamageCalculator;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.material.FluidState;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Explosion engine (docs/CODEMAP.md, "Explosions"): vanilla-exact explosions with
 * the heavy math in Rust.
 *
 * <ul>
 *   <li><b>E1 sweep</b> — {@code calculateExplodedPositions} replaced by the
 *       Rust ray kernel over a per-explosion resistance snapshot. RNG floats
 *       are drawn JAVA-side in vanilla's exact per-cell order (the world RNG
 *       state stays bit-identical for the shuffle and fire rolls after).
 *       The kernel returns positions in DISCOVERY ORDER; re-inserting them
 *       into a real {@code HashSet} reproduces vanilla's iteration (and
 *       therefore the shuffle input) without modelling hash internals.</li>
 *   <li><b>E2 seen-percent</b> — {@code getSeenPercent}'s ~45 raycasts per
 *       entity computed over the same snapshot when every voxel between the
 *       entity and the centre is air-or-full-cube (vanilla DDA + unit-cube
 *       clip ported verbatim); partial/context shapes → vanilla clip.</li>
 *   <li><b>Qualification, not hope</b>: takeover only for the exactly-vanilla
 *       damage calculators (default / entity-based with no entity overrides —
 *       reflection per class, cached). Wind charges, mods → vanilla.</li>
 *   <li><b>RNG commitment</b>: every fallible step happens BEFORE floats are
 *       drawn. After the draw we are committed — a kernel hiccup falls back
 *       to an exact Java port of the sweep using the same floats.</li>
 * </ul>
 *
 * Modes (persisted in config/rustmi/explosion-*.properties): {@code engine} (takeover,
 * default on), {@code shadow} (predict + compare, vanilla applies; turns the engine off),
 * {@code seenfast} (E2, default off). Any engine exception disables it for the session.
 */
public final class ExplosionEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger("Rustmi-Explosion");
    private static final Path DIR = Paths.get("config", "rustmi");

    private static final int RAYS = 1352;
    private static final int MAX_DIM = 64;
    private static final int OUT_CAP = 32768;
    private static final float MAX_RADIUS = 12.0f;

    /** Not persisted: a shadow session is a temporary measurement and never survives a
     *  restart (an old explosion-shadow file is ignored). */
    private static volatile boolean shadow = false;
    private static volatile boolean engine = loadPersisted("explosion-engine", true);
    /** Engine state to restore when the running shadow session ends; null = none pending
     *  (also cleared when the engine disabled itself after a failure). */
    private static Boolean engineAfterShadow;
    /** E2 seen-percent fast path — the DAMAGE path, so it stays OFF until its shadow
     *  divergence (117/7230 mismatches, 2026-07-02 storm) is root-caused to bit-exact.
     *  E1 (sweep) does not depend on it: engine mode with seenFast=false runs the
     *  native sweep + vanilla getSeenPercent. */
    private static volatile boolean seenFast = loadPersisted("explosion-seen-fast", false);

    public static boolean isSeenFast() { return seenFast; }

    public static synchronized void setSeenFast(boolean on) {
        seenFast = on;
        persist("explosion-seen-fast", on);
        LOGGER.info("[explosion-seen-fast] {}", on ? "ENABLED" : "DISABLED");
    }

    // ── stats ──
    private static final AtomicLong EXPLOSIONS      = new AtomicLong();
    private static final AtomicLong TAKEOVERS       = new AtomicLong();
    private static final AtomicLong JAVA_FALLBACKS  = new AtomicLong();
    private static final AtomicLong DISQUALIFIED    = new AtomicLong();
    private static final AtomicLong SHADOW_OK       = new AtomicLong();
    private static final AtomicLong SHADOW_BAD      = new AtomicLong();
    private static final AtomicLong SEEN_FAST       = new AtomicLong();
    private static final AtomicLong SEEN_VANILLA    = new AtomicLong();
    private static final AtomicLong SEEN_SHADOW_BAD = new AtomicLong();
    /** Content-equal, order-differs sweeps — the expected class under Lithium
     *  (its LongOpenHashSet iteration vs vanilla HashSet iteration). */
    private static final AtomicLong SHADOW_ORDER_ONLY = new AtomicLong();
    private static final ConcurrentLinkedQueue<String> EXAMPLES = new ConcurrentLinkedQueue<>();
    private static volatile String lastDisqualify = "";

    private ExplosionEngine() {}

    // ─────────────────────────────────────────────────────────────────────
    // Toggles
    // ─────────────────────────────────────────────────────────────────────

    public static boolean isShadow() { return shadow; }
    public static boolean isEngine() { return engine; }

    /** Shadow on turns the engine off for the session only; shadow off restores it. */
    public static synchronized void setShadow(boolean on) {
        if (on) {
            resetStats();
            if (engineAfterShadow == null) {
                engineAfterShadow = engine;
            }
            engine = false;
        } else if (engineAfterShadow != null) {
            engine = engineAfterShadow;
            engineAfterShadow = null;
        }
        shadow = on;
    }

    public static synchronized void setEngine(boolean on) {
        engineAfterShadow = null;
        if (on) {
            resetStats();
            shadow = false;
        }
        engine = on;
        persist("explosion-engine", on);
        LOGGER.info("[explosion-engine] {}", on ? "ENABLED" : "DISABLED");
    }

    private static void resetStats() {
        for (AtomicLong c : new AtomicLong[]{EXPLOSIONS, TAKEOVERS, JAVA_FALLBACKS, DISQUALIFIED,
                SHADOW_OK, SHADOW_BAD, SHADOW_ORDER_ONLY, SEEN_FAST, SEEN_VANILLA, SEEN_SHADOW_BAD}) {
            c.set(0);
        }
        EXAMPLES.clear();
        lastDisqualify = "";
    }

    public static String status() {
        StringBuilder sb = new StringBuilder(String.format(
                "shadow=%s engine=%s seenFast=%s | explosions=%d takeovers=%d javaFallback=%d disqualified=%d%s | " +
                "sweepShadow ok=%d orderOnly=%d bad=%d | seen fast=%d vanilla=%d shadowBad=%d",
                shadow, engine, seenFast,
                EXPLOSIONS.get(), TAKEOVERS.get(), JAVA_FALLBACKS.get(), DISQUALIFIED.get(),
                lastDisqualify.isEmpty() ? "" : " (last: " + lastDisqualify + ")",
                SHADOW_OK.get(), SHADOW_ORDER_ONLY.get(), SHADOW_BAD.get(),
                SEEN_FAST.get(), SEEN_VANILLA.get(), SEEN_SHADOW_BAD.get()));
        if (!EXAMPLES.isEmpty()) {
            sb.append(" | ").append(String.join("; ", EXAMPLES));
        }
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Per-thread context + buffers
    // ─────────────────────────────────────────────────────────────────────

    static final class Ctx {
        ServerLevel level;
        Vec3 center;
        float radius;
        ExplosionDamageCalculator calc;
        Entity source;
        /** The live ServerExplosion — source of Lithium's per-explosion skip-air byte. */
        Object explosion;
        /** Mirror of Lithium skipAirBlocks: 0 vanilla, 1 skip+count, 2 skip. */
        byte skipAir;
        boolean qualified;
        boolean snapBuilt;
        int ox, oy, oz, dx, dy, dz;
        /** Section grid of the packed snapshot (section coords / counts). */
        int secOx, secOy, secOz, secNx, secNy, secNz;
        long[] full;
        long[] partial;
        // shadow capture
        boolean capturing;
        int captureIdx;
        final float[] capture = new float[RAYS];
        boolean active;
    }

    // ── Lithium skip_air mirror ──────────────────────────────────────────
    // Lithium's block_raycast keeps air positions OUT of the destroy list
    // (shorter shuffle, no-op onExploded skipped) and, in state 1, banks the
    // distinct air cells in `explodedAirPositions` so its redirect can return
    // vanilla's position count from explode(). We read the live byte off the
    // explosion instance (set externally via lithium$setSkipAir before
    // explode()) instead of re-deriving the rule — fire gating etc. stays
    // Lithium's business. Fields absent (no Lithium) → offsets -1 → state 0.
    private static final long SKIP_AIR_OFFSET;
    private static final long AIR_SET_OFFSET;

    static {
        long s = -1L, a = -1L;
        try {
            var u = RustmiCramming.theUnsafe();
            var skip = net.minecraft.world.level.ServerExplosion.class.getDeclaredField("skipAirBlocks");
            var airs = net.minecraft.world.level.ServerExplosion.class.getDeclaredField("explodedAirPositions");
            // Unsafe accesses below assume these exact types; a renamed or retyped Lithium
            // field leaves both offsets at -1 (qualify then declines Lithium explosions).
            if (skip.getType() == byte.class && airs.getType().isAssignableFrom(LongOpenHashSet.class)) {
                s = u.objectFieldOffset(skip);
                a = u.objectFieldOffset(airs);
            }
        } catch (Throwable ignored) {
            // Lithium absent or restructured — mirror off, pure vanilla lists.
        }
        SKIP_AIR_OFFSET = s;
        AIR_SET_OFFSET = a;
    }

    /** The calculator's own entity: the sweep asks it, not the explosion's source. */
    private static final long CALC_SOURCE_OFFSET;

    static {
        long o = -1L;
        try {
            o = RustmiCramming.theUnsafe().objectFieldOffset(
                    EntityBasedExplosionDamageCalculator.class.getDeclaredField("source"));
        } catch (Throwable ignored) {
            // field renamed: entity-based calculators are declined
        }
        CALC_SOURCE_OFFSET = o;
    }

    private static byte readSkipAir(Ctx c) {
        return (SKIP_AIR_OFFSET >= 0 && c.explosion != null)
                ? RustmiCramming.theUnsafe().getByte(c.explosion, SKIP_AIR_OFFSET)
                : 0;
    }

    // ── PalettedContainer internals (read-only, server thread) ───────────
    // One private hop: PalettedContainer.data → record Data(configuration,
    // storage, palette). Data is a RECORD — sun.misc.Unsafe refuses field
    // offsets on records, so storage/palette go through the record's public
    // accessor METHODS (reflective, ~30 ns, twice per section). The values
    // are then used via their PUBLIC interfaces (BitStorage.getRaw/getBits,
    // Palette.valueFor/getSize).
    private static final long PC_DATA_OFFSET;
    private static final Method DATA_STORAGE_M;
    private static final Method DATA_PALETTE_M;
    private static final long LONG_BASE;

    static {
        long pc = -1L, lb = -1L;
        Method st = null, pa = null;
        try {
            var u = RustmiCramming.theUnsafe();
            pc = u.objectFieldOffset(
                    net.minecraft.world.level.chunk.PalettedContainer.class.getDeclaredField("data"));
            Class<?> dataCls = Class.forName("net.minecraft.world.level.chunk.PalettedContainer$Data");
            st = dataCls.getMethod("storage");
            pa = dataCls.getMethod("palette");
            st.setAccessible(true);   // Data is package-private; Knot unnamed module → permitted
            pa.setAccessible(true);
            lb = sun.misc.Unsafe.ARRAY_LONG_BASE_OFFSET;
        } catch (Throwable t) {
            LOGGER.warn("[explosion] PalettedContainer internals unavailable — dense per-cell fallback", t);
            pc = -1L;
            st = null;
            pa = null;
        }
        PC_DATA_OFFSET = pc;
        DATA_STORAGE_M = st;
        DATA_PALETTE_M = pa;
        LONG_BASE = lb;
    }

    /**
     * Apply the skip-air mirror to a discovery-ordered sweep result (air =
     * resistance sentinel -1 in the snapshot — exactly Lithium's
     * state.isAir(): air states never carry a fluid). In takeover state 1 the
     * distinct air cells are written into the explosion's
     * explodedAirPositions so Lithium's count redirect stays exact.
     */
    private static List<BlockPos> applySkipAir(Ctx c, Bufs b, List<BlockPos> discovery, boolean takeover) {
        if (c.skipAir == 0) {
            return discovery;
        }
        var u = RustmiCramming.theUnsafe();
        List<BlockPos> nonAir = new ArrayList<>(discovery.size());
        LongOpenHashSet airs = takeover && c.skipAir == 1 && AIR_SET_OFFSET >= 0 ? new LongOpenHashSet() : null;
        for (BlockPos p : discovery) {
            if (resAtPacked(c, b, p.getX(), p.getY(), p.getZ()) < 0.0f) {
                if (airs != null) {
                    airs.add(p.asLong());
                }
            } else {
                nonAir.add(p);
            }
        }
        if (airs != null) {
            u.putObject(c.explosion, AIR_SET_OFFSET, airs);
        }
        return nonAir;
    }

    private static final ThreadLocal<Ctx> CTX = ThreadLocal.withInitial(Ctx::new);

    /** Max sections per axis at MAX_DIM=64: 5 → 125 descriptors of 24 B. */
    private static final int SECTIONS_CAP_BYTES = 125 * 24;
    /** Worst section cost = dense 4096×f32 (16 KiB); 125 of them + slack. */
    private static final int BLOB_CAP = 125 * 4096 * 4 + 4096;

    private static final class Bufs {
        final ByteBuffer header = ByteBuffer.allocateDirect(128).order(ByteOrder.nativeOrder());
        final ByteBuffer floats = ByteBuffer.allocateDirect(RAYS * 4).order(ByteOrder.nativeOrder());
        final ByteBuffer sections = ByteBuffer.allocateDirect(SECTIONS_CAP_BYTES).order(ByteOrder.nativeOrder());
        final ByteBuffer blob = ByteBuffer.allocateDirect(BLOB_CAP).order(ByteOrder.nativeOrder());
        final ByteBuffer out = ByteBuffer.allocateDirect(OUT_CAP * 12).order(ByteOrder.nativeOrder());
        final long headerAddr = RustmiCramming.addressOf(header);
        final long floatsAddr = RustmiCramming.addressOf(floats);
        final long sectionsAddr = RustmiCramming.addressOf(sections);
        final long blobAddr = RustmiCramming.addressOf(blob);
        final long outAddr = RustmiCramming.addressOf(out);
    }

    private static final ThreadLocal<Bufs> BUFS = ThreadLocal.withInitial(Bufs::new);

    public static void enterExplosion(ServerLevel level, Vec3 center, float radius,
                                      ExplosionDamageCalculator calc, Entity source, Object explosion) {
        Ctx c = CTX.get();
        c.level = level;
        c.center = center;
        c.radius = radius;
        c.calc = calc;
        c.source = source;
        c.explosion = explosion;
        c.skipAir = 0;
        c.qualified = false;
        c.snapBuilt = false;
        c.capturing = false;
        c.active = true;
        EXPLOSIONS.incrementAndGet();
    }

    public static void exitExplosion() {
        Ctx c = CTX.get();
        c.active = false;
        c.level = null;
        c.calc = null;
        c.source = null;
        c.explosion = null;
        c.full = null;
        c.partial = null;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Qualification + caches
    // ─────────────────────────────────────────────────────────────────────

    private static final ConcurrentHashMap<Class<?>, Boolean> ENTITY_SAFE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Boolean> SHAPE_CTX = new ConcurrentHashMap<>();
    /** BlockState → packed (resistance f32 bits << 2 | shapeClass). */
    private static final ConcurrentHashMap<BlockState, Long> STATE_CACHE = new ConcurrentHashMap<>(4096);

    private static final int SHAPE_AIR = 0, SHAPE_FULL = 1, SHAPE_PARTIAL = 2;

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

    private static boolean entitySafe(Class<?> c) {
        return ENTITY_SAFE.computeIfAbsent(c, k ->
                !declaresBelow(k, Entity.class, "getBlockExplosionResistance")
                        && !declaresBelow(k, Entity.class, "shouldBlockExplode"));
    }

    private static long stateEntry(BlockState s) {
        Long cached = STATE_CACHE.get(s);
        if (cached != null) {
            return cached;
        }
        FluidState f = s.getFluidState();
        float res = s.isAir() && f.isEmpty() ? -1.0f
                : Math.max(s.getBlock().getExplosionResistance(), f.getExplosionResistance());
        int shape;
        if (s.isAir()) {
            shape = SHAPE_AIR;
        } else if (SHAPE_CTX.computeIfAbsent(s.getBlock().getClass(),
                k -> declaresBelow(k, BlockBehaviour.class, "getCollisionShape")
                        || declaresBelow(k, BlockBehaviour.class, "getShape"))) {
            shape = SHAPE_PARTIAL;   // position/context-capable — conservative
        } else {
            shape = s.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
                    ? SHAPE_FULL : SHAPE_PARTIAL;
        }
        long packed = ((long) Float.floatToRawIntBits(res) << 2) | shape;
        STATE_CACHE.put(s, packed);
        return packed;
    }

    private static boolean qualify(Ctx c) {
        if (c.level.isDebug()) {
            // Debug-world blocks exist only in getBlockState; the section snapshot sees air.
            lastDisqualify = "debug world";
            return false;
        }
        if (c.radius > MAX_RADIUS || !(c.radius > 0.0f)) {
            lastDisqualify = "radius=" + c.radius;
            return false;
        }
        if (LITHIUM_EXPLOSION != null && LITHIUM_EXPLOSION.isInstance(c.explosion) && SKIP_AIR_OFFSET < 0) {
            // Lithium handles this explosion but its skip-air fields could not be mirrored.
            lastDisqualify = "lithium mirror";
            return false;
        }
        Class<?> cc = c.calc.getClass();
        if (cc == ExplosionDamageCalculator.class) {
            return true;
        }
        if (cc == EntityBasedExplosionDamageCalculator.class) {
            Object calcSource = CALC_SOURCE_OFFSET < 0 ? null
                    : RustmiCramming.theUnsafe().getObject(c.calc, CALC_SOURCE_OFFSET);
            boolean ok = calcSource instanceof Entity e && entitySafe(e.getClass());
            if (!ok) lastDisqualify = "entity=" + (calcSource instanceof Entity e
                    ? e.getType().toShortString() : String.valueOf(calcSource));
            return ok;
        }
        lastDisqualify = "calculator=" + cc.getSimpleName();
        return false;
    }

    private static final int MODE_UNIFORM = 0, MODE_PACKED = 1, MODE_DENSE = 2;

    /**
     * Snapshot via palette walk — per overlapping chunk section we copy MC's
     * OWN storage form (palette resistances + raw BitStorage longs) into the
     * kernel blob at memcpy speed instead of ~dim³ getBlockState calls. The
     * seen-percent shape bitsets are built from the same walk (uniform
     * sections in bulk, mixed sections by bit-extract). Fallible — runs
     * BEFORE RNG.
     */
    private static boolean buildSnapshot(Ctx c) {
        int reachSteps = (int) Math.ceil(c.radius * 1.3f / 0.225f) + 2;
        double reach = reachSteps * 0.3 + 1.0;
        double entReach = c.radius * 2.0 + 3.0;
        int half = (int) Math.ceil(Math.max(reach, entReach)) + 1;
        int dim = half * 2 + 1;
        if (dim > MAX_DIM) {
            lastDisqualify = "dim=" + dim;
            return false;
        }
        c.ox = Mth.floor(c.center.x) - half;
        c.oy = Mth.floor(c.center.y) - half;
        c.oz = Mth.floor(c.center.z) - half;
        c.dx = dim;
        c.dy = dim;
        c.dz = dim;
        int cells = dim * dim * dim;
        c.full = new long[(cells + 63) / 64];
        c.partial = new long[(cells + 63) / 64];

        c.secOx = c.ox >> 4;
        c.secOy = c.oy >> 4;
        c.secOz = c.oz >> 4;
        c.secNx = ((c.ox + dim - 1) >> 4) - c.secOx + 1;
        c.secNy = ((c.oy + dim - 1) >> 4) - c.secOy + 1;
        c.secNz = ((c.oz + dim - 1) >> 4) - c.secOz + 1;

        Bufs b = BUFS.get();
        var u = RustmiCramming.theUnsafe();
        ChunkAccess[] cols = new ChunkAccess[c.secNx * c.secNz];
        int blobOff = 0;
        int di = 0;
        for (int sy = 0; sy < c.secNy; sy++) {
            for (int sz = 0; sz < c.secNz; sz++) {
                for (int sx = 0; sx < c.secNx; sx++, di++) {
                    ChunkAccess chunk = cols[sz * c.secNx + sx];
                    if (chunk == null) {
                        chunk = c.level.getChunk(c.secOx + sx, c.secOz + sz);
                        cols[sz * c.secNx + sx] = chunk;
                    }
                    int sIdx = (c.secOy + sy) - (chunk.getMinY() >> 4);
                    LevelChunkSection sec = sIdx >= 0 && sIdx < chunk.getSections().length
                            ? chunk.getSection(sIdx) : null;
                    blobOff = writeSection(c, b, u, b.sectionsAddr + (long) di * 24, sec, sx, sy, sz, blobOff);
                    if (blobOff < 0) {
                        lastDisqualify = "blob overflow";
                        return false;
                    }
                }
            }
        }
        c.snapBuilt = true;
        return true;
    }

    /** Writes one section descriptor (+blob payload +shape bits); returns the
     *  new blob offset or -1 on overflow. */
    private static int writeSection(Ctx c, Bufs b, sun.misc.Unsafe u, long desc,
                                    LevelChunkSection sec, int sx, int sy, int sz, int blobOff) {
        if (sec == null || sec.hasOnlyAir()) {
            return writeUniform(b, u, desc, blobOff, -1.0f);
        }
        Object data = PC_DATA_OFFSET >= 0 && DATA_STORAGE_M != null
                ? u.getObject(sec.getStates(), PC_DATA_OFFSET) : null;
        BitStorage storage = null;
        Palette<BlockState> pal = null;
        if (data != null) {
            try {
                storage = (BitStorage) DATA_STORAGE_M.invoke(data);
                @SuppressWarnings("unchecked")
                Palette<BlockState> p = (Palette<BlockState>) DATA_PALETTE_M.invoke(data);
                pal = p;
            } catch (ReflectiveOperationException ignored) {
                storage = null;
            }
        }
        if (storage == null || pal == null) {
            // reflection unavailable — correct-but-slow per-cell dense fill
            var states = sec.getStates();
            return writeDense(c, b, u, desc, sx, sy, sz, blobOff,
                    cell -> states.get(cell & 15, cell >>> 8, (cell >>> 4) & 15));
        }
        int bits = storage.getBits();
        if (bits == 0) {
            long e = stateEntry(pal.valueFor(0));
            int shape = (int) (e & 3);
            if (shape != SHAPE_AIR) {
                fillUniformShape(c, sx, sy, sz, shape);
            }
            return writeUniform(b, u, desc, blobOff, Float.intBitsToFloat((int) (e >>> 2)));
        }
        int palSize = pal.getSize();
        if (palSize > 256 || bits > 16) {
            // global/huge palette (very mixed section) — dense mini-grid
            final BitStorage fStorage = storage;
            final Palette<BlockState> fPal = pal;
            return writeDense(c, b, u, desc, sx, sy, sz, blobOff,
                    cell -> fPal.valueFor(fStorage.get(cell)));
        }
        float[] resTab = new float[palSize];
        byte[] shapeTab = new byte[palSize];
        boolean allAir = true;
        for (int i = 0; i < palSize; i++) {
            long e = stateEntry(pal.valueFor(i));
            resTab[i] = Float.intBitsToFloat((int) (e >>> 2));
            shapeTab[i] = (byte) (e & 3);
            allAir &= shapeTab[i] == SHAPE_AIR;
        }
        if (allAir) {
            return writeUniform(b, u, desc, blobOff, -1.0f);
        }
        long[] raw = storage.getRaw();
        int palBytes = palSize * 4, dataBytes = raw.length * 8;
        if (blobOff + palBytes + dataBytes > BLOB_CAP) {
            return -1;
        }
        int palOff = blobOff;
        for (int i = 0; i < palSize; i++) {
            u.putFloat(b.blobAddr + palOff + (long) i * 4, resTab[i]);
        }
        int dataOff = palOff + palBytes;
        u.copyMemory(raw, LONG_BASE, null, b.blobAddr + dataOff, dataBytes);
        u.putInt(desc, MODE_PACKED);
        u.putInt(desc + 4, bits);
        u.putInt(desc + 8, 64 / bits);
        u.putInt(desc + 12, palOff);
        u.putInt(desc + 16, dataOff);
        u.putInt(desc + 20, 0);

        // shape bitsets over box∩section by direct bit-extract from raw[]
        int vpl = 64 / bits;
        long mask = (1L << bits) - 1;
        int bx0 = (c.secOx + sx) << 4, by0 = (c.secOy + sy) << 4, bz0 = (c.secOz + sz) << 4;
        int x0 = Math.max(c.ox, bx0), x1 = Math.min(c.ox + c.dx - 1, bx0 + 15);
        int y0 = Math.max(c.oy, by0), y1 = Math.min(c.oy + c.dy - 1, by0 + 15);
        int z0 = Math.max(c.oz, bz0), z1 = Math.min(c.oz + c.dz - 1, bz0 + 15);
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    int cell = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
                    int li = cell / vpl;
                    int pi = (int) ((raw[li] >>> ((cell - li * vpl) * bits)) & mask);
                    int shape = shapeTab[pi];
                    if (shape != SHAPE_AIR) {
                        int idx = ((y - c.oy) * c.dz + (z - c.oz)) * c.dx + (x - c.ox);
                        (shape == SHAPE_FULL ? c.full : c.partial)[idx >> 6] |= 1L << (idx & 63);
                    }
                }
            }
        }
        return dataOff + dataBytes;
    }

    private static int writeUniform(Bufs b, sun.misc.Unsafe u, long desc, int blobOff, float res) {
        if (blobOff + 4 > BLOB_CAP) {
            return -1;
        }
        u.putFloat(b.blobAddr + blobOff, res);
        u.putInt(desc, MODE_UNIFORM);
        u.putInt(desc + 4, 0);
        u.putInt(desc + 8, 0);
        u.putInt(desc + 12, blobOff);
        u.putInt(desc + 16, 0);
        u.putInt(desc + 20, 0);
        return blobOff + 4;
    }

    /** Uniform non-air section: bulk-set the shape bit over box∩section. */
    private static void fillUniformShape(Ctx c, int sx, int sy, int sz, int shape) {
        long[] bits = shape == SHAPE_FULL ? c.full : c.partial;
        int bx0 = (c.secOx + sx) << 4, by0 = (c.secOy + sy) << 4, bz0 = (c.secOz + sz) << 4;
        int x0 = Math.max(c.ox, bx0), x1 = Math.min(c.ox + c.dx - 1, bx0 + 15);
        int y0 = Math.max(c.oy, by0), y1 = Math.min(c.oy + c.dy - 1, by0 + 15);
        int z0 = Math.max(c.oz, bz0), z1 = Math.min(c.oz + c.dz - 1, bz0 + 15);
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    int idx = ((y - c.oy) * c.dz + (z - c.oz)) * c.dx + (x - c.ox);
                    bits[idx >> 6] |= 1L << (idx & 63);
                }
            }
        }
    }

    /** Dense f32 mini-grid (4096 cells) — rare sections / reflection fallback. */
    private static int writeDense(Ctx c, Bufs b, sun.misc.Unsafe u, long desc,
                                  int sx, int sy, int sz, int blobOff,
                                  java.util.function.IntFunction<BlockState> cellState) {
        if (blobOff + 4096 * 4 > BLOB_CAP) {
            return -1;
        }
        int bx0 = (c.secOx + sx) << 4, by0 = (c.secOy + sy) << 4, bz0 = (c.secOz + sz) << 4;
        for (int cell = 0; cell < 4096; cell++) {
            long e = stateEntry(cellState.apply(cell));
            u.putFloat(b.blobAddr + blobOff + (long) cell * 4, Float.intBitsToFloat((int) (e >>> 2)));
            int shape = (int) (e & 3);
            if (shape != SHAPE_AIR) {
                int x = bx0 + (cell & 15), y = by0 + (cell >>> 8), z = bz0 + ((cell >>> 4) & 15);
                if (x >= c.ox && x < c.ox + c.dx && y >= c.oy && y < c.oy + c.dy
                        && z >= c.oz && z < c.oz + c.dz) {
                    int idx = ((y - c.oy) * c.dz + (z - c.oz)) * c.dx + (x - c.ox);
                    (shape == SHAPE_FULL ? c.full : c.partial)[idx >> 6] |= 1L << (idx & 63);
                }
            }
        }
        u.putInt(desc, MODE_DENSE);
        u.putInt(desc + 4, 0);
        u.putInt(desc + 8, 0);
        u.putInt(desc + 12, 0);
        u.putInt(desc + 16, blobOff);
        u.putInt(desc + 20, 0);
        return blobOff + 4096 * 4;
    }

    /** Java mirror of the kernel's packed cell read — fallback paths only. */
    private static float resAtPacked(Ctx c, Bufs b, int x, int y, int z) {
        var u = RustmiCramming.theUnsafe();
        int sx = (x >> 4) - c.secOx, sy = (y >> 4) - c.secOy, sz = (z >> 4) - c.secOz;
        long desc = b.sectionsAddr + (long) ((sy * c.secNz + sz) * c.secNx + sx) * 24;
        int mode = u.getInt(desc);
        if (mode == MODE_UNIFORM) {
            return u.getFloat(b.blobAddr + u.getInt(desc + 12));
        }
        int cell = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
        if (mode == MODE_DENSE) {
            return u.getFloat(b.blobAddr + u.getInt(desc + 16) + (long) cell * 4);
        }
        int bits = u.getInt(desc + 4), vpl = u.getInt(desc + 8);
        int li = cell / vpl;
        long w = u.getLong(b.blobAddr + u.getInt(desc + 16) + (long) li * 8);
        int idx = (int) ((w >>> ((cell - li * vpl) * bits)) & ((1L << bits) - 1));
        return u.getFloat(b.blobAddr + u.getInt(desc + 12) + (long) idx * 4);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Sweep takeover (engine) / shadow
    // ─────────────────────────────────────────────────────────────────────

    /** Engine path: null = run vanilla original. */
    public static List<BlockPos> trySweepTakeover() {
        if (!engine) {
            return null;
        }
        Ctx c = CTX.get();
        // NO isSameThread gate (removed 2026-07-02): under Fabrolia region threading TNT
        // explosions execute on region workers, and the old main-thread guard silently
        // KILLED the engine there (engine=true, takeovers=0 observed live). Whichever
        // thread is legally inside vanilla explode() may run the kernel instead — the
        // context, buffers and RNG are all per-thread (CTX/BUFS ThreadLocal; the floats
        // come from the SAME level.getRandom() the vanilla sweep on this thread would
        // draw from), and the snapshot reads exactly the blocks vanilla's rays read here.
        if (!c.active) {
            return null;
        }
        boolean drawn = false;
        try {
            c.qualified = qualify(c);
            if (!c.qualified || !buildSnapshot(c)) {
                DISQUALIFIED.incrementAndGet();
                return null;
            }
            c.skipAir = readSkipAir(c);
            // ── RNG commitment point: draw exactly like the vanilla loop ──
            Bufs b = BUFS.get();
            var u = RustmiCramming.theUnsafe();
            drawFloats(c, u, b.floatsAddr, null);
            drawn = true;
            int kernelSkip = c.skipAir != 0 ? 1 : 0;
            int count = runKernel(c, b, kernelSkip);
            TAKEOVERS.incrementAndGet();
            if (count >= 0 && kernelSkip == 1) {
                // hot path: kernel already split the streams
                List<BlockPos> nonAir = readTriples(u, b.outAddr, count);
                commitKernelAirs(c, b);
                return rebuildList(c, nonAir);
            }
            List<BlockPos> discovery;
            if (count >= 0) {
                discovery = readTriples(u, b.outAddr, count);
            } else {
                // committed — exact Java port with the same floats
                return committedJavaSweep(c);
            }
            return rebuildList(c, applySkipAir(c, b, discovery, true));
        } catch (Throwable t) {
            engine = false;
            LOGGER.error("[explosion-engine] takeover failed — engine disabled for this session", t);
            if (!drawn) {
                return null;
            }
            // The world RNG already advanced by the 1352 draws: running vanilla now would
            // draw them a second time. Finish with the exact Java port on the same floats.
            try {
                return committedJavaSweep(c);
            } catch (Throwable t2) {
                LOGGER.error("[explosion-engine] Java port failed after the RNG draws; vanilla redraws", t2);
                return null;
            }
        }
    }

    /** Post-commitment path: the exact Java port over the snapshot with the floats already drawn. */
    private static List<BlockPos> committedJavaSweep(Ctx c) {
        JAVA_FALLBACKS.incrementAndGet();
        Bufs b = BUFS.get();
        var u = RustmiCramming.theUnsafe();
        float[] floats = new float[RAYS];
        for (int i = 0; i < RAYS; i++) {
            floats[i] = u.getFloat(b.floatsAddr + (long) i * 4);
        }
        return rebuildList(c, applySkipAir(c, b, javaSweep(c, b, floats), true));
    }

    /** Shadow path step 1: arm the float capture before vanilla runs. */
    public static boolean armCapture() {
        if (!shadow) {
            return false;
        }
        Ctx c = CTX.get();
        // Same rule as trySweepTakeover: any thread legally inside explode() may
        // shadow it — everything is per-thread (see the note there).
        if (!c.active) {
            return false;
        }
        try {
            c.qualified = qualify(c);
            if (!c.qualified || !buildSnapshot(c)) {
                DISQUALIFIED.incrementAndGet();
                return false;
            }
            c.skipAir = readSkipAir(c);
            c.captureIdx = 0;
            c.capturing = true;
            return true;
        } catch (Throwable t) {
            shadow = false;
            LOGGER.error("[explosion-shadow] arm failed — shadow disabled", t);
            return false;
        }
    }

    public static void onFloatDrawn(float f) {
        Ctx c = CTX.get();
        if (c.capturing && c.captureIdx < RAYS) {
            c.capture[c.captureIdx++] = f;
        }
    }

    /** Shadow path step 2: vanilla finished — predict with captured floats and diff. */
    public static void finishCapture(List<BlockPos> vanilla) {
        Ctx c = CTX.get();
        if (!c.capturing) {
            return;
        }
        c.capturing = false;
        try {
            if (c.captureIdx != RAYS) {
                SHADOW_BAD.incrementAndGet();
                example("floatCount=" + c.captureIdx);
                return;
            }
            Bufs b = BUFS.get();
            var u = RustmiCramming.theUnsafe();
            for (int i = 0; i < RAYS; i++) {
                u.putFloat(b.floatsAddr + (long) i * 4, c.capture[i]);
            }
            int count = runKernel(c, b, c.skipAir != 0 ? 1 : 0);
            List<BlockPos> predicted = count >= 0
                    ? rebuildList(c, readTriples(u, b.outAddr, count))
                    : rebuildList(c, applySkipAir(c, b, javaSweep(c, b, c.capture), false));
            if (predicted.equals(vanilla)) {
                SHADOW_OK.incrementAndGet();
            } else if (predicted.size() == vanilla.size()
                    && new HashSet<>(predicted).equals(new HashSet<>(vanilla))) {
                // Same destroyed set, different order: the shuffle, drop and chain order
                // would differ from the server without Rustmi (rebuildList reproduces
                // vanilla's and Lithium's order), so the harness counts this as a failure.
                SHADOW_ORDER_ONLY.incrementAndGet();
            } else {
                SHADOW_BAD.incrementAndGet();
                example("sweep size " + predicted.size() + " vs " + vanilla.size()
                        + " skip=" + c.skipAir + firstDiff(predicted, vanilla));
            }
        } catch (Throwable t) {
            shadow = false;
            LOGGER.error("[explosion-shadow] compare failed — shadow disabled", t);
        }
    }

    private static String firstDiff(List<BlockPos> a, List<BlockPos> v) {
        int n = Math.min(a.size(), v.size());
        for (int i = 0; i < n; i++) {
            if (!a.get(i).equals(v.get(i))) {
                return " first@" + i + " sim=" + a.get(i).toShortString() + " van=" + v.get(i).toShortString();
            }
        }
        return " (prefix equal)";
    }

    private static void example(String s) {
        if (EXAMPLES.size() < 5) {
            EXAMPLES.add(s);
            LOGGER.warn("[explosion-shadow] mismatch: {}", s);
        }
    }

    /** Vanilla's per-cell RNG draws, in exact loop order. */
    private static void drawFloats(Ctx c, sun.misc.Unsafe u, long addr, float[] alsoInto) {
        var random = c.level.getRandom();
        int i = 0;
        for (int xx = 0; xx < 16; xx++) {
            for (int yy = 0; yy < 16; yy++) {
                for (int zz = 0; zz < 16; zz++) {
                    if (xx == 0 || xx == 15 || yy == 0 || yy == 15 || zz == 0 || zz == 15) {
                        float f = random.nextFloat();
                        u.putFloat(addr + (long) i * 4, f);
                        if (alsoInto != null) {
                            alsoInto[i] = f;
                        }
                        i++;
                    }
                }
            }
        }
    }

    /** @param skipAir 0 → single interleaved vanilla stream; 1 → non-air
     *                 forward + distinct air backwards from the out tail. */
    private static int runKernel(Ctx c, Bufs b, int skipAir) {
        var u = RustmiCramming.theUnsafe();
        u.putDouble(b.headerAddr, c.center.x);
        u.putDouble(b.headerAddr + 8, c.center.y);
        u.putDouble(b.headerAddr + 16, c.center.z);
        u.putFloat(b.headerAddr + 24, c.radius);
        u.putInt(b.headerAddr + 28, RAYS);
        u.putInt(b.headerAddr + 32, c.ox);
        u.putInt(b.headerAddr + 36, c.oy);
        u.putInt(b.headerAddr + 40, c.oz);
        u.putInt(b.headerAddr + 44, c.dx);
        u.putInt(b.headerAddr + 48, c.dy);
        u.putInt(b.headerAddr + 52, c.dz);
        u.putInt(b.headerAddr + 56, c.level.getMinY());
        u.putInt(b.headerAddr + 60, c.level.getMaxY() + 1);   // kernel takes exclusive
        u.putInt(b.headerAddr + 64, skipAir);
        u.putInt(b.headerAddr + 68, OUT_CAP);
        u.putInt(b.headerAddr + 72, c.secOx);
        u.putInt(b.headerAddr + 76, c.secOy);
        u.putInt(b.headerAddr + 80, c.secOz);
        u.putInt(b.headerAddr + 84, c.secNx);
        u.putInt(b.headerAddr + 88, c.secNy);
        u.putInt(b.headerAddr + 92, c.secNz);
        u.putInt(b.headerAddr + 96, 0);                       // airCount (kernel writes)
        return RustmiLib.oxi_explosion_sweep_packed(
                b.headerAddr, b.floatsAddr, b.sectionsAddr, b.blobAddr, b.outAddr);
    }

    /** Reads the kernel's backward air stream into the explosion's
     *  explodedAirPositions (Lithium state 1 — the count redirect needs it). */
    private static void commitKernelAirs(Ctx c, Bufs b) {
        if (c.skipAir != 1 || AIR_SET_OFFSET < 0) {
            return;
        }
        var u = RustmiCramming.theUnsafe();
        int airCount = u.getInt(b.headerAddr + 96);
        LongOpenHashSet airs = new LongOpenHashSet(Math.max(airCount, 4));
        for (int i = 0; i < airCount; i++) {
            long base = b.outAddr + (long) (OUT_CAP - 1 - i) * 12;
            airs.add(BlockPos.asLong(u.getInt(base), u.getInt(base + 4), u.getInt(base + 8)));
        }
        u.putObject(c.explosion, AIR_SET_OFFSET, airs);
    }

    private static List<BlockPos> readTriples(sun.misc.Unsafe u, long addr, int count) {
        List<BlockPos> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long base = addr + (long) i * 12;
            out.add(new BlockPos(u.getInt(base), u.getInt(base + 4), u.getInt(base + 8)));
        }
        return out;
    }

    /** Implemented by ServerExplosion when Lithium's block_raycast mixin is applied; null
     *  without Lithium. */
    private static final Class<?> LITHIUM_EXPLOSION = lithiumExplosionClass();

    private static Class<?> lithiumExplosionClass() {
        try {
            return Class.forName("net.caffeinemc.mods.lithium.common.explosion.LithiumExplosion",
                    false, ExplosionEngine.class.getClassLoader());
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The list the server without Rustmi would produce. Lithium's block_raycast replaces
     * vanilla's HashSet with {@code new LongOpenHashSet(0)}, adds the positions in the same
     * discovery order and returns them in that set's iteration order (Lithium 0.26.2
     * ServerExplosionMixin.collectBlocks); vanilla iterates a HashSet&lt;BlockPos&gt;.
     */
    private static List<BlockPos> rebuildList(Ctx c, List<BlockPos> discovery) {
        if (LITHIUM_EXPLOSION == null || !LITHIUM_EXPLOSION.isInstance(c.explosion)) {
            return rebuildVanillaList(discovery);
        }
        LongOpenHashSet set = new LongOpenHashSet(0);
        for (BlockPos p : discovery) {
            set.add(p.asLong());
        }
        List<BlockPos> out = new ObjectArrayList<>(set.size());
        for (LongIterator it = set.iterator(); it.hasNext(); ) {
            out.add(BlockPos.of(it.nextLong()));
        }
        return out;
    }

    /** Vanilla's HashSet construction, reproduced with the discovery order. */
    private static List<BlockPos> rebuildVanillaList(List<BlockPos> discovery) {
        Set<BlockPos> set = new HashSet<>();
        for (BlockPos p : discovery) {
            set.add(p);
        }
        return new ObjectArrayList<>(set);
    }

    /**
     * Exact Java port of the vanilla sweep over the SNAPSHOT (same data the
     * kernel saw) with pre-drawn floats — the post-commitment emergency path.
     */
    private static List<BlockPos> javaSweep(Ctx c, Bufs b, float[] floats) {
        var u = RustmiCramming.theUnsafe();
        List<BlockPos> discovery = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        int minY = c.level.getMinY(), maxYExcl = c.level.getMaxY() + 1;
        int fi = 0;
        for (int xx = 0; xx < 16; xx++) {
            for (int yy = 0; yy < 16; yy++) {
                for (int zz = 0; zz < 16; zz++) {
                    if (xx != 0 && xx != 15 && yy != 0 && yy != 15 && zz != 0 && zz != 15) {
                        continue;
                    }
                    double xd = xx / 15.0F * 2.0F - 1.0F;
                    double yd = yy / 15.0F * 2.0F - 1.0F;
                    double zd = zz / 15.0F * 2.0F - 1.0F;
                    double d = Math.sqrt(xd * xd + yd * yd + zd * zd);
                    xd /= d;
                    yd /= d;
                    zd /= d;
                    float remaining = c.radius * (0.7F + floats[fi++] * 0.6F);
                    double xp = c.center.x, yp = c.center.y, zp = c.center.z;
                    for (; remaining > 0.0F; remaining -= 0.22500001F) {
                        int bx = Mth.floor(xp), by = Mth.floor(yp), bz = Mth.floor(zp);
                        if (by < minY || by >= maxYExcl
                                || bx < -30_000_000 || bx >= 30_000_000
                                || bz < -30_000_000 || bz >= 30_000_000) {
                            break;
                        }
                        int lx = bx - c.ox, ly = by - c.oy, lz = bz - c.oz;
                        if (lx < 0 || ly < 0 || lz < 0 || lx >= c.dx || ly >= c.dy || lz >= c.dz) {
                            break;   // should not happen; degrade gracefully
                        }
                        float res = resAtPacked(c, b, bx, by, bz);
                        if (res >= 0.0f) {
                            remaining -= (res + 0.3F) * 0.3F;
                        }
                        if (remaining > 0.0F) {
                            BlockPos p = new BlockPos(bx, by, bz);
                            if (seen.add(p)) {
                                discovery.add(p);
                            }
                        }
                        xp += xd * 0.3F;
                        yp += yd * 0.3F;
                        zp += zd * 0.3F;
                    }
                }
            }
        }
        return discovery;
    }

    // ─────────────────────────────────────────────────────────────────────
    // E2: seen percent
    // ─────────────────────────────────────────────────────────────────────

    /** Wrap entry for getSeenPercent: fast path / shadow / vanilla. */
    public static float seenPercent(Vec3 center, Entity entity, Operation<Float> original) {
        if (!engine && !shadow) {
            return original.call(center, entity);
        }
        Ctx c = CTX.get();
        if (!c.active || !c.snapBuilt || entity.level() != c.level) {
            return original.call(center, entity);
        }
        try {
            AABB bb = entity.getBoundingBox();
            if (!subBoxSimple(c, bb, center)) {
                SEEN_VANILLA.incrementAndGet();
                return original.call(center, entity);
            }
            // E2 is DAMAGE-observable and not yet bit-certified: only return the fast
            // value when explicitly enabled. Shadow mode still computes + diffs it for
            // certification data; engine-without-seenFast pays vanilla here (E1 sweep
            // is independent and stays native).
            if (engine && seenFast) {
                float fast = computeSeen(c, center, bb);
                SEEN_FAST.incrementAndGet();
                return fast;
            }
            if (!shadow) {
                SEEN_VANILLA.incrementAndGet();
                return original.call(center, entity);
            }
            float fast = computeSeen(c, center, bb);
            SEEN_FAST.incrementAndGet();
            float vanilla = original.call(center, entity);
            if (fast != vanilla) {
                SEEN_SHADOW_BAD.incrementAndGet();
                example("seen " + fast + " vs " + vanilla + " @" + entity.getType().toShortString());
            }
            return vanilla;
        } catch (Throwable t) {
            engine = false;
            shadow = false;
            engineAfterShadow = null;   // a failed engine must not come back with "shadow off"
            LOGGER.error("[explosion] seenPercent failed — engine+shadow disabled", t);
            return original.call(center, entity);
        }
    }

    /** All voxels in (entity box ∪ centre) padded by 1 are air or full cube. */
    private static boolean subBoxSimple(Ctx c, AABB bb, Vec3 center) {
        int x0 = Mth.floor(Math.min(bb.minX, center.x)) - 1, x1 = Mth.floor(Math.max(bb.maxX, center.x)) + 1;
        int y0 = Mth.floor(Math.min(bb.minY, center.y)) - 1, y1 = Mth.floor(Math.max(bb.maxY, center.y)) + 1;
        int z0 = Mth.floor(Math.min(bb.minZ, center.z)) - 1, z1 = Mth.floor(Math.max(bb.maxZ, center.z)) + 1;
        if (x0 < c.ox || y0 < c.oy || z0 < c.oz
                || x1 >= c.ox + c.dx || y1 >= c.oy + c.dy || z1 >= c.oz + c.dz) {
            return false;   // pokes outside the snapshot
        }
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    int idx = ((y - c.oy) * c.dz + (z - c.oz)) * c.dx + (x - c.ox);
                    if ((c.partial[idx >> 6] & (1L << (idx & 63))) != 0) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Exact port of ServerExplosion.getSeenPercent over the snapshot. */
    static float computeSeen(Ctx c, Vec3 center, AABB bb) {
        double xs = 1.0 / ((bb.maxX - bb.minX) * 2.0 + 1.0);
        double ys = 1.0 / ((bb.maxY - bb.minY) * 2.0 + 1.0);
        double zs = 1.0 / ((bb.maxZ - bb.minZ) * 2.0 + 1.0);
        double xOffset = (1.0 - Math.floor(1.0 / xs) * xs) / 2.0;
        double zOffset = (1.0 - Math.floor(1.0 / zs) * zs) / 2.0;
        if (xs < 0.0 || ys < 0.0 || zs < 0.0) {
            return 0.0F;
        }
        int hits = 0;
        int count = 0;
        for (double xx = 0.0; xx <= 1.0; xx += xs) {
            for (double yy = 0.0; yy <= 1.0; yy += ys) {
                for (double zz = 0.0; zz <= 1.0; zz += zs) {
                    double x = Mth.lerp(xx, bb.minX, bb.maxX);
                    double y = Mth.lerp(yy, bb.minY, bb.maxY);
                    double z = Mth.lerp(zz, bb.minZ, bb.maxZ);
                    if (!ddaHits(c, x + xOffset, y, z + zOffset, center.x, center.y, center.z)) {
                        hits++;
                    }
                    count++;
                }
            }
        }
        return (float) hits / count;
    }

    /**
     * Vanilla BlockGetter traversal over the snapshot. Its epsilon-expanded
     * endpoints are only for voxel enumeration; each visited collision shape
     * clips the original segment.
     */
    static boolean ddaHits(Ctx c, double sx, double sy, double sz, double ex, double ey, double ez) {
        if (sx == ex && sy == ey && sz == ez) {
            return false;
        }
        double rayDx = ex - sx;
        double rayDy = ey - sy;
        double rayDz = ez - sz;
        if (rayDx * rayDx + rayDy * rayDy + rayDz * rayDz < 1.0E-7) {
            return false;   // every full-cube VoxelShape.clip rejects this segment
        }
        double probeX = sx + rayDx * 0.001;
        double probeY = sy + rayDy * 0.001;
        double probeZ = sz + rayDz * 0.001;

        double toX = Mth.lerp(-1.0E-7, ex, sx);
        double toY = Mth.lerp(-1.0E-7, ey, sy);
        double toZ = Mth.lerp(-1.0E-7, ez, sz);
        double fromX = Mth.lerp(-1.0E-7, sx, ex);
        double fromY = Mth.lerp(-1.0E-7, sy, ey);
        double fromZ = Mth.lerp(-1.0E-7, sz, ez);
        int bx = Mth.floor(fromX);
        int by = Mth.floor(fromY);
        int bz = Mth.floor(fromZ);
        if (voxelClips(c, bx, by, bz, sx, sy, sz, rayDx, rayDy, rayDz,
                probeX, probeY, probeZ)) {
            return true;
        }
        double traverseDx = toX - fromX;
        double traverseDy = toY - fromY;
        double traverseDz = toZ - fromZ;
        int signX = Mth.sign(traverseDx);
        int signY = Mth.sign(traverseDy);
        int signZ = Mth.sign(traverseDz);
        double tDeltaX = signX == 0 ? Double.MAX_VALUE : signX / traverseDx;
        double tDeltaY = signY == 0 ? Double.MAX_VALUE : signY / traverseDy;
        double tDeltaZ = signZ == 0 ? Double.MAX_VALUE : signZ / traverseDz;
        double tX = tDeltaX * (signX > 0 ? 1.0 - Mth.frac(fromX) : Mth.frac(fromX));
        double tY = tDeltaY * (signY > 0 ? 1.0 - Mth.frac(fromY) : Mth.frac(fromY));
        double tZ = tDeltaZ * (signZ > 0 ? 1.0 - Mth.frac(fromZ) : Mth.frac(fromZ));
        while (tX <= 1.0 || tY <= 1.0 || tZ <= 1.0) {
            if (tX < tY) {
                if (tX < tZ) {
                    bx += signX;
                    tX += tDeltaX;
                } else {
                    bz += signZ;
                    tZ += tDeltaZ;
                }
            } else if (tY < tZ) {
                by += signY;
                tY += tDeltaY;
            } else {
                bz += signZ;
                tZ += tDeltaZ;
            }
            if (voxelClips(c, bx, by, bz, sx, sy, sz, rayDx, rayDy, rayDz,
                    probeX, probeY, probeZ)) {
                return true;
            }
        }
        return false;
    }

    /** FULL voxel + the exact full-cube VoxelShape.clip hit predicate. */
    private static boolean voxelClips(Ctx c, int bx, int by, int bz,
                                      double sx, double sy, double sz,
                                      double dx, double dy, double dz,
                                      double probeX, double probeY, double probeZ) {
        int lx = bx - c.ox, ly = by - c.oy, lz = bz - c.oz;
        if (lx < 0 || ly < 0 || lz < 0 || lx >= c.dx || ly >= c.dy || lz >= c.dz) {
            return false;   // outside snapshot — qualification already bounded the rays
        }
        int idx = (ly * c.dz + lz) * c.dx + lx;
        if ((c.full[idx >> 6] & (1L << (idx & 63))) == 0) {
            return false;
        }
        double minX = bx, minY = by, minZ = bz;
        double maxX = bx + 1.0, maxY = by + 1.0, maxZ = bz + 1.0;
        if (probeX >= minX && probeX < maxX
                && probeY >= minY && probeY < maxY
                && probeZ >= minZ && probeZ < maxZ) {
            return true;
        }

        double best = 1.0;
        boolean hit = false;
        if (dx > 1.0E-7) {
            double d = (minX - sx) / dx;
            if (clipFaceOk(d, best, sy + d * dy, minY, maxY, sz + d * dz, minZ, maxZ)) { best = d; hit = true; }
        } else if (dx < -1.0E-7) {
            double d = (maxX - sx) / dx;
            if (clipFaceOk(d, best, sy + d * dy, minY, maxY, sz + d * dz, minZ, maxZ)) { best = d; hit = true; }
        }
        if (dy > 1.0E-7) {
            double d = (minY - sy) / dy;
            if (clipFaceOk(d, best, sz + d * dz, minZ, maxZ, sx + d * dx, minX, maxX)) { best = d; hit = true; }
        } else if (dy < -1.0E-7) {
            double d = (maxY - sy) / dy;
            if (clipFaceOk(d, best, sz + d * dz, minZ, maxZ, sx + d * dx, minX, maxX)) { best = d; hit = true; }
        }
        if (dz > 1.0E-7) {
            double d = (minZ - sz) / dz;
            if (clipFaceOk(d, best, sx + d * dx, minX, maxX, sy + d * dy, minY, maxY)) { hit = true; }
        } else if (dz < -1.0E-7) {
            double d = (maxZ - sz) / dz;
            if (clipFaceOk(d, best, sx + d * dx, minX, maxX, sy + d * dy, minY, maxY)) { hit = true; }
        }
        return hit;
    }

    private static boolean clipFaceOk(double d, double best, double b, double minB, double maxB,
                                      double cc, double minC, double maxC) {
        return d > 0.0 && d < best
                && b > minB - 1.0E-7 && b < maxB + 1.0E-7
                && cc > minC - 1.0E-7 && cc < maxC + 1.0E-7;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Persistence
    // ─────────────────────────────────────────────────────────────────────

    /** Optimizations default ON for fresh installs (user directive 2026-06-12);
     *  the persisted file, once written by a toggle command, always wins. */
    private static boolean loadPersisted(String name, boolean def) {
        try {
            Path f = DIR.resolve(name + ".properties");
            if (Files.exists(f)) {
                for (String line : Files.readAllLines(f)) {
                    String t = line.trim();
                    if (t.startsWith("enabled=")) {
                        return Boolean.parseBoolean(t.substring("enabled=".length()));
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return def;
    }

    private static void persist(String name, boolean on) {
        try {
            Files.createDirectories(DIR);
            Files.write(DIR.resolve(name + ".properties"),
                    ("# Rustmi " + name + "\nenabled=" + on + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            LOGGER.warn("could not persist {}", name, e);
        }
    }
}
