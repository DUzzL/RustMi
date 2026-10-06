package com.github.tatercertified.nalim.mixin;

import com.github.tatercertified.nalim.cramming.CandidateBatch;
import com.github.tatercertified.nalim.cramming.CrammingHooks;
import com.github.tatercertified.nalim.cramming.CrammingEscapes;
import com.github.tatercertified.nalim.cramming.CrammingTracked;
import com.github.tatercertified.nalim.cramming.CrammingShadow;
import com.github.tatercertified.nalim.cramming.RustmiCramming;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Replaces vanilla {@link LivingEntity#pushEntities()}'s expensive spatial
 * query with the candidate list precomputed by {@link RustmiCramming}
 * (padded snapshot superset), then runs the EXACT vanilla tickCramming body
 * against LIVE state:
 * <ul>
 *   <li>live {@code AABB.intersects} — membership decided at the entity's own
 *       tick, restoring vanilla's sub-tick feedback (no snapshot staleness in
 *       the physics; dense piles dissolve exactly like vanilla);</li>
 *   <li>the real {@code EntitySelector.pushableBy(this)} predicate — teams,
 *       spectators, isPushable overrides, all vanilla;</li>
 *   <li>vanilla damage gates verbatim — list size, gamerule,
 *       {@code random.nextInt(4)} on the entity's own RNG, non-passenger
 *       recount, {@code hurtServer(..., cramming(), 6.0F)};</li>
 *   <li>the actual vanilla {@code doPush} — virtual dispatch keeps the
 *       sleeping override, same-vehicle/noPhysics gates and exact push math
 *       with both-sides application.</li>
 * </ul>
 * The only difference from vanilla is WHERE the neighbour candidates come
 * from: a spatial-hash kernel instead of an entity-section walk. The kernel
 * guarantees a superset (0.5-block padding covers one tick of drift); the
 * live re-checks shrink it to exactly vanilla's list.
 * <p>
 * Fallbacks (no cancellation, vanilla runs): entity not batched this tick,
 * client side, or capacity exceeded. OVERFLOW rows (&gt;127 snapshot
 * candidates — dense piles) are served by an unbounded re-query of the same
 * retained snapshot instead of the vanilla section walk; vanilla only runs
 * if even that declines (&gt;4096 candidates or no retention). Never
 * truncate — that would silently weaken cramming.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityCrammingMixin {

    @Shadow
    protected abstract void doPush(Entity entity);

    @Inject(method = "pushEntities", at = @At("HEAD"), cancellable = true)
    private void rustmi$candidateCramming(CallbackInfo ci) {
        if (!CrammingHooks.isCrammingEnabled()) {
            return;   // master switch off — full vanilla pushEntities
        }
        LivingEntity self = (LivingEntity) (Object) this;
        if (!self.isAlive() || self.isPassenger() || self.isSpectator()) {
            // Vanilla handles these states (passengers keep exact semantics
            // for the boat-farm cramming exemption).
            return;
        }

        // Region mode: the batch collected by this entity's region worker;
        // otherwise the global pre-tick batch. Vanilla fallback when absent.
        CandidateBatch c = CrammingHooks.batchForCurrentThread();
        int slot = c.originSlot(self);
        if (slot < 0 || !CrammingEscapes.originReliable(self)) {
            return;                      // not batched, or snapshot untrustworthy — vanilla
        }
        int count = c.candidateCount(slot);
        boolean fromScratch = false;
        if (count == RustmiCramming.OVERFLOW) {
            // Dense pile (>127 snapshot candidates): re-query the SAME
            // retained snapshot without the record cap instead of paying the
            // vanilla section walk per entity (O(N²) across the pile). The
            // superset contract and the live re-filter below are unchanged —
            // semantics stay vanilla; only the candidate SOURCE differs.
            count = c.overflowCandidates(slot);
            if (count < 0) {
                return;                  // no retention / >4096 — true vanilla fallback
            }
            fromScratch = true;
        }

        // ── live re-filter: padded snapshot superset → vanilla list ──
        AABB box = self.getBoundingBox();
        Predicate<Entity> pushableBy = EntitySelector.pushableBy(self);
        List<Entity> list = null;
        // Candidate slots arrive ascending; in a vanilla-ordered batch that IS vanilla's
        // iteration order for every candidate still in its collection-time section (an
        // unchanged insertion stamp proves it never re-entered a section since).
        boolean inOrder = c.slotOrderIsVanilla();
        int prevSlot = -1;
        CrammingEscapes.SectionWindow window = CrammingEscapes.SectionWindow.of(box);
        if (count > 0) {
            list = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int candidateSlot = fromScratch ? c.scratchCandidateSlot(i) : c.candidateSlot(slot, i);
                Entity candidate = c.entityAt(candidateSlot);
                if (candidate == null || candidate == self) continue;
                if (candidate.isRemoved()) continue;
                if (candidate.level() != self.level()) continue;       // teleported mid-tick
                if (!((CrammingTracked) candidate).rustmi$inLevel()) continue;   // between sections
                if (!box.intersects(candidate.getBoundingBox())) continue;
                if (!window.contains(candidate)) continue;
                if (!pushableBy.test(candidate)) continue;
                list.add(candidate);
                // Native output contract: ascending slots. Verify instead of trusting it.
                if (inOrder && (candidateSlot <= prevSlot
                        || ((CrammingTracked) candidate).rustmi$sectionSeq() != c.collectedSeq(candidateSlot))) {
                    inOrder = false;
                }
                prevSlot = candidateSlot;
            }
        }
        // Entities that left their snapshot envelope or joined after it.
        int kernelCount = list == null ? 0 : list.size();
        list = CrammingEscapes.addEscapees(list, Entity.class, self, box, pushableBy);
        if (list == null) {
            list = List.of();
        } else if (!inOrder || list.size() != kernelCount) {
            CrammingEscapes.sortVanilla(list);   // vanilla section order → same doPush order
        }
        if (CrammingShadow.isEnabled()) {
            CrammingShadow.compare(self, list);
        }

        // ── exact vanilla tickCramming body over the live list ───────
        if (!list.isEmpty()) {
            if (self.level() instanceof ServerLevel serverLevel) {
                int maxCramming = serverLevel.getGameRules().get(GameRules.MAX_ENTITY_CRAMMING);
                if (maxCramming > 0 && list.size() > maxCramming - 1 && self.getRandom().nextInt(4) == 0) {
                    int nonPassengers = 0;
                    for (Entity entity : list) {
                        if (!entity.isPassenger()) {
                            nonPassengers++;
                        }
                    }
                    if (nonPassengers > maxCramming - 1) {
                        self.hurtServer(serverLevel, self.damageSources().cramming(), 6.0F);
                    }
                }
            }
            for (Entity entity : list) {
                this.doPush(entity);
            }
        }

        ci.cancel();
    }
}
