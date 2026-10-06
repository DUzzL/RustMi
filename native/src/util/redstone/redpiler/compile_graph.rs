//! Pre-compilation graph IR — adapted port of MCHPRS
//! `crates/redpiler/src/compile_graph/mod.rs`.
//!
//! Adapter changes from upstream:
//!   * `BlockPos` replaced with our [`crate::util::redstone::Pos`] (3×i32
//!     tuple). The graph is position-agnostic except for the per-node
//!     block list that the host needs to flush state back to (Rustmi
//!     [`Level.setBlock`] / NativeSections write-side).
//!   * `Instrument` collapsed into a `u8` tag — the host resolves the real
//!     `Instrument` enum from the noteblock table.
//!   * `SmallVec<[(BlockPos, u32); 1]>` → `Vec<(Pos, u32)>` — the inline
//!     optimisation is irrelevant until we benchmark; vanilla `Vec` keeps
//!     the port dep-free.
//!   * `ComparatorMode` re-exported from [`super::types`] (same tag set).

use super::stable_graph::{NodeIndex, StableGraph};
use super::types::ComparatorMode;
use crate::util::redstone::Pos;

pub use super::stable_graph::{Direction, EdgeRef};

/// Index into the [`CompileGraph`] node arena.
pub type NodeIdx = NodeIndex<u32>;

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub enum NodeType {
    Repeater {
        delay: u8,
        facing_diode: bool,
    },
    Torch,
    Comparator {
        mode: ComparatorMode,
        far_input: Option<u8>,
        facing_diode: bool,
    },
    Lamp,
    Button,
    Lever,
    PressurePlate,
    Trapdoor,
    Wire,
    Constant,
    NoteBlock {
        /// Index into the host-side noteblock table. Replaces upstream's
        /// `instrument` + `note` pair — Rustmi emits both back to Java
        /// when the note event drains, so the index suffices here.
        noteblock_id: u16,
    },
}

impl NodeType {
    pub fn is_normally_input(&self) -> bool {
        matches!(
            self,
            NodeType::Button | NodeType::Lever | NodeType::PressurePlate
        )
    }

    pub fn is_normally_output(&self) -> bool {
        matches!(
            self,
            NodeType::Trapdoor | NodeType::Lamp | NodeType::NoteBlock { .. }
        )
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct NodeState {
    pub powered: bool,
    pub repeater_locked: bool,
    pub output_strength: u8,
}

impl NodeState {
    pub fn simple(powered: bool) -> NodeState {
        NodeState {
            powered,
            output_strength: if powered { 15 } else { 0 },
            ..Default::default()
        }
    }

    pub fn repeater(powered: bool, locked: bool) -> NodeState {
        NodeState {
            powered,
            repeater_locked: locked,
            output_strength: if powered { 15 } else { 0 },
        }
    }

    pub fn ss(ss: u8) -> NodeState {
        NodeState {
            output_strength: ss,
            ..Default::default()
        }
    }

    pub fn comparator(powered: bool, ss: u8) -> NodeState {
        NodeState {
            powered,
            output_strength: ss,
            ..Default::default()
        }
    }
}

/// Reserved for future analysis-pass output (vanilla pulse detection,
/// signal-strength range narrowing). Empty for the runtime-only first port.
#[derive(Debug, Default)]
pub struct Annotations {}

#[derive(Debug)]
pub struct CompileNode {
    pub ty: NodeType,
    /// (position, host-block-id) pairs the runtime needs to flush state to.
    /// Multiple blocks per node = grouped components (e.g. a `(wire, dust)`
    /// pair when a power-providing block sits under a wire). The host
    /// assigns block ids when marshalling the graph.
    pub block: Vec<(Pos, u32)>,
    pub name: Option<String>,
    pub state: NodeState,

    pub is_input: bool,
    pub is_output: bool,
    pub annotations: Annotations,
}

impl CompileNode {
    pub fn new(ty: NodeType) -> CompileNode {
        CompileNode {
            ty,
            block: Vec::new(),
            name: None,
            state: NodeState::default(),
            is_input: false,
            is_output: false,
            annotations: Annotations::default(),
        }
    }

    pub fn is_removable(&self) -> bool {
        !self.is_input && !self.is_output
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LinkType {
    Default,
    Side,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CompileLink {
    pub ty: LinkType,
    pub ss: u8,
}

impl CompileLink {
    pub fn new(ty: LinkType, ss: u8) -> CompileLink {
        CompileLink { ty, ss }
    }

    pub fn default(ss: u8) -> CompileLink {
        CompileLink {
            ty: LinkType::Default,
            ss,
        }
    }

    pub fn side(ss: u8) -> CompileLink {
        CompileLink {
            ty: LinkType::Side,
            ss,
        }
    }
}

/// The IR graph that frontend passes populate and the compile pass lowers
/// into the runtime [`super::node::Nodes`] + [`super::node::ForwardLinks`].
pub type CompileGraph = StableGraph<CompileNode, CompileLink, u32>;
