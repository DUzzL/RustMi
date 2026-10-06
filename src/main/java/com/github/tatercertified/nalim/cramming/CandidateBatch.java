package com.github.tatercertified.nalim.cramming;

import net.minecraft.world.entity.Entity;

/**
 * Read view of one tick's cramming candidate batch, consumed by
 * {@code LivingEntityCrammingMixin}. Two implementations:
 * <ul>
 *   <li>{@link RustmiCramming} — the global pre-tick batch (flat-pool /
 *       non-region mode);</li>
 *   <li>{@code RegionalCramming.RegionBatch} — per-region batches collected on
 *       the region worker right before the region's entities tick (Fabrolia
 *       region scheduler mode).</li>
 * </ul>
 */
public interface CandidateBatch {

    /** Slot of an origin row, or -1 when the entity must use vanilla. */
    int originSlot(Entity entity);

    /**
     * Candidate count for an origin row; {@link RustmiCramming#OVERFLOW}
     * means denser than the kernel's record cap — re-query via
     * {@link #overflowCandidates} (full vanilla query only if that declines).
     */
    int candidateCount(int slot);

    /** i-th candidate slot of an origin row. */
    int candidateSlot(int slot, int i);

    /**
     * Unbounded re-query for an OVERFLOW row against the same retained
     * snapshot the kernel ran on: fills a per-thread scratch with the slots
     * of all flagged candidates (a conservative superset for living rows —
     * identical contract to the capped per-row lists). Returns the
     * count, or -1 (no retention / scratch exceeded) → caller must run the
     * full vanilla query.
     */
    int overflowCandidates(int slot);

    /** i-th slot written by the last {@link #overflowCandidates} call on this thread. */
    int scratchCandidateSlot(int i);

    /** Entity registered at the given slot this batch (null if none). */
    Entity entityAt(int slot);

    /**
     * True when slots were assigned in vanilla entity-section order (section key ascending,
     * then insertion order), so ascending candidate slots are vanilla's iteration order for
     * every entity still in the section it was collected from.
     */
    default boolean slotOrderIsVanilla() {
        return false;
    }

    /** The entity's section insertion stamp when its slot was collected. */
    default long collectedSeq(int slot) {
        return Long.MIN_VALUE;
    }
}
