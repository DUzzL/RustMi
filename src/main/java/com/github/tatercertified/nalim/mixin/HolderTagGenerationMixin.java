package com.github.tatercertified.nalim.mixin;

import com.github.tatercertified.nalim.entity.ClimbCache;
import net.minecraft.core.Holder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@code Holder.Reference.bindTags} is the only writer of a holder's tag set. */
@Mixin(Holder.Reference.class)
public abstract class HolderTagGenerationMixin {

    @Inject(method = "bindTags", at = @At("HEAD"))
    private void rustmi$tagsRebinding(CallbackInfo ci) {
        ClimbCache.onTagsBound();
    }

    @Inject(method = "bindTags", at = @At("RETURN"))
    private void rustmi$tagsRebound(CallbackInfo ci) {
        ClimbCache.onTagsBound();
    }
}
