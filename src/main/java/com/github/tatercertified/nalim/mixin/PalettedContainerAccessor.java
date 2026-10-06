package com.github.tatercertified.nalim.mixin;

import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read-only access to {@link PalettedContainer}'s {@code strategy} field — the
 * source of its global {@link net.minecraft.core.IdMap IdMap}.
 *
 * <p><b>The {@code data} field is NOT exposed here.</b> It is typed
 * {@code PalettedContainer.Data<T>}, a <b>private nested record</b>: it cannot be
 * named as an {@code @Accessor} return type from this compilation unit, and Mixin
 * rejects a widened {@code Object} return at accessor-locate time
 * ({@code InvalidAccessorException: No candidates were found matching
 * data:Ljava/lang/Object;}). {@code data} is read through a cached
 * {@code sun.misc.Unsafe} field offset instead
 * ({@link com.github.tatercertified.nalim.sections.NativeSections#dataOf}, used by
 * {@code PalettedContainerMixin}); the returned {@code Data}
 * instance is cast to {@link PalettedContainerDataAccessor} to read
 * {@code storage}/{@code palette}.
 *
 * <p>Coexists with {@code PalettedContainerMixin}; both target the same class,
 * neither conflicts.
 */
@Mixin(PalettedContainer.class)
public interface PalettedContainerAccessor {

    @Accessor("strategy")
    Strategy rustmi$strategy();
}
