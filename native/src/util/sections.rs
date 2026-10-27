// NativeSections stage 1 — native backing for section block storage.
//
// The 23GB-heap analysis (2026-06-12) put 60-70% of the heap in block
// storage: 4.4M PalettedContainer instances and ~5GB of SimpleBitStorage
// long[] backing arrays. Stage 1 moves THE ARRAYS off-heap:
//
//   * Java reads/writes through Unsafe address arithmetic (NativeBitStorage)
//     — same cost as an array access, no per-block FFI;
//   * the GC stops scanning/copying gigabytes of long[] (old-gen pause win);
//   * Rust kernels receive (addr, bits, size) and read the same memory
//     directly — zero-copy world inspection for future engines.
//
// Allocation strategy: straight Rust global alloc with 64-byte alignment
// (cache-line; SIMD-friendly for future kernels). Section word counts are a
// few distinct sizes, so the allocator below adds a tiny per-size free-list
// pool to dodge malloc churn during chunk load storms. Pool is bounded;
// overflow frees go straight to the global allocator.
//
// Threading: chunk load/unload happens on multiple threads (c2me chunk
// system + main). Pools are striped behind a small spinlock per size class
// — hold times are a few instructions.

use parking_lot::Mutex;
use std::alloc::{alloc_zeroed, dealloc, Layout};
use std::sync::atomic::{AtomicI64, Ordering};

const ALIGN: usize = 64;
/// Free-list cap per size class — 4096 entries of the largest class (1024
/// words = 8KiB) bounds pooled-but-idle memory at 32MiB for that class,
/// ~100MiB across all classes worst case.
const POOL_CAP: usize = 4096;
/// Size classes we pool (words) = ceil(entries / (64 / bits)) for every storage
/// width vanilla's `Strategy` can allocate (26.3):
///   * biomes, 64 entries: 1/2/3-bit linear → 1/2/4; global 6/7 bits → 7/8;
///   * block states, 4096 entries: 4-bit linear → 256; 5/6/7/8-bit hashmap →
///     342/410/456/512; global 13..16 bits (block-state registry) → 1024.
///
/// 16 is the `NativeSections` allocator probe.
/// Anything else: direct alloc/free.
const CLASSES: [usize; 12] = [1, 2, 4, 7, 8, 16, 256, 342, 410, 456, 512, 1024];

static LIVE_BYTES: AtomicI64 = AtomicI64::new(0);
static LIVE_BLOCKS: AtomicI64 = AtomicI64::new(0);

struct Pool {
    free: Vec<*mut u8>,
}
// SAFETY: raw pointers in the pool reference exclusively-owned allocations.
unsafe impl Send for Pool {}

#[allow(clippy::declare_interior_mutable_const)] // array-repeat seed only; never used by value
const EMPTY_POOL: Mutex<Pool> = Mutex::new(Pool { free: Vec::new() });
static POOLS: [Mutex<Pool>; CLASSES.len()] = [EMPTY_POOL; CLASSES.len()];

fn class_of(words: usize) -> Option<usize> {
    CLASSES.iter().position(|&c| c == words)
}

fn layout(words: usize) -> Layout {
    // words is validated ≤ 1<<20 at the FFI boundary; this cannot overflow.
    Layout::from_size_align(words * 8, ALIGN).unwrap()
}

pub mod ffi {
    use super::*;

    /// Allocate a zeroed native u64 array of `words` elements, 64-aligned.
    /// Returns the address, or 0 on bad args / OOM.
    #[no_mangle]
    pub unsafe extern "C" fn oxi_section_alloc(words: i32) -> i64 {
        if words <= 0 || words > 1 << 20 {
            return 0;
        }
        let words = words as usize;
        if let Some(ci) = class_of(words) {
            // Pop in its own statement: in an `if let` scrutinee the guard would live
            // through the body and the memset below would run under the pool lock.
            let popped = POOLS[ci].lock().free.pop();
            if let Some(p) = popped {
                // Pooled blocks are returned dirty; zero like alloc_zeroed.
                std::ptr::write_bytes(p, 0, words * 8);
                LIVE_BYTES.fetch_add((words * 8) as i64, Ordering::Relaxed);
                LIVE_BLOCKS.fetch_add(1, Ordering::Relaxed);
                return p as i64;
            }
        }
        let p = alloc_zeroed(layout(words));
        if p.is_null() {
            return 0;
        }
        LIVE_BYTES.fetch_add((words * 8) as i64, Ordering::Relaxed);
        LIVE_BLOCKS.fetch_add(1, Ordering::Relaxed);
        p as i64
    }

    /// Release a block from oxi_section_alloc. `words` MUST match the
    /// allocation size (Java side stores it on the NativeBitStorage).
    ///
    /// # Safety
    /// `addr` must come from oxi_section_alloc with the same `words`, and
    /// must not be used after this call (the Java Cleaner guarantees both).
    #[no_mangle]
    pub unsafe extern "C" fn oxi_section_free(addr: i64, words: i32) {
        if addr == 0 || words <= 0 || words > 1 << 20 {
            return;
        }
        let words = words as usize;
        LIVE_BYTES.fetch_sub((words * 8) as i64, Ordering::Relaxed);
        LIVE_BLOCKS.fetch_sub(1, Ordering::Relaxed);
        let p = addr as *mut u8;
        if let Some(ci) = class_of(words) {
            let mut pool = POOLS[ci].lock();
            if pool.free.len() < POOL_CAP {
                pool.free.push(p);
                return;
            }
        }
        dealloc(p, layout(words));
    }

    /// Live native bytes (index 0) and live block count (index 1).
    ///
    /// # Safety
    /// `out` must reference at least 2 i64 slots.
    #[no_mangle]
    pub unsafe extern "C" fn oxi_section_stats(out: *mut i64) {
        if out.is_null() {
            return;
        }
        *out = LIVE_BYTES.load(Ordering::Relaxed);
        *out.add(1) = LIVE_BLOCKS.load(Ordering::Relaxed);
    }
}

#[cfg(test)]
mod tests {
    use super::ffi::*;

    #[test]
    fn alloc_zeroed_roundtrip() {
        unsafe {
            let a = oxi_section_alloc(256);
            assert_ne!(a, 0);
            let p = a as *mut u64;
            for i in 0..256 {
                assert_eq!(*p.add(i), 0, "not zeroed at {i}");
            }
            *p.add(7) = 0xDEADBEEF;
            oxi_section_free(a, 256);
            // pooled: realloc same class returns zeroed memory again
            let b = oxi_section_alloc(256);
            assert_ne!(b, 0);
            let q = b as *mut u64;
            for i in 0..256 {
                assert_eq!(*q.add(i), 0, "pool reuse not zeroed at {i}");
            }
            oxi_section_free(b, 256);
        }
    }

    #[test]
    fn stats_track_live() {
        unsafe {
            let mut out = [0i64; 2];
            oxi_section_stats(out.as_mut_ptr());
            let (b0, n0) = (out[0], out[1]);
            let a = oxi_section_alloc(512);
            oxi_section_stats(out.as_mut_ptr());
            assert_eq!(out[0] - b0, 512 * 8);
            assert_eq!(out[1] - n0, 1);
            oxi_section_free(a, 512);
            oxi_section_stats(out.as_mut_ptr());
            assert_eq!(out[0], b0);
            assert_eq!(out[1], n0);
        }
    }

    #[test]
    fn odd_size_unpooled() {
        unsafe {
            let a = oxi_section_alloc(100);
            assert_ne!(a, 0);
            oxi_section_free(a, 100);
        }
    }
}
