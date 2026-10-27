package com.github.tatercertified.nalim.spawncap;

import com.github.tatercertified.nalim.cramming.RustmiCramming;
import com.github.tatercertified.nalim.rust.RustmiLib;

import java.nio.ByteBuffer;

/**
 * Public surface for Fabrolia's mob-cap arena (reached via the {@code SpawnCapBridge}
 * MethodHandle bridge). The off-heap, Send+Sync Rust replacement for
 * {@code NaturalSpawner.SpawnState} + {@code LocalMobCapCalculator} + {@code PotentialCalculator}:
 * built once per level tick, then read / atomically incremented lock-free by the parallel
 * spawn workers. No HashMap on the concurrent path ⇒ the {@code getPlayersNear} computeIfAbsent
 * race class cannot exist.
 *
 * <p>Record layouts the caller must marshal into direct buffers (little-endian, matching
 * {@code native/src/util/spawncap/ffi.rs}):
 * <ul>
 *   <li>entity, 32 B: {@code charge f64@0, catOrd i32@8, bx i32@12, by i32@16, bz i32@20,
 *       flags i32@24} (flags bit0=isMob, bit1=hasCharge), pad@28</li>
 *   <li>player, 24 B: {@code x f64@0, z f64@8, spectator i32@16}, pad@20</li>
 *   <li>caps: 8 × i32 (MobCategory.getMaxInstancesPerChunk per ordinal)</li>
 * </ul>
 * Pointers cross as long addresses; only primitives + the handle traverse the boundary.
 */
public final class SpawnCap {

    /** MobCategory count the native arena is built for (N_CATEGORIES in native/src/util/spawncap/mod.rs). */
    public static final int N_CATEGORIES = 8;

    private SpawnCap() {}

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("Rustmi-SpawnCap");
    private static volatile boolean buildFailureLogged;

    /** @return arena handle, or 0 on failure → caller uses its Java SpawnState fallback. */
    public static long build(ByteBuffer entities, int nEnt,
                             ByteBuffer players, int nPlay,
                             int spawnableChunks, ByteBuffer caps) {
        try {
            return RustmiLib.oxi_spawncap_build(
                    RustmiCramming.addressOf(entities), nEnt,
                    RustmiCramming.addressOf(players), nPlay,
                    spawnableChunks, RustmiCramming.addressOf(caps));
        } catch (Throwable t) {
            // Log ONCE — a silent 0 here means every tick quietly declines to the Java
            // path and a real linkage/ABI failure would otherwise be invisible.
            if (!buildFailureLogged) {
                buildFailureLogged = true;
                LOGGER.warn("oxi_spawncap_build failed — Rust local-cap arena disabled, using the Java path", t);
            }
            return 0L;
        }
    }

    /** vanilla canSpawnForCategoryGlobal → 1 allowed / 0 capped / -1 bad. */
    public static int canGlobal(long handle, int cat) {
        return RustmiLib.oxi_spawncap_can_global(handle, cat);
    }

    /** vanilla LocalMobCapCalculator.canSpawn → 1 allowed / 0 capped|no-near / -1 bad. */
    public static int canLocal(long handle, int cat, int cx, int cz) {
        return RustmiLib.oxi_spawncap_can_local(handle, cat, cx, cz);
    }

    /** Fabrolia fused local-cap check + pack reservation: 1 reserved / 0 capped / -1 bad. */
    public static int tryReservePack(long handle, int cat, int cx, int cz, int count) {
        return RustmiLib.oxi_spawncap_try_reserve_pack(handle, cat, cx, cz, count);
    }

    /** Return an unconsumed Fabrolia pack reservation: 0 success / -1 bad. */
    public static int releasePack(long handle, int cat, int cx, int cz, int count) {
        return RustmiLib.oxi_spawncap_release_pack(handle, cat, cx, cz, count);
    }

    /** vanilla afterSpawn (global +1, addMob to near players). Safe to call concurrently. */
    public static int add(long handle, int cat, int cx, int cz, int isMob) {
        return RustmiLib.oxi_spawncap_add(handle, cat, cx, cz, isMob);
    }

    /** vanilla PotentialCalculator.getPotentialEnergyChange(pos, charge). */
    public static double potential(long handle, int x, int y, int z, double charge) {
        return RustmiLib.oxi_spawncap_potential(handle, x, y, z, charge);
    }

    /** Reclaim the arena (tick end). */
    public static int free(long handle) {
        return RustmiLib.oxi_spawncap_free(handle);
    }
}
