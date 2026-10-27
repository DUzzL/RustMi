package com.github.tatercertified.nalim.mixin;

import com.github.tatercertified.nalim.collision.CollisionEngine;
import com.github.tatercertified.nalim.collision.HardColliderBridge;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;

/**
 * Full-cube collision fast path (C2): preconditions the kernel does not model
 * (entity colliders, world border proximity, zero movement, client side) fall
 * to vanilla; the rest goes through {@link CollisionEngine#tryCollide}, which
 * itself bails to vanilla on any partial shape / unloaded chunk in range.
 */
@Mixin(Entity.class)
public abstract class EntityCollideMixin {

    @WrapMethod(method = "collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;")
    private Vec3 oxi$fastCollide(Vec3 movement, Operation<Vec3> original) {
        if (!CollisionEngine.isActive()) {
            return original.call(movement);
        }
        Entity self = (Entity) (Object) this;
        if (self.level().isClientSide() || movement.lengthSqr() == 0.0) {
            return original.call(movement);
        }
        // Debug-world chunks synthesize their blocks in getBlockState; the sections the
        // mirror reads stay empty.
        if (self.level().isDebug()) {
            return original.call(movement);
        }
        if (CollisionEngine.requiresVanillaPrecision(movement)) {
            CollisionEngine.countPrecisionBail(movement);
            return original.call(movement);
        }
        // minecart_improvements: CollisionContext.of gives minecarts a MinecartCollisionContext
        // that ignores the block under the rail and the slope neighbour — positions the
        // context-free mirror would still collide with.
        if (self instanceof AbstractMinecart && AbstractMinecart.useExperimentalMovement(self.level())) {
            return original.call(movement);
        }
        AABB box = self.getBoundingBox();
        AABB stretched = box.expandTowards(movement);
        // 26.3 Entity.collide queries entity colliders over the stretched box
        // extended UP by maxUpStep (the step-up probe reuses that list), so
        // the emptiness proof must cover the same region.
        // expandTowards(0, 0, 0) is the identity — skip the allocation for
        // step-less movers (items, projectiles, XP orbs).
        float step = self.maxUpStep();
        AABB entityQuery = step != 0.0F ? stretched.expandTowards(0.0, step, 0.0) : stretched;
        // Hard-collider pre-gate: vanilla's only way to prove "no entity
        // colliders here" is a full EntitySectionStorage walk per call; the
        // HardColliders index answers the emptiness question from the live
        // boat/shulker boxes in the same sections. Positive → vanilla's precise
        // walk as before.
        if (HardColliderBridge.mayHaveEntityColliders(self, entityQuery)) {
            List<net.minecraft.world.phys.shapes.VoxelShape> entityCollisions =
                    self.level().getEntityCollisions(self, entityQuery);
            if (!entityCollisions.isEmpty()) {
                return original.call(movement);
            }
        }
        if (self.level().getWorldBorder().isInsideCloseToBorder(self, stretched)) {
            return original.call(movement);
        }
        Vec3 fast = CollisionEngine.tryCollide(self, movement, box, step);
        if (fast == null) {
            return original.call(movement);
        }
        if (CollisionEngine.isEngine()) {
            return fast;
        }
        Vec3 vanilla = original.call(movement);
        CollisionEngine.recordShadow(vanilla, fast, self, movement);
        return vanilla;
    }
}
