package com.github.tatercertified.nalim.mixin;

import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraft.world.level.entity.EntitySection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EntitySection.class)
public interface EntitySectionAccessor<T extends net.minecraft.world.level.entity.EntityAccess> {
    @Accessor("storage")
    ClassInstanceMultiMap<T> rustmi$storage();
}
