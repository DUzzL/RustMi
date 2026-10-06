//! Vanilla-equivalent scheduled-tick queue.
//!
//! Adapted from MCHPRS `crates/redpiler/src/backend/direct/mod.rs`. The
//! 16-slot ring covers every direct-backend node type, whose internal
//! schedules use delays no greater than four ticks. Host-side migration can
//! address all 16 slots, including delay zero as a full-ring wrap under the
//! advance-then-drain convention. Pending entries are drained through the
//! Redpiler FFI before a session returns ownership to vanilla.

use std::mem;

use super::node::NodeId;
use super::types::TickPriority;

#[derive(Default, Clone)]
pub struct Queues(pub(crate) [Vec<NodeId>; TickPriority::COUNT]);

impl Queues {
    /// Drain every priority lane FIFO into `f`.
    #[inline(always)]
    pub(crate) fn drain_each<F: FnMut(NodeId)>(&mut self, mut f: F) {
        for q in self.0.iter_mut() {
            for n in q.iter() {
                f(*n);
            }
            q.clear();
        }
    }

    pub fn is_empty(&self) -> bool {
        self.0.iter().all(|q| q.is_empty())
    }

    /// Read-only iterator over per-priority queues, in priority order
    /// (Highest..Normal). Used by the FFI's bail-drain to enumerate
    /// pending ticks without consuming them.
    #[inline]
    pub fn queues_iter(&self) -> impl Iterator<Item = &Vec<NodeId>> {
        self.0.iter()
    }
}

#[derive(Default)]
pub struct TickScheduler {
    queues_deque: [Queues; Self::NUM_QUEUES],
    pos: usize,
}

impl TickScheduler {
    pub const NUM_QUEUES: usize = 16;

    pub fn schedule_tick(&mut self, node: NodeId, delay: usize, priority: TickPriority) {
        let slot = (self.pos + delay) % Self::NUM_QUEUES;
        self.queues_deque[slot].0[priority as usize].push(node);
    }

    /// Advance one game-tick and return that slot's queues for draining.
    /// Call [`Self::end_tick`] with the (now-emptied) returned queues to
    /// return them to the ring so allocations are reused.
    ///
    /// <p><b>Semantic note:</b> advance THEN drain. {@code schedule_tick(delay=D)}
    /// from inside a tick fires D ticks later (cascade scheduling — correct).
    /// {@code schedule_tick(delay=D)} from outside a tick (warmup / FFI
    /// replay at pos=0) fires D-1 ticks later (off-by-one early). The
    /// host compensates with {@code delay+1} shifts in seed/replay paths.
    /// This semantic preserves the timing user reported as "working" in
    /// their pulsing-source chain (2026-06-16).
    pub fn queues_this_tick(&mut self) -> Queues {
        self.pos = (self.pos + 1) % Self::NUM_QUEUES;
        mem::take(&mut self.queues_deque[self.pos])
    }

    pub fn end_tick(&mut self, queues: Queues) {
        self.queues_deque[self.pos % Self::NUM_QUEUES] = queues;
    }

    pub fn has_pending_ticks(&self) -> bool {
        for queues in &self.queues_deque {
            for queue in &queues.0 {
                if !queue.is_empty() {
                    return true;
                }
            }
        }
        false
    }

    /// Clear every slot — used when bailing the backend back to vanilla,
    /// before re-arming any host-side ticks.
    pub fn clear(&mut self) {
        for queues in self.queues_deque.iter_mut() {
            for queue in queues.0.iter_mut() {
                queue.clear();
            }
        }
        self.pos = 0;
    }

    /// Read-only access to the entire ring (FFI bail-drain enumerates pending
    /// ticks). Use [`Self::queues_at`] when you need a specific slot.
    #[inline]
    pub fn queues_deque_iter(&self) -> impl Iterator<Item = &Queues> {
        self.queues_deque.iter()
    }

    #[inline]
    pub fn queues_at(&self, slot: usize) -> &Queues {
        &self.queues_deque[slot % Self::NUM_QUEUES]
    }

    /// True if {@code node_id} is already scheduled in {@code slot}'s
    /// queues (any priority lane). Used by callers that want to dedup
    /// against earlier schedules — notably the FFI replay/seed path
    /// during bail/rewalk, where vanilla LevelChunkTicks and the
    /// backend's captured tick list can independently target the same
    /// (node, slot) pair, producing visible double-toggles.
    ///
    /// <p>Cost is O(queue_size) over 4 priority lanes — slot queues
    /// are typically tiny (1-5 entries) so the scan is effectively free
    /// compared to the cost of a duplicated tick fire.
    #[inline]
    pub fn is_scheduled_at(&self, node_id: NodeId, slot: usize) -> bool {
        let s = slot % Self::NUM_QUEUES;
        self.queues_deque[s]
            .queues_iter()
            .any(|q| q.iter().any(|&id| id == node_id))
    }

    #[inline]
    pub fn pos(&self) -> usize {
        self.pos
    }

    pub(crate) fn priorities() -> [TickPriority; TickPriority::COUNT] {
        [
            TickPriority::Highest,
            TickPriority::Higher,
            TickPriority::High,
            TickPriority::Normal,
        ]
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn delay_zero_wraps_to_the_current_slot_after_a_full_ring() {
        let mut scheduler = TickScheduler::default();
        let node = unsafe { NodeId::from_index(0) };
        scheduler.schedule_tick(node, 0, TickPriority::Highest);

        for _ in 0..15 {
            let queues = scheduler.queues_this_tick();
            assert!(queues.is_empty());
            scheduler.end_tick(queues);
        }

        let mut queues = scheduler.queues_this_tick();
        assert_eq!(queues.0[TickPriority::Highest as usize].as_slice(), &[node]);
        queues.drain_each(|_| {});
        scheduler.end_tick(queues);
        assert!(!scheduler.has_pending_ticks());
    }
}
