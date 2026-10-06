package com.github.tatercertified.nalim.mixin;

import com.github.tatercertified.nalim.entity.TickingMemo;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.SimulationChunkTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Simulation ticket levels (read by {@code inEntityTickingRange}) change only in
 * {@code setLevel}; Fabrolia republishes its completed view of them at the RETURN of
 * {@code runAllUpdates}, so the outermost wrap bumps again after that publication.
 */
@Mixin(SimulationChunkTracker.class)
public abstract class SimulationTrackerEpochMixin {

    @Inject(method = "setLevel(JI)V", at = {@At("HEAD"), @At("RETURN")})
    private void rustmi$levelChanged(CallbackInfo ci) {
        TickingMemo.bump();
    }

    @WrapMethod(method = "runAllUpdates", order = 2000)
    private void rustmi$afterWave(Operation<Void> original) {
        try {
            original.call();
        } finally {
            TickingMemo.bump();
        }
    }
}
