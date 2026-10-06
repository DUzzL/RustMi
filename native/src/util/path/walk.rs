// WalkNodeEvaluator port (yarn LandPathNodeMaker, 26.1.2 decompile in
// /tmp/vansrc; re-checked against 26.3) over the recorded fixture world —
// line-for-line semantics:
// getStart's four-way start-Y resolution + bbox-corner fallback,
// getSuccessors' cardinal(N,E,S,W)+diagonal generation with the
// quarter-turn-indexed scratch array, getPathNode's step/jump recursion,
// getOpenNode's fall scan, the per-mob bbox type aggregation with EnumSet
// ordinal iteration, and the two box-collision checks (fence squeeze sweep,
// width<1 jump clearance) against recorded shapes.
//
// f32/f64 boundaries follow the Java source exactly (e.g. the sweep step
// scale is the FLOAT quotient 1.0f/steps widened to double).

use std::collections::HashMap;

use super::world::*;
use super::{Evaluator, Node, MAX_SUCCESSORS};

pub struct MobSpec {
    pub pos: [f64; 3],
    pub block_pos: [i32; 3],
    pub bb_width: f32,
    pub bb_height: f32,
    pub malus: [f32; TYPE_COUNT],
    pub can_pass_doors: bool,
    pub can_open_doors: bool,
    pub can_float: bool,
    pub can_walk_over_fences: bool,
    pub on_ground: bool,
    /// Entity.isInFloatableFluid() (26.3; recorded under the v3 `inWater` key).
    pub in_floatable_fluid: bool,
    pub step_height: f32,
    pub max_fall_distance: i32,
}

impl MobSpec {
    #[inline]
    fn penalty(&self, t: u8) -> f32 {
        self.malus[t as usize]
    }

    fn bb(&self) -> [f64; 6] {
        let w = self.bb_width as f64 / 2.0;
        [
            self.pos[0] - w,
            self.pos[1],
            self.pos[2] - w,
            self.pos[0] + w,
            self.pos[1] + self.bb_height as f64,
            self.pos[2] + w,
        ]
    }
}

// Direction.Type.HORIZONTAL iteration order: N, E, S, W.
const DIRS: [(i32, i32); 4] = [(0, -1), (1, 0), (0, 1), (-1, 0)];
// getHorizontalQuarterTurns per DIRS entry (S=0, W=1, N=2, E=3).
const QT: [usize; 4] = [2, 3, 0, 1];
// rotateYClockwise per DIRS entry: N→E, E→S, S→W, W→N (as DIRS indices).
const CLOCKWISE: [usize; 4] = [1, 2, 3, 0];

pub struct WalkEvaluator<'w> {
    pub world: &'w mut World,
    pub mob: MobSpec,
    entity_x: i32,
    entity_y: i32,
    entity_z: i32,
    nodes: HashMap<(i32, i32, i32), i32>,
    type_cache: HashMap<(i32, i32, i32), u8>,
    collided: HashMap<[u64; 6], bool>,
    scratch: [i32; 4], // successors[] indexed by quarter turns; -1 = null
}

impl<'w> WalkEvaluator<'w> {
    pub fn new(world: &'w mut World, mob: MobSpec) -> Self {
        let ex = (mob.bb_width + 1.0).floor() as i32;
        let ey = (mob.bb_height + 1.0).floor() as i32;
        WalkEvaluator {
            world,
            mob,
            entity_x: ex,
            entity_y: ey,
            entity_z: ex,
            nodes: HashMap::new(),
            type_cache: HashMap::new(),
            collided: HashMap::new(),
            scratch: [-1; 4],
        }
    }

    fn get_node(&mut self, arena: &mut Vec<Node>, x: i32, y: i32, z: i32) -> i32 {
        *self.nodes.entry((x, y, z)).or_insert_with(|| {
            arena.push(Node::new(x, y, z));
            (arena.len() - 1) as i32
        })
    }

    /// getNodeType(context, x, y, z, mob) — per-mob aggregated type, cached.
    fn node_type_mob(&mut self, x: i32, y: i32, z: i32) -> u8 {
        if let Some(&t) = self.type_cache.get(&(x, y, z)) {
            return t;
        }
        let t = self.compute_node_type_mob(x, y, z);
        self.type_cache.insert((x, y, z), t);
        t
    }

    fn compute_node_type_mob(&mut self, x: i32, y: i32, z: i32) -> u8 {
        // getCollidingNodeTypes: EnumSet over the entity-sized bbox sweep.
        let mut set: u32 = 0;
        let mob_pos = self.mob.block_pos;
        let mob_on_rail = self.world.type_at(mob_pos[0], mob_pos[1], mob_pos[2]) == T_RAIL
            || self.world.type_at(mob_pos[0], mob_pos[1] - 1, mob_pos[2]) == T_RAIL;
        for dx in 0..self.entity_x {
            for dy in 0..self.entity_y {
                for dz in 0..self.entity_z {
                    let mut t = self.world.type_at(dx + x, dy + y, dz + z);
                    if t == T_DOOR_WOOD_CLOSED && self.mob.can_open_doors && self.mob.can_pass_doors
                    {
                        t = T_WALKABLE_DOOR;
                    }
                    if t == T_DOOR_OPEN && !self.mob.can_pass_doors {
                        t = T_BLOCKED;
                    }
                    if t == T_RAIL && !mob_on_rail {
                        t = T_UNPASSABLE_RAIL;
                    }
                    set |= 1u32 << t;
                }
            }
        }

        if set.count_ones() == 1 {
            return set.trailing_zeros() as u8;
        }
        if set & (1 << T_FENCE) != 0 {
            return T_FENCE;
        }
        if set & (1 << T_UNPASSABLE_RAIL) != 0 {
            return T_UNPASSABLE_RAIL;
        }
        let mut best = T_BLOCKED;
        let mut best_malus = self.mob.penalty(T_BLOCKED);
        // EnumSet iteration = ordinal ascending.
        for t in 0..TYPE_COUNT as u8 {
            if set & (1u32 << t) == 0 {
                continue;
            }
            let m = self.mob.penalty(t);
            if m < 0.0 {
                return t;
            }
            if m >= best_malus {
                best_malus = m;
                best = t;
            }
        }
        let current = self.world.type_at(x, y, z);
        let is_large = self.entity_x > 1;
        if is_large {
            let current_cheaper = self.mob.penalty(current) < best_malus;
            let cap = current_cheaper && self.mob.penalty(T_BIG_MOBS_CLOSE_TO_DANGER) < best_malus;
            if cap {
                T_BIG_MOBS_CLOSE_TO_DANGER
            } else {
                best
            }
        } else if current == T_OPEN && best != T_OPEN && best_malus == 0.0 {
            T_OPEN
        } else {
            best
        }
    }

    /// Instance getFeetY: swim branch + static floor.
    fn feet_y(&mut self, x: i32, y: i32, z: i32) -> f64 {
        // (canFloat() || isAmphibious()) && fluid in #entity_floatable → y + 0.5
        if self.mob.can_float && self.world.floatable_at(x, y, z) {
            return y as f64 + 0.5;
        }
        self.world.static_feet_y(x, y, z)
    }

    /// max(1.125, entity.getStepHeight()) — double math (getPathNode gate).
    fn step_height_d(&self) -> f64 {
        (1.125f64).max(self.mob.step_height as f64)
    }

    fn is_blocked_type(t: u8) -> bool {
        t == T_FENCE || t == T_DOOR_WOOD_CLOSED || t == T_DOOR_IRON_CLOSED
    }

    /// Instance isBlocked(PathNode) — TRUE means the swept bb fits (vanilla
    /// returns true when no step collides; the caller treats false as "can't
    /// squeeze through").
    fn sweep_fits(&mut self, arena: &[Node], node: i32) -> bool {
        let n = &arena[node as usize];
        let bb = self.mob.bb();
        let lx = bb[3] - bb[0];
        let ly = bb[4] - bb[1];
        let lz = bb[5] - bb[2];
        let mut d = [
            n.x as f64 - self.mob.pos[0] + lx / 2.0,
            n.y as f64 - self.mob.pos[1] + ly / 2.0,
            n.z as f64 - self.mob.pos[2] + lz / 2.0,
        ];
        let avg = (lx + ly + lz) / 3.0;
        let len = (d[0] * d[0] + d[1] * d[1] + d[2] * d[2]).sqrt();
        let steps = (len / avg).ceil() as i32;
        // vanilla: delta.multiply(1.0F / steps) — float quotient, widened.
        let scale = (1.0f32 / steps as f32) as f64;
        d = [d[0] * scale, d[1] * scale, d[2] * scale];
        let mut cur = bb;
        for _ in 1..=steps {
            cur = [
                cur[0] + d[0],
                cur[1] + d[1],
                cur[2] + d[2],
                cur[3] + d[0],
                cur[4] + d[1],
                cur[5] + d[2],
            ];
            if self.check_box_collision(&cur) {
                return false;
            }
        }
        true
    }

    /// checkBoxCollision: memoized !isSpaceEmpty(entity, box).
    fn check_box_collision(&mut self, bb: &[f64; 6]) -> bool {
        let key = [
            bb[0].to_bits(),
            bb[1].to_bits(),
            bb[2].to_bits(),
            bb[3].to_bits(),
            bb[4].to_bits(),
            bb[5].to_bits(),
        ];
        if let Some(&v) = self.collided.get(&key) {
            return v;
        }
        let v = !self.world.no_collision(bb);
        self.collided.insert(key, v);
        v
    }

    fn get_node_with(
        &mut self,
        arena: &mut Vec<Node>,
        x: i32,
        y: i32,
        z: i32,
        t: u8,
        penalty: f32,
    ) -> i32 {
        let idx = self.get_node(arena, x, y, z);
        let n = &mut arena[idx as usize];
        n.node_type = t;
        n.penalty = n.penalty.max(penalty);
        idx
    }

    fn get_node_with_default(
        &mut self,
        arena: &mut Vec<Node>,
        x: i32,
        y: i32,
        z: i32,
        t: u8,
    ) -> i32 {
        let idx = self.get_node(arena, x, y, z);
        let n = &mut arena[idx as usize];
        n.visited = true;
        n.node_type = t;
        n.penalty = DEFAULT_PENALTY[t as usize];
        idx
    }

    fn get_blocked_node(&mut self, arena: &mut Vec<Node>, x: i32, y: i32, z: i32) -> i32 {
        let idx = self.get_node(arena, x, y, z);
        let n = &mut arena[idx as usize];
        n.node_type = T_BLOCKED;
        n.penalty = -1.0;
        idx
    }

    /// canPathThrough: type != OPEN && malus >= 0.
    fn can_path_through(&mut self, x: i32, y: i32, z: i32) -> bool {
        let t = self.node_type_mob(x, y, z);
        t != T_OPEN && self.mob.penalty(t) >= 0.0
    }

    fn get_start_at(&mut self, arena: &mut Vec<Node>, x: i32, y: i32, z: i32) -> i32 {
        let idx = self.get_node(arena, x, y, z);
        let t = self.node_type_mob(x, y, z);
        let p = self.mob.penalty(t);
        let n = &mut arena[idx as usize];
        n.node_type = t;
        n.penalty = p;
        idx
    }

    /// getPathNode — the per-direction step/jump/fall resolver. Returns the
    /// arena index or -1 (vanilla null).
    #[allow(clippy::too_many_arguments)]
    fn get_path_node(
        &mut self,
        arena: &mut Vec<Node>,
        x: i32,
        y: i32,
        z: i32,
        max_y_step: i32,
        last_feet_y: f64,
        dir: usize,
        node_type: u8,
    ) -> i32 {
        let mut best: i32 = -1;
        let max_y_target = self.feet_y(x, y, z);
        if max_y_target - last_feet_y > self.step_height_d() {
            return -1;
        }
        let path_type = self.node_type_mob(x, y, z);
        let path_cost = self.mob.penalty(path_type);
        if path_cost >= 0.0 {
            best = self.get_node_with(arena, x, y, z, path_type, path_cost);
        }

        if Self::is_blocked_type(node_type)
            && best >= 0
            && arena[best as usize].penalty >= 0.0
            && !self.sweep_fits(arena, best)
        {
            best = -1;
        }

        if path_type == T_WALKABLE {
            return best;
        }
        // (isAmphibious() is false for WalkNodeEvaluator — the WATER
        // amphibious early-return never fires.)

        if (best < 0 || arena[best as usize].penalty < 0.0)
            && max_y_step > 0
            && (path_type != T_FENCE || self.mob.can_walk_over_fences)
            && path_type != T_UNPASSABLE_RAIL
            && path_type != T_TRAPDOOR
            && path_type != T_POWDER_SNOW
        {
            best =
                self.get_jump_on_top_node(arena, x, y, z, max_y_step, last_feet_y, dir, node_type);
        } else if path_type == T_WATER && !self.mob.can_float {
            best = self.get_non_water_node_below(arena, x, y, z, best);
        } else if path_type == T_OPEN {
            best = self.get_open_node(arena, x, y, z);
        } else if Self::is_blocked_type(path_type) && best < 0 {
            best = self.get_node_with_default(arena, x, y, z, path_type);
        }
        best
    }

    #[allow(clippy::too_many_arguments)]
    fn get_jump_on_top_node(
        &mut self,
        arena: &mut Vec<Node>,
        x: i32,
        y: i32,
        z: i32,
        max_y_step: i32,
        last_feet_y: f64,
        dir: usize,
        node_type: u8,
    ) -> i32 {
        let above = self.get_path_node(
            arena,
            x,
            y + 1,
            z,
            max_y_step - 1,
            last_feet_y,
            dir,
            node_type,
        );
        if above < 0 {
            return -1;
        }
        if self.mob.bb_width >= 1.0 {
            return above;
        }
        let at = arena[above as usize].node_type;
        if at != T_OPEN && at != T_WALKABLE {
            return above;
        }
        // width < 1: clearance box between source-cell center and the landing.
        let (dx, dz) = DIRS[dir];
        let center_x = (x - dx) as f64 + 0.5;
        let center_z = (z - dz) as f64 + 0.5;
        let half = self.mob.bb_width as f64 / 2.0;
        let feet_src = self.feet_y(center_x.floor() as i32, y + 1, center_z.floor() as i32) + 0.001;
        let (ax, ay, az) = {
            let a = &arena[above as usize];
            (a.x, a.y, a.z)
        };
        let feet_dst = self.feet_y(ax, ay, az);
        let bb = [
            center_x - half,
            feet_src,
            center_z - half,
            center_x + half,
            self.mob.bb_height as f64 + feet_dst - 0.002,
            center_z + half,
        ];
        if self.check_box_collision(&bb) {
            -1
        } else {
            above
        }
    }

    fn get_non_water_node_below(
        &mut self,
        arena: &mut Vec<Node>,
        x: i32,
        y: i32,
        z: i32,
        node_in: i32,
    ) -> i32 {
        let mut node = node_in;
        let mut y = y - 1;
        while y > self.world.min_y {
            let t = self.node_type_mob(x, y, z);
            if t != T_WATER {
                return node;
            }
            let p = self.mob.penalty(t);
            node = self.get_node_with(arena, x, y, z, t, p);
            y -= 1;
        }
        node
    }

    fn get_open_node(&mut self, arena: &mut Vec<Node>, x: i32, y: i32, z: i32) -> i32 {
        let mut current_y = y - 1;
        while current_y >= self.world.min_y {
            if y - current_y > self.mob.max_fall_distance {
                return self.get_blocked_node(arena, x, current_y, z);
            }
            let t = self.node_type_mob(x, current_y, z);
            let cost = self.mob.penalty(t);
            if t != T_OPEN {
                if cost >= 0.0 {
                    return self.get_node_with(arena, x, current_y, z, t, cost);
                }
                return self.get_blocked_node(arena, x, current_y, z);
            }
            current_y -= 1;
        }
        self.get_blocked_node(arena, x, y, z)
    }

    fn is_valid_adjacent(&self, arena: &[Node], node: i32, current: i32) -> bool {
        if node < 0 {
            return false;
        }
        let n = &arena[node as usize];
        !n.visited && (n.penalty >= 0.0 || arena[current as usize].penalty < 0.0)
    }

    /// isValidDiagonalSuccessor(xNode=current, zNode, xDiagNode) — the
    /// two-flank gate.
    fn diagonal_flanks_ok(&self, arena: &[Node], current: i32, z_node: i32, x_diag: i32) -> bool {
        if x_diag < 0 || z_node < 0 {
            return false;
        }
        let cur_y = arena[current as usize].y;
        let xd = &arena[x_diag as usize];
        let zn = &arena[z_node as usize];
        if xd.y > cur_y || zn.y > cur_y {
            return false;
        }
        if zn.node_type == T_WALKABLE_DOOR || xd.node_type == T_WALKABLE_DOOR {
            return false;
        }
        if self.mob.bb_width > 1.0 && (zn.penalty > 0.0 || xd.penalty > 0.0) {
            return false;
        }
        let between_posts =
            xd.node_type == T_FENCE && zn.node_type == T_FENCE && (self.mob.bb_width as f64) < 0.5;
        (xd.y < cur_y || xd.penalty >= 0.0 || between_posts)
            && (zn.y < cur_y || zn.penalty >= 0.0 || between_posts)
    }

    fn diagonal_node_ok(&self, arena: &[Node], node: i32) -> bool {
        if node < 0 {
            return false;
        }
        let n = &arena[node as usize];
        if n.visited {
            return false;
        }
        n.node_type != T_WALKABLE_DOOR && n.penalty >= 0.0
    }
}

impl<'w> Evaluator for WalkEvaluator<'w> {
    fn start(&mut self, arena: &mut Vec<Node>) -> Option<i32> {
        let fx = self.mob.pos[0].floor() as i32;
        let fz = self.mob.pos[2].floor() as i32;
        let mut start_y = self.mob.pos[1].floor() as i32; // getBlockY

        // (canWalkOnFluid branch is excluded at fixture-selection time.)
        if self.mob.can_float && self.mob.in_floatable_fluid {
            // 26.3: while (fluid in #entity_floatable) y++; then y-1.
            while self.world.floatable_at(fx, start_y, fz) {
                start_y += 1;
            }
            start_y -= 1;
        } else if self.mob.on_ground {
            start_y = (self.mob.pos[1] + 0.5).floor() as i32;
        } else {
            // Fall scan from y+1 down to the first non-passable block.
            let mut cursor_y = (self.mob.pos[1] + 1.0).floor() as i32;
            while cursor_y > self.world.min_y {
                start_y = cursor_y;
                cursor_y -= 1;
                let air = self.world.air_at(fx, cursor_y, fz);
                let pass = self.world.pass_land_at(fx, cursor_y, fz);
                if !air && !pass {
                    break;
                }
            }
        }

        let (bx, bz) = (self.mob.block_pos[0], self.mob.block_pos[2]);
        if !self.can_path_through(bx, start_y, bz) {
            let bb = self.mob.bb();
            let corners = [
                (bb[0].floor() as i32, bb[2].floor() as i32),
                (bb[0].floor() as i32, bb[5].floor() as i32),
                (bb[3].floor() as i32, bb[2].floor() as i32),
                (bb[3].floor() as i32, bb[5].floor() as i32),
            ];
            for (cx, cz) in corners {
                if self.can_path_through(cx, start_y, cz) {
                    return Some(self.get_start_at(arena, cx, start_y, cz));
                }
            }
        }
        Some(self.get_start_at(arena, bx, start_y, bz))
    }

    fn target_node(&mut self, arena: &mut Vec<Node>, x: f64, y: f64, z: f64) -> Node {
        let idx = self.get_node(arena, x.floor() as i32, y.floor() as i32, z.floor() as i32);
        arena[idx as usize]
    }

    fn successors(
        &mut self,
        arena: &mut Vec<Node>,
        current: i32,
        out: &mut [i32; MAX_SUCCESSORS],
    ) -> usize {
        let (cx, cy, cz) = {
            let c = &arena[current as usize];
            (c.x, c.y, c.z)
        };
        let mut p = 0usize;
        let mut jump_size = 0i32;
        let above = self.node_type_mob(cx, cy + 1, cz);
        let cur_type = self.node_type_mob(cx, cy, cz);
        if self.mob.penalty(above) >= 0.0 && cur_type != T_STICKY_HONEY {
            // floor(max(1.0F, getStepHeight())) — float max.
            jump_size = (1.0f32.max(self.mob.step_height)).floor() as i32;
        }
        let pos_height = self.feet_y(cx, cy, cz);

        self.scratch = [-1; 4];
        for d in 0..4 {
            let (dx, dz) = DIRS[d];
            let node = self.get_path_node(
                arena,
                cx + dx,
                cy,
                cz + dz,
                jump_size,
                pos_height,
                d,
                cur_type,
            );
            self.scratch[QT[d]] = node;
            if self.is_valid_adjacent(arena, node, current) {
                out[p] = node;
                p += 1;
            }
        }

        for d in 0..4 {
            let second = CLOCKWISE[d];
            if self.diagonal_flanks_ok(
                arena,
                current,
                self.scratch[QT[d]],
                self.scratch[QT[second]],
            ) {
                let (dx1, dz1) = DIRS[d];
                let (dx2, dz2) = DIRS[second];
                let diag = self.get_path_node(
                    arena,
                    cx + dx1 + dx2,
                    cy,
                    cz + dz1 + dz2,
                    jump_size,
                    pos_height,
                    d,
                    cur_type,
                );
                if self.diagonal_node_ok(arena, diag) {
                    out[p] = diag;
                    p += 1;
                }
            }
        }
        p
    }
}
