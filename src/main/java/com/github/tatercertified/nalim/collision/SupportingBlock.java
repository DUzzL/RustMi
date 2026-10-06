package com.github.tatercertified.nalim.collision;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Optional;

/**
 * {@code CollisionGetter.findSupportingBlock} over the collision mirror. Visits exactly the
 * cells vanilla's {@code BlockCollisions} cursor visits and applies the same per-cell rule;
 * the mirror only answers the cells whose answer is fixed by their class:
 * <ul>
 *   <li>EMPTY — empty collision shape, never yields;</li>
 *   <li>FULL — the {@code Shapes.block()} singleton (classification requires identity), so
 *       vanilla's {@code box.intersects(cube)} branch; on a face/edge cell of the padded
 *       cursor a unit cube can never strictly intersect the box, so only inside cells test;</li>
 *   <li>BOXES/PARTIAL — the live state through vanilla's exact per-cell code.</li>
 * </ul>
 * The winner rule (min distance, ties to the larger {@code BlockPos}) is order-independent,
 * so the visit order does not matter.
 */
public final class SupportingBlock {

    private SupportingBlock() {}

    /** @return the vanilla result, or null when the caller must run vanilla itself. */
    @SuppressWarnings("OptionalAssignedToNull")
    public static Optional<BlockPos> find(Level level, Entity source, AABB box) {
        if (level.isClientSide()
                || (source instanceof AbstractMinecart && AbstractMinecart.useExperimentalMovement(level))) {
            return null;   // MinecartCollisionContext ignores cells the mirror would report
        }
        int x0 = net.minecraft.util.Mth.floor(box.minX - 1.0E-7) - 1;
        int x1 = net.minecraft.util.Mth.floor(box.maxX + 1.0E-7) + 1;
        int y0 = net.minecraft.util.Mth.floor(box.minY - 1.0E-7) - 1;
        int y1 = net.minecraft.util.Mth.floor(box.maxY + 1.0E-7) + 1;
        int z0 = net.minecraft.util.Mth.floor(box.minZ - 1.0E-7) - 1;
        int z1 = net.minecraft.util.Mth.floor(box.maxZ + 1.0E-7) + 1;
        if ((long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1) > 4096) {
            return null;
        }
        net.minecraft.world.phys.Vec3 at = source.position();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        CollisionContext context = null;
        VoxelShape entityShape = null;
        BlockGetter chunk = null;
        long chunkKey = Long.MIN_VALUE;
        for (int z = z0; z <= z1; z++) {
            boolean zEdge = z == z0 || z == z1;
            for (int y = y0; y <= y1; y++) {
                boolean yEdge = y == y0 || y == y1;
                for (int x = x0; x <= x1; x++) {
                    int type = (x == x0 || x == x1 ? 1 : 0) + (yEdge ? 1 : 0) + (zEdge ? 1 : 0);
                    if (type == 3) {
                        continue;
                    }
                    long key = ChunkPos.pack(x >> 4, z >> 4);
                    if (key != chunkKey) {
                        chunk = level.getChunkForCollisions(x >> 4, z >> 4);
                        chunkKey = key;
                    }
                    if (chunk == null) {
                        continue;
                    }
                    pos.set(x, y, z);
                    boolean hit;
                    if (chunk instanceof LevelChunk lc) {
                        long[] bits = ((CubeMirror) lc).oxi$bits(lc.getSectionIndex(y));
                        if (bits == null) {
                            continue;   // outside the build height: vanilla reads AIR
                        }
                        int cell = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
                        int word = cell >> 6;
                        long bit = 1L << (cell & 63);
                        if ((bits[CubeMirror.FULL_OFF + word] & bit) != 0) {
                            hit = type == 0 && box.intersects(x, y, z, x + 1.0, y + 1.0, z + 1.0);
                        } else if (((bits[CubeMirror.PARTIAL_OFF + word] | bits[CubeMirror.BOXES_OFF + word]) & bit) == 0) {
                            continue;   // EMPTY
                        } else {
                            if (context == null) {
                                context = CollisionContext.of(source);
                                entityShape = Shapes.create(box);
                            }
                            hit = vanillaCell(level, chunk, pos, type, context, box, entityShape);
                        }
                    } else {
                        if (context == null) {
                            context = CollisionContext.of(source);
                            entityShape = Shapes.create(box);
                        }
                        hit = vanillaCell(level, chunk, pos, type, context, box, entityShape);
                    }
                    if (hit) {
                        double distance = pos.distToCenterSqr(at);
                        if (distance < bestDistance
                                || distance == bestDistance && (best == null || best.compareTo(pos) < 0)) {
                            best = pos.immutable();
                            bestDistance = distance;
                        }
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** {@code BlockCollisions.computeNext}'s per-cell test, verbatim. */
    private static boolean vanillaCell(Level level, BlockGetter chunk, BlockPos.MutableBlockPos pos, int type,
                                       CollisionContext context, AABB box, VoxelShape entityShape) {
        BlockState state = chunk.getBlockState(pos);
        if ((type == 1 && !state.hasLargeCollisionShape()) || (type == 2 && !state.is(Blocks.MOVING_PISTON))) {
            return false;
        }
        VoxelShape blockShape = context.getCollisionShape(state, level, pos);
        if (blockShape == Shapes.block()) {
            return box.intersects(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1.0, pos.getY() + 1.0, pos.getZ() + 1.0);
        }
        VoxelShape shape = blockShape.move(pos);
        return !shape.isEmpty() && Shapes.joinIsNotEmpty(shape, entityShape, BooleanOp.AND);
    }
}
