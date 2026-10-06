//! FFI for the full-cube collision kernel — one call per Entity.collide
//! fast-path. Header-packed (Nalim register budget), all i32/i64/f32/f64 at
//! the boundary per the nalim ABI rule.
//!
//! header layout (88 B):
//!   0..48   f64 box  (minX, minY, minZ, maxX, maxY, maxZ)
//!   48..72  f64 delta (x, y, z)
//!   72..76  f32 stepHeight
//!   76..80  i32 onGround (0/1)
//!   80..84  i32 solidCount
//!
//! solids_ptr → solidCount × (f64 minX..maxZ) collider boxes, vanilla discovery order.
//! out_ptr    → 3 × f64 resolved movement.

use super::{collide_checked, collide_mixed_checked, grid, Aabb, Collider};

/// Returns 0 on success, 1 when Java must use the exact fallback, or -1 on bad args.
///
/// # Safety
/// All pointers must reference buffers of the stated sizes.
#[no_mangle]
pub unsafe extern "C" fn oxi_collide(
    header_ptr: *const u8,
    solids_ptr: *const f64,
    out_ptr: *mut f64,
) -> i32 {
    if header_ptr.is_null() || out_ptr.is_null() {
        return -1;
    }
    let h64 = header_ptr as *const f64;
    let bb = Aabb {
        min: [
            h64.read_unaligned(),
            h64.add(1).read_unaligned(),
            h64.add(2).read_unaligned(),
        ],
        max: [
            h64.add(3).read_unaligned(),
            h64.add(4).read_unaligned(),
            h64.add(5).read_unaligned(),
        ],
    };
    let delta = [
        h64.add(6).read_unaligned(),
        h64.add(7).read_unaligned(),
        h64.add(8).read_unaligned(),
    ];
    let step = (header_ptr.add(72) as *const f32).read_unaligned();
    let hi = header_ptr.add(76) as *const i32;
    let on_ground = hi.read_unaligned() != 0;
    let count = hi.add(1).read_unaligned();
    if count < 0 || count > 1 << 20 || (count > 0 && solids_ptr.is_null()) {
        return -1;
    }
    let cols: &[[f64; 6]] = if count > 0 {
        std::slice::from_raw_parts(solids_ptr.cast::<[f64; 6]>(), count as usize)
    } else {
        &[]
    };
    let r = collide_checked(delta, bb, step, on_ground, cols);
    out_ptr.write_unaligned(r.movement[0]);
    out_ptr.add(1).write_unaligned(r.movement[1]);
    out_ptr.add(2).write_unaligned(r.movement[2]);
    if r.epsilon_unstable { 1 } else { 0 }
}

/// C3 entry — mixed cubes + voxel grids in discovery order.
///
/// header layout (96 B): as v1 up to 84, then
///   84..88  i32 gridCount
///   88..92  i32 colliderCount (total tags)
///
/// tags_ptr   → colliderCount × u8: 0 = next solids record, 1 = next grid record.
/// grids_ptr  → gridCount variable-size records (see grid::parse_grids).
/// out_ptr    → 3 × f64 resolved movement.
///
/// # Safety
/// All pointers must reference buffers of the stated sizes.
#[no_mangle]
pub unsafe extern "C" fn oxi_collide2(
    header_ptr: *const u8,
    solids_ptr: *const f64,
    grids_ptr: *const u8,
    tags_ptr: *const u8,
    out_ptr: *mut f64,
) -> i32 {
    if header_ptr.is_null() || out_ptr.is_null() {
        return -1;
    }
    let h64 = header_ptr as *const f64;
    let bb = Aabb {
        min: [
            h64.read_unaligned(),
            h64.add(1).read_unaligned(),
            h64.add(2).read_unaligned(),
        ],
        max: [
            h64.add(3).read_unaligned(),
            h64.add(4).read_unaligned(),
            h64.add(5).read_unaligned(),
        ],
    };
    let delta = [
        h64.add(6).read_unaligned(),
        h64.add(7).read_unaligned(),
        h64.add(8).read_unaligned(),
    ];
    let step = (header_ptr.add(72) as *const f32).read_unaligned();
    let hi = header_ptr.add(76) as *const i32;
    let on_ground = hi.read_unaligned() != 0;
    let solid_count = hi.add(1).read_unaligned();
    let grid_count = hi.add(2).read_unaligned();
    let collider_count = hi.add(3).read_unaligned();
    let Some(expected_count) = solid_count.checked_add(grid_count) else {
        return -1;
    };
    if solid_count < 0
        || grid_count < 0
        || collider_count < 0
        || collider_count != expected_count
        || solid_count > 1 << 20
        || grid_count > 1 << 12
        || (solid_count > 0 && solids_ptr.is_null())
        || (grid_count > 0 && grids_ptr.is_null())
        || (collider_count > 0 && tags_ptr.is_null())
    {
        return -1;
    }

    let raw = if solid_count > 0 {
        std::slice::from_raw_parts(solids_ptr, solid_count as usize * 6)
    } else {
        &[]
    };
    let grids = grid::parse_grids(grids_ptr, grid_count as usize);
    let tags = if collider_count > 0 {
        std::slice::from_raw_parts(tags_ptr, collider_count as usize)
    } else {
        &[]
    };

    let mut colliders: Vec<Collider<'_>> = Vec::with_capacity(collider_count as usize);
    let mut si = 0usize;
    let mut gi = 0usize;
    for &t in tags {
        if t == 0 {
            if si >= solid_count as usize {
                return -1;
            }
            colliders.push(Collider::Cube([
                raw[si * 6],
                raw[si * 6 + 1],
                raw[si * 6 + 2],
                raw[si * 6 + 3],
                raw[si * 6 + 4],
                raw[si * 6 + 5],
            ]));
            si += 1;
        } else if t == 1 {
            if gi >= grids.len() {
                return -1;
            }
            colliders.push(Collider::Grid(&grids[gi]));
            gi += 1;
        } else {
            return -1;
        }
    }
    if si != solid_count as usize || gi != grid_count as usize {
        return -1;
    }

    let r = collide_mixed_checked(delta, bb, step, on_ground, &colliders);
    out_ptr.write_unaligned(r.movement[0]);
    out_ptr.add(1).write_unaligned(r.movement[1]);
    out_ptr.add(2).write_unaligned(r.movement[2]);
    if r.epsilon_unstable { 1 } else { 0 }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn header(delta: [f64; 3], solid_count: i32, grid_count: i32, collider_count: i32) -> [u8; 96] {
        let mut bytes = [0u8; 96];
        let values = [0.0, 0.0, 0.0, 0.6, 1.8, 0.6, delta[0], delta[1], delta[2]];
        unsafe {
            for (i, value) in values.into_iter().enumerate() {
                (bytes.as_mut_ptr().add(i * 8) as *mut f64).write_unaligned(value);
            }
            (bytes.as_mut_ptr().add(72) as *mut f32).write_unaligned(0.6);
            (bytes.as_mut_ptr().add(76) as *mut i32).write_unaligned(1);
            (bytes.as_mut_ptr().add(80) as *mut i32).write_unaligned(solid_count);
            (bytes.as_mut_ptr().add(84) as *mut i32).write_unaligned(grid_count);
            (bytes.as_mut_ptr().add(88) as *mut i32).write_unaligned(collider_count);
        }
        bytes
    }

    #[test]
    fn empty_solids_accept_null_and_preserve_movement_bits() {
        let delta = [0.25, -0.5, -0.0];
        let header = header(delta, 0, 0, 0);
        let mut out = [f64::NAN; 3];
        let rc = unsafe { oxi_collide(header.as_ptr(), std::ptr::null(), out.as_mut_ptr()) };
        assert_eq!(rc, 0);
        for i in 0..3 {
            assert_eq!(out[i].to_bits(), delta[i].to_bits());
        }
    }

    #[test]
    fn box_only_entries_match_between_ffi_versions() {
        let solids = [
            [0.75, 0.0, -0.25, 1.75, 1.0, 0.75],
            [-0.5, -1.0, -0.5, 0.5, 0.0, 0.5],
        ];
        let header = header([1.0, -0.4, 0.25], 2, 0, 2);
        let tags = [0u8; 2];
        let mut v1 = [f64::NAN; 3];
        let mut v2 = [f64::NAN; 3];
        unsafe {
            assert_eq!(
                oxi_collide(header.as_ptr(), solids.as_ptr().cast(), v1.as_mut_ptr()),
                0
            );
            assert_eq!(
                oxi_collide2(
                    header.as_ptr(),
                    solids.as_ptr().cast(),
                    std::ptr::null(),
                    tags.as_ptr(),
                    v2.as_mut_ptr(),
                ),
                0
            );
        }
        assert!(v1.iter().zip(v2).all(|(a, b)| a.to_bits() == b.to_bits()));
    }


    #[test]
    fn epsilon_order_dependency_requests_java_fallback() {
        let mut header = header([0.0, 0.0, -0.022360680108198], 1, 0, 1);
        let bounds = [0.7, 120.0, 3.399999988079071, 1.3, 121.8, 3.999999988079071];
        unsafe {
            for (i, value) in bounds.into_iter().enumerate() {
                (header.as_mut_ptr().add(i * 8) as *mut f64).write_unaligned(value);
            }
        }
        let curb = [[0.0, 120.0, 2.0, 2.0, 122.0, 3.4]];
        let mut out = [f64::NAN; 3];
        let rc = unsafe { oxi_collide(header.as_ptr(), curb.as_ptr().cast(), out.as_mut_ptr()) };
        assert_eq!(rc, 1);
        assert_eq!(out[2].to_bits(), (3.4 - bounds[2]).to_bits());
    }
    #[test]
    fn mixed_entry_rejects_invalid_tags_and_count_overflow() {
        let solids = [[0.75, 0.0, -0.25, 1.75, 1.0, 0.75]];
        let invalid_tag = [2u8];
        let mut out = [0.0; 3];
        let valid_counts = header([1.0, 0.0, 0.0], 1, 0, 1);
        unsafe {
            assert_eq!(
                oxi_collide2(
                    valid_counts.as_ptr(),
                    solids.as_ptr().cast(),
                    std::ptr::null(),
                    invalid_tag.as_ptr(),
                    out.as_mut_ptr(),
                ),
                -1
            );
        }

        let overflow = header([0.0; 3], i32::MAX, i32::MAX, 0);
        unsafe {
            assert_eq!(
                oxi_collide2(
                    overflow.as_ptr(),
                    std::ptr::null(),
                    std::ptr::null(),
                    std::ptr::null(),
                    out.as_mut_ptr(),
                ),
                -1
            );
        }
    }
}
