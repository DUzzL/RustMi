//! Stateless FFI for the explosion sweep — one call per explosion. All meta
//! is packed into a single header buffer so the call fits in registers
//! (Nalim's shuffle does not spill to the stack):
//!
//! header layout (64 B):
//!   0..24   f64 cx, cy, cz
//!   24..28  f32 power
//!   28..32  i32 floats_len
//!   32..64  i32 ox, oy, oz, dim_x, dim_y, dim_z, min_y, max_y(exclusive)

use super::{sweep, sweep_packed, Bounds, PackedSnapshot, SectionDesc, Snapshot};

/// Returns the destroyed-position count (triples written to `out_ptr` in
/// discovery order), or -1 (escape / bad args / cap exceeded) — the caller
/// must then run its exact Java fallback with the SAME floats.
///
/// # Safety
/// All pointers must reference buffers of the stated sizes.
#[no_mangle]
pub unsafe extern "C" fn oxi_explosion_sweep(
    header_ptr: *const u8,
    floats_ptr: *const f32,
    res_ptr: *const f32,
    out_ptr: *mut i32,
    out_cap: i32,
) -> i32 {
    if header_ptr.is_null()
        || floats_ptr.is_null()
        || res_ptr.is_null()
        || out_ptr.is_null()
        || out_cap <= 0
    {
        return -1;
    }
    let h64 = header_ptr as *const f64;
    let (cx, cy, cz) = (
        h64.read_unaligned(),
        h64.add(1).read_unaligned(),
        h64.add(2).read_unaligned(),
    );
    let power = (header_ptr.add(24) as *const f32).read_unaligned();
    let hi = header_ptr.add(28) as *const i32;
    let floats_len = hi.read_unaligned();
    let (ox, oy, oz) = (
        hi.add(1).read_unaligned(),
        hi.add(2).read_unaligned(),
        hi.add(3).read_unaligned(),
    );
    let (dim_x, dim_y, dim_z) = (
        hi.add(4).read_unaligned(),
        hi.add(5).read_unaligned(),
        hi.add(6).read_unaligned(),
    );
    let (min_y, max_y) = (hi.add(7).read_unaligned(), hi.add(8).read_unaligned());

    if floats_len <= 0 || dim_x <= 0 || dim_y <= 0 || dim_z <= 0 {
        return -1;
    }
    let cells = (dim_x as i64) * (dim_y as i64) * (dim_z as i64);
    if cells <= 0 || cells > (1 << 22) {
        return -1;
    }
    let floats = std::slice::from_raw_parts(floats_ptr, floats_len as usize);
    let resistance = std::slice::from_raw_parts(res_ptr, cells as usize);
    let snap = Snapshot {
        origin: (ox, oy, oz),
        dims: (dim_x, dim_y, dim_z),
        resistance,
    };
    let mut out = Vec::new();
    if !sweep(
        (cx, cy, cz),
        power,
        floats,
        &snap,
        Bounds { min_y, max_y },
        &mut out,
    ) {
        return -1;
    }
    if out.len() > out_cap as usize {
        return -1;
    }
    let dst = std::slice::from_raw_parts_mut(out_ptr, out.len() * 3);
    for (i, p) in out.iter().enumerate() {
        dst[i * 3] = p.0;
        dst[i * 3 + 1] = p.1;
        dst[i * 3 + 2] = p.2;
    }
    out.len() as i32
}

/// Palette-packed variant — header layout (128 B):
///   0..24   f64 cx, cy, cz
///   24..28  f32 power
///   28..32  i32 floats_len
///   32..56  i32 ox, oy, oz, dim_x, dim_y, dim_z
///   56..64  i32 min_y, max_y(exclusive)
///   64..68  i32 skip_air (0/1)
///   68..72  i32 out_cap
///   72..84  i32 sec_ox, sec_oy, sec_oz   (section coords)
///   84..96  i32 sec_nx, sec_ny, sec_nz
///   96..100 i32 air_count                (written by the kernel)
///
/// `sections_ptr` → sec_nx*sec_ny*sec_nz SectionDesc (24 B each, y-major),
/// `blob_ptr`/`blob_len` → palettes + raw storage longs + dense fills.
///
/// Returns the non-air count (triples at out[0..n*3], discovery order).
/// With skip_air=1 the distinct air cells are written BACKWARDS from the
/// buffer tail (triple i at out[(out_cap-1-i)*3]) and counted in the header.
/// -1 = escape/bad args/cap — caller falls back to its exact Java port.
///
/// # Safety
/// All pointers must reference buffers of the stated sizes.
#[no_mangle]
pub unsafe extern "C" fn oxi_explosion_sweep_packed(
    header_ptr: *mut u8,
    floats_ptr: *const f32,
    sections_ptr: *const u8,
    blob_ptr: *const u8,
    out_ptr: *mut i32,
) -> i32 {
    if header_ptr.is_null()
        || floats_ptr.is_null()
        || sections_ptr.is_null()
        || blob_ptr.is_null()
        || out_ptr.is_null()
    {
        return -1;
    }
    let h64 = header_ptr as *const f64;
    let (cx, cy, cz) = (
        h64.read_unaligned(),
        h64.add(1).read_unaligned(),
        h64.add(2).read_unaligned(),
    );
    let power = (header_ptr.add(24) as *const f32).read_unaligned();
    let hi = header_ptr.add(28) as *const i32;
    let floats_len = hi.read_unaligned();
    let (ox, oy, oz) = (
        hi.add(1).read_unaligned(),
        hi.add(2).read_unaligned(),
        hi.add(3).read_unaligned(),
    );
    let (dim_x, dim_y, dim_z) = (
        hi.add(4).read_unaligned(),
        hi.add(5).read_unaligned(),
        hi.add(6).read_unaligned(),
    );
    let (min_y, max_y) = (hi.add(7).read_unaligned(), hi.add(8).read_unaligned());
    let skip_air = hi.add(9).read_unaligned() != 0;
    let out_cap = hi.add(10).read_unaligned();
    let (sec_ox, sec_oy, sec_oz) = (
        hi.add(11).read_unaligned(),
        hi.add(12).read_unaligned(),
        hi.add(13).read_unaligned(),
    );
    let (sec_nx, sec_ny, sec_nz) = (
        hi.add(14).read_unaligned(),
        hi.add(15).read_unaligned(),
        hi.add(16).read_unaligned(),
    );

    if floats_len <= 0
        || dim_x <= 0
        || dim_y <= 0
        || dim_z <= 0
        || out_cap <= 0
        || sec_nx <= 0
        || sec_ny <= 0
        || sec_nz <= 0
    {
        return -1;
    }
    let cells = (dim_x as i64) * (dim_y as i64) * (dim_z as i64);
    let nsec = (sec_nx as i64) * (sec_ny as i64) * (sec_nz as i64);
    if cells <= 0 || cells > (1 << 22) || nsec <= 0 || nsec > 512 {
        return -1;
    }
    // Blob extent is validated Java-side; descriptors carry offsets into it.
    // Determine the blob slice length from the maximum descriptor reach.
    let descriptors = std::slice::from_raw_parts(sections_ptr as *const SectionDesc, nsec as usize);
    let mut blob_len = 0usize;
    for d in descriptors {
        let reach = match d.mode {
            super::MODE_UNIFORM => d.pal_off as usize + 4,
            super::MODE_PACKED => {
                let pal = d.pal_off as usize + (1usize << d.bits.min(16)) * 4;
                let data = d.data_off as usize + (4096 / d.vpl.max(1) as usize + 1) * 8;
                pal.max(data)
            }
            _ => d.data_off as usize + 4096 * 4,
        };
        if d.mode == super::MODE_PACKED && (d.bits <= 0 || d.bits > 16 || d.vpl != 64 / d.bits) {
            return -1;
        }
        blob_len = blob_len.max(reach);
    }
    let blob = std::slice::from_raw_parts(blob_ptr, blob_len);
    let floats = std::slice::from_raw_parts(floats_ptr, floats_len as usize);

    let snap = PackedSnapshot {
        origin: (ox, oy, oz),
        dims: (dim_x, dim_y, dim_z),
        sec_origin: (sec_ox, sec_oy, sec_oz),
        sec_n: (sec_nx, sec_ny, sec_nz),
        descriptors,
        blob,
    };
    let mut out = Vec::new();
    let mut air = Vec::new();
    if !sweep_packed(
        (cx, cy, cz),
        power,
        floats,
        &snap,
        Bounds { min_y, max_y },
        skip_air,
        &mut out,
        &mut air,
    ) {
        return -1;
    }
    if out.len() + air.len() > out_cap as usize {
        return -1;
    }
    let dst = std::slice::from_raw_parts_mut(out_ptr, out_cap as usize * 3);
    for (i, p) in out.iter().enumerate() {
        dst[i * 3] = p.0;
        dst[i * 3 + 1] = p.1;
        dst[i * 3 + 2] = p.2;
    }
    for (i, p) in air.iter().enumerate() {
        let base = (out_cap as usize - 1 - i) * 3;
        dst[base] = p.0;
        dst[base + 1] = p.1;
        dst[base + 2] = p.2;
    }
    (header_ptr.add(96) as *mut i32).write_unaligned(air.len() as i32);
    out.len() as i32
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn header_roundtrip() {
        let dims = (33i32, 33i32, 33i32);
        let res = vec![-1.0f32; (dims.0 * dims.1 * dims.2) as usize];
        let floats = vec![0.5f32; 1352];
        let mut header = vec![0u8; 64];
        unsafe {
            (header.as_mut_ptr() as *mut f64).write_unaligned(0.5);
            (header.as_mut_ptr().add(8) as *mut f64).write_unaligned(64.5);
            (header.as_mut_ptr().add(16) as *mut f64).write_unaligned(0.5);
            (header.as_mut_ptr().add(24) as *mut f32).write_unaligned(4.0);
            let hi = header.as_mut_ptr().add(28) as *mut i32;
            hi.write_unaligned(1352);
            hi.add(1).write_unaligned(-16);
            hi.add(2).write_unaligned(48);
            hi.add(3).write_unaligned(-16);
            hi.add(4).write_unaligned(33);
            hi.add(5).write_unaligned(33);
            hi.add(6).write_unaligned(33);
            hi.add(7).write_unaligned(-64);
            hi.add(8).write_unaligned(320);
            let mut out = vec![0i32; 3 * 32768];
            let n = oxi_explosion_sweep(
                header.as_ptr(),
                floats.as_ptr(),
                res.as_ptr(),
                out.as_mut_ptr(),
                32768,
            );
            assert!(n > 500, "got {n}");
            assert_eq!(&out[0..3], &[0, 64, 0]);
        }
    }
}
