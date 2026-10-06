package com.github.tatercertified.nalim.entity;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@code LivingEntity.onClimbable} returns false, with no side effect, for every in-block
 * state that is neither in {@code #climbable} nor a trapdoor — whatever the entity's
 * fall-flying state (the glide-through branch only ever returns false). That predicate is a
 * pure function of the BlockState and the bound tags, so it is cached on the state and
 * invalidated by a generation counter bumped whenever any holder's tags are rebound.
 */
public final class ClimbCache {

    /** Mixed into {@code BlockStateBase}: {@code generation << 1 | neverClimbable}, 0 = unset. */
    public interface Holder {
        long rustmi$climbCache();

        void rustmi$setClimbCache(long packed);
    }

    /** Atomic: a lost increment could step the counter back onto an already-used value. */
    private static final java.util.concurrent.atomic.AtomicLong GENERATION = new java.util.concurrent.atomic.AtomicLong(1);

    private ClimbCache() {}

    /** Any tag (re)binding may change {@code #climbable}. */
    public static void onTagsBound() {
        GENERATION.incrementAndGet();
    }

    public static boolean neverClimbable(BlockState state) {
        long gen = GENERATION.get();
        Holder h = (Holder) state;
        long packed = h.rustmi$climbCache();
        if ((packed >>> 1) == gen) {
            return (packed & 1) != 0;
        }
        boolean never = !state.is(BlockTags.CLIMBABLE) && !(state.getBlock() instanceof TrapDoorBlock);
        if (GENERATION.get() == gen) {   // a rebind during the computation leaves the slot unset
            h.rustmi$setClimbCache(gen << 1 | (never ? 1L : 0L));
        }
        return never;
    }
}
