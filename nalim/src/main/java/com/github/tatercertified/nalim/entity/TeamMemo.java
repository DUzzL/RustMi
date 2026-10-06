package com.github.tatercertified.nalim.entity;

import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.jspecify.annotations.Nullable;

/**
 * Per-entity memo of {@code Entity.getTeam()} = {@code scoreboard.getPlayersTeam(name)}.
 * The answer only changes when a scoreboard's player→team map is written (bumping
 * {@link #currentEpoch()}), when the entity sees another scoreboard, or when its scoreboard name
 * changes (setUUID makes a new String) — all three are part of the memo key.
 */
public record TeamMemo(long epoch, Scoreboard scoreboard, String name, @Nullable PlayerTeam team) {

    /** Atomic: a lost increment could step the counter back onto an already-used value. */
    private static final java.util.concurrent.atomic.AtomicLong CURRENT = new java.util.concurrent.atomic.AtomicLong(1);

    public static long currentEpoch() {
        return CURRENT.get();
    }

    /** Any write to a scoreboard's player→team map. */
    public static void bump() {
        CURRENT.incrementAndGet();
    }

    public boolean matches(long atEpoch, Scoreboard atScoreboard, String atName) {
        return epoch == atEpoch && scoreboard == atScoreboard && name == atName;
    }
}
