// Distance sort for Fabrolia's sensor mixins — the batch-FFI form of
// FastBitRadixSort: squared distances + a STABLE order in one call, returning
// a permutation Java applies to its Object[] (object references never cross
// the boundary).
//
// Stability contract (vanilla parity): vanilla sorts sensor lists with
// TimSort; ties (an item pile at one coordinate) must keep encounter order.
// Keying the unstable pdqsort by (distance_bits, original_index) yields
// exactly the stable result. d² is non-negative, so IEEE bit order equals
// numeric order — the same raw-bits trick the Java path uses.

pub mod ffi {
    /// xs/ys/zs: n doubles each; out_perm: n i32 slots.
    /// Returns 0 on success, -1 on bad args.
    ///
    /// # Safety
    /// Pointers must reference buffers of at least n elements.
    #[no_mangle]
    pub unsafe extern "C" fn oxi_distance_sort(
        xs: *const f64,
        ys: *const f64,
        zs: *const f64,
        tx: f64,
        ty: f64,
        tz: f64,
        n: i32,
        out_perm: *mut i32,
    ) -> i32 {
        if !(0..=1 << 20).contains(&n) {
            return -1;
        }
        let n = n as usize;
        if n == 0 {
            return 0;
        }
        if xs.is_null() || ys.is_null() || zs.is_null() || out_perm.is_null() {
            return -1;
        }
        let xs = std::slice::from_raw_parts(xs, n);
        let ys = std::slice::from_raw_parts(ys, n);
        let zs = std::slice::from_raw_parts(zs, n);

        // The distance loop autovectorizes (no branches, no calls).
        let mut keys: Vec<(u64, u32)> = Vec::with_capacity(n);
        for i in 0..n {
            let dx = xs[i] - tx;
            let dy = ys[i] - ty;
            let dz = zs[i] - tz;
            let d2 = dx * dx + dy * dy + dz * dz;
            keys.push((d2.to_bits(), i as u32));
        }
        keys.sort_unstable();

        let out = std::slice::from_raw_parts_mut(out_perm, n);
        for (k, &(_, idx)) in keys.iter().enumerate() {
            out[k] = idx as i32;
        }
        0
    }
}

#[cfg(test)]
mod tests {
    use super::ffi::oxi_distance_sort;

    /// Against a naive STABLE reference sort, with deliberate ties.
    #[test]
    fn matches_stable_reference() {
        let xs = [1.0, 3.0, 1.0, -2.0, 1.0, 0.0, 3.0, 1.0];
        let ys = [0.0; 8];
        let zs = [2.0, 0.5, 2.0, 1.0, 2.0, 0.0, 0.5, -2.0];
        let (tx, ty, tz) = (0.5, 0.0, 0.25);
        let n = xs.len();

        let mut reference: Vec<usize> = (0..n).collect();
        let d2 = |i: usize| {
            let dx = xs[i] - tx;
            let dy = ys[i] - ty;
            let dz = zs[i] - tz;
            dx * dx + dy * dy + dz * dz
        };
        reference.sort_by(|&a, &b| d2(a).partial_cmp(&d2(b)).unwrap()); // stable

        let mut perm = vec![0i32; n];
        let rc = unsafe {
            oxi_distance_sort(
                xs.as_ptr(),
                ys.as_ptr(),
                zs.as_ptr(),
                tx,
                ty,
                tz,
                n as i32,
                perm.as_mut_ptr(),
            )
        };
        assert_eq!(rc, 0);
        let got: Vec<usize> = perm.iter().map(|&v| v as usize).collect();
        assert_eq!(got, reference, "stable order must match TimSort semantics");
    }
}
