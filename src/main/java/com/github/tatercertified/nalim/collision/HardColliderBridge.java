package com.github.tatercertified.nalim.collision;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/**
 * Answers "can {@code getEntityCollisions} return anything for this box?" without the
 * EntitySectionStorage walk, from Rustmi's live {@link HardColliders} index. Fabrolia's own
 * {@code HardColliderIndex} is deliberately not consulted: it answers from a start-of-tick
 * cell snapshot with a 4-block drift margin, so a shulker teleporting up to 8 blocks or a
 * same-dimension teleport within the tick would be reported absent. Movers with a custom
 * {@code canCollideWith} predicate always run the exact query because a candidate-only
 * index cannot prove emptiness for them.
 */
public final class HardColliderBridge {

    /**
     * EntityGetter.getEntityCollisions filters candidates through the mover's
     * virtual canCollideWith call. The candidate index is a complete emptiness
     * proof only for Entity's default implementation.
     */
    private static final ClassValue<Boolean> MOVER_OVERRIDES = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("canCollideWith", Entity.class).getDeclaringClass()
                        != Entity.class;
            } catch (ReflectiveOperationException | SecurityException unavailable) {
                return Boolean.TRUE;
            }
        }
    };

    private HardColliderBridge() {}

    static boolean hasCustomCollisionPredicate(Class<?> type) {
        return MOVER_OVERRIDES.get(type);
    }

    /** True when entity colliders might intersect — caller must then run the
     *  precise vanilla query. False is a hard guarantee of emptiness. */
    public static boolean mayHaveEntityColliders(Entity self, AABB box) {
        try {
            return hasCustomCollisionPredicate(self.getClass())
                    || HardColliders.anyNear(self.level(), self, box);
        } catch (Throwable t) {
            CollisionEngine.fault("HardColliders", t);
            return true;   // "maybe": the caller runs vanilla's exact query
        }
    }
}
