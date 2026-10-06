package com.github.tatercertified.nalim.mixin;

import net.minecraft.util.BitStorage;
import net.minecraft.world.level.chunk.Palette;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read-only access to the {@code storage} / {@code palette} fields of the private
 * record {@code PalettedContainer.Data<T>}. That record is a <b>private nested
 * type</b>, so it cannot be named (or {@code .class}-referenced) from another
 * compilation unit; Mixin's string {@code targets} form sidesteps that by applying
 * this accessor directly to the record at runtime without a compile-time type.
 *
 * <p><b>Mapping assumption.</b> String {@code targets} are <b>not</b> remapped by
 * loom — they must be the runtime (mapped) name. This stack runs mojmap at runtime
 * (modern-yarn), so {@code net.minecraft.world.level.chunk.PalettedContainer$Data}
 * is the live name in both dev and prod. If the mod ever ships on an intermediary
 * runtime, this single target string is the one place to update (or swap the
 * accessor for a cached-reflective field read).
 *
 * <p>The record's components {@code storage} / {@code palette} are backed by
 * private final fields of the same name, which {@code @Accessor} reads directly —
 * no record accessor method dispatch, no allocation. {@code palette} is returned
 * raw to avoid weaving the container's type parameter through the duck type.
 */
@Mixin(targets = "net.minecraft.world.level.chunk.PalettedContainer$Data")
public interface PalettedContainerDataAccessor {

    @Accessor("storage")
    BitStorage rustmi$storage();

    @Accessor("palette")
    Palette rustmi$palette();
}
