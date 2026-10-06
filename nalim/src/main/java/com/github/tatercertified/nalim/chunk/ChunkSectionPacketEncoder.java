package com.github.tatercertified.nalim.chunk;

import com.github.tatercertified.nalim.mixin.LevelChunkSectionAccessor;
import com.github.tatercertified.nalim.mixin.PalettedContainerAccessor;
import com.github.tatercertified.nalim.mixin.PalettedContainerDataAccessor;
import com.github.tatercertified.nalim.sections.NativeBitStorage;
import com.github.tatercertified.nalim.sections.NativeSections;
import com.github.tatercertified.nalim.cramming.RustmiCramming;
import com.github.tatercertified.nalim.rust.RustmiLib;
import net.minecraft.core.IdMap;
import net.minecraft.core.Holder;
import net.minecraft.network.VarInt;
import net.minecraft.util.BitStorage;
import net.minecraft.util.ZeroBitStorage;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.HashMapPalette;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LinearPalette;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.SingleValuePalette;
import net.minecraft.world.level.chunk.Strategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Java-only descriptor builder — the first layer of the future Rust chunk-section
 * packet encoder. Inspects a {@link LevelChunkSection} and gathers <b>every wire
 * byte's worth of metadata</b> a Rust kernel would need, but does <b>not</b> write
 * any packet bytes and does <b>not</b> call any native function.
 *
 * <h2>What it proves</h2>
 * That all of the section's serialisable state is reachable and classifiable:
 * the two count shorts, each container's bits/words/palette-mode/global-ids, and
 * whether the storage is native-backed (so Rust can read it by address later).
 *
 * <h2>Why this avoids {@code getRaw()}</h2>
 * Vanilla's {@code PalettedContainer.getSerializedSize()} and
 * {@code Data.write()} both evaluate {@code storage.getRaw()}. On a
 * {@link NativeBitStorage} that materialises a fresh {@code long[]} — exactly the
 * per-section allocation this whole effort removes. This builder instead reads
 * {@code NativeBitStorage.words()} / {@code address()} and computes the wire size
 * from {@code words * 8}, never touching the heap-copy path. The self-check
 * ({@link #verifyAgainstVanilla}) deliberately <i>does</i> call vanilla's size
 * method (allocating, once) to confirm the two agree — that is a debug oracle, not
 * the production path.
 *
 * <h2>Fallback policy</h2>
 * A section is {@code supported} only when <b>both</b> containers are native- or
 * zero-backed with a recognised palette. Anything else — heap
 * {@code SimpleBitStorage} (NativeSections off / transient), biomes that are not a
 * {@code PalettedContainer}, or an unknown {@link Palette} subclass — yields
 * {@code supported == false} with a reason; the caller keeps the vanilla
 * {@code section.write} path for that section. No partial output, no behaviour
 * change.
 *
 * <p>Generic types are erased internally (raw {@link Strategy}/{@link IdMap}/
 * {@link Palette}) because the container's type parameter cannot be threaded
 * through the accessor duck-types; runtime values still come from each
 * container's own {@code strategy.globalMap()}, so id resolution is correct.
 */
public final class ChunkSectionPacketEncoder {

    private ChunkSectionPacketEncoder() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("Rustmi-ChunkEncoder");
    private static final int[] EMPTY_IDS = new int[0];
    private static final AtomicBoolean DISABLE_LOGGED = new AtomicBoolean();
    private static volatile boolean disabled;

    /**
     * Lithium's {@code chunk.palette} option swaps the vanilla 3-8 bit Linear/HashMap
     * configurations for its own palette. Its wire form ({@code write}/{@code getSerializedSize})
     * is the HashMapPalette one: varint size, then one varint global id per index. Matched by
     * name so nalim keeps no compile dependency on Lithium.
     */
    private static final String LITHIUM_HASH_PALETTE =
            "net.caffeinemc.mods.lithium.common.world.chunk.LithiumHashPalette";

    /**
     * Gather the wire descriptor for one section. Returns {@code supported == false}
     * only for expected native-encoder coverage gaps (heap storage, unsupported
     * palette/container shape). Accessor, mapping, or invariant failures are bugs
     * and must throw instead of silently falling back to vanilla.
     */
    public static SectionDescriptor describe(LevelChunkSection section) {
        LevelChunkSectionAccessor sa = (LevelChunkSectionAccessor) section;
        int nonEmpty = sa.rustmi$nonEmptyBlockCount() & 0xFFFF;
        int fluid = sa.rustmi$fluidCount() & 0xFFFF;

        PalettedContainer<BlockState> statesContainer = section.getStates();
        PalettedContainerRO<Holder<Biome>> biomesRO = section.getBiomes();

        ContainerDescriptor states = describeContainer(statesContainer);
        ContainerDescriptor biomes = (biomesRO instanceof PalettedContainer)
                ? describeContainer((PalettedContainer<?>) biomesRO)
                : ContainerDescriptor.unsupported("biomes is not PalettedContainer (" + biomesRO.getClass().getName() + ")");

        if (!states.supported) {
            return SectionDescriptor.unsupported("states: " + states.reason, nonEmpty, fluid, states, biomes);
        }
        if (!biomes.supported) {
            return SectionDescriptor.unsupported("biomes: " + biomes.reason, nonEmpty, fluid, states, biomes);
        }
        int expected = 4 + states.expectedContainerSize + biomes.expectedContainerSize;
        return SectionDescriptor.ok(nonEmpty, fluid, states, biomes, expected);
    }

    /**
     * Inspect one container. Raw generics throughout: the container's
     * {@code strategy.globalMap()} is the authoritative id source regardless of T.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ContainerDescriptor describeContainer(PalettedContainer<?> container) {
        PalettedContainerAccessor acc = (PalettedContainerAccessor) container;
        PalettedContainerDataAccessor data = NativeSections.dataOf(container);
        BitStorage storage = data.rustmi$storage();
        Palette palette = data.rustmi$palette();
        if (storage == null || palette == null) {
            throw new IllegalStateException("PalettedContainer.Data has null storage/palette");
        }
        Strategy strategy = (Strategy) acc.rustmi$strategy();
        IdMap globalMap = strategy.globalMap();

        int bits = storage.getBits();
        int entryCount = storage.getSize();

        // --- storage backing ---
        boolean nativeBacked;
        long nativeAddress;
        int words;
        if (storage instanceof ZeroBitStorage) {
            nativeBacked = false;
            nativeAddress = 0L;
            words = 0;
        } else if (storage instanceof NativeBitStorage nbs) {
            nativeBacked = true;
            nativeAddress = nbs.address();
            words = nbs.words();
        } else {
            // Heap SimpleBitStorage (NativeSections off, or a transient save-path
            // storage). Not passed to Rust in this layer — GC-relocation hazard.
            return ContainerDescriptor.unsupported("heap BitStorage: " + storage.getClass().getName());
        }

        // --- palette mode + global ids (vanilla order) ---
        PaletteMode mode;
        int[] globalIds;
        if (palette instanceof GlobalPalette) {
            mode = PaletteMode.GLOBAL;
            globalIds = EMPTY_IDS;            // no palette body on the wire
        } else if (palette instanceof SingleValuePalette) {
            mode = PaletteMode.SINGLE;
            Object value = palette.valueFor(0);
            globalIds = new int[]{globalMap.getId(value)};
        } else if (palette instanceof LinearPalette) {
            mode = PaletteMode.LINEAR;
            globalIds = collectIds(palette, globalMap);
        } else if (palette instanceof HashMapPalette
                || palette.getClass().getName().equals(LITHIUM_HASH_PALETTE)) {
            mode = PaletteMode.HASHMAP;
            globalIds = collectIds(palette, globalMap);
        } else {
            return ContainerDescriptor.unsupported("unknown palette: " + palette.getClass().getName());
        }

        // --- expected wire size, allocation-free (no getRaw) ---
        int paletteBytes;
        switch (mode) {
            case SINGLE -> paletteBytes = VarInt.getByteSize(globalIds[0]);
            case LINEAR, HASHMAP -> {
                int pb = VarInt.getByteSize(globalIds.length);   // size prefix
                for (int id : globalIds) {
                    pb += VarInt.getByteSize(id);
                }
                paletteBytes = pb;
            }
            case GLOBAL -> paletteBytes = 0;                       // no body
            default -> {
                throw new AssertionError("Unhandled palette mode: " + mode);
            }
        }
        int expectedContainerSize = 1 + paletteBytes + words * 8;  // bits-byte + palette + data longs (BE)
        return ContainerDescriptor.ok(mode, bits, words, entryCount, nativeBacked, nativeAddress,
                nativeBacked ? storage : null, globalIds, expectedContainerSize);
    }

    @SuppressWarnings("unchecked")
    private static int[] collectIds(Palette palette, IdMap globalMap) {
        int n = palette.getSize();
        int[] ids = new int[n];
        for (int i = 0; i < n; i++) {
            ids[i] = globalMap.getId(palette.valueFor(i));
        }
        return ids;
    }

    // ------------------------------------------------------------------
    // Self-check: our allocation-free size vs vanilla's allocating size
    // ------------------------------------------------------------------

    /**
     * Confirm the descriptor's computed sizes match what vanilla would emit.
     * Returns {@code true} iff every figure agrees. This calls vanilla
     * {@code getSerializedSize()} (which internally reads {@code storage.getRaw()}
     * — the allocation the production descriptor avoids) so it is a debug oracle,
     * not for the hot send path.
     */
    public static boolean verifyAgainstVanilla(LevelChunkSection section, SectionDescriptor d) {
        if (!d.supported) {
            return false;
        }
        if (section.getSerializedSize() != d.expectedSerializedSize) {
            return false;
        }
        PalettedContainer<BlockState> states = section.getStates();
        if (states.getSerializedSize() != d.states.expectedContainerSize) {
            return false;
        }
        if (section.getBiomes() instanceof PalettedContainer<?> biomes
                && biomes.getSerializedSize() != d.biomes.expectedContainerSize) {
            return false;
        }
        return true;
    }

    /** Lightweight stats over a chunk's sections for a one-shot validation pass. */
    public static final class CheckSummary {
        public final int total;
        public final int supported;
        public final int unsupported;
        public final int sizeMismatches;
        public final int nativeBacked;

        public CheckSummary(int total, int supported, int unsupported, int sizeMismatches, int nativeBacked) {
            this.total = total;
            this.supported = supported;
            this.unsupported = unsupported;
            this.sizeMismatches = sizeMismatches;
            this.nativeBacked = nativeBacked;
        }

        @Override
        public String toString() {
            return "sections: total=" + total + " supported=" + supported
                    + " unsupported=" + unsupported + " nativeBacked=" + nativeBacked
                    + " sizeMismatches=" + sizeMismatches;
        }
    }

    /**
     * Describe + verify every section in a column; counts supported/unsupported,
     * native-backed, and any size disagreements with vanilla. Safe to call on the
     * hot path is NOT intended — it invokes the allocating vanilla size oracle per
     * section for verification.
     */
    public static CheckSummary checkSections(LevelChunkSection[] sections) {
        int supported = 0, unsupported = 0, mismatches = 0, nativeBacked = 0;
        for (LevelChunkSection section : sections) {
            if (section == null) {
                continue;
            }
            SectionDescriptor d = describe(section);
            if (d.supported) {
                supported++;
                if (d.states.nativeBacked || d.biomes.nativeBacked) {
                    nativeBacked++;
                }
                if (!verifyAgainstVanilla(section, d)) {
                    mismatches++;
                }
            } else {
                unsupported++;
            }
        }
        return new CheckSummary(sections.length, supported, unsupported, mismatches, nativeBacked);
    }

    // ==================================================================
    // Native encoder bridge — DEBUG ONLY, not wired into the chunk-send path.
    // Encodes a whole column in one FFI call; the future hot-path integration
    // (Fabrolia PlayerChunkSender prebuild) is a separate step.
    // ==================================================================

    /** Packed section-record stride — MUST match Rust's {@code SECTION_STRIDE}
     *  ({@link RustmiLib#oxi_chunk_section_record_size()}). */
    public static final int SECTION_STRIDE = 64;

    // Field offsets inside one 64-byte section record (mirror native chunk/mod.rs).
    private static final int REC_STATES_ADDR = 0;
    private static final int REC_BIOMES_ADDR = 8;
    private static final int REC_NON_EMPTY = 16;
    private static final int REC_FLUID = 20;
    private static final int REC_STATES_BITS = 24;
    private static final int REC_STATES_WORDS = 28;
    private static final int REC_STATES_MODE = 32;
    private static final int REC_STATES_PAL_OFF = 36;
    private static final int REC_STATES_PAL_COUNT = 40;
    private static final int REC_BIOMES_BITS = 44;
    private static final int REC_BIOMES_WORDS = 48;
    private static final int REC_BIOMES_MODE = 52;
    private static final int REC_BIOMES_PAL_OFF = 56;
    private static final int REC_BIOMES_PAL_COUNT = 60;

    /**
     * Total wire bytes required to encode the supported sections, or {@code -1} if
     * any section is unsupported (caller must keep vanilla {@code section.write} for
     * the whole column — the kernel is all-or-nothing per call).
     */
    public static int requiredBytes(LevelChunkSection[] sections) {
        if (sections == null) {
            return -1;
        }
        int total = 0;
        for (LevelChunkSection s : sections) {
            if (s == null) {
                continue;
            }
            SectionDescriptor d = describe(s);
            if (!d.supported) {
                return -1;
            }
            total += d.expectedSerializedSize;
        }
        return total;
    }

    /**
     * Debug-only native encode of a chunk column into {@code out}. Allocates scratch
     * direct buffers per call (acceptable for a diagnostic; a hot-path variant would
     * pool them and avoid the final native→heap copy).
     *
     * <p>Contract: every section must be {@code describe(...).supported} (native- or
     * zero-backed storage, recognised palette). If any is not, or {@code out} is too
     * small, or the native chunk encoder is disabled/unavailable, returns
     * {@code -1} <b>without touching {@code out}</b> — the caller keeps vanilla.
     * The disable path logs once for this server run.
     *
     * @return written bytes {@code >= 0}, or {@code -1} only when the section set is
     *         outside the native encoder's supported coverage or {@code out} is too
     *         small, or when this optional encoder was disabled after an internal
     *         native/accessor failure.
     */
    public static int tryEncodeNative(LevelChunkSection[] sections, byte[] out) {
        if (disabled) {
            return -1;
        }
        if (sections == null || out == null) {
            return -1;
        }
        int n = 0;
        for (LevelChunkSection s : sections) {
            if (s != null) {
                n++;
            }
        }
        if (n == 0) {
            return 0;
        }

        SectionDescriptor[] descs = new SectionDescriptor[sections.length];
        int totalIds = 0;
        int totalBytes = 0;
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection s = sections[i];
            if (s == null) {
                descs[i] = null;
                continue;
            }
            SectionDescriptor d;
            try {
                d = describe(s);
            } catch (ClassCastException | LinkageError e) {
                disable("descriptor access failed", e);
                return -1;
            }
            if (!d.supported) {
                return -1;   // don't build buffers, don't call native
            }
            descs[i] = d;
            totalBytes += d.expectedSerializedSize;
            totalIds += d.states.globalIds.length + d.biomes.globalIds.length;
        }
        if (out.length < totalBytes) {
            return -1;
        }

        sun.misc.Unsafe U = RustmiCramming.theUnsafe();
        ByteBuffer header = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder());
        ByteBuffer secBuf = ByteBuffer.allocateDirect(n * SECTION_STRIDE).order(ByteOrder.nativeOrder());
        ByteBuffer idBuf = ByteBuffer.allocateDirect(Math.max(4, totalIds * 4)).order(ByteOrder.nativeOrder());
        ByteBuffer outDirect = ByteBuffer.allocateDirect(totalBytes).order(ByteOrder.nativeOrder());
        long headerAddr = RustmiCramming.addressOf(header);
        long secAddr = RustmiCramming.addressOf(secBuf);
        long idAddr = RustmiCramming.addressOf(idBuf);
        long outAddr = RustmiCramming.addressOf(outDirect);

        U.putInt(headerAddr, n);   // nSections

        int idCursor = 0;
        int secIdx = 0;
        for (int i = 0; i < sections.length; i++) {
            SectionDescriptor d = descs[i];
            if (d == null) {
                continue;
            }
            long base = secAddr + (long) secIdx * SECTION_STRIDE;
            int sOff = idCursor;
            for (int id : d.states.globalIds) {
                U.putInt(idAddr + (long) idCursor * 4, id);
                idCursor++;
            }
            int bOff = idCursor;
            for (int id : d.biomes.globalIds) {
                U.putInt(idAddr + (long) idCursor * 4, id);
                idCursor++;
            }
            writeSectionRecord(U, base, d, sOff, bOff);
            secIdx++;
        }

        int nativeStride;
        try {
            nativeStride = RustmiLib.oxi_chunk_section_record_size();
        } catch (LinkageError e) {
            disable("native symbols unavailable", e);
            return -1;
        }
        if (SECTION_STRIDE != nativeStride) {
            disable("chunk section record stride mismatch: java=" + SECTION_STRIDE
                    + " native=" + nativeStride, null);
            return -1;
        }
        int written;
        try {
            written = RustmiLib.oxi_chunk_section_encode(headerAddr, secAddr, idAddr, outAddr, totalBytes);
        } catch (LinkageError e) {
            disable("native encode symbol unavailable", e);
            return -1;
        }
        if (written < 0) {
            disable("native chunk-section encoder rejected a Java-supported descriptor", null);
            return -1;
        }
        if (written > totalBytes) {
            disable("native chunk-section encoder overflowed declared size: "
                    + written + " > " + totalBytes, null);
            return -1;
        }
        // Rust wrote into the direct buffer; copy the written prefix to the heap out[].
        U.copyMemory(null, outAddr, out, sun.misc.Unsafe.ARRAY_BYTE_BASE_OFFSET, written);
        // Everything above was addressed through raw pointers only: keep the direct
        // buffers and the storages behind the descriptors alive across the native call.
        java.lang.ref.Reference.reachabilityFence(descs);
        java.lang.ref.Reference.reachabilityFence(header);
        java.lang.ref.Reference.reachabilityFence(secBuf);
        java.lang.ref.Reference.reachabilityFence(idBuf);
        java.lang.ref.Reference.reachabilityFence(outDirect);
        return written;
    }

    private static void disable(String reason, Throwable cause) {
        disabled = true;
        if (DISABLE_LOGGED.compareAndSet(false, true)) {
            if (cause == null) {
                LOGGER.error("[chunk-encoder] native encoder disabled; vanilla section.write fallback will be used: {}", reason);
            } else {
                LOGGER.error("[chunk-encoder] native encoder disabled; vanilla section.write fallback will be used: {}", reason, cause);
            }
        }
    }

    private static void writeSectionRecord(sun.misc.Unsafe U, long base, SectionDescriptor d,
                                           int statesIdOff, int biomesIdOff) {
        U.putLong(base + REC_STATES_ADDR, d.states.nativeAddress);
        U.putLong(base + REC_BIOMES_ADDR, d.biomes.nativeAddress);
        U.putInt(base + REC_NON_EMPTY, d.nonEmptyBlockCount);
        U.putInt(base + REC_FLUID, d.fluidCount);
        U.putInt(base + REC_STATES_BITS, d.states.bits);
        U.putInt(base + REC_STATES_WORDS, d.states.words);
        U.putInt(base + REC_STATES_MODE, wireMode(d.states.mode));
        U.putInt(base + REC_STATES_PAL_OFF, statesIdOff);
        U.putInt(base + REC_STATES_PAL_COUNT, d.states.globalIds.length);
        U.putInt(base + REC_BIOMES_BITS, d.biomes.bits);
        U.putInt(base + REC_BIOMES_WORDS, d.biomes.words);
        U.putInt(base + REC_BIOMES_MODE, wireMode(d.biomes.mode));
        U.putInt(base + REC_BIOMES_PAL_OFF, biomesIdOff);
        U.putInt(base + REC_BIOMES_PAL_COUNT, d.biomes.globalIds.length);
    }

    private static int wireMode(PaletteMode mode) {
        return switch (mode) {
            case SINGLE -> 0;
            case LINEAR -> 1;
            case HASHMAP -> 2;
            case GLOBAL -> 3;
        };
    }
}
