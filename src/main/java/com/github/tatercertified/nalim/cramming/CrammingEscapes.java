package com.github.tatercertified.nalim.cramming;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Predicate;

/**
 * Makes the snapshot-based cramming/sensor candidates exact.
 *
 * <p>The kernel accepts a pair when the padded snapshot boxes intersect
 * ({@code |dc| < ha + hb + SNAPSHOT_PAD}). That is a superset of the live intersection as
 * long as every row's live box stays inside its snapshot box grown by
 * {@link #TOLERANCE} (half the pair pad). Entities that leave that envelope during the
 * tick — falling or launched mobs, minecarts at speed, teleports — and entities that were
 * not in the snapshot at all (spawned or loaded this tick) are "escapees": every query
 * also tests them against live state, and an escaped origin uses the vanilla query.
 * Past {@link #CAP} escapees in one tick every remaining query falls back to vanilla.
 *
 * <p>Candidates are then put into vanilla's iteration order: entity sections by
 * {@code (x, packed z, packed y)} key order, then insertion order inside a section.
 */
public final class CrammingEscapes {

    static final double TOLERANCE = RegionalCrammingGeometry.SNAPSHOT_PAD * 0.5;
    private static final int CAP = 64;

    private static final AtomicReferenceArray<Entity> ESCAPEES = new AtomicReferenceArray<>(CAP);
    private static final AtomicInteger COUNT = new AtomicInteger();
    private static final AtomicLong SECTION_SEQ = new AtomicLong();
    private static volatile int tick = 1;
    private static volatile boolean tracking;

    private CrammingEscapes() {}

    /** Server thread, tickServer HEAD, before any snapshot of this tick. */
    static void beginTick(boolean active) {
        int n = Math.min(COUNT.get(), CAP);
        for (int i = 0; i < n; i++) {
            ESCAPEES.set(i, null);
        }
        COUNT.set(0);
        tick++;
        tracking = active;
    }

    public static boolean tracking() {
        return tracking;
    }

    public static int tick() {
        return tick;
    }

    public static long nextSectionSeq() {
        return SECTION_SEQ.incrementAndGet();
    }

    /** A snapshot row was built from {@code box}. */
    static void snapshotted(Entity e, AABB box) {
        ((CrammingTracked) e).rustmi$snapshotted(tick, box);
    }

    /**
     * Registers {@code e} as an escapee if it can be a cramming/sensor candidate.
     * @return false when {@code e} is not a candidate (caller re-checks on its next move)
     */
    public static boolean offer(Entity e) {
        Level level = e.level();
        if (level == null || level.isClientSide()) {
            return false;
        }
        boolean living = e instanceof LivingEntity le && le.isAlive();
        if (!RustmiCramming.snapshotPushable(e, living)) {
            return false;
        }
        int i = COUNT.getAndIncrement();
        if (i < CAP) {
            ESCAPEES.set(i, e);
        }
        return true;
    }

    /**
     * The entity joins an entity section (added to the level, or moved between sections).
     * Entities are only tracked once they are in a level, so one that was not snapshotted
     * or registered this tick becomes an escapee here.
     */
    public static void onSectionAdd(Entity e) {
        if (!tracking) {
            return;
        }
        int t = tick;
        CrammingTracked tracked = (CrammingTracked) e;
        if (!tracked.rustmi$seen(t) && offer(e)) {
            tracked.rustmi$markEscaped(t);
        }
    }

    /** False when this query must not trust the snapshot (origin escaped, or the
     *  escapee table overflowed this tick). */
    public static boolean originReliable(Entity origin) {
        return COUNT.get() <= CAP && !((CrammingTracked) origin).rustmi$escaped(tick);
    }

    /**
     * Appends live escapees accepted by the query (identity-deduplicated against the
     * kernel candidates already in {@code out}).
     * @param out the kernel candidates, or null for none
     * @return {@code out}, a new list if escapees were found and {@code out} was null,
     *         otherwise null
     */
    @SuppressWarnings("unchecked")
    public static <T extends Entity> List<T> addEscapees(List<T> out, Class<T> type, Entity self,
                                                         AABB box, Predicate<? super T> accept) {
        int n = Math.min(COUNT.get(), CAP);
        if (n == 0) {
            return out;
        }
        Level level = self.level();
        SectionWindow window = SectionWindow.of(box);
        for (int i = 0; i < n; i++) {
            Entity e = ESCAPEES.get(i);
            if (e == null || e == self || !type.isInstance(e) || e.level() != level || e.isRemoved()
                    || !((CrammingTracked) e).rustmi$inLevel()
                    || !box.intersects(e.getBoundingBox())
                    || !window.contains(e)) {
                continue;
            }
            T t = (T) e;
            if (!accept.test(t) || (out != null && containsIdentity(out, e))) {
                continue;
            }
            if (out == null) {
                out = new java.util.ArrayList<>(4);
            }
            out.add(t);
        }
        return out;
    }

    /**
     * {@code EntitySectionStorage.forEachAccessibleNonEmptySection} visits only the sections
     * in this window around the query box, so an entity whose box intersects but whose own
     * section lies outside (giants, scaled or command-sized mobs) is invisible to vanilla's
     * {@code getEntities}; the candidate superset must drop it too. Computed once per query.
     */
    public record SectionWindow(int x0, int x1, int y0, int y1, int z0, int z1) {
        public static SectionWindow of(AABB box) {
            return new SectionWindow(
                    SectionPos.posToSectionCoord(box.minX - 2.0), SectionPos.posToSectionCoord(box.maxX + 2.0),
                    SectionPos.posToSectionCoord(box.minY - 4.0), SectionPos.posToSectionCoord(box.maxY + 0.0),
                    SectionPos.posToSectionCoord(box.minZ - 2.0), SectionPos.posToSectionCoord(box.maxZ + 2.0));
        }

        public boolean contains(Entity e) {
            BlockPos p = e.blockPosition();
            int sx = SectionPos.blockToSectionCoord(p.getX());
            int sy = SectionPos.blockToSectionCoord(p.getY());
            int sz = SectionPos.blockToSectionCoord(p.getZ());
            return sx >= x0 && sx <= x1 && sy >= y0 && sy <= y1 && sz >= z0 && sz <= z1;
        }
    }

    private static boolean containsIdentity(List<? extends Entity> list, Entity e) {
        for (int i = 0, n = list.size(); i < n; i++) {
            if (list.get(i) == e) {
                return true;
            }
        }
        return false;
    }

    private static final class SortScratch {
        long[] keys = new long[64];
        long[] seqs = new long[64];
        Entity[] items = new Entity[64];

        void ensure(int n) {
            if (keys.length < n) {
                int cap = Math.max(n, keys.length * 2);
                keys = new long[cap];
                seqs = new long[cap];
                items = new Entity[cap];
            }
        }
    }

    private static final ThreadLocal<SortScratch> SORT_SCRATCH = ThreadLocal.withInitial(SortScratch::new);

    /**
     * Section key in {@code EntitySectionStorage} iteration order: the forEach loop walks x
     * ascending and then the {@code sectionIds} subset in signed-long order, and with x in the
     * top 22 bits the signed comparison of {@code SectionPos.asLong} keys is exactly that
     * order (x signed, then the packed z and y fields unsigned).
     */
    private static long sectionOrderKey(Entity e) {
        BlockPos p = e.blockPosition();
        return net.minecraft.core.SectionPos.asLong(p.getX() >> 4, p.getY() >> 4, p.getZ() >> 4);
    }

    /** Sorts into the order {@code EntitySectionStorage.getEntities} visits entities. */
    public static void sortVanilla(List<? extends Entity> list) {
        int n = list.size();
        if (n < 2) {
            return;
        }
        SortScratch s = SORT_SCRATCH.get();
        s.ensure(n);
        long[] keys = s.keys;
        long[] seqs = s.seqs;
        Entity[] items = s.items;
        boolean sorted = true;
        for (int i = 0; i < n; i++) {
            Entity e = list.get(i);
            long k = sectionOrderKey(e);
            long q = ((CrammingTracked) e).rustmi$sectionSeq();
            if (i > 0 && (k < keys[i - 1] || (k == keys[i - 1] && q < seqs[i - 1]))) {
                sorted = false;
            }
            keys[i] = k;
            seqs[i] = q;
            items[i] = e;
        }
        if (!sorted && n > 128) {
            // Large overflow piles: O(n log n) regardless of input order.
            int[] idx = new int[n];
            for (int i = 0; i < n; i++) {
                idx[i] = i;
            }
            it.unimi.dsi.fastutil.ints.IntArrays.quickSort(idx, 0, n, (a, b) -> keys[a] != keys[b]
                    ? Long.compare(keys[a], keys[b]) : Long.compare(seqs[a], seqs[b]));
            @SuppressWarnings("unchecked")
            List<Entity> l = (List<Entity>) list;
            for (int i = 0; i < n; i++) {
                l.set(i, items[idx[i]]);
            }
        } else if (!sorted) {
            // Candidate lists come out nearly ordered; insertion sort is linear on those.
            for (int i = 1; i < n; i++) {
                long k = keys[i];
                long q = seqs[i];
                Entity e = items[i];
                int j = i - 1;
                while (j >= 0 && (keys[j] > k || (keys[j] == k && seqs[j] > q))) {
                    keys[j + 1] = keys[j];
                    seqs[j + 1] = seqs[j];
                    items[j + 1] = items[j];
                    j--;
                }
                keys[j + 1] = k;
                seqs[j + 1] = q;
                items[j + 1] = e;
            }
            @SuppressWarnings("unchecked")
            List<Entity> l = (List<Entity>) list;
            for (int i = 0; i < n; i++) {
                l.set(i, items[i]);
            }
        }
        java.util.Arrays.fill(items, 0, n, null);
    }

    public static double tolerance() {
        return TOLERANCE;
    }
}
