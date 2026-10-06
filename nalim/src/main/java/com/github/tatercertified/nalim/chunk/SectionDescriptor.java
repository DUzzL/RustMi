package com.github.tatercertified.nalim.chunk;

/**
 * Wire metadata for one whole {@code LevelChunkSection}, as written by
 * {@code LevelChunkSection.write(FriendlyByteBuf)} (26.2 layout, unchanged in 26.3):
 *
 * <pre>
 *   writeShort(nonEmptyBlockCount)   // unsigned
 *   writeShort(fluidCount)           // unsigned   (NEW 26.2 — sits between the counts and states)
 *   states.write(buffer)             // {@link #states}
 *   biomes.write(buffer)             // {@link #biomes}
 * </pre>
 *
 * <p>{@code supported == false} means at least one container's storage/palette is
 * not yet handled (heap {@code SimpleBitStorage}, non-{@code PalettedContainer}
 * biomes, unknown palette type). The future encoder falls back to vanilla
 * {@code section.write} for the whole section in that case — see
 * {@link ChunkSectionPacketEncoder#describe} / the FFI fallback protocol.
 *
 * <p>{@code expectedSerializedSize} is computed by us (allocation-free); it equals
 * vanilla {@code section.getSerializedSize()} = {@code 4 + states + biomes}.
 * The {@code 4} is the two count shorts.
 */
public final class SectionDescriptor {
    public final boolean supported;
    public final String reason;

    public final int nonEmptyBlockCount;   // unsigned (short & 0xFFFF)
    public final int fluidCount;           // unsigned
    public final ContainerDescriptor states;
    public final ContainerDescriptor biomes;
    public final int expectedSerializedSize;

    private SectionDescriptor(boolean supported, String reason, int nonEmptyBlockCount,
                              int fluidCount, ContainerDescriptor states,
                              ContainerDescriptor biomes, int expectedSerializedSize) {
        this.supported = supported;
        this.reason = reason;
        this.nonEmptyBlockCount = nonEmptyBlockCount;
        this.fluidCount = fluidCount;
        this.states = states;
        this.biomes = biomes;
        this.expectedSerializedSize = expectedSerializedSize;
    }

    static SectionDescriptor unsupported(String reason, int nonEmptyBlockCount, int fluidCount,
                                         ContainerDescriptor states, ContainerDescriptor biomes) {
        return new SectionDescriptor(false, reason, nonEmptyBlockCount, fluidCount, states, biomes, -1);
    }

    static SectionDescriptor ok(int nonEmptyBlockCount, int fluidCount,
                                ContainerDescriptor states, ContainerDescriptor biomes,
                                int expectedSerializedSize) {
        return new SectionDescriptor(true, null, nonEmptyBlockCount, fluidCount, states, biomes, expectedSerializedSize);
    }

    @Override
    public String toString() {
        if (!supported) return "SectionDescriptor[UNSUPPORTED: " + reason + "]";
        return "SectionDescriptor[nonEmpty=" + nonEmptyBlockCount + " fluid=" + fluidCount
                + " size=" + expectedSerializedSize + " states=" + states + " biomes=" + biomes + "]";
    }
}
