package com.github.tatercertified.nalim;

import net.minecraft.server.level.ServerLevel;

/**
 * Hooks for the parity harness, active only with {@code -Drustmi.test=true} (tools/parity sets
 * it for the Rustmi runs). Production servers never register the {@code /rustmi test} commands
 * and every hook is a constant-false check.
 */
public final class TestHooks {

    public static final boolean ENABLED = Boolean.getBoolean("rustmi.test");

    private static volatile boolean explosionSeedArmed;
    private static volatile long explosionSeed;

    private TestHooks() {}

    /** Every explosion from now on starts from this level RNG seed, until disarmed. */
    public static void armExplosionSeed(long seed) {
        explosionSeed = seed;
        explosionSeedArmed = true;
    }

    public static void disarmExplosionSeed() {
        explosionSeedArmed = false;
    }

    /** ServerExplosion.explode HEAD: explosions (chains included) draw the same floats in
     *  two otherwise identical runs, whatever else used the level RNG in between. */
    public static void beforeExplosion(ServerLevel level) {
        if (ENABLED && explosionSeedArmed) {
            level.getRandom().setSeed(explosionSeed);
        }
    }
}
