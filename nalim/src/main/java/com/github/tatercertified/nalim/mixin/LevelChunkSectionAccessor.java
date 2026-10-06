package com.github.tatercertified.nalim.mixin;

import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read-only access to {@link LevelChunkSection}'s two private count shorts.
 * {@code nonEmptyBlockCount} and {@code fluidCount} are exactly the two values
 * vanilla writes ahead of the palette data ({@code LevelChunkSection.write}:
 * {@code writeShort(nonEmptyBlockCount); writeShort(fluidCount); ...}). Vanilla
 * exposes no public getter for them, so an accessor is the clean path. The
 * remaining counts ({@code tickingBlockCount}/{@code tickingFluidCount}) are not
 * on the wire and are deliberately not exposed.
 */
@Mixin(LevelChunkSection.class)
public interface LevelChunkSectionAccessor {

    @Accessor("nonEmptyBlockCount")
    short rustmi$nonEmptyBlockCount();

    @Accessor("fluidCount")
    short rustmi$fluidCount();
}
