package com.github.tatercertified.nalim.rust;

/**
 * {@link AbiProbe} against a pure-Java model of the probed exports: the correct model passes,
 * a model that swaps two arguments (what a register mix-up looks like) is reported.
 */
public final class AbiProbeTest {

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

    /** Java model of the Rust exports the probe calls. */
    static class Model implements AbiProbe.Calls {
        public int distanceSort(long xs, long ys, long zs, double tx, double ty, double tz, int n, long perm) {
            Integer[] idx = new Integer[n];
            double[] d = new double[n];
            for (int i = 0; i < n; i++) {
                idx[i] = i;
                double dx = U.getDouble(xs + i * 8L) - tx;
                double dy = U.getDouble(ys + i * 8L) - ty;
                double dz = U.getDouble(zs + i * 8L) - tz;
                d[i] = dx * dx + dy * dy + dz * dz;
            }
            java.util.Arrays.sort(idx, (a, b) -> Double.compare(d[a], d[b]));
            for (int i = 0; i < n; i++) {
                U.putInt(perm + i * 4L, idx[i]);
            }
            return 0;
        }

        public long sectionAlloc(int words) {
            long raw = U.allocateMemory(words * 8L + 64);
            long a = (raw + 63) & ~63L;
            U.setMemory(a, words * 8L, (byte) 0);
            return a;
        }

        public void sectionFree(long addr, int words) {
            // the model leaks its few bytes on purpose (the aligned address is not the raw one)
        }

        private double px, pz;
        private int[] caps = new int[8];

        public long spawncapBuild(long entities, int nEnt, long players, int nPlay, int spawnable, long caps) {
            px = U.getDouble(players);
            pz = U.getDouble(players + 8);
            for (int i = 0; i < 8; i++) {
                this.caps[i] = U.getInt(caps + i * 4L);
            }
            return 1;
        }

        public int spawncapCanLocal(long handle, int cat, int cx, int cz) {
            double dx = cx * 16 + 8 - px, dz = cz * 16 + 8 - pz;
            return dx * dx + dz * dz < 16384.0 && 0 < caps[cat] ? 1 : 0;
        }

        public int spawncapFree(long handle) {
            return 0;
        }
    }

    public static void main(String[] args) {
        AbiProbe.run("model", new Model());

        // A broken convention: the target's y and z swap places.
        String msg = null;
        try {
            AbiProbe.run("broken", new Model() {
                @Override
                public int distanceSort(long xs, long ys, long zs, double tx, double ty, double tz, int n, long perm) {
                    return super.distanceSort(xs, ys, zs, tz, ty, tx, n, perm);
                }
            });
        } catch (IllegalStateException e) {
            msg = e.getMessage();
        }
        if (msg == null || !msg.contains("oxi_distance_sort")) {
            throw new AssertionError("broken distance sort not reported: " + msg);
        }

        // A broken six-argument call: the caps pointer arrives as the spawnable count.
        msg = null;
        try {
            AbiProbe.run("broken", new Model() {
                @Override
                public long spawncapBuild(long entities, int nEnt, long players, int nPlay, int spawnable, long caps) {
                    return 0;
                }
            });
        } catch (IllegalStateException e) {
            msg = e.getMessage();
        }
        if (msg == null || !msg.contains("oxi_spawncap_build")) {
            throw new AssertionError("broken spawn-cap build not reported: " + msg);
        }
        System.out.println("AbiProbeTest: ok");
    }
}
