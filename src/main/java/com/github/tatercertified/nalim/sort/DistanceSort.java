package com.github.tatercertified.nalim.sort;

import com.github.tatercertified.nalim.cramming.RustmiCramming;
import com.github.tatercertified.nalim.rust.RustmiLib;

import java.nio.ByteBuffer;

/**
 * Public surface for Fabrolia's sensor distance sort (reached via a
 * MethodHandle bridge, mirror of HardColliderBridge in the other direction).
 *
 * <p>Contract: writes into {@code perm} a permutation of [0, n) ordered by
 * squared distance to (tx, ty, tz) with ties broken by original index —
 * exactly the stable (TimSort) order vanilla's sensor sort produces. The
 * caller owns the direct buffers and applies the permutation to its
 * Object[] — object references never cross the FFI boundary.
 */
public final class DistanceSort {

    private DistanceSort() {}

    private static volatile boolean failed;

    /** @return true on success; false → caller must use its Java fallback. */
    public static boolean sort(ByteBuffer xs, ByteBuffer ys, ByteBuffer zs,
                               double tx, double ty, double tz,
                               int n, ByteBuffer perm) {
        if (failed) {
            return false;
        }
        try {
            return RustmiLib.oxi_distance_sort(
                    RustmiCramming.addressOf(xs),
                    RustmiCramming.addressOf(ys),
                    RustmiCramming.addressOf(zs),
                    tx, ty, tz, n,
                    RustmiCramming.addressOf(perm)) == 0;
        } catch (Throwable t) {
            // Was swallowed silently on every call; report once and stay on the Java sort.
            failed = true;
            org.slf4j.LoggerFactory.getLogger("Rustmi").error(
                    "[distance-sort] native sort failed — Fabrolia uses its Java sort from now on", t);
            return false;
        }
    }
}
