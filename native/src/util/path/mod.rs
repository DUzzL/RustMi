// Vanilla-exact pathfinding kernel — P1 of the pathfinding campaign
// (project_rust_pathfinding). Ports PathFinder.findPath's A* core verbatim
// from the decompiled 26.1.2 source (/tmp/vansrc PathNodeNavigator +
// PathMinHeap + PathNode): exact f32 math, exact heap sift semantics, the
// start-node heuristic WITHOUT the 1.5 multiplier (neighbors get ×1.5),
// followRange gates, partial-path best-node selection.
//
// The node EVALUATOR (WalkNodeEvaluator port: getStart, successors,
// findAcceptedNode over the fixture's static PathType grid + sparse floor
// levels) plugs in via the `Evaluator` trait — `fixtures/path/*.json` are
// the bit-exact arbiter recorded from live vanilla.
//
// Zero-alloc discipline: nodes live in a flat Vec arena keyed by packed
// coords; the heap holds arena indices; per-search buffers are reused via
// `Search::clear` (thread-local at the FFI layer, one per region worker).

pub mod walk;
pub mod world;

#[cfg(test)]
mod fixture_tests;

pub const MAX_SUCCESSORS: usize = 32;

#[derive(Clone, Copy)]
pub struct Node {
    pub x: i32,
    pub y: i32,
    pub z: i32,
    /// g with penalties (vanilla penalizedPathLength).
    pub penalized: f32,
    /// raw path length (vanilla pathLength) — followRange gate.
    pub path_len: f32,
    /// h to nearest target (vanilla distanceToNearestTarget).
    pub h: f32,
    /// heap ordering key (vanilla heapWeight).
    pub heap_weight: f32,
    /// node penalty (malus) applied on (re)expansion.
    pub penalty: f32,
    pub previous: i32,   // arena index or -1
    pub heap_index: i32, // -1 = not in heap (vanilla isInHeap)
    /// Set on pop by the A* loop AND by the evaluator's blocked-node
    /// constructor; both gate successor validity (vanilla PathNode.visited).
    pub visited: bool,
    /// vanilla PathNode.type, ordinal (initial = BLOCKED = 0).
    pub node_type: u8,
}

impl Node {
    pub fn new(x: i32, y: i32, z: i32) -> Self {
        Node {
            x,
            y,
            z,
            penalized: 0.0,
            path_len: 0.0,
            h: 0.0,
            heap_weight: 0.0,
            penalty: 0.0,
            previous: -1,
            heap_index: -1,
            visited: false,
            node_type: 0,
        }
    }

    #[inline]
    pub fn distance(&self, o: &Node) -> f32 {
        // PathNode.getDistance — f32 throughout, MathHelper.sqrt = (float)Math.sqrt(double)
        let xd = (o.x - self.x) as f32;
        let yd = (o.y - self.y) as f32;
        let zd = (o.z - self.z) as f32;
        ((xd * xd + yd * yd + zd * zd) as f64).sqrt() as f32
    }

    #[inline]
    pub fn manhattan(&self, o: &Node) -> f32 {
        (o.x - self.x).abs() as f32 + (o.y - self.y).abs() as f32 + (o.z - self.z).abs() as f32
    }
}

/// Exact port of PathMinHeap: identical sift comparisons (strict `<`),
/// identical parent/child index math — the tie-breaking heart of vanilla.
pub struct MinHeap {
    items: Vec<i32>, // arena indices
}

impl MinHeap {
    pub fn new() -> Self {
        MinHeap {
            items: Vec::with_capacity(128),
        }
    }

    pub fn clear(&mut self) {
        self.items.clear();
    }

    pub fn is_empty(&self) -> bool {
        self.items.is_empty()
    }

    pub fn push(&mut self, arena: &mut [Node], idx: i32) {
        let slot = self.items.len();
        self.items.push(idx);
        arena[idx as usize].heap_index = slot as i32;
        self.shift_up(arena, slot);
    }

    pub fn pop(&mut self, arena: &mut [Node]) -> i32 {
        let popped = self.items[0];
        let last = self.items.pop().unwrap();
        if !self.items.is_empty() {
            self.items[0] = last;
            arena[last as usize].heap_index = 0;
            self.shift_down(arena, 0);
        }
        arena[popped as usize].heap_index = -1;
        popped
    }

    pub fn set_weight(&mut self, arena: &mut [Node], idx: i32, weight: f32) {
        let old = arena[idx as usize].heap_weight;
        arena[idx as usize].heap_weight = weight;
        let hi = arena[idx as usize].heap_index as usize;
        if weight < old {
            self.shift_up(arena, hi);
        } else {
            self.shift_down(arena, hi);
        }
    }

    fn shift_up(&mut self, arena: &mut [Node], mut index: usize) {
        let node = self.items[index];
        let cost = arena[node as usize].heap_weight;
        while index > 0 {
            let parent_idx = (index - 1) >> 1;
            let parent = self.items[parent_idx];
            if !(cost < arena[parent as usize].heap_weight) {
                break;
            }
            self.items[index] = parent;
            arena[parent as usize].heap_index = index as i32;
            index = parent_idx;
        }
        self.items[index] = node;
        arena[node as usize].heap_index = index as i32;
    }

    fn shift_down(&mut self, arena: &mut [Node], mut index: usize) {
        let node = self.items[index];
        let cost = arena[node as usize].heap_weight;
        loop {
            let left = 1 + (index << 1);
            let right = left + 1;
            if left >= self.items.len() {
                break;
            }
            let (best, best_cost) = if right >= self.items.len() {
                (left, arena[self.items[left] as usize].heap_weight)
            } else {
                let lc = arena[self.items[left] as usize].heap_weight;
                let rc = arena[self.items[right] as usize].heap_weight;
                // vanilla: left < right ? left : right (STRICT — ties pick RIGHT? no:
                // vanilla picks left when leftCost < rightCost, else right)
                if lc < rc {
                    (left, lc)
                } else {
                    (right, rc)
                }
            };
            if !(best_cost < cost) {
                break;
            }
            let child = self.items[best];
            self.items[index] = child;
            arena[child as usize].heap_index = index as i32;
            index = best;
        }
        self.items[index] = node;
        arena[node as usize].heap_index = index as i32;
    }
}

/// Per-target tracking (vanilla TargetPathNode): nearest examined node.
pub struct Target {
    pub node: Node,             // target as a node (евалуатор может смещать!)
    pub block: (i32, i32, i32), // original BlockPos
    pub best_h: f32,
    pub best_node: i32,
    pub reached: bool,
}

/// The evaluator contract — WalkNodeEvaluator port supplies these.
pub trait Evaluator {
    /// Vanilla getStart: arena index of the start node.
    fn start(&mut self, arena: &mut Vec<Node>) -> Option<i32>;
    /// Vanilla createNode for a target pos: floors coords AND registers the
    /// plain node in the evaluator's node cache (vanilla side effect — the
    /// TargetPathNode wraps a node that entered pathNodeCache).
    fn target_node(&mut self, arena: &mut Vec<Node>, x: f64, y: f64, z: f64) -> Node;
    /// Vanilla getSuccessors: fill `out`, return count. Nodes are arena indices.
    fn successors(
        &mut self,
        arena: &mut Vec<Node>,
        current: i32,
        out: &mut [i32; MAX_SUCCESSORS],
    ) -> usize;
}

pub struct PathResult {
    pub nodes: Vec<(i32, i32, i32)>,
    pub reached: bool,
    pub target: (i32, i32, i32),
}

/// Exact port of PathNodeNavigator.findPathToAny (the A* core).
pub fn find_path<E: Evaluator>(
    eval: &mut E,
    arena: &mut Vec<Node>,
    targets_in: &[(i32, i32, i32)],
    follow_range: f32,
    accuracy: i32,
    range_multiplier: f32,
    max_visited_base: i32,
) -> Option<PathResult> {
    let start_idx = eval.start(arena)?;

    let mut targets: Vec<Target> = targets_in
        .iter()
        .map(|&(x, y, z)| Target {
            node: eval.target_node(arena, x as f64, y as f64, z as f64),
            block: (x, y, z),
            best_h: f32::MAX,
            best_node: -1,
            reached: false,
        })
        .collect();

    let mut heap = MinHeap::new();

    // startNode.distanceToNearestTarget = calculateDistances(start) — NO ×1.5!
    let h0 = calculate_distances(arena, start_idx, &mut targets);
    {
        let s = &mut arena[start_idx as usize];
        s.penalized = 0.0;
        s.h = h0;
        s.heap_weight = h0;
    }
    heap.push(arena, start_idx);

    let mut count = 0;
    let max_visited = (max_visited_base as f32 * range_multiplier) as i32;
    let mut any_reached = false;

    let mut successors = [0i32; MAX_SUCCESSORS];

    while !heap.is_empty() {
        count += 1;
        if count >= max_visited {
            break;
        }
        let current = heap.pop(arena);
        arena[current as usize].visited = true;

        for t in targets.iter_mut() {
            let man = {
                let c = &arena[current as usize];
                (t.node.x - c.x).abs() as f32
                    + (t.node.y - c.y).abs() as f32
                    + (t.node.z - c.z).abs() as f32
            };
            if man <= accuracy as f32 {
                t.reached = true;
                any_reached = true;
            }
        }
        if any_reached {
            break;
        }

        let dist_to_start = arena[current as usize].distance(&arena[start_idx as usize]);
        if dist_to_start >= follow_range {
            continue;
        }

        let n = eval.successors(arena, current, &mut successors);
        for &nb in successors.iter().take(n) {
            let d = arena[current as usize].distance(&arena[nb as usize]);
            // Vanilla quirk (PathNodeNavigator L109): pathLength is assigned
            // UNCONDITIONALLY before the relaxation gate — a worse path still
            // overwrites the neighbor's pathLength. Bit-exactness requires it.
            arena[nb as usize].path_len = arena[current as usize].path_len + d;
            let tentative = arena[current as usize].penalized + d + arena[nb as usize].penalty;
            let in_heap = arena[nb as usize].heap_index >= 0;
            if arena[nb as usize].path_len < follow_range
                && (!in_heap || tentative < arena[nb as usize].penalized)
            {
                arena[nb as usize].previous = current;
                arena[nb as usize].penalized = tentative;
                let h = calculate_distances(arena, nb, &mut targets) * 1.5;
                arena[nb as usize].h = h;
                if in_heap {
                    let w = arena[nb as usize].penalized + h;
                    heap.set_weight(arena, nb, w);
                } else {
                    arena[nb as usize].heap_weight = arena[nb as usize].penalized + h;
                    heap.push(arena, nb);
                }
            }
        }
    }

    // Path selection: reached → min by length; else min by (manhattanFromTarget, length).
    let mut best: Option<(Vec<(i32, i32, i32)>, bool, (i32, i32, i32), f32)> = None;
    let reached_any = targets.iter().any(|t| t.reached);
    for t in targets.iter() {
        if reached_any && !t.reached {
            continue;
        }
        if t.best_node < 0 {
            continue;
        }
        let nodes = reconstruct(arena, t.best_node);
        let man_from_target = {
            let end = nodes.last().unwrap();
            (t.block.0 - end.0).abs() as f32
                + (t.block.1 - end.1).abs() as f32
                + (t.block.2 - end.2).abs() as f32
        };
        let better = match &best {
            None => true,
            Some((bn, _, _, bman)) => {
                if reached_any {
                    nodes.len() < bn.len()
                } else {
                    man_from_target < *bman || (man_from_target == *bman && nodes.len() < bn.len())
                }
            }
        };
        if better {
            best = Some((nodes, t.reached, t.block, man_from_target));
        }
    }
    best.map(|(nodes, reached, target, _)| PathResult {
        nodes,
        reached,
        target,
    })
}

fn calculate_distances(arena: &[Node], idx: i32, targets: &mut [Target]) -> f32 {
    let mut best = f32::MAX;
    for t in targets.iter_mut() {
        let h = arena[idx as usize].distance(&t.node);
        // TargetPathNode.updateNearestNode(h, node)
        if h < t.best_h {
            t.best_h = h;
            t.best_node = idx;
        }
        best = best.min(h);
    }
    best
}

fn reconstruct(arena: &[Node], end: i32) -> Vec<(i32, i32, i32)> {
    let mut out = Vec::new();
    let mut cur = end;
    while cur >= 0 {
        let n = &arena[cur as usize];
        out.push((n.x, n.y, n.z));
        cur = n.previous;
    }
    out.reverse();
    out
}
