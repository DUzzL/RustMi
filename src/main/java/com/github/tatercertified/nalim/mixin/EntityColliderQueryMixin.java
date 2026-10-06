package com.github.tatercertified.nalim.mixin;

import com.github.tatercertified.nalim.collision.CollisionEngine;
import com.github.tatercertified.nalim.collision.HardColliderBridge;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moulberry.mixinconstraints.annotations.IfModAbsent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * Skips vanilla's entity-collider walk in {@code Entity.collide} when the hard-collider
 * index proves it empty — the same empty list vanilla would build, without iterating every
 * entity in the surrounding sections (≈25% of the server thread in a crowded mob farm).
 * Lithium already postpones this query in its own collide, so the mixin stays out of its way.
 */
@IfModAbsent("lithium")
@Mixin(Entity.class)
public abstract class EntityColliderQueryMixin {

    @WrapOperation(method = "collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getEntityCollisions(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
    private List<VoxelShape> rustmi$skipEmptyColliderWalk(Level level, Entity source, AABB area,
                                                          Operation<List<VoxelShape>> original) {
        // Never while the collision shadow runs: that vanilla call is the reference the native
        // result is compared against, and it must not depend on the index under test.
        if (!level.isClientSide() && !CollisionEngine.isShadow()
                && !HardColliderBridge.mayHaveEntityColliders(source, area)) {
            return List.of();
        }
        return original.call(level, source, area);
    }
}
