// C3 — multi-box shapes as vanilla MERGED VOXEL GRIDS (the fix the
// single-box quarantine comment anticipated: per-box flattening diverges from
// vanilla at internal/coplanar faces by 5e-9-class clamps).
//
// Exact ports of (yarn names, 26.1.2 sources):
//   VoxelShape.calculateMaxDistance(AxisCycleDirection, Box, double)
//   VoxelShape.getCoordIndex (binarySearch first-above semantics)
//   AxisCycleDirection algebra: collide(axis) walks slabs of the MOVEMENT
//   axis; (a,b,c)→(x,y,z) lookup is X:(a,b,c), Y:(c,a,b), Z:(b,c,a).
//
// Grids arrive from Java once per BlockState (mirror gather marshals
// origin + per-axis point positions + occupancy bits); coordinates stay
// shape-local with a world origin — vanilla's OffsetDoubleList does the
// identical `value + offset` add, so doubles match bit-for-bit.

use super::{is_nonzero_epsilon_boundary, Aabb, EPSILON};

pub struct Grid<'a> {
    pub origin: [f64; 3],
    pub n: [usize; 3],
    /// Shape-local point positions, len n[axis] + 1.
    pub coords: [&'a [f64]; 3],
    /// Occupancy, idx = (x * ny + y) * nz + z.
    pub bits: &'a [u64],
}

impl Grid<'_> {
    #[inline]
    fn full(&self, x: usize, y: usize, z: usize) -> bool {
        let idx = (x * self.n[1] + y) * self.n[2] + z;
        (self.bits[idx >> 6] >> (idx & 63)) & 1 != 0
    }

    /// (a,b,c) → (x,y,z) per the inverse axis cycle of `axis`.
    #[inline]
    fn full_abc(&self, axis: usize, a: usize, b: usize, c: usize) -> bool {
        match axis {
            0 => self.full(a, b, c),
            1 => self.full(c, a, b),
            _ => self.full(b, c, a),
        }
    }

    #[inline]
    fn coord(&self, axis: usize, i: usize) -> f64 {
        self.origin[axis] + self.coords[axis][i]
    }

    /// VoxelShape.getCoordIndex: first idx in [0, n+1) with v < coord(idx),
    /// else n+1; minus 1. Linear scan — n ≤ 16, result identical to the
    /// binary search.
    #[inline]
    fn coord_index(&self, axis: usize, v: f64) -> i64 {
        let n = self.n[axis];
        for idx in 0..=n {
            if v < self.coord(axis, idx) {
                return idx as i64 - 1;
            }
        }
        n as i64
    }

    /// World-space bounding box (first/last point position per axis).
    pub fn bounds(&self) -> [f64; 6] {
        [
            self.coord(0, 0),
            self.coord(1, 0),
            self.coord(2, 0),
            self.coord(0, self.n[0]),
            self.coord(1, self.n[1]),
            self.coord(2, self.n[2]),
        ]
    }

    /// Voxel-precise intersection with `query`: any OCCUPIED cell whose world
    /// box strictly intersects — the grid analogue of the certified
    /// single-box filter predicate (vanilla pre-filters non-full shapes with
    /// joinIsNotEmpty against the stretched box before they enter the
    /// collider list, which also keeps their step-height points out).
    pub fn intersects(&self, query: &Aabb) -> bool {
        for x in 0..self.n[0] {
            if self.coord(0, x + 1) <= query.min[0] || self.coord(0, x) >= query.max[0] {
                continue;
            }
            for y in 0..self.n[1] {
                if self.coord(1, y + 1) <= query.min[1] || self.coord(1, y) >= query.max[1] {
                    continue;
                }
                for z in 0..self.n[2] {
                    if self.coord(2, z + 1) <= query.min[2] || self.coord(2, z) >= query.max[2] {
                        continue;
                    }
                    if self.full(x, y, z) {
                        return true;
                    }
                }
            }
        }
        false
    }

    /// Exact port of VoxelShape.calculateMaxDistance — including the
    /// early-return on the FIRST slab containing any occupied cell, both
    /// 1e-7 epsilons, and the window index clamping.
    pub fn collide_axis(
        &self,
        axis: usize,
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
        let (a_ax, b_ax, c_ax) = match axis {
            0 => (0usize, 1usize, 2usize),
            1 => (1, 2, 0),
            _ => (2, 0, 1),
        };
        let max_a = bb.max[a_ax];
        let min_a = bb.min[a_ax];
        let a_min = self.coord_index(a_ax, min_a + EPSILON);
        let a_max = self.coord_index(a_ax, max_a - EPSILON);
        let b_min = 0i64.max(self.coord_index(b_ax, bb.min[b_ax] + EPSILON));
        let b_max = (self.n[b_ax] as i64).min(self.coord_index(b_ax, bb.max[b_ax] - EPSILON) + 1);
        let c_min = 0i64.max(self.coord_index(c_ax, bb.min[c_ax] + EPSILON));
        let c_max = (self.n[c_ax] as i64).min(self.coord_index(c_ax, bb.max[c_ax] - EPSILON) + 1);
        let a_size = self.n[a_ax] as i64;

        if max_dist > 0.0 {
            let mut a = a_max + 1;
            while a < a_size {
                for b in b_min..b_max {
                    for c in c_min..c_max {
                        if self.full_abc(a_ax, a as usize, b as usize, c as usize) {
                            let nd = self.coord(a_ax, a as usize) - max_a;
                            if nd >= -EPSILON {
                                max_dist = max_dist.min(nd);
                            }
                            if is_nonzero_epsilon_boundary(max_dist) {
                                *epsilon_unstable = true;
                            }
                            return max_dist;
                        }
                    }
                }
                a += 1;
            }
        } else if max_dist < 0.0 {
            let mut a = a_min - 1;
            while a >= 0 {
                for b in b_min..b_max {
                    for c in c_min..c_max {
                        if self.full_abc(a_ax, a as usize, b as usize, c as usize) {
                            let nd = self.coord(a_ax, (a + 1) as usize) - min_a;
                            if nd <= EPSILON {
                                max_dist = max_dist.max(nd);
                            }
                            if is_nonzero_epsilon_boundary(max_dist) {
                                *epsilon_unstable = true;
                            }
                            return max_dist;
                        }
                    }
                }
                a -= 1;
            }
        }
        max_dist
    }
}

/// Parse the marshalled grids buffer: per record —
/// [ox, oy, oz: f64×3][nx, ny, nz, nWords: i32×4]
/// [coordsX: (nx+1)×f64][coordsY][coordsZ][bits: nWords×u64].
/// Returns parsed grids borrowing the buffer.
///
/// # Safety
/// `addr` must point to `count` complete grid records laid out as above, alive and unmodified
/// for as long as the returned grids are used.
pub unsafe fn parse_grids(addr: *const u8, count: usize) -> Vec<Grid<'static>> {
    let mut out = Vec::with_capacity(count);
    let mut p = addr;
    for _ in 0..count {
        let origin = [
            (p as *const f64).read_unaligned(),
            (p.add(8) as *const f64).read_unaligned(),
            (p.add(16) as *const f64).read_unaligned(),
        ];
        let nx = (p.add(24) as *const i32).read_unaligned() as usize;
        let ny = (p.add(28) as *const i32).read_unaligned() as usize;
        let nz = (p.add(32) as *const i32).read_unaligned() as usize;
        let nwords = (p.add(36) as *const i32).read_unaligned() as usize;
        let mut q = p.add(40);
        let cx = std::slice::from_raw_parts(q as *const f64, nx + 1);
        q = q.add((nx + 1) * 8);
        let cy = std::slice::from_raw_parts(q as *const f64, ny + 1);
        q = q.add((ny + 1) * 8);
        let cz = std::slice::from_raw_parts(q as *const f64, nz + 1);
        q = q.add((nz + 1) * 8);
        let bits = std::slice::from_raw_parts(q as *const u64, nwords);
        q = q.add(nwords * 8);
        out.push(Grid {
            origin,
            n: [nx, ny, nz],
            coords: [cx, cy, cz],
            bits,
        });
        p = q;
    }
    out
}
