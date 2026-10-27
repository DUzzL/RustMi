package com.github.tatercertified.nalim.redstone;

import com.github.tatercertified.nalim.cramming.RustmiCramming;
import com.github.tatercertified.nalim.rust.RustmiLib;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * R2 shadow engine: for every captured cascade burst, replay the network
 * snapshot through the Rust simulator (checked bit-exact against recorded
 * cascades; fixtures/redstone keeps three of them) and diff the predicted event stream
 * against what vanilla actually did. Observation only — vanilla's results
 * are what the world keeps; this builds the live confidence corpus that
 * gates the R3 flip.
 *
 * <p>Entry discipline is adaptive per entry (InDrain vs Depth0 — see
 * sim.rs): the burst's recorded collector depth picks the first try, a
 * mismatch rolls the Rust-side power state back via {@code oxi_redstone_undo}
 * and tries the other, exactly like the fixture corpus test.
 */
final class CascadeShadow {

    private static final Logger LOGGER = LoggerFactory.getLogger("Rustmi-RedstoneShadow");

    private static final AtomicLong HANDLES = new AtomicLong(0xC0FFEE);

    private static final AtomicLong BURSTS      = new AtomicLong();
    private static final AtomicLong MATCHED     = new AtomicLong();
    private static final AtomicLong MISMATCHED  = new AtomicLong();
    private static final AtomicLong SKIPPED     = new AtomicLong();
    private static final AtomicLong ERRORS      = new AtomicLong();
    private static final AtomicLong SIM_NANOS   = new AtomicLong();
    private static final AtomicLong VAN_NANOS   = new AtomicLong();
    private static final ConcurrentLinkedQueue<String> EXAMPLES = new ConcurrentLinkedQueue<>();
    private static final int MAX_EXAMPLES = 6;

    private CascadeShadow() {}

    static void resetStats() {
        for (AtomicLong c : new AtomicLong[]{BURSTS, MATCHED, MISMATCHED, SKIPPED, ERRORS, SIM_NANOS, VAN_NANOS}) {
            c.set(0);
        }
        EXAMPLES.clear();
    }

    static String status() {
        long b = BURSTS.get();
        StringBuilder sb = new StringBuilder(String.format(
                "shadow bursts=%d matched=%d mismatched=%d skipped(multiNet/overflow)=%d errors=%d",
                b, MATCHED.get(), MISMATCHED.get(), SKIPPED.get(), ERRORS.get()));
        if (VAN_NANOS.get() > 0) {
            sb.append(String.format(" | vanilla %.2fms vs sim %.2fms (x%.1f)",
                    VAN_NANOS.get() / 1e6, SIM_NANOS.get() / 1e6,
                    SIM_NANOS.get() == 0 ? 0.0 : (double) VAN_NANOS.get() / SIM_NANOS.get()));
        }
        if (!EXAMPLES.isEmpty()) {
            sb.append(" | ").append(String.join("; ", EXAMPLES));
        }
        return sb.toString();
    }

    /**
     * Diff one finished burst against the simulator. Never throws past the
     * caller's guard; vanilla already won — this only updates statistics.
     */
    static void compare(CascadeRecorder.Burst b, long vanillaNanos) {
        if (b.multiNetwork || b.overflow || b.network.isEmpty() || b.events.isEmpty()) {
            SKIPPED.incrementAndGet();
            return;
        }
        BURSTS.incrementAndGet();
        VAN_NANOS.addAndGet(vanillaNanos);
        long t0 = System.nanoTime();

        ReplayBuffers buf = ReplayBuffers.get();
        var u = RustmiCramming.theUnsafe();
        List<CascadeRecorder.Node> nodes = new ArrayList<>(b.network.size());
        Map<BlockPos, Integer> index = new HashMap<>(b.network.size() * 2);
        int edgeCursor = buf.marshal(b.network, nodes, index);

        long handle = HANDLES.incrementAndGet();
        try {
            if (RustmiLib.oxi_redstone_begin(handle, buf.nodesAddr, nodes.size(), buf.edgesAddr, edgeCursor) != 0) {
                ERRORS.incrementAndGet();
                return;
            }
            int consumed = 0;
            while (consumed < b.events.size()) {
                String entryEv = b.events.get(consumed);
                Integer trigger = entryEv.startsWith("EVAL ") ? index.get(parsePos(entryEv)) : null;
                if (trigger == null) {
                    mismatch(b, consumed, "non-EVAL/unknown entry");
                    return;
                }
                // burst's collector depth picks the first discipline to try
                int[] modes = (consumed == 0 && b.entryDepth0) ? new int[]{1, 0} : new int[]{0, 1};
                int advanced = -1;
                for (int mode : modes) {
                    int count = RustmiLib.oxi_redstone_entry(handle, trigger, mode, buf.outAddr, CascadeRecorder.maxEvents());
                    if (count <= 0 || consumed + count > b.events.size()) {
                        RustmiLib.oxi_redstone_undo(handle);
                        continue;
                    }
                    boolean ok = true;
                    for (int i = 0; i < count; i++) {
                        if (!eventEquals(u, buf.outAddr, i, b.events.get(consumed + i))) {
                            ok = false;
                            break;
                        }
                    }
                    if (ok) {
                        advanced = count;
                        break;
                    }
                    RustmiLib.oxi_redstone_undo(handle);
                }
                if (advanced < 0) {
                    mismatch(b, consumed, "no discipline reproduces the span");
                    return;
                }
                consumed += advanced;
            }
            MATCHED.incrementAndGet();
        } finally {
            RustmiLib.oxi_redstone_end(handle);
            SIM_NANOS.addAndGet(System.nanoTime() - t0);
        }
    }

    private static void mismatch(CascadeRecorder.Burst b, int at, String why) {
        MISMATCHED.incrementAndGet();
        if (EXAMPLES.size() < MAX_EXAMPLES) {
            int end = Math.min(at + 3, b.events.size());
            String ex = "ev#" + at + " (" + why + ") vanilla=" + b.events.subList(at, end);
            EXAMPLES.add(ex);
            LOGGER.warn("[redstone-shadow] mismatch {}", ex);
        }
    }

    private static BlockPos parsePos(String ev) {
        String[] p = ev.split(" ");
        return new BlockPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
    }

    /** Compare one predicted binary event record against the recorder's string. */
    private static boolean eventEquals(sun.misc.Unsafe u, long outAddr, int i, String actual) {
        long base = outAddr + (long) i * 20;
        int tag = u.getInt(base);
        int x = u.getInt(base + 4), y = u.getInt(base + 8), z = u.getInt(base + 12);
        int val = u.getInt(base + 16);
        String[] p = actual.split(" ");
        return switch (tag) {
            case 0 -> p.length == 5 && p[0].equals("EVAL")
                    && eq(p, x, y, z) && Integer.parseInt(p[4]) == val;
            case 1 -> p.length == 5 && p[0].equals("SET")
                    && eq(p, x, y, z) && Integer.parseInt(p[4]) == val;
            case 2 -> p.length == 4 && p[0].equals("UPD") && eq(p, x, y, z);
            default -> false;
        };
    }

    private static boolean eq(String[] p, int x, int y, int z) {
        return Integer.parseInt(p[1]) == x && Integer.parseInt(p[2]) == y && Integer.parseInt(p[3]) == z;
    }
}
