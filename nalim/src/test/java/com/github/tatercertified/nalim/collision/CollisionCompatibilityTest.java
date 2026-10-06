package com.github.tatercertified.nalim.collision;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.phys.Vec3;

/** Regression checks for transformed-runtime collision fallback boundaries. */
public final class CollisionCompatibilityTest {
    private CollisionCompatibilityTest() {}

    public static void main(String[] args) {
        subEpsilonComponentsRequireVanilla();
        customMoverPredicatesRequireExactEntityQuery();
    }

    private static void subEpsilonComponentsRequireVanilla() {
        check(!CollisionEngine.requiresVanillaPrecision(new Vec3(0.0, 0.0, 0.0)),
                "positive zero components may use the native path");
        check(CollisionEngine.requiresVanillaPrecision(new Vec3(0.0, -0.0, 0.0)),
                "negative zero keeps or loses its sign depending on the vanilla shape set");
        check(CollisionEngine.requiresVanillaPrecision(new Vec3(Double.MIN_VALUE, 0.0, 0.0)),
                "positive subnormal x");
        check(CollisionEngine.requiresVanillaPrecision(new Vec3(0.0, -Double.MIN_VALUE, 0.0)),
                "negative subnormal y");
        check(CollisionEngine.requiresVanillaPrecision(
                        new Vec3(0.0, 0.0, Math.nextDown(1.0E-7))),
                "one ulp below the epsilon boundary");
        check(CollisionEngine.requiresVanillaPrecision(new Vec3(1.0E-7, -1.0E-7, 1.0)),
                "the exact epsilon boundary can change sign under candidate reordering");
        check(CollisionEngine.requiresVanillaPrecision(new Vec3(Double.NaN, 0.0, 0.0)),
                "NaN would floor the gather window to the origin");
        check(CollisionEngine.requiresVanillaPrecision(new Vec3(0.0, Double.NEGATIVE_INFINITY, 0.0)),
                "infinite movement has no finite gather window");
    }

    private static void customMoverPredicatesRequireExactEntityQuery() {
        check(!HardColliderBridge.hasCustomCollisionPredicate(Entity.class),
                "Entity's default predicate may use the candidate index");
        check(HardColliderBridge.hasCustomCollisionPredicate(AbstractMinecart.class),
                "minecart predicate accepts pushable candidates outside the candidate index");
        check(HardColliderBridge.hasCustomCollisionPredicate(String.class),
                "unclassifiable mover types fail closed to the exact query");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
