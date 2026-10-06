package com.github.tatercertified.nalim.mixin;

import com.github.tatercertified.nalim.redstone.CascadeEngine;
import com.github.tatercertified.nalim.redstone.CascadeRecorder;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import org.spongepowered.asm.mixin.Mixin;

/**
 * R0 recorder burst boundaries and the R3 engine's drain depth. Vanilla 26.x
 * flattens neighbor-update cascades through {@link CollectingNeighborUpdater}:
 * the outermost {@code addAndRun} call runs the whole drain loop, inner calls
 * merely enqueue. One logical redstone cascade therefore equals one outermost
 * addAndRun — the fixture boundary the recorder needs (evaluator nesting alone
 * never goes past depth 1).
 *
 * <p>A method wrapper, not HEAD/RETURN injects: Fabrolia {@code @WrapMethod}s
 * addAndRun with a per-thread re-implementation that never calls the original
 * body, which silently removed injects placed inside it (the engine saw depth 0
 * forever and never took over under Fabrolia). Priority 2000 applies this wrapper
 * after Fabrolia's (1000), making it the outermost one, so it brackets whichever
 * implementation runs; try/finally keeps the per-thread depth balanced when the
 * drain throws and the caller survives (region workers catch failures).
 */
@Mixin(value = CollectingNeighborUpdater.class, priority = 2000)
public abstract class CollectingNeighborUpdaterMixin {

    @WrapMethod(method = "addAndRun")
    private void rustmi$drain(BlockPos pos, CollectingNeighborUpdater.NeighborUpdates update,
                              Operation<Void> original) {
        CascadeRecorder.onDrainEnter();
        CascadeEngine.onDrainEnter();
        try {
            original.call(pos, update);
        } finally {
            CascadeRecorder.onDrainExit();
            CascadeEngine.onDrainExit();
        }
    }
}
