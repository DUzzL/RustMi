package com.github.tatercertified.nalim.mixin;

import com.github.tatercertified.nalim.collision.HardColliders;
import com.github.tatercertified.nalim.cramming.CrammingEscapes;
import com.github.tatercertified.nalim.cramming.CrammingTracked;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The entity-section membership choke point:
 * <ul>
 *   <li>stamps each insertion with a global sequence number — a section's
 *       {@code ClassInstanceMultiMap} only appends and removes, so ascending stamps are
 *       exactly vanilla's iteration order inside one section;</li>
 *   <li>keeps the per-level {@link HardColliders} buckets (entities that can be collided
 *       with) in step with section membership.</li>
 * </ul>
 */
@Mixin(EntitySection.class)
public abstract class EntitySectionSeqMixin<T extends EntityAccess> {

    @Inject(method = "add", at = @At("HEAD"))
    private void rustmi$stamp(T entity, CallbackInfo ci) {
        if (entity instanceof CrammingTracked tracked) {
            tracked.rustmi$setSectionSeq(CrammingEscapes.nextSectionSeq());
        }
        if (entity instanceof Entity e) {
            HardColliders.onSectionAdd(e);
        }
    }

    @Inject(method = "remove", at = @At("RETURN"))
    private void rustmi$unindex(T entity, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && entity instanceof Entity e) {
            HardColliders.onSectionRemove(e);
        }
    }
}
