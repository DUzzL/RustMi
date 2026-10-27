// `!(x < 0.0)`-style tests mirror vanilla's float comparisons, NaN included; rewriting them
// with partial_cmp would change behaviour. FFI kernels take wide flat argument lists by design.
#![allow(clippy::neg_cmp_op_on_partial_ord, clippy::too_many_arguments)]

/// Process-wide allocator for every native structure (sections, cramming batches, redstone networks).
/// macOS uses the system allocator (see Cargo.toml).
#[cfg(not(target_os = "macos"))]
#[global_allocator]
static GLOBAL: mimalloc::MiMalloc = mimalloc::MiMalloc;

pub mod util;
