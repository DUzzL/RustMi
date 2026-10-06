//! Local stand-ins for MCHPRS-crate enums imported by the direct backend.
//!
//! MCHPRS imports `ComparatorMode` / `Instrument` from `mchprs_blocks` and
//! `TickPriority` from `mchprs_world`. The runtime backend only needs the
//! tag (no block-state semantics), so we duplicate the minimal shape here
//! to keep this port free of upstream crate deps.

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ComparatorMode {
    /// Output equals input unless any side ≥ input.
    Compare,
    /// Output is `max(0, input - max(sides))`.
    Subtract,
}

/// Scheduled-tick priority. Vanilla MC sorts ticks by priority within a
/// gameTime slot before draining; the direct backend mirrors this with four
/// FIFO lanes per slot.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[repr(u8)]
pub enum TickPriority {
    Highest = 0,
    Higher = 1,
    High = 2,
    Normal = 3,
}

impl TickPriority {
    pub const COUNT: usize = 4;
}

/// Backend → host side-effect produced inside [`crate::util::redstone::redpiler::tick`]
/// or [`crate::util::redstone::redpiler::update::update_node`]. The Java
/// integration drains these between ticks and forwards them as block events.
#[derive(Debug, Clone, Copy)]
pub enum Event {
    /// A note block fired; `id` indexes the host-side noteblock table that
    /// holds (position, instrument, note pitch).
    NoteBlockPlay { noteblock_id: u16 },
}
