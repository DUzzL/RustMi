package com.github.tatercertified.nalim.collision;

/**
 * Duck interface on LevelChunk: per-section collision-class bitsets (4096 bits per class).
 * One immutable {@code long[SNAPSHOT_LONGS]} per section holds all three classes —
 * {@code [FULL_OFF, +64)} block's collision shape is exactly the unit cube,
 * {@code [PARTIAL_OFF, +64)} non-empty shape that is neither a cube nor a state-determined
 * box set, {@code [BOXES_OFF, +64)} state-determined box shapes. A snapshot is never mutated
 * after publication: a shape-class change publishes a modified copy, so a reader that fetched
 * one snapshot always sees a mutually consistent set of classes. Lifetime rides the chunk
 * object — unload = GC, no eviction machinery.
 */
public interface CubeMirror {

    int FULL_OFF = 0;
    int PARTIAL_OFF = 64;
    int BOXES_OFF = 128;
    int SNAPSHOT_LONGS = 192;

    /** @return the section's immutable class snapshot, building it lazily; null for an index
     *  outside the chunk. Uniform sections return the shared {@link Snapshots} constants. */
    long[] oxi$bits(int sectionIdx);

    /** Re-reads the live block at {@code cell} and publishes a reclassified copy when its
     *  class differs from the snapshot. No-op if the section isn't built. */
    void oxi$updateCell(int sectionIdx, int cell);

    /** Drops the section's snapshot so the next read rebuilds it from live state (writers
     *  that bypass LevelChunk.setBlockState). */
    void oxi$invalidateSection(int sectionIdx);

    /** Shared immutable snapshots for uniform sections; identity-comparable. */
    final class Snapshots {
        public static final long[] EMPTY = new long[SNAPSHOT_LONGS];
        public static final long[] SOLID = filled(FULL_OFF);
        public static final long[] ALL_PARTIAL = filled(PARTIAL_OFF);

        private Snapshots() {}

        private static long[] filled(int off) {
            long[] s = new long[SNAPSHOT_LONGS];
            java.util.Arrays.fill(s, off, off + 64, -1L);
            return s;
        }

        /** The shared constant equal to {@code bits}, or {@code bits} itself. */
        public static long[] canonical(long[] bits) {
            if (java.util.Arrays.equals(bits, EMPTY)) {
                return EMPTY;
            }
            if (java.util.Arrays.equals(bits, SOLID)) {
                return SOLID;
            }
            if (java.util.Arrays.equals(bits, ALL_PARTIAL)) {
                return ALL_PARTIAL;
            }
            return bits;
        }
    }
}
