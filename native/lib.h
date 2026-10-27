#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>

/**
 * One origin's output record: candidate count + slot indices.
 * count == OVERFLOW (-1) means "more than MAX_CANDIDATES neighbours —
 * use the uncapped retained-snapshot query".
 */
#define MAX_CANDIDATES 127

#define OVERFLOW -1

#define FLAG_IS_PUSHABLE (1 << 0)

#define FLAG_IS_ORIGIN (1 << 5)

#define FLAG_IS_LIVING (1 << 6)

#define MODE_UNIFORM 0

#define MODE_PACKED 1

#define MODE_DENSE 2

/**
 * Vanilla default for ChainRestrictedNeighborUpdater's depth budget.
 */
#define VANILLA_MAX_CHAIN 1000000

#define N_CATEGORIES 8

/**
 * One entity's input record. 64 bytes.
 */
typedef struct CrammingInput {
  /**
   * World position (cx,cy,cz centre of entity's AABB).
   */
  double pos[3];
  /**
   * AABB half-extents from pos.
   */
  double half[3];
  /**
   * Dimension key — entities with different dim_id never interact.
   */
  int32_t dim_id;
  /**
   * Bit flags (see FLAG_*).
   */
  int32_t flags;
  int32_t _pad[2];
} CrammingInput;

typedef struct CrammingOutput {
  int32_t count;
  int32_t slots[MAX_CANDIDATES];
} CrammingOutput;

/**
 * Returns 0 on success, 1 when Java must use the exact fallback, or -1 on bad args.
 *
 * # Safety
 * All pointers must reference buffers of the stated sizes.
 */
int32_t oxi_collide(const uint8_t *header_ptr, const double *solids_ptr, double *out_ptr);

/**
 * C3 entry — mixed cubes + voxel grids in discovery order.
 *
 * header layout (96 B): as v1 up to 84, then
 *   84..88  i32 gridCount
 *   88..92  i32 colliderCount (total tags)
 *
 * tags_ptr   → colliderCount × u8: 0 = next solids record, 1 = next grid record.
 * grids_ptr  → gridCount variable-size records (see grid::parse_grids).
 * out_ptr    → 3 × f64 resolved movement.
 *
 * # Safety
 * All pointers must reference buffers of the stated sizes.
 */
int32_t oxi_collide2(const uint8_t *header_ptr,
                     const double *solids_ptr,
                     const uint8_t *grids_ptr,
                     const uint8_t *tags_ptr,
                     double *out_ptr);

/**
 * Batch cramming entry point.
 *
 * # Safety
 * * `inputs_ptr` must point to `n * size_of::<CrammingInput>()` bytes of
 *   readable memory, properly aligned (8-byte).
 * * `outputs_ptr` must point to `n * size_of::<CrammingOutput>()` bytes of
 *   writable memory, properly aligned (8-byte).
 * * Both regions must outlive the call.
 * * `n` must be non-negative and fit in usize.
 *
 * Java side guarantees these by allocating via `ByteBuffer.allocateDirect`
 * (8-byte aligned by spec) and keeping the buffer in scope across the call.
 */
void oxi_cramming_batch(const struct CrammingInput *inputs_ptr,
                        struct CrammingOutput *outputs_ptr,
                        int32_t n);

/**
 * Layout introspection for the Java side. Called once at mod init to
 * verify struct sizes match — if anyone ever changes the Rust struct
 * without updating the Java accessor, this will trip during the smoke
 * test before any real entity is ticked.
 * # Safety
 * No preconditions (no pointer arguments).
 */
int32_t oxi_cramming_input_size(void);

/**
 * # Safety
 * No preconditions (no pointer arguments).
 */
int32_t oxi_cramming_output_size(void);

double oxi_cramming_pad(void);

double oxi_sensor_pad(void);

/**
 * Retain a batch's input buffer for lazy sensor-range queries during the
 * region's entity tick. `handle` is the region id; the Java side guarantees
 * the buffer outlives begin..end.
 *
 * # Safety
 * `inputs_ptr` must be null or point to `n` input records that stay valid until `oxi_sensor_end`.
 */
void oxi_sensor_begin(uint64_t handle, const struct CrammingInput *inputs_ptr, int32_t n);

/**
 * # Safety
 * No pointer arguments; ends the handle's retained view of the input buffer.
 */
void oxi_sensor_end(uint64_t handle);

/**
 * Candidate slots within `range` (vanilla FOLLOW_RANGE) of `slot`, padded
 * superset semantics. Returns count, or -1 → caller runs the vanilla query.
 *
 * # Safety
 * Pointer arguments must be null or point to buffers of the sizes described above.
 */
int32_t oxi_sensor_query(uint64_t handle,
                         int32_t slot,
                         double range,
                         int32_t *out_ptr,
                         int32_t out_cap);

/**
 * Unbounded cramming candidate re-query for an OVERFLOW row: pushable
 * rows whose PAD-padded snapshot AABB intersects `slot`'s snapshot AABB,
 * written as i32 slots to `out_ptr`. Same superset contract as the
 * kernel's per-row candidate lists, no 127-slot cap. Returns the count,
 * or -1 (unknown handle / slot out of range / more than `out_cap`
 * candidates) — caller falls back to the vanilla section walk.
 *
 * # Safety
 * Pointer arguments must be null or point to buffers of the sizes described above.
 */
int32_t oxi_cramming_query(uint64_t handle, int32_t slot, int32_t *out_ptr, int32_t out_cap);

/**
 * Palette-packed variant — header layout (128 B):
 *   0..24   f64 cx, cy, cz
 *   24..28  f32 power
 *   28..32  i32 floats_len
 *   32..56  i32 ox, oy, oz, dim_x, dim_y, dim_z
 *   56..64  i32 min_y, max_y(exclusive)
 *   64..68  i32 skip_air (0/1)
 *   68..72  i32 out_cap
 *   72..84  i32 sec_ox, sec_oy, sec_oz   (section coords)
 *   84..96  i32 sec_nx, sec_ny, sec_nz
 *   96..100 i32 air_count                (written by the kernel)
 *
 * `sections_ptr` → sec_nx*sec_ny*sec_nz SectionDesc (24 B each, y-major),
 * `blob_ptr`/`blob_len` → palettes + raw storage longs + dense fills.
 *
 * Returns the non-air count (triples at out[0..n*3], discovery order).
 * With skip_air=1 the distinct air cells are written BACKWARDS from the
 * buffer tail (triple i at out[(out_cap-1-i)*3]) and counted in the header.
 * -1 = escape/bad args/cap — caller falls back to its exact Java port.
 *
 * # Safety
 * All pointers must reference buffers of the stated sizes.
 */
int32_t oxi_explosion_sweep_packed(uint8_t *header_ptr,
                                   const float *floats_ptr,
                                   const uint8_t *sections_ptr,
                                   const uint8_t *blob_ptr,
                                   int32_t *out_ptr);

/**
 * Build the network from the marshalled snapshot and retain it.
 *
 * # Safety
 * `nodes_ptr` must point at `n` 7-i32 records; `edges_ptr` at `edge_count`
 * i32 indices, all < n. Buffers are owned by Java and outlive begin..end.
 */
int32_t oxi_redstone_begin(uint64_t handle,
                           const int32_t *nodes_ptr,
                           int32_t n,
                           const int32_t *edges_ptr,
                           int32_t edge_count);

/**
 * Run one cascade entry (trigger = node index; mode 0=InDrain, 1=Depth0),
 * writing predicted events to `out_ptr`. Auto-saves power state for
 * `oxi_redstone_undo`. Returns the event count, or -1 (unknown handle /
 * bad index / `out_cap` exceeded).
 *
 * # Safety
 * `out_ptr` must hold `out_cap` 5-i32 records.
 */
int32_t oxi_redstone_entry(uint64_t handle,
                           int32_t trigger_idx,
                           int32_t mode,
                           int32_t *out_ptr,
                           int32_t out_cap);

/**
 * Roll the power state back to before the last `oxi_redstone_entry`
 * (adaptive discipline retry). Returns 0, or -1 when there is nothing to
 * undo / the handle is unknown.
 */
int32_t oxi_redstone_undo(uint64_t handle);

/**
 * Drop the retention.
 */
void oxi_redstone_end(uint64_t handle);

/**
 * Allocate a zeroed native u64 array of `words` elements, 64-aligned.
 * Returns the address, or 0 on bad args / OOM.
 *
 * # Safety
 * No pointer arguments; the returned block must be released with `oxi_section_free`.
 */
int64_t oxi_section_alloc(int32_t words);

/**
 * Release a block from oxi_section_alloc. `words` MUST match the
 * allocation size (Java side stores it on the NativeBitStorage).
 *
 * # Safety
 * `addr` must come from oxi_section_alloc with the same `words`, and
 * must not be used after this call (the Java Cleaner guarantees both).
 */
void oxi_section_free(int64_t addr, int32_t words);

/**
 * Live native bytes (index 0) and live block count (index 1).
 *
 * # Safety
 * `out` must reference at least 2 i64 slots.
 */
void oxi_section_stats(int64_t *out);

/**
 * xs/ys/zs: n doubles each; out_perm: n i32 slots.
 * Returns 0 on success, -1 on bad args.
 *
 * # Safety
 * Pointers must reference buffers of at least n elements.
 */
int32_t oxi_distance_sort(const double *xs,
                          const double *ys,
                          const double *zs,
                          double tx,
                          double ty,
                          double tz,
                          int32_t n,
                          int32_t *out_perm);

/**
 * Build the per-tick arena. Single-threaded (tick head). @return handle, or 0 on bad args.
 *
 * # Safety
 * `entities`/`players` must reference `n_ent`/`n_play` records of the strides above;
 * `caps` must point to 8 i32.
 */
int64_t oxi_spawncap_build(const uint8_t *entities,
                           int32_t n_ent,
                           const uint8_t *players,
                           int32_t n_play,
                           int32_t spawnable,
                           const int32_t *caps);

/**
 * vanilla canSpawnForCategoryGlobal → 1 if a spawn is allowed, 0 if capped, -1 on bad args.
 *
 * # Safety
 * `handle` must be a live handle from `oxi_spawncap_build`.
 */
int32_t oxi_spawncap_can_global(int64_t handle, int32_t cat);

/**
 * vanilla LocalMobCapCalculator.canSpawn → 1 allowed, 0 capped/no-near, -1 on bad args.
 *
 * # Safety
 * `handle` must be a live handle from `oxi_spawncap_build`.
 */
int32_t oxi_spawncap_can_local(int64_t handle, int32_t cat, int32_t cx, int32_t cz);

/**
 * Fabrolia fused local-cap check + pack reservation. Returns 1 when reserved,
 * 0 when every near player is capped (all provisional increments rolled back),
 * or -1 on bad args.
 *
 * # Safety
 * `handle` must be a live handle from `oxi_spawncap_build`; safe to call concurrently.
 */
int32_t oxi_spawncap_try_reserve_pack(int64_t handle,
                                      int32_t cat,
                                      int32_t cx,
                                      int32_t cz,
                                      int32_t count);

/**
 * Return an unconsumed Fabrolia pack reservation. Returns 0, or -1 on bad args.
 *
 * # Safety
 * `handle` must be a live handle from `oxi_spawncap_build`; safe to call concurrently.
 */
int32_t oxi_spawncap_release_pack(int64_t handle,
                                  int32_t cat,
                                  int32_t cx,
                                  int32_t cz,
                                  int32_t count);

/**
 * vanilla afterSpawn (global +1, addMob to near players). @return 0, or -1 on bad args.
 *
 * # Safety
 * `handle` must be a live handle from `oxi_spawncap_build`; safe to call concurrently.
 */
int32_t oxi_spawncap_add(int64_t handle, int32_t cat, int32_t cx, int32_t cz, int32_t is_mob);

/**
 * vanilla PotentialCalculator.getPotentialEnergyChange(pos, charge).
 *
 * # Safety
 * `handle` must be a live handle from `oxi_spawncap_build`.
 */
double oxi_spawncap_potential(int64_t handle, int32_t x, int32_t y, int32_t z, double charge);

/**
 * Reclaim the arena (tick end). No-op on 0. @return 0 (i32 to avoid a void-return binding).
 *
 * # Safety
 * `handle` must be a handle from `oxi_spawncap_build` not yet freed; no concurrent use.
 */
int32_t oxi_spawncap_free(int64_t handle);
