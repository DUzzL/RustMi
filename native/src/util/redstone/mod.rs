// Vanilla-exact redstone replay core (campaign phase R1, see
// docs/REDSTONE_VANILLA_PLAN.md). Two pure-math pillars of vanilla's update
// ORDER, ported 1:1 from extracted 26.1.2 sources and tested against
// Java-generated ground truth:
//
//   jorder    — BlockPos.hashCode + java.util.HashSet iteration order of the
//               evaluator's 7-position update set (the "locational" part).
//   collector — ChainRestrictedNeighborUpdater (mojmap CollectingNeighborUpdater)
//               queue discipline: stack + pending + depth budget, SixWay
//               generator entries with except-skips (the cascade shape).
//
// The wire-evaluator simulation that plugs into these lands with the R0
// fixtures (rustmi-redstone-fixtures/) as its oracle.
pub mod collector;
pub mod ffi;
pub mod jorder;
pub mod sim;

// R4 MCHPRS Redpiler direct-interpreter backend. The Java host owns graph
// discovery and lifecycle; this module supplies the compiled native engine
// and its FFI surface.
pub mod redpiler;

/// Block position as plain ints — kernel-side mirror of BlockPos.
pub type Pos = (i32, i32, i32);
