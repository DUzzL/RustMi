package com.github.tatercertified.nalim.entity;

import net.minecraft.world.level.Level;

/**
 * Per-entity memo of {@code ServerLevel.isPositionEntityTicking(blockPosition())} =
 * {@code canPositionTick(chunk) && inEntityTickingRange(chunk)}: a function of the chunk key
 * alone while neither the entity-manager visibility map nor the simulation ticket levels
 * (or Fabrolia's completed view of them) change. Every writer bumps {@link #currentEpoch()}.
 */
public record TickingMemo(long epoch, Level level, long chunk, boolean ticking) {

    /** Atomic: a lost increment could step the counter back onto an already-used value. */
    private static final java.util.concurrent.atomic.AtomicLong CURRENT = new java.util.concurrent.atomic.AtomicLong(1);

    public static long currentEpoch() {
        return CURRENT.get();
    }

    public static void bump() {
        CURRENT.incrementAndGet();
    }

    public boolean matches(long atEpoch, Level atLevel, long atChunk) {
        return epoch == atEpoch && level == atLevel && chunk == atChunk;
    }
}
