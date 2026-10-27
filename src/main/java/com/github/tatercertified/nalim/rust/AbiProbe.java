package com.github.tatercertified.nalim.rust;

/**
 * Known-answer self-test of the native calling convention, run by {@code NativeBackend} before
 * a backend is published. The record-size probe only covers argument-less calls; these calls
 * mix integer and floating-point registers, use six integer arguments (the SysV register
 * limit) and pass pointers both ways. A wrong answer means arguments are not arriving where
 * Rust reads them, which no later check would notice.
 *
 * <p>Minecraft-free on purpose: it runs from {@code RustmiMixinPlugin.onLoad}.
 */
public final class AbiProbe {

    /** The probed exports, implemented once per backend. */
    public interface Calls {
        int distanceSort(long xs, long ys, long zs, double tx, double ty, double tz, int n, long perm);

        long sectionAlloc(int words);

        void sectionFree(long addr, int words);

        long spawncapBuild(long entities, int nEnt, long players, int nPlay, int spawnable, long caps);

        int spawncapCanLocal(long handle, int cat, int cx, int cz);

        int spawncapFree(long handle);
    }

    private static final sun.misc.Unsafe U;

    static {
        try {
            var f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            U = (sun.misc.Unsafe) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private AbiProbe() {}

    /** @throws IllegalStateException naming the first call whose answer is wrong */
    public static void run(String backend, Calls calls) {
        long mem = U.allocateMemory(256);
        try {
            U.setMemory(mem, 256, (byte) 0);
            probeDistanceSort(backend, calls, mem);
            probeSections(backend, calls);
            probeSpawnCap(backend, calls, mem);
        } finally {
            U.freeMemory(mem);
        }
    }

    private static void probeDistanceSort(String backend, Calls calls, long mem) {
        long xs = mem, ys = mem + 24, zs = mem + 48, perm = mem + 72;
        double[][] p = {{5.0, 2.0, -3.0}, {0.0, 0.0, 0.0}, {1.0, 2.0, -3.0}};
        for (int i = 0; i < 3; i++) {
            U.putDouble(xs + i * 8L, p[i][0]);
            U.putDouble(ys + i * 8L, p[i][1]);
            U.putDouble(zs + i * 8L, p[i][2]);
        }
        // Squared distances from (0.9, 2, -3): 16.81, 13.81, 0.01 -> order 2, 1, 0. Any swap of
        // the three target coordinates changes the order (e.g. y/z swapped: 1, 2, 0).
        int rc = calls.distanceSort(xs, ys, zs, 0.9, 2.0, -3.0, 3, perm);
        if (rc != 0 || U.getInt(perm) != 2 || U.getInt(perm + 4) != 1 || U.getInt(perm + 8) != 0) {
            throw fail(backend, "oxi_distance_sort", "rc=" + rc + " perm=" + U.getInt(perm) + ","
                    + U.getInt(perm + 4) + "," + U.getInt(perm + 8) + " (expected 0 and 2,1,0)");
        }
    }

    private static void probeSections(String backend, Calls calls) {
        long a = calls.sectionAlloc(8);
        if (a == 0 || (a & 63) != 0) {
            throw fail(backend, "oxi_section_alloc", "address " + a + " (expected non-zero, 64-aligned)");
        }
        U.putLong(a + 56, 0x5EC7_10A5L);
        boolean ok = U.getLong(a) == 0 && U.getLong(a + 56) == 0x5EC7_10A5L;
        calls.sectionFree(a, 8);
        if (!ok) {
            throw fail(backend, "oxi_section_alloc", "block not zeroed or not writable");
        }
    }

    private static void probeSpawnCap(String backend, Calls calls, long mem) {
        long players = mem + 128, caps = mem + 160;
        U.putDouble(players, 8.0);        // player at block (8, 8): chunk (0, 0)
        U.putDouble(players + 8, 8.0);
        U.putInt(players + 16, 0);        // not a spectator
        for (int i = 0; i < 8; i++) {
            U.putInt(caps + i * 4L, i + 1);
        }
        long h = calls.spawncapBuild(0L, 0, players, 1, 0, caps);
        if (h == 0) {
            throw fail(backend, "oxi_spawncap_build", "handle 0 for one player and no mobs");
        }
        int near = calls.spawncapCanLocal(h, 3, 0, 0);    // player in range, count 0 < cap 4
        int far = calls.spawncapCanLocal(h, 3, 100, 100); // no player within 128 blocks
        int freed = calls.spawncapFree(h);
        if (near != 1 || far != 0 || freed != 0) {
            throw fail(backend, "oxi_spawncap_can_local/free",
                    "near=" + near + " far=" + far + " free=" + freed + " (expected 1, 0, 0)");
        }
    }

    private static IllegalStateException fail(String backend, String call, String detail) {
        return new IllegalStateException(backend + " ABI self-test failed at " + call + ": " + detail);
    }
}
