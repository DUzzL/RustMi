package com.github.tatercertified.nalim.mixin;

import com.github.tatercertified.nalim.collision.CollisionEngine;
import com.github.tatercertified.nalim.entity.TeamMemo;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * {@code Entity.getTeam()} is a scoreboard map lookup by name, asked once per neighbour pair
 * by the cramming predicate. Memoized per entity (see {@link TeamMemo}); the memo is a single
 * immutable record behind a volatile field, so concurrent readers never see a torn key.
 * Under the collision shadow the vanilla body always runs and must agree.
 */
@Mixin(Entity.class)
public abstract class EntityTeamMemoMixin {

    @Unique private volatile TeamMemo rustmi$teamMemo;

    @WrapMethod(method = "getTeam")
    private PlayerTeam rustmi$memoTeam(Operation<PlayerTeam> original) {
        Entity self = (Entity) (Object) this;
        Scoreboard scoreboard = self.level().getScoreboard();
        // No player is on any team: vanilla's map lookup returns null for every name. One
        // read of the shared map, without touching this entity's name or memo.
        if (((ScoreboardTeamsAccessor) scoreboard).rustmi$teamsByPlayer().isEmpty()) {
            if (CollisionEngine.isShadow()) {
                PlayerTeam vanilla = original.call();
                CollisionEngine.recordPredicateShadow(vanilla == null,
                        "getTeam " + self.getType().toShortString() + " emptyScoreboard vanilla=" + vanilla);
                return vanilla;
            }
            return null;
        }
        long epoch = TeamMemo.currentEpoch();
        String name = self.getScoreboardName();
        TeamMemo memo = rustmi$teamMemo;
        if (memo != null && memo.matches(epoch, scoreboard, name)) {
            if (CollisionEngine.isShadow()) {
                PlayerTeam vanilla = original.call();
                CollisionEngine.recordPredicateShadow(vanilla == memo.team(),
                        "getTeam " + self.getType().toShortString() + " memo=" + memo.team() + " vanilla=" + vanilla);
                return vanilla;
            }
            return memo.team();
        }
        PlayerTeam team = original.call();
        rustmi$teamMemo = new TeamMemo(epoch, scoreboard, name, team);
        return team;
    }
}
