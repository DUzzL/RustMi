package com.github.tatercertified.nalim.redstone;

import com.github.tatercertified.nalim.rust.RustmiLib;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedstoneWireBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.RedstoneWallTorchBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.entity.ComparatorBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the lifecycle of every {@link RedpilerEngine} session on the server.
 * Sessions are claimed by AABB via {@code /rustmi redpiler claim}; the
 * host drives per-tick prepare / tick / drain / apply phases after Fabrolia
 * Stage C barriers complete (so main owns all inventory and block state
 * when our pipeline runs).
 *
 * <p>Coexistence:
 * <ul>
 *   <li>{@link CascadeEngine} (R3) — bails out for any pos inside a Redpiler
 *       AABB. The Redpiler IS the engine for those regions.</li>
 *   <li>{@code IslandScheduledTicks} (Fabrolia Stage C) — scheduled-tick
 *       dispatches inside a Redpiler AABB are filtered out at runSlice
 *       time. The backend's own scheduler drives those ticks.</li>
 *   <li>{@code LevelChunkMirror.oxi$invalidateCell} — foreign writes inside
 *       a Redpiler AABB bail the session (only Redpiler-flush writes are
 *       suppressed via the per-thread flag).</li>
 * </ul>
 */
public final class RedpilerHost {

    private static final Logger LOGGER = LoggerFactory.getLogger("Rustmi-RedpilerHost");

    /** Soft per-session node cap. Above this we refuse claim — too large and
     *  the per-tick drainChanges/apply walk dominates the speedup. */
    private static final int MAX_NODES = 64_000;

    /** Per-thread flag set true around {@code level.setBlock} calls made by
     *  the Redpiler flush. Read in mixins to skip the bail-on-foreign-write
     *  abort logic for our own writes. */
    public static final ThreadLocal<Boolean> IS_FLUSH = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public static boolean isFlushing() {
        return IS_FLUSH.get();
    }

    /** Per-position burnt-out timestamp (game ticks) for redstone torches.
     *  Survives bail/rewalk so a fresh backend can restore a torch's
     *  burnt state — otherwise every unrelated wire placement that
     *  triggers a rewalk would wipe the in-Rust {@code torch_history}
     *  and let the torch re-cycle 8 toggles → re-burn → play the
     *  burnout sound again (user-reported 2026-06-15:
     *  "ФАКЕЛ ПОСТОЯННО ОБНОВЛЯЕТЬСЯ КОГДА Я ГДЕ ТО СТАВЛЮ ПЫЛИНКУ").
     *
     *  <p>Entries older than 60 GT are removed lazily during
     *  {@link #restoreBurntTorches} — they've recovered naturally
     *  per vanilla's 60-tick history window. */
    private static final java.util.concurrent.ConcurrentHashMap<BlockPos, Long> BURNT_TORCHES =
            new java.util.concurrent.ConcurrentHashMap<>();

    private RedpilerHost() {}

    static {
        installFabroliaHook();
    }

    /** Best-effort install of the scheduled-tick filter into Fabrolia's
     *  IslandScheduledTicks. Reflective so Rustmi stays compatible
     *  with Fabrolia builds that predate the filter hook field. */
    private static void installFabroliaHook() {
        try {
            Class<?> cls = Class.forName("com.axalotl.fabrolia.common.regions.IslandScheduledTicks");
            java.lang.reflect.Field f = cls.getField("SCHEDULED_TICK_FILTER");
            f.set(null,
                    (java.util.function.BiPredicate<Level, BlockPos>)
                            RedpilerHost::shouldFilterTick);
            LOGGER.info("[redpiler] installed IslandScheduledTicks filter");
        } catch (ClassNotFoundException e) {
            // Fabrolia not present — nothing to filter on.
        } catch (NoSuchFieldException e) {
            LOGGER.warn("[redpiler] IslandScheduledTicks present but lacks SCHEDULED_TICK_FILTER — "
                    + "vanilla serial fallback will dispatch redpiler-area ticks "
                    + "(BlockStateBaseTickMixin still catches them)");
        } catch (Throwable t) {
            LOGGER.warn("[redpiler] could not install Fabrolia filter hook", t);
        }
    }

    // ── session registry ──────────────────────────────────────────────────

    private static final AtomicLong HANDLE_GEN = new AtomicLong(0x5ED401L);
    private static final ConcurrentHashMap<Long, RedpilerSession> SESSIONS = new ConcurrentHashMap<>();

    /** AABBs awaiting auto-rewalk after a bail. Drained at the start of
     *  the next tickLevel for the matching dim. Per-AABB single-entry —
     *  rapid bail-rewalk-bail loops are debounced by the dedup. */
    private static final ConcurrentHashMap<ResourceKey<Level>, java.util.concurrent.ConcurrentLinkedQueue<PendingRewalk>>
            PENDING_REWALKS = new ConcurrentHashMap<>();

    private static final class PendingRewalk {
        final BlockPos min, max;
        /** Pending scheduled ticks captured from the OLD backend at bail
         *  time. Replayed into the NEW backend's scheduler after re-claim
         *  so the toggle-phase of long-delay gates (repeaters set to max,
         *  comparator output cycles, etc.) survives the re-walk.
         *
         *  <p>Entry layout per element (long[3]):
         *    [0] = world {@link BlockPos#asLong()} of the node's primary block
         *    [1] = native slot distance from the next scheduler slot (0..15)
         *    [2] = scheduler priority ordinal (0=Highest..3=Normal)
         *
         *  <p>Stored as world positions (not node indices) because the new
         *  backend assigns fresh indices on re-compile — only the world
         *  position survives the rewalk. The replay step resolves each
         *  position back through the new session's {@code posToIdx}; any
         *  position the new walk dropped (a block was removed during the
         *  bail-gap) is silently skipped. */
        final java.util.List<long[]> pendingTicks;
        PendingRewalk(BlockPos min, BlockPos max, java.util.List<long[]> pendingTicks) {
            this.min = min; this.max = max; this.pendingTicks = pendingTicks;
        }
    }

    private record VanillaTickSeed(
            net.minecraft.world.ticks.LevelChunkTicks<Block> container,
            net.minecraft.world.ticks.ScheduledTick<Block> tick,
            int nodeIdx,
            int backendDelay,
            byte priority) {
    }

    /** Per-dim immutable AABB list snapshot. Hot read path (every block
     *  write checks this). Updated on claim/release via CoW. */
    private static final ConcurrentHashMap<ResourceKey<Level>, AtomicReference<AabbList>> PER_DIM =
            new ConcurrentHashMap<>();

    private static final Path STATE_FILE = Paths.get("config", "rustmi", "redpiler-areas.properties");
    // Default OFF — host is opt-in. Re-enable via /rustmi redpiler on.
    // The persisted state file at config/rustmi/redpiler-areas.properties
    // (`enabled=true|false`) overrides this default once set, so once a
    // user has explicitly enabled it the state survives restarts.
    private static volatile boolean enabled = loadEnabled(false);

    public static boolean isEnabled() { return enabled; }

    public static synchronized void setEnabled(boolean on) {
        if (enabled == on) return;
        enabled = on;
        persistEnabled(on);
        LOGGER.info("[redpiler] host {}", on ? "ENABLED" : "DISABLED");
        if (!on) {
            // Bail every session in flight WITHOUT queuing auto-rewalk —
            // user explicitly turned us off, they don't want everything
            // silently re-claimed when they turn us back on. Captured
            // ticks still go to vanilla so the world resumes naturally.
            for (RedpilerSession s : new ArrayList<>(SESSIONS.values())) {
                bail(s, "host disabled", false);
            }
            // Drop any leftover rewalks queued by earlier topology-change
            // bails — same intent: a fresh /rustmi redpiler on should
            // start clean. AutoClaim will re-scan hot chunks if enabled.
            PENDING_REWALKS.clear();
        }
    }

    private static boolean loadEnabled(boolean def) {
        try {
            if (Files.exists(STATE_FILE)) {
                for (String l : Files.readAllLines(STATE_FILE)) {
                    String t = l.trim();
                    if (t.startsWith("enabled=")) {
                        return Boolean.parseBoolean(t.substring(8));
                    }
                }
            }
        } catch (Exception ignored) {}
        return def;
    }

    private static void persistEnabled(boolean on) {
        try {
            Files.createDirectories(STATE_FILE.getParent());
            Files.write(STATE_FILE,
                    ("# Rustmi redpiler host (R4)\nenabled=" + on + "\n").getBytes());
        } catch (Exception e) {
            LOGGER.warn("could not persist redpiler enabled", e);
        }
    }

    // ── claim/release ─────────────────────────────────────────────────────

    /** Result tuple — handle ≥ 0 on success; otherwise message says why. */
    public static final class ClaimResult {
        public final long handle;
        public final String message;
        public final int nodes;
        public final int edges;

        public ClaimResult(long handle, String message, int nodes, int edges) {
            this.handle = handle;
            this.message = message;
            this.nodes = nodes;
            this.edges = edges;
        }
    }

    public static ClaimResult claim(ServerLevel level, BlockPos p1, BlockPos p2) {
        return claim(level, p1, p2, true);
    }

    /** Claim with explicit control over whether {@link #seedScheduledTicks}
     *  pulls vanilla LevelChunkTicks entries into the backend.
     *
     *  <p>Initial claims and async-rewalk paths pass {@code true}: vanilla
     *  may legitimately hold pre-existing ticks for blocks that just
     *  became modeled (e.g. a repeater scheduled before the claim
     *  started), and warmup alone can't recreate the in-flight phase of
     *  those ticks.
     *
     *  <p>Sync-rewalk paths (bail's autoRewalk=true with sync re-claim)
     *  pass {@code false}: the bailing setBlock's vanilla cascade
     *  scheduled ticks for adjacent modeled positions in vanilla
     *  LevelChunkTicks, BUT the backend's {@link #replayPendingTicksPreservingDelay}
     *  is about to replay the captured pre-bail backend state AND
     *  {@link #warmupSession} will detect any state mismatches the new
     *  topology introduces and schedule equivalent ticks. Seeding from
     *  vanilla on top of that pile produces duplicates that compound
     *  per bail cycle (logs 2026-06-15: 2→10→12→14 ticks per cycle),
     *  visible to the user as "повторители странно тригерятся" and ring
     *  oscillators collapsing to a stuck-ON state. */
    public static ClaimResult claim(ServerLevel level, BlockPos p1, BlockPos p2, boolean seedFromVanilla) {
        if (!enabled) {
            return new ClaimResult(-1L, "redpiler host disabled", 0, 0);
        }
        BlockPos min = new BlockPos(
                Math.min(p1.getX(), p2.getX()),
                Math.min(p1.getY(), p2.getY()),
                Math.min(p1.getZ(), p2.getZ()));
        BlockPos max = new BlockPos(
                Math.max(p1.getX(), p2.getX()),
                Math.max(p1.getY(), p2.getY()),
                Math.max(p1.getZ(), p2.getZ()));

        // Overlap-check: a new claim must NOT intersect any existing one
        // in this dim (sessions are independent — overlap would race
        // shared block writes).
        AtomicReference<AabbList> ref = PER_DIM.computeIfAbsent(level.dimension(),
                k -> new AtomicReference<>(AabbList.EMPTY));
        for (Aabb a : ref.get().items) {
            if (a.intersects(min, max)) {
                return new ClaimResult(-1L,
                        "overlaps existing session #" + a.handle, 0, 0);
            }
        }

        RedpilerWalker.Result w = RedpilerWalker.walk(level, min, max, MAX_NODES);
        if (!w.ok) {
            return new ClaimResult(-1L, w.message, 0, 0);
        }
        RedpilerEngine.Session backend = RedpilerEngine.Session.adopt(w.handle);
        RedpilerSession s = new RedpilerSession(level.dimension(), min, max, w.handle, backend,
                w.nodeBlocks, w.nodeStates, w.posToIdx, w.containerSources, w.containerReadDir,
                w.boundaryInputPos, w.boundaryInputDir, w.wireFedBoundaryConstants, w.observerWatchers);
        s.lastTickedLevel = level;

        // Seed the backend's pending-tick queue from existing LevelChunkTicks
        // entries within the area — gated by `seedFromVanilla`. See the
        // claim(level, p1, p2, seedFromVanilla) javadoc for why sync-rewalk
        // skips this. Vanilla ticks for modeled positions left behind in
        // LevelChunkTicks fire harmlessly: shouldFilterTick drops them at
        // execution time (filter is installed in Fabrolia's
        // IslandScheduledTicks dispatcher).
        if (seedFromVanilla) {
            try {
                seedScheduledTicks(level, s);
            } catch (RuntimeException failure) {
                try {
                    backend.close();
                } catch (RuntimeException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                String message = "pending-tick migration refused claim: " + failure.getMessage();
                if (failure instanceof IllegalArgumentException) {
                    LOGGER.info("[redpiler] {}", message);
                } else {
                    LOGGER.warn("[redpiler] {}", message, failure);
                }
                return new ClaimResult(-1L, message, 0, 0);
            }
        }
        // Warm-up is the CALLER's responsibility — for fresh claims (no
        // bail-replay) the warm-up runs immediately after this method
        // returns; for the auto-rewalk path in tickLevel it runs AFTER
        // {@link #replayPendingTicks} so update_node sees the replayed
        // pending_tick=true and skips double-scheduling. Garland bug
        // (2026-06-15) was claim() doing its own warmup before replay had
        // a chance to install pending ticks.

        SESSIONS.put(w.handle, s);
        // CoW-add to per-dim list.
        Aabb newAabb = new Aabb(min, max, w.handle);
        ref.updateAndGet(old -> old.add(newAabb));

        LOGGER.info("[redpiler] claimed #{} in {} {}-{} ({} nodes, {} edges)",
                w.handle, level.dimension().identifier(), min, max, w.totalNodes, w.totalEdges);
        return new ClaimResult(w.handle, "ok", w.totalNodes, w.totalEdges);
    }

    /** Run the backend's warm-up pass on a session. External claim callers
     *  (commands, AutoClaim) must invoke this after {@link #claim} when
     *  they have no pending-tick replay to install — otherwise gates whose
     *  walk-time POWERED snapshot disagrees with their newly-populated
     *  input histogram never wake. For auto-rewalk paths that DO have a
     *  replay, call {@link #replayPendingTicks} FIRST and warm-up SECOND so
     *  update_node skips gates with replayed pending ticks (avoids the
     *  garland double-schedule, 2026-06-15). */
    public static void warmupSession(long handle) {
        RedpilerSession s = SESSIONS.get(handle);
        if (s == null) return;
        try {
            s.backend.warmup();
        } catch (Throwable t) {
            LOGGER.warn("[redpiler] warmup failed for #{}", handle, t);
        }
        // After warmup, restore burnt-torch history from the
        // persistent map so torches that burnt out in the previous
        // session don't re-cycle through their burnout chain.
        restoreBurntTorches(s);
    }

    /** Restore burnt-torch markers from {@link #BURNT_TORCHES}. Called
     *  after warmup completes. Strips entries older than 60 GT (vanilla
     *  recovery window). Each surviving entry that maps to a torch
     *  node in this session is marked burnt in the FFI with the
     *  correct age offset so it recovers at the right gametime. */
    /** Vanilla burnout history window = 60 GT
     *  ({@code RedstoneTorchBlock.isToggledTooFrequently} prunes
     *  entries older than 60 ticks). Match that here so the
     *  persistent map releases its hold at the same time the
     *  Rust-side history would naturally age out — torches that
     *  recover in vanilla recover here too. */
    private static final long TORCH_BURNOUT_WINDOW_TICKS = 60L;

    private static void restoreBurntTorches(RedpilerSession s) {
        if (s.lastTickedLevel == null) return;
        long now = s.lastTickedLevel.getGameTime();
        int removed = 0;
        java.util.Iterator<Map.Entry<BlockPos, Long>> it = BURNT_TORCHES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Long> e = it.next();
            if (now - e.getValue() > TORCH_BURNOUT_WINDOW_TICKS) {
                it.remove();
                removed++;
            }
        }
        int restored = 0;
        int skippedNotInClaim = 0;
        int skippedFfiError = 0;
        for (Map.Entry<BlockPos, Long> e : BURNT_TORCHES.entrySet()) {
            BlockPos pos = e.getKey();
            Integer idx = s.posToIdx.get(pos);
            if (idx == null) {
                skippedNotInClaim++;
                continue;
            }
            int ageTicks = (int) Math.max(0, Math.min(TORCH_BURNOUT_WINDOW_TICKS, now - e.getValue()));
            int rc = RustmiLib.oxi_redpiler_mark_torch_burnt(s.handle, idx, ageTicks);
            if (rc >= 0) {
                restored++;
            } else {
                skippedFfiError++;
                LOGGER.warn("[redpiler] mark_torch_burnt({}, {}, age={}) failed rc={} — node may not be a torch",
                        s.handle, idx, ageTicks, rc);
            }
        }
        if (restored > 0 || skippedNotInClaim > 0 || skippedFfiError > 0 || removed > 0) {
            LOGGER.info("[redpiler] restoreBurntTorches: restored={}, skipped-out-of-claim={}, skipped-ffi-err={}, removed-expired={} (BURNT_TORCHES size now {})",
                    restored, skippedNotInClaim, skippedFfiError, removed, BURNT_TORCHES.size());
        }
    }

    public static boolean release(long handle) {
        RedpilerSession s = SESSIONS.remove(handle);
        if (s == null) return false;
        try {
            // Drain any backend-pending ticks back into LevelChunkTicks so
            // the world resumes from where the backend left off.
            if (s.lastTickedLevel != null) {
                reinstateBackendTicks(s, s.lastTickedLevel);
            }
            return true;
        } finally {
            closeSession(s);
            LOGGER.info("[redpiler] released #{}", handle);
        }
    }

    private static void closeSession(RedpilerSession s) {
        SESSIONS.remove(s.handle);
        AtomicReference<AabbList> ref = PER_DIM.get(s.dim);
        try {
            s.backend.close();
        } finally {
            if (ref != null) {
                ref.updateAndGet(old -> old.removeHandle(s.handle));
            }
        }
    }

    public static List<RedpilerSession> sessionsIn(Level level) {
        AtomicReference<AabbList> ref = PER_DIM.get(level.dimension());
        if (ref == null) return List.of();
        List<RedpilerSession> out = new ArrayList<>(ref.get().items.length);
        for (Aabb a : ref.get().items) {
            RedpilerSession s = SESSIONS.get(a.handle);
            if (s != null) out.add(s);
        }
        return out;
    }

    public static List<RedpilerSession> allSessions() {
        return new ArrayList<>(SESSIONS.values());
    }

    public static RedpilerSession sessionByHandle(long h) {
        return SESSIONS.get(h);
    }

    public static int sessionCount() {
        return SESSIONS.size();
    }

    // ── inside-aabb predicate (hot path) ──────────────────────────────────

    /** Hot path: called from invalidateCell + scheduled-tick filter +
     *  evaluator entry. Branchless when no claims exist in the dim. */
    public static boolean containsAny(Level level, BlockPos pos) {
        AtomicReference<AabbList> ref = PER_DIM.get(level.dimension());
        if (ref == null) return false;
        AabbList list = ref.get();
        Aabb[] items = list.items;
        for (Aabb a : items) {
            if (a.contains(pos)) return true;
        }
        return false;
    }

    public static long handleAt(Level level, BlockPos pos) {
        AtomicReference<AabbList> ref = PER_DIM.get(level.dimension());
        if (ref == null) return -1L;
        for (Aabb a : ref.get().items) {
            if (a.contains(pos)) return a.handle;
        }
        return -1L;
    }

    // ── tick driver ───────────────────────────────────────────────────────

    /** Called from Fabrolia LevelMixin after both Stage C barriers
     *  (IslandBlockEntityTicks + IslandScheduledTicks) complete on this
     *  level. Main owns all state at this point. */
    public static void tickLevel(ServerLevel level, long gameTime) {
        if (!enabled) return;
        // Respect vanilla's tick-freeze (/tick freeze, /tick step). Without
        // this check the backend keeps draining its scheduler and pushing
        // setBlock writes while vanilla LevelTicks is paused — comparators
        // / repeaters visibly toggle even during freeze (user-reported
        // 2026-06-15: "компораторы вообще никак не реагируют на тик фриз,
        // они как то пытаются но как бы нет"). The "kind of try" part is
        // the boundary-input refresher: it samples vanilla state every
        // tickLevel, so a lever flip mid-freeze (entity actions still work)
        // updates the backend's view, the backend processes it, and the
        // resulting changes flush to world — but downstream vanilla blocks
        // outside the claim don't react because their ticks are paused.
        //
        // runsNormally()==false during both `tick freeze` (always frozen)
        // and `tick step` between steps. During a step it momentarily
        // returns true for the stepped tick(s), so we naturally process
        // that step too. Fabrolia's IslandBlockEntityTicks uses the same
        // check at the same level.
        if (!level.tickRateManager().runsNormally()) {
            return;
        }
        // Process deferred bails captured by the mixin during the PREVIOUS
        // game tick's setBlock cascades. Each captured reason becomes a
        // single bail() call here — at this point the previous tick's
        // cascades have fully unwound, so the sync-rewalk's walker
        // reads the FINAL world state for every modeled position
        // (including wire connection updates that the cascade applied
        // after the original setBlock TAIL mixin fired). Snapshot the
        // session list because bail() removes from SESSIONS during
        // execution.
        for (RedpilerSession s : new ArrayList<>(SESSIONS.values())) {
            if (s.dim != level.dimension()) continue;
            String reason = s.deferredBailReason.getAndSet(null);
            if (reason != null && !s.aborted) {
                bail(s, "deferred: " + reason);
            }
        }
        // Auto-claim: scan for new high-density redstone areas every N ticks.
        try {
            AutoClaim.maybeScan(level, gameTime);
        } catch (Throwable t) {
            LOGGER.warn("[redpiler] auto-claim scan failed", t);
        }
        // Drain any auto-rewalk requests from a bail in the previous tick.
        // Each re-claim runs the walker afresh against the current world
        // state — picks up newly placed repeaters/wires/etc and rebuilds
        // the graph. Best-effort; failures are logged and skipped (the
        // user can manually /rustmi redpiler claim again if needed).
        java.util.concurrent.ConcurrentLinkedQueue<PendingRewalk> q =
                PENDING_REWALKS.get(level.dimension());
        if (q != null) {
            PendingRewalk pr;
            while ((pr = q.poll()) != null) {
                ClaimResult r = claim(level, pr.min, pr.max);
                if (r.handle < 0) {
                    LOGGER.warn("[redpiler] auto-rewalk failed for {}↔{}: {} — "
                                    + "falling back to vanilla scheduleTick for {} pending ticks",
                            pr.min, pr.max, r.message, pr.pendingTicks.size());
                    // Fallback: push captured ticks into vanilla so the
                    // area's redstone resumes from the same phase even
                    // when we can't re-take over.
                    for (long[] t : pr.pendingTicks) {
                        BlockPos tp = BlockPos.of(t[0]);
                        int delay = (int) t[1];
                        int prio = (int) t[2];
                        BlockState st = level.getBlockState(tp);
                        Block b = st.getBlock();
                        if (b == net.minecraft.world.level.block.Blocks.AIR) continue;
                        // delay+1 compensates for the advance-THEN-drain
                        // scheduler semantic. Vanilla accepts the full 1..16
                        // range represented by the backend's 16 slots.
                        level.scheduleTick(tp, b, delay + 1, mapPriority(prio));
                    }
                } else {
                    RedpilerSession ns = sessionByHandle(r.handle);
                    if (ns != null) {
                        if (!pr.pendingTicks.isEmpty()) {
                            replayPendingTicks(ns, pr.pendingTicks);
                        }
                        // Warm-up AFTER replay so update_node sees the
                        // replayed pending_tick=true on relevant gates and
                        // skips them — prevents double-scheduling that
                        // produced the garland flicker (2026-06-15).
                        // warmupSession also restores burnt-torch state
                        // from BURNT_TORCHES (bypass would cause every
                        // rewalk to re-burn out the torches).
                        try {
                            warmupSession(r.handle);
                        } catch (Throwable t) {
                            LOGGER.warn("[redpiler] warmup after auto-rewalk failed for #{}",
                                    r.handle, t);
                        }
                    }
                    LOGGER.info("[redpiler] auto-rewalk re-claimed #{} ({} nodes, {} pending ticks replayed)",
                            r.handle, r.nodes, pr.pendingTicks.size());
                }
            }
        }
        AtomicReference<AabbList> ref = PER_DIM.get(level.dimension());
        if (ref == null) return;
        for (Aabb a : ref.get().items) {
            RedpilerSession s = SESSIONS.get(a.handle);
            if (s == null || s.aborted) continue;
            try {
                tickOne(level, s);
            } catch (Throwable t) {
                LOGGER.error("[redpiler] session #{} tick failed — bailing", a.handle, t);
                bail(s, "tick exception: " + t.getClass().getSimpleName());
            }
        }
    }

    private static void tickOne(ServerLevel level, RedpilerSession s) {
        s.lastTickedLevel = level;
        // 1a. Prepare — sync container signals (comparator back-input).
        for (Map.Entry<Integer, BlockPos> e : s.containerSources.entrySet()) {
            int idx = e.getKey();
            BlockPos cp = e.getValue();
            Direction d = s.containerReadDir.getOrDefault(idx, Direction.UP);
            BlockState cs = level.getBlockState(cp);
            int sig = 0;
            if (cs.hasAnalogOutputSignal()) {
                try { sig = cs.getAnalogOutputSignal(level, cp, d); } catch (Throwable ignored) {}
            }
            if (s.lastContainerSignal[idx] != sig) {
                s.lastContainerSignal[idx] = sig;
                s.backend.setInput(idx, sig > 0, (byte) sig);
                s.inputUpdates++;
            }
        }
        // 1b. Prepare — sync boundary inputs (observers, external diodes,
        //     redstone blocks, strong-power-through-solid). Each Constant
        //     captures level.getSignal(pos, dir) — vanilla's exact "what
        //     signal reaches this face" function. Cheap: 1 lookup per
        //     boundary Constant per tick, set_input only on change.
        for (Map.Entry<Integer, BlockPos> e : s.boundaryInputPos.entrySet()) {
            int idx = e.getKey();
            BlockPos cp = e.getValue();
            Direction d = s.boundaryInputDir.get(idx);
            if (d == null) continue;
            int sig;
            try {
                // Wire-fed constants suppress wire feedback through
                // conductors so the wire's own POWER doesn't self-loop
                // back into its input histogram — matches vanilla's
                // RedstoneWireEvaluator.getBlockSignal which toggles
                // shouldSignal=false on the wire singleton during the
                // read. Non-wire targets keep plain getSignal because
                // vanilla DiodeBlock.getInputSignal et al. DO read wire
                // contributions through conductors.
                sig = s.wireFedBoundaryConstants.contains(idx)
                        ? RedpilerWalker.sampleBoundaryForWire(level, cp, d)
                        : level.getSignal(cp, d);
            } catch (Throwable t) {
                sig = 0;
            }
            if (s.lastBoundarySignal[idx] != sig) {
                s.lastBoundarySignal[idx] = sig;
                s.backend.setInput(idx, sig > 0, (byte) sig);
                s.inputUpdates++;
            }
        }

        // 2. Tick the backend.
        s.backend.tick();

        // 3. Drain changes + apply.
        boolean[] anyChanged = { false };
        s.backend.drainChanges(ch -> {
            anyChanged[0] = true;
            s.changesDrained++;
            applyChange(level, s, ch);
        });
        // Note-block events (currently a no-op for MVP — no noteblocks).
        s.backend.drainEvents(ev -> {});
        // Torch burnouts — fire vanilla level event 1502 at each torch's
        // world position (smoke particle + redstone-torch-burnout sound),
        // matching {@code RedstoneTorchBlock.tick}'s {@code
        // level.levelEvent(1502, pos, 0)} call on the LIT && hasSignal
        // branch when isToggledTooFrequently just flipped to true.
        s.backend.drainBurnouts(idx -> {
            if (idx < 0 || idx >= s.nodeBlocks.size()) return;
            List<BlockPos> blocks = s.nodeBlocks.get(idx);
            if (blocks.isEmpty()) return;
            BlockPos pos = blocks.get(0).immutable();
            level.levelEvent(1502, pos, 0);
            // Persist burnt timestamp so sync-rewalk can restore the
            // burnt state and avoid re-burning the torch on the next
            // unrelated wire placement.
            BURNT_TORCHES.put(pos, level.getGameTime());
            LOGGER.info("[redpiler] torch burnout at {} (game tick {}) — sound played, persisted in BURNT_TORCHES (size now {})",
                    pos.toShortString(), level.getGameTime(), BURNT_TORCHES.size());
        });

        s.ticksProcessed++;
        // No global stats burst yet — /rustmi redpiler status reports
        // per-session.
    }

    private static void applyChange(ServerLevel level, RedpilerSession s,
                                    RedpilerEngine.NodeChange ch) {
        if (ch.idx < 0 || ch.idx >= s.nodeBlocks.size()) return;
        List<BlockPos> blocks = s.nodeBlocks.get(ch.idx);
        List<BlockState> orig = s.nodeStates.get(ch.idx);
        if (blocks.isEmpty()) return; // synthetic constant — no world block
        BlockPos pos = blocks.get(0);
        BlockState was = level.getBlockState(pos);
        BlockState origSt = orig.get(0);
        BlockState target = derive(origSt, ch);
        if (target == null || target == was) {
            // Comparator BE outputSignal write still needed even if block-
            // state didn't change.
            if (origSt.getBlock() instanceof ComparatorBlock
                    && level.getBlockEntity(pos) instanceof ComparatorBlockEntity be
                    && be.getOutputSignal() != (ch.outputPower & 0xFF)) {
                be.setOutputSignal(ch.outputPower & 0xFF);
                level.updateNeighborsAt(pos, origSt.getBlock());
            }
            return;
        }
        // Flag bit 2 = UPDATE_CLIENTS (no neighbor notifications). Redpiler
        // handles neighbor updates explicitly for boundary outputs below.
        IS_FLUSH.set(Boolean.TRUE);
        try {
            level.setBlock(pos, target, Block.UPDATE_CLIENTS);
            s.writesApplied++;
        } finally {
            IS_FLUSH.set(Boolean.FALSE);
        }
        if (target.getBlock() instanceof ComparatorBlock
                && level.getBlockEntity(pos) instanceof ComparatorBlockEntity be) {
            be.setOutputSignal(ch.outputPower & 0xFF);
        }
        // Observer wake — vanilla observer detection (updateShape on back
        // direction) fires from neighbor-update notifications, which our
        // flag=2 setBlock does NOT generate. Walk the watch table and
        // scheduleTick every observer whose back is at this pos. Mirrors
        // ObserverBlock.startSignal verbatim, including the no-double-
        // schedule check via hasScheduledTick.
        java.util.List<BlockPos> watchers = s.observerWatchers.get(pos);
        if (watchers != null) {
            for (BlockPos op : watchers) {
                BlockState ost = level.getBlockState(op);
                if (ost.getBlock() instanceof net.minecraft.world.level.block.ObserverBlock) {
                    if (!level.getBlockTicks().hasScheduledTick(op, ost.getBlock())) {
                        level.scheduleTick(op, ost.getBlock(), 2);
                    }
                }
            }
        }
        // Neighbor updates for NON-MODELED neighbors. Modeled blocks
        // (wires/diodes/etc) are driven by Redpiler internally and have
        // their vanilla neighborChanged path cancelled by CascadeEngine.
        // But un-modeled blocks adjacent to a flushed Redpiler node —
        // pistons, hoppers, dispensers, droppers, fence gates, doors,
        // tripwire hooks, sculk sensors, and crucially blocks OUTSIDE
        // the AABB that vanilla still owns — need a neighborChanged
        // notification so they can react. setBlock flag=2 doesn't fire
        // these (it's UPDATE_CLIENTS only). Trigger updateNeighborsAt
        // (fans out to all 6 neighbors; modeled ones get cancelled, so
        // the net cost is one dispatch + cheap cancel-checks).
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            if (!s.posToIdx.containsKey(n)) {
                level.updateNeighborsAt(pos, target.getBlock());
                break;
            }
        }
    }

    private static boolean containsPos(RedpilerSession s, BlockPos pos) {
        return pos.getX() >= s.min.getX() && pos.getX() <= s.max.getX()
            && pos.getY() >= s.min.getY() && pos.getY() <= s.max.getY()
            && pos.getZ() >= s.min.getZ() && pos.getZ() <= s.max.getZ();
    }

    /** Build the target BlockState from the original + drained change. */
    private static BlockState derive(BlockState orig, RedpilerEngine.NodeChange ch) {
        Block b = orig.getBlock();
        if (b instanceof RedstoneWireBlock) {
            int pow = ch.outputPower & 0xFF;
            if (orig.hasProperty(RedstoneWireBlock.POWER)) {
                return orig.setValue(RedstoneWireBlock.POWER, pow);
            }
        } else if (b instanceof RepeaterBlock) {
            BlockState s = orig;
            if (s.hasProperty(BlockStateProperties.POWERED)) {
                s = s.setValue(BlockStateProperties.POWERED, ch.powered);
            }
            if (s.hasProperty(BlockStateProperties.LOCKED)) {
                s = s.setValue(BlockStateProperties.LOCKED, ch.locked);
            }
            return s;
        } else if (b instanceof ComparatorBlock) {
            if (orig.hasProperty(BlockStateProperties.POWERED)) {
                return orig.setValue(BlockStateProperties.POWERED, ch.powered);
            }
        } else if (b instanceof RedstoneTorchBlock) {
            // Both ground and wall torches share the LIT property via inheritance.
            if (orig.hasProperty(BlockStateProperties.LIT)) {
                return orig.setValue(BlockStateProperties.LIT, ch.powered);
            }
        } else if (b instanceof RedstoneLampBlock) {
            if (orig.hasProperty(BlockStateProperties.LIT)) {
                return orig.setValue(BlockStateProperties.LIT, ch.powered);
            }
        } else if (b instanceof LeverBlock || b instanceof ButtonBlock) {
            if (orig.hasProperty(BlockStateProperties.POWERED)) {
                return orig.setValue(BlockStateProperties.POWERED, ch.powered);
            }
        }
        return null;
    }

    // ── bail ──────────────────────────────────────────────────────────────

    /** Stop driving this session: drain its pending ticks back into vanilla
     *  LevelChunkTicks, mark aborted, and forget. World keeps ticking from
     *  exactly where the backend left off. Schedules an auto-rewalk of the
     *  same AABB on the next tickLevel so an edit (placing a repeater, a
     *  piston pushing into the graph, etc.) doesn't permanently disable
     *  Redpiler — the new state is walked and re-compiled within 1–2 ticks. */
    public static void bail(RedpilerSession s, String reason) {
        bail(s, reason, true);
    }

    /** Bail with explicit auto-rewalk control. {@code autoRewalk=false}
     *  is used by {@link #setEnabled}(false) — user explicitly turned the
     *  host off; auto-reclaiming on the next {@code /rustmi redpiler on}
     *  would surprise them. Topology-change bails from
     *  {@code LevelChunkMirrorMixin} keep auto-rewalk on so block edits
     *  don't permanently disable Redpiler. */
    public static void bail(RedpilerSession s, String reason, boolean autoRewalk) {
        if (s.aborted) return;
        s.aborted = true;
        LOGGER.warn("[redpiler] session #{} bailing: {} (autoRewalk={})",
                s.handle, reason, autoRewalk);

        // Capture pending backend ticks first. Sync rewalk replays them into
        // a new backend without letting the world evolve; async/no-rewalk
        // paths restore them to vanilla before relinquishing ownership.
        List<long[]> capturedTicks = java.util.Collections.emptyList();
        try {
            if (s.lastTickedLevel != null) {
                capturedTicks = capturePendingTicks(s);
            }
        } catch (RuntimeException | Error failure) {
            LOGGER.error("[redpiler] session #{} could not drain pending ticks during bail; closing it",
                    s.handle, failure);
            closeSession(s);
            throw failure;
        }
        ServerLevel level = s.lastTickedLevel;

        closeSession(s);

        // ─── Sync rewalk path ───────────────────────────────────────────
        // Closes the gap between bail and re-claim that would otherwise
        // let vanilla wire cascade settle the network to max-of-sources
        // equilibrium before the new backend takes over — destroying
        // mid-pulse phase in clock/feedback circuits. By the time
        // setBlockState's upstream {@code updateNeighborsAt} fires,
        // the new claim is back in PER_DIM and CascadeEngine cancels
        // the eval, freezing wire values at their backend-flushed state.
        //
        // <p>Pending ticks are replayed DIRECTLY into the new backend with
        // the same ring-slot distance. The world has not evolved between
        // capture and re-claim, so this avoids a vanilla detour without
        // losing pulse phase.
        //
        // <p>Falls back to async-queue + vanilla-push if synchronous
        // claim() fails (e.g. walker hit MAX_NODES cap).
        if (autoRewalk && level != null) {
            try {
                // seedFromVanilla=false: sync-rewalk path. capturedTicks
                // (replayed below) + warmup state-mismatch detection
                // together cover every legitimate transition; pulling
                // vanilla LevelChunkTicks ON TOP produces compounding
                // duplicates (the "ring collapses to stuck-ON" bug,
                // 2026-06-15).
                ClaimResult r = claim(level, s.min, s.max, false);
                if (r.handle >= 0) {
                    RedpilerSession ns = sessionByHandle(r.handle);
                    if (ns != null && !capturedTicks.isEmpty()) {
                        replayPendingTicksPreservingDelay(ns, capturedTicks);
                    }
                    warmupSession(r.handle);
                    LOGGER.info("[redpiler] sync rewalk: re-claimed #{} "
                            + "({} nodes, {} edges, {} ticks replayed)",
                            r.handle, r.nodes, r.edges, capturedTicks.size());
                    return;
                }
                LOGGER.warn("[redpiler] sync rewalk failed: {} — falling back to async queue",
                        r.message);
            } catch (Throwable t) {
                LOGGER.warn("[redpiler] sync rewalk threw — falling back to async queue", t);
            }
            // Async fallback: push captured ticks to vanilla so the world
            // resumes from them, queue async rewalk for next tickLevel.
            if (!capturedTicks.isEmpty()) {
                pushCapturedTicksToVanilla(level, s, capturedTicks);
            }
            PENDING_REWALKS.computeIfAbsent(s.dim,
                    k -> new java.util.concurrent.ConcurrentLinkedQueue<>())
                .add(new PendingRewalk(s.min, s.max, java.util.Collections.emptyList()));
            return;
        }

        // ─── No-rewalk path (autoRewalk=false, e.g. /redpiler off) ──────
        // Push captured ticks to vanilla. World resumes via vanilla
        // scheduler from where the backend left off, preserving phase
        // across the off-on cycle.
        if (!capturedTicks.isEmpty() && level != null) {
            pushCapturedTicksToVanilla(level, s, capturedTicks);
        }
    }

    /** Push a previously-captured pending-ticks list (from
     *  {@link #capturePendingTicks}) into vanilla {@code LevelChunkTicks}
     *  via {@link ServerLevel#scheduleTick}. The native delay is relative
     *  to the next backend slot, so vanilla receives {@code delay + 1}
     *  (the exact 1..16 range). Used by bail's async-fallback and
     *  no-rewalk paths; sync rewalk uses
     *  {@link #replayPendingTicksPreservingDelay} instead. */
    private static void pushCapturedTicksToVanilla(ServerLevel level, RedpilerSession s, List<long[]> ticks) {
        for (long[] t : ticks) {
            BlockPos pos = BlockPos.of(t[0]);
            int delay = (int) t[1];
            int prio = (int) t[2];
            Integer idx = s.posToIdx.get(pos);
            if (idx == null || idx < 0 || idx >= s.nodeStates.size()) continue;
            List<BlockState> states = s.nodeStates.get(idx);
            if (states.isEmpty()) continue;
            Block b = states.get(0).getBlock();
            // delay+1 — compensates for advance-THEN-drain scheduler
            // semantic.
            level.scheduleTick(pos, b, delay + 1, mapPriority(prio));
        }
    }

    /** Replay captured ticks directly into a new backend's scheduler while
     *  preserving their exact ring-slot distance. A drained delay is relative
     *  to {@code pos + 1}; scheduling outside a backend tick therefore maps
     *  it to {@code (delay + 1) mod 16}. In particular, drained delay 15
     *  targets the current slot and fires after one complete ring rather than
     *  one tick early.
     *
     *  <p>Used only by the synchronous rewalk path, where the world does not
     *  evolve between capture and replay. */
    private static void replayPendingTicksPreservingDelay(RedpilerSession s, List<long[]> ticks) {
        int replayed = 0;
        int skipped = 0;
        for (long[] t : ticks) {
            BlockPos pos = BlockPos.of(t[0]);
            int delay = (int) t[1];
            int prio = (int) t[2];
            Integer idx = s.posToIdx.get(pos);
            if (idx == null) { skipped++; continue; }
            // Exact inverse of drain_pending_ticks' (pos+1)-relative delay.
            int schedDelay = (delay + 1) % 16;
            int rc = RustmiLib.oxi_redpiler_schedule_tick(
                    s.handle, idx, schedDelay, (byte) (prio & 0xFF));
            if (rc < 0) {
                if (replayed == 0) {
                    LOGGER.warn("[redpiler] sync-replay schedule_tick(idx={}, delay={}, prio={}) failed rc={}",
                            idx, schedDelay, prio, rc);
                }
                skipped++;
            } else {
                replayed++;
            }
        }
        if (replayed > 0 || skipped > 0) {
            LOGGER.info("[redpiler] sync-replay: {} ticks restored ({} skipped, wrapped +1 mapping)",
                    replayed, skipped);
        }
    }

    // ── scheduled-tick filter (called from coexistence hooks) ─────────────

    /** Returns true if vanilla's scheduled-tick dispatch should DROP this
     *  tick because Redpiler owns its position AND its block.
     *
     *  <p>Pos-only filter would drop observer ticks too — observers live
     *  inside the AABB but Redpiler doesn't model them; they tick via
     *  vanilla and we sample their POWERED state through boundary
     *  inputs. Same goes for any future un-modeled block the user might
     *  place inside the area. */
    /** Block types whose addition/removal at any AABB position invalidates
     *  the walked graph topology (new edges/nodes appear; existing ones go
     *  away). Used by the bail check in LevelChunkMirrorMixin to decide
     *  whether a foreign block change is "redstone-relevant". Observers
     *  count as topology-changing because their FACING and POWERED state
     *  drive the boundary-input scan; placing/removing one needs a re-walk
     *  to rebuild the watchers table. */
    /** True when {@code pos} is within Chebyshev distance {@code radius}
     *  of any modeled node in {@code s}. Used by
     *  {@link com.github.tatercertified.nalim.mixin.LevelChunkMirrorMixin}
     *  to skip bail for placements far from every modeled position —
     *  such placements cannot affect the existing graph (wire connects
     *  at 1 block; strong-power crosses 1 conductor).
     *
     *  <p>Cost: O(N) where N = modeled-node count. Acceptable for the
     *  placement-driven call rate; switch to a precomputed expanded set
     *  if profiling exposes this. */
    public static boolean isWithinModelRadius(RedpilerSession s, BlockPos pos, int radius) {
        int px = pos.getX(), py = pos.getY(), pz = pos.getZ();
        for (BlockPos modeled : s.posToIdx.keySet()) {
            int dx = Math.abs(modeled.getX() - px);
            int dy = Math.abs(modeled.getY() - py);
            int dz = Math.abs(modeled.getZ() - pz);
            if (Math.max(dx, Math.max(dy, dz)) <= radius) return true;
        }
        return false;
    }

    public static boolean isRedstoneTopologyBlock(net.minecraft.world.level.block.Block b) {
        return b instanceof net.minecraft.world.level.block.RedstoneWireBlock
            || b instanceof net.minecraft.world.level.block.DiodeBlock
            || b instanceof net.minecraft.world.level.block.ComparatorBlock
            || b instanceof net.minecraft.world.level.block.RedstoneTorchBlock
            || b instanceof net.minecraft.world.level.block.RedstoneLampBlock
            || b instanceof net.minecraft.world.level.block.LeverBlock
            || b instanceof net.minecraft.world.level.block.ButtonBlock
            || b instanceof net.minecraft.world.level.block.BasePressurePlateBlock
            || b instanceof net.minecraft.world.level.block.ObserverBlock
            // Block of Redstone — plain Block instance (no special class),
            // matched by reference. Placement/removal MUST bail so walker
            // re-runs and creates/drops the Constant-15 node modelling it.
            || b == net.minecraft.world.level.block.Blocks.REDSTONE_BLOCK;
    }

    /** True iff the BlockState diff between {@code was} and {@code state}
     *  contains at least one property that the player must have changed
     *  manually (DELAY / MODE / FACING / etc.) — as opposed to properties
     *  that vanilla redstone propagation flips on its own (POWERED /
     *  POWER / LIT / LOCKED).
     *
     *  <p>The mixin uses this to decide whether a property-only change
     *  at a modeled position is worth bailing on. Cascade-driven flips
     *  must NOT bail — the backend will mirror them via its own state
     *  tracking, and bailing creates a runaway recursion (drain →
     *  vanilla cascade → bail → sync-rewalk → drain → ...) that's
     *  precisely the "ticks accumulating 2→10→12→14 per cycle"
     *  pattern observed in 2026-06-15 logs.
     *
     *  <p>Same-block guarantee: callers only invoke this when
     *  {@code was.getBlock() == state.getBlock()} (the topology check
     *  is separate). That means the two BlockStates share the same
     *  property set; iterating either side's properties is equivalent. */
    public static boolean isPlayerRelevantPropertyChange(
            net.minecraft.world.level.block.state.BlockState was,
            net.minecraft.world.level.block.state.BlockState state) {
        for (net.minecraft.world.level.block.state.properties.Property<?> prop
                : was.getProperties()) {
            if (was.getValue(prop).equals(state.getValue(prop))) continue;
            // Cascade-driven properties — flipped by vanilla propagation,
            // mirrored by backend. NOT a reason to bail.
            if (prop == net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED
             || prop == net.minecraft.world.level.block.state.properties.BlockStateProperties.POWER
             || prop == net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT
             || prop == net.minecraft.world.level.block.state.properties.BlockStateProperties.LOCKED) {
                continue;
            }
            // Any other property differs (DELAY / MODE / FACING /
            // EXTENDED / etc.) — graph encoding depends on it, bail and
            // re-walk.
            return true;
        }
        return false;
    }

    public static boolean shouldFilterTick(Level level, BlockPos pos) {
        if (!enabled) return false;
        if (!containsAny(level, pos)) return false;
        net.minecraft.world.level.block.Block b = level.getBlockState(pos).getBlock();
        return b instanceof net.minecraft.world.level.block.RedstoneWireBlock
            || b instanceof net.minecraft.world.level.block.DiodeBlock
            || b instanceof net.minecraft.world.level.block.ComparatorBlock
            || b instanceof net.minecraft.world.level.block.RedstoneTorchBlock
            || b instanceof net.minecraft.world.level.block.RedstoneLampBlock
            || b instanceof net.minecraft.world.level.block.LeverBlock
            || b instanceof net.minecraft.world.level.block.ButtonBlock
            || b instanceof net.minecraft.world.level.block.BasePressurePlateBlock;
    }

    // ── scheduled-tick seed / reinstate ───────────────────────────────────

    /** Transfer pre-existing vanilla {@code LevelChunkTicks} entries whose
     *  pos is inside the claim AABB and whose block is modeled by the
     *  backend into the backend's tick scheduler, then remove them from
     *  vanilla so they don't double-fire.
     *
     *  <p><b>Why this matters (the garland-flicker root cause, 2026-06-15).</b>
     *  The original implementation was a no-op — pre-existing ticks were
     *  just filtered out at dispatch time via {@link #shouldFilterTick}.
     *  For a long chain of repeaters in steady state, each repeater has a
     *  scheduled tick in flight (waiting to flip its POWERED state at the
     *  next cycle boundary). Dropping those ticks on the floor at claim
     *  time loses the chain's phase: the backend starts with NO pending
     *  ticks, warmup discovers state mismatches and schedules NEW ticks
     *  with delay=full-period (8 GT for max-delay repeaters), but the
     *  upstream observer keeps blinking on a 2-GT cycle independent of
     *  when the backend's first tick fires. The phase drift between the
     *  blink and the repeater's tick-fires produces the "garland" flicker.
     *  User-reported: "иногда мигает гирляндой, иногда нет" — exact match
     *  for "depends on claim-time phase alignment".
     *
     *  <p>With this seed, every in-flight repeater/comparator/torch/button
     *  tick is preserved with its EXACT remaining delay + priority — the
     *  backend's first tick for each gate fires at the same game tick
     *  vanilla would have, so the chain's phase is unchanged across the
     *  claim boundary.
     *
     *  <p>Threading: called from {@link #claim}, which runs on the main
     *  thread (commands / AutoClaim hook). Island slice workers cannot be
     *  mid-flight at this point — vanilla's tickBlocks happens at a
     *  different phase of the server loop, and Fabrolia enforces the same
     *  invariant. Direct mutation of {@code LevelChunkTicks} structures
     *  is therefore safe without grabbing the Fabrolia monitor. */
    private static void seedScheduledTicks(ServerLevel level, RedpilerSession s) {
        long now = level.getGameTime();
        int minCx = s.min.getX() >> 4;
        int maxCx = s.max.getX() >> 4;
        int minCz = s.min.getZ() >> 4;
        int maxCz = s.max.getZ() >> 4;
        List<VanillaTickSeed> seeds = new ArrayList<>();

        // Snapshot and validate the complete transfer before touching either
        // scheduler. A claim owns every modeled block tick, so one delay that
        // the 16-slot backend cannot represent must reject the whole claim.
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                net.minecraft.world.level.chunk.ChunkAccess ca =
                        level.getChunkSource().getChunk(cx, cz,
                                net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
                if (!(ca instanceof net.minecraft.world.level.chunk.LevelChunk lc)) {
                    continue;
                }
                net.minecraft.world.ticks.TickContainerAccess<Block> tc = lc.getBlockTicks();
                if (!(tc instanceof net.minecraft.world.ticks.LevelChunkTicks<Block> lct)) {
                    continue;
                }
                lct.getAll().forEach(tick -> {
                    BlockPos pos = tick.pos();
                    if (pos.getX() < s.min.getX() || pos.getX() > s.max.getX()) return;
                    if (pos.getY() < s.min.getY() || pos.getY() > s.max.getY()) return;
                    if (pos.getZ() < s.min.getZ() || pos.getZ() > s.max.getZ()) return;
                    Integer idx = s.posToIdx.get(pos);
                    if (idx == null) return;

                    long remaining = Math.max(0L, tick.triggerTick() - now);
                    if (remaining > 15L) {
                        throw new IllegalArgumentException(
                                "modeled tick at " + pos.toShortString()
                                        + " has unsupported remaining delay " + remaining);
                    }
                    // Calls made outside backend.tick need a +1 phase shift.
                    // A 15-slot distance maps to delay 0 (one full ring), not
                    // delay 15, because the scheduler advances before drain.
                    int backendDelay = (int) ((remaining + 1L) % 16L);
                    seeds.add(new VanillaTickSeed(
                            lct, tick, idx, backendDelay,
                            (byte) vanillaPriorityToBackend(tick.priority())));
                });
            }
        }

        // Populate the backend first. Any FFI error rejects and destroys the
        // unregistered backend session while vanilla remains untouched.
        for (VanillaTickSeed seed : seeds) {
            int rc = RustmiLib.oxi_redpiler_schedule_tick(
                    s.handle, seed.nodeIdx(), seed.backendDelay(), seed.priority());
            if (rc < 0) {
                throw new IllegalStateException(
                        "schedule_tick(idx=" + seed.nodeIdx()
                                + ", delay=" + seed.backendDelay()
                                + ", priority=" + seed.priority() + ") failed with rc=" + rc);
            }
        }

        IdentityHashMap<net.minecraft.world.ticks.LevelChunkTicks<Block>,
                Set<net.minecraft.world.ticks.ScheduledTick<Block>>> removals =
                new IdentityHashMap<>();
        for (VanillaTickSeed seed : seeds) {
            removals.computeIfAbsent(seed.container(), ignored -> new HashSet<>())
                    .add(seed.tick());
        }
        removals.forEach((container, ticks) -> container.removeIf(ticks::contains));

        if (!seeds.isEmpty()) {
            LOGGER.info("[redpiler] session #{}: atomically seeded {} pre-existing ticks",
                    s.handle, seeds.size());
        }
    }

    /** Vanilla {@link net.minecraft.world.ticks.TickPriority} → backend
     *  priority byte (0=Highest, 1=Higher, 2=High, 3=Normal). Inverse of
     *  {@link #mapPriority}. */
    private static int vanillaPriorityToBackend(net.minecraft.world.ticks.TickPriority p) {
        return switch (p) {
            case EXTREMELY_HIGH -> 0;
            case VERY_HIGH -> 1;
            case HIGH -> 2;
            default -> 3;   // NORMAL / LOW / VERY_LOW / EXTREMELY_LOW collapse to Normal
        };
    }

    private static void reinstateBackendTicks(RedpilerSession s, ServerLevel level) {
        // Reinstate via drain_pending_ticks → level.scheduleTick. Used by
        // {@link #release} only — the bail path captures+routes ticks
        // explicitly via {@link #pushCapturedTicksToVanilla} or
        // {@link #replayPendingTicksPreservingDelay}.
        List<long[]> captured = capturePendingTicks(s);
        for (long[] t : captured) {
            BlockPos pos = BlockPos.of(t[0]);
            int delay = (int) t[1];
            int prio = (int) t[2];
            Integer idx = s.posToIdx.get(pos);
            if (idx == null || idx < 0 || idx >= s.nodeStates.size()) continue;
            List<BlockState> states = s.nodeStates.get(idx);
            if (states.isEmpty()) continue;
            Block b = states.get(0).getBlock();
            // delay+1 — compensates for advance-THEN-drain semantic.
            level.scheduleTick(pos, b, delay + 1, mapPriority(prio));
        }
    }

    /** Drain the backend's pending-tick queue into a Java list of
     *  (worldPos, delay, priority) triples. Resolves node indices to world
     *  positions immediately so the result survives a session close.
     *  Native errors throw because returning an empty list would silently
     *  discard the scheduler during bail or release.
     *
     *  <p>Side effects: the backend scheduler is emptied after a successful
     *  call (matching {@code oxi_redpiler_drain_pending_ticks}). The caller
     *  must replay the records into a new backend or push them to vanilla. */
    private static List<long[]> capturePendingTicks(RedpilerSession s) {
        int cap = Math.multiplyExact(Math.max(16, s.nodeBlocks.size()), 4);
        sun.misc.Unsafe u = UnsafeAccess.theUnsafe();
        long addr = u.allocateMemory((long) cap * 4L);
        List<long[]> out = new ArrayList<>();
        try {
            while (true) {
                int rc = RustmiLib.oxi_redpiler_drain_pending_ticks(s.handle, addr, cap);
                if (rc == -6) {  // ERR_OUT_CAP
                    cap = Math.multiplyExact(cap, 2);
                    addr = u.reallocateMemory(addr, (long) cap * 4L);
                    continue;
                }
                if (rc < 0) {
                    throw new IllegalStateException(
                            "redpiler drain_pending_ticks failed for session #"
                                    + s.handle + " with rc=" + rc);
                }
                for (int i = 0; i < rc; i++) {
                    int idx = u.getInt(addr + (long) i * 16L);
                    int delay = u.getInt(addr + (long) i * 16L + 4L);
                    int prio = u.getInt(addr + (long) i * 16L + 8L);
                    if (idx < 0 || idx >= s.nodeBlocks.size()) continue;
                    List<BlockPos> blocks = s.nodeBlocks.get(idx);
                    if (blocks.isEmpty()) continue;
                    BlockPos pos = blocks.get(0);
                    out.add(new long[] { pos.asLong(), delay, prio });
                }
                return out;
            }
        } finally {
            u.freeMemory(addr);
        }
    }

    /** Replay a captured pending-tick list into the NEW backend session's
     *  scheduler. Positions that the new walk dropped (a block was removed
     *  during the bail gap) are silently skipped. Failure to schedule a
     *  particular tick is logged once and continues — partial replay is
     *  preferable to losing the whole queue. */
    private static void replayPendingTicks(RedpilerSession s, List<long[]> ticks) {
        int replayed = 0;
        int skipped = 0;
        for (long[] t : ticks) {
            BlockPos pos = BlockPos.of(t[0]);
            int delay = (int) t[1];
            int prio = (int) t[2];
            Integer idx = s.posToIdx.get(pos);
            if (idx == null) { skipped++; continue; }
            // Exact inverse of drain_pending_ticks' (pos+1)-relative delay.
            int schedDelay = (delay + 1) % 16;
            int rc = RustmiLib.oxi_redpiler_schedule_tick(
                    s.handle, idx, schedDelay, (byte) (prio & 0xFF));
            if (rc < 0) {
                if (replayed == 0) {
                    LOGGER.warn("[redpiler] schedule_tick(idx={}, delay={}, prio={}) failed rc={}",
                            idx, delay, prio, rc);
                }
                skipped++;
            } else {
                replayed++;
            }
        }
        if (replayed > 0 || skipped > 0) {
            LOGGER.info("[redpiler] replayed {} pending ticks ({} skipped — block removed during gap) into session #{}",
                    replayed, skipped, s.handle);
        }
    }

    private static net.minecraft.world.ticks.TickPriority mapPriority(int prio) {
        return switch (prio) {
            case 0 -> net.minecraft.world.ticks.TickPriority.EXTREMELY_HIGH;
            case 1 -> net.minecraft.world.ticks.TickPriority.VERY_HIGH;
            case 2 -> net.minecraft.world.ticks.TickPriority.HIGH;
            default -> net.minecraft.world.ticks.TickPriority.NORMAL;
        };
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static final class UnsafeAccess {
        private static final sun.misc.Unsafe U;
        static {
            try {
                java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                f.setAccessible(true);
                U = (sun.misc.Unsafe) f.get(null);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
        static sun.misc.Unsafe theUnsafe() { return U; }
    }

    // ── per-dim aabb list (snapshot-immutable) ────────────────────────────

    public static final class Aabb {
        public final int x0, y0, z0, x1, y1, z1;
        public final long handle;
        Aabb(BlockPos min, BlockPos max, long handle) {
            this.x0 = min.getX(); this.y0 = min.getY(); this.z0 = min.getZ();
            this.x1 = max.getX(); this.y1 = max.getY(); this.z1 = max.getZ();
            this.handle = handle;
        }
        public boolean contains(BlockPos p) {
            return p.getX() >= x0 && p.getX() <= x1
                && p.getY() >= y0 && p.getY() <= y1
                && p.getZ() >= z0 && p.getZ() <= z1;
        }
        public boolean intersects(BlockPos min, BlockPos max) {
            return x0 <= max.getX() && x1 >= min.getX()
                && y0 <= max.getY() && y1 >= min.getY()
                && z0 <= max.getZ() && z1 >= min.getZ();
        }
    }

    private static final class AabbList {
        static final AabbList EMPTY = new AabbList(new Aabb[0]);
        final Aabb[] items;
        AabbList(Aabb[] items) { this.items = items; }
        AabbList add(Aabb a) {
            Aabb[] arr = new Aabb[items.length + 1];
            System.arraycopy(items, 0, arr, 0, items.length);
            arr[items.length] = a;
            return new AabbList(arr);
        }
        AabbList removeHandle(long h) {
            int j = -1;
            for (int i = 0; i < items.length; i++) {
                if (items[i].handle == h) { j = i; break; }
            }
            if (j < 0) return this;
            Aabb[] arr = new Aabb[items.length - 1];
            System.arraycopy(items, 0, arr, 0, j);
            System.arraycopy(items, j + 1, arr, j, items.length - j - 1);
            return new AabbList(arr);
        }
    }

    // ── status ────────────────────────────────────────────────────────────

    public static String status() {
        StringBuilder sb = new StringBuilder();
        sb.append("redpiler enabled=").append(enabled)
          .append(" sessions=").append(SESSIONS.size());
        for (RedpilerSession s : SESSIONS.values()) {
            sb.append("\n  #").append(s.handle)
              .append(' ').append(s.dim.identifier())
              .append(' ').append(s.min).append('↔').append(s.max)
              .append(" nodes=").append(s.nodeBlocks.size())
              .append(" containers=").append(s.containerSources.size())
              .append(" ticked=").append(s.ticksProcessed)
              .append(" boundary=").append(s.boundaryInputPos.size())
              .append(" obsWatch=").append(s.observerWatchers.size())
              .append(" inputs=").append(s.inputUpdates)
              .append(" changes=").append(s.changesDrained)
              .append(" writes=").append(s.writesApplied)
              .append(s.aborted ? " ABORTED" : "");
        }
        return sb.toString();
    }

    /** Per-session human-readable summary lines for {@code /rustmi stats}.
     *  Each line: {@code #handle dim min..max N nodes, T ticks, W writes}. */
    public static java.util.List<String> sessionLines() {
        java.util.ArrayList<String> out = new java.util.ArrayList<>(SESSIONS.size());
        for (RedpilerSession s : SESSIONS.values()) {
            StringBuilder sb = new StringBuilder();
            sb.append('#').append(s.handle)
              .append(' ').append(s.dim.identifier().getPath())
              .append(' ').append(s.min.toShortString()).append("..").append(s.max.toShortString())
              .append("  ").append(s.nodeBlocks.size()).append(" nodes")
              .append(", ").append(s.ticksProcessed).append(" ticks")
              .append(", ").append(s.writesApplied).append(" writes");
            if (s.aborted) sb.append(" (aborted)");
            out.add(sb.toString());
        }
        return out;
    }
}
