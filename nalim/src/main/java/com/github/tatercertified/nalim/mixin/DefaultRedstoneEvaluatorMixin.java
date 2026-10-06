package com.github.tatercertified.nalim.mixin;

import com.github.tatercertified.nalim.redstone.CascadeEngine;
import com.github.tatercertified.nalim.redstone.CascadeRecorder;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RedstoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.DefaultRedstoneWireEvaluator;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.level.redstone.RedstoneWireEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * R0 recorder taps on vanilla's default wire evaluator
 * (docs/REDSTONE_VANILLA_PLAN.md). Pure observation — every original
 * operation is invoked unchanged; when {@code -Drustmi.redstone.record}
 * is absent all handlers JIT-fold to no-ops.
 *
 * <p>Extends {@link RedstoneWireEvaluator} (the target's superclass) to reach
 * the protected {@code getBlockSignal} for network snapshots — same pattern
 * Sepals uses with {@code LivingEntityMixin extends Entity}.
 */
@Mixin(DefaultRedstoneWireEvaluator.class)
public abstract class DefaultRedstoneEvaluatorMixin extends RedstoneWireEvaluator
        implements CascadeRecorder.BlockSignalReader {

    protected DefaultRedstoneEvaluatorMixin(RedstoneWireBlock wire) {
        super(wire);
    }

    @Override
    public int rustmi$blockSignal(Level level, BlockPos pos) {
        return this.getBlockSignal(level, pos);
    }

    /**
     * Single HEAD handler: the R3 engine decides first — a script-handled
     * evaluation cancels the body BEFORE the recorder's enter hook runs, so
     * the recorder's eval-depth stays balanced (the cancel also skips its
     * RETURN hook) and engine-handled evals never leak into fixtures.
     */
    @Inject(method = "updatePowerStrength", at = @At("HEAD"), cancellable = true)
    private void rustmi$evalHead(Level level, BlockPos pos, BlockState state,
                                    Orientation orientation, boolean blockAdded,
                                    CallbackInfo ci) {
        if (CascadeEngine.onEvaluatorEnter(level, pos, state, this.wireBlock, this)) {
            ci.cancel();
            return;
        }
        CascadeRecorder.onEvaluatorEnter(level, pos, state, this.wireBlock, this);
    }

    @Inject(method = "updatePowerStrength", at = @At("RETURN"))
    private void rustmi$recordEvalExit(Level level, BlockPos pos, BlockState state,
                                          Orientation orientation, boolean blockAdded,
                                          CallbackInfo ci) {
        CascadeRecorder.onEvaluatorExit(level);
    }

    @WrapOperation(
            method = "updatePowerStrength",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean rustmi$recordWireSet(Level level, BlockPos pos, BlockState newState, int flags,
                                            Operation<Boolean> original) {
        CascadeRecorder.onWireSet(level, pos, newState);
        return original.call(level, pos, newState, flags);
    }

    @WrapOperation(
            method = "updatePowerStrength",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/world/level/Level;updateNeighborsAt(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;)V"))
    private void rustmi$recordUpdateNeighbors(Level level, BlockPos pos, Block block,
                                                 Operation<Void> original) {
        CascadeRecorder.onUpdateNeighbors(level, pos);
        original.call(level, pos, block);
    }
}
