package com.github.tatercertified.nalim.mixin;

import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** C3: read access to a VoxelShape's merged voxel grid — the per-axis point
 *  positions and the occupancy set — extracted ONCE per multi-box BlockState
 *  and cached as the kernel-side grid payload. */
@Mixin(VoxelShape.class)
public interface VoxelShapeAccessor {

    @Accessor("shape")
    DiscreteVoxelShape oxi$voxels();

    @Invoker("getCoords")
    DoubleList oxi$coords(Direction.Axis axis);
}
