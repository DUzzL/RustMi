//! CompileGraph → runtime Nodes/ForwardLinks lowering pass — adapted port
//! of MCHPRS `crates/redpiler/src/backend/direct/compile.rs`.
//!
//! Adapter changes from upstream:
//!   * `FxHashMap` → `std::collections::HashMap` (fewer deps; the map is
//!     built once per compile, microsecond cost lost in the noise).
//!   * `Block::from_id` resolution dropped — Rustmi keeps the host-side
//!     block-id as an opaque `u32` and resolves it on flush in Java, so
//!     `backend.blocks` holds the `(Pos, u32)` pairs verbatim.
//!   * `TickEntry`-driven pre-scheduling lifted out of `compile` — the host
//!     reinstates scheduled ticks via a separate FFI call after install, so
//!     compile is pure graph→Nodes.
//!   * `pos_map` rebuilt here (HashMap<Pos, NodeId>) — Java drives input
//!     toggles by position, lookup needs the same map.
//!   * `itertools::sorted_by_key`/`into_group_map_by` replaced with a manual
//!     two-step sort using `std::mem::discriminant` keys — same semantics,
//!     no dep on itertools.
//!   * `CompilerOptions::export_dot_graph` dropped — debug-only feature,
//!     bring back when Graphviz inspection is wanted.
//!   * `Arc<TaskMonitor>` dropped — host runs compile synchronously off the
//!     hot path (one-shot per network rebuild).

use std::collections::HashMap;

use super::backend::DirectBackend;
use super::compile_graph::{CompileGraph, Direction, LinkType, NodeIdx, NodeType as CNodeType};
use super::node::{ForwardLink, ForwardLinks, Node, NodeId, NodeInput, NodeType, Nodes, NonMaxU8};
use crate::util::redstone::Pos;

#[derive(Debug, Default)]
pub struct CompileStats {
    pub update_link_count: usize,
    pub side_link_count: usize,
    pub default_link_count: usize,
    pub nodes_bytes: usize,
}

const MAX_INPUTS_PER_SIDE: usize = 255;

fn compile_node(
    graph: &CompileGraph,
    node_idx: NodeIdx,
    nodes_len: usize,
    nodes_map: &HashMap<NodeIdx, usize>,
    forward_links: &mut ForwardLinks,
    stats: &mut CompileStats,
) -> Node {
    let node = &graph[node_idx];

    let mut default_input_count = 0usize;
    let mut side_input_count = 0usize;

    let mut default_inputs = NodeInput { ss_counts: [0; 16] };
    let mut side_inputs = NodeInput { ss_counts: [0; 16] };

    // Incoming-edge histogram fill — exactly as upstream.
    for edge in graph.edges(node_idx, Direction::Incoming) {
        let weight = edge.weight();
        let distance = weight.ss;
        let source = edge.source();
        let ss = graph[source].state.output_strength.saturating_sub(distance);
        match weight.ty {
            LinkType::Default => {
                if default_input_count >= MAX_INPUTS_PER_SIDE {
                    panic!(
                        "Exceeded the maximum number of default inputs {}",
                        MAX_INPUTS_PER_SIDE
                    );
                }
                default_input_count += 1;
                default_inputs.ss_counts[ss as usize] += 1;
            }
            LinkType::Side => {
                if side_input_count >= MAX_INPUTS_PER_SIDE {
                    panic!(
                        "Exceeded the maximum number of side inputs {}",
                        MAX_INPUTS_PER_SIDE
                    );
                }
                side_input_count += 1;
                side_inputs.ss_counts[ss as usize] += 1;
            }
        }
    }
    stats.default_link_count += default_input_count;
    stats.side_link_count += side_input_count;

    // Pad bucket[0] so the histogram sums to 255 — lets `get_bool_input`
    // become a single equality check (see node.rs/update.rs).
    default_inputs.ss_counts[0] += (MAX_INPUTS_PER_SIDE - default_input_count) as u8;
    side_inputs.ss_counts[0] += (MAX_INPUTS_PER_SIDE - side_input_count) as u8;

    // Outgoing edges → ForwardLinks, grouped by target node type discriminant
    // (matches upstream's `into_group_map_by` ordering — same dispatch
    // locality for the hot `set_node` loop).
    //
    // Upstream MCHPRS drops Constants' forward_links because Constants are
    // immutable (compile-time-baked into downstream histograms). Rustmi
    // uses Constants for two DYNAMIC purposes: comparator container-source
    // readings (set_input each tick when container fullness changes) and
    // boundary inputs (set_input each tick from level.getSignal on the
    // un-modeled blocks at the AABB perimeter — observers, external
    // diodes, etc.). For the dynamic case the forward_links MUST be
    // populated so set_node can update downstream input histograms when
    // the host pushes a new value. For the static case the host never
    // calls set_input on the Constant, so its links sit unused — zero
    // cost. Symptom of the upstream behavior (2026-06-15): claim ran,
    // observers visually toggled, but wires never reflected the change
    // because boundary Constants couldn't reach them.
    let fwd_link_range = {
        let mut out: Vec<_> = graph.edges(node_idx, Direction::Outgoing).collect();
        // Stable sort first by target NodeId (insertion order), then by
        // target type discriminant — same key sequence as upstream's
        // `sorted_by_key` + `into_group_map_by`.
        out.sort_by_key(|edge| nodes_map[&edge.target()]);
        out.sort_by_key(|edge| {
            // discriminant has no stable u64 cast, but std implements
            // Hash → we can stably group by hashing the discriminant.
            // Simpler: bucket by tag ordinal via a match.
            type_ordinal(&graph[edge.target()].ty)
        });
        let new_links = out.into_iter().map(|edge| {
            let target_idx = nodes_map[&edge.target()];
            assert!(target_idx < nodes_len);
            // Safety: bounds checked above.
            let target_id = unsafe { NodeId::from_index(target_idx) };
            let weight = edge.weight();
            ForwardLink::new(target_id, weight.ty == LinkType::Side, weight.ss)
        });
        forward_links.extend(new_links)
    };
    stats.update_link_count += fwd_link_range.len();

    let ty = match &node.ty {
        CNodeType::Repeater {
            delay,
            facing_diode,
        } => NodeType::Repeater {
            delay: *delay,
            facing_diode: *facing_diode,
        },
        CNodeType::Torch => NodeType::Torch,
        CNodeType::Comparator {
            mode,
            far_input,
            facing_diode,
        } => NodeType::Comparator {
            mode: *mode,
            far_input: far_input.and_then(NonMaxU8::new),
            facing_diode: *facing_diode,
        },
        CNodeType::Lamp => NodeType::Lamp,
        CNodeType::Button => NodeType::Button,
        CNodeType::Lever => NodeType::Lever,
        CNodeType::PressurePlate => NodeType::PressurePlate,
        CNodeType::Trapdoor => NodeType::Trapdoor,
        CNodeType::Wire => NodeType::Wire,
        CNodeType::Constant => NodeType::Constant,
        CNodeType::NoteBlock { noteblock_id } => NodeType::NoteBlock {
            noteblock_id: *noteblock_id,
        },
    };

    Node {
        ty,
        default_inputs,
        side_inputs,
        fwd_link_range,
        powered: node.state.powered,
        output_power: node.state.output_strength,
        locked: node.state.repeater_locked,
        pending_tick: false,
        changed: false,
        is_io: node.is_input || node.is_output,
    }
}

/// Same idea as upstream `std::mem::discriminant` group key, but ordered:
/// keeps the hot `set_node` loop's downstream-type dispatch predictable
/// per-network-shape (same network → identical link layout → branch
/// predictor warm).
fn type_ordinal(ty: &CNodeType) -> u8 {
    match ty {
        CNodeType::Repeater { .. } => 0,
        CNodeType::Torch => 1,
        CNodeType::Comparator { .. } => 2,
        CNodeType::Lamp => 3,
        CNodeType::Button => 4,
        CNodeType::Lever => 5,
        CNodeType::PressurePlate => 6,
        CNodeType::Trapdoor => 7,
        CNodeType::Wire => 8,
        CNodeType::Constant => 9,
        CNodeType::NoteBlock { .. } => 10,
    }
}

/// Compile the IR graph into the runtime backend.
///
/// Returns the `(Pos → NodeId)` map the host uses to address inputs
/// (Button/Lever/PressurePlate toggles, comparator container-strength
/// updates) and outputs (final SET dispatches on flush). Also returns the
/// per-node block list so the host can drive `level.setBlock` on flush.
pub fn compile(backend: &mut DirectBackend, graph: CompileGraph) -> CompiledMeta {
    let mut nodes_map: HashMap<NodeIdx, usize> = HashMap::with_capacity(graph.node_count());
    for node in graph.node_indices() {
        nodes_map.insert(node, nodes_map.len());
    }
    let nodes_len = nodes_map.len();

    let mut forward_links = ForwardLinks::default();
    let mut stats = CompileStats::default();

    let nodes_vec: Vec<Node> = graph
        .node_indices()
        .map(|idx| {
            compile_node(
                &graph,
                idx,
                nodes_len,
                &nodes_map,
                &mut forward_links,
                &mut stats,
            )
        })
        .collect();
    stats.nodes_bytes = nodes_len * std::mem::size_of::<Node>();

    // Per-node block list — host resolves block ids back to MC BlockStates
    // on flush (Rustmi NativeSections gives O(1) reads).
    let blocks: Vec<Vec<(Pos, u32)>> = graph
        .all_node_weights()
        .map(|node| node.block.iter().copied().collect())
        .collect();

    // (Pos → NodeId) lookup for host-driven input toggles.
    let mut pos_map: HashMap<Pos, NodeId> = HashMap::new();
    for (i, block_list) in blocks.iter().enumerate() {
        let nid = unsafe { NodeId::from_index(i) };
        for (pos, _) in block_list.iter().copied() {
            pos_map.insert(pos, nid);
        }
    }

    backend.install(Nodes::new(nodes_vec.into_boxed_slice()), forward_links);

    CompiledMeta {
        blocks,
        pos_map,
        stats,
    }
}

/// Side-band data the host needs alongside the lowered backend.
pub struct CompiledMeta {
    /// Per-node block list — index aligns with [`Node`] indices in the
    /// installed [`Nodes`] arena.
    pub blocks: Vec<Vec<(Pos, u32)>>,
    /// Position lookup for input toggles and flush addressing.
    pub pos_map: HashMap<Pos, NodeId>,
    pub stats: CompileStats,
}
