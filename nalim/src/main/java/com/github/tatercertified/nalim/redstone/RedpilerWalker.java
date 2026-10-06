package com.github.tatercertified.nalim.redstone;

import com.github.tatercertified.nalim.redstone.RedpilerEngine.Builder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.RedstoneWireBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.RedstoneWallTorchBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.WeightedPressurePlateBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ComparatorMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Snapshot-walker over a claimed AABB that lowers the contained redstone
 * subgraph into the Redpiler {@link Builder}. One-shot: claim → walk →
 * compile. No hot-path responsibilities; we can afford to read every block
 * state in the AABB once and resolve signal connectivity by vanilla rules.
 *
 * <p>Walk policy:
 * <ul>
 *   <li>Every redstone component in the AABB becomes a node.</li>
 *   <li>Edges follow vanilla signal flow: wire ↔ wire (per
 *       {@link RedstoneWireBlock} 6-direction rules), diode back-input,
 *       diode front-output, comparator side-inputs, torch in/out.</li>
 *   <li>Comparator container back-input: a synthetic {@code Constant} node
 *       per container is added; the host's prepare phase re-reads the
 *       container each tick and {@code set_input}s the Constant.</li>
 *   <li>Cross-boundary signal: any wire/diode pointing OUT of the AABB to
 *       another redstone-component block aborts the claim — we can't
 *       simulate signal exchange across the boundary. Boundary writes to
 *       non-component blocks (block UPDATEs) are fine; we emit those
 *       through {@code level.updateNeighborsAt} on flush.</li>
 * </ul>
 *
 * <p>The walker NEVER mutates the world; it builds a Builder graph and
 * returns the result. The caller (RedpilerHost) decides commit/abort.
 */
public final class RedpilerWalker {

    private static final Logger LOGGER = LoggerFactory.getLogger("Rustmi-RedpilerWalk");

    private RedpilerWalker() {}

    public static final class Result {
        public final boolean ok;
        public final String message;
        public final long handle;
        /** node-index → BlockPos list (host needs these for flush). */
        public final List<List<BlockPos>> nodeBlocks;
        /** node-index → original BlockState[] (1:1 with nodeBlocks). */
        public final List<List<BlockState>> nodeStates;
        /** pos → node-index (host needs for input-side maintenance). */
        public final Map<BlockPos, Integer> posToIdx;
        /** Synthetic container-source nodes that prepare phase must keep in
         *  sync. node-index → comparator's container-source pos. */
        public final Map<Integer, BlockPos> containerSources;
        public final Map<Integer, Direction> containerReadDir;
        public final Map<Integer, BlockPos> boundaryInputPos;
        public final Map<Integer, Direction> boundaryInputDir;
        /** Subset of {@link #boundaryInputPos} indices whose target is a
         *  redstone wire. Host MUST sample these with vanilla's
         *  {@code shouldSignal=false} semantics (see
         *  {@link RedpilerWalker#sampleBoundaryForWire}) — otherwise wire
         *  feedback through a conductor neighbour self-loops the wire's own
         *  POWER back into its input histogram via {@code getDirectSignalTo},
         *  and wires "stay on" forever after the real source disconnects.
         *  Symptom from the field (2026-06-15): the user reports note blocks,
         *  dispensers, droppers "creating redstone signal" — they're not
         *  sources, they're solid conductors that propagate strong signal,
         *  and our boundary sampler reads the wire's own signal back through
         *  them. Vanilla's RedstoneWireEvaluator.getBlockSignal sets
         *  shouldSignal=false on the wire singleton precisely to gate this. */
        public final java.util.Set<Integer> wireFedBoundaryConstants;
        public final Map<BlockPos, java.util.List<BlockPos>> observerWatchers;
        public final int totalNodes;
        public final int totalEdges;

        private Result(boolean ok, String message, long handle,
                       List<List<BlockPos>> nodeBlocks,
                       List<List<BlockState>> nodeStates,
                       Map<BlockPos, Integer> posToIdx,
                       Map<Integer, BlockPos> containerSources,
                       Map<Integer, Direction> containerReadDir,
                       Map<Integer, BlockPos> boundaryInputPos,
                       Map<Integer, Direction> boundaryInputDir,
                       java.util.Set<Integer> wireFedBoundaryConstants,
                       Map<BlockPos, java.util.List<BlockPos>> observerWatchers,
                       int totalNodes, int totalEdges) {
            this.ok = ok;
            this.message = message;
            this.handle = handle;
            this.nodeBlocks = nodeBlocks;
            this.nodeStates = nodeStates;
            this.posToIdx = posToIdx;
            this.containerSources = containerSources;
            this.containerReadDir = containerReadDir;
            this.boundaryInputPos = boundaryInputPos;
            this.boundaryInputDir = boundaryInputDir;
            this.wireFedBoundaryConstants = wireFedBoundaryConstants;
            this.observerWatchers = observerWatchers;
            this.totalNodes = totalNodes;
            this.totalEdges = totalEdges;
        }

        static Result fail(String why) {
            return new Result(false, why, 0L, List.of(), List.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), java.util.Set.of(), Map.of(), 0, 0);
        }
    }

    private static final class Acc {
        final Level level;
        final BlockPos min, max;
        final Builder builder;
        // pos → node index (assigned in walk order). Used for both lookup
        // and the "is pos in this network" predicate.
        final Map<BlockPos, Integer> posToIdx = new HashMap<>();
        // node-index parallel list of underlying positions for the FFI's
        // block table. For repeaters/torches/comparators it's just [pos];
        // for Constants we never store any position (they're synthetic).
        final List<List<BlockPos>> nodeBlocks = new ArrayList<>();
        final List<List<BlockState>> nodeStates = new ArrayList<>();
        // (containerNodeIdx → containerSource pos) for prepare-phase sync.
        final Map<Integer, BlockPos> containerSources = new HashMap<>();
        // (containerNodeIdx → Direction read from) — see readContainerSignal.
        final Map<Integer, Direction> containerReadDir = new HashMap<>();
        // Boundary inputs: synthetic Constant nodes whose value is sampled
        // each tick via level.getSignal(pos, dir). Captures observers,
        // external diodes, redstone blocks, strong-power-through-solid —
        // anything vanilla would route into the wire network from outside
        // (or from un-modeled blocks inside) the AABB. boundaryConstantFor
        // dedups by (pos, dir) so the same external source feeding multiple
        // boundary wires shares one Constant.
        final Map<Long, Integer> boundaryConstantFor = new HashMap<>();
        /** Separate dedup pool for wire-fed boundary constants — see
         *  {@link Result#wireFedBoundaryConstants}. Sharing a constant
         *  between a wire target and (say) a repeater's back-input target
         *  is unsound: the wire wants the sample with wire feedback
         *  suppressed, the repeater wants it included. Different pools,
         *  potentially duplicate constants for the same (pos,dir) when both
         *  classes of target exist — small memory cost, correct semantics. */
        final Map<Long, Integer> wireBoundaryConstantFor = new HashMap<>();
        final java.util.Set<Integer> wireFedBoundaryConstants = new java.util.HashSet<>();
        final Map<Integer, BlockPos> boundaryInputPos = new HashMap<>();
        final Map<Integer, Direction> boundaryInputDir = new HashMap<>();
        // Observer wake table — `watchPos → list of observer positions
        // whose back face is at watchPos`. Redpiler-flush at watchPos uses
        // setBlock flag=2 (no neighbor updates), so vanilla's observer
        // detection (ObserverBlock.updateShape on back direction) doesn't
        // fire. The host walks this map after each flush and explicitly
        // scheduleTicks each watching observer — mirrors vanilla
        // ObserverBlock.startSignal.
        final Map<BlockPos, java.util.List<BlockPos>> observerWatchers = new HashMap<>();
        int totalEdges = 0;
        // BlockState identity → opaque blockId (Java holds the reverse map).
        final Map<BlockState, Integer> blockIds = new HashMap<>();

        Acc(Level level, BlockPos min, BlockPos max, Builder builder) {
            this.level = level;
            this.min = min;
            this.max = max;
            this.builder = builder;
        }

        boolean inside(BlockPos p) {
            return p.getX() >= min.getX() && p.getX() <= max.getX()
                    && p.getY() >= min.getY() && p.getY() <= max.getY()
                    && p.getZ() >= min.getZ() && p.getZ() <= max.getZ();
        }

        int blockIdFor(BlockState s) {
            Integer id = blockIds.get(s);
            if (id != null) return id;
            int next = blockIds.size();
            blockIds.put(s, next);
            return next;
        }

        int addNode(byte tag, byte p1, byte p2, byte p3,
                    boolean powered, boolean locked, byte outputStrength,
                    boolean isInput, boolean isOutput,
                    BlockPos pos, BlockState state) {
            int idx = builder.addNode(tag, p1, p2, p3,
                    powered, locked, outputStrength, isInput, isOutput);
            int id = blockIdFor(state);
            builder.addBlock(idx, pos.immutable(), id);
            BlockPos immut = pos.immutable();
            posToIdx.put(immut, idx);
            List<BlockPos> bl = new ArrayList<>(1);
            bl.add(immut);
            nodeBlocks.add(bl);
            List<BlockState> sl = new ArrayList<>(1);
            sl.add(state);
            nodeStates.add(sl);
            return idx;
        }

        /** Find or create a Constant node sampling vanilla signal at
         *  {@code sourcePos} from {@code dir}. Used for boundary inputs;
         *  not for comparator container sources (those use addConstant).
         *  Caller knows the receiver is NOT a redstone wire — for wire
         *  receivers use {@link #wireBoundaryConstant} instead. */
        int boundaryConstant(BlockPos sourcePos, Direction dir) {
            long key = ((long) sourcePos.asLong() << 3) | (dir.ordinal() & 7);
            Integer existing = boundaryConstantFor.get(key);
            if (existing != null) return existing;
            int sig = 0;
            try {
                sig = level.getSignal(sourcePos, dir);
            } catch (Throwable ignored) {}
            int idx = builder.addNode(RedpilerEngine.TAG_CONSTANT,
                    (byte) 0, (byte) 0, (byte) 0,
                    sig > 0, false, (byte) sig, false, false);
            nodeBlocks.add(java.util.List.of());
            nodeStates.add(java.util.List.of());
            boundaryConstantFor.put(key, idx);
            boundaryInputPos.put(idx, sourcePos.immutable());
            boundaryInputDir.put(idx, dir);
            return idx;
        }

        /** Wire-specific boundary constant. Samples with
         *  {@link RedpilerWalker#sampleBoundaryForWire} which replicates
         *  vanilla's {@code shouldSignal=false} trick — wire contributions
         *  through a conductor neighbour are suppressed so the wire's own
         *  POWER doesn't self-loop back into its input histogram via
         *  {@code getDirectSignalTo}. Dedup pool separate from the non-wire
         *  one because the two samplers disagree on the same (pos,dir). */
        int wireBoundaryConstant(BlockPos sourcePos, Direction dir) {
            long key = ((long) sourcePos.asLong() << 3) | (dir.ordinal() & 7);
            Integer existing = wireBoundaryConstantFor.get(key);
            if (existing != null) return existing;
            int sig = 0;
            try {
                sig = sampleBoundaryForWire(level, sourcePos, dir);
            } catch (Throwable ignored) {}
            int idx = builder.addNode(RedpilerEngine.TAG_CONSTANT,
                    (byte) 0, (byte) 0, (byte) 0,
                    sig > 0, false, (byte) sig, false, false);
            nodeBlocks.add(java.util.List.of());
            nodeStates.add(java.util.List.of());
            wireBoundaryConstantFor.put(key, idx);
            boundaryInputPos.put(idx, sourcePos.immutable());
            boundaryInputDir.put(idx, dir);
            wireFedBoundaryConstants.add(idx);
            return idx;
        }

        int addConstant(int outputStrength, BlockPos sourcePos) {
            int idx = builder.addNode(RedpilerEngine.TAG_CONSTANT,
                    (byte) 0, (byte) 0, (byte) 0,
                    outputStrength > 0, false, (byte) outputStrength,
                    false, false);
            // A Constant has no associated world block, but we still must
            // map it to the source pos for prepare-phase set_input drives.
            // Don't push to posToIdx — that map is for tickables.
            nodeBlocks.add(List.of());
            nodeStates.add(List.of());
            containerSources.put(idx, sourcePos.immutable());
            return idx;
        }

        // Dedup set for edges already submitted. Key packs (from:31)(to:31)(side:1)(ss:4)
        // into a long — fits in one map probe.
        //
        // <p>Without this dedup, multiple walker passes can submit the
        // SAME edge for one source/target pair (e.g. wire→repeater BACK
        // is added BOTH by {@link #wireToDiodeSide} during the wire's
        // pass AND by {@link #repeaterEdges} during the repeater's
        // pass). The graph layer doesn't deduplicate either, so two
        // forward_links land in the compiled backend.
        //
        // <p>Symptoms in a ring oscillator (user-reported 2026-06-15
        // "у одного из повторителей задержка на 1 выше чем у других и
        // это приводит к дюпу сигнала"): each set_node fan-out
        // updates the downstream input histogram TWICE (one per
        // duplicate edge) and calls {@link update_node} TWICE. The
        // intermediate histogram state between the two updates can
        // straddle the get_bool_input threshold (bucket[15] = 1 from
        // one of the two contributors → "input present" → no
        // mismatch → no schedule; then the second update zeros it
        // and the schedule fires). In a ring with mismatched delays
        // the staggered intermediate visits compound across cycles
        // and the ring's energy accumulates into a stuck-ON state.
        final java.util.HashSet<Long> seenEdges = new java.util.HashSet<>();

        void addEdge(int from, int to, boolean side, int ss) {
            int clamped = Math.max(0, Math.min(15, ss));
            // Key: from(low 30 bits) | to(next 30 bits) | side(bit 60) | ss(bits 56-59).
            long key = (((long) from) & 0x3FFFFFFFL)
                     | ((((long) to) & 0x3FFFFFFFL) << 30)
                     | (side ? (1L << 60) : 0L)
                     | ((((long) clamped) & 0xFL) << 56);
            if (!seenEdges.add(key)) {
                return;
            }
            builder.addEdge(from, to, side, (byte) clamped);
            totalEdges++;
        }
    }

    /**
     * Walks the AABB and lowers everything into the supplied builder. On
     * failure ({@link Result#ok} == false) the builder will be closed by
     * the caller — the returned message explains why.
     */
    public static Result walk(Level level, BlockPos min, BlockPos max, int maxNodes) {
        long volume = (long)(max.getX() - min.getX() + 1)
                    * (long)(max.getY() - min.getY() + 1)
                    * (long)(max.getZ() - min.getZ() + 1);
        if (volume <= 0 || volume > 2_000_000L) {
            return Result.fail("AABB too large or empty: volume=" + volume);
        }
        Builder builder = RedpilerEngine.begin();
        try {
            Acc acc = new Acc(level, min, max, builder);

            // Pass 1: classify + addNode for every redstone component in AABB.
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    for (int x = min.getX(); x <= max.getX(); x++) {
                        cursor.set(x, y, z);
                        BlockState st = level.getBlockState(cursor);
                        classifyAndAdd(acc, cursor, st);
                        if (acc.nodeBlocks.size() > maxNodes) {
                            return Result.fail("network too large: " + acc.nodeBlocks.size() + " > " + maxNodes);
                        }
                    }
                }
            }

            // Pass 1.5 — halo scan for observers OUTSIDE the AABB whose
            // back face points INTO the AABB. Their wakeups still come
            // from internal state changes (Redpiler flush), so we need
            // them in observerWatchers too. One-block-thick shell on
            // the 6 outer faces.
            scanObserverHalo(acc, min, max);

            // Pass 2: edges. Iterate nodes (snapshot — pass 1 cursor would
            // visit synthetic Constants too).
            int n0 = acc.nodeBlocks.size();
            for (int i = 0; i < n0; i++) {
                List<BlockPos> blocks = acc.nodeBlocks.get(i);
                if (blocks.isEmpty()) continue; // synthetic constant — no edges from world
                BlockPos pos = blocks.get(0);
                BlockState st = acc.nodeStates.get(i).get(0);
                String err = wireUpEdges(acc, i, pos, st);
                if (err != null) {
                    return Result.fail(err);
                }
            }

            int totalNodes = acc.nodeBlocks.size();
            builder.compile();
            return new Result(true, "ok", builder.handle(),
                    acc.nodeBlocks, acc.nodeStates,
                    acc.posToIdx, acc.containerSources,
                    acc.containerReadDir,
                    acc.boundaryInputPos, acc.boundaryInputDir,
                    acc.wireFedBoundaryConstants,
                    acc.observerWatchers,
                    totalNodes, acc.totalEdges);
        } catch (RuntimeException re) {
            try { builder.close(); } catch (Throwable ignored) {}
            return Result.fail("walker exception: " + re.getMessage());
        }
    }

    private static void scanObserverHalo(Acc acc, BlockPos min, BlockPos max) {
        BlockPos.MutableBlockPos c = new BlockPos.MutableBlockPos();
        // -1 face on each axis
        for (int axis = 0; axis < 3; axis++) {
            for (int side = 0; side < 2; side++) {
                int fixed = (side == 0)
                        ? (axis == 0 ? min.getX() - 1 : axis == 1 ? min.getY() - 1 : min.getZ() - 1)
                        : (axis == 0 ? max.getX() + 1 : axis == 1 ? max.getY() + 1 : max.getZ() + 1);
                int u0 = axis == 0 ? min.getY() - 1 : axis == 1 ? min.getX() - 1 : min.getX() - 1;
                int u1 = axis == 0 ? max.getY() + 1 : axis == 1 ? max.getX() + 1 : max.getX() + 1;
                int v0 = axis == 0 ? min.getZ() - 1 : axis == 1 ? min.getZ() - 1 : min.getY() - 1;
                int v1 = axis == 0 ? max.getZ() + 1 : axis == 1 ? max.getZ() + 1 : max.getY() + 1;
                for (int u = u0; u <= u1; u++) {
                    for (int v = v0; v <= v1; v++) {
                        int x = axis == 0 ? fixed : axis == 1 ? u : u;
                        int y = axis == 0 ? u : axis == 1 ? fixed : v;
                        int z = axis == 0 ? v : axis == 1 ? v : fixed;
                        c.set(x, y, z);
                        BlockState st = acc.level.getBlockState(c);
                        if (!(st.getBlock() instanceof ObserverBlock)) continue;
                        Direction facing = st.getValue(BlockStateProperties.FACING);
                        BlockPos watch = c.immutable().relative(facing);
                        // Only care if watch pos is INSIDE the AABB — those
                        // are the changes Redpiler flushes silently.
                        if (watch.getX() >= min.getX() && watch.getX() <= max.getX()
                                && watch.getY() >= min.getY() && watch.getY() <= max.getY()
                                && watch.getZ() >= min.getZ() && watch.getZ() <= max.getZ()) {
                            acc.observerWatchers.computeIfAbsent(watch,
                                    k -> new java.util.ArrayList<>(1)).add(c.immutable());
                        }
                    }
                }
            }
        }
    }

    /** Full vanilla {@code DiodeBlock.shouldPrioritize} predicate, used to
     *  compute the {@code facing_diode} flag for the priority-bump logic
     *  ({@code TickPriority.EXTREMELY_HIGH} when chained with another
     *  forward diode).
     *
     *  <p>Vanilla bytecode (verified 2026-06-15, MC 26.1 mojmap):
     *  <ol>
     *    <li>{@code direction = state.FACING.opposite()} — the FRONT direction
     *    <li>Read block at {@code pos.relative(direction)} (front neighbor)
     *    <li>If neighbor is not a diode → return false
     *    <li>{@code if neighbor.FACING == direction → return false}
     *  </ol>
     *
     *  <p>The step-4 check filters out the case where the front neighbor is
     *  itself a diode but is OUTPUTTING TOWARD US (its BACK is in our
     *  FRONT direction, so its output direction = our FACING which is the
     *  opposite of the chain direction). Such "reverse-feeding" neighbors
     *  are NOT downstream chain partners and shouldn't trigger the
     *  priority bump.
     *
     *  <p>Earlier walker omitted step 4 — it only ran isDiode. That caused
     *  spurious EXTREMELY_HIGH priority in unusual feedback configurations
     *  (e.g. a comparator pointing back into a repeater's front face);
     *  matching vanilla precisely keeps tick ordering identical in those
     *  edge cases. */
    private static boolean computeFacingDiode(net.minecraft.world.level.Level level,
                                              BlockPos pos, Direction facing) {
        Direction frontDir = facing.getOpposite();
        BlockPos frontPos = pos.relative(frontDir);
        net.minecraft.world.level.block.state.BlockState frontState = level.getBlockState(frontPos);
        net.minecraft.world.level.block.Block frontBlock = frontState.getBlock();
        if (!(frontBlock instanceof RepeaterBlock) && !(frontBlock instanceof ComparatorBlock)) {
            return false;
        }
        Direction neighborFacing = frontState.getValue(BlockStateProperties.HORIZONTAL_FACING);
        if (neighborFacing == frontDir) {
            // Reverse-feeding neighbor — its BACK is in our FRONT direction,
            // i.e. it's outputting toward us. Not a chain partner.
            return false;
        }
        return true;
    }

    // ── Pass 1 — node classification ───────────────────────────────────────

    private static void classifyAndAdd(Acc acc, BlockPos pos, BlockState st) {
        Block b = st.getBlock();
        if (b instanceof RedstoneWireBlock) {
            int power = st.getValue(RedstoneWireBlock.POWER);
            acc.addNode(RedpilerEngine.TAG_WIRE, (byte) 0, (byte) 0, (byte) 0,
                    power > 0, false, (byte) power, false, false, pos, st);
        } else if (b instanceof RepeaterBlock) {
            // Vanilla repeater delay is `DELAY_property × 2` game ticks
            // (RepeaterBlock.getDelay does iconst_2 imul on the property).
            // Our Rust scheduler ticks every game tick, so we must pass the
            // game-tick delay verbatim — otherwise everything runs 2× too
            // fast. Verified against bytecode 2026-06-15. DELAY range
            // 1..4 → tick range 2..8 game ticks. Fits the 16-slot ring.
            int delay = st.getValue(BlockStateProperties.DELAY) * 2;
            boolean powered = st.getValue(BlockStateProperties.POWERED);
            boolean locked = st.getValue(BlockStateProperties.LOCKED);
            // facing_diode = vanilla DiodeBlock.shouldPrioritize (full
            // semantic): block at pos.relative(FACING.opposite()) — i.e.
            // the FRONT neighbor — is a diode AND its FACING does not
            // point back at us. See {@link #computeFacingDiode} for the
            // bytecode-verified spec.
            boolean facingDiode = computeFacingDiode(acc.level, pos,
                    st.getValue(BlockStateProperties.HORIZONTAL_FACING));
            acc.addNode(RedpilerEngine.TAG_REPEATER,
                    (byte) delay, (byte) (facingDiode ? 1 : 0), (byte) 0,
                    powered, locked, (byte)(powered ? 15 : 0), false, true, pos, st);
        } else if (b instanceof ComparatorBlock) {
            ComparatorMode mode = st.getValue(ComparatorBlock.MODE);
            boolean powered = st.getValue(BlockStateProperties.POWERED);
            int output = 0;
            BlockEntity be = acc.level.getBlockEntity(pos);
            if (be instanceof net.minecraft.world.level.block.entity.ComparatorBlockEntity cbe) {
                output = cbe.getOutputSignal();
            }
            // Same facing_diode rule as repeater (DiodeBlock.shouldPrioritize
            // is inherited unmodified by ComparatorBlock).
            boolean facingDiode = computeFacingDiode(acc.level, pos,
                    st.getValue(BlockStateProperties.HORIZONTAL_FACING));
            acc.addNode(RedpilerEngine.TAG_COMPARATOR,
                    (byte)(mode == ComparatorMode.SUBTRACT ? 1 : 0),
                    /* far_input encoded later (0 = none) */ (byte) 0,
                    (byte) (facingDiode ? 1 : 0),
                    powered, false, (byte) output, false, true, pos, st);
        } else if (b instanceof RedstoneTorchBlock) {
            boolean lit = st.getValue(BlockStateProperties.LIT);
            acc.addNode(RedpilerEngine.TAG_TORCH,
                    (byte) 0, (byte) 0, (byte) 0,
                    lit, false, (byte)(lit ? 15 : 0), false, true, pos, st);
        } else if (b instanceof RedstoneLampBlock) {
            boolean lit = st.getValue(BlockStateProperties.LIT);
            acc.addNode(RedpilerEngine.TAG_LAMP,
                    (byte) 0, (byte) 0, (byte) 0,
                    lit, false, (byte) 0, true, true, pos, st);
        } else if (b instanceof LeverBlock) {
            boolean powered = st.getValue(BlockStateProperties.POWERED);
            acc.addNode(RedpilerEngine.TAG_LEVER,
                    (byte) 0, (byte) 0, (byte) 0,
                    powered, false, (byte)(powered ? 15 : 0), true, false, pos, st);
        } else if (b instanceof ButtonBlock) {
            boolean powered = st.getValue(BlockStateProperties.POWERED);
            acc.addNode(RedpilerEngine.TAG_BUTTON,
                    (byte) 0, (byte) 0, (byte) 0,
                    powered, false, (byte)(powered ? 15 : 0), true, false, pos, st);
        } else if (b instanceof BasePressurePlateBlock) {
            int power = 0;
            if (b instanceof WeightedPressurePlateBlock) {
                power = st.getValue(BlockStateProperties.POWER);
            } else if (st.hasProperty(BlockStateProperties.POWERED)
                    && st.getValue(BlockStateProperties.POWERED)) {
                power = 15;
            }
            acc.addNode(RedpilerEngine.TAG_PRESSURE_PLATE,
                    (byte) 0, (byte) 0, (byte) 0,
                    power > 0, false, (byte) power, true, false, pos, st);
        } else if (b == net.minecraft.world.level.block.Blocks.REDSTONE_BLOCK) {
            // Block of Redstone — always emits 15 in every direction. In
            // vanilla {@code RedstoneBlock.getSignal} returns 15
            // unconditionally and {@code isSignalSource()} is true. Model
            // as a constant 15-emitting source so wires feeding from it
            // get the right histogram bucket at compile time AND don't
            // depend on the boundary refresher (which would treat it as
            // an un-modeled neighbour).
            //
            // <p>User report (2026-06-15): "Редстоун блок вообще не
            // выдаёт сигнал редстоуна — поставил, окружил пылью, она
            // показывает 0". The boundary sampler path SHOULD have
            // returned 15 (RedstoneBlock.getSignal=15 → sampleBoundaryForWire
            // returns 15) but apparently doesn't reach the wire in time
            // for the user's perception. Modelling explicitly as a node
            // sidesteps the boundary mechanism for this static source.
            acc.addNode(RedpilerEngine.TAG_CONSTANT,
                    (byte) 0, (byte) 0, (byte) 0,
                    true, false, (byte) 15, true, false, pos, st);
        } else if (b instanceof ObserverBlock) {
            // NOT modeled as a Redpiler node (no Observer in MCHPRS IR).
            // Register watch table only so flush wakes it. Observer tick
            // continues to fire via vanilla scheduled-tick path.
            // Vanilla convention: observer.FACING = direction the eye
            // points (watched block direction). Watched block at
            // pos.relative(FACING). Output at pos.relative(FACING.opposite()).
            // Earlier code had this inverted, so observer wakes triggered
            // on wrong positions.
            Direction facing = st.getValue(BlockStateProperties.FACING);
            BlockPos watch = pos.relative(facing);
            acc.observerWatchers.computeIfAbsent(watch.immutable(),
                    k -> new java.util.ArrayList<>(1)).add(pos.immutable());
        }
        // NoteBlock, Trapdoor: deliberately skipped for MVP.
    }

    // ── Pass 2 — edges ────────────────────────────────────────────────────

    /** Returns null on success, otherwise a bail message. */
    private static String wireUpEdges(Acc acc, int idx, BlockPos pos, BlockState st) {
        Block b = st.getBlock();
        if (b instanceof RedstoneWireBlock) {
            return wireEdges(acc, idx, pos);
        } else if (b instanceof RepeaterBlock) {
            return repeaterEdges(acc, idx, pos, st);
        } else if (b instanceof ComparatorBlock) {
            return comparatorEdges(acc, idx, pos, st);
        } else if (b instanceof RedstoneTorchBlock) {
            return torchEdges(acc, idx, pos, st);
        } else if (b instanceof RedstoneLampBlock) {
            return lampEdges(acc, idx, pos);
        } else if (b instanceof LeverBlock || b instanceof ButtonBlock
                || b instanceof BasePressurePlateBlock) {
            // Inputs only — output edges from these to adjacent components.
            return sourceOutEdges(acc, idx, pos);
        } else if (b == net.minecraft.world.level.block.Blocks.REDSTONE_BLOCK) {
            // Block of Redstone — emits 15 in every direction. Same
            // pass-2 edge generation as lever/button/plate: walk 6
            // cardinal neighbours, add default edges into adjacent
            // wires/lamps/diodes (the {@code shouldAddDefaultOutputTo}
            // gate inside {@code sourceOutEdges} handles direction
            // rules for diodes' back-input convention).
            return sourceOutEdges(acc, idx, pos);
        }
        return null;
    }

    /** Wire edges per vanilla connection rules (horizontal + diagonals).
     *  Wires are bidirectional: every connection produces TWO directed edges
     *  (from→to and to→from), each with ss=1 (one-block degradation). */
    private static String wireEdges(Acc acc, int idx, BlockPos pos) {
        BlockState aboveSelf = acc.level.getBlockState(pos.above());
        boolean aboveSelfSolid = aboveSelf.isRedstoneConductor(acc.level, pos.above());
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos n = pos.relative(dir);
            BlockState nState = acc.level.getBlockState(n);
            wireConnect(acc, idx, pos, n, nState);
            boolean nSolid = nState.isRedstoneConductor(acc.level, n);
            if (nSolid && !aboveSelfSolid) {
                BlockPos up = n.above();
                wireConnect(acc, idx, pos, up, acc.level.getBlockState(up));
            } else if (!nSolid) {
                BlockPos down = n.below();
                wireConnect(acc, idx, pos, down, acc.level.getBlockState(down));
            }
            // Wire side-couples into diodes (back input) or torches' support
            // block exiting the side.
            wireToDiodeSide(acc, idx, pos, n, nState, dir);
        }
        // Boundary inputs: any of wire's 6 cardinal neighbors that isn't a
        // modeled redstone node becomes a synthetic Constant whose value is
        // refreshed each tick. Captures observers, external diodes'
        // outputs, redstone blocks, strong-power-through-solid conductors —
        // all the un-modeled sources that would otherwise drop on the floor
        // when DefaultRedstoneEvaluatorMixin cancels the wire's vanilla
        // eval. The wire reads "external influence" through these Constants
        // every tick.
        //
        // Wire-feedback suppression: uses {@link Acc#wireBoundaryConstant}
        // which samples with vanilla's {@code shouldSignal=false} semantics
        // — wire contributions through conductors don't leak into the
        // sample, mirroring {@code RedstoneWireEvaluator.getBlockSignal}.
        // Without this, a wire next to any solid conductor (note block,
        // dispenser, dropper, slab, ...) reads its own POWER back through
        // the conductor via {@code getDirectSignalTo} and self-sustains.
        //
        // Direction convention (matches vanilla SignalGetter.getBestNeighborSignal):
        // direction passed to level.getSignal(neighborPos, dir) is the SAME
        // direction we used to walk from asker to neighbor — i.e. d
        // (wire→neighbor), NOT d.getOpposite(). E.g. for an observer with
        // FACING==d (observer pointing back at the wire, output going the
        // OTHER way → wire is in observer's FRONT which is opposite-of-FACING),
        // observer.getSignal(neighborPos, d) returns POWERED && FACING==d.
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            if (acc.posToIdx.containsKey(n)) continue;
            int cIdx = acc.wireBoundaryConstant(n, d);
            acc.addEdge(cIdx, idx, false, 0);
        }
        return null;
    }

    /** Sample {@code level.getSignal(pos, dir)} with vanilla's {@code
     *  RedstoneWireBlock.shouldSignal=false} trick applied — wire-emitted
     *  signal is excluded from the sample, both the block's own weak
     *  signal and any wire-contributed strong signal that would otherwise
     *  leak through a conductor's {@code getDirectSignalTo}.
     *
     *  <p>Why a manual replication and not a singleton-field toggle: the
     *  field is shared across all RedstoneWireBlock evaluations server-
     *  wide; mutating it would race with concurrent vanilla redstone work
     *  on other threads (Fabrolia island lanes, parallel evaluator etc.).
     *  Re-implementing the trick is one extra loop and stays thread-safe.
     *
     *  <p>Equivalence sketch (matches what {@code shouldSignal=false} does
     *  in vanilla):
     *  <ul>
     *    <li>If {@code pos} is a wire — returns 0 ({@code wire.getSignal}
     *        is gated on {@code shouldSignal} in vanilla; with it false
     *        the wire emits nothing).</li>
     *    <li>If {@code pos} is not a conductor — returns the block's own
     *        weak signal (non-wire sources still emit their own signal
     *        through {@code state.getSignal}).</li>
     *    <li>If {@code pos} is a conductor — manually iterates the 6
     *        neighbours of {@code pos}, skipping any wire neighbour (a wire's
     *        {@code getDirectSignal} would return 0 under
     *        shouldSignal=false), and takes the max strong contribution.
     *        Same one-conductor-hop semantics as vanilla
     *        {@code SignalGetter.getDirectSignalTo}.</li>
     *  </ul>
     */
    static int sampleBoundaryForWire(net.minecraft.world.level.Level level,
                                      BlockPos pos, Direction dir) {
        net.minecraft.world.level.block.state.BlockState st = level.getBlockState(pos);
        if (st.getBlock() instanceof RedstoneWireBlock) {
            // Sampling the wire itself — shouldSignal=false gates the wire's
            // own getSignal to 0. No conductor path either (wires aren't
            // conductors).
            return 0;
        }
        int weak;
        try {
            weak = st.getSignal(level, pos, dir);
        } catch (Throwable ignored) {
            weak = 0;
        }
        if (weak >= 15) return 15;
        if (!st.isRedstoneConductor(level, pos)) {
            return weak;
        }
        int strong = weak;
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            net.minecraft.world.level.block.state.BlockState ns = level.getBlockState(n);
            if (ns.getBlock() instanceof RedstoneWireBlock) continue;
            int s;
            try {
                s = ns.getDirectSignal(level, n, d);
            } catch (Throwable ignored) {
                s = 0;
            }
            if (s >= 15) return 15;
            if (s > strong) strong = s;
        }
        return strong;
    }

    private static void wireConnect(Acc acc, int from, BlockPos fromPos,
                                    BlockPos to, BlockState toState) {
        Integer toIdx = acc.posToIdx.get(to);
        if (toIdx == null) return;
        Block tb = toState.getBlock();
        if (tb instanceof RedstoneWireBlock) {
            // Wire-wire bidirectional. addEdge in BOTH directions; the
            // pass-2 loop will also process the partner and add the
            // counterpart edge then, but the symmetric counterpart from
            // here is fine — MCHPRS direct backend dedups by direction.
            acc.addEdge(from, toIdx, false, 1);
        }
    }

    /** Wire → diode (repeater/comparator) when the wire is in the back-input
     *  slot, OR → comparator side input.
     *
     *  <p>Vanilla diode convention (verified via DiodeBlock.getInputSignal
     *  bytecode 2026-06-15): {@code pos.relative(FACING)} is the BACK (where
     *  the diode reads its input). Output goes the OPPOSITE direction.
     *  Visually the repeater's arrow points AWAY from FACING (toward the
     *  output). So a wire in the diode's back is at {@code diode.pos.relative(FACING)},
     *  which means from the wire's POV the diode is in direction
     *  {@code FACING.getOpposite()} — i.e. {@code wireToNeighbour == FACING.getOpposite()}. */
    private static void wireToDiodeSide(Acc acc, int wireIdx, BlockPos wirePos,
                                        BlockPos neighbourPos, BlockState neighbourState,
                                        Direction wireToNeighbour) {
        Block nb = neighbourState.getBlock();
        if (!(nb instanceof DiodeBlock)) return;
        Integer diodeIdx = acc.posToIdx.get(neighbourPos);
        if (diodeIdx == null) return;
        Direction facing = neighbourState.getValue(BlockStateProperties.HORIZONTAL_FACING);
        if (wireToNeighbour == facing.getOpposite()) {
            // wire is BEHIND the diode (its back) — back input
            acc.addEdge(wireIdx, diodeIdx, false, 0);
        } else if (nb instanceof ComparatorBlock
                && (wireToNeighbour == facing.getClockWise()
                 || wireToNeighbour == facing.getCounterClockWise())) {
            // wire is to the comparator's side — side input.
            acc.addEdge(wireIdx, diodeIdx, true, 0);
        }
    }

    private static String repeaterEdges(Acc acc, int idx, BlockPos pos, BlockState st) {
        Direction facing = st.getValue(BlockStateProperties.HORIZONTAL_FACING);
        // Vanilla diode convention: pos.relative(FACING) = BACK (input).
        // Output goes opposite direction. (DiodeBlock.getInputSignal reads
        // signal at pos.relative(FACING).) Earlier walker had this swapped,
        // causing signal to flow BACKWARDS through repeaters.
        BlockPos backPos = pos.relative(facing);
        Integer backIdx = acc.posToIdx.get(backPos);
        if (backIdx != null) {
            // ss=0 (diode input has no degradation).
            acc.addEdge(backIdx, idx, false, 0);
        } else {
            // Un-modeled back source — captured as boundary Constant.
            // Repeater at R asks "what signal does backPos give me?".
            // Direction = R→backPos = facing.
            int cIdx = acc.boundaryConstant(backPos, facing);
            acc.addEdge(cIdx, idx, false, 0);
        }
        // Side inputs (lock): adjacent diodes pointing INTO this one's sides.
        // Vanilla convention (FACING = BACK direction of the side-diode): for
        // the side-diode D at our.relative(side) to be OUTPUTTING toward us,
        // D's output direction = D.FACING.opposite() must equal the direction
        // FROM D TO our position = side.getOpposite(). So D.FACING.opposite()
        // == side.getOpposite(), i.e. D.FACING == side. Earlier code checked
        // FACING == side.getOpposite() (inverted), which captured DIODES
        // POINTING AWAY from us as lock sources — causing the user's bug
        // where unrelated flickering circuits cross-talked into the lock
        // input of a nearby repeater and froze it ON.
        for (Direction side : new Direction[]{ facing.getClockWise(), facing.getCounterClockWise() }) {
            BlockPos sp = pos.relative(side);
            Integer si = acc.posToIdx.get(sp);
            if (si == null) continue;
            BlockState s = acc.level.getBlockState(sp);
            if (s.getBlock() instanceof DiodeBlock) {
                Direction sf = s.getValue(BlockStateProperties.HORIZONTAL_FACING);
                if (sf == side) {
                    acc.addEdge(si, idx, true, 0);
                }
            }
        }
        // Repeater output: FRONT block at pos.relative(FACING.opposite())
        // per vanilla convention. Add default edge only if the target
        // genuinely reads this position as a default input (see
        // shouldAddDefaultOutputTo). Earlier code added the edge to ANY
        // frontIdx; if the frontIdx happened to be another diode whose
        // BACK was elsewhere, the duplicate edge corrupted that diode's
        // default-input histogram and could schedule spurious toggles
        // even while the lock-side edge held the diode locked.
        BlockPos frontPos = pos.relative(facing.getOpposite());
        Integer frontIdx = acc.posToIdx.get(frontPos);
        if (frontIdx != null && shouldAddDefaultOutputTo(acc, pos, frontPos)) {
            acc.addEdge(idx, frontIdx, false, 0);
        }
        return null;
    }

    private static String comparatorEdges(Acc acc, int idx, BlockPos pos, BlockState st) {
        Direction facing = st.getValue(BlockStateProperties.HORIZONTAL_FACING);
        // Vanilla convention: pos.relative(FACING) = BACK (input). For
        // comparators back-input can be either a redstone source OR a
        // container 1 block behind whose analog signal we sample.
        BlockPos back = pos.relative(facing);
        BlockState backSt = acc.level.getBlockState(back);
        Integer backIdx = acc.posToIdx.get(back);
        if (backIdx != null) {
            acc.addEdge(backIdx, idx, false, 0);
        } else {
            // Try as a container source — model as synthetic Constant. The
            // read direction passed to getAnalogOutputSignal mirrors vanilla
            // ComparatorBlock.getInputSignal: it passes its own FACING (the
            // direction toward the back container) — same here.
            int sig = readContainerSignal(acc, back, backSt, facing);
            if (sig >= 0) {
                int cIdx = acc.addConstant(sig, back);
                acc.addEdge(cIdx, idx, false, 0);
                acc.containerReadDir.put(cIdx, facing);
            } else {
                // Not a container — model as boundary signal source.
                // Comparator at C asks "what signal does back give me?".
                // Direction passed = C→back = facing.
                int cIdx = acc.boundaryConstant(back, facing);
                acc.addEdge(cIdx, idx, false, 0);
            }
        }
        // Side inputs.
        for (Direction side : new Direction[]{ facing.getClockWise(), facing.getCounterClockWise() }) {
            BlockPos sp = pos.relative(side);
            Integer si = acc.posToIdx.get(sp);
            if (si != null) {
                acc.addEdge(si, idx, true, 0);
            } else {
                // Comparator at C asks side neighbor. Direction = C→sp = side.
                int cIdx = acc.boundaryConstant(sp, side);
                acc.addEdge(cIdx, idx, true, 0);
            }
        }
        // Comparator output: FRONT block at pos.relative(FACING.opposite())
        // per vanilla convention. Same gate as repeaterEdges — only add
        // if target accepts this position as default input.
        BlockPos front = pos.relative(facing.getOpposite());
        Integer frontIdx = acc.posToIdx.get(front);
        if (frontIdx != null && shouldAddDefaultOutputTo(acc, pos, front)) {
            acc.addEdge(idx, frontIdx, false, 0);
        }
        return null;
    }

    /** Decide whether a signal-emitting source at {@code sourcePos} should
     *  be wired as a DEFAULT (back) input edge into the modeled node at
     *  {@code targetPos}. For diodes (repeater / comparator) the source
     *  must literally be at the target's BACK position
     *  ({@code target.relative(target.FACING)} per the corrected vanilla
     *  convention). For torches: source must be at the torch's support
     *  block (below for floor, opposite-of-FACING for wall). For
     *  wires / lamps / pressure plates: any adjacent source counts as a
     *  default input (they read by getBestNeighborSignal across all
     *  directions). Observers don't receive signals at all.
     *
     *  <p>Without this gate, a repeater's output edge to its front block
     *  ends up wiring into a SIDE-positioned target diode as if it were
     *  the back input — corrupting the lock-only side relationship. */
    private static boolean shouldAddDefaultOutputTo(Acc acc, BlockPos sourcePos, BlockPos targetPos) {
        BlockState target = acc.level.getBlockState(targetPos);
        Block b = target.getBlock();
        if (b instanceof RedstoneWireBlock
                || b instanceof RedstoneLampBlock
                || b instanceof BasePressurePlateBlock
                || b instanceof LeverBlock
                || b instanceof ButtonBlock) {
            return true;
        }
        if (b instanceof DiodeBlock) {
            Direction tf = target.getValue(BlockStateProperties.HORIZONTAL_FACING);
            return targetPos.relative(tf).equals(sourcePos);
        }
        if (b instanceof RedstoneTorchBlock) {
            BlockPos support;
            if (b instanceof RedstoneWallTorchBlock) {
                Direction tf = target.getValue(BlockStateProperties.HORIZONTAL_FACING);
                support = targetPos.relative(tf.getOpposite());
            } else {
                support = targetPos.below();
            }
            return support.equals(sourcePos);
        }
        // Observer doesn't receive default redstone input.
        return false;
    }

    /** Reads container analog signal via vanilla's hasAnalogOutputSignal +
     *  getAnalogOutputSignal, or -1 if the block isn't a container source.
     *  The {@code readDir} is the direction TOWARD the comparator (i.e. the
     *  side of the container the comparator is reading) — vanilla's
     *  ComparatorBlock.getInputSignal passes FACING here (the comparator's
     *  FACING which points AWAY from comparator toward its back input). */
    private static int readContainerSignal(Acc acc, BlockPos pos, BlockState st, Direction readDir) {
        if (!st.hasAnalogOutputSignal()) {
            return -1;
        }
        try {
            return st.getAnalogOutputSignal(acc.level, pos, readDir);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String torchEdges(Acc acc, int idx, BlockPos pos, BlockState st) {
        Block b = st.getBlock();
        // Support block: below for floor torch, opposite of FACING for wall.
        BlockPos support;
        if (b instanceof RedstoneWallTorchBlock) {
            Direction facing = st.getValue(BlockStateProperties.HORIZONTAL_FACING);
            support = pos.relative(facing.getOpposite());
        } else {
            support = pos.below();
        }
        Integer supportIdx = acc.posToIdx.get(support);
        if (supportIdx != null) {
            // Torch reads "redstone block component at its support" — model
            // as direct edge. Diode/wire/etc. at the support feeds the torch.
            acc.addEdge(supportIdx, idx, false, 0);
        } else {
            // Un-modeled support — model as boundary input. Direction
            // passed to getSignal is asker→neighbor = torch→support.
            // Floor torch: support is BELOW → DOWN. Wall torch: support
            // is OPPOSITE of head-facing → FACING.opposite() (where the
            // wall is from the torch's perspective).
            Direction toSupport = (b instanceof RedstoneWallTorchBlock)
                    ? st.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite()
                    : Direction.DOWN;
            int cIdx = acc.boundaryConstant(support, toSupport);
            acc.addEdge(cIdx, idx, false, 0);
        }
        // Output: 4 horizontal + above (NOT into support). Default-edge
        // gate to avoid wiring torch output into a target's side input.
        for (Direction d : Direction.values()) {
            if (d.getAxis() == Direction.Axis.Y && d == Direction.DOWN) continue;
            BlockPos out = pos.relative(d);
            if (out.equals(support)) continue;
            Integer oi = acc.posToIdx.get(out);
            if (oi != null && shouldAddDefaultOutputTo(acc, pos, out)) {
                acc.addEdge(idx, oi, false, 0);
            }
        }
        return null;
    }

    private static String lampEdges(Acc acc, int idx, BlockPos pos) {
        // Lamp accepts input from 6 directions; no output.
        // Direction passed to boundaryConstant = lamp→neighbor = d (the
        // iteration direction itself, NOT its opposite — matches vanilla
        // SignalGetter.getBestNeighborSignal convention).
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            Integer ni = acc.posToIdx.get(n);
            if (ni != null) {
                acc.addEdge(ni, idx, false, 0);
            } else {
                int cIdx = acc.boundaryConstant(n, d);
                acc.addEdge(cIdx, idx, false, 0);
            }
        }
        return null;
    }

    private static String sourceOutEdges(Acc acc, int idx, BlockPos pos) {
        // Source blocks (lever/button/plate) feed adjacent components.
        // Default-edge gate prevents the output from being wired into a
        // target's lock-only side input.
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            Integer ni = acc.posToIdx.get(n);
            if (ni != null && shouldAddDefaultOutputTo(acc, pos, n)) {
                acc.addEdge(idx, ni, false, 0);
            }
        }
        return null;
    }
}
