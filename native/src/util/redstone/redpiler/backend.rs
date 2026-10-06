//! Direct backend — port of MCHPRS `crates/redpiler/src/backend/direct/mod.rs`
//! reduced to the pure-simulator core.
//!
//! Stripped from upstream for this initial port:
//!   * `JITBackend` trait — bring back when wiring into Java via FFI.
//!   * `World` trait / `flush` / `reset` / `on_use_block` / `set_pressure_plate`
//!     — host integration, deferred.
//!   * `compile()` (depends on `CompileGraph` + petgraph) — deferred to a
//!     follow-up port (`compile_graph` IR + frontend passes).
//!   * `Display` impl that dumps Graphviz — deferred.
//!   * `noteblock_info` side table — the runtime backend only needs the
//!     `noteblock_id` payload to emit an Event; the host resolves the actual
//!     (pos, instrument, note) when draining events.

use std::collections::HashMap;

use super::node::{ForwardLinks, NodeId, NodeType, Nodes};
use super::scheduler::TickScheduler;
use super::types::{Event, TickPriority};
use super::update::update_node;

/// Per-torch toggle history for vanilla burnout detection. Vanilla rule:
/// 8+ toggles within 60 game ticks → torch enters "burnt" state where
/// only OFF transitions are allowed; ON transitions are suppressed until
/// the window clears.
///
/// <p>Stores last 8 toggle timestamps as a ring (`history[0..8]`) with
/// `idx` pointing at the next write slot. `i64::MIN` sentinel marks
/// unused slots so the burnout window check naturally ignores them.
#[derive(Copy, Clone, Debug)]
pub struct TorchHistory {
    history: [i64; 8],
    idx: u8,
}

impl Default for TorchHistory {
    fn default() -> Self {
        Self {
            history: [i64::MIN; 8],
            idx: 0,
        }
    }
}

impl TorchHistory {
    /// True if 8+ toggle records lie within the last 60 ticks of `now`.
    /// Vanilla `RedstoneTorchBlock.isToggledTooFrequently` cleans up
    /// entries older than 60 GT and burnout = 8 entries remain.
    pub fn is_burnt(&self, now: i64) -> bool {
        self.history.iter().filter(|&&t| t > now - 60).count() >= 8
    }

    /// Record a toggle at time `now` into the ring.
    pub fn record(&mut self, now: i64) {
        let i = (self.idx as usize) & 7;
        self.history[i] = now;
        self.idx = ((self.idx + 1) & 7) as u8;
    }
}

#[derive(Default)]
pub struct DirectBackend {
    pub nodes: Nodes,
    pub forward_links: ForwardLinks,
    pub scheduler: TickScheduler,
    pub events: Vec<Event>,
    /// Per-torch burnout tracking. Sparse — only torches that have
    /// actually toggled get entries. Cleared on `install` (fresh
    /// claim) and survives across regular ticks. Currently does NOT
    /// persist across bail/rewalk — the rare case of bail-during-burn-
    /// out resets the window and the torch could re-light immediately
    /// after rewalk. Acceptable for the common case (placements happen
    /// in stable states).
    pub torch_history: HashMap<NodeId, TorchHistory>,
    /// Backend's internal "game tick" counter. Increments once per
    /// {@code tick()} call. Wraps at `i64::MAX` (practically never).
    /// Used as the timestamp for [`TorchHistory`] burnout windows.
    pub tick_count: i64,
    /// Torches that JUST entered the burnt state during this tick. The
    /// host drains this list each tick and fires vanilla level event
    /// 1502 (smoke particle + redstone-torch-burnout sound) at each
    /// torch's world position. Cleared by the drain on the host side
    /// — entries are only meaningful between push and drain.
    pub burnout_pending: Vec<NodeId>,
}

impl DirectBackend {
    /// Wire a freshly-compiled network in. Call before any tick/set_node.
    pub fn install(&mut self, nodes: Nodes, forward_links: ForwardLinks) {
        self.nodes = nodes;
        self.forward_links = forward_links;
        self.scheduler.clear();
        self.events.clear();
        self.torch_history.clear();
        self.burnout_pending.clear();
        self.tick_count = 0;
    }

    pub fn schedule_tick(&mut self, node_id: NodeId, delay: usize, priority: TickPriority) {
        self.scheduler.schedule_tick(node_id, delay, priority);
    }

    /// Propagate a power change from `node_id` through its outbound links,
    /// updating each downstream node's per-strength histogram and re-running
    /// [`update_node`] for it. This is the hot path of the entire backend.
    ///
    /// <p><b>Wire-chain propagation.</b> Upstream MCHPRS flattens wire chains
    /// at compile time (input_search) — every wire on a chain gets a direct
    /// edge from the source with accumulated distance, and wires have NO
    /// outgoing edges in the runtime graph. Our walker keeps the wire-to-wire
    /// edges (simpler frontend), so set_node must drive the chain itself:
    /// when a downstream target is a Wire whose `output_power` changed in
    /// `update_node`, queue it for the same fan-out. The iterative worklist
    /// terminates when no more wires change (output_power stabilizes —
    /// histogram is bounded and idempotent).
    pub fn set_node(&mut self, node_id: NodeId, powered: bool, new_power: u8) {
        let root = &mut self.nodes[node_id];
        let root_old = root.output_power;
        root.changed = true;
        root.powered = powered;
        root.output_power = new_power;

        // Worklist of (node, old_out, new_out) for fan-out. Wires pushed
        // here have already had their `output_power` set by `update_node`;
        // we only need to spread their change downstream.
        let mut work: Vec<(NodeId, u8, u8)> = Vec::with_capacity(8);
        work.push((node_id, root_old, new_power));

        while let Some((from_id, from_old, from_new)) = work.pop() {
            let fwd_range = self.nodes[from_id].fwd_link_range.clone();
            for forward_link in self.forward_links.get(&fwd_range) {
                let side = forward_link.side();
                let distance = forward_link.ss();
                let update = forward_link.node();

                let update_ref = &mut self.nodes[update];
                let inputs = if side {
                    &mut update_ref.side_inputs
                } else {
                    &mut update_ref.default_inputs
                };

                let old_in = from_old.saturating_sub(distance);
                let new_in = from_new.saturating_sub(distance);
                if old_in == new_in {
                    continue;
                }

                // Safety: signal strength is bounded to 0..=15 by construction —
                // forward links clamp to <15 (compile pass), saturating_sub keeps
                // it in range.
                unsafe {
                    *inputs.ss_counts.get_unchecked_mut(old_in as usize) -= 1;
                    *inputs.ss_counts.get_unchecked_mut(new_in as usize) += 1;
                }

                let is_wire = matches!(update_ref.ty, NodeType::Wire);
                let wire_old = if is_wire { update_ref.output_power } else { 0 };

                update_node(
                    &mut self.scheduler,
                    &mut self.events,
                    &mut self.nodes,
                    update,
                );

                if is_wire {
                    let wire_new = self.nodes[update].output_power;
                    if wire_new != wire_old {
                        work.push((update, wire_old, wire_new));
                    }
                }
            }
        }
    }

    /// Propagate compile-time histogram values through the wire network
    /// until output_powers converge. Required after a fresh claim because
    /// the walker initialises each wire's `output_power` from its world
    /// {@code BlockState.POWER} property — which can disagree with the
    /// histogram-derived input when the claim was made mid-cascade.
    ///
    /// <p>Concrete trigger (2026-06-15 user report): adding a redstone
    /// block / torch / any signal source inside an existing claim triggers
    /// {@code bail} → sync re-claim. The walker now captures the new
    /// source as a 15-emitting node, but the synchronous re-claim happens
    /// at {@code setBlockState} RETURN — BEFORE the upstream
    /// {@code updateNeighborsAt} cascade would have propagated the
    /// signal through the wire network. Vanilla's wire eval is then
    /// cancelled by {@link CascadeEngine.onEvaluatorEnter} (the claim is
    /// back), so the wire never gets its POWER updated to reflect the
    /// new source. Walker captures the wire at POWER=0; compile fills
    /// its histogram with bucket\[15\] from the new source; output_power
    /// stays 0 because warm-up only fires {@code update_node} on GATES,
    /// not wires.
    ///
    /// <p>This routine scans every wire, identifies output/input
    /// mismatches, and runs the same worklist propagation as
    /// {@code set_node}. Single-pass for most cases; the worklist absorbs
    /// chain-decay scenarios where wire1's correction reveals wire2's
    /// next, etc. Idempotent on already-consistent networks (no work).
    pub fn stabilize(&mut self) {
        let n = self.nodes.len();
        let mut work: Vec<(NodeId, u8, u8)> = Vec::new();

        // Initial scan: each wire whose output disagrees with its
        // histogram-derived input gets corrected here AND queued for
        // forward propagation.
        for i in 0..n {
            let nid = unsafe { NodeId::from_index(i) };
            let ty = self.nodes[nid].ty;
            if !matches!(ty, NodeType::Wire) {
                continue;
            }
            let current = self.nodes[nid].output_power;
            let (input, _) = super::update::get_all_input(&self.nodes[nid]);
            if input != current {
                self.nodes[nid].output_power = input;
                self.nodes[nid].changed = true;
                work.push((nid, current, input));
            }
        }

        // Propagation loop — structurally identical to set_node's inner
        // loop. Cannot just call set_node from the scan above because
        // set_node's own old==new shortcut would not trigger here:
        // we've already updated output_power before pushing; set_node
        // would see no transition. Inlining the loop lets us pass the
        // pre-mutation old explicitly.
        while let Some((from_id, from_old, from_new)) = work.pop() {
            let fwd_range = self.nodes[from_id].fwd_link_range.clone();
            for forward_link in self.forward_links.get(&fwd_range) {
                let side = forward_link.side();
                let distance = forward_link.ss();
                let update = forward_link.node();

                let update_ref = &mut self.nodes[update];
                let inputs = if side {
                    &mut update_ref.side_inputs
                } else {
                    &mut update_ref.default_inputs
                };

                let old_in = from_old.saturating_sub(distance);
                let new_in = from_new.saturating_sub(distance);
                if old_in == new_in {
                    continue;
                }

                // Safety: signal strength is bounded to 0..=15 by construction.
                unsafe {
                    *inputs.ss_counts.get_unchecked_mut(old_in as usize) -= 1;
                    *inputs.ss_counts.get_unchecked_mut(new_in as usize) += 1;
                }

                let is_wire = matches!(update_ref.ty, NodeType::Wire);
                let wire_old = if is_wire { update_ref.output_power } else { 0 };

                super::update::update_node(
                    &mut self.scheduler,
                    &mut self.events,
                    &mut self.nodes,
                    update,
                );

                if is_wire {
                    let wire_new = self.nodes[update].output_power;
                    if wire_new != wire_old {
                        work.push((update, wire_old, wire_new));
                    }
                }
            }
        }
    }

    /// Drain this game-tick's scheduled-tick queue. Each fire may call
    /// `set_node` → `update_node` → schedule further ticks; vanilla
    /// semantics enforce that those land in NEXT tick's slot (delay ≥ 1).
    /// Increments [`tick_count`] — used as the "game tick" timestamp by
    /// [`TorchHistory`] burnout detection in [`Self::tick_node`].
    pub fn tick(&mut self) {
        self.tick_count = self.tick_count.wrapping_add(1);
        let mut queues = self.scheduler.queues_this_tick();
        queues.drain_each(|node_id| {
            self.tick_node(node_id);
        });
        self.scheduler.end_tick(queues);
    }

    pub fn has_pending_ticks(&self) -> bool {
        self.scheduler.has_pending_ticks()
    }

    pub fn drain_events(&mut self) -> std::vec::Drain<'_, Event> {
        self.events.drain(..)
    }
}
