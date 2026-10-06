package com.github.tatercertified.nalim.mixin;

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Scoreboard.class)
public interface ScoreboardTeamsAccessor {
    @Accessor("teamsByPlayer")
    Object2ObjectMap<String, PlayerTeam> rustmi$teamsByPlayer();
}
