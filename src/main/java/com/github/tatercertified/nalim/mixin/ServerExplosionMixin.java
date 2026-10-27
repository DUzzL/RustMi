package com.github.tatercertified.nalim.mixin;

import com.github.tatercertified.nalim.explosion.ExplosionEngine;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

/**
 * ServerExplosion taps for {@link ExplosionEngine}: the sweep takeover (engine mode), the
 * float capture and compare (shadow mode) and the E2 seen-percent path. With engine and
 * shadow off each wrap is a single volatile read and the vanilla original runs untouched.
 */
@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {

    @Shadow @Final private ServerLevel level;
    @Shadow @Final private Vec3 center;
    @Shadow @Final private float radius;
    @Shadow @Final private ExplosionDamageCalculator damageCalculator;
    @Shadow @Final private Entity source;

    /** Context lifetime: snapshot built at the sweep is reused by every
     *  getSeenPercent of the same explosion, cleared on exit. */
    @WrapMethod(method = "explode")
    private int rustmi$explosionContext(Operation<Integer> original) {
        if (com.github.tatercertified.nalim.TestHooks.ENABLED) {
            com.github.tatercertified.nalim.TestHooks.beforeExplosion(this.level);
        }
        if (!ExplosionEngine.isEngine() && !ExplosionEngine.isShadow()) {
            return original.call();
        }
        ExplosionEngine.enterExplosion(this.level, this.center, this.radius, this.damageCalculator, this.source, this);
        try {
            return original.call();
        } finally {
            ExplosionEngine.exitExplosion();
        }
    }

    @WrapMethod(method = "calculateExplodedPositions")
    private List<BlockPos> rustmi$sweep(Operation<List<BlockPos>> original) {
        List<BlockPos> taken = ExplosionEngine.trySweepTakeover();
        if (taken != null) {
            return taken;
        }
        boolean capture = ExplosionEngine.armCapture();
        List<BlockPos> vanilla = original.call();
        if (capture) {
            ExplosionEngine.finishCapture(vanilla);
        }
        return vanilla;
    }

    /** Captures the per-ray nextFloat draws while vanilla runs (shadow mode). */
    @WrapOperation(method = "calculateExplodedPositions",
            at = @org.spongepowered.asm.mixin.injection.At(value = "INVOKE",
                    target = "Lnet/minecraft/util/RandomSource;nextFloat()F"))
    private float rustmi$captureFloat(RandomSource rs, Operation<Float> original) {
        float f = original.call(rs);
        ExplosionEngine.onFloatDrawn(f);
        return f;
    }

    @WrapMethod(method = "getSeenPercent")
    private static float rustmi$seen(Vec3 center, Entity entity, Operation<Float> original) {
        return ExplosionEngine.seenPercent(center, entity, original);
    }
}
