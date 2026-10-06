//! Redpiler-style compiled direct-interpreter backend.
//!
//! The runtime is adapted from MCHPRS
//! (`crates/redpiler/src/backend/direct/`, MIT licensed,
//! github.com/MCHPR/MCHPRS). RustMI's Java walker discovers a claimed world
//! region and sends its nodes, block mappings, and directed edges through
//! [`ffi`]. [`compile`] lowers that graph into the compact runtime IR; the
//! host then drives inputs, scheduled ticks, state drains, and teardown.
//!
//! Main components:
//!   * [`compile_graph`] — stable graph and host-facing compile IR.
//!   * [`compile`] — graph-to-runtime lowering and block/position metadata.
//!   * [`node`] — cache-conscious nodes, inputs, and packed forward links.
//!   * [`scheduler::TickScheduler`] — 16 delay slots × 4 priority lanes.
//!   * [`update`] / [`tick`] — vanilla-oriented propagation and gate ticks.
//!   * [`backend::DirectBackend`] — compiled session state and input fan-out.
//!   * [`ffi`] — the complete Java lifecycle and drain surface.
//!
//! Graph optimization passes and JIT backends remain intentionally out of
//! scope; RustMI uses the direct interpreter and flushes resulting state from
//! Java on the owning server thread.

#![allow(dead_code)]

pub mod backend;
pub mod compile;
pub mod compile_graph;
pub mod ffi;
pub mod node;
pub mod scheduler;
pub mod stable_graph;
pub mod tick;
pub mod types;
pub mod update;

pub use backend::DirectBackend;
pub use compile::{compile, CompileStats, CompiledMeta};
pub use compile_graph::{
    CompileGraph, CompileLink, CompileNode, LinkType, NodeIdx, NodeState,
    NodeType as CompileNodeType,
};
pub use node::{
    ForwardLink, ForwardLinkRange, ForwardLinks, Node, NodeId, NodeInput, NodeType, Nodes, NonMaxU8,
};
pub use scheduler::TickScheduler;
pub use types::{ComparatorMode, Event, TickPriority};
