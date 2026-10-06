package com.github.tatercertified.nalim.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The physical server thread. {@code isSameThread()}/{@code getRunningThread()} are not
 * usable as a "single-threaded state" guard: Fabrolia redirects {@code getRunningThread}
 * to the current region worker during exact player-actor turns and packet replay, so a
 * worker would pass as main and race the real server thread on unsynchronized state.
 */
@Mixin(MinecraftServer.class)
public interface MinecraftServerThreadAccessor {

    @Accessor("serverThread")
    Thread rustmi$serverThread();
}
