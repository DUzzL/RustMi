package com.github.tatercertified.nalim.redstone;

import com.axalotl.fabrolia.common.regions.Region;
import com.axalotl.fabrolia.common.regions.RegionGrid;
import com.axalotl.fabrolia.common.regions.RegionScheduler;
import com.axalotl.fabrolia.common.regions.RegionWorkerThread;
import net.minecraft.world.level.Level;

/**
 * Fabrolia region ownership for the network walk. Only loaded when Fabrolia is present
 * ({@link CascadeRecorder} checks first), so the Fabrolia types never resolve without it.
 */
final class FabroliaRegions {

    private FabroliaRegions() {}

    /** True off region workers; on a region worker, true only for chunks of its region. */
    static boolean ownsChunk(Level level, int cx, int cz) {
        if (!(Thread.currentThread() instanceof RegionWorkerThread w)) {
            return true;
        }
        Region r = w.currentRegion;
        RegionGrid grid = RegionScheduler.grid();
        if (r == null || grid == null || r.level != level) {
            return false;
        }
        return grid.keyFor(cx, cz) == r.gridKey;
    }
}
