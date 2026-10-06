package com.github.tatercertified.nalim.sections;

import com.github.tatercertified.nalim.mixin.PalettedContainerAccessor;
import com.github.tatercertified.nalim.mixin.PalettedContainerDataAccessor;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Live proof of the chunk wire format: for every section of the level's force-loaded chunks,
 * the bytes the (possibly native) {@code PalettedContainer.write} emits must equal vanilla's
 * {@code Data.write} body (bits byte, palette, {@code getRaw()} words), {@code
 * getSerializedSize} must equal the bytes actually written, and reading those bytes back
 * (the native {@code readFrom} path for native containers) must reproduce every cell, and the
 * native words must equal vanilla {@link SimpleBitStorage}'s packing of the same values.
 * Runs on the server thread from {@code /rustmi sections wirecheck}.
 */
public final class WireCheck {

    private WireCheck() {}

    public static String run(ServerLevel level) {
        int sections = 0;
        int containers = 0;
        int nativeContainers = 0;
        int writeBad = 0;
        int sizeBad = 0;
        int roundTripBad = 0;
        int packBad = 0;
        long[] keys = level.getForceLoadedChunks().toLongArray();
        for (long key : keys) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk == null) {
                continue;
            }
            for (LevelChunkSection section : chunk.getSections()) {
                sections++;
                PalettedContainer<?>[] parts = section.getBiomes() instanceof PalettedContainer<?> biomes
                        ? new PalettedContainer<?>[]{section.getStates(), biomes}
                        : new PalettedContainer<?>[]{section.getStates()};
                for (PalettedContainer<?> c : parts) {
                    containers++;
                    if (NativeSections.dataOf(c).rustmi$storage() instanceof NativeBitStorage nbs) {
                        nativeContainers++;
                        if (!packsLikeVanilla(nbs)) {
                            packBad++;
                        }
                    }
                    byte[] live = bytes(c::write);
                    if (!Arrays.equals(live, bytes(buf -> vanillaWrite(c, buf)))) {
                        writeBad++;
                    }
                    if (c.getSerializedSize() != live.length) {
                        sizeBad++;
                    }
                }
                byte[] whole = bytes(section::write);
                if (section.getSerializedSize() != whole.length) {
                    sizeBad++;
                }
                LevelChunkSection back = section.copy();
                back.read(new FriendlyByteBuf(Unpooled.wrappedBuffer(whole)));
                if (!sameCells(section, back)) {
                    roundTripBad++;
                }
            }
        }
        return String.format(
                "wirecheck sections=%d containers=%d native=%d | writeBad=%d sizeBad=%d roundTripBad=%d packBad=%d",
                sections, containers, nativeContainers, writeBad, sizeBad, roundTripBad, packBad);
    }

    /**
     * Independent packing oracle: vanilla's {@link SimpleBitStorage} packs the same cell
     * values; its words must equal the native words bit for bit (the write comparison alone
     * reads the native words on both sides).
     */
    private static boolean packsLikeVanilla(NativeBitStorage nbs) {
        int size = nbs.getSize();
        SimpleBitStorage ref = new SimpleBitStorage(nbs.getBits(), size);
        for (int i = 0; i < size; i++) {
            ref.set(i, nbs.get(i));
        }
        return Arrays.equals(ref.getRaw(), nbs.getRaw());
    }

    /** Vanilla {@code PalettedContainer.Data.write}, verbatim. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void vanillaWrite(PalettedContainer<?> c, FriendlyByteBuf buffer) {
        PalettedContainerDataAccessor data = NativeSections.dataOf(c);
        BitStorage storage = data.rustmi$storage();
        buffer.writeByte(storage.getBits());
        data.rustmi$palette().write(buffer, ((PalettedContainerAccessor) c).rustmi$strategy().globalMap());
        buffer.writeFixedSizeLongArray(storage.getRaw());
    }

    private static byte[] bytes(Consumer<FriendlyByteBuf> writer) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            writer.accept(buf);
            byte[] out = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), out);
            return out;
        } finally {
            buf.release();
        }
    }

    private static boolean sameCells(LevelChunkSection a, LevelChunkSection b) {
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (a.getBlockState(x, y, z) != b.getBlockState(x, y, z)) {
                        return false;
                    }
                }
            }
        }
        for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) {
                    if (!Objects.equals(a.getNoiseBiome(x, y, z), b.getNoiseBiome(x, y, z))) {
                        return false;
                    }
                }
            }
        }
        return a.hasOnlyAir() == b.hasOnlyAir();
    }
}
