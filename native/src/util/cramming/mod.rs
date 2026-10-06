// Mob cramming candidate-search kernel.
pub mod ffi;
pub mod sensor;
//
// DESIGN (v3, "Rust finds WHO, vanilla decides WHAT"):
//
// Vanilla LivingEntity::pushEntities() costs are dominated by the spatial
// query — getEntities(this, box, pushableBy(this)) walks entity sections and
// filters, O(N) per entity and O(N·section_pop) across a crowded server.
// The per-pair push math afterwards is ~20 flops and CHEAP.
//
// The previous kernel precomputed push FORCES from a start-of-tick snapshot.
// That broke the feedback loop: vanilla detects "we are touching" inside the
// entity's own tick (pushEntities runs right after travel), so reaction is
// sub-tick; a snapshot delays detection by a full tick. Dense freshly-spawned
// piles then behave wrongly — the pile sits (dead zone), ejects single mobs
// in quantized pops, then bursts all at once when the lagged forces land
// together.
//
// v3 therefore returns only CANDIDATE LISTS: for every origin (an entity that
// will run a batch-cancelled pushEntities this tick) the kernel emits the
// slots of all entities whose PADDED AABBs intersect it in the snapshot.
// The Java side re-checks live AABB intersection + the real vanilla
// EntitySelector.pushableBy predicate at the entity's own tick, counts the
// survivors for cramming damage, and calls the actual vanilla doPush() —
// live positions, virtual dispatch (sleep override), real team rules, real
// side effects. Bit-exact vanilla dynamics; the kernel only needs to be a
// SUPERSET of the true neighbour set, guaranteed by the padding.
//
// PAD covers one tick of relative drift between snapshot and apply
// (movement is capped well below 0.5 blocks/tick for colliding mobs; faster
// entities self-correct next tick).

/// One entity's input record. 64 bytes.
#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct CrammingInput {
    /// World position (cx,cy,cz centre of entity's AABB).
    pub pos: [f64; 3], //  0..24
    /// AABB half-extents from pos.
    pub half: [f64; 3], // 24..48
    /// Dimension key — entities with different dim_id never interact.
    pub dim_id: i32, // 48..52
    /// Bit flags (see FLAG_*).
    pub flags: i32, // 52..56
    pub _pad: [i32; 2], // 56..64
}

/// One origin's output record: candidate count + slot indices.
/// count == OVERFLOW (-1) means "more than MAX_CANDIDATES neighbours —
/// use the uncapped retained-snapshot query".
pub const MAX_CANDIDATES: usize = 127;
pub const OVERFLOW: i32 = -1;

#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct CrammingOutput {
    pub count: i32,                   //  0..4
    pub slots: [i32; MAX_CANDIDATES], //  4..512
}

impl Default for CrammingOutput {
    fn default() -> Self {
        CrammingOutput {
            count: 0,
            slots: [0; MAX_CANDIDATES],
        }
    }
}

// Compile-time layout guarantees. Java offsets depend on these.
const _: () = {
    assert!(std::mem::size_of::<CrammingInput>() == 64);
    assert!(std::mem::align_of::<CrammingInput>() == 8);
    assert!(std::mem::size_of::<CrammingOutput>() == 512);
    assert!(std::mem::align_of::<CrammingOutput>() == 4);
};

pub const FLAG_IS_PUSHABLE: i32 = 1 << 0; // Conservative candidate prefilter:
                                          // exact for non-living rows, set for every
                                          // alive LivingEntity. Java rechecks live.
pub const FLAG_IS_ORIGIN: i32 = 1 << 5; // runs a batch-cancelled pushEntities
pub const FLAG_IS_LIVING: i32 = 1 << 6; // LivingEntity && isAlive() — sensor-visible

/// Padding added to the snapshot intersection test so the candidate set
/// stays a superset of the live set despite one tick of drift.
pub(super) const PAD: f64 = 0.5;

/// Spatial grid cell size. For a point-inserted grid with a ±1-cell stencil
/// the guarantee is: any pair with per-axis centre distance d < CELL_SIZE
/// lands in cells differing by at most 1 (|⌊u⌋−⌊v⌋| ≤ ⌊|u−v|⌋ + 1). The
/// maximum candidate reach for in-grid entities is
/// 2·OVERSIZE_HALF + PAD = 2.5 ≤ CELL_SIZE, so nothing is missed.
const CELL_SIZE: f64 = 2.5;
const OVERSIZE_HALF: f64 = 1.0;

#[inline]
fn cell(pos: f64) -> i32 {
    (pos / CELL_SIZE).floor() as i32
}

#[inline]
fn has(e: &CrammingInput, flag: i32) -> bool {
    (e.flags & flag) != 0
}

#[inline]
fn oversized(e: &CrammingInput) -> bool {
    e.half[0] > OVERSIZE_HALF || e.half[1] > OVERSIZE_HALF || e.half[2] > OVERSIZE_HALF
}

/// Padded snapshot intersection — must be a superset of vanilla's strict
/// AABB.intersects at apply time.
#[inline]
fn padded_intersects(a: &CrammingInput, b: &CrammingInput) -> bool {
    (a.pos[0] - b.pos[0]).abs() < a.half[0] + b.half[0] + PAD
        && (a.pos[1] - b.pos[1]).abs() < a.half[1] + b.half[1] + PAD
        && (a.pos[2] - b.pos[2]).abs() < a.half[2] + b.half[2] + PAD
}

use std::collections::HashMap;
use std::hash::{BuildHasherDefault, Hasher};

/// FxHash (rustc's hasher): one rotate/xor/multiply per word. The cell maps only key on a
/// few small integers, so SipHash's DoS resistance buys nothing here.
#[derive(Default, Clone, Copy)]
pub(super) struct FxHasher {
    hash: u64,
}

impl FxHasher {
    #[inline]
    fn add(&mut self, word: u64) {
        self.hash = (self.hash.rotate_left(5) ^ word).wrapping_mul(0x51_7c_c1_b7_27_22_0a_95);
    }
}

impl Hasher for FxHasher {
    #[inline]
    fn write(&mut self, bytes: &[u8]) {
        for &b in bytes {
            self.add(b as u64);
        }
    }

    #[inline]
    fn write_i32(&mut self, i: i32) {
        self.add(i as u32 as u64);
    }

    #[inline]
    fn write_u64(&mut self, i: u64) {
        self.add(i);
    }

    #[inline]
    fn finish(&self) -> u64 {
        self.hash
    }
}

pub(super) type FxBuild = BuildHasherDefault<FxHasher>;

type CellKey = (i32, i32, i32, i32);

#[inline]
fn cell_key(e: &CrammingInput) -> CellKey {
    (e.dim_id, cell(e.pos[0]), cell(e.pos[1]), cell(e.pos[2]))
}

/// One origin's candidate collection: the rule every traversal shares. The candidate SET
/// (and overflow, which only depends on its size) is independent of visit order, and the
/// emitted slots are sorted, so any traversal visiting the same rows gives the same output.
struct Collector<'a> {
    inputs: &'a [CrammingInput],
    origin: usize,
    count: usize,
    overflow: bool,
}

impl Collector<'_> {
    #[inline]
    fn visit(&mut self, out: &mut CrammingOutput, j: u32) {
        if self.overflow {
            return;
        }
        let ju = j as usize;
        if ju == self.origin {
            return;
        }
        let e = &self.inputs[self.origin];
        let o = &self.inputs[ju];
        if o.dim_id != e.dim_id || !padded_intersects(e, o) {
            return;
        }
        self.accept(out, j);
    }

    /// Records a candidate that passed the rule.
    #[inline]
    fn accept(&mut self, out: &mut CrammingOutput, j: u32) {
        if self.count == MAX_CANDIDATES {
            self.overflow = true; // Java switches to the uncapped retained-snapshot query
            return;
        }
        out.slots[self.count] = j as i32;
        self.count += 1;
    }

    fn finish(self, out: &mut CrammingOutput) {
        if self.overflow {
            out.count = OVERFLOW;
        } else {
            // Java collects slots in vanilla entity-section order, so ascending slots are the
            // order the live re-filter must produce.
            out.slots[..self.count].sort_unstable();
            out.count = self.count as i32;
        }
    }
}

/// Fill candidate lists for every ORIGIN row. `inputs` and `outputs` must
/// have equal length.
///
/// Grid rows (pushable, not oversized) are sorted by cell so each cell is one contiguous
/// run; origins are processed cell by cell, resolving the 27 neighbour runs once per cell
/// instead of once per origin, with no per-cell allocation. Oversized pushable rows are a
/// side list every origin checks; oversized origins scan every row.
pub fn compute_cramming(inputs: &[CrammingInput], outputs: &mut [CrammingOutput]) {
    assert_eq!(
        inputs.len(),
        outputs.len(),
        "cramming kernel: input and output slices must match in length"
    );

    let n = inputs.len();
    if n == 0 {
        return;
    }

    let mut members: Vec<(CellKey, u32)> = Vec::with_capacity(n);
    let mut big: Vec<u32> = Vec::new();
    for (i, e) in inputs.iter().enumerate() {
        // Only flagged candidates enter the grid. The bit is conservative;
        // Java's live pushableBy predicate rejects false positives.
        if !has(e, FLAG_IS_PUSHABLE) {
            continue;
        }
        if oversized(e) {
            big.push(i as u32);
        } else {
            members.push((cell_key(e), i as u32));
        }
    }
    members.sort_unstable();

    let mut runs: HashMap<CellKey, (u32, u32), FxBuild> =
        HashMap::with_capacity_and_hasher(members.len(), FxBuild::default());
    let mut start = 0;
    while start < members.len() {
        let key = members[start].0;
        let mut end = start + 1;
        while end < members.len() && members[end].0 == key {
            end += 1;
        }
        runs.insert(key, (start as u32, end as u32));
        start = end;
    }

    for out in outputs.iter_mut() {
        out.count = 0;
    }

    // Grid rows' boxes as contiguous arrays in run order: the dense-pile pair loop below
    // streams them instead of gathering 64-byte input records. Same values, same
    // arithmetic as padded_intersects; a run shares one dimension, so no dim check there.
    let mut gx = Vec::with_capacity(members.len());
    let mut gy = Vec::with_capacity(members.len());
    let mut gz = Vec::with_capacity(members.len());
    let mut hx = Vec::with_capacity(members.len());
    let mut hy = Vec::with_capacity(members.len());
    let mut hz = Vec::with_capacity(members.len());
    for &(_, i) in &members {
        let e = &inputs[i as usize];
        gx.push(e.pos[0]);
        gy.push(e.pos[1]);
        gz.push(e.pos[2]);
        hx.push(e.half[0]);
        hy.push(e.half[1]);
        hz.push(e.half[2]);
    }

    // In-grid origins, cell by cell.
    let mut neighbours: Vec<(u32, u32)> = Vec::with_capacity(27);
    for (&(dim, cx, cy, cz), &(s, t)) in runs.iter() {
        neighbours.clear();
        for dx in -1..=1 {
            for dy in -1..=1 {
                for dz in -1..=1 {
                    if let Some(&r) = runs.get(&(dim, cx + dx, cy + dy, cz + dz)) {
                        neighbours.push(r);
                    }
                }
            }
        }
        for m in s..t {
            let m = m as usize;
            let i = members[m].1 as usize;
            if !has(&inputs[i], FLAG_IS_ORIGIN) {
                continue;
            }
            let out = &mut outputs[i];
            let mut c = Collector { inputs, origin: i, count: 0, overflow: false };
            let (ex, ey, ez) = (gx[m], gy[m], gz[m]);
            let (fx, fy, fz) = (hx[m], hy[m], hz[m]);
            'scan: for &(ns, nt) in &neighbours {
                for k in ns as usize..nt as usize {
                    if k != m
                        && (ex - gx[k]).abs() < fx + hx[k] + PAD
                        && (ey - gy[k]).abs() < fy + hy[k] + PAD
                        && (ez - gz[k]).abs() < fz + hz[k] + PAD
                    {
                        c.accept(out, members[k].1);
                        if c.overflow {
                            break 'scan;
                        }
                    }
                }
            }
            for &j in &big {
                c.visit(out, j);
            }
            c.finish(out);
        }
    }

    // Origins outside the grid.
    for i in 0..n {
        let e = &inputs[i];
        if !has(e, FLAG_IS_ORIGIN) || (has(e, FLAG_IS_PUSHABLE) && !oversized(e)) {
            continue; // not an origin, or already handled as a grid member
        }
        let out = &mut outputs[i];
        let mut c = Collector { inputs, origin: i, count: 0, overflow: false };
        if oversized(e) {
            // Oversized origins out-reach the stencil — scan everything.
            for j in 0..n as u32 {
                c.visit(out, j);
            }
        } else {
            let (dim, cx, cy, cz) = cell_key(e);
            for dx in -1..=1 {
                for dy in -1..=1 {
                    for dz in -1..=1 {
                        if let Some(&(ns, nt)) = runs.get(&(dim, cx + dx, cy + dy, cz + dz)) {
                            for k in ns..nt {
                                c.visit(out, members[k as usize].1);
                            }
                        }
                    }
                }
            }
            for &j in &big {
                c.visit(out, j);
            }
        }
        c.finish(out);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const PUSH: i32 = FLAG_IS_PUSHABLE | FLAG_IS_ORIGIN;

    fn mob(x: f64, z: f64, flags: i32) -> CrammingInput {
        mob_dim(x, z, 0, flags)
    }

    fn mob_dim(x: f64, z: f64, dim: i32, flags: i32) -> CrammingInput {
        CrammingInput {
            pos: [x, 64.0, z],
            half: [0.3, 0.95, 0.3],
            dim_id: dim,
            flags,
            _pad: [0; 2],
        }
    }

    fn run(inputs: &[CrammingInput]) -> Vec<CrammingOutput> {
        let mut outputs = vec![CrammingOutput::default(); inputs.len()];
        compute_cramming(inputs, &mut outputs);
        outputs
    }

    fn slots(o: &CrammingOutput) -> Vec<i32> {
        let mut v = o.slots[..o.count.max(0) as usize].to_vec();
        v.sort();
        v
    }

    /// The kernel's definition, O(n²): an oversized origin takes every same-dimension row
    /// whose padded box intersects; any other origin takes every such PUSHABLE row. More
    /// than MAX_CANDIDATES → OVERFLOW. Slots ascending.
    fn brute_force(inputs: &[CrammingInput]) -> Vec<(i32, Vec<i32>)> {
        inputs
            .iter()
            .enumerate()
            .map(|(i, e)| {
                if !has(e, FLAG_IS_ORIGIN) {
                    return (0, Vec::new());
                }
                let found: Vec<i32> = (0..inputs.len())
                    .filter(|&j| j != i)
                    .filter(|&j| oversized(e) || has(&inputs[j], FLAG_IS_PUSHABLE))
                    .filter(|&j| inputs[j].dim_id == e.dim_id && padded_intersects(e, &inputs[j]))
                    .map(|j| j as i32)
                    .collect();
                if found.len() > MAX_CANDIDATES {
                    (OVERFLOW, Vec::new())
                } else {
                    (found.len() as i32, found)
                }
            })
            .collect()
    }

    #[test]
    fn grid_matches_brute_force_on_random_worlds() {
        let mut seed: u64 = 0x9E37_79B9_7F4A_7C15;
        let mut next = move || {
            seed ^= seed << 13;
            seed ^= seed >> 7;
            seed ^= seed << 17;
            seed
        };
        for world in 0..300 {
            let n = 1 + (next() % 400) as usize;
            // Spread from a crammed pile (overflow) to a sparse field, around negative and
            // positive cell boundaries.
            let spread = [0.6, 2.0, 6.0, 25.0][world % 4];
            let mut inputs = Vec::with_capacity(n);
            for _ in 0..n {
                let mut f = |r: f64| ((next() % 1_000_000) as f64 / 1_000_000.0 - 0.5) * r;
                let big = f(1.0) > 0.46; // ~4% oversized
                let half = if big {
                    [1.0 + f(4.0).abs(), 1.0 + f(4.0).abs(), 1.0 + f(4.0).abs()]
                } else {
                    [0.2 + f(1.0).abs(), 0.2 + f(1.6).abs(), 0.2 + f(1.0).abs()]
                };
                let pick = (f(1.0) + 0.5) * 7.0;
                let dim_pick = f(1.0) > 0.0;
                let flags = match pick as u32 {
                    0 => FLAG_IS_PUSHABLE,
                    1 => FLAG_IS_ORIGIN,
                    2 => 0,
                    _ => FLAG_IS_PUSHABLE | FLAG_IS_ORIGIN,
                };
                inputs.push(CrammingInput {
                    pos: [f(spread) - 2.5, 64.0 + f(spread / 2.0), f(spread) + 2.5],
                    half,
                    dim_id: dim_pick as i32,
                    flags,
                    _pad: [0; 2],
                });
            }
            let got = run(&inputs);
            let want = brute_force(&inputs);
            for (i, (o, (count, list))) in got.iter().zip(want.iter()).enumerate() {
                assert_eq!(o.count, *count, "world {world} row {i}: count");
                if *count > 0 {
                    assert_eq!(&o.slots[..*count as usize], &list[..], "world {world} row {i}: slots");
                }
            }
        }
    }

    #[test]
    fn single_mob_empty() {
        let out = run(&[mob(0.0, 0.0, PUSH)]);
        assert_eq!(out[0].count, 0);
    }

    #[test]
    fn touching_pair_mutual_candidates() {
        let out = run(&[mob(0.0, 0.0, PUSH), mob(0.5, 0.0, PUSH)]);
        assert_eq!(slots(&out[0]), vec![1]);
        assert_eq!(slots(&out[1]), vec![0]);
    }

    #[test]
    fn padding_includes_near_misses() {
        // Strict intersect needs |dx| < 0.6; padded accepts up to 1.1 so a
        // pair drifting together within the tick is still found.
        let out = run(&[mob(0.0, 0.0, PUSH), mob(1.0, 0.0, PUSH)]);
        assert_eq!(slots(&out[0]), vec![1]);
        // Beyond padding → excluded.
        let out2 = run(&[mob(0.0, 0.0, PUSH), mob(1.2, 0.0, PUSH)]);
        assert_eq!(out2[0].count, 0);
    }

    #[test]
    fn unflagged_row_never_a_candidate_but_still_queries() {
        // Defensive kernel invariant: an origin need not carry the candidate bit.
        let climber = mob(0.0, 0.0, FLAG_IS_ORIGIN);
        let other = mob(0.5, 0.0, PUSH);
        let out = run(&[climber, other]);
        assert_eq!(slots(&out[0]), vec![1]); // climber sees pushable other
        assert_eq!(out[1].count, 0); // climber is not a candidate
    }

    #[test]
    fn non_origin_gets_no_list() {
        let boat = mob(0.5, 0.0, FLAG_IS_PUSHABLE);
        let out = run(&[mob(0.0, 0.0, PUSH), boat]);
        assert_eq!(out[1].count, 0); // boats never query
        assert_eq!(slots(&out[0]), vec![1]); // but they are candidates
    }

    #[test]
    fn different_dimensions_never_interact() {
        let out = run(&[mob_dim(0.0, 0.0, 0, PUSH), mob_dim(0.5, 0.0, 1, PUSH)]);
        assert_eq!(out[0].count, 0);
        assert_eq!(out[1].count, 0);
    }

    #[test]
    fn overflow_returns_sentinel_not_truncation() {
        // 70 mobs stacked: > MAX_CANDIDATES neighbours each. Truncation would
        // silently weaken vanilla cramming — must signal the uncapped query.
        let mut inputs = Vec::new();
        for k in 0..140 {
            inputs.push(mob(0.005 * k as f64, 0.0, PUSH));
        }
        let out = run(&inputs);
        for o in &out {
            assert_eq!(o.count, OVERFLOW);
        }
    }

    #[test]
    fn just_under_cap_is_exact() {
        let mut inputs = Vec::new();
        for k in 0..120 {
            inputs.push(mob(0.005 * k as f64, 0.0, PUSH));
        }
        let out = run(&inputs);
        for o in &out {
            assert_eq!(o.count, 119);
        }
    }

    #[test]
    fn oversized_found_beyond_stencil_both_directions() {
        // Ghast (half 2.0) and a small mob 4.3 blocks away: padded reach is
        // 2.0 + 0.3 + 0.5 = 2.8 → NOT candidates. At 2.6 → candidates, even
        // though the small mob's ±1 stencil (4.0) wouldn't reach a ghast
        // centre 2.6 away if the ghast were point-inserted near a far cell
        // boundary. The side list guarantees it.
        let mut ghast = mob(0.0, 0.0, PUSH);
        ghast.half = [2.0, 2.0, 2.0];
        let near = mob(2.6, 0.0, PUSH);
        let out = run(&[ghast, near]);
        assert_eq!(slots(&out[0]), vec![1]);
        assert_eq!(slots(&out[1]), vec![0]);

        let far = mob(2.9, 0.0, PUSH);
        let out2 = run(&[ghast.clone(), far]);
        assert_eq!(out2[0].count, 0);
        assert_eq!(out2[1].count, 0);
    }

    #[test]
    fn stencil_covers_max_reach_pairs() {
        // Worst in-grid case: both entities at OVERSIZE_HALF with PAD —
        // reach 2·1.0 + 0.5 = 2.5. Place the pair straddling cell boundaries
        // at distance 2.4 → must still be mutual candidates.
        let mut a = mob(1.99, 0.0, PUSH);
        a.half = [1.0, 1.0, 1.0];
        let mut b = mob(4.39, 0.0, PUSH);
        b.half = [1.0, 1.0, 1.0];
        let out = run(&[a, b]);
        assert_eq!(slots(&out[0]), vec![1]);
        assert_eq!(slots(&out[1]), vec![0]);
    }
}
