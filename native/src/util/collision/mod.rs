// Vanilla-exact entity collision over FULL-CUBE colliders (oracle:
// fixtures/collision/*.json, recorded from vanilla).
//
// Port of Entity.adjustMovementForCollisions(Vec3d) for the case the C0
// classifier showed dominates (~80%): every candidate collider is a full
// unit cube, no entity colliders, no world border. The grid walk of
// VoxelShape.calculateMaxDistance collapses to closed-form inequalities for
// a 1×1×1 voxel grid — every epsilon (1.0E-7), floor-index and comparison
// direction mirrors the decompiled 26.1.2 source line by line:
//
//   getCoordIndex(coord)  → floor(coord - cubeCoord)   (cube grid: {-..,0,1,..})
//   cross-section overlap → floor(boxMin+1e-7 - c) ≤ 0 ≤ floor(boxMax-1e-7 - c)
//   positive a-range      → floor(boxMaxA-1e-7 - cA) ≤ -1, face dist = cA - boxMaxA
//   negative a-range      → floor(boxMinA+1e-7 - cA) ≥ 1, face dist = (cA+1) - boxMinA
//
// The caller (Java/fixtures) supplies the candidate cube list in vanilla's
// own discovery order; per-call filtering against the stretched query box
// reproduces BlockCollisions' strict-intersect cursor.

pub mod ffi;
pub mod grid;

use grid::Grid;

const EPSILON: f64 = 1.0e-7;

#[inline]
fn is_nonzero_epsilon_boundary(value: f64) -> bool {
    value != 0.0 && value.abs() <= EPSILON
}

pub(crate) struct CollisionResult {
    pub movement: [f64; 3],
    pub epsilon_unstable: bool,
}

/// One collider in vanilla discovery order: a plain box (full cube or a
/// single-box shape — clamp math identical to a 1-cell grid) or a merged
/// voxel grid (multi-box shape, C3). Order matters at the 1e-7 epsilon
/// boundary (the short-circuit-to-zero), so cubes and grids interleave in
/// the exact gather sequence.
pub enum Collider<'a> {
    Cube([f64; 6]),
    Grid(&'a Grid<'a>),
}

#[derive(Clone, Copy)]
pub struct Aabb {
    pub min: [f64; 3],
    pub max: [f64; 3],
}

impl Aabb {
    #[inline]
    fn offset(&self, d: [f64; 3]) -> Aabb {
        Aabb {
            min: [self.min[0] + d[0], self.min[1] + d[1], self.min[2] + d[2]],
            max: [self.max[0] + d[0], self.max[1] + d[1], self.max[2] + d[2]],
        }
    }

    /// Box.stretch — extend towards the movement direction per axis.
    #[inline]
    fn stretch(&self, d: [f64; 3]) -> Aabb {
        let mut r = *self;
        for a in 0..3 {
            if d[a] < 0.0 {
                r.min[a] += d[a];
            } else {
                r.max[a] += d[a];
            }
        }
        r
    }

    /// Box.intersects — strict inequalities, exactly vanilla.
    #[inline]
    fn intersects_box(&self, c: &[f64; 6]) -> bool {
        self.min[0] < c[3]
            && self.max[0] > c[0]
            && self.min[1] < c[4]
            && self.max[1] > c[1]
            && self.min[2] < c[5]
            && self.max[2] > c[2]
    }
}

/// VoxelShape.calculateMaxDistance for one collider AABB (a full cube is the
/// box [c, c+1]^3 — the floor-index boundary semantics generalise verbatim:
/// left-closed cells, every 1e-7 epsilon and comparison direction preserved).
#[inline]
fn max_distance_box(
    a: usize,
    col: &[f64; 6],
    bb: &Aabb,
    mut max_dist: f64,
    epsilon_unstable: &mut bool,
) -> f64 {
    if is_nonzero_epsilon_boundary(max_dist) {
        *epsilon_unstable = true;
    }
    if max_dist.abs() < EPSILON {
        return 0.0;
    }
    let b = (a + 1) % 3;
    let c = (a + 2) % 3;
    // cross-section overlap (mirrors floor(min+eps-c) ≤ 0 ≤ floor(max-eps-c)):
    if !(bb.min[b] + EPSILON < col[b + 3] && bb.max[b] - EPSILON >= col[b]) {
        return max_dist;
    }
    if !(bb.min[c] + EPSILON < col[c + 3] && bb.max[c] - EPSILON >= col[c]) {
        return max_dist;
    }
    if max_dist > 0.0 {
        // collider strictly ahead: floor(boxMaxA-1e-7 - colMinA) ≤ -1
        if bb.max[a] - EPSILON < col[a] {
            let new_distance = col[a] - bb.max[a];
            if new_distance >= -EPSILON {
                max_dist = max_dist.min(new_distance);
            }
        }
    } else if max_dist < 0.0 {
        // collider strictly behind: floor(boxMinA+1e-7 - colMaxA-relative) ≥ 1
        if bb.min[a] + EPSILON >= col[a + 3] {
            let new_distance = col[a + 3] - bb.min[a];
            if new_distance <= EPSILON {
                max_dist = max_dist.max(new_distance);
            }
        }
    }
    if is_nonzero_epsilon_boundary(max_dist) {
        *epsilon_unstable = true;
    }
    max_dist
}

/// VoxelShapes.calculateMaxOffset over the candidate list (vanilla order).
/// The loop-level shortcut equals vanilla's per-shape entry check: once
/// |dist| < 1e-7 every later shape would return 0.0 anyway.
#[inline]
fn calculate_max_offset(
    a: usize,
    bb: &Aabb,
    cols: &[&Collider<'_>],
    mut max_dist: f64,
    epsilon_unstable: &mut bool,
) -> f64 {
    for col in cols {
        if is_nonzero_epsilon_boundary(max_dist) {
            *epsilon_unstable = true;
        }
        if max_dist.abs() < EPSILON {
            return 0.0;
        }
        max_dist = match col {
            Collider::Cube(c) => max_distance_box(a, c, bb, max_dist, epsilon_unstable),
            Collider::Grid(g) => g.collide_axis(a, bb, max_dist, epsilon_unstable),
        };
    }
    max_dist
}

/// Direction.getCollisionOrder: |x| < |z| → Y,Z,X else Y,X,Z.
#[inline]
fn collision_order(d: [f64; 3]) -> [usize; 3] {
    if d[0].abs() < d[2].abs() {
        [1, 2, 0]
    } else {
        [1, 0, 2]
    }
}

/// Entity.adjustMovementForCollisions(movement, box, colliders) — the inner
/// per-axis resolve. `cols` must already be filtered to the stretched box.
fn adjust_movement(
    movement: [f64; 3],
    bb: &Aabb,
    cols: &[&Collider<'_>],
    epsilon_unstable: &mut bool,
) -> [f64; 3] {
    if cols.is_empty() {
        return movement;
    }
    let mut resolved = [0.0f64; 3];
    for &axis in &collision_order(movement) {
        let axis_movement = movement[axis];
        if axis_movement != 0.0 {
            let moved = bb.offset(resolved);
            resolved[axis] =
                calculate_max_offset(axis, &moved, cols, axis_movement, epsilon_unstable);
        }
    }
    resolved
}

/// Filter candidates to those intersecting `query`, preserving order. Cubes
/// use the strict box intersect (BlockCollisions cursor predicate, certified
/// on 12.28M shadow calls); grids use the voxel-precise occupied-cell test —
/// vanilla pre-filters non-full shapes with joinIsNotEmpty against the query
/// shape, which also keeps a non-intersecting shape's points OUT of the
/// step-height collection.
fn filter_colliders<'a, 'g>(
    out: &mut Vec<&'a Collider<'g>>,
    candidates: &'a [Collider<'g>],
    query: &Aabb,
) {
    out.clear();
    for c in candidates {
        let keep = match c {
            Collider::Cube(b) => query.intersects_box(b),
            Collider::Grid(g) => g.intersects(query),
        };
        if keep {
            out.push(c);
        }
    }
}

/// collectStepHeights — candidate Y point positions of the colliders relative
/// to the box floor, deduped (exact f32 equality) and ascending-sorted.
/// A grid contributes its full merged Y coordinate list (vanilla iterates the
/// shape's getPointPositions(Y)), with the same ascending-break semantics.
fn collect_step_heights(
    collision_box: &Aabb,
    cols: &[&Collider<'_>],
    step_height: f32,
    movement_y: f32,
) -> Vec<f32> {
    let mut candidates: Vec<f32> = Vec::with_capacity(8);
    'outer: for col in cols {
        match col {
            Collider::Cube(c) => {
                // collider Y point positions: minY, maxY (ascending, as
                // vanilla's getPointPositions(Y) yields them)
                for coord in [c[1], c[4]] {
                    let relative = (coord - collision_box.min[1]) as f32;
                    if !(relative < 0.0) && relative != movement_y {
                        if relative > step_height {
                            // vanilla `break` leaves the per-shape point loop:
                            // points ascend per shape, later ones only grow.
                            continue 'outer;
                        }
                        if !candidates.contains(&relative) {
                            candidates.push(relative);
                        }
                    }
                }
            }
            Collider::Grid(g) => {
                for i in 0..=g.n[1] {
                    let coord = g.origin[1] + g.coords[1][i];
                    let relative = (coord - collision_box.min[1]) as f32;
                    if !(relative < 0.0) && relative != movement_y {
                        if relative > step_height {
                            continue 'outer;
                        }
                        if !candidates.contains(&relative) {
                            candidates.push(relative);
                        }
                    }
                }
            }
        }
    }
    candidates.sort_by(|a, b| a.partial_cmp(b).unwrap());
    candidates
}

/// The full vanilla flow over mixed colliders (cubes + voxel grids) in
/// discovery order: first collide, then the step-up attempt.
pub(crate) fn collide_mixed_checked(
    movement: [f64; 3],
    bb: Aabb,
    step_height: f32,
    on_ground: bool,
    candidates: &[Collider<'_>],
) -> CollisionResult {
    let mut epsilon_unstable = false;
    let mut scratch: Vec<&Collider<'_>> = Vec::with_capacity(candidates.len());

    // adjustMovementForCollisions(entity, movement, aabb, world, …):
    // colliders = filter(stretched box), then per-axis resolve.
    let movement_step = if movement[0] == 0.0 && movement[1] == 0.0 && movement[2] == 0.0 {
        movement
    } else {
        filter_colliders(&mut scratch, candidates, &bb.stretch(movement));
        adjust_movement(movement, &bb, &scratch, &mut epsilon_unstable)
    };

    let x_collision = movement[0] != movement_step[0];
    let y_collision = movement[1] != movement_step[1];
    let z_collision = movement[2] != movement_step[2];
    let on_ground_after = y_collision && movement[1] < 0.0;

    if step_height > 0.0 && (on_ground_after || on_ground) && (x_collision || z_collision) {
        let grounded = if on_ground_after {
            bb.offset([0.0, movement_step[1], 0.0])
        } else {
            bb
        };
        let mut step_up_box = grounded.stretch([movement[0], step_height as f64, movement[2]]);
        if !on_ground_after {
            // stretch(0, -1.0E-5F, 0) — the float literal widened, as vanilla
            step_up_box = step_up_box.stretch([0.0, -(1.0e-5f32 as f64), 0.0]);
        }

        let mut step_cols: Vec<&Collider<'_>> = Vec::with_capacity(candidates.len());
        filter_colliders(&mut step_cols, candidates, &step_up_box);
        let step_height_to_skip = movement_step[1] as f32;
        let heights = collect_step_heights(&grounded, &step_cols, step_height, step_height_to_skip);

        let horiz_base = movement_step[0] * movement_step[0] + movement_step[2] * movement_step[2];
        for h in heights {
            let step_from_ground = adjust_movement(
                [movement[0], h as f64, movement[2]],
                &grounded,
                &step_cols,
                &mut epsilon_unstable,
            );
            let horiz = step_from_ground[0] * step_from_ground[0]
                + step_from_ground[2] * step_from_ground[2];
            if horiz > horiz_base {
                let distance_to_ground = bb.min[1] - grounded.min[1];
                return CollisionResult {
                    movement: [
                        step_from_ground[0],
                        step_from_ground[1] - distance_to_ground,
                        step_from_ground[2],
                    ],
                    epsilon_unstable,
                };
            }
        }
    }

    CollisionResult {
        movement: movement_step,
        epsilon_unstable,
    }
}

pub fn collide_mixed(
    movement: [f64; 3],
    bb: Aabb,
    step_height: f32,
    on_ground: bool,
    candidates: &[Collider<'_>],
) -> [f64; 3] {
    collide_mixed_checked(movement, bb, step_height, on_ground, candidates).movement
}

/// Cube/box-specialized calculateMaxOffset. Filtering remains fused into the
/// per-axis scan, so the common path allocates nothing while preserving the
/// candidate discovery order.
#[inline]
fn calculate_max_offset_boxes(
    axis: usize,
    bb: &Aabb,
    candidates: &[[f64; 6]],
    query: &Aabb,
    mut max_dist: f64,
    epsilon_unstable: &mut bool,
) -> f64 {
    for col in candidates {
        if !query.intersects_box(col) {
            continue;
        }
        if is_nonzero_epsilon_boundary(max_dist) {
            *epsilon_unstable = true;
        }
        if max_dist.abs() < EPSILON {
            return 0.0;
        }
        max_dist = max_distance_box(axis, col, bb, max_dist, epsilon_unstable);
    }
    max_dist
}

fn adjust_movement_boxes(
    movement: [f64; 3],
    bb: &Aabb,
    candidates: &[[f64; 6]],
    query: &Aabb,
    epsilon_unstable: &mut bool,
) -> [f64; 3] {
    // Vanilla returns the original vector when the filtered shape list is
    // empty. Besides avoiding work, this preserves the sign bit of -0.0.
    if !candidates.iter().any(|col| query.intersects_box(col)) {
        return movement;
    }
    let mut resolved = [0.0f64; 3];
    for &axis in &collision_order(movement) {
        let axis_movement = movement[axis];
        if axis_movement != 0.0 {
            let moved = bb.offset(resolved);
            resolved[axis] = calculate_max_offset_boxes(
                axis,
                &moved,
                candidates,
                query,
                axis_movement,
                epsilon_unstable,
            );
        }
    }
    resolved
}

fn collect_step_heights_boxes(
    collision_box: &Aabb,
    candidates: &[[f64; 6]],
    query: &Aabb,
    step_height: f32,
    movement_y: f32,
) -> Vec<f32> {
    let mut heights = Vec::with_capacity(8);
    'outer: for col in candidates {
        if !query.intersects_box(col) {
            continue;
        }
        for coord in [col[1], col[4]] {
            let relative = (coord - collision_box.min[1]) as f32;
            if !(relative < 0.0) && relative != movement_y {
                if relative > step_height {
                    continue 'outer;
                }
                if !heights.contains(&relative) {
                    heights.push(relative);
                }
            }
        }
    }
    heights.sort_by(|a, b| a.partial_cmp(b).unwrap());
    heights
}

/// Box-only entry — certified bit-for-bit by the collision fixture corpus.
/// The non-step path performs no heap allocation.
pub(crate) fn collide_checked(
    movement: [f64; 3],
    bb: Aabb,
    step_height: f32,
    on_ground: bool,
    candidates: &[[f64; 6]],
) -> CollisionResult {
    if movement[0] == 0.0 && movement[1] == 0.0 && movement[2] == 0.0 {
        return CollisionResult {
            movement,
            epsilon_unstable: false,
        };
    }

    let mut epsilon_unstable = false;
    let query = bb.stretch(movement);
    let movement_step =
        adjust_movement_boxes(movement, &bb, candidates, &query, &mut epsilon_unstable);
    let x_collision = movement[0] != movement_step[0];
    let y_collision = movement[1] != movement_step[1];
    let z_collision = movement[2] != movement_step[2];
    let on_ground_after = y_collision && movement[1] < 0.0;

    if step_height > 0.0 && (on_ground_after || on_ground) && (x_collision || z_collision) {
        let grounded = if on_ground_after {
            bb.offset([0.0, movement_step[1], 0.0])
        } else {
            bb
        };
        let mut step_query = grounded.stretch([movement[0], step_height as f64, movement[2]]);
        if !on_ground_after {
            step_query = step_query.stretch([0.0, -(1.0e-5f32 as f64), 0.0]);
        }
        let heights = collect_step_heights_boxes(
            &grounded,
            candidates,
            &step_query,
            step_height,
            movement_step[1] as f32,
        );
        let horizontal_base =
            movement_step[0] * movement_step[0] + movement_step[2] * movement_step[2];
        for height in heights {
            let step_from_ground = adjust_movement_boxes(
                [movement[0], height as f64, movement[2]],
                &grounded,
                candidates,
                &step_query,
                &mut epsilon_unstable,
            );
            let horizontal = step_from_ground[0] * step_from_ground[0]
                + step_from_ground[2] * step_from_ground[2];
            if horizontal > horizontal_base {
                let distance_to_ground = bb.min[1] - grounded.min[1];
                return CollisionResult {
                    movement: [
                        step_from_ground[0],
                        step_from_ground[1] - distance_to_ground,
                        step_from_ground[2],
                    ],
                    epsilon_unstable,
                };
            }
        }
    }

    CollisionResult {
        movement: movement_step,
        epsilon_unstable,
    }
}

pub fn collide(
    movement: [f64; 3],
    bb: Aabb,
    step_height: f32,
    on_ground: bool,
    candidates: &[[f64; 6]],
) -> [f64; 3] {
    collide_checked(movement, bb, step_height, on_ground, candidates).movement
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::path::PathBuf;

    fn fixture_dir() -> PathBuf {
        // native/ → repo root → fixtures/collision
        PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../fixtures/collision")
    }

    #[test]
    fn fixtures_bit_exact() {
        let dir = fixture_dir();
        let mut entries: Vec<_> = fs::read_dir(&dir)
            .unwrap_or_else(|e| panic!("fixtures dir {dir:?}: {e}"))
            .map(|e| e.unwrap().path())
            .filter(|p| p.extension().is_some_and(|x| x == "json"))
            .collect();
        entries.sort();
        assert!(!entries.is_empty(), "no fixtures found in {dir:?}");

        let mut failures = Vec::new();
        for path in &entries {
            let v: serde_json::Value =
                serde_json::from_str(&fs::read_to_string(path).unwrap()).unwrap();
            let f = |k: &str, i: usize| v[k][i].as_f64().unwrap();
            let bb = Aabb {
                min: [f("box", 0), f("box", 1), f("box", 2)],
                max: [f("box", 3), f("box", 4), f("box", 5)],
            };
            let movement = [f("delta", 0), f("delta", 1), f("delta", 2)];
            let step = v["step"].as_f64().unwrap() as f32;
            let on_ground = v["onGround"].as_bool().unwrap();
            let solids: Vec<[f64; 6]> = v["solids"]
                .as_array()
                .unwrap()
                .iter()
                .map(|p| {
                    let (x, y, z) = (
                        p[0].as_i64().unwrap() as f64,
                        p[1].as_i64().unwrap() as f64,
                        p[2].as_i64().unwrap() as f64,
                    );
                    [x, y, z, x + 1.0, y + 1.0, z + 1.0]
                })
                .collect();
            let expected = [f("result", 0), f("result", 1), f("result", 2)];

            let got = collide(movement, bb, step, on_ground, &solids);
            let exact = got
                .iter()
                .zip(expected.iter())
                .all(|(a, b)| a.to_bits() == b.to_bits());
            if !exact {
                failures.push(format!(
                    "{}: got {:?} want {:?}",
                    path.file_name().unwrap().to_string_lossy(),
                    got,
                    expected
                ));
            }
        }
        assert!(
            failures.is_empty(),
            "{}/{} fixtures diverged:\n{}",
            failures.len(),
            entries.len(),
            failures.join("\n")
        );
    }

    #[test]
    fn flags_nonzero_sub_epsilon_intermediates_in_both_output_directions() {
        let bb = Aabb {
            min: [0.7, 120.0, 3.399999988079071],
            max: [1.3, 121.8, 3.999999988079071],
        };
        let movement = [0.0, 0.0, -0.022360680108198];
        let curb = [0.0, 120.0, 2.0, 2.0, 122.0, 3.4];

        let exposed = collide_checked(movement, bb, 0.0, false, &[curb]);
        let penetration = 3.4 - bb.min[2];
        assert_eq!(exposed.movement[2].to_bits(), penetration.to_bits());
        assert!(exposed.epsilon_unstable);

        // A later collider performs the order-sensitive entry normalization:
        // the native result is now zero, but must still request Java fallback.
        let later = [0.0, 120.0, 3.5, 2.0, 122.0, 4.5];
        let normalized = collide_checked(movement, bb, 0.0, false, &[curb, later]);
        assert_eq!(normalized.movement[2].to_bits(), 0.0f64.to_bits());
        assert!(normalized.epsilon_unstable);
    }

    #[test]
    fn exact_zero_contact_is_order_independent() {
        let bb = Aabb {
            min: [0.7, 120.0, 3.4],
            max: [1.3, 121.8, 4.0],
        };
        let curb = [0.0, 120.0, 2.0, 2.0, 122.0, 3.4];
        let result = collide_checked(
            [0.0, 0.0, -0.02],
            bb,
            0.0,
            false,
            &[curb],
        );
        assert_eq!(result.movement[2].to_bits(), 0.0f64.to_bits());
        assert!(!result.epsilon_unstable);
    }

    #[test]
    fn exact_epsilon_boundary_is_quarantined_but_zero_is_not() {
        assert!(is_nonzero_epsilon_boundary(EPSILON));
        assert!(is_nonzero_epsilon_boundary(-EPSILON));
        assert!(!is_nonzero_epsilon_boundary(0.0));
        assert!(!is_nonzero_epsilon_boundary(-0.0));
        assert!(!is_nonzero_epsilon_boundary(f64::from_bits(EPSILON.to_bits() + 1)));
    }

    #[test]
    fn grid_producer_sets_the_same_fallback_latch() {
        let bb = Aabb {
            min: [0.7, 120.0, 3.399999988079071],
            max: [1.3, 121.8, 3.999999988079071],
        };
        let xs = [0.0, 2.0];
        let ys = [0.0, 2.0];
        let zs = [0.0, 1.4];
        let bits = [1u64];
        let grid = Grid {
            origin: [0.0, 120.0, 2.0],
            n: [1, 1, 1],
            coords: [&xs, &ys, &zs],
            bits: &bits,
        };
        let colliders = [Collider::Grid(&grid)];
        let result = collide_mixed_checked(
            [0.0, 0.0, -0.022360680108198],
            bb,
            0.0,
            false,
            &colliders,
        );
        assert!(result.epsilon_unstable);
        assert_eq!(result.movement[2].to_bits(), (3.4 - bb.min[2]).to_bits());
    }

    #[test]
    fn step_height_incoming_boundary_sets_the_fallback_latch() {
        let bb = Aabb {
            min: [0.0, 0.0, 0.0],
            max: [0.6, 1.8, 0.6],
        };
        let low_step = [0.6, 1.0e-8, 0.0, 1.6, 1.0, 1.0];
        let result = collide_checked([0.2, 0.0, 0.0], bb, 0.6, true, &[low_step]);
        assert!(result.epsilon_unstable);
    }
}
