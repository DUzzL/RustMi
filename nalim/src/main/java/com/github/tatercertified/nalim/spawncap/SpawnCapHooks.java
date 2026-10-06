package com.github.tatercertified.nalim.spawncap;

import com.github.tatercertified.rustmi.Rustmi;

/**
 * Registers Rustmi's Rust {@link RustLocalCapProvider} into Fabrolia's {@code LocalCapBridge}
 * seam — the symbiosis point, mirror of {@code CrammingHooks} probing {@code RegionTickHooks}.
 * Probes for the seam class first; {@link RustLocalCapProvider} (which references it) is only
 * loaded inside the successful branch, so nothing breaks when Fabrolia is absent.
 */
public final class SpawnCapHooks {

    private static volatile boolean registered = false;

    private SpawnCapHooks() {}

    public static void register() {
        try {
            Class.forName("com.axalotl.fabrolia.common.utils.LocalCapBridge");
            RustLocalCapProvider.registerWithBridge();
            registered = true;
            Rustmi.LOGGER.info("Fabrolia LocalCapBridge detected — Rust local mob-cap provider registered " +
                    "(off-heap arena; /rustmi spawncap to toggle).");
        } catch (ClassNotFoundException e) {
            Rustmi.LOGGER.info("Fabrolia LocalCapBridge not present — Rust local mob-cap disabled.");
        } catch (Throwable t) {
            Rustmi.LOGGER.error("[Rustmi] failed to register Rust local mob-cap provider", t);
        }
    }

    // Toggle surface for /rustmi spawncap. The `registered &&` short-circuit means
    // RustLocalCapProvider (which references Fabrolia's LocalCapBridge) is never loaded when
    // Fabrolia is absent — safe to call from RustmiCommands unconditionally.
    public static boolean isEnabled() {
        return registered && RustLocalCapProvider.INSTANCE.isEnabled();
    }

    public static void setEnabled(boolean e) {
        if (registered) {
            RustLocalCapProvider.INSTANCE.setEnabled(e);
        }
    }
}
