package com.github.tatercertified.nalim.mixin;

import com.github.tatercertified.nalim.collision.CollisionEngine;
import com.github.tatercertified.nalim.entity.ClimbCache;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Short-circuits the common "not climbing" answer of {@code LivingEntity.onClimbable}: the
 * cramming predicate ({@code pushableBy → isPushable}) asks it once per neighbour pair, and
 * each call costs two tag probes plus a synched-data read. Same call order as vanilla up to
 * the decision (isSpectator, then getInBlockState); see {@link ClimbCache} for exactness.
 * Under the collision shadow the vanilla body always runs and must agree.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityClimbableMixin {

    @WrapMethod(method = "onClimbable")
    private boolean rustmi$neverClimbable(Operation<Boolean> original) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.isSpectator()) {
            return original.call();
        }
        BlockState state = self.getInBlockState();
        boolean never = ClimbCache.neverClimbable(state);
        if (CollisionEngine.isShadow()) {
            boolean vanilla = original.call();
            CollisionEngine.recordPredicateShadow(!(never && vanilla),
                    "onClimbable " + self.getType().toShortString() + " in " + state);
            return vanilla;
        }
        return !never && original.call();
    }
}
