package com.github.tatercertified.nalim.sections;

import com.github.tatercertified.nalim.cramming.RustmiCramming;
import com.github.tatercertified.nalim.mixin.PalettedContainerDataAccessor;
import com.github.tatercertified.nalim.rust.RustmiLib;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * NativeSections stage 2 — gate + swap point. PalettedContainerMixin routes
 * every RESIDENT section storage (fresh, loaded, repacked) through
 * {@link #swapToNative}; transient storages (serialize/repack temporaries)
 * never reach the Data constructor and stay heap. Disabled state or a native
 * probe failure leaves vanilla untouched — the swap is the only coupling.
 *
 * <p>Toggle: /rustmi sections on|off (persisted; default ON per the
 * everything-on directive 2026-06-12). Toggling affects NEW storages only —
 * live sections keep whatever backing they were born with until their next
 * resize/reload, both backings interoperate freely.
 */
public final class NativeSections {

    private static final Logger LOGGER = LoggerFactory.getLogger("Rustmi-NativeSections");
    private static final Path STATE_FILE = com.github.tatercertified.nalim.RustmiPaths.config("rustmi", "native-sections.properties");

    private static volatile boolean enabled = loadPersisted();
    /** 0 = unprobed, 1 = native lib OK, -1 = probe failed (latched off). */
    private static volatile int libState;

    private NativeSections() {}

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(boolean on) {
        enabled = on;
        persist(on);
    }

    /** Gate + one-time allocator probe (covers nalim link failures). */
    public static boolean usable() {
        if (!enabled) {
            return false;
        }
        int s = libState;
        if (s == 0) {
            s = probe();
        }
        return s > 0;
    }

    private static synchronized int probe() {
        if (libState != 0) {
            return libState;
        }
        try {
            long a = RustmiLib.oxi_section_alloc(16);
            if (a != 0) {
                RustmiLib.oxi_section_free(a, 16);
                // Initialise NativeBitStorage (MAGIC table, Unsafe, Cleaner) once here, so a
                // failure latches off with its cause instead of failing every chunk section.
                new NativeBitStorage(4, 4096).releaseNow();
                libState = 1;
                LOGGER.info("[native-sections] allocator probe OK — section storage goes native");
            } else {
                libState = -1;
                LOGGER.warn("[native-sections] allocator returned 0 on probe — staying on heap");
            }
        } catch (Throwable t) {
            libState = -1;
            LOGGER.warn("[native-sections] native lib unavailable — staying on heap", t);
        }
        return libState;
    }

    /**
     * The mixin's swap point: resident SimpleBitStorage → NativeBitStorage
     * (raw memcpy; the heap original becomes instant eden garbage). Zero-bit
     * storages and already-native ones pass through untouched.
     */
    public static BitStorage swapToNative(BitStorage storage) {
        if (storage instanceof SimpleBitStorage simple && usable()) {
            if (simple.getSize() < MIN_NATIVE_ENTRIES) {
                // Biome storages (64 entries, 1-8 words): a native block plus its Cleaner
                // registration costs more heap than the words it moves off-heap.
                return storage;
            }
            if (NativeBitStorage.liveBytes() >= MAX_NATIVE_BYTES) {
                // No back-pressure from the GC on native memory: past the ceiling new storages
                // stay on the heap (identical results), which in turn lets the GC run the Cleaner.
                return storage;
            }
            try {
                return new NativeBitStorage(simple.getBits(), simple.getSize(), simple.getRaw());
            } catch (OutOfMemoryError | LinkageError | RuntimeException e) {
                // Called while a chunk section is constructed: never propagate, stay on heap.
                LOGGER.error("[native-sections] native storage failed — latching off, staying on heap", e);
                libState = -1;
            }
        }
        return storage;
    }

    /** Block-state storages have 4096 entries, biome storages 64. */
    private static final int MIN_NATIVE_ENTRIES = 1024;

    /** {@code -Drustmi.sections.maxMiB}; default a quarter of the maximum heap. */
    private static final long MAX_NATIVE_BYTES = Long.getLong("rustmi.sections.maxMiB",
            Math.max(64L, Runtime.getRuntime().maxMemory() / 4L / (1024L * 1024L))) * 1024L * 1024L;

    /**
     * True once the allocator probe succeeded. While false no {@link NativeBitStorage} can
     * exist, so the mixin's native fast paths skip straight to vanilla. (After an OOM latch
     * this turns false again while native storages are still alive; those then take the
     * vanilla {@code getRaw()} copy path, which is slower but still correct.)
     */
    public static boolean nativeLive() {
        return libState > 0;
    }

    /**
     * One volatile read of {@code container.data} (the field is volatile in vanilla). The
     * field's type is the private record {@code PalettedContainer.Data}, which can be neither
     * named by an {@code @Accessor} nor {@code @Shadow}ed, so it is read through Unsafe and
     * cast to the Data accessor duck type.
     */
    public static PalettedContainerDataAccessor dataOf(PalettedContainer<?> container) {
        Object data = RustmiCramming.theUnsafe().getObjectVolatile(container, DataField.OFFSET);
        if (data == null) {
            throw new IllegalStateException("PalettedContainer.data is null");
        }
        return (PalettedContainerDataAccessor) data;
    }

    /** Lazy holder: resolved on first {@link #dataOf}, never on the plain swap path. */
    private static final class DataField {
        static final long OFFSET;

        static {
            try {
                OFFSET = RustmiCramming.theUnsafe()
                        .objectFieldOffset(PalettedContainer.class.getDeclaredField("data"));
            } catch (NoSuchFieldException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }

    /** Compact one-line summary for {@code /rustmi stats}. Returns the
     *  live memory + block count without the ON/OFF prefix (the stats
     *  command renders the toggle separately). Empty string if the
     *  native lib failed or there's nothing live yet. */
    public static String statusShort() {
        if (libState < 0) return "lib failed";
        try {
            sun.misc.Unsafe u = RustmiCramming.theUnsafe();
            long scratch = u.allocateMemory(16);
            try {
                RustmiLib.oxi_section_stats(scratch);
                long bytes = u.getLong(scratch);
                long blocks = u.getLong(scratch + 8);
                if (blocks == 0) return "";
                return (bytes >> 20) + " MiB across " + blocks + " blocks";
            } finally {
                u.freeMemory(scratch);
            }
        } catch (Throwable t) {
            return "";
        }
    }

    private static boolean loadPersisted() {
        // Default ON (user directive 2026-06-12). Coexistence with Lithium:
        // Lithium's chunk.serialization.PalettedContainerMixin replaces
        // PalettedContainer.pack with a path that casts BitStorage to its
        // own CompactingPackedIntegerArray interface — NativeBitStorage
        // doesn't implement that interface so the cast throws on every
        // chunk save. Mitigation: src/main/resources/fabric.mod.json declares
        // `lithium:options.chunk.serialization=false` so the offending
        // Lithium mixin never applies and vanilla pack() (which doesn't
        // cast) runs instead. Don't drop that option or chunks stop saving.
        if (!Files.exists(STATE_FILE)) {
            return true;
        }
        try (var in = Files.newInputStream(STATE_FILE)) {
            Properties p = new Properties();
            p.load(in);
            return Boolean.parseBoolean(p.getProperty("enabled", "true"));
        } catch (IOException e) {
            return true;
        }
    }

    private static void persist(boolean on) {
        try {
            Files.createDirectories(STATE_FILE.getParent());
            Properties p = new Properties();
            p.setProperty("enabled", Boolean.toString(on));
            try (var out = Files.newOutputStream(STATE_FILE)) {
                p.store(out, "Rustmi NativeSections (section block storage in native memory)");
            }
        } catch (IOException e) {
            LOGGER.warn("[native-sections] failed to persist state", e);
        }
    }
}
