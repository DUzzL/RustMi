/// Process-wide allocator for every native structure (sections, cramming batches, redstone networks).
/// macOS uses the system allocator (see Cargo.toml).
#[cfg(not(target_os = "macos"))]
#[global_allocator]
static GLOBAL: mimalloc::MiMalloc = mimalloc::MiMalloc;

pub mod util;
