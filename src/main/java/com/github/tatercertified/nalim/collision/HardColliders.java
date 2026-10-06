package com.github.tatercertified.nalim.collision;

import com.github.tatercertified.nalim.cramming.CrammingTracked;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rustmi's hard-collider index. {@code EntityGetter.getEntityCollisions} can only return
 * shapes of entities whose {@code canBeCollidedWith} can be true (boats, shulkers, happy
 * ghasts and whatever mods add); every other entity inherits Entity's constant
 * {@code false}. Each level buckets such entities by the entity section that holds them,
 * maintained at the EntitySection add/remove choke point, so "no entity collider can
 * intersect this box" is answered from exactly the sections vanilla's own walk would visit,
 * using live bounding boxes — no snapshot, no drift margin, no global size limit.
 *
 * <p>Buckets are immutable arrays replaced atomically through {@code ConcurrentHashMap.compute},
 * so lock-free readers (parallel region ticking) always iterate a consistent bucket.
 */
public final class HardColliders {

    private static final Entity[] NONE = new Entity[0];

    private static final ClassValue<Boolean> POTENTIAL = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("canBeCollidedWith", Entity.class).getDeclaringClass() != Entity.class;
            } catch (ReflectiveOperationException | SecurityException unavailable) {
                return Boolean.TRUE;
            }
        }
    };

    /** Mixed into {@code Level}: section key → entities in that section that may collide. */
    public interface Holder {
        ConcurrentHashMap<Long, Entity[]> rustmi$hardBuckets();
    }

    private HardColliders() {}

    /** Class override, or a mod that patched Entity's own body (instance probe at insertion). */
    private static boolean candidate(Entity e) {
        if (POTENTIAL.get(e.getClass())) {
            return true;
        }
        try {
            return e.canBeCollidedWith(null);
        } catch (Throwable t) {
            return true;
        }
    }

    public static void onSectionAdd(Entity e) {
        if (!(e.level() instanceof Holder h) || e.level().isClientSide() || !candidate(e)) {
            return;
        }
        CrammingTracked t = (CrammingTracked) e;
        if (t.rustmi$hardIndexed()) {
            unbucket(h, t.rustmi$hardKey(), e);   // defensive: never in two buckets
        }
        // addEntity and onMove pick the section from the same blockPosition.
        long key = SectionPos.asLong(e.blockPosition());
        t.rustmi$setHardBucket(true, key);
        h.rustmi$hardBuckets().compute(key, (k, arr) -> with(arr, e));
    }

    public static void onSectionRemove(Entity e) {
        CrammingTracked t = (CrammingTracked) e;
        if (!t.rustmi$hardIndexed() || !(e.level() instanceof Holder h)) {
            return;
        }
        long key = t.rustmi$hardKey();
        t.rustmi$setHardBucket(false, 0L);
        unbucket(h, key, e);
    }

    private static void unbucket(Holder h, long key, Entity e) {
        h.rustmi$hardBuckets().computeIfPresent(key, (k, arr) -> without(arr, e));
    }

    private static Entity[] with(Entity[] arr, Entity e) {
        if (arr == null) {
            return new Entity[]{e};
        }
        for (Entity x : arr) {
            if (x == e) {
                return arr;
            }
        }
        Entity[] n = java.util.Arrays.copyOf(arr, arr.length + 1);
        n[arr.length] = e;
        return n;
    }

    /** @return the bucket without {@code e}, or null to drop an emptied bucket. */
    private static Entity[] without(Entity[] arr, Entity e) {
        int i = 0;
        while (i < arr.length && arr[i] != e) {
            i++;
        }
        if (i == arr.length) {
            return arr;
        }
        if (arr.length == 1) {
            return null;
        }
        Entity[] n = new Entity[arr.length - 1];
        System.arraycopy(arr, 0, n, 0, i);
        System.arraycopy(arr, i + 1, n, i, arr.length - i - 1);
        return n;
    }

    /**
     * False is a guarantee that {@code getEntityCollisions(source, box)} is empty. True means
     * the precise vanilla query must run (a candidate intersects, or the index can't tell).
     */
    public static boolean anyNear(Level level, Entity source, AABB box) {
        if (!(level instanceof Holder h) || level.isClientSide()) {
            return true;
        }
        ConcurrentHashMap<Long, Entity[]> buckets = h.rustmi$hardBuckets();
        if (buckets.isEmpty()) {
            return false;
        }
        // getEntityCollisions queries testArea.inflate(1.0E-7); EntitySectionStorage then
        // visits sections in this window around that box (forEachAccessibleNonEmptySection).
        double minX = box.minX - 1.0E-7, minY = box.minY - 1.0E-7, minZ = box.minZ - 1.0E-7;
        double maxX = box.maxX + 1.0E-7, maxY = box.maxY + 1.0E-7, maxZ = box.maxZ + 1.0E-7;
        int sx0 = SectionPos.posToSectionCoord(minX - 2.0), sx1 = SectionPos.posToSectionCoord(maxX + 2.0);
        int sy0 = SectionPos.posToSectionCoord(minY - 4.0), sy1 = SectionPos.posToSectionCoord(maxY + 0.0);
        int sz0 = SectionPos.posToSectionCoord(minZ - 2.0), sz1 = SectionPos.posToSectionCoord(maxZ + 2.0);
        long window = (long) (sx1 - sx0 + 1) * (sy1 - sy0 + 1) * (sz1 - sz0 + 1);
        if (window > buckets.size()) {
            for (Map.Entry<Long, Entity[]> en : buckets.entrySet()) {
                long key = en.getKey();
                int x = SectionPos.x(key), y = SectionPos.y(key), z = SectionPos.z(key);
                if (x >= sx0 && x <= sx1 && y >= sy0 && y <= sy1 && z >= sz0 && z <= sz1
                        && hits(h, level, key, en.getValue(), source, minX, minY, minZ, maxX, maxY, maxZ)) {
                    return true;
                }
            }
            return false;
        }
        for (int x = sx0; x <= sx1; x++) {
            for (int y = sy0; y <= sy1; y++) {
                for (int z = sz0; z <= sz1; z++) {
                    long key = SectionPos.asLong(x, y, z);
                    Entity[] arr = buckets.get(key);
                    if (arr != null && hits(h, level, key, arr, source, minX, minY, minZ, maxX, maxY, maxZ)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean hits(Holder h, Level level, long key, Entity[] arr, Entity source,
                                double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        for (Entity e : arr) {
            if (e == source) {
                continue;
            }
            if (e.level() != level
                    || (e.isRemoved() && !((CrammingTracked) e).rustmi$inLevel())) {
                // Missed removal: only drop what is provably out of every section (the level
                // callback is cleared after the section removal) — a removed-but-still-
                // sectioned boat is still returned by vanilla.
                unbucket(h, key, e);
                continue;
            }
            AABB bb = e.getBoundingBox();
            if (bb.minX < maxX && bb.maxX > minX && bb.minY < maxY && bb.maxY > minY
                    && bb.minZ < maxZ && bb.maxZ > minZ) {
                return true;
            }
        }
        return false;
    }
}
