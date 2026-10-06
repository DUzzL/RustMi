package com.github.tatercertified.nalim.mixin;

import com.github.tatercertified.nalim.path.PathRecorder;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Set;

/** P0 fixture tap for the pathfinding campaign — see {@link PathRecorder}. */
@Mixin(PathFinder.class)
public abstract class PathFinderMixin {

    @Shadow @Final private NodeEvaluator nodeEvaluator;
    @Shadow private int maxVisitedNodes;

    @WrapMethod(method = "findPath(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;Ljava/util/Set;FIF)Lnet/minecraft/world/level/pathfinder/Path;")
    private Path oxi$recordFindPath(PathNavigationRegion region, Mob mob, Set<BlockPos> targets,
                                    float maxRange, int accuracy, float searchDepthMultiplier,
                                    Operation<Path> original) {
        Path result = original.call(region, mob, targets, maxRange, accuracy, searchDepthMultiplier);
        if (PathRecorder.isRecording()) {
            PathRecorder.record(region, mob, targets, maxRange, accuracy, searchDepthMultiplier,
                    this.nodeEvaluator, this.maxVisitedNodes, result);
        }
        return result;
    }
}
