package com.github.tatercertified.nalim.mixin;

import com.github.tatercertified.nalim.redstone.RedpilerHost;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedstoneWireBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Filters out scheduled-tick dispatches whose position is inside a
 * Redpiler-claimed AABB AND whose block is one Redpiler simulates. The
 * backend's own scheduler drives those ticks — vanilla dispatching the
 * same tick would race the backend's view of the world.
 *
 * <p>The dispatch path: LevelTicks.tick → BiConsumer ticker (in
 * ServerLevel) → BlockState.tick → {@code BlockBehaviour$BlockStateBase.tick}
 * → {@code Block.tick}. Cancelling at BlockStateBase.tick HEAD covers BOTH
 * the island-partitioned dispatch (Fabrolia) and the vanilla serial
 * fallback in one mixin.
 *
 * <p>Non-redstone blocks inside the AABB tick normally — this matches the
 * "Redpiler owns its redstone graph" invariant without breaking any
 * unrelated tickables the user may have placed in the area.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseTickMixin {

    @Inject(method = "tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V",
            at = @At("HEAD"), cancellable = true)
    private void oxi$redpilerFilter(ServerLevel level, BlockPos pos, RandomSource random,
                                    CallbackInfo ci) {
        if (!RedpilerHost.isEnabled()) return;
        if (!RedpilerHost.containsAny(level, pos)) return;
        // Only cancel for blocks Redpiler simulates. Other tickables in the
        // same AABB run vanilla — Redpiler doesn't represent them.
        BlockState self = (BlockState) (Object) this;
        Block b = self.getBlock();
        if (b instanceof RedstoneWireBlock
                || b instanceof DiodeBlock
                || b instanceof ComparatorBlock
                || b instanceof RedstoneTorchBlock
                || b instanceof RedstoneLampBlock
                || b instanceof LeverBlock
                || b instanceof ButtonBlock
                || b instanceof BasePressurePlateBlock) {
            ci.cancel();
        }
    }
}
