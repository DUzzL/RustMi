package com.github.tatercertified.nalim.path;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * P0 of the pathfinding campaign (project_rust_pathfinding): fixture recorder
 * for {@code PathFinder.findPath}. v3 captures everything the Rust kernel
 * needs to replay a WalkNodeEvaluator search bit-exactly WITHOUT a world:
 *
 * <ul>
 *   <li>{@code typeGrid} — per-cell {@code WalkNodeEvaluator.getPathTypeStatic}
 *       (the static classification incl. below-block switch and 3x3x3 neighbor
 *       upgrades), byte per cell;</li>
 *   <li>{@code emptyBits/waterBits/airBits/passLandBits} — per-cell bitsets:
 *       collision shape empty, fluid in {@code #entity_floatable} (26.3: the
 *       tag {@code getFloorLevel} and the {@code getStart} float column test;
 *       key name kept from v3), state.isAir, isPathfindable(LAND)
 *       (feet-Y derivation, start-node resolution, swim column walk);</li>
 *   <li>{@code shapes} — sparse AABB lists for cells whose collision shape is
 *       neither empty nor a full cube (slabs/stairs/fences: feet-Y partial
 *       levels + the width&lt;1 jump box-collision check);</li>
 *   <li>evaluator flags (canPassDoors/canOpenDoors/canFloat/canWalkOverFences)
 *       and mob state (onGround/inWater = {@code isInFloatableFluid()}/
 *       canStandOnFluid/stepHeight/maxFall);</li>
 *   <li>the query (targets/range/accuracy/depth, malus table) and vanilla's
 *       resulting Path — the arbiter.</li>
 * </ul>
 *
 * <p>Thread model: findPath runs on region workers (mob AI). One capture at a
 * time ({@link #BUSY}): concurrent full-grid passes once outran ZGC (14
 * workers x 617k VoxelShape allocations) — heap to zero + allocation-stall
 * spin (live 2026-06-12).
 */
public final class PathRecorder {

    private static final Logger LOGGER = LoggerFactory.getLogger("Rustmi-Path");
    private static final int MAX_FIXTURES = 40;
    private static final int MAX_CELLS = 700_000;

    private static final java.nio.file.Path OUT_DIR = Paths.get("rustmi-path-fixtures");
    private static final AtomicInteger FIXTURES = new AtomicInteger();
    private static volatile boolean record;
    private static final AtomicBoolean BUSY = new AtomicBoolean();

    private static final PathType[] TYPES = PathType.values();

    private PathRecorder() {}

    public static boolean isRecording() {
        return record && FIXTURES.get() < MAX_FIXTURES;
    }

    public static void setRecord(boolean on) {
        record = on;
        if (on) {
            FIXTURES.set(0);
        }
    }

    public static String status() {
        return "path record=" + record + " fixtures=" + FIXTURES.get() + "/" + MAX_FIXTURES;
    }

    public static void record(PathNavigationRegion region, Mob mob, Set<BlockPos> targets,
                              float maxRange, int accuracy, float searchDepthMultiplier,
                              Object evaluator, int maxVisitedNodes, Path result) {
        // Exact class: AmphibiousNodeEvaluator etc. subclass WalkNodeEvaluator
        // but override behaviors the v1 kernel doesn't model.
        if (evaluator.getClass() != WalkNodeEvaluator.class) {
            return;
        }
        if (!BUSY.compareAndSet(false, true)) {
            return;
        }
        int n = FIXTURES.get();
        if (n >= MAX_FIXTURES || !FIXTURES.compareAndSet(n, n + 1)) {
            BUSY.set(false);
            return;
        }
        try {
            BlockPos mp = mob.blockPosition();
            // followRange caps node distance from start; probes reach a few
            // cells beyond a rim node (neighbor +1, bbox sweep +2, jump +1).
            int r = (int) Math.ceil(maxRange) + 5;
            int x0 = mp.getX() - r, x1 = mp.getX() + r;
            int z0 = mp.getZ() - r, z1 = mp.getZ() + r;
            int y0 = Math.max(mob.level().getMinY(), mp.getY() - r);
            int y1 = Math.min(mob.level().getMaxY(), mp.getY() + r);
            long cells = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
            if (cells > MAX_CELLS) {
                FIXTURES.decrementAndGet();
                return;
            }

            PathfindingContext ctx = new PathfindingContext(region, mob);
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            int nc = (int) cells;
            byte[] grid = new byte[nc];
            byte[] emptyBits = new byte[(nc + 7) >> 3];
            byte[] waterBits = new byte[(nc + 7) >> 3];
            byte[] airBits = new byte[(nc + 7) >> 3];
            byte[] passLandBits = new byte[(nc + 7) >> 3];
            StringBuilder shapes = new StringBuilder(1 << 12);
            int gi = 0;
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    for (int x = x0; x <= x1; x++) {
                        cursor.set(x, y, z);
                        PathType t = WalkNodeEvaluator.getPathTypeStatic(ctx, cursor);
                        grid[gi] = (byte) t.ordinal();
                        BlockState state = region.getBlockState(cursor);
                        if (state.isAir()) {
                            airBits[gi >> 3] |= (byte) (1 << (gi & 7));
                            emptyBits[gi >> 3] |= (byte) (1 << (gi & 7));
                        } else {
                            VoxelShape shape = state.getCollisionShape(region, cursor);
                            if (shape.isEmpty()) {
                                emptyBits[gi >> 3] |= (byte) (1 << (gi & 7));
                            } else if (!Block.isShapeFullBlock(shape)) {
                                List<AABB> boxes = shape.toAabbs();
                                if (shapes.length() > 0) shapes.append(',');
                                shapes.append('[').append(gi);
                                for (AABB b : boxes) {
                                    shapes.append(',').append(b.minX).append(',').append(b.minY).append(',').append(b.minZ)
                                          .append(',').append(b.maxX).append(',').append(b.maxY).append(',').append(b.maxZ);
                                }
                                shapes.append(']');
                            }
                        }
                        if (state.getFluidState().is(FluidTags.ENTITY_FLOATABLE)) {
                            waterBits[gi >> 3] |= (byte) (1 << (gi & 7));
                        }
                        if (state.isPathfindable(PathComputationType.LAND)) {
                            passLandBits[gi >> 3] |= (byte) (1 << (gi & 7));
                        }
                        gi++;
                    }
                }
            }

            WalkNodeEvaluator we = (WalkNodeEvaluator) evaluator;
            Base64.Encoder b64 = Base64.getEncoder();
            StringBuilder sb = new StringBuilder(1 << 16);
            sb.append("{\"v\":3")
              .append(",\"evaluator\":\"").append(evaluator.getClass().getSimpleName()).append('"')
              .append(",\"mob\":\"").append(mob.getType().toShortString()).append('"')
              .append(",\"bbWidth\":").append(mob.getBbWidth())
              .append(",\"bbHeight\":").append(mob.getBbHeight())
              .append(",\"pos\":[").append(mob.getX()).append(',').append(mob.getY()).append(',').append(mob.getZ())
              .append("],\"blockPos\":[").append(mp.getX()).append(',').append(mp.getY()).append(',').append(mp.getZ())
              .append("],\"maxRange\":").append(maxRange)
              .append(",\"accuracy\":").append(accuracy)
              .append(",\"depthMult\":").append(searchDepthMultiplier)
              .append(",\"maxVisitedNodes\":").append(maxVisitedNodes)
              .append(",\"minY\":").append(mob.level().getMinY())
              .append(",\"maxY\":").append(mob.level().getMaxY())
              .append(",\"flags\":{\"canPassDoors\":").append(we.canPassDoors())
              .append(",\"canOpenDoors\":").append(we.canOpenDoors())
              .append(",\"canFloat\":").append(we.canFloat())
              .append(",\"canWalkOverFences\":").append(we.canWalkOverFences())
              .append(",\"onGround\":").append(mob.onGround())
              .append(",\"inWater\":").append(mob.isInFloatableFluid())
              .append(",\"canStandOnFluidAtFeet\":").append(mob.canStandOnFluid(
                      region.getBlockState(cursor.set(mob.getX(), mob.getBlockY(), mob.getZ())).getFluidState()))
              .append('}')
              .append(",\"stepHeight\":").append(mob.maxUpStep())
              .append(",\"maxFallDistance\":").append(mob.getMaxFallDistance());
            sb.append(",\"malus\":[");
            for (int i = 0; i < TYPES.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(mob.getPathfindingMalus(TYPES[i]));
            }
            sb.append("],\"typeNames\":[");
            for (int i = 0; i < TYPES.length; i++) {
                if (i > 0) sb.append(',');
                sb.append('"').append(TYPES[i].name()).append('"');
            }
            sb.append("],\"targets\":[");
            boolean first = true;
            for (BlockPos t : targets) {
                if (!first) sb.append(',');
                first = false;
                sb.append('[').append(t.getX()).append(',').append(t.getY()).append(',').append(t.getZ()).append(']');
            }
            sb.append("],\"bbox\":[").append(x0).append(',').append(y0).append(',').append(z0)
              .append(',').append(x1).append(',').append(y1).append(',').append(z1)
              .append("],\"typeGrid\":\"").append(b64.encodeToString(grid)).append('"')
              .append(",\"emptyBits\":\"").append(b64.encodeToString(emptyBits)).append('"')
              .append(",\"waterBits\":\"").append(b64.encodeToString(waterBits)).append('"')
              .append(",\"airBits\":\"").append(b64.encodeToString(airBits)).append('"')
              .append(",\"passLandBits\":\"").append(b64.encodeToString(passLandBits)).append('"')
              .append(",\"shapes\":[").append(shapes).append(']');
            if (result == null) {
                sb.append(",\"result\":null");
            } else {
                sb.append(",\"result\":{\"canReach\":").append(result.canReach())
                  .append(",\"target\":[").append(result.getTarget().getX()).append(',')
                  .append(result.getTarget().getY()).append(',').append(result.getTarget().getZ())
                  .append("],\"nodes\":[");
                for (int i = 0; i < result.getNodeCount(); i++) {
                    if (i > 0) sb.append(',');
                    BlockPos p = result.getNodePos(i);
                    sb.append('[').append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ()).append(']');
                }
                sb.append("]}");
            }
            sb.append('}');

            Files.createDirectories(OUT_DIR);
            Files.write(OUT_DIR.resolve("path-" + (n + 1) + ".json"),
                    sb.toString().getBytes(StandardCharsets.UTF_8));
            LOGGER.info("[path-record] fixture {} ({} cells, {} nodes)",
                    n + 1, cells, result == null ? -1 : result.getNodeCount());
        } catch (Throwable t) {
            LOGGER.warn("path fixture capture failed", t);
            FIXTURES.decrementAndGet();
        } finally {
            BUSY.set(false);
        }
    }
}
