package com.github.tatercertified.nalim.sections;

import com.github.tatercertified.nalim.cramming.RustmiCramming;
import com.github.tatercertified.nalim.rust.RustmiLib;
import io.netty.buffer.ByteBuf;
import net.minecraft.util.BitStorage;
import net.minecraft.util.SimpleBitStorage;
import org.apache.commons.lang3.Validate;

import java.lang.ref.Cleaner;
import java.lang.ref.Reference;
import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * NativeSections drop-in {@link BitStorage} whose packed words live in
 * native memory ({@code oxi_section_alloc}): the GC stops scanning/copying
 * the ~5GB of section {@code long[]} the heap analysis found, and Rust kernels
 * can read the same memory directly by address. Bit algebra is vanilla
 * {@link SimpleBitStorage} verbatim (same magic-divide cell index and masks);
 * only the word load/store goes through Unsafe instead of an array.
 * {@code get/set/getAndSet} carry no {@code Validate} range checks, matching the
 * deployed stack (Lithium's {@code chunk.no_validation} strips them from
 * SimpleBitStorage); {@code fill} keeps its check, as Lithium leaves it in place.
 *
 * <p>{@link #getRaw()} is a heap COPY, not the backing array. Vanilla code that
 * writes through {@code getRaw()} ({@code PalettedContainer.read}) is redirected
 * to {@link #readFrom} by {@code PalettedContainerMixin}.
 *
 * <p>Thread model: identical to the {@code long[]} it replaces — plain
 * unfenced reads and writes. Whatever ordering vanilla+C2ME establish around
 * section access applies unchanged; this class adds none and removes none.
 *
 * <p>Lifecycle: freed by {@link Cleaner} when the storage becomes unreachable
 * (PalettedContainer drops it on resize, chunks drop containers on unload).
 * The Rust side pools the standard section word-counts, so churn is cheap.
 *
 * <p>Resident {@code PalettedContainer.Data} instances are wired through
 * {@code PalettedContainerMixin}; fresh construction, disk unpack, repacks,
 * and native-aware copies all converge on that constructor. Transient
 * serialization scratch storages remain vanilla.
 */
public final class NativeBitStorage implements BitStorage {

    private static final Cleaner CLEANER = Cleaner.create(r -> {
        Thread t = new Thread(r, "Rustmi-NativeSections-Cleaner");
        t.setDaemon(true);
        return t;
    });
    private static final sun.misc.Unsafe U = RustmiCramming.theUnsafe();

    /** Vanilla MAGIC table, identical layout: (scale, offset, shift) per
     *  valuesPerLong-1. Mirrored via SimpleBitStorage on first use. */
    private static final int[] MAGIC = MagicHolder.MAGIC;

    private final long addr;
    private final int words;
    private final int bits;
    private final long mask;
    private final int size;
    private final int valuesPerLong;
    private final long divideMul;   // pre-widened, like vanilla's unsigned use
    private final long divideAdd;
    private final int divideShift;
    private final Cleaner.Cleanable cleanable;

    public NativeBitStorage(int bits, int size) {
        this(bits, size, (long[]) null);
    }

    public NativeBitStorage(int bits, int size, long[] init) {
        if (bits < 1 || bits > 32) {
            throw new IllegalArgumentException("bits out of range: " + bits);
        }
        this.bits = bits;
        this.size = size;
        this.mask = (1L << bits) - 1L;
        this.valuesPerLong = (char) (64 / bits);
        int magicRow = 3 * (this.valuesPerLong - 1);
        this.divideMul = Integer.toUnsignedLong(MAGIC[magicRow]);
        this.divideAdd = Integer.toUnsignedLong(MAGIC[magicRow + 1]);
        this.divideShift = MAGIC[magicRow + 2];
        this.words = (size + this.valuesPerLong - 1) / this.valuesPerLong;
        if (init != null && init.length != this.words) {
            // Vanilla throws SimpleBitStorage.InitializationException here; its
            // ctor is private. Callers on the unpack path catch-and-recount, so
            // surfacing IAE keeps the same "reject, don't truncate" contract.
            throw new IllegalArgumentException(
                    "Invalid length given for storage, got: " + init.length
                            + " but expected: " + this.words);
        }
        long a = RustmiLib.oxi_section_alloc(this.words);
        if (a == 0) {
            throw new OutOfMemoryError("oxi_section_alloc(" + this.words + ") failed");
        }
        this.addr = a;
        if (init != null) {
            U.copyMemory(init, sun.misc.Unsafe.ARRAY_LONG_BASE_OFFSET,
                    null, a, (long) this.words * 8L);
        }
        final long fAddr = a;
        final int fWords = this.words;
        this.cleanable = CLEANER.register(this, () -> RustmiLib.oxi_section_free(fAddr, fWords));
    }

    /** Vanilla SimpleBitStorage(int,int,int[]) packing loop, words → native. */
    public NativeBitStorage(int bits, int size, int[] values) {
        this(bits, size);
        int idx = 0;
        int cell = 0;
        for (; idx <= size - valuesPerLong; idx += valuesPerLong) {
            long w = 0L;
            for (int j = valuesPerLong - 1; j >= 0; j--) {
                w <<= bits;
                w |= values[idx + j] & mask;
            }
            word(cell++, w);
        }
        int rem = size - idx;
        if (rem > 0) {
            long w = 0L;
            for (int j = rem - 1; j >= 0; j--) {
                w <<= bits;
                w |= values[idx + j] & mask;
            }
            word(cell, w);
        }
    }

    /** Native address of word 0 — the Rust kernels' view of this storage. */
    public long address() {
        return addr;
    }

    public int words() {
        return words;
    }

    private int cellIndex(int index) {
        return (int) (index * divideMul + divideAdd >> 32 >> divideShift);
    }

    // Objects.checkIndex stands in for the array bounds check SimpleBitStorage gets for
    // free: a bad index must throw (as vanilla's AIOOBE does), never write past the
    // native block into a neighbouring allocation. C2 intrinsifies it to one compare.
    //
    // reachabilityFence: once C2 hoists the final addr/words loads out of a loop, nothing
    // else keeps `this` alive, and the Cleaner may free (and the pool re-issue) the block
    // mid-loop — e.g. writeTo racing a resize that dropped the container's last reference.
    private long word(int cellIndex) {
        long w = U.getLong(addr + (long) Objects.checkIndex(cellIndex, words) * 8L);
        Reference.reachabilityFence(this);
        return w;
    }

    private void word(int cellIndex, long value) {
        U.putLong(addr + (long) Objects.checkIndex(cellIndex, words) * 8L, value);
        Reference.reachabilityFence(this);
    }

    @Override
    public int getAndSet(int index, int value) {
        int cell = cellIndex(index);
        long w = word(cell);
        int bitIndex = (index - cell * valuesPerLong) * bits;
        int old = (int) (w >> bitIndex & mask);
        word(cell, w & ~(mask << bitIndex) | (value & mask) << bitIndex);
        return old;
    }

    @Override
    public void set(int index, int value) {
        int cell = cellIndex(index);
        long w = word(cell);
        int bitIndex = (index - cell * valuesPerLong) * bits;
        word(cell, w & ~(mask << bitIndex) | (value & mask) << bitIndex);
    }

    @Override
    public int get(int index) {
        int cell = cellIndex(index);
        long w = word(cell);
        int bitIndex = (index - cell * valuesPerLong) * bits;
        return (int) (w >> bitIndex & mask);
    }

    /** Vanilla 26.3 {@code SimpleBitStorage.fill} verbatim: same value check, and the same
     *  doubling replication, which also fills the padding bits above
     *  {@code valuesPerLong * bits}. The packet and save paths emit raw words, so those
     *  padding bits have to match vanilla byte-for-byte. */
    @Override
    public void fill(int value) {
        Validate.inclusiveBetween(0L, mask, value);
        long cellValue = value & mask;
        int offset = bits;
        do {
            cellValue |= cellValue << offset;
            offset <<= 1;
        } while (offset < 64);
        for (int cell = 0; cell < words; cell++) {
            word(cell, cellValue);
        }
    }

    /** {@code buffer.readFixedSizeLongArray(getRaw())} straight into native memory: the
     *  same {@code words} big-endian longs, read in order. */
    public void readFrom(ByteBuf buffer) {
        for (int cell = 0; cell < words; cell++) {
            word(cell, buffer.readLong());
        }
    }

    /** {@code buffer.writeFixedSizeLongArray(getRaw())} without the heap copy. */
    public void writeTo(ByteBuf buffer) {
        for (int cell = 0; cell < words; cell++) {
            buffer.writeLong(word(cell));
        }
    }

    /** Heap materialization for serialization (pack/save) — one copy, same
     *  cost class as vanilla's defensive copies on the save path. */
    @Override
    public long[] getRaw() {
        long[] out = new long[words];
        U.copyMemory(null, addr, out, sun.misc.Unsafe.ARRAY_LONG_BASE_OFFSET,
                (long) words * 8L);
        Reference.reachabilityFence(this);
        return out;
    }

    @Override
    public int getSize() {
        return size;
    }

    @Override
    public int getBits() {
        return bits;
    }

    @Override
    public void getAll(IntConsumer consumer) {
        int idx = 0;
        for (int cell = 0; cell < words; cell++) {
            long w = word(cell);
            for (int j = 0; j < valuesPerLong; j++) {
                consumer.accept((int) (w & mask));
                w >>= bits;
                if (++idx >= size) {
                    return;
                }
            }
        }
    }

    @Override
    public void unpack(int[] out) {
        int chunks = size / valuesPerLong;
        int idx = 0;
        for (int cell = 0; cell < chunks; cell++) {
            long w = word(cell);
            for (int j = 0; j < valuesPerLong; j++) {
                out[idx + j] = (int) (w & mask);
                w >>= bits;
            }
            idx += valuesPerLong;
        }
        int rem = size - idx;
        if (rem > 0) {
            long w = word(chunks);
            for (int j = 0; j < rem; j++) {
                out[idx + j] = (int) (w & mask);
                w >>= bits;
            }
        }
    }

    @Override
    public BitStorage copy() {
        NativeBitStorage c = new NativeBitStorage(bits, size);
        U.copyMemory(addr, c.addr, (long) words * 8L);
        Reference.reachabilityFence(this);
        return c;
    }

    /** Explicit early release (chunk unload fast path, optional). */
    public void releaseNow() {
        cleanable.clean();
    }

    /** Pulls vanilla's MAGIC via a tiny reflective read once — keeping the
     *  table bit-identical to the deployed mappings without duplication. */
    private static final class MagicHolder {
        static final int[] MAGIC;

        static {
            try {
                var f = SimpleBitStorage.class.getDeclaredField("MAGIC");
                f.setAccessible(true);
                MAGIC = (int[]) f.get(null);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }
}
