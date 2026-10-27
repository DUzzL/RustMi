package com.github.tatercertified.nalim.spawncap;

import com.axalotl.fabrolia.common.utils.LocalCapBridge;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobCategory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Rustmi's implementation of Fabrolia's {@link LocalCapBridge.Provider}: the LOCAL per-player
 * mob cap on an off-heap, Send+Sync Rust arena ({@code util::spawncap}). Built once per level
 * tick on the server thread, then read / atomically incremented lock-free by the parallel spawn
 * workers — no concurrent HashMap on the hot path, so the {@code getPlayersNear} computeIfAbsent
 * race class cannot exist. Owns all Rust marshalling; Fabrolia only wires the seam.
 *
 * <p>Loaded ONLY after {@code SpawnCapHooks} probes that Fabrolia's {@link LocalCapBridge} is
 * present (this class references it), mirroring {@code RegionalCramming}/{@code RegionTickHooks}.
 *
 * <p>Vanilla parity: the arena is built with players + per-category caps + the tick's INITIAL
 * mobs (packed chunk + category, marshalled into the 32-byte entity records) — the Rust build
 * loop seeds the local counts exactly as vanilla createState's per-mob {@code addMob} replay
 * would (same near-player predicate), in one native pass instead of one FFI call per mob
 * (that replay was 4.1% of the server thread at 51 players). Post-build {@code addMob} calls
 * (afterSpawn) still route here per spawn. Global cap stays vanilla Java.
 *
 * <p>Fabrolia pack reservations use the same all-near-player atomic counters:
 * reserve {@code +count} before a concurrent chunk pass and release it after
 * vanilla {@code addMob} has recorded the pack's real spawns.
 */
public final class RustLocalCapProvider implements LocalCapBridge.Provider {

    public static final RustLocalCapProvider INSTANCE = new RustLocalCapProvider();

    private static final int PLY_STRIDE = 24; // x f64@0, z f64@8, spectator i32@16, pad@20
    // Entity record (ffi.rs): charge f64@0, catOrd i32@8, bx i32@12, by i32@16, bz i32@20,
    // flags i32@24 (bit0 isMob, bit1 hasCharge), pad@28.
    private static final int ENT_STRIDE = 32;

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("Rustmi");

    private volatile boolean enabled = true;

    // Marshalling buffers, reused across calls; Rust copies the input into the arena during
    // build(). buildArena is synchronized so two levels building at once cannot share them.
    private ByteBuffer playerBuf;
    private ByteBuffer capsBuf;
    private ByteBuffer entBuf;

    private RustLocalCapProvider() {}

    /**
     * Registers {@link #INSTANCE} with Fabrolia's seam. Lives here, not in
     * {@code SpawnCapHooks}: a {@code LocalCapBridge.register(INSTANCE)} call site makes the
     * verifier prove {@code RustLocalCapProvider <: LocalCapBridge.Provider}, which loads the
     * Fabrolia interface while the caller class links — before any try/catch can run.
     */
    static void registerWithBridge() {
        LocalCapBridge.register(INSTANCE);
    }

    public void setEnabled(boolean e) {
        this.enabled = e;
    }

    public boolean isEnabled() {
        return enabled;
    }


    @Override
    public boolean supportsPackReservation() {
        return true;
    }
    @Override
    public synchronized long buildArena(ServerLevel level, long[] mobChunks, int[] mobCategories, int mobCount) {
        if (!enabled) {
            return 0L; // decline → Fabrolia uses its Java LocalMobCapCalculator
        }
        if (MobCategory.values().length != SpawnCap.N_CATEGORIES) {
            // The native arena has a fixed category count; a new MobCategory would shift
            // every cap and count. Checked here, not at registration (onLoad must not load
            // Minecraft classes).
            enabled = false;
            LOGGER.error("[spawncap] {} mob categories, native arena expects {} — Rust local cap disabled",
                    MobCategory.values().length, SpawnCap.N_CATEGORIES);
            return 0L;
        }
        List<ServerPlayer> players = level.players();
        int np = players.size();
        int needP = Math.max(1, np) * PLY_STRIDE;
        if (playerBuf == null || playerBuf.capacity() < needP) {
            playerBuf = ByteBuffer.allocateDirect(Math.max(needP, PLY_STRIDE * 16)).order(ByteOrder.nativeOrder());
        }
        ByteBuffer pb = playerBuf;
        for (int i = 0; i < np; i++) {
            ServerPlayer p = players.get(i);
            int base = i * PLY_STRIDE;
            pb.putDouble(base, p.getX());
            pb.putDouble(base + 8, p.getZ());
            pb.putInt(base + 16, p.isSpectator() ? 1 : 0);
            pb.putInt(base + 20, 0);
        }
        MobCategory[] cats = MobCategory.values();
        if (capsBuf == null) {
            capsBuf = ByteBuffer.allocateDirect(cats.length * 4).order(ByteOrder.nativeOrder());
        }
        for (int i = 0; i < cats.length; i++) {
            capsBuf.putInt(i * 4, cats[i].getMaxInstancesPerChunk());
        }
        // Initial-mob seed: block coords = chunk*16, so Rust's cx = bx >> 4 recovers the exact
        // chunk (arithmetic shift — exact for negatives on a *16 multiple). isMob=1 seeds the
        // local counts; hasCharge=0 — the PotentialCalculator stays Java.
        int n = Math.max(0, mobCount);
        // Vanilla's addMob replay reaches no player when getPlayersCloseForSpawning returns
        // the empty list, i.e. when the natural-spawn ticket tracker says FALSE (> 8 chunks)
        // — even if a player's live position is within 128 blocks (tracker lag after a
        // teleport). Rust only applies the 128-block test, so such seeds are dropped here.
        // This provider only exists under Fabrolia, whose DistanceManager reads the
        // concurrent tracker map without running a wave — the same call its own Java
        // createState fallback makes from region workers.
        var distanceManager = level.getChunkSource().chunkMap.getDistanceManager();
        long lastChunk = Long.MIN_VALUE;
        boolean lastNear = true;
        ByteBuffer eb;
        if (n > 0) {
            int needE = n * ENT_STRIDE;
            if (entBuf == null || entBuf.capacity() < needE) {
                entBuf = ByteBuffer.allocateDirect(Math.max(needE, ENT_STRIDE * 256)).order(ByteOrder.nativeOrder());
            }
            int kept = 0;
            for (int i = 0; i < n; i++) {
                long packed = mobChunks[i];
                if (packed != lastChunk) {
                    lastChunk = packed;
                    lastNear = distanceManager.hasPlayersNearby(packed) != net.minecraft.util.TriState.FALSE;
                }
                if (!lastNear) {
                    continue;
                }
                int base = kept++ * ENT_STRIDE;
                entBuf.putDouble(base, 0.0);
                entBuf.putInt(base + 8, mobCategories[i]);
                entBuf.putInt(base + 12, net.minecraft.world.level.ChunkPos.getX(packed) << 4);
                entBuf.putInt(base + 16, 0);
                entBuf.putInt(base + 20, net.minecraft.world.level.ChunkPos.getZ(packed) << 4);
                entBuf.putInt(base + 24, 1);
                entBuf.putInt(base + 28, 0);
            }
            n = kept;
        }
        if (n == 0) {
            eb = capsBuf; // dummy pointer, n_ent=0 → never dereferenced
        } else {
            eb = entBuf;
        }
        return SpawnCap.build(eb, n, pb, np, 0, capsBuf);
    }

    @Override
    public int canSpawn(long handle, int categoryOrdinal, int chunkX, int chunkZ) {
        return SpawnCap.canLocal(handle, categoryOrdinal, chunkX, chunkZ);
    }

    @Override
    public int tryReservePack(long handle, int categoryOrdinal, int chunkX, int chunkZ, int count) {
        return SpawnCap.tryReservePack(handle, categoryOrdinal, chunkX, chunkZ, count);
    }

    @Override
    public void releasePack(long handle, int categoryOrdinal, int chunkX, int chunkZ, int count) {
        if (SpawnCap.releasePack(handle, categoryOrdinal, chunkX, chunkZ, count) != 0) {
            throw new IllegalStateException("native local-cap reservation release failed");
        }
    }

    @Override
    public void addMob(long handle, int categoryOrdinal, int chunkX, int chunkZ) {
        if (SpawnCap.add(handle, categoryOrdinal, chunkX, chunkZ, 1) != 0) {
            throw new IllegalStateException("native local-cap addMob failed");
        }
    }

    @Override
    public void free(long handle) {
        if (SpawnCap.free(handle) != 0) {
            throw new IllegalStateException("native local-cap free failed");
        }
    }
}
