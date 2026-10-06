package com.github.tatercertified.nalim.mixin;

import com.github.tatercertified.nalim.collision.CollisionEngine;
import com.github.tatercertified.nalim.collision.CubeMirror;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Per-chunk collision-class mirror (see {@link CubeMirror}): one immutable snapshot per
 * section, built lazily and replaced copy-on-write from setBlockState. Storage rides the
 * chunk object — no global maps, no eviction.
 *
 * <p>Concurrency: builds, updates and invalidations serialize on the per-chunk monitor and
 * publish through {@code setRelease} on the section slot; lock-free readers
 * {@code getAcquire} the slot once and use that single array. A snapshot is never written
 * after publication, so a reader can never observe a half-built section or a
 * transiently-EMPTY cell (the #973 phase-through class), and there is no multi-array torn
 * read. Writers always take the monitor AFTER their block write, so a later build (also
 * under the monitor) is ordered after the write and scans it.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMirrorMixin implements CubeMirror {

    @Unique private volatile long[][] oxi$slots;
    /** NOT final/initialized: c2me constructs LevelChunk via Unsafe.allocateInstance,
     *  which skips constructors — a field initializer would stay null (live NPE crash
     *  "oxi$buildLock is null", 2026-06-12 06:17). Lazily CAS-created instead. */
    @Unique private volatile Object oxi$buildLock;

    @Unique private static final VarHandle OXI$SLOT =
            MethodHandles.arrayElementVarHandle(long[][].class);

    @Unique private static final VarHandle OXI$LOCK;

    static {
        try {
            OXI$LOCK = MethodHandles.privateLookupIn(LevelChunk.class, MethodHandles.lookup())
                    .findVarHandle(LevelChunk.class, "oxi$buildLock", Object.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Unique
    private Object oxi$lock() {
        Object l = oxi$buildLock;
        if (l == null) {
            Object fresh = new Object();
            Object raced = OXI$LOCK.compareAndExchange(this, null, fresh);
            l = raced == null ? fresh : raced;
        }
        return l;
    }

    @Unique
    private long[][] oxi$ensureSlots() {
        long[][] slots = oxi$slots;
        if (slots == null) {
            synchronized (oxi$lock()) {
                slots = oxi$slots;
                if (slots == null) {
                    slots = new long[((ChunkAccess) (Object) this).getSections().length][];
                    oxi$slots = slots;
                }
            }
        }
        return slots;
    }

    @Unique
    private long[] oxi$buildSection(long[][] slots, int idx) {
        synchronized (oxi$lock()) {
            long[] built = slots[idx];
            if (built != null) {
                return built;
            }
            LevelChunkSection sec = ((ChunkAccess) (Object) this).getSections()[idx];
            long[] bits = oxi$scan(sec);
            OXI$SLOT.setRelease(slots, idx, bits);
            return bits;
        }
    }

    /** Classifies a section; uniform palettes resolve without touching the 4096 cells. */
    @Unique
    private static long[] oxi$scan(LevelChunkSection sec) {
        if (sec == null || sec.hasOnlyAir()) {
            return Snapshots.EMPTY;
        }
        // maybeHas consults the palette only; a palette entry may be unused, so a false
        // answer proves absence and a true answer just falls through to the scan.
        if (!sec.maybeHas(s -> CollisionEngine.classify(s) != CollisionEngine.CLS_FULL)) {
            return Snapshots.SOLID;
        }
        if (!sec.maybeHas(s -> CollisionEngine.classify(s) != CollisionEngine.CLS_EMPTY)) {
            return Snapshots.EMPTY;
        }
        if (!sec.maybeHas(s -> CollisionEngine.classify(s) != CollisionEngine.CLS_PARTIAL)) {
            return Snapshots.ALL_PARTIAL;
        }
        long[] bits = new long[SNAPSHOT_LONGS];
        var states = sec.getStates();
        for (int cell = 0; cell < 4096; cell++) {
            BlockState s = states.get(cell & 15, cell >>> 8, (cell >>> 4) & 15);
            int off = oxi$offsetOf(CollisionEngine.classify(s));
            if (off >= 0) {
                bits[off + (cell >> 6)] |= 1L << (cell & 63);
            }
        }
        return Snapshots.canonical(bits);
    }

    @Unique
    private static int oxi$offsetOf(int cls) {
        return cls == CollisionEngine.CLS_FULL ? FULL_OFF
                : cls == CollisionEngine.CLS_PARTIAL ? PARTIAL_OFF
                : cls == CollisionEngine.CLS_BOXES ? BOXES_OFF
                : -1;
    }

    @Override
    public long[] oxi$bits(int sectionIdx) {
        long[][] slots = oxi$ensureSlots();
        if (sectionIdx < 0 || sectionIdx >= slots.length) {
            return null;
        }
        long[] bits = (long[]) OXI$SLOT.getAcquire(slots, sectionIdx);
        return bits != null ? bits : oxi$buildSection(slots, sectionIdx);
    }

    @Override
    public void oxi$updateCell(int sectionIdx, int cell) {
        // Everything happens under the monitor, including the "never built" check: the block
        // write precedes this call, so taking the monitor orders it before any later build's
        // scan. An unlocked null check would race a concurrent first build (store-buffering:
        // our plain section write vs. its slot publication) and could publish a stale cell.
        synchronized (oxi$lock()) {
            long[][] slots = oxi$slots;
            if (slots == null || sectionIdx < 0 || sectionIdx >= slots.length) {
                return;   // mirror never built here — the lazy build reads current state
            }
            long[] cur = slots[sectionIdx];
            if (cur == null) {
                return;
            }
            // Classify the LIVE cell, not the hook's state argument: nested writes to the same
            // cell (TNT primed in onPlace, lava → cobblestone inside spreadTo) complete their
            // hooks inner-first, so the outer hook's argument is already superseded.
            LevelChunkSection sec = ((ChunkAccess) (Object) this).getSections()[sectionIdx];
            BlockState live = sec.getBlockState(cell & 15, cell >>> 8, (cell >>> 4) & 15);
            int now = oxi$offsetOf(CollisionEngine.classify(live));
            int word = cell >> 6;
            long bit = 1L << (cell & 63);
            int was = (cur[FULL_OFF + word] & bit) != 0 ? FULL_OFF
                    : (cur[PARTIAL_OFF + word] & bit) != 0 ? PARTIAL_OFF
                    : (cur[BOXES_OFF + word] & bit) != 0 ? BOXES_OFF
                    : -1;
            if (was == now) {
                return;   // collision class unchanged (power/lit/... state edits) — snapshot stays
            }
            long[] next = cur.clone();
            if (was >= 0) {
                next[was + word] &= ~bit;
            }
            if (now >= 0) {
                next[now + word] |= bit;
            }
            OXI$SLOT.setRelease(slots, sectionIdx, Snapshots.canonical(next));
        }
    }

    @Override
    public void oxi$invalidateSection(int sectionIdx) {
        synchronized (oxi$lock()) {
            long[][] slots = oxi$slots;
            if (slots != null && sectionIdx >= 0 && sectionIdx < slots.length) {
                OXI$SLOT.setRelease(slots, sectionIdx, null);
            }
        }
    }

    @Inject(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("RETURN"))
    private void oxi$invalidateCell(BlockPos pos, BlockState state, int flags,
                                    CallbackInfoReturnable<BlockState> cir) {
        if (cir.getReturnValue() == null) {
            // Either a no-op set, or a nested write replaced the block inside onPlace and its
            // own hook already reconciled the cell from live state.
            return;
        }
        int idx = (pos.getY() - ((ChunkAccess) (Object) this).getMinY()) >> 4;
        int cell = ((pos.getY() & 15) << 8) | ((pos.getZ() & 15) << 4) | (pos.getX() & 15);
        oxi$updateCell(idx, cell);
        // R3 sentinel: feeds the wire-network cache (power/sp upkeep, topology
        // drops) and aborts a running cascade script on topology-relevant
        // foreign writes in its halo. Return value = the replaced (old) state.
        com.github.tatercertified.nalim.redstone.CascadeEngine.onWorldSetBlock(
                ((LevelChunk) (Object) this).getLevel(), pos, cir.getReturnValue(), state);
    }
}
