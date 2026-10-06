package com.github.tatercertified.nalim.mixin;

import com.github.tatercertified.nalim.collision.CollisionEngine;
import com.github.tatercertified.nalim.entity.TickingMemo;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * {@code LivingEntity.isPushable} asks {@code isPositionEntityTicking} once per neighbour
 * pair of the cramming predicate (two hash lookups each). Memoized per entity, see
 * {@link TickingMemo}; one immutable record behind a volatile field. Under the collision
 * shadow a memo hit is checked against vanilla.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityTickingMemoMixin {

    @Unique private volatile TickingMemo rustmi$tickingMemo;

    /**
     * Off under Fabrolia: it publishes a completed view of the simulation levels that region
     * workers read concurrently with the epoch bump, leaving a few-instruction window where a
     * stale memo could still match; the A/B there showed no gain, so exactness wins. C2ME
     * (0.4.2-alpha.0.41, checked by bytecode) only reads chunkVisibility and the simulation
     * map and changes levels through setLevel, so it needs no gate.
     */
    @Unique private static final boolean RUSTMI$MEMO_ENABLED =
            !net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("fabrolia");

    @WrapOperation(method = "isPushable",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;isPositionEntityTicking(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean rustmi$memoTicking(ServerLevel level, BlockPos pos, Operation<Boolean> original) {
        if (!RUSTMI$MEMO_ENABLED) {
            return original.call(level, pos);
        }
        long epoch = TickingMemo.currentEpoch();
        long chunk = ChunkPos.pack(pos);
        TickingMemo memo = rustmi$tickingMemo;
        if (memo != null && memo.matches(epoch, level, chunk)) {
            if (CollisionEngine.isShadow()) {
                boolean vanilla = original.call(level, pos);
                CollisionEngine.recordPredicateShadow(vanilla == memo.ticking(),
                        "isPositionEntityTicking " + pos.toShortString() + " memo=" + memo.ticking());
                return vanilla;
            }
            return memo.ticking();
        }
        boolean ticking = original.call(level, pos);
        rustmi$tickingMemo = new TickingMemo(epoch, level, chunk, ticking);
        return ticking;
    }
}
