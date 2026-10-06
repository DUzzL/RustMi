package com.github.tatercertified.nalim.chunk;

/**
 * Wire metadata for one {@code PalettedContainer} (block states or biomes) of a
 * section, as gathered by {@link ChunkSectionPacketEncoder}. Read-only; carries
 * everything the future Rust encoder needs to emit this container's bytes
 * <b>without</b> calling {@code storage.getRaw()} and <b>without</b> re-resolving
 * palette ids at encode time.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code mode} — palette wire shape; {@code null} when {@code !supported}.</li>
 *   <li>{@code bits} — {@code storage.getBits()}, i.e. the exact byte vanilla
 *       writes first ({@code writeByte(storage.getBits())}). For SINGLE this is 0,
 *       for GLOBAL it is the storage's real bit width (e.g. ~15).</li>
 *   <li>{@code words} — packed {@code long} count of the storage. For
 *       {@code ZeroBitStorage} (SINGLE) this is 0; for native-backed storage it
 *       comes from {@code NativeBitStorage.words()} — <b>never</b> from
 *       {@code getRaw().length}, which would allocate.</li>
 *   <li>{@code entryCount} — {@code storage.getSize()} (4096 blocks / 64 biomes).
 *       Cheap (interface read, no allocation); included so Rust can derive the
 *       same word count independently if it ever needs to.</li>
 *   <li>{@code nativeBacked} / {@code nativeAddress} — true with the live native
 *       address when storage is {@code NativeBitStorage}; false (address 0) for
 *       {@code ZeroBitStorage}. Heap {@code SimpleBitStorage} ⇒ {@code !supported}.</li>
 *   <li>{@code globalIds} — palette entries already resolved to global registry
 *       ids via the container's own {@code strategy.globalMap().getId(value)}.
 *       Empty for GLOBAL (no palette body), length 1 for SINGLE, {@code size} for
 *       LINEAR/HASHMAP. Vanilla order preserved.</li>
 *   <li>{@code expectedContainerSize} — this container's wire byte count computed
 *       from the above (1 bits-byte + palette varints + {@code words*8}), matching
 *       {@code Data.getSerializedSize(globalMap)} but allocation-free.</li>
 * </ul>
 */
public final class ContainerDescriptor {
    public final boolean supported;
    public final String reason;

    public final PaletteMode mode;
    public final int bits;
    public final int words;
    public final int entryCount;
    public final boolean nativeBacked;
    public final long nativeAddress;
    /** The storage {@link #nativeAddress} points into. Held so the block cannot be freed
     *  by its Cleaner while a native call reads it (a concurrent resize can drop the
     *  container's own reference); callers fence the descriptor after the call. */
    final Object nativeOwner;
    public final int[] globalIds;
    public final int expectedContainerSize;

    private ContainerDescriptor(boolean supported, String reason, PaletteMode mode,
                                int bits, int words, int entryCount,
                                boolean nativeBacked, long nativeAddress, Object nativeOwner,
                                int[] globalIds, int expectedContainerSize) {
        this.supported = supported;
        this.reason = reason;
        this.mode = mode;
        this.bits = bits;
        this.words = words;
        this.entryCount = entryCount;
        this.nativeBacked = nativeBacked;
        this.nativeAddress = nativeAddress;
        this.nativeOwner = nativeOwner;
        this.globalIds = globalIds;
        this.expectedContainerSize = expectedContainerSize;
    }

    static ContainerDescriptor unsupported(String reason) {
        return new ContainerDescriptor(false, reason, null, 0, 0, 0, false, 0L, null, new int[0], 0);
    }

    static ContainerDescriptor ok(PaletteMode mode, int bits, int words, int entryCount,
                                 boolean nativeBacked, long nativeAddress, Object nativeOwner,
                                 int[] globalIds, int expectedContainerSize) {
        return new ContainerDescriptor(true, null, mode, bits, words, entryCount,
                nativeBacked, nativeAddress, nativeOwner, globalIds, expectedContainerSize);
    }

    @Override
    public String toString() {
        if (!supported) return "ContainerDescriptor[UNSUPPORTED: " + reason + "]";
        return "ContainerDescriptor[" + mode + " bits=" + bits + " words=" + words
                + " native=" + nativeBacked + (nativeBacked ? "@0x" + Long.toHexString(nativeAddress) : "")
                + " ids=" + globalIds.length + " size=" + expectedContainerSize + "]";
    }
}
