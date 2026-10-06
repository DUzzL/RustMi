//! Lazy point queries over a retained cramming batch: sensor-range scans
//! (S1, NearestLivingEntitySensor) and unbounded cramming candidate
//! re-queries for OVERFLOW rows (dense piles > MAX_CANDIDATES neighbours).
//!
//! After a batch's kernel runs, Java registers the input buffer under a
//! handle (`oxi_sensor_begin`). While entities tick, queries resolve against
//! that snapshot; `oxi_sensor_end` drops the retention before the buffer is
//! cleared/reused. Both the regional batches and the global batch retain.
//!
//! Concurrency: queries arrive from the coordinator AND dense fan-out
//! helpers concurrently (multiple regions, multiple threads). The registry
//! is behind an RwLock — begin/end take the write lock, queries share the
//! read lock; the coarse grid builds lazily inside a per-handle `OnceLock`
//! on the first query (batches where no query fires this tick never build
//! it; concurrent first queries race safely inside OnceLock).
//!
//! One grid serves both query kinds: it indexes rows with FLAG_IS_LIVING or
//! FLAG_IS_PUSHABLE; each query filters by its own mask at visit time —
//!   * sensor   → FLAG_IS_LIVING (vanilla: getEntitiesOfClass(LivingEntity)
//!     sees spectators/armor stands, NOT boats),
//!   * cramming → FLAG_IS_PUSHABLE (conservative candidate superset; Java's
//!     live pushableBy predicate rejects spectators and other extras).
//!
//! SUPERSET contract (both queries): the padded snapshot box test must catch
//! every entity the live vanilla test can accept — Java re-filters against
//! live state, so extras cost a failed predicate, misses would break
//! vanilla. Sensor pad 2.0 covers one tick of drift for both parties plus
//! pose-driven AABB growth; cramming reuses the kernel's PAD (0.5), the
//! exact contract the per-row candidate lists already ship with. Ordinary
//! rows live in the coarse grid; oversized rows live in a linear side list,
//! so one legal giant cannot inflate every ordinary query's hash stencil.
//! Teleport-class moves can slip one cycle — the same accepted staleness
//! class as the kernel's candidate lists.

use std::collections::HashMap;
use std::sync::OnceLock;

use parking_lot::RwLock;

use super::{CrammingInput, FLAG_IS_LIVING, FLAG_IS_PUSHABLE, PAD};

const COARSE_CELL: f64 = 16.0;
const GRID_MAX_HALF: f64 = COARSE_CELL * 0.5;
pub(super) const SENSOR_PAD: f64 = 2.0;

type Grid = HashMap<(i32, i32, i32, i32), Vec<u32>, super::FxBuild>;

struct GridIndex {
    grid: Grid,
    big: Vec<u32>,
    max_grid_half: f64,
}

struct Retained {
    inputs_ptr: usize,
    n: usize,
    grid: OnceLock<GridIndex>,
}

// Safety: inputs_ptr points into a Java DirectByteBuffer that the Java side
// guarantees alive and unmodified between begin/end (the batch owns it for
// exactly the tick; clear/grow happen only after end).
unsafe impl Send for Retained {}
unsafe impl Sync for Retained {}

static REGISTRY: RwLock<Option<HashMap<u64, Retained>>> = RwLock::new(None);

#[inline]
fn coarse_cell(v: f64) -> i32 {
    (v / COARSE_CELL).floor() as i32
}

#[inline]
fn max_half(e: &CrammingInput) -> f64 {
    e.half[0].max(e.half[1]).max(e.half[2])
}

pub fn sensor_begin(handle: u64, inputs_ptr: usize, n: usize) {
    let mut reg = REGISTRY.write();
    reg.get_or_insert_with(HashMap::new).insert(
        handle,
        Retained {
            inputs_ptr,
            n,
            grid: OnceLock::new(),
        },
    );
}

pub fn sensor_end(handle: u64) {
    let mut reg = REGISTRY.write();
    if let Some(map) = reg.as_mut() {
        map.remove(&handle);
    }
}

/// Living candidates within `range` of `slot` (padded superset) — serves
/// the nearest-living sensor query.
pub fn sensor_query(handle: u64, slot: usize, range: f64, out: &mut [i32]) -> i32 {
    batch_query(handle, slot, range, SENSOR_PAD, FLAG_IS_LIVING, out)
}

/// Flagged candidates intersecting `slot`'s padded snapshot box — the
/// kernel's conservative per-row candidate contract WITHOUT the 127-slot
/// output cap. Serves OVERFLOW rows instead of the vanilla section walk.
pub fn cramming_query(handle: u64, slot: usize, out: &mut [i32]) -> i32 {
    batch_query(handle, slot, 0.0, PAD, FLAG_IS_PUSHABLE, out)
}

/// Shared engine: slots of rows with `mask` flags whose snapshot AABB,
/// padded by `range + pad`, intersects `slot`'s snapshot AABB. Returns the
/// count, or -1 when the handle is unknown / slot out of range / `out`
/// capacity exceeded (caller falls back to the vanilla query).
/// Lazy coarse grid + oversized side list, built at most once per handle
/// (OnceLock resolves concurrent first-query races).
fn grid_of<'a>(r: &'a Retained, inputs: &[CrammingInput]) -> &'a GridIndex {
    r.grid.get_or_init(|| {
        let mut grid: Grid = HashMap::with_capacity_and_hasher(inputs.len() / 4 + 8, Default::default());
        let mut big: Vec<u32> = Vec::new();
        let mut max_grid_half: f64 = 0.0;
        for (i, e) in inputs.iter().enumerate() {
            if (e.flags & (FLAG_IS_LIVING | FLAG_IS_PUSHABLE)) == 0 {
                continue;
            }
            let half = max_half(e);
            if half > GRID_MAX_HALF {
                big.push(i as u32);
                continue;
            }
            max_grid_half = max_grid_half.max(half);
            grid.entry((
                e.dim_id,
                coarse_cell(e.pos[0]),
                coarse_cell(e.pos[1]),
                coarse_cell(e.pos[2]),
            ))
            .or_default()
            .push(i as u32);
        }
        GridIndex {
            grid,
            big,
            max_grid_half,
        }
    })
}

fn batch_query(handle: u64, slot: usize, range: f64, pad: f64, mask: i32, out: &mut [i32]) -> i32 {
    let reg = REGISTRY.read();
    let Some(map) = reg.as_ref() else { return -1 };
    let Some(r) = map.get(&handle) else { return -1 };
    if slot >= r.n {
        return -1;
    }
    let inputs = unsafe { std::slice::from_raw_parts(r.inputs_ptr as *const CrammingInput, r.n) };
    let index = grid_of(r, inputs);

    let me = &inputs[slot];
    let me_half = max_half(me);
    let mut count: usize = 0;
    let mut overflow = false;
    {
        let mut visit = |j: u32| {
            if overflow {
                return;
            }
            let ju = j as usize;
            if ju == slot {
                return;
            }
            let other = &inputs[ju];
            if (other.flags & mask) == 0 || other.dim_id != me.dim_id {
                return;
            }
            if (me.pos[0] - other.pos[0]).abs() >= me.half[0] + other.half[0] + range + pad
                || (me.pos[1] - other.pos[1]).abs() >= me.half[1] + other.half[1] + range + pad
                || (me.pos[2] - other.pos[2]).abs() >= me.half[2] + other.half[2] + range + pad
            {
                return;
            }
            if count == out.len() {
                overflow = true;
                return;
            }
            out[count] = j as i32;
            count += 1;
        };

        if me_half > GRID_MAX_HALF {
            // A huge origin spans many coarse cells; a linear row scan avoids
            // hundreds of thousands of empty HashMap probes.
            for j in 0..inputs.len() as u32 {
                visit(j);
            }
        } else {
            // Grid rows are bounded by GRID_MAX_HALF. Oversized candidates
            // are not part of this reach calculation and are visited once
            // through the side list below.
            let reach = range + me_half + index.max_grid_half + pad;
            let cells = (reach / COARSE_CELL).ceil() as i32;
            let cx = coarse_cell(me.pos[0]);
            let cy = coarse_cell(me.pos[1]);
            let cz = coarse_cell(me.pos[2]);
            for dx in -cells..=cells {
                for dy in -cells..=cells {
                    for dz in -cells..=cells {
                        let Some(list) = index.grid.get(&(me.dim_id, cx + dx, cy + dy, cz + dz))
                        else {
                            continue;
                        };
                        for &j in list {
                            visit(j);
                        }
                    }
                }
            }
            for &j in &index.big {
                visit(j);
            }
        }
    }
    if overflow {
        -1
    } else {
        out[..count].sort_unstable();   // ascending slots = collection (vanilla) order
        count as i32
    }
}

#[cfg(test)]
mod tests {
    use super::super::FLAG_IS_ORIGIN;
    use super::*;

    const LIVING: i32 = FLAG_IS_LIVING | FLAG_IS_PUSHABLE | FLAG_IS_ORIGIN;

    fn input(x: f64, y: f64, z: f64, flags: i32) -> CrammingInput {
        CrammingInput {
            pos: [x, y, z],
            half: [0.3, 0.95, 0.3],
            dim_id: 0,
            flags,
            _pad: [0; 2],
        }
    }

    /// Tests run in parallel and share the static registry — every test MUST
    /// use its own handle (a shared handle was a real flaky-failure source).
    fn with_batch<R>(handle: u64, inputs: &[CrammingInput], f: impl FnOnce() -> R) -> R {
        sensor_begin(handle, inputs.as_ptr() as usize, inputs.len());
        let r = f();
        sensor_end(handle);
        r
    }

    #[test]
    fn finds_within_range_excludes_self_and_far() {
        let inputs = vec![
            input(0.0, 64.0, 0.0, LIVING),
            input(10.0, 64.0, 0.0, LIVING), // inside 16
            input(30.0, 64.0, 0.0, LIVING), // outside 16 (+pad)
        ];
        with_batch(101, &inputs, || {
            let mut out = [0i32; 8];
            let n = sensor_query(101, 0, 16.0, &mut out);
            assert_eq!(n, 1);
            assert_eq!(out[0], 1);
        });
    }

    #[test]
    fn zombie_follow_range_35_reaches_far() {
        let inputs = vec![
            input(0.0, 64.0, 0.0, LIVING),
            input(30.0, 64.0, 0.0, LIVING),
        ];
        with_batch(102, &inputs, || {
            let mut out = [0i32; 8];
            let n = sensor_query(102, 0, 35.0, &mut out);
            assert_eq!(n, 1, "followRange 35 must see 30 blocks out");
        });
    }

    #[test]
    fn sensor_excludes_boat_includes_spectator() {
        let inputs = vec![
            input(0.0, 64.0, 0.0, LIVING),
            input(2.0, 64.0, 0.0, FLAG_IS_PUSHABLE), // boat: pushable, not living
            input(4.0, 64.0, 0.0, FLAG_IS_LIVING),   // spectator: living only
        ];
        with_batch(103, &inputs, || {
            let mut out = [0i32; 8];
            let n = sensor_query(103, 0, 16.0, &mut out);
            assert_eq!(n, 1);
            assert_eq!(
                out[0], 2,
                "spectator (living-only) is a candidate; boat is not"
            );
        });
    }

    #[test]
    fn capacity_overflow_returns_fallback() {
        let mut inputs = vec![input(0.0, 64.0, 0.0, LIVING)];
        for k in 0..10 {
            inputs.push(input(1.0 + k as f64 * 0.1, 64.0, 0.0, LIVING));
        }
        with_batch(104, &inputs, || {
            let mut out = [0i32; 4];
            assert_eq!(sensor_query(104, 0, 16.0, &mut out), -1);
        });
    }

    #[test]
    fn unknown_handle_is_fallback() {
        let mut out = [0i32; 4];
        assert_eq!(sensor_query(999, 0, 16.0, &mut out), -1);
    }

    #[test]
    fn concurrent_handles_are_independent() {
        // Two batches alive at once (two regions) — queries must not bleed.
        let a = vec![input(0.0, 64.0, 0.0, LIVING), input(5.0, 64.0, 0.0, LIVING)];
        let b = vec![input(1000.0, 64.0, 0.0, LIVING)];
        with_batch(105, &a, || {
            with_batch(106, &b, || {
                let mut out = [0i32; 8];
                assert_eq!(sensor_query(105, 0, 16.0, &mut out), 1);
                assert_eq!(sensor_query(106, 0, 16.0, &mut out), 0);
            });
        });
    }

    // ── cramming_query (OVERFLOW re-query) ─────────────────────────────

    #[test]
    fn cramming_mask_excludes_living_only_row() {
        // Defensive mask distinction. Real Java collection also gives an
        // alive spectator the conservative candidate bit; the mandatory live
        // pushableBy filter, outside this native query, rejects it.
        let inputs = vec![
            input(0.0, 64.0, 0.0, LIVING),
            input(0.5, 64.0, 0.0, FLAG_IS_PUSHABLE), // boat
            input(0.7, 64.0, 0.0, FLAG_IS_LIVING),   // defensive living-only row
        ];
        with_batch(107, &inputs, || {
            let mut out = [0i32; 8];
            let n = cramming_query(107, 0, &mut out);
            assert_eq!(n, 1);
            assert_eq!(out[0], 1);
        });
    }

    #[test]
    fn cramming_pad_matches_kernel_contract() {
        // halves 0.3+0.3 + PAD 0.5 = 1.1: pair at 1.0 is a candidate,
        // pair at 1.2 is not — identical to the kernel's padded test.
        let near = vec![input(0.0, 64.0, 0.0, LIVING), input(1.0, 64.0, 0.0, LIVING)];
        with_batch(108, &near, || {
            let mut out = [0i32; 8];
            assert_eq!(cramming_query(108, 0, &mut out), 1);
        });
        let far = vec![input(0.0, 64.0, 0.0, LIVING), input(1.2, 64.0, 0.0, LIVING)];
        with_batch(109, &far, || {
            let mut out = [0i32; 8];
            assert_eq!(cramming_query(109, 0, &mut out), 0);
        });
    }

    #[test]
    fn cramming_dense_pile_unbounded() {
        // 200 stacked mobs — far beyond the kernel's 127-slot records; the
        // re-query must return all 199.
        let mut inputs = Vec::new();
        for k in 0..200 {
            inputs.push(input(0.002 * k as f64, 64.0, 0.0, LIVING));
        }
        with_batch(110, &inputs, || {
            let mut out = [0i32; 4096];
            assert_eq!(cramming_query(110, 0, &mut out), 199);
        });
    }

    #[test]
    fn cramming_scratch_overflow_returns_fallback() {
        let mut inputs = Vec::new();
        for k in 0..10 {
            inputs.push(input(0.01 * k as f64, 64.0, 0.0, LIVING));
        }
        with_batch(112, &inputs, || {
            let mut out = [0i32; 4];
            assert_eq!(cramming_query(112, 0, &mut out), -1);
        });
    }

    #[test]
    fn huge_candidate_uses_side_list() {
        // half=20 candidate 18 blocks away passes the box test. Keeping it
        // outside the ordinary grid prevents it from inflating every other
        // query's stencil while the side list still finds it exactly.
        let mut huge = input(33.9, 64.0, 0.0, LIVING);
        huge.half = [20.0, 20.0, 20.0];
        let inputs = vec![input(15.9, 64.0, 0.0, LIVING), huge];
        with_batch(113, &inputs, || {
            let mut out = [0i32; 8];
            assert_eq!(cramming_query(113, 0, &mut out), 1);
            assert_eq!(out[0], 1);
        });
    }

    #[test]
    fn huge_origin_scans_all_rows() {
        let mut huge = input(0.0, 64.0, 0.0, LIVING);
        huge.half = [20.0, 20.0, 20.0];
        let inputs = vec![huge, input(18.0, 64.0, 0.0, LIVING)];
        with_batch(114, &inputs, || {
            let mut out = [0i32; 8];
            assert_eq!(cramming_query(114, 0, &mut out), 1);
            assert_eq!(out[0], 1);
        });
    }
}
