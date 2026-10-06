package com.github.tatercertified.nalim.mixin;

import com.github.tatercertified.nalim.redstone.CascadeEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RedstoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.Orientation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * R3 dispatch-chain shortcut: when the cascade engine's script expects this
 * exact wire next, its span is emitted straight from neighborChanged HEAD —
 * skipping canSurvive (sentinel-guarded), the carpet redirect, fastUpdate and
 * the evaluator entry the suppressed evaluation would otherwise pay. See
 * {@link CascadeEngine#shortcutNeighborChanged}.
 */
@Mixin(RedstoneWireBlock.class)
public abstract class RedStoneWireShortcutMixin {

    @Inject(method = "neighborChanged", at = @At("HEAD"), cancellable = true)
    private void rustmi$scriptShortcut(BlockState state, Level level, BlockPos pos,
                                          Block neighborBlock, Orientation orientation,
                                          boolean movedByPiston, CallbackInfo ci) {
        if (CascadeEngine.shortcutNeighborChanged(level, pos, state)) {
            ci.cancel();
        }
    }
}
