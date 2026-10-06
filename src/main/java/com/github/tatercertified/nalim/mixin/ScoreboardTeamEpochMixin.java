package com.github.tatercertified.nalim.mixin;

import com.github.tatercertified.nalim.entity.TeamMemo;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The three writers of {@code Scoreboard.teamsByPlayer}; each bumps the team-memo epoch. */
@Mixin(Scoreboard.class)
public abstract class ScoreboardTeamEpochMixin {

    @Inject(method = "removePlayerTeam", at = {@At("HEAD"), @At("RETURN")})
    private void rustmi$teamRemoved(CallbackInfo ci) {
        TeamMemo.bump();
    }

    @Inject(method = "addPlayerToTeam", at = {@At("HEAD"), @At("RETURN")})
    private void rustmi$playerAdded(CallbackInfoReturnable<Boolean> cir) {
        TeamMemo.bump();
    }

    @Inject(method = "removePlayerFromTeam(Ljava/lang/String;Lnet/minecraft/world/scores/PlayerTeam;)V",
            at = {@At("HEAD"), @At("RETURN")})
    private void rustmi$playerRemoved(CallbackInfo ci) {
        TeamMemo.bump();
    }
}
