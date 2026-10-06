package com.github.tatercertified.nalim.cramming;

import net.minecraft.world.phys.AABB;

/** Per-entity cramming bookkeeping, mixed into {@code Entity}. */
public interface CrammingTracked {

    /** Records the box a snapshot row was built from (first snapshot of the tick wins). */
    void rustmi$snapshotted(int tick, AABB box);

    /** True when the entity drifted out of its snapshot tolerance (or was never
     *  snapshotted) during {@code tick}. */
    boolean rustmi$escaped(int tick);

    /** True once the entity manager owns the entity (vanilla queries can see it). */
    boolean rustmi$inLevel();

    /** Order of the last insertion into an entity section ({@code ClassInstanceMultiMap}
     *  append order, which is vanilla's iteration order inside a section). */
    long rustmi$sectionSeq();

    void rustmi$setSectionSeq(long seq);

    /** Whether the entity currently sits in a {@code HardColliders} bucket. */
    boolean rustmi$hardIndexed();

    /** Section key of that bucket (valid while {@link #rustmi$hardIndexed()}). */
    long rustmi$hardKey();

    void rustmi$setHardBucket(boolean indexed, long key);
}
