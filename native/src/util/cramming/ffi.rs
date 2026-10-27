// C ABI bridge for the cramming kernel. Java side passes pointers to
// off-heap ByteBuffer regions; we just read/write through them. No
// allocation, no Java-side reference counting, no JNI handles.
//
// Function signature deliberately uses raw pointers + lengths rather than
// JNI-typed array references so the same export works with Nalim (direct
// dlsym lookup, no JNI envelope) and with classic JNI (the symbol is
// callable from System.loadLibrary'd code too).

use super::{compute_cramming, CrammingInput, CrammingOutput};

/// Batch cramming entry point.
///
/// # Safety
/// * `inputs_ptr` must point to `n * size_of::<CrammingInput>()` bytes of
///   readable memory, properly aligned (8-byte).
/// * `outputs_ptr` must point to `n * size_of::<CrammingOutput>()` bytes of
///   writable memory, properly aligned (8-byte).
/// * Both regions must outlive the call.
/// * `n` must be non-negative and fit in usize.
///
/// Java side guarantees these by allocating via `ByteBuffer.allocateDirect`
/// (8-byte aligned by spec) and keeping the buffer in scope across the call.
#[no_mangle]
pub unsafe extern "C" fn oxi_cramming_batch(
    inputs_ptr: *const CrammingInput,
    outputs_ptr: *mut CrammingOutput,
    n: i32,
) {
    if n <= 0 || inputs_ptr.is_null() || outputs_ptr.is_null() {
        return;
    }
    let len = n as usize;
    let inputs = std::slice::from_raw_parts(inputs_ptr, len);
    let outputs = std::slice::from_raw_parts_mut(outputs_ptr, len);
    compute_cramming(inputs, outputs);
}

/// Layout introspection for the Java side. Called once at mod init to
/// verify struct sizes match — if anyone ever changes the Rust struct
/// without updating the Java accessor, this will trip during the smoke
/// test before any real entity is ticked.
/// # Safety
/// No preconditions (no pointer arguments).
#[no_mangle]
pub unsafe extern "C" fn oxi_cramming_input_size() -> i32 {
    std::mem::size_of::<CrammingInput>() as i32
}

/// # Safety
/// No preconditions (no pointer arguments).
#[no_mangle]
pub unsafe extern "C" fn oxi_cramming_output_size() -> i32 {
    std::mem::size_of::<CrammingOutput>() as i32
}

#[no_mangle]
pub extern "C" fn oxi_cramming_pad() -> f64 {
    super::PAD
}

#[no_mangle]
pub extern "C" fn oxi_sensor_pad() -> f64 {
    super::sensor::SENSOR_PAD
}

/// Retain a batch's input buffer for lazy sensor-range queries during the
/// region's entity tick. `handle` is the region id; the Java side guarantees
/// the buffer outlives begin..end.
///
/// # Safety
/// `inputs_ptr` must be null or point to `n` input records that stay valid until `oxi_sensor_end`.
#[no_mangle]
pub unsafe extern "C" fn oxi_sensor_begin(handle: u64, inputs_ptr: *const CrammingInput, n: i32) {
    if inputs_ptr.is_null() || n <= 0 {
        return;
    }
    super::sensor::sensor_begin(handle, inputs_ptr as usize, n as usize);
}

/// # Safety
/// No pointer arguments; ends the handle's retained view of the input buffer.
#[no_mangle]
pub unsafe extern "C" fn oxi_sensor_end(handle: u64) {
    super::sensor::sensor_end(handle);
}

/// Candidate slots within `range` (vanilla FOLLOW_RANGE) of `slot`, padded
/// superset semantics. Returns count, or -1 → caller runs the vanilla query.
///
/// # Safety
/// Pointer arguments must be null or point to buffers of the sizes described above.
#[no_mangle]
pub unsafe extern "C" fn oxi_sensor_query(
    handle: u64,
    slot: i32,
    range: f64,
    out_ptr: *mut i32,
    out_cap: i32,
) -> i32 {
    if out_ptr.is_null() || out_cap <= 0 || slot < 0 {
        return -1;
    }
    let out = std::slice::from_raw_parts_mut(out_ptr, out_cap as usize);
    super::sensor::sensor_query(handle, slot as usize, range, out)
}

/// Unbounded cramming candidate re-query for an OVERFLOW row: pushable
/// rows whose PAD-padded snapshot AABB intersects `slot`'s snapshot AABB,
/// written as i32 slots to `out_ptr`. Same superset contract as the
/// kernel's per-row candidate lists, no 127-slot cap. Returns the count,
/// or -1 (unknown handle / slot out of range / more than `out_cap`
/// candidates) — caller falls back to the vanilla section walk.
///
/// # Safety
/// Pointer arguments must be null or point to buffers of the sizes described above.
#[no_mangle]
pub unsafe extern "C" fn oxi_cramming_query(
    handle: u64,
    slot: i32,
    out_ptr: *mut i32,
    out_cap: i32,
) -> i32 {
    if out_ptr.is_null() || out_cap <= 0 || slot < 0 {
        return -1;
    }
    let out = std::slice::from_raw_parts_mut(out_ptr, out_cap as usize);
    super::sensor::cramming_query(handle, slot as usize, out)
}
