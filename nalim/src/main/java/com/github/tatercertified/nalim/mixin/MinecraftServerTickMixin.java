package com.github.tatercertified.nalim.mixin;

import com.github.tatercertified.nalim.cramming.CrammingHooks;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * Hooks the cramming pre-phase into {@code MinecraftServer.tickServer} —
 * fires once per tick BEFORE all level ticks. This avoids needing
 * fabric-api on the nalim subproject's classpath while keeping the same
 * lifecycle as a Fabric START_SERVER_TICK event.
 *
 * <p>Priority 900 is intentionally lower than Fabrolia's 1500 so that
 * Fabrolia's tick orchestration mixins compose later; we only want the
 * HEAD inject to run before any entity-tick batch dispatch.
 */
@Mixin(value = MinecraftServer.class, priority = 900)
public abstract class MinecraftServerTickMixin {

    @Inject(method = "tickServer", at = @At("HEAD"))
    private void rustmi$crammingPreTick(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        CrammingHooks.onTickStart((MinecraftServer) (Object) this);
    }

    @Inject(method = "tickServer", at = @At("RETURN"))
    private void rustmi$crammingPostTick(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        CrammingHooks.onTickEnd((MinecraftServer) (Object) this);
    }
}
