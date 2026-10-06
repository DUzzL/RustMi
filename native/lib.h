#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>

#define SECTION_STRIDE 64

#define MODE_SINGLE 0

#define MODE_LINEAR 1

#define MODE_HASHMAP 2

#define MODE_GLOBAL 3

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

#define MAX_SUCCESSORS 32

#define T_BLOCKED 0

#define T_OPEN 1

#define T_WALKABLE 2

#define T_WALKABLE_DOOR 3

#define T_TRAPDOOR 4

#define T_POWDER_SNOW 5

#define T_FENCE 7

#define T_WATER 9

#define T_RAIL 11

#define T_UNPASSABLE_RAIL 12

#define T_DOOR_OPEN 17

#define T_DOOR_WOOD_CLOSED 18

#define T_DOOR_IRON_CLOSED 19

#define T_STICKY_HONEY 22

#define T_BIG_MOBS_CLOSE_TO_DANGER 26

#define TYPE_COUNT 27

/**
 * Vanilla default for ChainRestrictedNeighborUpdater's depth budget.
 */
#define VANILLA_MAX_CHAIN 1000000

#define TickScheduler_NUM_QUEUES 16

#define TickPriority_COUNT 4

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
 * Layout introspection for the Java side (runtime cross-check).
 */
int32_t oxi_chunk_section_record_size(void);

/**
 * Encode `n_sections` sections into `out_ptr`.
 *
 * Returns total bytes written (`>= 0`), or `-1` on any validation failure /
 * unsupported record / `out_cap` too small. Never writes a partial section.
 *
 * # Safety
 * `header_ptr` → 4 readable bytes `[i32 n_sections]`; `sections_ptr` →
 * `n_sections * 64` readable bytes (layout above); `palette_ids_ptr` → every
 * `(pal_off + pal_count)` referenced `i32`; `out_ptr` → `out_cap` writable
 * bytes. Java guarantees these by packing from validated descriptors.
 */
int32_t oxi_chunk_section_encode(const uint8_t *header_ptr,
                                 const uint8_t *sections_ptr,
                                 const int32_t *palette_ids_ptr,
                                 uint8_t *out_ptr,
                                 int32_t out_cap);

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
 */
int32_t oxi_cramming_input_size(void);

int32_t oxi_cramming_output_size(void);

double oxi_cramming_pad(void);

double oxi_sensor_pad(void);

/**
 * Retain a batch's input buffer for lazy sensor-range queries during the
 * region's entity tick. `handle` is the region id; the Java side guarantees
 * the buffer outlives begin..end.
 */
void oxi_sensor_begin(uint64_t handle, const struct CrammingInput *inputs_ptr, int32_t n);

void oxi_sensor_end(uint64_t handle);

/**
 * Candidate slots within `range` (vanilla FOLLOW_RANGE) of `slot`, padded
 * superset semantics. Returns count, or -1 → caller runs the vanilla query.
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
 */
int32_t oxi_cramming_query(uint64_t handle, int32_t slot, int32_t *out_ptr, int32_t out_cap);

/**
 * Returns the destroyed-position count (triples written to `out_ptr` in
 * discovery order), or -1 (escape / bad args / cap exceeded) — the caller
 * must then run its exact Java fallback with the SAME floats.
 *
 * # Safety
 * All pointers must reference buffers of the stated sizes.
 */
int32_t oxi_explosion_sweep(const uint8_t *header_ptr,
                            const float *floats_ptr,
                            const float *res_ptr,
                            int32_t *out_ptr,
                            int32_t out_cap);

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
 * Open a new compile session under `handle`. Caller-assigned u64 — pick
 * any unique value (Java side allocates from an `AtomicLong`, matching
 * the redstone-engine convention).
 */
int32_t oxi_redpiler_create(uint64_t handle);

int32_t oxi_redpiler_destroy(uint64_t handle);

int32_t oxi_redpiler_add_node(uint64_t handle, uint64_t packed);

/**
 * Attach a `(pos, block_id)` pair to a previously-added node. `block_id`
 * is host-opaque (Java resolves it to a `BlockState` on flush).
 */
int32_t oxi_redpiler_node_add_block(uint64_t handle,
                                    int32_t node_idx,
                                    int32_t x,
                                    int32_t y,
                                    int32_t z,
                                    uint32_t block_id);

/**
 * Add an edge `from → to` with weight `(side, ss)`. `side=1` = comparator
 * side-input lane, `0` = default.
 */
int32_t oxi_redpiler_add_edge(uint64_t handle, int32_t from, int32_t to, uint8_t side, uint8_t ss);

/**
 * Lower the assembled graph into the runtime backend. After this point
 * `add_*` calls return [`ERR_ALREADY_COMPILED`].
 */
int32_t oxi_redpiler_compile(uint64_t handle);

/**
 * Warm-up pass — bake the captured walk-time state into each gate's
 * pending-tick/output decisions. After [`oxi_redpiler_compile`] populates
 * the input histograms from each source's `output_strength`, the gates
 * (Repeater / Torch / Comparator / Lamp / Trapdoor / NoteBlock) still hold
 * the WALK-TIME `powered`/`output_power` snapshot; nothing has called
 * `update_node` on them yet to compare their histogram input against that
 * snapshot. The mismatch is real (e.g. a wire feeding a newly-walked
 * repeater whose POWERED=false in the world but whose input is actually 15)
 * — without warm-up the gate never wakes until some EXTERNAL set_input
 * event happens to ripple to it, which for a static circuit may be never.
 *
 * **MUST be called AFTER** any [`oxi_redpiler_schedule_tick`] replays.
 * `update_node` returns early for nodes whose `pending_tick` is set, so a
 * replayed tick correctly suppresses warm-up from double-scheduling. The
 * reverse order (warm-up first, replay second) was the garland bug
 * (2026-06-15): warm-up schedules a snapshot-driven tick on a gate that's
 * also about to receive a replayed pre-bail tick → two ticks fire, the
 * second flips the gate back, producing flicker that wasn't there in
 * vanilla. Fresh claims (no replay) call this immediately after compile
 * with the same effect they had before — no behavior change for that path.
 */
int32_t oxi_redpiler_warmup(uint64_t handle);

int32_t oxi_redpiler_schedule_tick(uint64_t handle,
                                   int32_t node_idx,
                                   int32_t delay,
                                   uint8_t priority);

/**
 * Drive an input node (Button / Lever / PressurePlate / Comparator
 * container-strength source). Mirrors the upstream `on_use_block` +
 * `set_pressure_plate` paths into one call.
 */
int32_t oxi_redpiler_set_input(uint64_t handle, int32_t node_idx, uint8_t powered, uint8_t power);

/**
 * Advance one gameTick — drains the current tick-slot's queue and runs
 * any cascading updates synchronously.
 */
int32_t oxi_redpiler_tick(uint64_t handle);

/**
 * Copy every node whose `changed` flag is set into `out_ptr` and clear
 * the flags. Wire format per node (5 × i32):
 *   `[idx, powered, locked, output_power, _reserved]`.
 *
 * Returns the count of records written, or [`ERR_OUT_CAP`] if `out_cap`
 * is smaller than the changed-set size (host should grow its buffer and
 * retry — no state mutated when [`ERR_OUT_CAP`]).
 */
int32_t oxi_redpiler_drain_changes(uint64_t handle, int32_t *out_ptr, int32_t out_cap);

/**
 * Drain every still-pending scheduled tick (slot + priority). Used by the
 * host on bail to re-instate ticks into Minecraft's `LevelChunkTicks` so
 * the world keeps ticking from exactly where the backend left off. Wire
 * format per record (4 × i32): `[node_idx, delay_remaining, priority, _reserved]`.
 *
 * After a successful drain the scheduler is left empty (slot pointer reset).
 * Returns the count of records written, or [`ERR_OUT_CAP`] if too small
 * (caller must grow and retry — nothing mutated on overflow).
 */
int32_t oxi_redpiler_drain_pending_ticks(uint64_t handle, int32_t *out_ptr, int32_t out_cap);

/**
 * Drain queued [`Event`]s. Wire format per event (1 × i32):
 *   * NoteBlockPlay: `[noteblock_id]`.
 * Returns the count of records written, or [`ERR_OUT_CAP`] if too small.
 */
int32_t oxi_redpiler_drain_events(uint64_t handle, int32_t *out_ptr, int32_t out_cap);

/**
 * Pre-populate a torch's history to mark it as currently burnt-out,
 * with the burn time offset {@code age_ticks} game ticks into the
 * past. The vanilla burnout window is 60 GT; passing
 * {@code age_ticks < 60} marks the torch burnt for another
 * {@code 60 - age_ticks} ticks, then it recovers naturally as the
 * history slides out of the window.
 *
 * <p>Used by the host on sync-rewalk: a torch that was burnt in the
 * previous backend loses its history when the backend is rebuilt
 * from a fresh walk. Without this restore, the torch would
 * re-toggle 8 times → re-burn → repeat sound, every time the user
 * edits an unrelated wire (user-reported 2026-06-15:
 * "ФАКЕЛ ПОСТОЯННО ОБНОВЛЯЕТЬСЯ КОГДА Я ГДЕ ТО СТАВЛЮ ПЫЛИНКУ").
 */
int32_t oxi_redpiler_mark_torch_burnt(uint64_t handle,
                                      int32_t node_idx,
                                      int32_t age_ticks);

/**
 * Drain the queued torch-burnout list — every torch that JUST entered
 * the burnt state since the last drain. Wire format per record
 * (1 × i32): `[node_idx]`. Host plays vanilla level event 1502
 * (smoke particle + redstone-torch-burnout sound) at each torch's
 * world position.
 */
int32_t oxi_redpiler_drain_burnouts(uint64_t handle, int32_t *out_ptr, int32_t out_cap);

/**
 * Look up a node index by world position. Returns the index or [`ERR_OUT_OF_RANGE`].
 */
int32_t oxi_redpiler_lookup_pos(uint64_t handle, int32_t x, int32_t y, int32_t z);

/**
 * Dump the `(pos, block_id)` flush table for a given node. The host calls
 * this on flush to map runtime nodes back to world block writes. Wire
 * format per block (4 × i32): `[x, y, z, block_id]`.
 *
 * Returns the count of records written, or [`ERR_OUT_CAP`] if too small.
 */
int32_t oxi_redpiler_node_blocks(uint64_t handle,
                                 int32_t node_idx,
                                 int32_t *out_ptr,
                                 int32_t out_cap);

/**
 * Allocate a zeroed native u64 array of `words` elements, 64-aligned.
 * Returns the address, or 0 on bad args / OOM.
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
