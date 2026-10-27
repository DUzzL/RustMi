package com.github.tatercertified.nalim.mixin;

import com.github.tatercertified.nalim.entity.TickingMemo;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import com.moulberry.mixinconstraints.annotations.IfModAbsent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The only writer of {@code chunkVisibility} (read by {@code canPositionTick}). */
// Only the ticking memo reads this epoch, and it is not applied with Fabrolia.
@IfModAbsent("fabrolia")
@Mixin(PersistentEntitySectionManager.class)
public abstract class EntityVisibilityEpochMixin {

    @Inject(method = "updateChunkStatus(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/entity/Visibility;)V",
            at = {@At("HEAD"), @At("RETURN")})
    private void rustmi$visibilityChanged(CallbackInfo ci) {
        TickingMemo.bump();
    }
}
