package com.github.tatercertified.nalim.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Entity storage of a level, for collecting the cramming batch in vanilla section order. */
@Mixin(ServerLevel.class)
public interface ServerLevelEntityManagerAccessor {
    @Accessor("entityManager")
    PersistentEntitySectionManager<Entity> rustmi$entityManager();
}
