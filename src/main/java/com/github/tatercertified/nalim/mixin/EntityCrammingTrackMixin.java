package com.github.tatercertified.nalim.mixin;

import com.github.tatercertified.nalim.cramming.CrammingEscapes;
import com.github.tatercertified.nalim.cramming.CrammingTracked;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Escape detection for the cramming/sensor snapshot ({@link CrammingEscapes}).
 * {@code setBoundingBox} is the only writer of {@code Entity.bb}, so every move, resize
 * and teleport passes here. No field initializers: the constructor's own setPos runs
 * this hook before initializers would.
 */
@Mixin(Entity.class)
public abstract class EntityCrammingTrackMixin implements CrammingTracked {

    @Shadow private EntityInLevelCallback levelCallback;

    @Unique private int rustmi$tick;
    @Unique private boolean rustmi$escaped;
    @Unique private double rustmi$minX, rustmi$minY, rustmi$minZ, rustmi$maxX, rustmi$maxY, rustmi$maxZ;
    /** Volatile: collectors read stamp-then-position against the writer's position-then-
     *  stamp order, so a torn pair can only be (old stamp, new key) — caught at query. */
    @Unique private volatile long rustmi$sectionSeq;
    @Unique private boolean rustmi$hardIndexed;
    @Unique private long rustmi$hardKey;

    @Inject(method = "setBoundingBox", at = @At("HEAD"))
    private void rustmi$trackEscape(AABB next, CallbackInfo ci) {
        if (!CrammingEscapes.tracking()) {
            return;
        }
        int t = CrammingEscapes.tick();
        if (rustmi$tick == t && (rustmi$escaped || rustmi$within(next))) {
            return;
        }
        // Not snapshotted this tick, or drifted out of the snapshot tolerance.
        if (CrammingEscapes.offer((Entity) (Object) this)) {
            rustmi$tick = t;
            rustmi$escaped = true;
        }
    }

    @Unique
    private boolean rustmi$within(AABB next) {
        return next.minX >= rustmi$minX && next.minY >= rustmi$minY && next.minZ >= rustmi$minZ
                && next.maxX <= rustmi$maxX && next.maxY <= rustmi$maxY && next.maxZ <= rustmi$maxZ;
    }

    @Override
    public void rustmi$snapshotted(int tick, AABB box) {
        if (rustmi$tick == tick) {
            return;   // first snapshot of the tick wins; an escape stays an escape
        }
        double tol = CrammingEscapes.tolerance();
        rustmi$tick = tick;
        rustmi$escaped = false;
        rustmi$minX = box.minX - tol;
        rustmi$minY = box.minY - tol;
        rustmi$minZ = box.minZ - tol;
        rustmi$maxX = box.maxX + tol;
        rustmi$maxY = box.maxY + tol;
        rustmi$maxZ = box.maxZ + tol;
    }

    @Override
    public boolean rustmi$escaped(int tick) {
        return rustmi$tick != tick || rustmi$escaped;
    }

    @Override
    public boolean rustmi$inLevel() {
        return levelCallback != EntityInLevelCallback.NULL;
    }

    @Override
    public long rustmi$sectionSeq() {
        return rustmi$sectionSeq;
    }

    @Override
    public void rustmi$setSectionSeq(long seq) {
        rustmi$sectionSeq = seq;
    }

    @Override
    public boolean rustmi$hardIndexed() {
        return rustmi$hardIndexed;
    }

    @Override
    public long rustmi$hardKey() {
        return rustmi$hardKey;
    }

    @Override
    public void rustmi$setHardBucket(boolean indexed, long key) {
        rustmi$hardIndexed = indexed;
        rustmi$hardKey = key;
    }
}
