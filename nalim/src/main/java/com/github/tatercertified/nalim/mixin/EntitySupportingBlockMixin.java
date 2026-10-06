package com.github.tatercertified.nalim.mixin;

import com.github.tatercertified.nalim.collision.CollisionEngine;
import com.github.tatercertified.nalim.collision.SupportingBlock;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

/**
 * Answers the per-tick supporting-block query of grounded entities from the collision mirror
 * (see {@link SupportingBlock}); vanilla walks and re-classifies every cell of a padded box
 * through {@code BlockCollisions}. Shares the collision engine's on/shadow switches.
 */
@Mixin(Entity.class)
public abstract class EntitySupportingBlockMixin {

    @WrapOperation(method = "checkSupportingBlock(ZLnet/minecraft/world/phys/Vec3;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;findSupportingBlock(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/Optional;"))
    private Optional<BlockPos> rustmi$mirrorSupport(Level level, Entity source, AABB box,
                                                    Operation<Optional<BlockPos>> original) {
        if (!CollisionEngine.isActive()) {
            return original.call(level, source, box);
        }
        Optional<BlockPos> fast = SupportingBlock.find(level, source, box);
        if (fast == null) {
            return original.call(level, source, box);
        }
        if (CollisionEngine.isEngine()) {
            return fast;
        }
        Optional<BlockPos> vanilla = original.call(level, source, box);
        CollisionEngine.recordSupportShadow(vanilla, fast, source);
        return vanilla;
    }
}
