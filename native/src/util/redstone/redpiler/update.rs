//! Forward-propagation handler — port of MCHPRS `crates/redpiler/src/backend/direct/update.rs`.
//!
//! Called by [`super::backend::DirectBackend::set_node`] on every downstream
//! node after an upstream power change. Decides whether the change requires
//! the node to schedule a future tick (Repeater / Torch / Comparator) or to
//! react immediately (Trapdoor / Wire / NoteBlock); the actual tick fire
//! lives in [`super::tick`].

use super::node::{Node, NodeId, NodeInput, NodeType, Nodes};
use super::scheduler::TickScheduler;
use super::types::{ComparatorMode, Event, TickPriority};

thread_local! {
    /// Active during {@code oxi_redpiler_warmup} (incl. its nested
    /// {@code backend.stabilize()} pass). When set, {@link update_node}
    /// for diode-class nodes (Repeater / Torch / Comparator) skips
    /// nodes whose `pending_tick` is already true, and
    /// {@link schedule_tick} sets `pending_tick = true` on scheduling so
    /// later calls in the same warmup see the in-progress schedule.
    ///
    /// <p>This is the dedup mechanism for the warmup phase only —
    /// vanilla's runtime `willTickThisTick` is effectively never true for
    /// diode schedules (delay ≥ 1) so the runtime path stays
    /// dedup-free, matching vanilla's "queue duplicates across game
    /// ticks" semantic.
    ///
    /// <p>Without warmup-time dedup: FFI replay sets pending_tick on
    /// a gate, then stabilize's wire cascade triggers update_node on
    /// the same gate → schedules another delayed tick alongside the
    /// FFI replay → both fire → repeater toggles twice
    /// (user-reported "повторители странно тригерятся / дважды
    /// стригериться", 2026-06-15).
    pub(crate) static WARMUP_DEDUP: std::cell::Cell<bool> = const { std::cell::Cell::new(false) };
}

/// RAII guard that turns on warmup-time dedup for the lifetime of the
/// scope it's created in, and turns it off when dropped — including
/// panic unwind, so a half-completed warmup can never leak the dedup
/// flag into the normal runtime path.
pub struct WarmupGuard;
impl WarmupGuard {
    pub fn enter() -> Self {
        WARMUP_DEDUP.with(|c| c.set(true));
        Self
    }
}
impl Drop for WarmupGuard {
    fn drop(&mut self) {
        WARMUP_DEDUP.with(|c| c.set(false));
    }
}

#[inline(always)]
fn warmup_dedup_active() -> bool {
    WARMUP_DEDUP.with(|c| c.get())
}

#[inline(always)]
pub fn update_node(
    scheduler: &mut TickScheduler,
    events: &mut Vec<Event>,
    nodes: &mut Nodes,
    node_id: NodeId,
) {
    let node = &mut nodes[node_id];

    match node.ty {
        NodeType::Repeater {
            delay,
            facing_diode,
        } => {
            let should_be_locked = get_bool_side(node);
            if should_be_locked != node.locked {
                set_node_locked(node, should_be_locked);
            }
            // Vanilla DiodeBlock.checkTickOnNeighborChanged checks
            // `willTickThisTick(pos, this)` — schedules only if no tick is
            // ALREADY pending for THIS exact gametick. Since repeater
            // (and comparator/torch) schedules always use delay ≥ 1, vanilla's
            // willTickThisTick is effectively always false for them, so
            // vanilla queues duplicates across different gameticks.
            //
            // <p>Earlier we blocked on `node.pending_tick` (ANY pending tick),
            // which DIVERGED from vanilla — repeaters missed scheduled
            // transitions vanilla would queue, producing wrong timing in
            // feedback loops / rapid-toggle inputs. User-reported "rep2
            // blinks when it shouldn't" (2026-06-15) is the symptom.
            //
            // <p>`pending_tick` is still used by the warmup phase
            // (ffi.rs::oxi_redpiler_warmup) to skip nodes that already have
            // an FFI-replayed tick from bail/rewalk. That use-case is
            // distinct from this in-tick path and stays untouched.
            if node.locked {
                return;
            }

            let should_be_powered = get_bool_input(node);
            if should_be_powered != node.powered {
                let priority = if facing_diode {
                    TickPriority::Highest
                } else if !should_be_powered {
                    TickPriority::Higher
                } else {
                    TickPriority::High
                };
                schedule_tick(scheduler, node_id, node, delay as usize, priority);
            }
        }
        NodeType::Torch => {
            // willTickThisTick semantic: same reasoning as Repeater above —
            // torch schedules use delay = 2, so vanilla never blocks via
            // willTickThisTick. Allowing duplicates matches vanilla; the
            // first fire that gets to set_node settles the state and
            // subsequent fires are no-ops.
            let should_be_powered = !get_bool_input(node);
            if node.powered != should_be_powered {
                // Vanilla torch toggles after 1 redstone tick = 2 game ticks.
                // Our scheduler ticks every game tick, so schedule at +2.
                schedule_tick(scheduler, node_id, node, 2, TickPriority::Normal);
            }
        }
        NodeType::Comparator {
            mode,
            far_input,
            facing_diode,
        } => {
            // willTickThisTick semantic: same reasoning as Repeater above.
            let (mut input_power, side_input_power) = get_all_input(node);
            if let Some(far_override) = far_input {
                if input_power < 15 {
                    input_power = far_override.get();
                }
            }
            let old_strength = node.output_power;
            let output_power = calculate_comparator_output(mode, input_power, side_input_power);
            if output_power != old_strength {
                let priority = if facing_diode {
                    TickPriority::High
                } else {
                    TickPriority::Normal
                };
                // Vanilla comparator delay = 1 redstone tick = 2 game ticks.
                schedule_tick(scheduler, node_id, node, 2, priority);
            }
        }
        NodeType::Lamp => {
            let should_be_lit = get_bool_input(node);
            let lit = node.powered;
            if lit && !should_be_lit {
                // Vanilla lamp turn-off delay = 2 redstone ticks = 4 game ticks.
                schedule_tick(scheduler, node_id, node, 4, TickPriority::Normal);
            } else if !lit && should_be_lit {
                set_node(node, true);
            }
        }
        NodeType::Trapdoor => {
            let should_be_powered = get_bool_input(node);
            if node.powered != should_be_powered {
                set_node(node, should_be_powered);
            }
        }
        NodeType::Wire => {
            let (input_power, _) = get_all_input(node);
            if node.output_power != input_power {
                node.output_power = input_power;
                node.changed = true;
            }
        }
        NodeType::NoteBlock { noteblock_id } => {
            let should_be_powered = get_bool_input(node);
            if node.powered != should_be_powered {
                set_node(node, should_be_powered);
                if should_be_powered {
                    events.push(Event::NoteBlockPlay { noteblock_id });
                }
            }
        }
        _ => {} // Constant / Button / Lever / PressurePlate: driven by host only.
    }
}

// ── helpers re-exported for reuse by backend.rs and tick.rs ──────────────

/// Set power+changed flag. Does NOT touch `output_power` — callers above
/// only use it for binary on/off transitions (Trapdoor / NoteBlock); gate
/// nodes use `DirectBackend::set_node` (in backend.rs) which also pushes
/// the change through `ForwardLinks`.
pub(crate) fn set_node(node: &mut Node, powered: bool) {
    node.powered = powered;
    node.changed = true;
}

pub(crate) fn set_node_locked(node: &mut Node, locked: bool) {
    node.locked = locked;
    node.changed = true;
}

pub(crate) fn schedule_tick(
    scheduler: &mut TickScheduler,
    node_id: NodeId,
    node: &mut Node,
    delay: usize,
    priority: TickPriority,
) {
    if warmup_dedup_active() {
        // During warmup: dedup against earlier schedules in this same
        // pass. Without this, stabilize's wire cascade can hit a gate
        // already FFI-replayed (pending_tick=true) and queue an
        // additional tick alongside the replay, producing
        // "ангажируется дважды" (2026-06-15). pending_tick is set on
        // first schedule and read back here for subsequent calls.
        if node.pending_tick {
            return;
        }
        node.pending_tick = true;
        scheduler.schedule_tick(node_id, delay, priority);
        return;
    }
    // Normal runtime path: same-target-slot dedup ONLY. Vanilla's
    // willTickThisTick(pos, this) returns true when ANY tick for (pos,
    // block) is queued for the CURRENT game tick — which, for our
    // delay-N schedule reaching the slot `pos + N`, corresponds to
    // "is a tick for this node already queued at slot pos+N?". Across-
    // gametick duplicates (same node, different slots) are still
    // allowed — that's the vanilla semantic.
    //
    // <p>Without same-slot dedup, set_node cascades that reach a
    // repeater via multiple paths in the same fan-out queue multiple
    // identical ticks at the same slot. Both fire; the first does the
    // turn-OFF transition (powered=true → false), the second sees
    // node.powered=false and falls into the `else if !powered` blip-on
    // branch — turning the gate back ON regardless of input. In a
    // ring oscillator that turn-off-then-spurious-turn-on accumulates
    // across cycles and visibly collapses the ring to stuck-ON
    // (user-reported 2026-06-15: "кольцо несколько раз перешло в
    // состояние вкл").
    //
    // <p>Cost: O(slot_queue_size × 4 priority lanes) per schedule.
    // Slot queues are typically tiny (<10 entries) so the scan is
    // effectively free vs the cost of a duplicate tick fire.
    let target_slot = scheduler.pos().wrapping_add(delay) % TickScheduler::NUM_QUEUES;
    if scheduler.is_scheduled_at(node_id, target_slot) {
        let _ = node;
        return;
    }
    let _ = node;
    scheduler.schedule_tick(node_id, delay, priority);
}

/// Are there ANY non-zero default inputs? Compile pass guarantees
/// sum(ss_counts) = 255, so "no input" ⟺ ss_counts[0] == 255.
#[inline(always)]
pub(crate) fn get_bool_input(node: &Node) -> bool {
    node.default_inputs.ss_counts[0] != 255
}

#[inline(always)]
pub(crate) fn get_bool_side(node: &Node) -> bool {
    node.side_inputs.ss_counts[0] != 255
}

/// Index of the highest non-zero ss bucket — replicated upstream trick:
/// reinterpret the 16-byte histogram as `u128`, count leading-zero bytes.
/// On little-endian this is one `bswap` + `clz`; the comment in upstream
/// notes "might be slower on big-endian".
#[inline(always)]
fn last_index_positive(array: &[u8; 16]) -> u32 {
    let value = u128::from_le_bytes(*array);
    if value == 0 {
        0
    } else {
        15 - (value.leading_zeros() >> 3)
    }
}

#[inline(always)]
pub(crate) fn get_all_input(node: &Node) -> (u8, u8) {
    let input_power = last_index_positive(&node.default_inputs.ss_counts) as u8;
    let side_input_power = last_index_positive(&node.side_inputs.ss_counts) as u8;
    (input_power, side_input_power)
}

/// Vanilla comparator math, optimised for inputs in `0..=15`.
/// Behaviour outside that range is unspecified — the compile pass clamps.
///
/// <p>Mirrors {@code ComparatorBlock.calculateOutputSignal}: bytecode pattern
/// is "if (i >= 15) return i; else { mode-and-side logic }". The
/// {@code i >= 15} shortcut bypasses the Subtract subtraction — Subtract
/// mode with input=15 and side>0 returns 15 in vanilla (not 15-side).
/// Previously we always ran the subtraction, producing 15-side; fixed
/// 2026-06-15 after user reported sporadic comparator flicker.
#[inline(always)]
pub(crate) fn calculate_comparator_output(
    mode: ComparatorMode,
    input_strength: u8,
    power_on_sides: u8,
) -> u8 {
    if input_strength >= 15 {
        return input_strength;
    }
    let difference = input_strength.wrapping_sub(power_on_sides);
    if difference <= 15 {
        match mode {
            ComparatorMode::Compare => input_strength,
            ComparatorMode::Subtract => difference,
        }
    } else {
        0
    }
}

/// Vanilla {@code ComparatorBlock.shouldTurnOn} = the BlockState.POWERED
/// state the comparator should be in given current back/side inputs.
/// Different from {@code calculate_comparator_output > 0}: a Subtract
/// comparator with back == side > 0 has shouldTurnOn=true but output=0.
#[inline(always)]
pub(crate) fn comparator_should_turn_on(input: u8, side: u8) -> bool {
    if input >= 15 {
        return true;
    }
    input != 0 && side <= input
}

// `NodeInput` is referenced indirectly via `Node`; the explicit import here
// keeps rustc happy when the file is compiled without `super::*`.
#[allow(dead_code)]
fn _types_alive(_: &NodeInput) {}
