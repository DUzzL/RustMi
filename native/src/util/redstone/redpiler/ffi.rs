//! FFI surface for the redpiler backend.
//!
//! Workflow (one full session):
//!
//! 1. `oxi_redpiler_create(handle)` — register a new compile session.
//! 2. `oxi_redpiler_add_node(handle, ...)` × N — every network node. Returns
//!    the assigned node index (or -1 on failure). Caller MUST add nodes in
//!    order; the index is just `0..N`.
//! 3. `oxi_redpiler_node_add_block(handle, node_idx, x, y, z, block_id)` ×
//!    M — per-node block list (positions the host needs to flush state to).
//! 4. `oxi_redpiler_add_edge(handle, from, to, side, ss)` × E — directed
//!    edges (`from → to`). `side=1` for the comparator-side input lane,
//!    `0` for default.
//! 5. `oxi_redpiler_compile(handle)` — lowers the graph into the runtime
//!    backend. Subsequent `add_*` calls are rejected.
//! 6. `oxi_redpiler_schedule_tick(handle, node_idx, delay, priority)` — for
//!    each pre-existing world-scheduled tick that touched a network node
//!    (the host queries `LevelTicks` and replays into us).
//! 7. Per gameTick on the host:
//!     * `oxi_redpiler_set_input(handle, node_idx, powered, power)` — when
//!       the host detects a player toggled a lever or a redstone block
//!       changed strength.
//!     * `oxi_redpiler_tick(handle)` — drains this tick's queue.
//!     * `oxi_redpiler_drain_changes(handle, out, cap)` → N — copy
//!       changed-since-last-flush nodes into `out` (5 i32 per node: idx,
//!       powered, locked, output_power, padding). Clears `changed` flags.
//!     * `oxi_redpiler_drain_events(handle, out, cap)` → N — note-block
//!       fires (1 i32 per event: noteblock_id).
//! 8. `oxi_redpiler_destroy(handle)` — drop the session.
//!
//! All functions return `i32`: ≥ 0 = success / count, < 0 = error code.

use parking_lot::RwLock;
use std::collections::HashMap;

use super::backend::DirectBackend;
use super::compile::{compile as run_compile, CompiledMeta};
use super::compile_graph::{
    CompileGraph, CompileLink, CompileNode, LinkType, NodeIdx, NodeState, NodeType as CNodeType,
};
use super::node::NodeId;
use super::scheduler::TickScheduler;
use super::types::{ComparatorMode, Event, TickPriority};
use crate::util::redstone::Pos;

// ── error codes ───────────────────────────────────────────────────────────

const OK: i32 = 0;
const ERR_UNKNOWN_HANDLE: i32 = -1;
const ERR_BAD_PARAM: i32 = -2;
const ERR_ALREADY_COMPILED: i32 = -3;
const ERR_NOT_COMPILED: i32 = -4;
const ERR_OUT_OF_RANGE: i32 = -5;
const ERR_OUT_CAP: i32 = -6;

// ── session registry ──────────────────────────────────────────────────────

enum SessionPhase {
    Build {
        graph: CompileGraph,
        node_handles: Vec<NodeIdx>,
    },
    Run {
        backend: DirectBackend,
        meta: CompiledMeta,
    },
}

static REGISTRY: RwLock<Option<HashMap<u64, SessionPhase>>> = RwLock::new(None);

fn with_session<R>(handle: u64, f: impl FnOnce(&mut SessionPhase) -> R) -> Option<R> {
    let mut reg = REGISTRY.write();
    let map = reg.as_mut()?;
    map.get_mut(&handle).map(f)
}

// ── lifecycle ─────────────────────────────────────────────────────────────

/// Open a new compile session under `handle`. Caller-assigned u64 — pick
/// any unique value (Java side allocates from an `AtomicLong`, matching
/// the redstone-engine convention).
#[no_mangle]
pub extern "C" fn oxi_redpiler_create(handle: u64) -> i32 {
    let mut reg = REGISTRY.write();
    let map = reg.get_or_insert_with(HashMap::new);
    if map.contains_key(&handle) {
        return ERR_BAD_PARAM;
    }
    map.insert(
        handle,
        SessionPhase::Build {
            graph: CompileGraph::default(),
            node_handles: Vec::new(),
        },
    );
    OK
}

#[no_mangle]
pub extern "C" fn oxi_redpiler_destroy(handle: u64) -> i32 {
    let mut reg = REGISTRY.write();
    match reg.as_mut().and_then(|m| m.remove(&handle)) {
        Some(_) => OK,
        None => ERR_UNKNOWN_HANDLE,
    }
}

// ── graph build phase ─────────────────────────────────────────────────────

/// Encode a `NodeType` tag + 3 type-specific bytes into a `CompileGraph`
/// node. See [`oxi_redpiler_add_node`] for the wire format.
fn decode_node_type(tag: u8, p1: u8, p2: u8, p3: u8) -> Option<CNodeType> {
    Some(match tag {
        0 => CNodeType::Repeater {
            delay: p1,
            facing_diode: p2 != 0,
        },
        1 => CNodeType::Torch,
        2 => CNodeType::Comparator {
            mode: match p1 {
                0 => ComparatorMode::Compare,
                1 => ComparatorMode::Subtract,
                _ => return None,
            },
            // p2 = far_input + 1 (0 means None, else value-1).
            far_input: if p2 == 0 { None } else { Some(p2 - 1) },
            facing_diode: p3 != 0,
        },
        3 => CNodeType::Lamp,
        4 => CNodeType::Button,
        5 => CNodeType::Lever,
        6 => CNodeType::PressurePlate,
        7 => CNodeType::Trapdoor,
        8 => CNodeType::Wire,
        9 => CNodeType::Constant,
        10 => CNodeType::NoteBlock {
            noteblock_id: u16::from(p1) | (u16::from(p2) << 8),
        },
        _ => return None,
    })
}

/// Add one node. Returns the assigned `0..N` index, or a negative error.
///
/// `packed` keeps this hot-link ABI inside Nalim's integer-register budget.
/// Bytes from least to most significant:
///   * 0: `tag` — NodeType discriminant (0..=10), see [`decode_node_type`].
///   * 1..=3: `p1`/`p2`/`p3` type-specific params:
///       Repeater: p1=delay, p2=facing_diode(0/1)
///       Comparator: p1=mode(0=Compare,1=Subtract),
///                   p2=far_input+1 (0=None, else value-1),
///                   p3=facing_diode(0/1)
///       NoteBlock: (p1|p2<<8) = noteblock_id
///       others: unused.
///   * 4: `powered` (0/1).
///   * 5: `locked` (0/1).
///   * 6: `output_strength` (0..=15).
///   * 7: flags, bit0=is_input and bit1=is_output.
#[inline]
fn unpack_node(packed: u64) -> [u8; 8] {
    packed.to_le_bytes()
}

#[no_mangle]
pub extern "C" fn oxi_redpiler_add_node(handle: u64, packed: u64) -> i32 {
    let [tag, p1, p2, p3, powered, locked, output_strength, flags] = unpack_node(packed);
    let Some(ty) = decode_node_type(tag, p1, p2, p3) else {
        return ERR_BAD_PARAM;
    };
    if powered > 1 || locked > 1 || output_strength > 15 || flags & !0b11 != 0 {
        return ERR_BAD_PARAM;
    }
    let state = NodeState {
        powered: powered != 0,
        repeater_locked: locked != 0,
        output_strength,
    };
    let is_input = flags & 0b01 != 0;
    let is_output = flags & 0b10 != 0;

    with_session(handle, |session| match session {
        SessionPhase::Build {
            graph,
            node_handles,
        } => {
            let mut node = CompileNode::new(ty);
            node.state = state;
            node.is_input = is_input;
            node.is_output = is_output;
            let idx = graph.add_node(node);
            node_handles.push(idx);
            // 0..N index — same as `node_handles.len() - 1`.
            (node_handles.len() - 1) as i32
        }
        SessionPhase::Run { .. } => ERR_ALREADY_COMPILED,
    })
    .unwrap_or(ERR_UNKNOWN_HANDLE)
}

/// Attach a `(pos, block_id)` pair to a previously-added node. `block_id`
/// is host-opaque (Java resolves it to a `BlockState` on flush).
#[no_mangle]
pub extern "C" fn oxi_redpiler_node_add_block(
    handle: u64,
    node_idx: i32,
    x: i32,
    y: i32,
    z: i32,
    block_id: u32,
) -> i32 {
    with_session(handle, |session| match session {
        SessionPhase::Build {
            graph,
            node_handles,
        } => {
            let idx = node_idx as usize;
            let Some(nidx) = node_handles.get(idx).copied() else {
                return ERR_OUT_OF_RANGE;
            };
            let node = &mut graph[nidx];
            node.block.push(((x, y, z), block_id));
            OK
        }
        SessionPhase::Run { .. } => ERR_ALREADY_COMPILED,
    })
    .unwrap_or(ERR_UNKNOWN_HANDLE)
}

/// Add an edge `from → to` with weight `(side, ss)`. `side=1` = comparator
/// side-input lane, `0` = default.
#[no_mangle]
pub extern "C" fn oxi_redpiler_add_edge(handle: u64, from: i32, to: i32, side: u8, ss: u8) -> i32 {
    if ss > 15 {
        return ERR_BAD_PARAM;
    }
    with_session(handle, |session| match session {
        SessionPhase::Build {
            graph,
            node_handles,
        } => {
            let Some(&src) = node_handles.get(from as usize) else {
                return ERR_OUT_OF_RANGE;
            };
            let Some(&dst) = node_handles.get(to as usize) else {
                return ERR_OUT_OF_RANGE;
            };
            let ty = if side != 0 {
                LinkType::Side
            } else {
                LinkType::Default
            };
            graph.add_edge(src, dst, CompileLink::new(ty, ss));
            OK
        }
        SessionPhase::Run { .. } => ERR_ALREADY_COMPILED,
    })
    .unwrap_or(ERR_UNKNOWN_HANDLE)
}

// ── compile ───────────────────────────────────────────────────────────────

/// Lower the assembled graph into the runtime backend. After this point
/// `add_*` calls return [`ERR_ALREADY_COMPILED`].
#[no_mangle]
pub extern "C" fn oxi_redpiler_compile(handle: u64) -> i32 {
    let mut reg = REGISTRY.write();
    let Some(map) = reg.as_mut() else {
        return ERR_UNKNOWN_HANDLE;
    };
    let Some(session) = map.get_mut(&handle) else {
        return ERR_UNKNOWN_HANDLE;
    };
    if matches!(session, SessionPhase::Run { .. }) {
        return ERR_ALREADY_COMPILED;
    }
    let SessionPhase::Build { graph, .. } = std::mem::replace(
        session,
        // Temporary placeholder — overwritten after lowering succeeds.
        SessionPhase::Build {
            graph: CompileGraph::default(),
            node_handles: Vec::new(),
        },
    ) else {
        unreachable!("run phase was handled above")
    };

    let mut backend = DirectBackend::default();
    let meta = run_compile(&mut backend, graph);
    // NOTE: warm-up moved out of compile — it MUST run AFTER the host has
    // replayed any in-flight scheduled ticks captured at bail time (see
    // {@code oxi_redpiler_warmup} and the host's
    // `replayPendingTicks` → `warmup` sequence). Otherwise warm-up
    // schedules a snapshot-based tick AND replay schedules the original
    // pre-bail tick for the same gate → two ticks fire, double-toggle,
    // visible as the "garland" flashing in long repeater chains
    // (2026-06-15).
    *session = SessionPhase::Run { backend, meta };
    OK
}

/// Warm-up pass — bake the captured walk-time state into each gate's
/// pending-tick/output decisions. After [`oxi_redpiler_compile`] populates
/// the input histograms from each source's `output_strength`, the gates
/// (Repeater / Torch / Comparator / Lamp / Trapdoor / NoteBlock) still hold
/// the WALK-TIME `powered`/`output_power` snapshot; nothing has called
/// `update_node` on them yet to compare their histogram input against that
/// snapshot. The mismatch is real (e.g. a wire feeding a newly-walked
/// repeater whose POWERED=false in the world but whose input is actually 15)
/// — without warm-up the gate never wakes until some EXTERNAL set_input
/// event happens to ripple to it, which for a static circuit may be never.
///
/// **MUST be called AFTER** any [`oxi_redpiler_schedule_tick`] replays.
/// `update_node` returns early for nodes whose `pending_tick` is set, so a
/// replayed tick correctly suppresses warm-up from double-scheduling. The
/// reverse order (warm-up first, replay second) was the garland bug
/// (2026-06-15): warm-up schedules a snapshot-driven tick on a gate that's
/// also about to receive a replayed pre-bail tick → two ticks fire, the
/// second flips the gate back, producing flicker that wasn't there in
/// vanilla. Fresh claims (no replay) call this immediately after compile
/// with the same effect they had before — no behavior change for that path.
#[no_mangle]
pub extern "C" fn oxi_redpiler_warmup(handle: u64) -> i32 {
    with_session(handle, |session| match session {
        SessionPhase::Run { backend, .. } => {
            // Enter warmup-dedup mode for the entire warmup pass —
            // stabilize() AND the gate-warmup loop both go through
            // update.rs::schedule_tick, which honors this guard to
            // prevent duplicate scheduling for nodes that already have
            // an FFI-replayed pending tick OR were already touched by
            // an earlier propagation step within this pass.
            //
            // <p>Guard auto-drops on scope exit (including panic
            // unwind), so the dedup flag can never leak into the
            // normal runtime path.
            let _warmup_guard = super::update::WarmupGuard::enter();

            // Phase 1: stabilise wires. Propagates compile-time histogram
            // values through wire-wire bidirectional edges until every
            // wire's `output_power` matches its histogram-derived input.
            // Necessary after sync re-claim where walker may capture
            // mid-cascade BlockState mismatches (e.g. a freshly-placed
            // redstone block at 15 sitting next to a wire whose POWER is
            // still 0 because vanilla never got to evaluate it before our
            // sync rewalk cancelled the wire eval). User report 2026-06-15:
            // "ставлю редстоун блок цепи похуй" — without this pass, the
            // adjacent wires never propagate the new source. See
            // [`DirectBackend::stabilize`] for the full why.
            backend.stabilize();

            // Phase 2: gate warm-up. Schedule transitions for gates whose
            // walk-time POWERED snapshot disagrees with their (now
            // stabilised) input histograms.
            //
            // <p>Skip gates with `pending_tick=true`: those were re-armed
            // by a preceding {@code oxi_redpiler_schedule_tick} replay
            // (the FFI entry point that runs first during bail/rewalk),
            // and re-evaluating them here would schedule a duplicate
            // transition that fires alongside the replayed one — the
            // 2026-06-15 "garland" double-toggle. update_node itself no
            // longer gates on pending_tick (that was the
            // willTickThisTick divergence), so the skip is now done
            // explicitly at this call site only.
            let len = backend.nodes.len();
            for i in 0..len {
                let nid = unsafe { NodeId::from_index(i) };
                let node_ref = &backend.nodes[nid];
                let ty = node_ref.ty;
                if node_ref.pending_tick {
                    continue;
                }
                if matches!(
                    ty,
                    super::node::NodeType::Repeater { .. }
                        | super::node::NodeType::Torch
                        | super::node::NodeType::Comparator { .. }
                        | super::node::NodeType::Lamp
                        | super::node::NodeType::Trapdoor
                        | super::node::NodeType::NoteBlock { .. }
                ) {
                    super::update::update_node(
                        &mut backend.scheduler,
                        &mut backend.events,
                        &mut backend.nodes,
                        nid,
                    );
                }
            }
            OK
        }
        SessionPhase::Build { .. } => ERR_NOT_COMPILED,
    })
    .unwrap_or(ERR_UNKNOWN_HANDLE)
}

// ── runtime phase ─────────────────────────────────────────────────────────

#[no_mangle]
pub extern "C" fn oxi_redpiler_schedule_tick(
    handle: u64,
    node_idx: i32,
    delay: i32,
    priority: u8,
) -> i32 {
    if delay < 0 || delay as usize >= TickScheduler::NUM_QUEUES {
        return ERR_BAD_PARAM;
    }
    let pri = match priority {
        0 => TickPriority::Highest,
        1 => TickPriority::Higher,
        2 => TickPriority::High,
        3 => TickPriority::Normal,
        _ => return ERR_BAD_PARAM,
    };
    with_session(handle, |session| match session {
        SessionPhase::Run { backend, .. } => {
            let n = node_idx as usize;
            if n >= backend.nodes.len() {
                return ERR_OUT_OF_RANGE;
            }
            let nid = unsafe { NodeId::from_index(n) };
            // Dedup against existing schedule at the target slot. During
            // bail/rewalk the seedScheduledTicks pass (reading vanilla
            // LevelChunkTicks) and the sync-replay pass (replaying the
            // captured backend tick list) can independently target the
            // same (node, slot) pair — without this dedup the slot ends
            // up with two queued ticks for the gate, both fire at the
            // same gametick, and the second one flips state back via
            // the `else if !powered → set ON` branch of tick_node
            // (user-reported "повторители странно тригерятся / дважды
            // стригериться", 2026-06-15). Successive bail cycles let
            // duplicates compound — observed 2→6→7 ticks per cycle in
            // the logs.
            //
            // <p>Returning OK on dedup (not an error) — the caller's
            // intent (this tick should fire at this slot) is preserved
            // by the already-existing entry.
            let target_slot =
                (backend.scheduler.pos() + delay as usize) % TickScheduler::NUM_QUEUES;
            if backend.scheduler.is_scheduled_at(nid, target_slot) {
                // Still set pending_tick=true so warmup's skip check
                // sees this node as already scheduled — same effect as
                // adding the duplicate would have had.
                backend.nodes[nid].pending_tick = true;
                return OK;
            }
            backend.schedule_tick(nid, delay as usize, pri);
            backend.nodes[nid].pending_tick = true;
            OK
        }
        SessionPhase::Build { .. } => ERR_NOT_COMPILED,
    })
    .unwrap_or(ERR_UNKNOWN_HANDLE)
}

/// Drive an input node (Button / Lever / PressurePlate / Comparator
/// container-strength source). Mirrors the upstream `on_use_block` +
/// `set_pressure_plate` paths into one call.
#[no_mangle]
pub extern "C" fn oxi_redpiler_set_input(
    handle: u64,
    node_idx: i32,
    powered: u8,
    power: u8,
) -> i32 {
    if power > 15 {
        return ERR_BAD_PARAM;
    }
    with_session(handle, |session| match session {
        SessionPhase::Run { backend, .. } => {
            let n = node_idx as usize;
            if n >= backend.nodes.len() {
                return ERR_OUT_OF_RANGE;
            }
            let nid = unsafe { NodeId::from_index(n) };
            backend.set_node(nid, powered != 0, power);
            OK
        }
        SessionPhase::Build { .. } => ERR_NOT_COMPILED,
    })
    .unwrap_or(ERR_UNKNOWN_HANDLE)
}

/// Advance one gameTick — drains the current tick-slot's queue and runs
/// any cascading updates synchronously.
#[no_mangle]
pub extern "C" fn oxi_redpiler_tick(handle: u64) -> i32 {
    with_session(handle, |session| match session {
        SessionPhase::Run { backend, .. } => {
            backend.tick();
            OK
        }
        SessionPhase::Build { .. } => ERR_NOT_COMPILED,
    })
    .unwrap_or(ERR_UNKNOWN_HANDLE)
}

/// Copy every node whose `changed` flag is set into `out_ptr` and clear
/// the flags. Wire format per node (5 × i32):
///   `[idx, powered, locked, output_power, _reserved]`.
///
/// Returns the count of records written, or [`ERR_OUT_CAP`] if `out_cap`
/// is smaller than the changed-set size (host should grow its buffer and
/// retry — no state mutated when [`ERR_OUT_CAP`]).
#[no_mangle]
pub unsafe extern "C" fn oxi_redpiler_drain_changes(
    handle: u64,
    out_ptr: *mut i32,
    out_cap: i32,
) -> i32 {
    if out_ptr.is_null() || out_cap < 0 {
        return ERR_BAD_PARAM;
    }
    with_session(handle, |session| match session {
        SessionPhase::Run { backend, .. } => {
            let count = backend
                .nodes
                .inner()
                .iter()
                .filter(|node| node.changed)
                .count();
            if count * 5 > out_cap as usize {
                return ERR_OUT_CAP;
            }
            let out = std::slice::from_raw_parts_mut(out_ptr, count * 5);
            let mut rec = 0usize;
            for (idx, node) in backend.nodes.inner_mut().iter_mut().enumerate() {
                if !node.changed {
                    continue;
                }
                let off = rec * 5;
                out[off] = idx as i32;
                out[off + 1] = node.powered as i32;
                out[off + 2] = node.locked as i32;
                out[off + 3] = node.output_power as i32;
                out[off + 4] = 0;
                node.changed = false;
                rec += 1;
            }
            count as i32
        }
        SessionPhase::Build { .. } => ERR_NOT_COMPILED,
    })
    .unwrap_or(ERR_UNKNOWN_HANDLE)
}

/// Drain every still-pending scheduled tick (slot + priority). Used by the
/// host on bail to re-instate ticks into Minecraft's `LevelChunkTicks` so
/// the world keeps ticking from exactly where the backend left off. Wire
/// format per record (4 × i32): `[node_idx, delay_remaining, priority, _reserved]`.
///
/// After a successful drain the scheduler is left empty (slot pointer reset).
/// Returns the count of records written, or [`ERR_OUT_CAP`] if too small
/// (caller must grow and retry — nothing mutated on overflow).
#[no_mangle]
pub unsafe extern "C" fn oxi_redpiler_drain_pending_ticks(
    handle: u64,
    out_ptr: *mut i32,
    out_cap: i32,
) -> i32 {
    if out_ptr.is_null() || out_cap < 0 {
        return ERR_BAD_PARAM;
    }
    with_session(handle, |session| match session {
        SessionPhase::Run { backend, .. } => {
            // Count first so we can fail out_cap without mutating.
            let mut total: usize = 0;
            for queues in backend.scheduler.queues_deque_iter() {
                for q in queues.queues_iter() {
                    total += q.len();
                }
            }
            if total * 4 > out_cap as usize {
                return ERR_OUT_CAP;
            }
            let out = std::slice::from_raw_parts_mut(out_ptr, total * 4);
            let pos = backend.scheduler.pos();
            let mut written: usize = 0;
            for slot in 0..TickScheduler::NUM_QUEUES {
                // Drained delay is relative to (pos+1), the next slot under
                // advance-then-drain. Host replay maps it back with
                // (delay+1) mod 16; vanilla reinstatement uses delay+1.
                let delay = (slot + TickScheduler::NUM_QUEUES
                    - (pos + 1) % TickScheduler::NUM_QUEUES)
                    % TickScheduler::NUM_QUEUES;
                let qs = backend.scheduler.queues_at(slot);
                for (pri_idx, q) in qs.queues_iter().enumerate() {
                    for nid in q.iter() {
                        let off = written * 4;
                        out[off] = nid.index() as i32;
                        out[off + 1] = delay as i32;
                        out[off + 2] = pri_idx as i32;
                        out[off + 3] = 0;
                        written += 1;
                    }
                }
            }
            backend.scheduler.clear();
            written as i32
        }
        SessionPhase::Build { .. } => ERR_NOT_COMPILED,
    })
    .unwrap_or(ERR_UNKNOWN_HANDLE)
}

/// Drain queued [`Event`]s. Wire format per event (1 × i32):
///   * NoteBlockPlay: `[noteblock_id]`.
/// Returns the count of records written, or [`ERR_OUT_CAP`] if too small.
#[no_mangle]
pub unsafe extern "C" fn oxi_redpiler_drain_events(
    handle: u64,
    out_ptr: *mut i32,
    out_cap: i32,
) -> i32 {
    if out_ptr.is_null() || out_cap < 0 {
        return ERR_BAD_PARAM;
    }
    with_session(handle, |session| match session {
        SessionPhase::Run { backend, .. } => {
            let count = backend.events.len();
            if count > out_cap as usize {
                return ERR_OUT_CAP;
            }
            let out = std::slice::from_raw_parts_mut(out_ptr, count);
            for (i, event) in backend.events.drain(..).enumerate() {
                out[i] = match event {
                    Event::NoteBlockPlay { noteblock_id } => noteblock_id as i32,
                };
            }
            count as i32
        }
        SessionPhase::Build { .. } => ERR_NOT_COMPILED,
    })
    .unwrap_or(ERR_UNKNOWN_HANDLE)
}

/// Pre-populate a torch's history to mark it as currently burnt-out,
/// with the burn time offset {@code age_ticks} game ticks into the
/// past. The vanilla burnout window is 60 GT; passing
/// {@code age_ticks < 60} marks the torch burnt for another
/// {@code 60 - age_ticks} ticks, then it recovers naturally as the
/// history slides out of the window.
///
/// <p>Used by the host on sync-rewalk: a torch that was burnt in the
/// previous backend loses its history when the backend is rebuilt
/// from a fresh walk. Without this restore, the torch would
/// re-toggle 8 times → re-burn → repeat sound, every time the user
/// edits an unrelated wire (user-reported 2026-06-15:
/// "ФАКЕЛ ПОСТОЯННО ОБНОВЛЯЕТЬСЯ КОГДА Я ГДЕ ТО СТАВЛЮ ПЫЛИНКУ").
#[no_mangle]
pub extern "C" fn oxi_redpiler_mark_torch_burnt(handle: u64, node_idx: i32, age_ticks: i32) -> i32 {
    if age_ticks < 0 {
        return ERR_BAD_PARAM;
    }
    with_session(handle, |session| match session {
        SessionPhase::Run { backend, .. } => {
            let n = node_idx as usize;
            if n >= backend.nodes.len() {
                return ERR_OUT_OF_RANGE;
            }
            let nid = unsafe { NodeId::from_index(n) };
            if !matches!(backend.nodes[nid].ty, super::node::NodeType::Torch) {
                return ERR_BAD_PARAM;
            }
            let burn_time = backend.tick_count - (age_ticks as i64);
            let mut history = super::backend::TorchHistory::default();
            for _ in 0..8 {
                history.record(burn_time);
            }
            backend.torch_history.insert(nid, history);
            OK
        }
        SessionPhase::Build { .. } => ERR_NOT_COMPILED,
    })
    .unwrap_or(ERR_UNKNOWN_HANDLE)
}

/// Drain the queued torch-burnout list — every torch that JUST entered
/// the burnt state since the last drain. Wire format per record
/// (1 × i32): `[node_idx]`. Host plays vanilla level event 1502
/// (smoke particle + redstone-torch-burnout sound) at each torch's
/// world position.
#[no_mangle]
pub unsafe extern "C" fn oxi_redpiler_drain_burnouts(
    handle: u64,
    out_ptr: *mut i32,
    out_cap: i32,
) -> i32 {
    if out_ptr.is_null() || out_cap < 0 {
        return ERR_BAD_PARAM;
    }
    with_session(handle, |session| match session {
        SessionPhase::Run { backend, .. } => {
            let count = backend.burnout_pending.len();
            if count > out_cap as usize {
                return ERR_OUT_CAP;
            }
            let out = std::slice::from_raw_parts_mut(out_ptr, count);
            for (i, nid) in backend.burnout_pending.drain(..).enumerate() {
                out[i] = nid.index() as i32;
            }
            count as i32
        }
        SessionPhase::Build { .. } => ERR_NOT_COMPILED,
    })
    .unwrap_or(ERR_UNKNOWN_HANDLE)
}

/// Look up a node index by world position. Returns the index or [`ERR_OUT_OF_RANGE`].
#[no_mangle]
pub extern "C" fn oxi_redpiler_lookup_pos(handle: u64, x: i32, y: i32, z: i32) -> i32 {
    with_session(handle, |session| match session {
        SessionPhase::Run { meta, .. } => {
            let pos: Pos = (x, y, z);
            match meta.pos_map.get(&pos) {
                Some(nid) => nid.index() as i32,
                None => ERR_OUT_OF_RANGE,
            }
        }
        SessionPhase::Build { .. } => ERR_NOT_COMPILED,
    })
    .unwrap_or(ERR_UNKNOWN_HANDLE)
}

/// Dump the `(pos, block_id)` flush table for a given node. The host calls
/// this on flush to map runtime nodes back to world block writes. Wire
/// format per block (4 × i32): `[x, y, z, block_id]`.
///
/// Returns the count of records written, or [`ERR_OUT_CAP`] if too small.
#[no_mangle]
pub unsafe extern "C" fn oxi_redpiler_node_blocks(
    handle: u64,
    node_idx: i32,
    out_ptr: *mut i32,
    out_cap: i32,
) -> i32 {
    if out_ptr.is_null() || out_cap < 0 {
        return ERR_BAD_PARAM;
    }
    with_session(handle, |session| match session {
        SessionPhase::Run { meta, .. } => {
            let n = node_idx as usize;
            let Some(blocks) = meta.blocks.get(n) else {
                return ERR_OUT_OF_RANGE;
            };
            if blocks.len() * 4 > out_cap as usize {
                return ERR_OUT_CAP;
            }
            let out = std::slice::from_raw_parts_mut(out_ptr, blocks.len() * 4);
            for (i, ((x, y, z), id)) in blocks.iter().copied().enumerate() {
                let off = i * 4;
                out[off] = x;
                out[off + 1] = y;
                out[off + 2] = z;
                out[off + 3] = id as i32;
            }
            blocks.len() as i32
        }
        SessionPhase::Build { .. } => ERR_NOT_COMPILED,
    })
    .unwrap_or(ERR_UNKNOWN_HANDLE)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pack(bytes: [u8; 8]) -> u64 {
        u64::from_le_bytes(bytes)
    }

    #[test]
    fn packed_node_layout_and_lifecycle_are_stable() {
        assert_eq!(
            unpack_node(0x8877_6655_4433_2211),
            [0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88]
        );

        let handle = 0x5245_4450_494c_4552;
        assert_eq!(oxi_redpiler_create(handle), OK);
        assert_eq!(oxi_redpiler_create(handle), ERR_BAD_PARAM);

        // Lever, initially off, marked as both input and output.
        let node = pack([5, 0x11, 0x22, 0x33, 0, 0, 0, 0b11]);
        assert_eq!(oxi_redpiler_add_node(handle, node), 0);
        assert_eq!(
            oxi_redpiler_node_add_block(handle, 0, 12, 34, 56, 0x1234_5678),
            OK
        );
        assert_eq!(oxi_redpiler_compile(handle), OK);
        assert_eq!(oxi_redpiler_compile(handle), ERR_ALREADY_COMPILED);
        assert_eq!(
            oxi_redpiler_add_node(handle, pack([9, 0, 0, 0, 0, 0, 0, 0])),
            ERR_ALREADY_COMPILED
        );
        assert_eq!(oxi_redpiler_lookup_pos(handle, 12, 34, 56), 0);

        let mut block = [0i32; 4];
        unsafe {
            assert_eq!(
                oxi_redpiler_node_blocks(handle, 0, block.as_mut_ptr(), block.len() as i32),
                1
            );
        }
        assert_eq!(block, [12, 34, 56, 0x1234_5678]);

        assert_eq!(oxi_redpiler_warmup(handle), OK);
        assert_eq!(oxi_redpiler_set_input(handle, 0, 1, 15), OK);
        let mut changes = [0i32; 5];
        unsafe {
            assert_eq!(
                oxi_redpiler_drain_changes(handle, changes.as_mut_ptr(), 0),
                ERR_OUT_CAP
            );
            assert_eq!(
                oxi_redpiler_drain_changes(handle, changes.as_mut_ptr(), changes.len() as i32,),
                1
            );
        }
        assert_eq!(&changes[..4], &[0, 1, 0, 15]);

        assert_eq!(oxi_redpiler_destroy(handle), OK);
        assert_eq!(oxi_redpiler_tick(handle), ERR_UNKNOWN_HANDLE);
    }

    #[test]
    fn pending_tick_drain_reports_the_full_ring_slot_without_mutating_on_overflow() {
        let handle = 0x5245_4450_494c_4554;
        assert_eq!(oxi_redpiler_create(handle), OK);
        assert_eq!(
            oxi_redpiler_add_node(handle, pack([9, 0, 0, 0, 0, 0, 0, 0])),
            0
        );
        assert_eq!(oxi_redpiler_compile(handle), OK);
        assert_eq!(oxi_redpiler_schedule_tick(handle, 0, 0, 3), OK);

        let mut out = [0i32; 4];
        unsafe {
            assert_eq!(
                oxi_redpiler_drain_pending_ticks(handle, out.as_mut_ptr(), 3),
                ERR_OUT_CAP
            );
            assert_eq!(
                oxi_redpiler_drain_pending_ticks(handle, out.as_mut_ptr(), out.len() as i32),
                1
            );
        }
        assert_eq!(out, [0, 15, 3, 0]);
        assert_eq!(oxi_redpiler_destroy(handle), OK);
    }

    #[test]
    fn packed_node_rejects_invalid_state_bytes() {
        let handle = 0x5245_4450_494c_4553;
        assert_eq!(oxi_redpiler_create(handle), OK);
        assert_eq!(
            oxi_redpiler_add_node(handle, pack([9, 0, 0, 0, 2, 0, 0, 0])),
            ERR_BAD_PARAM
        );
        assert_eq!(
            oxi_redpiler_add_node(handle, pack([9, 0, 0, 0, 0, 0, 16, 0])),
            ERR_BAD_PARAM
        );
        assert_eq!(
            oxi_redpiler_add_node(handle, pack([9, 0, 0, 0, 0, 0, 0, 4])),
            ERR_BAD_PARAM
        );
        assert_eq!(oxi_redpiler_destroy(handle), OK);
    }
}
