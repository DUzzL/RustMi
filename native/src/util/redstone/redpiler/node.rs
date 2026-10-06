//! Runtime IR — direct port of MCHPRS `crates/redpiler/src/backend/direct/node.rs`.
//!
//! Stripping changes from upstream:
//!   * `ComparatorMode` is sourced from [`super::types`] instead of
//!     `mchprs_blocks::blocks::ComparatorMode` — identical tags.
//!   * `NoteBlock { instrument, note }` collapsed into `NoteBlock { noteblock_id }`
//!     exactly as the direct backend already did (mod.rs holds the side
//!     table); kept the indirection so the runtime node stays 16-bit.
//!
//! Memory layout invariants (kept verbatim from upstream — important for
//! perf, do not change without re-benching):
//!   * [`Node`] is `repr(align(64))` so each node fits one L1 cache line.
//!   * [`ForwardLink`] packs `(NodeId<27> | side<1> | ss<4>)` into a single
//!     `u32` — node id space 0..2²⁷, signal-strength 0..15. The compile
//!     pass's `clamp_weights` ensures `ss < 15`.
//!   * [`NodeInput`] is `repr(align(16))` — fits in one SSE register.

use std::num::NonZeroU8;
use std::ops::{Index, IndexMut};

use super::types::ComparatorMode;

#[derive(Debug, Copy, Clone, PartialEq, Eq, Hash)]
pub struct NodeId(u32);

impl NodeId {
    pub fn index(self) -> usize {
        self.0 as usize
    }

    /// Safety: `index` must be within the bounds of the [`Nodes`] array it
    /// was minted from.
    pub unsafe fn from_index(index: usize) -> NodeId {
        NodeId(index as u32)
    }
}

#[derive(Default)]
pub struct Nodes {
    pub nodes: Box<[Node]>,
}

impl Nodes {
    pub fn new(nodes: Box<[Node]>) -> Nodes {
        Nodes { nodes }
    }

    pub fn get(&self, idx: usize) -> NodeId {
        if self.nodes.get(idx).is_some() {
            NodeId(idx as u32)
        } else {
            panic!("node index out of bounds: {}", idx)
        }
    }

    pub fn inner(&self) -> &[Node] {
        &self.nodes
    }

    pub fn inner_mut(&mut self) -> &mut [Node] {
        &mut self.nodes
    }

    pub fn into_inner(self) -> Box<[Node]> {
        self.nodes
    }

    pub fn len(&self) -> usize {
        self.nodes.len()
    }

    pub fn is_empty(&self) -> bool {
        self.nodes.is_empty()
    }
}

impl Index<NodeId> for Nodes {
    type Output = Node;

    // The index MUST have been created by this instance — otherwise UB.
    fn index(&self, index: NodeId) -> &Self::Output {
        unsafe { self.nodes.get_unchecked(index.0 as usize) }
    }
}

impl IndexMut<NodeId> for Nodes {
    fn index_mut(&mut self, index: NodeId) -> &mut Self::Output {
        unsafe { self.nodes.get_unchecked_mut(index.0 as usize) }
    }
}

#[derive(Clone, Copy)]
pub struct ForwardLink {
    data: u32,
}

impl ForwardLink {
    pub fn new(id: NodeId, side: bool, ss: u8) -> Self {
        assert!(id.index() < (1 << 27));
        // The clamp_weights compile pass should ensure ss < 15.
        assert!(ss < 15);
        Self {
            data: (id.index() as u32) << 5 | if side { 1 << 4 } else { 0 } | ss as u32,
        }
    }

    pub fn node(self) -> NodeId {
        // Safety: ForwardLink is constructed from a valid NodeId.
        unsafe { NodeId::from_index((self.data >> 5) as usize) }
    }

    pub fn side(self) -> bool {
        self.data & (1 << 4) != 0
    }

    pub fn ss(self) -> u8 {
        (self.data & 0b1111) as u8
    }
}

impl std::fmt::Debug for ForwardLink {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ForwardLink")
            .field("node", &self.node())
            .field("side", &self.side())
            .field("ss", &self.ss())
            .finish()
    }
}

#[derive(Clone, Debug, Default)]
pub struct ForwardLinkRange(std::ops::Range<usize>);

impl ForwardLinkRange {
    pub fn len(&self) -> usize {
        self.0.end - self.0.start
    }

    pub fn is_empty(&self) -> bool {
        self.0.is_empty()
    }
}

#[derive(Default)]
pub struct ForwardLinks {
    links: Vec<ForwardLink>,
}

impl ForwardLinks {
    pub fn extend(&mut self, iter: impl IntoIterator<Item = ForwardLink>) -> ForwardLinkRange {
        let start = self.links.len();
        self.links.extend(iter);
        let end = self.links.len();
        ForwardLinkRange(start..end)
    }

    /// Safety: the `range` MUST have been produced by this exact
    /// `ForwardLinks` instance, otherwise UB.
    pub fn get(&self, range: &ForwardLinkRange) -> &[ForwardLink] {
        unsafe { self.links.get_unchecked(range.0.clone()) }
    }

    /// Invalidates ALL outstanding [`ForwardLinkRange`]s.
    pub fn clear(&mut self) {
        self.links.clear();
    }

    pub fn len(&self) -> usize {
        self.links.len()
    }

    pub fn is_empty(&self) -> bool {
        self.links.is_empty()
    }
}

#[derive(Debug, Clone, Copy)]
pub enum NodeType {
    Repeater {
        delay: u8,
        facing_diode: bool,
    },
    Torch,
    Comparator {
        mode: ComparatorMode,
        far_input: Option<NonMaxU8>,
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
        /// Index into the host-side noteblock table that holds the actual
        /// (position, instrument, note pitch).
        noteblock_id: u16,
    },
}

/// Per-direction signal-strength histogram. `ss_counts[k]` = how many
/// incoming links currently feed strength `k` (0..=15). The compile pass
/// guarantees the sum is 255 so that "any signal at all" is a single
/// comparison: `ss_counts[0] != 255`.
#[repr(align(16))]
#[derive(Debug, Clone, Default)]
pub struct NodeInput {
    pub ss_counts: [u8; 16],
}

#[derive(Debug, Clone, Copy)]
pub struct NonMaxU8(NonZeroU8);

impl NonMaxU8 {
    pub fn new(value: u8) -> Option<Self> {
        NonZeroU8::new(value + 1).map(Self)
    }

    pub fn get(self) -> u8 {
        self.0.get() - 1
    }
}

/// One node in the compiled network. Holds its inputs (per-side histograms),
/// outbound edge range into [`ForwardLinks`], and current dynamic state.
///
/// 64-byte aligned so the whole node fits in a single L1 cache line —
/// removing this attribute regresses the lag-machine benchmark ~20% in
/// upstream MCHPRS.
#[repr(align(64))]
#[derive(Debug, Clone)]
pub struct Node {
    pub ty: NodeType,
    pub default_inputs: NodeInput,
    pub side_inputs: NodeInput,

    pub fwd_link_range: ForwardLinkRange,

    pub is_io: bool,

    /// Powered (gate) or lit (lamp/torch/noteblock).
    pub powered: bool,
    /// Only meaningful for repeaters.
    pub locked: bool,
    pub output_power: u8,
    /// Set by `set_node` / `set_node_locked` to signal the host that the
    /// world-side block needs a refresh on the next flush.
    pub changed: bool,
    /// True while this node has a scheduled tick in flight — used to
    /// suppress duplicate enqueues.
    pub pending_tick: bool,
}
