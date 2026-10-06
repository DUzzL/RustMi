//! Scheduled-tick handler — port of MCHPRS `crates/redpiler/src/backend/direct/tick.rs`.
//!
//! Refactor from upstream: in MCHPRS this is an `impl DirectBackend` method;
//! we lift it to a free function taking explicit `&mut` references so
//! `update.rs` can call back into [`super::backend::DirectBackend::set_node`]
//! without re-entering the borrow of `self.nodes` (Rust 2021 borrow checker
//! is stricter than the upstream's interior mutability path).
//!
//! Behaviourally identical: fire the queued state transition for one
//! gate-typed node (Repeater / Torch / Comparator / Lamp / Button).

use super::backend::DirectBackend;
use super::node::{NodeId, NodeType};
use super::types::TickPriority;
use super::update::{calculate_comparator_output, get_all_input, get_bool_input, schedule_tick};

impl DirectBackend {
    /// Marked `#[inline(never)]` upstream — inlining into `tick()` regressed
    /// the lag-machine benchmark there; keep the boundary.
    #[inline(never)]
    pub fn tick_node(&mut self, node_id: NodeId) {
        let node = &mut self.nodes[node_id];
        node.pending_tick = false;

        match node.ty {
            NodeType::Repeater { delay, .. } => {
                if node.locked {
                    return;
                }
                let should_be_powered = get_bool_input(node);
                if node.powered && !should_be_powered {
                    self.set_node(node_id, false, 0);
                } else if !node.powered {
                    if !should_be_powered {
                        // Vanilla DiodeBlock.tick uses TickPriority.HIGH for
                        // this self-reschedule (the "blip-off" path —
                        // repeater turned on but input was already gone, so
                        // schedule a tick to turn off `delay` GT later).
                        // Our `Higher` mapped to VERY_HIGH, one slot too
                        // tight, causing the blip-off to fire BEFORE
                        // unrelated turn-ons at HIGH in the same game tick.
                        // Verified vs DiodeBlock.tick bytecode 2026-06-15.
                        schedule_tick(
                            &mut self.scheduler,
                            node_id,
                            node,
                            delay as usize,
                            TickPriority::High,
                        );
                    }
                    self.set_node(node_id, true, 15);
                }
            }
            NodeType::Torch => {
                let should_be_powered = !get_bool_input(node);
                if node.powered != should_be_powered {
                    // Mirrors vanilla {@code RedstoneTorchBlock.tick}
                    // (bytecode verified 2026-06-15):
                    //  - LIT && hasSignal → ALWAYS turn off, then check
                    //    isToggledTooFrequently(pos, add=true). If burnt
                    //    NOW, fire levelEvent 1502 (smoke+sound) and
                    //    schedule 160 GT recovery tick.
                    //  - !LIT && !hasSignal → check
                    //    isToggledTooFrequently(pos, add=true). If NOT
                    //    burnt, turn on. (NOTE: vanilla records the
                    //    toggle regardless of the outcome — add=true is
                    //    always passed.)
                    let currently_powered = node.powered;
                    let _ = node;
                    let now = self.tick_count;
                    let history = self
                        .torch_history
                        .entry(node_id)
                        .or_insert_with(super::backend::TorchHistory::default);

                    if currently_powered && !should_be_powered {
                        // LIT → unLIT (turning off). Record, then check
                        // burnout state AFTER the record. If we just
                        // crossed into burnt, fire the host-side event
                        // so it can play the burnout sound + smoke.
                        let was_burnt = history.is_burnt(now);
                        history.record(now);
                        let now_burnt = history.is_burnt(now);
                        self.set_node(node_id, false, 0);
                        if !was_burnt && now_burnt {
                            self.burnout_pending.push(node_id);
                        }
                    } else {
                        // !LIT → LIT (turning on). Record then check;
                        // if burnt, skip the transition. Recording even
                        // on skip matches vanilla (passes add=true).
                        history.record(now);
                        if history.is_burnt(now) {
                            return;
                        }
                        self.set_node(node_id, true, 15);
                    }
                }
            }
            NodeType::Comparator {
                mode, far_input, ..
            } => {
                let (mut input_power, side_input_power) = get_all_input(node);
                if let Some(far_override) = far_input {
                    if input_power < 15 {
                        input_power = far_override.get();
                    }
                }
                let old_strength = node.output_power;
                let new_strength = calculate_comparator_output(mode, input_power, side_input_power);
                if new_strength != old_strength {
                    self.set_node(node_id, new_strength > 0, new_strength);
                }
            }
            NodeType::Lamp => {
                let should_be_lit = get_bool_input(node);
                if node.powered && !should_be_lit {
                    self.set_node(node_id, false, 0);
                }
            }
            NodeType::Button => {
                if node.powered {
                    self.set_node(node_id, false, 0);
                }
            }
            _ => {} // Other node types are pure inputs / outputs, never ticked.
        }
    }
}
