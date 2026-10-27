package com.github.tatercertified.nalim.cramming;
import com.github.tatercertified.nalim.rust.RustmiLib;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Wires the cramming pre-phase into the server tick — driven by
 * {@code MinecraftServerTickMixin}, not Fabric API (Rustmi does not depend on it).
 *
 * <p>Fabrolia compatibility:
 * <ul>
 *   <li>{@link #onTickStart} fires from a mixin {@code HEAD} inject on
 *       {@code MinecraftServer.tickServer}. At that point all entity
 *       managers are quiescent — no concurrent mutators.</li>
 *   <li>Collection across dimensions uses Fabrolia's
 *       {@code ParallelProcessor.executor} via reflection so this jar
 *       does NOT depend on the fabrolia-fabric jar at compile time.</li>
 *   <li>{@code ParallelProcessor.pumpUntilDone} (same reflection) drains
 *       the collect futures with the chunk-system-aware barrier Fabrolia
 *       already exposes for despawn and entity-tick fan-out.</li>
 *   <li>If Fabrolia is not present, fall back to sequential collection on
 *       the server thread — still correct, just slower.</li>
 * </ul>
 */
public final class CrammingHooks {

    private static final Logger LOGGER = LoggerFactory.getLogger("Rustmi-Cramming");

    /** Set false if a conflicting mod (Sepals cramming) is detected at init. */
    private static volatile boolean enabled = true;

    /**
     * Runtime master switch (/rustmi cramming on|off) — the live A/B
     * bisect lever: off = the mixin falls through to vanilla pushEntities
     * everywhere and the pre-tick collect is skipped entirely.
     */
    private static volatile boolean runtimeEnabled = loadRuntimeEnabled();

    private static boolean loadRuntimeEnabled() {
        try {
            java.nio.file.Path f = com.github.tatercertified.nalim.RustmiPaths.config("rustmi", "cramming.properties");
            if (java.nio.file.Files.exists(f)) {
                for (String line : java.nio.file.Files.readAllLines(f)) {
                    String t = line.trim();
                    if (t.startsWith("enabled=")) {
                        return Boolean.parseBoolean(t.substring("enabled=".length()));
                    }
                }
            }
        } catch (java.io.IOException ignored) {
        }
        return true;   // default ON — the shipped behaviour
    }

    /** Runtime toggle AND not yielded to Sepals' cramming (which sets {@code enabled=false}). */
    public static boolean isCrammingEnabled() {
        return enabled && runtimeEnabled;
    }

    public static synchronized void setCrammingEnabled(boolean on) {
        runtimeEnabled = on;
        try {
            java.nio.file.Path f = com.github.tatercertified.nalim.RustmiPaths.config("rustmi", "cramming.properties");
            java.nio.file.Files.createDirectories(f.getParent());
            java.nio.file.Files.write(f, ("# Rustmi cramming master switch\nenabled=" + on + "\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            LOGGER.warn("could not persist cramming switch", e);
        }
        LOGGER.info("[cramming] runtime switch {}", on ? "ON" : "OFF");
    }
    private static volatile boolean registered = false;

    // Reflection handles for Fabrolia's ParallelProcessor — resolved once at
    // first tick. Null if Fabrolia is absent.
    private static volatile ExecutorService fabroliaExecutor;
    private static volatile Method pumpUntilDone;

    private CrammingHooks() {}

    public static synchronized void register() {
        if (registered) return;

        int nativeInputStride = RustmiLib.oxi_cramming_input_size();
        int nativeOutputStride = RustmiLib.oxi_cramming_output_size();
        double nativeCrammingPad = RustmiLib.oxi_cramming_pad();
        double nativeSensorPad = RustmiLib.oxi_sensor_pad();
        if (nativeInputStride != RustmiCramming.INPUT_STRIDE
                || nativeOutputStride != RustmiCramming.OUTPUT_STRIDE
                || nativeCrammingPad != RegionalCrammingGeometry.SNAPSHOT_PAD
                || nativeSensorPad != RegionalCrammingGeometry.SENSOR_PAD) {
            throw new IllegalStateException(
                    "RustMI cramming ABI mismatch: Java expects input/output/pads "
                            + RustmiCramming.INPUT_STRIDE + "/" + RustmiCramming.OUTPUT_STRIDE
                            + "/" + RegionalCrammingGeometry.SNAPSHOT_PAD
                            + "/" + RegionalCrammingGeometry.SENSOR_PAD
                            + ", native library reports "
                            + nativeInputStride + "/" + nativeOutputStride
                            + "/" + nativeCrammingPad + "/" + nativeSensorPad);
        }

        registered = true;
        // Conflict detection: upstream Sepals builds may carry their own
        // cramming mixin behind the enableSepalsEntitiesCramming flag. The
        // local Sepals fork has no cramming module at all, so a missing flag
        // (or missing file) means we proceed. Read the config JSON directly —
        // checking "mod is loaded" produced a false positive.
        try {
            java.nio.file.Path cfg = com.github.tatercertified.nalim.RustmiPaths.config("sepals.json");
            if (java.nio.file.Files.exists(cfg)) {
                String json = java.nio.file.Files.readString(cfg);
                int idx = json.indexOf("\"enableSepalsEntitiesCramming\"");
                if (idx >= 0) {
                    int colon = json.indexOf(':', idx);
                    int comma = json.indexOf(',', colon);
                    if (comma < 0) comma = json.indexOf('}', colon);
                    String val = json.substring(colon + 1, comma).trim();
                    if (val.equalsIgnoreCase("true")) {
                        LOGGER.info("Sepals enableSepalsEntitiesCramming=true detected — " +
                                    "Rustmi cramming yields. Set the Sepals flag to false " +
                                    "in config/sepals.json to use Rustmi cramming instead.");
                        enabled = false;
                        return;
                    }
                }
            }
        } catch (Throwable e) {
            LOGGER.warn("Could not inspect Sepals config; proceeding with Rustmi cramming enabled", e);
        }

        // NOTE: do NOT bind ParallelProcessor here. We are invoked from a
        // mixin-plugin onLoad path — Class.forName on Fabrolia's
        // ParallelProcessor would trigger its <clinit>, which loads
        // Entity.class and crashes mixin PREPARE ("target was loaded too
        // early"). Bind lazily on the first tick instead.

        LOGGER.info("Rustmi cramming pre-phase armed (Fabrolia bind deferred until first tick).");
    }

    private static volatile boolean fabroliaBindAttempted = false;

    /**
     * True after Fabrolia's region scheduler classes were successfully linked.
     * Gate for every touch of {@link RegionalCramming} — that class references
     * Fabrolia types directly and must not be class-loaded without them.
     */
    private static volatile boolean regionsLinked = false;

    /** Lazy first-tick bind of Fabrolia's ParallelProcessor. Idempotent. */
    private static void tryBindFabrolia() {
        if (fabroliaBindAttempted) return;
        fabroliaBindAttempted = true;
        try {
            Class<?> pp = Class.forName("com.axalotl.fabrolia.common.ParallelProcessor");
            fabroliaExecutor = (ExecutorService) pp.getField("executor").get(null);
            pumpUntilDone = pp.getMethod("pumpUntilDone", CompletableFuture.class);
            LOGGER.info("Fabrolia detected — cramming collect will use ParallelProcessor.executor.");
        } catch (ReflectiveOperationException e) {
            fabroliaExecutor = null;
            pumpUntilDone = null;
            LOGGER.info("Fabrolia not present — cramming collect runs sequentially.");
        }
        try {
            Class.forName("com.axalotl.fabrolia.common.regions.RegionTickHooks");
            RegionalCramming.register();   // safe: only executed when the probe succeeded
            regionsLinked = true;
            LOGGER.info("Fabrolia region scheduler API detected — regional cramming hook registered " +
                        "(per-region batches whenever /async regions is active).");
        } catch (ClassNotFoundException e) {
            LOGGER.info("Fabrolia RegionTickHooks not present (old Fabrolia or absent) — global batch only.");
        } catch (LinkageError e) {
            // Fabrolia changed an API Rustmi was compiled against (libs/fabrolia-compile.jar).
            regionsLinked = false;
            LOGGER.error("Fabrolia's region API differs from the one Rustmi was built against — regional "
                    + "cramming disabled, global batch only. Rebuild Rustmi against this Fabrolia.", e);
        }
    }

    /**
     * Batch the current entity tick should consult: the per-region batch when
     * this thread is a region worker holding a claim, otherwise the global one
     * (whose origin map is simply empty in region mode → vanilla fallback for
     * main-sync entities, exactly what we want).
     */
    public static CandidateBatch batchForCurrentThread() {
        if (regionsLinked) {
            CandidateBatch regional = RegionalCramming.currentThreadBatch();
            if (regional != null) {
                return regional;
            }
        }
        return RustmiCramming.get();
    }

    /**
     * Called from {@code MinecraftServerTickMixin} at HEAD of
     * {@code tickServer}, before any level ticks.
     */
    public static void onTickStart(MinecraftServer server) {
        CrammingEscapes.beginTick(enabled && runtimeEnabled);
        if (!enabled || !runtimeEnabled) return;
        try {
            tryBindFabrolia();
            // Region mode owns cramming: per-region batches are collected by the
            // region workers themselves (RegionalCramming via RegionTickHooks).
            // The global pre-phase would be pure waste — skip it. Checked every
            // tick because /async regions is runtime-toggleable.
            if (regionsLinked && RegionalCramming.regionsActive()) {
                return;
            }
        } catch (Throwable t) {
            // This runs at the head of tickServer: a Fabrolia API that differs at runtime
            // must not take the server down. Fall back to the global, non-regional path.
            LOGGER.error("Fabrolia probe failed at tick start; regional cramming disabled", t);
            regionsLinked = false;
            fabroliaExecutor = null;
            pumpUntilDone = null;
            try {
                RegionalCramming.disable();
            } catch (Throwable ignored) {
                // RegionalCramming itself does not link: its hook can never have run
            }
        }
        try {
            RustmiCramming c = RustmiCramming.get();
            c.beginCollect();

            List<ServerLevel> levels = new ArrayList<>();
            server.getAllLevels().forEach(levels::add);
            if (levels.isEmpty()) return;

            if (fabroliaExecutor != null && pumpUntilDone != null) {
                CompletableFuture<?>[] futs = new CompletableFuture<?>[levels.size()];
                for (int i = 0; i < levels.size(); i++) {
                    final ServerLevel lvl = levels.get(i);
                    futs[i] = CompletableFuture.runAsync(() -> collectFromLevel(lvl, c),
                            fabroliaExecutor);
                }
                pumpUntilDone.invoke(null, CompletableFuture.allOf(futs));
            } else {
                for (ServerLevel level : levels) {
                    collectFromLevel(level, c);
                }
            }

            // Single Nalim call into the Rust kernel — fills per-origin
            // candidate lists. Happens-before edge to the subsequent
            // entity-tick fan-out is established by either Fabrolia's
            // pumpUntilDone barrier above, or by the natural happens-before
            // of the server thread issuing both writes.
            // Rows past capacity were snapshotted but have no slot: they would be invisible
            // candidates for every origin. Run this tick fully vanilla; endTick grows.
            if (c.overCapacity()) {
                c.dropOrigins();
                return;   // nothing to compute: every query of this tick runs vanilla
            }
            c.runBatch();
            c.retainForQueries();
        } catch (Throwable t) {
            LOGGER.error("Cramming pre-phase failed; vanilla fallback this tick", t);
            try { RustmiCramming.get().endTick(); } catch (Throwable ignored) {}
        }
    }

    /** Called from {@code MinecraftServerTickMixin} at RETURN of tickServer. */
    public static void onTickEnd(MinecraftServer server) {
        if (!enabled) return;
        try {
            RustmiCramming.get().endTick();
        } catch (Throwable t) {
            LOGGER.error("Cramming end-of-tick cleanup failed", t);
        }
    }

    /**
     * Snapshot every collision-relevant entity of one level into the batch.
     * <p>
     * Two roles:
     * <ul>
     *   <li><b>Origins</b> — entities that will run a batch-cancelled
     *       {@code pushEntities} this tick: living, alive, not a passenger,
     *       not a spectator, AND inside entity-ticking range. They get a
     *       candidate list from the kernel.</li>
     *   <li><b>Colliders</b> — snapshot-pushable non-living entities plus
     *       every alive living entity, a conservative candidate superset.
     *       Vanilla's live predicate (called by the mixin) rejects extras and
     *       applies teams, sleep and every other rule before any effect.</li>
     * </ul>
     * The kernel's padded intersection only has to be a SUPERSET of the live
     * neighbour set — the mixin re-checks everything against live state.
     */
    /**
     * The level's visible entities in vanilla {@code EntitySectionStorage} order — section keys
     * ascending (the signed-long order vanilla's per-x subSet walk produces), then each
     * section's insertion order. Slot order then equals vanilla iteration order for the tick,
     * so the kernel's ascending candidate slots need no re-sort. Null if the storage layout is
     * not vanilla's (the caller then collects the unordered {@code getAllEntities()}).
     */
    /** Per-thread collection scratch (levels may be collected on parallel workers). */
    private static final class OrderedScratch {
        final ArrayList<Entity> entities = new ArrayList<>(1024);
        final it.unimi.dsi.fastutil.longs.LongArrayList stamps = new it.unimi.dsi.fastutil.longs.LongArrayList(1024);
    }

    private static final ThreadLocal<OrderedScratch> ORDERED = ThreadLocal.withInitial(OrderedScratch::new);
    private static final java.util.concurrent.atomic.AtomicBoolean ORDER_FALLBACK_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** Stamp recorded for a row whose section membership could not be pinned at visit time;
     *  never equal to a live stamp, so consumers always sort lists containing it. */
    static final long UNPINNED = -1L;

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static OrderedScratch entitiesInSectionOrder(ServerLevel level) {
        OrderedScratch out = ORDERED.get();
        out.entities.clear();
        out.stamps.clear();
        try {
            var manager = ((com.github.tatercertified.nalim.mixin.ServerLevelEntityManagerAccessor) level).rustmi$entityManager();
            var storage = (com.github.tatercertified.nalim.mixin.EntitySectionStorageAccessor)
                    ((com.github.tatercertified.nalim.mixin.PersistentEntitySectionManagerAccessor) (Object) manager)
                            .rustmi$sectionStorage();
            it.unimi.dsi.fastutil.longs.Long2ObjectMap sections = storage.rustmi$sections();
            for (var it = storage.rustmi$sectionIds().iterator(); it.hasNext(); ) {
                long key = it.nextLong();
                var section = (net.minecraft.world.level.entity.EntitySection) sections.get(key);
                if (section == null || section.isEmpty() || !section.getStatus().isAccessible()) {
                    continue;   // vanilla getEntities skips these; their entities aren't visible
                }
                for (Object o : ((com.github.tatercertified.nalim.mixin.EntitySectionAccessor) section).rustmi$storage()) {
                    if (!(o instanceof Entity e)) {   // also tolerates a torn read
                        continue;
                    }
                    // Stamp first, then position (writer: position, then section add/stamp).
                    // An unchanged stamp at query time then proves the entity is still in THIS
                    // section at THIS list position; a concurrent move shows up as a key mismatch.
                    long stamp = ((CrammingTracked) e).rustmi$sectionSeq();
                    boolean pinned = net.minecraft.core.SectionPos.asLong(e.blockPosition()) == key;
                    out.entities.add(e);
                    out.stamps.add(pinned ? stamp : UNPINNED);
                }
            }
            return out;
        } catch (Throwable layoutDiffers) {
            if (ORDER_FALLBACK_LOGGED.compareAndSet(false, true)) {
                LOGGER.warn("Cramming: vanilla-order collection unavailable ({}); using unordered collection",
                        layoutDiffers.toString());
            }
            out.entities.clear();
            out.stamps.clear();
            return null;
        }
    }

    private static void collectFromLevel(ServerLevel level, RustmiCramming c) {
        int dimId = level.dimension().identifier().hashCode();

        OrderedScratch ordered = entitiesInSectionOrder(level);
        Iterable<Entity> source;
        if (ordered == null) {
            c.markUnordered();
            source = level.getAllEntities();
        } else {
            source = ordered.entities;
        }
        int visit = -1;
        for (Entity e : source) {
            visit++;
            boolean living = e instanceof LivingEntity le && le.isAlive();
            boolean pushable = RustmiCramming.snapshotPushable(e, living);
            boolean origin = living
                    && !e.isPassenger()
                    && !e.isSpectator()
                    && level.isPositionEntityTicking(e.blockPosition());
            // Alive living rows are conservative cramming candidates without
            // evaluating their context-sensitive isPushable(), and also stay
            // sensor-visible exactly like vanilla's nearest-living query.
            if (!pushable && !origin && !living) {
                continue;
            }

            int flags = 0;
            // Every collected row is a snapshotPushable candidate, so this bit is always set
            // today and the kernel's "unflagged row" filter never fires; the flag stays as
            // the ABI's way to mark rows that are only queried, not candidates.
            if (pushable) flags |= RustmiCramming.FLAG_IS_PUSHABLE;
            if (origin) flags |= RustmiCramming.FLAG_IS_ORIGIN;
            if (living) flags |= RustmiCramming.FLAG_IS_LIVING;

            AABB bb = e.getBoundingBox();
            double halfX = (bb.maxX - bb.minX) * 0.5;
            double halfY = (bb.maxY - bb.minY) * 0.5;
            double halfZ = (bb.maxZ - bb.minZ) * 0.5;
            double cx = (bb.maxX + bb.minX) * 0.5;
            double cy = (bb.maxY + bb.minY) * 0.5;
            double cz = (bb.maxZ + bb.minZ) * 0.5;

            CrammingEscapes.snapshotted(e, bb);
            c.addEntity(e, origin, dimId,
                    cx, cy, cz, halfX, halfY, halfZ, flags,
                    ordered != null ? ordered.stamps.getLong(visit) : UNPINNED);
        }
    }
}
