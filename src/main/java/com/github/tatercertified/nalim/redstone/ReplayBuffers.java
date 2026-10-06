package com.github.tatercertified.nalim.redstone;

import com.github.tatercertified.nalim.cramming.RustmiCramming;
import net.minecraft.core.BlockPos;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-thread off-heap marshalling buffers shared by the shadow comparator
 * and the R3 takeover engine (~4.3 MB, lazily allocated; redstone runs on
 * the server thread, so in practice this is one allocation).
 */
final class ReplayBuffers {

    final ByteBuffer nodes;
    final ByteBuffer edges;
    final ByteBuffer out;
    final long nodesAddr, edgesAddr, outAddr;

    private static final ThreadLocal<ReplayBuffers> TL = ThreadLocal.withInitial(ReplayBuffers::new);

    static ReplayBuffers get() {
        return TL.get();
    }

    private ReplayBuffers() {
        int maxNetwork = CascadeRecorder.maxNetwork();
        int maxEvents = CascadeRecorder.maxEvents();
        nodes = ByteBuffer.allocateDirect(maxNetwork * 28).order(ByteOrder.nativeOrder());
        edges = ByteBuffer.allocateDirect(maxNetwork * 8 * 4).order(ByteOrder.nativeOrder());
        out = ByteBuffer.allocateDirect(maxEvents * 20).order(ByteOrder.nativeOrder());
        nodesAddr = RustmiCramming.addressOf(nodes);
        edgesAddr = RustmiCramming.addressOf(edges);
        outAddr = RustmiCramming.addressOf(out);
    }

    /**
     * Marshal a walked network into the node/edge buffers (7-i32 records +
     * flat edge indices) and fill {@code outIndex} with pos→index. Returns
     * the edge count.
     */
    int marshal(Map<BlockPos, CascadeRecorder.Node> network,
                List<CascadeRecorder.Node> orderedOut, Map<BlockPos, Integer> outIndex) {
        var u = RustmiCramming.theUnsafe();
        orderedOut.clear();
        orderedOut.addAll(network.values());
        outIndex.clear();
        if (!(outIndex instanceof HashMap<BlockPos, Integer>)) {
            throw new IllegalArgumentException("index map");
        }
        for (int i = 0; i < orderedOut.size(); i++) {
            outIndex.put(orderedOut.get(i).pos, i);
        }
        int edgeCursor = 0;
        for (int i = 0; i < orderedOut.size(); i++) {
            CascadeRecorder.Node n = orderedOut.get(i);
            long base = nodesAddr + (long) i * 28;
            u.putInt(base, n.pos.getX());
            u.putInt(base + 4, n.pos.getY());
            u.putInt(base + 8, n.pos.getZ());
            u.putInt(base + 12, n.power);
            u.putInt(base + 16, n.strongPower);
            u.putInt(base + 20, edgeCursor);
            int len = 0;
            for (BlockPos e : n.edges) {
                Integer ei = outIndex.get(e);
                if (ei != null) {
                    u.putInt(edgesAddr + (long) edgeCursor * 4, ei);
                    edgeCursor++;
                    len++;
                }
            }
            u.putInt(base + 24, len);
        }
        return edgeCursor;
    }
}
