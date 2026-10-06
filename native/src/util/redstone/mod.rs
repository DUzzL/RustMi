// Vanilla-exact redstone wire replay (see docs/CODEMAP.md, "Redstone wire").
// Two pure-math pillars of vanilla's update ORDER, ported 1:1 from the 26.1.2
// sources (checked against 26.3 by the parity harness) and tested against
// Java-generated ground truth:
//
//   jorder    — BlockPos.hashCode + java.util.HashSet iteration order of the
//               evaluator's 7-position update set (the "locational" part).
//   collector — ChainRestrictedNeighborUpdater (mojmap CollectingNeighborUpdater)
//               queue discipline: stack + pending + depth budget, SixWay
//               generator entries with except-skips (the cascade shape).
//
// sim.rs composes both into the wire-evaluator replay; its oracle is the
// recorded cascades in fixtures/redstone/.
pub mod collector;
pub mod ffi;
pub mod jorder;
pub mod sim;

/// Block position as plain ints — kernel-side mirror of BlockPos.
pub type Pos = (i32, i32, i32);
