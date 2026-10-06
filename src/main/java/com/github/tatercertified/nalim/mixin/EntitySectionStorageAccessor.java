package com.github.tatercertified.nalim.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSortedSet;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EntitySectionStorage.class)
public interface EntitySectionStorageAccessor<T extends net.minecraft.world.level.entity.EntityAccess> {
    @Accessor("sectionIds")
    LongSortedSet rustmi$sectionIds();

    @Accessor("sections")
    Long2ObjectMap<EntitySection<T>> rustmi$sections();
}
