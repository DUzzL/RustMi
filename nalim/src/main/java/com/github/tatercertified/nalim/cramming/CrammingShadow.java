package com.github.tatercertified.nalim.cramming;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Vanilla-parity probe for the cramming fast path: for every pushEntities the mod
 * serves, the list it pushes against is compared with what vanilla (or whatever owns
 * {@code Level.getPushableEntities}, e.g. Lithium) would have produced at that moment.
 * Observation only — the served list is still the one that gets pushed. A different
 * element set changes which entities move; a different order changes the float
 * rounding of the accumulated {@code deltaMovement}.
 */
public final class CrammingShadow {

    private static volatile boolean enabled;
    private static final AtomicLong CALLS = new AtomicLong();
    private static final AtomicLong SET_MISMATCH = new AtomicLong();
    private static final AtomicLong ORDER_MISMATCH = new AtomicLong();
    private static final AtomicLong SENSOR_CALLS = new AtomicLong();
    private static final AtomicLong SENSOR_SET_MISMATCH = new AtomicLong();
    private static final AtomicLong SENSOR_ORDER_MISMATCH = new AtomicLong();
    private static final ConcurrentLinkedQueue<String> EXAMPLES = new ConcurrentLinkedQueue<>();

    private CrammingShadow() {}

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean on) {
        if (on) {
            CALLS.set(0);
            SET_MISMATCH.set(0);
            ORDER_MISMATCH.set(0);
            SENSOR_CALLS.set(0);
            SENSOR_SET_MISMATCH.set(0);
            SENSOR_ORDER_MISMATCH.set(0);
            EXAMPLES.clear();
        }
        enabled = on;
    }

    public static void compare(LivingEntity self, List<Entity> served) {
        // Vanilla's getPushableEntities body, called directly: Lithium redirects that
        // method and, with Fabrolia, serves it from a concurrent class-group set that
        // iterates in hash order — not vanilla's section insertion order.
        List<Entity> vanilla = self.level().getEntities(self, self.getBoundingBox(),
                net.minecraft.world.entity.EntitySelector.pushableBy(self));
        CALLS.incrementAndGet();
        record(self, vanilla, served, SET_MISMATCH, ORDER_MISMATCH, "");
    }

    /** Nearest-living sensor provider (Fabrolia region mode) against the query it replaces. */
    public static void compareSensor(LivingEntity body, net.minecraft.world.phys.AABB box,
                                     List<? extends Entity> served) {
        List<? extends Entity> vanilla = body.level().getEntitiesOfClass(
                LivingEntity.class, box, e -> e != body && e.isAlive());
        SENSOR_CALLS.incrementAndGet();
        record(body, vanilla, served, SENSOR_SET_MISMATCH, SENSOR_ORDER_MISMATCH, "sensor ");
    }

    private static void record(Entity self, List<? extends Entity> vanilla, List<? extends Entity> served,
                               AtomicLong setMismatch, AtomicLong orderMismatch, String kind) {
        if (sameOrder(vanilla, served)) {
            return;
        }
        boolean sameSet = vanilla.size() == served.size()
                && new HashSet<>(vanilla).equals(new HashSet<>(served));
        (sameSet ? orderMismatch : setMismatch).incrementAndGet();
        if (EXAMPLES.size() < 4) {
            EXAMPLES.add(kind + (sameSet ? "order " : "set ") + self.getType().toShortString()
                    + "@" + self.blockPosition().toShortString()
                    + " vanilla=" + ids(vanilla) + " served=" + ids(served));
        }
    }

    private static boolean sameOrder(List<? extends Entity> a, List<? extends Entity> b) {
        int n = a.size();
        if (n != b.size()) {
            return false;
        }
        for (int i = 0; i < n; i++) {
            if (a.get(i) != b.get(i)) {
                return false;
            }
        }
        return true;
    }

    private static String ids(List<? extends Entity> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size() && i < 8; i++) {
            if (i > 0) sb.append(',');
            sb.append(list.get(i).getId());
        }
        if (list.size() > 8) sb.append(",…").append(list.size());
        return sb.append(']').toString();
    }

    public static String status() {
        StringBuilder sb = new StringBuilder("cramming shadow=").append(enabled)
                .append(" | calls=").append(CALLS.get())
                .append(" setMismatch=").append(SET_MISMATCH.get())
                .append(" orderMismatch=").append(ORDER_MISMATCH.get())
                .append(" | sensorCalls=").append(SENSOR_CALLS.get())
                .append(" sensorSet=").append(SENSOR_SET_MISMATCH.get())
                .append(" sensorOrder=").append(SENSOR_ORDER_MISMATCH.get());
        for (String e : EXAMPLES) {
            sb.append(" | ").append(e);
        }
        return sb.toString();
    }
}
