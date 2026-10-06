package com.github.tatercertified.nalim.redstone;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * Periodically scans every {@link ServerLevel}'s loaded chunks for high
 * redstone density and {@link RedpilerHost#claim claim}s any area not yet
 * covered. Avoids re-claiming, debounces failed attempts.
 *
 * <p>Triggered from {@link RedpilerHost#tickLevel} once every
 * {@link #SCAN_INTERVAL_TICKS} game ticks. Cost: ~16k getBlockState calls
 * per loaded chunk per scan; with a typical ~50 loaded chunks at 10 s
 * cadence that's ~80k lookups every 10 s — dwarfed by what redstone
 * itself costs.
 */
public final class AutoClaim {

    private static final Logger LOGGER = LoggerFactory.getLogger("Rustmi-RedpilerAuto");

    /** 10 s between scans — slow enough that scanning cost is invisible,
     *  fast enough that a newly built lag machine gets caught soon. */
    private static final int SCAN_INTERVAL_TICKS = 200;
    /** Per-chunk redstone-component count above which the chunk is "hot".
     *  Lowered to 8 because small schemas (6 observers + a couple wires
     *  spread across a 2-floor build) are below 30/chunk yet still benefit
     *  from compilation — the cost of running a walk on a low-redstone
     *  chunk is bounded by the chunk's actual component count (8 components
     *  → ~50-node graph → microseconds). */
    private static final int DENSITY_THRESHOLD = 8;
    /** AABB padding around a hot chunk, in chunks (16-block units). */
    private static final int CHUNK_PADDING = 1;
    /** Hard ceiling on per-chunk count — early-exit when we know it's hot. */
    private static final int COUNT_CAP = 200;

    /** Per-dim debounce: chunks that recently auto-claimed and were
     *  released (or failed) sit on this list and aren't re-tried until
     *  {@link #BLACKLIST_TICKS} pass. Keeps a rejected area from being
     *  re-scanned every 10 s in a loop. Keyed by ChunkPos.toLong. */
    private static final java.util.concurrent.ConcurrentHashMap<Long, Long> BLACKLIST =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int BLACKLIST_TICKS = 6000;   // 5 min

    private static volatile boolean enabled = loadEnabled();
    private static final java.nio.file.Path STATE_FILE =
            java.nio.file.Paths.get("config", "rustmi", "redpiler-auto.properties");

    public static boolean isEnabled() { return enabled; }

    public static synchronized void setEnabled(boolean on) {
        if (enabled == on) return;
        enabled = on;
        persist(on);
        LOGGER.info("[redpiler-auto] {}", on ? "ENABLED" : "DISABLED");
    }

    private static boolean loadEnabled() {
        try {
            if (java.nio.file.Files.exists(STATE_FILE)) {
                for (String line : java.nio.file.Files.readAllLines(STATE_FILE)) {
                    String t = line.trim();
                    if (t.startsWith("enabled=")) {
                        return Boolean.parseBoolean(t.substring(8));
                    }
                }
            }
        } catch (Exception ignored) {}
        // Default OFF — opt-in via /rustmi redpiler auto on.
        return false;
    }

    private static void persist(boolean on) {
        try {
            java.nio.file.Files.createDirectories(STATE_FILE.getParent());
            java.nio.file.Files.write(STATE_FILE,
                    ("# Rustmi redpiler auto-claim\nenabled=" + on + "\n").getBytes());
        } catch (Exception e) {
            LOGGER.warn("could not persist", e);
        }
    }

    private AutoClaim() {}

    public static void maybeScan(ServerLevel level, long gameTime) {
        if (!enabled) return;
        if (gameTime % SCAN_INTERVAL_TICKS != 0) return;
        long t0 = System.nanoTime();
        int playerCount = level.players().size();
        int hotCount = scanAndClaim(level, gameTime);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        if (playerCount > 0 || hotCount > 0) {
            LOGGER.info("[redpiler-auto] scan: {} players, {} hot chunks ({} ms)",
                    playerCount, hotCount, ms);
        }
    }

    private static int scanAndClaim(ServerLevel level, long gameTime) {
        // Iterate chunks around each player (view-distance radius). Avoids
        // needing internal ChunkMap accessors — loaded chunks near players
        // is the right scope anyway (no one cares about lag in chunks no
        // player is near). For each candidate chunk, getChunkNow returns
        // non-null only if loaded; null skip.
        Set<Long> seen = new HashSet<>();
        Set<ChunkPos> hot = new HashSet<>();
        int viewRadius = level.getServer().getPlayerList().getViewDistance() + 1;
        for (net.minecraft.server.level.ServerPlayer p : level.players()) {
            int pcx = net.minecraft.util.Mth.floor(p.getX()) >> 4;
            int pcz = net.minecraft.util.Mth.floor(p.getZ()) >> 4;
            for (int dx = -viewRadius; dx <= viewRadius; dx++) {
                for (int dz = -viewRadius; dz <= viewRadius; dz++) {
                    int cx = pcx + dx, cz = pcz + dz;
                    long key = ChunkPos.pack(cx, cz);
                    if (!seen.add(key)) continue;
                    Long bl = BLACKLIST.get(key);
                    if (bl != null && gameTime - bl < BLACKLIST_TICKS) continue;
                    LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                    if (chunk == null) continue;
                    ChunkPos cp = chunk.getPos();
                    BlockPos probe = new BlockPos(cp.getMinBlockX() + 8, 80, cp.getMinBlockZ() + 8);
                    if (RedpilerHost.containsAny(level, probe)) continue;
                    int count = countRedstoneInChunk(chunk);
                    if (count >= DENSITY_THRESHOLD) hot.add(cp);
                }
            }
        }
        if (hot.isEmpty()) return 0;
        // Cluster all hot chunks into one bounding AABB to avoid overlap
        // failures. Per-chunk claims would each try a 3×3 padded box; two
        // adjacent hot chunks then collide. One big AABB covers the whole
        // hot region in a single walk. If two separate lag machines hit
        // in the same scan they end up in one box too — wastes some
        // walker time on the empty space between but no correctness loss.
        int minCx = Integer.MAX_VALUE, maxCx = Integer.MIN_VALUE;
        int minCz = Integer.MAX_VALUE, maxCz = Integer.MIN_VALUE;
        for (ChunkPos cp : hot) {
            if (cp.x() < minCx) minCx = cp.x();
            if (cp.x() > maxCx) maxCx = cp.x();
            if (cp.z() < minCz) minCz = cp.z();
            if (cp.z() > maxCz) maxCz = cp.z();
        }
        minCx -= CHUNK_PADDING;
        maxCx += CHUNK_PADDING;
        minCz -= CHUNK_PADDING;
        maxCz += CHUNK_PADDING;
        BlockPos min = new BlockPos(minCx << 4, level.getMinY(), minCz << 4);
        BlockPos max = new BlockPos((maxCx << 4) | 15, level.getMaxY(), (maxCz << 4) | 15);
        RedpilerHost.ClaimResult r = RedpilerHost.claim(level, min, max);
        if (r.handle >= 0) {
            // Fresh claim — no pending-tick replay path applies here, so we
            // do the warm-up immediately. Gates whose walk-time POWERED
            // snapshot disagrees with the newly built input histogram need
            // update_node fired once to schedule the corrective tick.
            RedpilerHost.warmupSession(r.handle);
            LOGGER.info("[redpiler-auto] claimed #{} covering {} hot chunks "
                    + "[{}..{}, {}..{}] ({} nodes, {} edges)",
                    r.handle, hot.size(), minCx, maxCx, minCz, maxCz,
                    r.nodes, r.edges);
        } else {
            // Blacklist all hot chunks so we don't retry the same cluster
            // every 10s. Existing-session overlap is OK (someone manually
            // claimed); empty-walk failures are blacklisted.
            for (ChunkPos cp : hot) BLACKLIST.put(cp.pack(), gameTime);
            if (!r.message.contains("overlaps")) {
                LOGGER.warn("[redpiler-auto] claim failed: {}", r.message);
            }
        }
        return hot.size();
    }

    private static int countRedstoneInChunk(LevelChunk chunk) {
        int count = 0;
        LevelChunkSection[] sections = chunk.getSections();
        for (LevelChunkSection sec : sections) {
            if (sec == null || sec.hasOnlyAir()) continue;
            // Quick palette gate: if no redstone-topology block is in the
            // palette at all, this section can't contribute. Saves the
            // 4096-cell walk for the common "dirt/stone" sections.
            if (!sec.maybeHas(s -> RedpilerHost.isRedstoneTopologyBlock(s.getBlock()))) {
                continue;
            }
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState s = sec.getBlockState(x, y, z);
                        if (RedpilerHost.isRedstoneTopologyBlock(s.getBlock())) {
                            count++;
                            if (count >= COUNT_CAP) return count;
                        }
                    }
                }
            }
        }
        return count;
    }

    public static String status() {
        return "auto-claim=" + enabled
                + " interval=" + SCAN_INTERVAL_TICKS + "t"
                + " density=" + DENSITY_THRESHOLD + "/chunk"
                + " blacklist=" + BLACKLIST.size();
    }
}
