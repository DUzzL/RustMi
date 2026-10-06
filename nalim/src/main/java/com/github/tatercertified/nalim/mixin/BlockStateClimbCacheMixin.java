package com.github.tatercertified.nalim.mixin;

import com.github.tatercertified.nalim.entity.ClimbCache;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateClimbCacheMixin implements ClimbCache.Holder {

    /** Single long so concurrent readers never see a torn (generation, value) pair. */
    @Unique private volatile long rustmi$climbCache;

    @Override
    public long rustmi$climbCache() {
        return rustmi$climbCache;
    }

    @Override
    public void rustmi$setClimbCache(long packed) {
        rustmi$climbCache = packed;
    }
}
