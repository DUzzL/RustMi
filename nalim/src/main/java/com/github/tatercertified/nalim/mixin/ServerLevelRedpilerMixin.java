package com.github.tatercertified.nalim.mixin;

import com.github.tatercertified.nalim.redstone.RedpilerHost;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * Drives every {@link RedpilerHost} session for the level once vanilla's
 * own per-tick block/fluid/BE/chunk passes have run. Sits at
 * {@code ServerLevel.tick} RETURN with mixin priority 2000 — well after
 * Fabrolia's Stage C handlers (LevelMixin/ServerLevelMixin priority 1500),
 * so we observe the state main has fully resolved for this gameTime.
 *
 * <p>Catches every Throwable: a Redpiler bug must not crash the server
 * tick. Each session's per-tick body already has its own try/catch and
 * bails on exception.
 */
@Mixin(value = ServerLevel.class, priority = 2000)
public abstract class ServerLevelRedpilerMixin {

    @Inject(method = "tick", at = @At("RETURN"))
    private void oxi$redpilerTick(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        try {
            RedpilerHost.tickLevel(self, self.getGameTime());
        } catch (Throwable t) {
            // logged at the host level; swallow here so the server keeps running
        }
    }
}
