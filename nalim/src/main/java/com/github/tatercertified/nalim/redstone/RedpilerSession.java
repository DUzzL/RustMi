package com.github.tatercertified.nalim.redstone;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Map;

/**
 * One compiled Redpiler network claim — owns the FFI session + every host-
 * side reverse map needed to flush state back into the world. Mutated only
 * on the server thread (claim, tick, release).
 */
public final class RedpilerSession {

    public final ResourceKey<Level> dim;
    public final BlockPos min;
    public final BlockPos max;
    public final long handle;
    public final RedpilerEngine.Session backend;
    /** node-index → world block positions (1:1 with FFI side). */
    public final List<List<BlockPos>> nodeBlocks;
    /** node-index → original BlockState (used as the "template" we mutate
     *  on flush — wire POWER, repeater POWERED/LOCKED, comparator POWERED,
     *  torch/lamp LIT). */
    public final List<List<BlockState>> nodeStates;
    public final Map<BlockPos, Integer> posToIdx;
    /** Synthetic Constant nodes for comparator container back-inputs. The
     *  prepare phase re-reads each source's analog signal each tick. */
    public final Map<Integer, BlockPos> containerSources;
    public final Map<Integer, Direction> containerReadDir;
    /** Boundary inputs — synthetic Constants sampled each tick from
     *  level.getSignal(pos, dir). Captures everything that vanilla would
     *  route into our network from un-modeled blocks (observers, external
     *  diodes, redstone blocks, strong-power-through-solid). */
    public final Map<Integer, BlockPos> boundaryInputPos;
    public final Map<Integer, Direction> boundaryInputDir;
    /** Subset of {@link #boundaryInputPos} keys whose target node is a
     *  redstone wire. The tick refresher uses
     *  {@link RedpilerWalker#sampleBoundaryForWire} instead of plain
     *  {@code level.getSignal} for these, suppressing wire feedback through
     *  conductor neighbours (matches vanilla's {@code shouldSignal=false}
     *  trick in {@code RedstoneWireEvaluator.getBlockSignal}). */
    public final java.util.Set<Integer> wireFedBoundaryConstants;
    public final int[] lastBoundarySignal;
    /** Watch table: changedPos → observer positions whose back is at
     *  changedPos. After Redpiler flush at changedPos, host wakes each
     *  observer (scheduleTick) so vanilla observer behavior triggers
     *  even though our setBlock used flag=2 (no neighbor updates). */
    public final java.util.Map<BlockPos, java.util.List<BlockPos>> observerWatchers;
    /** Union of all positions whose block-identity change must abort the
     *  session: modeled nodes, observer watch positions, boundary-input
     *  source positions. Used by LevelChunkMirrorMixin's bail check so
     *  unrelated block placements inside the AABB (decorative blocks, far-
     *  away dirt, piston motion not touching the redstone graph) don't
     *  needlessly tear down the claim. O(1) hash lookup on the hot path. */
    public final java.util.Set<BlockPos> sensitivePositions;
    /** Per-Constant last-read signal so we only set_input on change. Keyed
     *  by node index, sized to nodeBlocks.size(). */
    public final int[] lastContainerSignal;

    public volatile boolean aborted;
    /** Bail reason captured by the mixin's modeled-position change check,
     *  to be processed at the START of the next {@link RedpilerHost#tickLevel}
     *  invocation. Set instead of bailing in-place because the mixin fires
     *  on {@code LevelChunk.setBlockState} RETURN — BEFORE
     *  {@code Level.setBlock} runs {@code updateNeighborsAt}, so the
     *  cascade hasn't finished changing adjacent wire connection
     *  properties. An immediate sync-rewalk's walker would see stale
     *  wire state; deferring to next-tick lets the cascade fully unwind
     *  before walker reads the world (2026-06-15: ring-collapse bug). */
    public final java.util.concurrent.atomic.AtomicReference<String> deferredBailReason =
            new java.util.concurrent.atomic.AtomicReference<>(null);
    public long ticksProcessed;
    /** Total NodeChange entries drained from the backend (= node-state
     *  changes Redpiler computed). */
    public long changesDrained;
    /** Total Redpiler-flush setBlock calls actually applied to the world. */
    public long writesApplied;
    /** Total setInput calls into the backend from prepare-phase
     *  boundary/container sampling. */
    public long inputUpdates;
    /** Snapshot of the level the session last ticked on — bail uses this to
     *  reinstate scheduled ticks. Always == the ServerLevel for {@link #dim}
     *  while the server is running; null before the first tick. */
    public net.minecraft.server.level.ServerLevel lastTickedLevel;

    public RedpilerSession(ResourceKey<Level> dim, BlockPos min, BlockPos max,
                           long handle, RedpilerEngine.Session backend,
                           List<List<BlockPos>> nodeBlocks,
                           List<List<BlockState>> nodeStates,
                           Map<BlockPos, Integer> posToIdx,
                           Map<Integer, BlockPos> containerSources,
                           Map<Integer, Direction> containerReadDir,
                           Map<Integer, BlockPos> boundaryInputPos,
                           Map<Integer, Direction> boundaryInputDir,
                           java.util.Set<Integer> wireFedBoundaryConstants,
                           java.util.Map<BlockPos, java.util.List<BlockPos>> observerWatchers) {
        this.dim = dim;
        this.min = min.immutable();
        this.max = max.immutable();
        this.handle = handle;
        this.backend = backend;
        this.nodeBlocks = nodeBlocks;
        this.nodeStates = nodeStates;
        this.posToIdx = posToIdx;
        this.containerSources = containerSources;
        this.containerReadDir = containerReadDir;
        this.boundaryInputPos = boundaryInputPos;
        this.boundaryInputDir = boundaryInputDir;
        this.wireFedBoundaryConstants = wireFedBoundaryConstants;
        this.observerWatchers = observerWatchers;
        // Build the sensitive-position set: modeled nodes ∪ observer watch
        // positions ∪ boundary-input source positions.
        java.util.HashSet<BlockPos> sens = new java.util.HashSet<>(
                posToIdx.size() + observerWatchers.size() + boundaryInputPos.size());
        sens.addAll(posToIdx.keySet());
        sens.addAll(observerWatchers.keySet());
        sens.addAll(boundaryInputPos.values());
        this.sensitivePositions = sens;
        this.lastContainerSignal = new int[nodeBlocks.size()];
        java.util.Arrays.fill(this.lastContainerSignal, -1);
        this.lastBoundarySignal = new int[nodeBlocks.size()];
        java.util.Arrays.fill(this.lastBoundarySignal, -1);
    }
}
