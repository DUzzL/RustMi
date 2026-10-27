package com.github.tatercertified.nalim.rust;

import one.nalim.Link;
import one.nalim.Linker;

/**
 * Nalim (JVMCI) bindings for the RustMI native library — the single source of truth for the
 * native surface. The build generates {@code RustmiLib} (the facade every caller uses) and
 * {@code FfmBindings} (the Foreign Function &amp; Memory fallback) from these declarations;
 * only functions that are short, bounded and lock-free (or hold only a short private lock)
 * may carry {@link Critical}.
 *
 * <p>Initialized only when {@code NativeBackend} selected Nalim. The bootstrap publishes the
 * extracted library's absolute path through {@code rustmi.native.path}; an explicit path is
 * never silently replaced with a different library from {@code java.library.path}.
 */
public final class NalimBindings {
    static {
        // LoadRustBinary always publishes the extracted library's path before this runs.
        String nativePath = System.getProperty("rustmi.native.path");
        if (nativePath == null || nativePath.isBlank()) {
            throw new IllegalStateException("rustmi.native.path is not set (LoadRustBinary did not run)");
        }
        Linker.loadLibrary(nativePath);
        Linker.linkClass(NalimBindings.class);
    }

    // ====================================================================
    // Batched cramming kernel.
    //
    // Off-heap pointers (Java side passes ((DirectBuffer)buf).address())
    // so GC cannot relocate the data during the call.
    //
    // See com.github.tatercertified.nalim.cramming.RustmiCramming for
    // the higher-level controller and packing helpers.
    // ====================================================================

    /**
     * Process one tick of cramming for {@code n} mobs whose state is
     * packed into the off-heap buffer at {@code inputsAddr} (64 bytes
     * per slot, layout matches Rust's {@code CrammingInput}). Writes
     * candidate slot indices into the buffer at {@code outputsAddr}
     * (512 bytes per slot).
     */
    @Link(name = "oxi_cramming_batch")
    public static native void oxi_cramming_batch(long inputsAddr, long outputsAddr, int n);

    /** Returns {@code sizeof(CrammingInput)} — 64. Checked at startup (NativeBackend, CrammingHooks). */
    @Critical
    @Link(name = "oxi_cramming_input_size")
    public static native int oxi_cramming_input_size();

    /** Returns {@code sizeof(CrammingOutput)} — 512. */
    @Critical
    @Link(name = "oxi_cramming_output_size")
    public static native int oxi_cramming_output_size();

    /** Native snapshot padding constants, checked against Java at startup. */
    @Critical
    @Link(name = "oxi_cramming_pad")
    public static native double oxi_cramming_pad();

    @Critical
    @Link(name = "oxi_sensor_pad")
    public static native double oxi_sensor_pad();

    /**
     * Retain {@code n} CrammingInput records at {@code inputsAddr} for lazy
     * sensor-range queries under {@code handle} until
     * {@link #oxi_sensor_end}. The Java side guarantees the buffer stays
     * alive and unmodified between begin and end (RegionBatch owns it for
     * exactly the region tick).
     */
    @Link(name = "oxi_sensor_begin")
    public static native void oxi_sensor_begin(long handle, long inputsAddr, int n);

    /** Drop the retention registered under {@code handle}. */
    @Link(name = "oxi_sensor_end")
    public static native void oxi_sensor_end(long handle);

    /**
     * Write the slots of FLAG_IS_LIVING entities whose padded snapshot AABB
     * intersects the {@code range}-inflated box around {@code slot}'s
     * snapshot AABB into the i32 array at {@code outAddr} (superset
     * semantics — the caller re-filters against live state). Returns the
     * count, or -1 (unknown handle / slot out of range / more than
     * {@code outCap} candidates) — caller falls back to the vanilla query.
     */
    @Link(name = "oxi_sensor_query")
    public static native int oxi_sensor_query(long handle, int slot, double range, long outAddr, int outCap);

    /**
     * Unbounded cramming candidate re-query for an OVERFLOW row: slots of
     * pushable entities whose PAD-padded snapshot AABB intersects
     * {@code slot}'s snapshot AABB, written as i32s to {@code outAddr}.
     * Same superset contract as the kernel's per-row candidate lists, no
     * record cap. Returns the count, or -1 (unknown handle / slot out of
     * range / more than {@code outCap} candidates) — caller falls back to
     * the vanilla section walk.
     */
    @Link(name = "oxi_cramming_query")
    public static native int oxi_cramming_query(long handle, int slot, long outAddr, int outCap);

    // ── Redstone wire cascade replay (CascadeEngine, CascadeShadow) ─────

    /** Retain a marshalled wire network (7-i32 node records + flat i32 edge
     *  indices) under {@code handle}. 0 on success, -1 on bad input. */
    @Link(name = "oxi_redstone_begin")
    public static native int oxi_redstone_begin(long handle, long nodesAddr, int n, long edgesAddr, int edgeCount);

    /** Simulate one cascade entry (mode 0=InDrain, 1=Depth0) writing 5-i32
     *  event records (tag,x,y,z,val) to {@code outAddr}; auto-saves power
     *  state for {@link #oxi_redstone_undo}. Event count, or -1. */
    @Link(name = "oxi_redstone_entry")
    public static native int oxi_redstone_entry(long handle, int triggerIdx, int mode, long outAddr, int outCap);

    /** Roll back the last entry (adaptive discipline retry). 0 or -1. */
    @Link(name = "oxi_redstone_undo")
    public static native int oxi_redstone_undo(long handle);

    /** Drop the retention. */
    @Link(name = "oxi_redstone_end")
    public static native void oxi_redstone_end(long handle);

    /** Palette-packed sweep (128 B header v2, skip-air split; air triples
     *  land backwards from the out tail, count in header[96]). */
    @Link(name = "oxi_explosion_sweep_packed")
    public static native int oxi_explosion_sweep_packed(long headerAddr, long floatsAddr, long sectionsAddr, long blobAddr, long outAddr);

    /** Full-cube Entity.collide fast path (88 B header, solids f64×6, out f64×3).
     *  Returns 0 on success, 1 for an epsilon/order-sensitive exact fallback,
     *  or -1 for malformed input. */
    @Critical
    @Link(name = "oxi_collide")
    public static native int oxi_collide(long headerAddr, long solidsAddr, long outAddr);

    /** C3: mixed cubes + voxel grids in discovery order (tags stream);
     *  return contract matches {@link #oxi_collide(long, long, long)}. */
    @Critical
    @Link(name = "oxi_collide2")
    public static native int oxi_collide2(long headerAddr, long solidsAddr, long gridsAddr, long tagsAddr, long outAddr);

    /** Stable distance sort for Fabrolia sensors: writes a permutation of
     *  [0, n) ordered by squared distance to (tx,ty,tz), ties by index —
     *  TimSort-equivalent. Object refs never cross the boundary. */
    @Link(name = "oxi_distance_sort")
    public static native int oxi_distance_sort(long xsAddr, long ysAddr, long zsAddr,
                                               double tx, double ty, double tz,
                                               int n, long outPermAddr);

    /** NativeSections: zeroed 64-aligned native u64[words]; 0 = OOM/bad args.
     *  Java reads it via Unsafe address arithmetic (no per-block FFI); Rust
     *  kernels read the same memory directly. */
    @Critical
    @Link(name = "oxi_section_alloc")
    public static native long oxi_section_alloc(int words);

    /** words MUST match the allocation; addr unusable afterwards. */
    @Critical
    @Link(name = "oxi_section_free")
    public static native void oxi_section_free(long addr, int words);

    /** out: 2×i64 — live bytes, live block count. */
    @Critical
    @Link(name = "oxi_section_stats")
    public static native void oxi_section_stats(long outAddr);

    // ===== Mob-cap arena (util::spawncap) — pointers passed as long addresses (addressOf).
    /** Build the per-tick arena from marshalled entity + player records. @return handle, 0 on bad args. */
    @Link(name = "oxi_spawncap_build")
    public static native long oxi_spawncap_build(long entitiesAddr, int nEnt,
                                                 long playersAddr, int nPlay,
                                                 int spawnableChunks, long capsAddr);

    /** vanilla canSpawnForCategoryGlobal → 1 allowed / 0 capped / -1 bad args. */
    @Critical
    @Link(name = "oxi_spawncap_can_global")
    public static native int oxi_spawncap_can_global(long handle, int cat);

    /** vanilla LocalMobCapCalculator.canSpawn → 1 allowed / 0 capped|no-near / -1 bad args. */
    @Critical
    @Link(name = "oxi_spawncap_can_local")
    public static native int oxi_spawncap_can_local(long handle, int cat, int cx, int cz);

    /** Fabrolia fused local-cap check + pack reservation. @return 1 reserved / 0 capped / -1 bad args. */
    @Critical
    @Link(name = "oxi_spawncap_try_reserve_pack")
    public static native int oxi_spawncap_try_reserve_pack(
            long handle, int cat, int cx, int cz, int count);

    /** Return an unconsumed Fabrolia pack reservation. @return 0 / -1 bad args. */
    @Critical
    @Link(name = "oxi_spawncap_release_pack")
    public static native int oxi_spawncap_release_pack(
            long handle, int cat, int cx, int cz, int count);

    /** vanilla afterSpawn (global +1, addMob to near players). @return 0 / -1 bad args. */
    @Critical
    @Link(name = "oxi_spawncap_add")
    public static native int oxi_spawncap_add(long handle, int cat, int cx, int cz, int isMob);

    /** vanilla PotentialCalculator.getPotentialEnergyChange(pos, charge). */
    @Critical
    @Link(name = "oxi_spawncap_potential")
    public static native double oxi_spawncap_potential(long handle, int x, int y, int z, double charge);

    /** Reclaim the arena (tick end). @return 0. */
    @Critical
    @Link(name = "oxi_spawncap_free")
    public static native int oxi_spawncap_free(long handle);
}
