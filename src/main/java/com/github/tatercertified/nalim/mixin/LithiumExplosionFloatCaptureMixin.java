package com.github.tatercertified.nalim.mixin;

import com.bawnorton.mixinsquared.TargetHandler;
import com.github.tatercertified.nalim.explosion.ExplosionEngine;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moulberry.mixinconstraints.annotations.IfModLoaded;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Shadow-mode float capture for Lithium-owned sweeps. Lithium's
 * world.explosions.block_raycast restructures calculateExplodedPositions so
 * the per-ray nextFloat draw happens inside ITS handler (performRayCast) —
 * the vanilla-body capture in {@link ServerExplosionMixin} sits in dead code
 * there (observed live: every shadow compare failed with floatCount=0).
 */
@IfModLoaded("lithium")
@Mixin(value = ServerExplosion.class, priority = 1500)
public abstract class LithiumExplosionFloatCaptureMixin {

    @TargetHandler(
            mixin = "net.caffeinemc.mods.lithium.mixin.world.explosions.block_raycast.ServerExplosionMixin",
            name = "performRayCast"
    )
    // require = 0: a Lithium update that renames performRayCast, or block_raycast turned off
    // in lithium.properties, must not stop the server; the shadow then reports floatCount=0.
    @WrapOperation(method = "@MixinSquared:Handler",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;nextFloat()F"),
            require = 0)
    private float rustmi$captureFloatLithium(RandomSource rs, Operation<Float> original) {
        // Volatile pre-check: with shadow off this is the only cost
        // (engine mode takes the sweep over before performRayCast can run).
        if (!ExplosionEngine.isShadow()) {
            return original.call(rs);
        }
        float f = original.call(rs);
        ExplosionEngine.onFloatDrawn(f);
        return f;
    }
}
