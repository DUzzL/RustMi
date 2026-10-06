// Fixture world model — the recorded static inputs a WalkNodeEvaluator search
// reads (recorder v3, PathRecorder.java): per-cell static PathType
// (getPathTypeStatic incl. neighbor upgrades), shape-empty / floatable-fluid /
// is-air / isPathfindable(LAND) bitsets, and sparse AABB lists for cells whose
// collision shape is neither empty nor a full cube.
//
// Coordinates are absolute world coords; the grid is the recorded bbox
// (inclusive), laid out y-major, then z, then x (recorder loop order).
// Out-of-grid reads are counted (`misses`) — a fixture replay with misses > 0
// is inconclusive rather than failed, since vanilla read live world there.

use std::collections::HashMap;

pub const T_BLOCKED: u8 = 0;
pub const T_OPEN: u8 = 1;
pub const T_WALKABLE: u8 = 2;
pub const T_WALKABLE_DOOR: u8 = 3;
pub const T_TRAPDOOR: u8 = 4;
pub const T_POWDER_SNOW: u8 = 5;
pub const T_FENCE: u8 = 7;
pub const T_WATER: u8 = 9;
pub const T_RAIL: u8 = 11;
pub const T_UNPASSABLE_RAIL: u8 = 12;
pub const T_DOOR_OPEN: u8 = 17;
pub const T_DOOR_WOOD_CLOSED: u8 = 18;
pub const T_DOOR_IRON_CLOSED: u8 = 19;
pub const T_STICKY_HONEY: u8 = 22;
pub const T_BIG_MOBS_CLOSE_TO_DANGER: u8 = 26;
pub const TYPE_COUNT: usize = 27;

/// PathType constant names in ordinal (== 26.3 network `id`) order. Fixtures
/// embed `typeNames`; the loader remaps recorded ordinals through these names
/// so an upstream enum reorder cannot silently shift typeGrid/malus columns.
pub const TYPE_NAMES: [&str; TYPE_COUNT] = [
    "BLOCKED",
    "OPEN",
    "WALKABLE",
    "WALKABLE_DOOR",
    "TRAPDOOR",
    "POWDER_SNOW",
    "ON_TOP_OF_POWDER_SNOW",
    "FENCE",
    "LAVA",
    "WATER",
    "WATER_BORDER",
    "RAIL",
    "UNPASSABLE_RAIL",
    "FIRE_IN_NEIGHBOR",
    "FIRE",
    "DAMAGING_IN_NEIGHBOR",
    "DAMAGING",
    "DOOR_OPEN",
    "DOOR_WOOD_CLOSED",
    "DOOR_IRON_CLOSED",
    "BREACH",
    "LEAVES",
    "STICKY_HONEY",
    "COCOA",
    "DAMAGE_CAUTIOUS",
    "ON_TOP_OF_TRAPDOOR",
    "BIG_MOBS_CLOSE_TO_DANGER",
];

/// PathType.getMalus() default, ordinal order (TYPE_NAMES) — unchanged
/// between 26.2 and 26.3.
pub const DEFAULT_PENALTY: [f32; TYPE_COUNT] = [
    -1.0, 0.0, 0.0, 0.0, 0.0, -1.0, 0.0, -1.0, -1.0, 8.0, 8.0, 0.0, -1.0, 8.0, 16.0, 8.0, -1.0,
    0.0, -1.0, -1.0, 4.0, -1.0, 8.0, 0.0, 0.0, 0.0, 4.0,
];

pub struct World {
    pub x0: i32,
    pub y0: i32,
    pub z0: i32,
    pub x1: i32,
    pub y1: i32,
    pub z1: i32,
    sx: i64,
    sz: i64,
    types: Vec<u8>,
    empty: Vec<u8>,
    floatable: Vec<u8>,
    air: Vec<u8>,
    pass_land: Vec<u8>,
    /// cell index -> collision AABBs RELATIVE to the cell (toAabbs output).
    shapes: HashMap<i32, Vec<[f64; 6]>>,
    /// world build limits (getMinY/getMaxY).
    pub min_y: i32,
    pub max_y: i32,
    pub misses: u64,
}

impl World {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        bbox: [i32; 6],
        min_y: i32,
        max_y: i32,
        types: Vec<u8>,
        empty: Vec<u8>,
        floatable: Vec<u8>,
        air: Vec<u8>,
        pass_land: Vec<u8>,
        shapes: HashMap<i32, Vec<[f64; 6]>>,
    ) -> World {
        let [x0, y0, z0, x1, y1, z1] = bbox;
        World {
            x0,
            y0,
            z0,
            x1,
            y1,
            z1,
            sx: (x1 - x0 + 1) as i64,
            sz: (z1 - z0 + 1) as i64,
            types,
            empty,
            floatable,
            air,
            pass_land,
            shapes,
            min_y,
            max_y,
            misses: 0,
        }
    }

    #[inline]
    fn idx(&self, x: i32, y: i32, z: i32) -> Option<usize> {
        if x < self.x0 || x > self.x1 || y < self.y0 || y > self.y1 || z < self.z0 || z > self.z1 {
            return None;
        }
        // recorder loop order: y outer, z middle, x inner
        Some(
            (((y - self.y0) as i64 * self.sz + (z - self.z0) as i64) * self.sx
                + (x - self.x0) as i64) as usize,
        )
    }

    #[inline]
    fn bit(arr: &[u8], gi: usize) -> bool {
        (arr[gi >> 3] >> (gi & 7)) & 1 != 0
    }

    /// Static PathType at cell (getPathTypeStatic). Out-of-grid → BLOCKED + miss.
    pub fn type_at(&mut self, x: i32, y: i32, z: i32) -> u8 {
        match self.idx(x, y, z) {
            Some(gi) => self.types[gi],
            None => {
                self.misses += 1;
                T_BLOCKED
            }
        }
    }

    pub fn empty_at(&mut self, x: i32, y: i32, z: i32) -> bool {
        match self.idx(x, y, z) {
            Some(gi) => Self::bit(&self.empty, gi),
            None => {
                self.misses += 1;
                false
            }
        }
    }

    /// Fluid at cell is in `#entity_floatable` (26.3 getStart column walk and
    /// getFloorLevel swim branch; vanilla data: `#water`).
    pub fn floatable_at(&mut self, x: i32, y: i32, z: i32) -> bool {
        match self.idx(x, y, z) {
            Some(gi) => Self::bit(&self.floatable, gi),
            None => {
                self.misses += 1;
                false
            }
        }
    }

    pub fn air_at(&mut self, x: i32, y: i32, z: i32) -> bool {
        match self.idx(x, y, z) {
            Some(gi) => Self::bit(&self.air, gi),
            None => {
                self.misses += 1;
                false
            }
        }
    }

    pub fn pass_land_at(&mut self, x: i32, y: i32, z: i32) -> bool {
        match self.idx(x, y, z) {
            Some(gi) => Self::bit(&self.pass_land, gi),
            None => {
                self.misses += 1;
                false
            }
        }
    }

    /// WalkNodeEvaluator.getFloorLevel static half (the non-swim branch):
    /// feetY(pos) = (y-1) + (below shape empty ? 0 : belowShape.max(Y)).
    /// The swim branch (canFloat && floatable fluid at pos → y + 0.5) lives in the
    /// evaluator, which knows the mob flags.
    pub fn static_feet_y(&mut self, x: i32, y: i32, z: i32) -> f64 {
        let by = y - 1;
        if self.empty_at(x, by, z) {
            return by as f64;
        }
        if let Some(gi) = self.idx(x, by, z) {
            if let Some(boxes) = self.shapes.get(&(gi as i32)) {
                let mut max_y = f64::NEG_INFINITY;
                for b in boxes {
                    if b[4] > max_y {
                        max_y = b[4];
                    }
                }
                return by as f64 + max_y;
            }
        }
        // full cube (or out-of-grid, already counted by empty_at)
        by as f64 + 1.0
    }

    /// PathNavigationRegion.noCollision(entity, box) equivalent: block
    /// collision shapes only (the region has no entities; the world border is
    /// far). Returns true when the box collides with nothing.
    ///
    /// Vanilla iterates floor(min - 1e-7) - 1 ..= floor(max + 1e-7) + 1 per
    /// axis and joins shapes with an ~1e-7-lenient voxel merge; we replicate
    /// with an overlap > 1e-7 AABB test per recorded box.
    pub fn no_collision(&mut self, bb: &[f64; 6]) -> bool {
        const EPS: f64 = 1.0e-7;
        let cx0 = (bb[0] - EPS).floor() as i32 - 1;
        let cy0 = (bb[1] - EPS).floor() as i32 - 1;
        let cz0 = (bb[2] - EPS).floor() as i32 - 1;
        let cx1 = (bb[3] + EPS).floor() as i32 + 1;
        let cy1 = (bb[4] + EPS).floor() as i32 + 1;
        let cz1 = (bb[5] + EPS).floor() as i32 + 1;
        for y in cy0..=cy1 {
            for z in cz0..=cz1 {
                for x in cx0..=cx1 {
                    let gi = match self.idx(x, y, z) {
                        Some(gi) => gi,
                        None => {
                            self.misses += 1;
                            continue;
                        }
                    };
                    if Self::bit(&self.empty, gi) {
                        continue;
                    }
                    let cell = [x as f64, y as f64, z as f64];
                    if let Some(boxes) = self.shapes.get(&(gi as i32)) {
                        for b in boxes {
                            if intersects(
                                bb,
                                &[
                                    cell[0] + b[0],
                                    cell[1] + b[1],
                                    cell[2] + b[2],
                                    cell[0] + b[3],
                                    cell[1] + b[4],
                                    cell[2] + b[5],
                                ],
                            ) {
                                return false;
                            }
                        }
                    } else if intersects(
                        bb,
                        &[
                            cell[0],
                            cell[1],
                            cell[2],
                            cell[0] + 1.0,
                            cell[1] + 1.0,
                            cell[2] + 1.0,
                        ],
                    ) {
                        return false;
                    }
                }
            }
        }
        true
    }
}

#[inline]
fn intersects(a: &[f64; 6], b: &[f64; 6]) -> bool {
    const EPS: f64 = 1.0e-7;
    a[3].min(b[3]) - a[0].max(b[0]) > EPS
        && a[4].min(b[4]) - a[1].max(b[1]) > EPS
        && a[5].min(b[5]) - a[2].max(b[2]) > EPS
}
