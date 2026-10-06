package com.github.tatercertified.nalim.mixin;

import com.github.tatercertified.nalim.collision.HardColliders;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.concurrent.ConcurrentHashMap;

/** Per-level storage of {@link HardColliders}. */
@Mixin(Level.class)
public abstract class LevelHardColliderMixin implements HardColliders.Holder {

    @Unique
    private final ConcurrentHashMap<Long, Entity[]> rustmi$hardBuckets = new ConcurrentHashMap<>();

    @Override
    public ConcurrentHashMap<Long, Entity[]> rustmi$hardBuckets() {
        return rustmi$hardBuckets;
    }
}
