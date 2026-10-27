package com.github.tatercertified.nalim.mixin;

import com.github.tatercertified.nalim.sections.NativeBitStorage;
import com.github.tatercertified.nalim.sections.NativeSections;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NativeSections stage 2 — the single coupling point. Every RESIDENT section
 * storage flows through the {@code PalettedContainer$Data} constructor
 * (fresh containers via createOrReuseData, disk loads + repacks via unpack's
 * result container); copies (Data.copy → storage.copy(), e.g. save snapshots)
 * come back as heap storages and transient serialize/repack temporaries never
 * reach it. Swapping the BitStorage argument here moves the words off-heap with zero
 * structural change — the heap SimpleBitStorage the caller just built becomes
 * instant eden garbage.
 *
 * <p>method = "*": the Data constructor is invoked from several methods
 * (createOrReuseData, the private container constructors, unpack); matching
 * the NEW instruction wherever it appears keeps the seam complete against
 * mapping drift without enumerating signatures.
 *
 * <p>The remaining injectors cover the vanilla call sites that treat
 * {@code storage.getRaw()} as the live backing array. On a {@link NativeBitStorage}
 * {@code getRaw()} is a heap copy, so:
 * <ul>
 *   <li>{@code read} (client chunk packets) must write into native memory, otherwise
 *       the received words land in a discarded copy and the section reads back as
 *       palette id 0 everywhere;</li>
 *   <li>{@code getSerializedSize} / {@code write} (every chunk packet) only need the
 *       word count and the words themselves; they produce the same bytes as
 *       vanilla {@code Data.getSerializedSize/write} without the two per-container
 *       heap copies.</li>
 * </ul>
 */
@Mixin(PalettedContainer.class)
public abstract class PalettedContainerMixin {

    @Unique
    private static final long[] RUSTMI$NO_WORDS = new long[0];

    @Shadow @Final private Strategy<?> strategy;

    @Shadow public abstract void acquire();

    @Shadow public abstract void release();

    @ModifyArg(
            method = "*",
            // INVOKE on the <init> call: @At("NEW") resolves to the NEW insn,
            // which @ModifyArg rejects even in descriptor form — its factory
            // handling is @Redirect-only sugar (two boot crashes 2026-06-13).
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/PalettedContainer$Data;<init>(Lnet/minecraft/world/level/chunk/Configuration;Lnet/minecraft/util/BitStorage;Lnet/minecraft/world/level/chunk/Palette;)V"),
            index = 1,
            require = 1)
    private static BitStorage rustmi$sectionsGoNative(BitStorage storage) {
        return NativeSections.swapToNative(storage);
    }

    /** {@code read}: remember the native target instead of materialising a throwaway copy. */
    @WrapOperation(
            method = "read(Lnet/minecraft/network/FriendlyByteBuf;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/BitStorage;getRaw()[J"),
            require = 1)
    private long[] rustmi$nativeReadTarget(BitStorage storage, Operation<long[]> original,
                                           @Share("rustmi$nativeRead") LocalRef<NativeBitStorage> target) {
        if (storage instanceof NativeBitStorage nbs) {
            target.set(nbs);
            return RUSTMI$NO_WORDS;
        }
        target.set(null);
        return original.call(storage);
    }

    /** {@code read}: the same {@code words} big-endian longs, stored straight into native memory. */
    @WrapOperation(
            method = "read(Lnet/minecraft/network/FriendlyByteBuf;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/FriendlyByteBuf;readFixedSizeLongArray([J)[J"),
            require = 1)
    private long[] rustmi$nativeReadInto(FriendlyByteBuf buffer, long[] output, Operation<long[]> original,
                                         @Share("rustmi$nativeRead") LocalRef<NativeBitStorage> target) {
        NativeBitStorage nbs = target.get();
        if (nbs == null) {
            return original.call(buffer, output);
        }
        nbs.readFrom(buffer);
        return output;
    }

    /** Vanilla {@code Data.getSerializedSize}: {@code 1 + palette + getRaw().length * 8}. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Inject(method = "getSerializedSize()I", at = @At("HEAD"), cancellable = true, require = 1)
    private void rustmi$nativeSerializedSize(CallbackInfoReturnable<Integer> cir) {
        if (!NativeSections.nativeLive()) {
            return;
        }
        PalettedContainerDataAccessor data = NativeSections.dataOf((PalettedContainer<?>) (Object) this);
        if (data.rustmi$storage() instanceof NativeBitStorage nbs) {
            Palette palette = data.rustmi$palette();
            cir.setReturnValue(1 + palette.getSerializedSize(this.strategy.globalMap()) + nbs.words() * 8);
        }
    }

    /** Vanilla {@code write} → {@code Data.write}: bits byte, palette, raw words, under the same lock. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Inject(method = "write(Lnet/minecraft/network/FriendlyByteBuf;)V", at = @At("HEAD"), cancellable = true, require = 1)
    private void rustmi$nativeWrite(FriendlyByteBuf buffer, CallbackInfo ci) {
        if (!NativeSections.nativeLive()
                || !(NativeSections.dataOf((PalettedContainer<?>) (Object) this).rustmi$storage() instanceof NativeBitStorage)) {
            return;
        }
        this.acquire();
        try {
            // Re-read under the lock, exactly where vanilla reads this.data.
            PalettedContainerDataAccessor data = NativeSections.dataOf((PalettedContainer<?>) (Object) this);
            if (!(data.rustmi$storage() instanceof NativeBitStorage nbs)) {
                return;   // resized to heap/zero storage meanwhile — vanilla body writes it
            }
            Palette palette = data.rustmi$palette();
            buffer.writeByte(nbs.getBits());
            palette.write(buffer, this.strategy.globalMap());
            nbs.writeTo(buffer);
            ci.cancel();
        } finally {
            this.release();
        }
    }
}
