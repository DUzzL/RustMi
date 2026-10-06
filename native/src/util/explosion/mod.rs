// Vanilla-exact explosion ray sweep (see docs/CODEMAP.md, "Explosions").
pub mod ffi;
//
// Port of ServerExplosion.calculateExplodedPositions (26.1.2 source; checked against
// 26.3 by /rustmi explosion shadow): 1352 rays
// (the 16³ surface), step 0.3, per-step resistance decrement
// (res + 0.3f) * 0.3f and the inter-step 0.22500001f budget burn — all in
// the same float widths vanilla uses (f32 power math, f64 position
// accumulation with 0.3f WIDENED to f64).
//
// RNG: vanilla draws world.random.nextFloat() once per ray INSIDE the cell
// loop. The caller (Java) draws those floats in exactly that order and
// passes them in — the Java-side RNG state stays bit-identical for the
// shuffle and fire rolls that follow.
//
// Output: destroyed positions in DISCOVERY ORDER, deduplicated by first
// occurrence. Java re-inserts them into a real HashSet in this order,
// reproducing vanilla's set iteration (and therefore the shuffle input)
// without modelling HashMap internals at all.
//
// The snapshot is a dense f32 grid of per-position blast resistance with
// -1.0 meaning "Optional.empty()" (air with no fluid — no resistance
// decrement). Rays that leave the snapshot box (must not happen — the box
// is sized to the maximum theoretical reach) abort the whole sweep and the
// caller falls back to its exact Java port using the same floats.

/// World height/border limits for the vanilla isInWorldBounds break.
#[derive(Clone, Copy)]
pub struct Bounds {
    pub min_y: i32,
    pub max_y: i32, // exclusive
}

/// Dense f32 resistance grid: the reference oracle the packed kernel is tested against.
#[cfg(test)]
pub struct Snapshot<'a> {
    pub origin: (i32, i32, i32),
    pub dims: (i32, i32, i32),
    pub resistance: &'a [f32],
}

#[cfg(test)]
impl Snapshot<'_> {
    #[inline]
    fn get(&self, x: i32, y: i32, z: i32) -> Option<f32> {
        let lx = x - self.origin.0;
        let ly = y - self.origin.1;
        let lz = z - self.origin.2;
        if lx < 0 || ly < 0 || lz < 0 || lx >= self.dims.0 || ly >= self.dims.1 || lz >= self.dims.2
        {
            return None;
        }
        let idx = ((ly * self.dims.2 + lz) * self.dims.0 + lx) as usize;
        Some(self.resistance[idx])
    }

    #[inline]
    fn local_index(&self, x: i32, y: i32, z: i32) -> usize {
        let lx = x - self.origin.0;
        let ly = y - self.origin.1;
        let lz = z - self.origin.2;
        ((ly * self.dims.2 + lz) * self.dims.0 + lx) as usize
    }
}

/// 0.3f widened to double — the exact constant vanilla adds per step.
const STEP: f64 = 0.3f32 as f64;

/// Palette-packed snapshot: per-section copies of MC's own storage form
/// (palette resistances + raw BitStorage longs) instead of a dense f32 cube.
/// Built Java-side at memcpy speed; cell reads do the bit-extract here.
pub struct PackedSnapshot<'a> {
    /// Box bounds in block coords — rays beyond these escape (same safety
    /// net as the dense snapshot, even though section data extends further).
    pub origin: (i32, i32, i32),
    pub dims: (i32, i32, i32),
    /// Section grid: origin in section coords, counts per axis.
    pub sec_origin: (i32, i32, i32),
    pub sec_n: (i32, i32, i32),
    /// One descriptor per section, y-major: ((sy*nz + sz)*nx + sx).
    pub descriptors: &'a [SectionDesc],
    pub blob: &'a [u8],
}

pub const MODE_UNIFORM: i32 = 0;
pub const MODE_PACKED: i32 = 1;
pub const MODE_DENSE: i32 = 2;

/// Mirrors the Java-side 24-byte descriptor exactly.
#[repr(C)]
#[derive(Clone, Copy)]
pub struct SectionDesc {
    pub mode: i32,
    pub bits: i32,
    /// values per long = 64 / bits (MC packs without crossing longs).
    pub vpl: i32,
    /// Byte offset into blob: uniform → 1×f32; packed → palette f32s.
    pub pal_off: i32,
    /// Byte offset into blob: packed → raw longs; dense → 4096×f32.
    pub data_off: i32,
    pub _pad: i32,
}

impl PackedSnapshot<'_> {
    #[inline]
    fn in_box(&self, x: i32, y: i32, z: i32) -> bool {
        let lx = x - self.origin.0;
        let ly = y - self.origin.1;
        let lz = z - self.origin.2;
        lx >= 0 && ly >= 0 && lz >= 0 && lx < self.dims.0 && ly < self.dims.1 && lz < self.dims.2
    }

    #[inline]
    fn local_index(&self, x: i32, y: i32, z: i32) -> usize {
        let lx = x - self.origin.0;
        let ly = y - self.origin.1;
        let lz = z - self.origin.2;
        ((ly * self.dims.2 + lz) * self.dims.0 + lx) as usize
    }

    /// Resistance at a block position (must be inside the box).
    /// -1.0 = air sentinel, exactly as the dense snapshot.
    #[inline]
    fn res_at(&self, x: i32, y: i32, z: i32) -> f32 {
        let sx = (x >> 4) - self.sec_origin.0;
        let sy = (y >> 4) - self.sec_origin.1;
        let sz = (z >> 4) - self.sec_origin.2;
        let sidx = ((sy * self.sec_n.2 + sz) * self.sec_n.0 + sx) as usize;
        let d = &self.descriptors[sidx];
        match d.mode {
            MODE_UNIFORM => self.blob_f32(d.pal_off as usize),
            MODE_PACKED => {
                // MC section cell index: (y&15)<<8 | (z&15)<<4 | (x&15).
                let cell = (((y & 15) << 8) | ((z & 15) << 4) | (x & 15)) as usize;
                let li = cell / d.vpl as usize;
                let shift = (cell - li * d.vpl as usize) * d.bits as usize;
                let word = self.blob_u64(d.data_off as usize + li * 8);
                let idx = ((word >> shift) & ((1u64 << d.bits) - 1)) as usize;
                self.blob_f32(d.pal_off as usize + idx * 4)
            }
            _ => {
                let cell = (((y & 15) << 8) | ((z & 15) << 4) | (x & 15)) as usize;
                self.blob_f32(d.data_off as usize + cell * 4)
            }
        }
    }

    #[inline]
    fn blob_f32(&self, off: usize) -> f32 {
        f32::from_le_bytes(self.blob[off..off + 4].try_into().unwrap())
    }

    #[inline]
    fn blob_u64(&self, off: usize) -> u64 {
        u64::from_le_bytes(self.blob[off..off + 8].try_into().unwrap())
    }
}

enum SweepStatus {
    Ok,
    Escaped,
}

/// The vanilla ray loop, generic over the resistance source. `air_out`
/// non-None mirrors Lithium skip_air: air cells (sentinel -1) split into it,
/// non-air keep discovery order in `out`. Shared dedup — a cell lands in
/// exactly one stream, first ray that reaches it wins (set semantics).
#[inline]
fn sweep_core<F>(
    center: (f64, f64, f64),
    power: f32,
    floats: &[f32],
    cells: usize,
    bounds: Bounds,
    mut probe: F,
    out: &mut Vec<(i32, i32, i32)>,
    mut air_out: Option<&mut Vec<(i32, i32, i32)>>,
) -> SweepStatus
where
    F: FnMut(i32, i32, i32) -> Option<(f32, usize)>, // (resistance, dedup index)
{
    out.clear();
    if let Some(a) = air_out.as_deref_mut() {
        a.clear();
    }
    let mut seen = vec![0u64; (cells + 63) / 64];

    let mut fi = 0usize;
    for xx in 0..16 {
        for yy in 0..16 {
            for zz in 0..16 {
                if xx != 0 && xx != 15 && yy != 0 && yy != 15 && zz != 0 && zz != 15 {
                    continue;
                }
                // double xd = xx / 15.0F * 2.0F - 1.0F — f32 math, widened.
                let mut xd = (xx as f32 / 15.0f32 * 2.0f32 - 1.0f32) as f64;
                let mut yd = (yy as f32 / 15.0f32 * 2.0f32 - 1.0f32) as f64;
                let mut zd = (zz as f32 / 15.0f32 * 2.0f32 - 1.0f32) as f64;
                let d = (xd * xd + yd * yd + zd * zd).sqrt();
                xd /= d;
                yd /= d;
                zd /= d;

                if fi >= floats.len() {
                    return SweepStatus::Escaped;
                }
                // float remaining = power * (0.7F + nextFloat() * 0.6F)
                let mut remaining: f32 = power * (0.7f32 + floats[fi] * 0.6f32);
                fi += 1;

                let (mut xp, mut yp, mut zp) = center;

                // for (; remaining > 0.0F; remaining -= 0.22500001F)
                while remaining > 0.0f32 {
                    let bx = xp.floor() as i32;
                    let by = yp.floor() as i32;
                    let bz = zp.floor() as i32;

                    // Level.isInWorldBounds: height + ±30M horizontal.
                    if by < bounds.min_y
                        || by >= bounds.max_y
                        || bx < -30_000_000
                        || bx >= 30_000_000
                        || bz < -30_000_000
                        || bz >= 30_000_000
                    {
                        break;
                    }

                    let Some((res, idx)) = probe(bx, by, bz) else {
                        return SweepStatus::Escaped; // left the box — caller falls back
                    };
                    if res >= 0.0 {
                        remaining -= (res + 0.3f32) * 0.3f32;
                    }

                    // shouldBlockExplode is gated to the always-true default
                    // by the caller's qualification.
                    if remaining > 0.0f32 {
                        let (w, b) = (idx / 64, idx % 64);
                        if seen[w] & (1u64 << b) == 0 {
                            seen[w] |= 1u64 << b;
                            match air_out.as_deref_mut() {
                                Some(a) if res < 0.0 => a.push((bx, by, bz)),
                                _ => out.push((bx, by, bz)),
                            }
                        }
                    }

                    xp += xd * STEP;
                    yp += yd * STEP;
                    zp += zd * STEP;
                    remaining -= 0.225_000_01_f32;
                }
            }
        }
    }
    if fi == floats.len() {
        SweepStatus::Ok
    } else {
        SweepStatus::Escaped
    }
}

/// Run the sweep over a dense snapshot (test oracle). Returns true on success (positions
/// in `out`, discovery order, air included — pure vanilla list); false when
/// a ray escaped the snapshot (caller must fall back).
#[cfg(test)]
pub fn sweep(
    center: (f64, f64, f64),
    power: f32,
    floats: &[f32],
    snap: &Snapshot,
    bounds: Bounds,
    out: &mut Vec<(i32, i32, i32)>,
) -> bool {
    let cells = (snap.dims.0 * snap.dims.1 * snap.dims.2) as usize;
    matches!(
        sweep_core(
            center,
            power,
            floats,
            cells,
            bounds,
            |x, y, z| snap.get(x, y, z).map(|r| (r, snap.local_index(x, y, z))),
            out,
            None,
        ),
        SweepStatus::Ok
    )
}

/// Run the sweep over a palette-packed snapshot, with optional Lithium-style
/// air split. Returns true on success; false = escape (caller falls back).
pub fn sweep_packed(
    center: (f64, f64, f64),
    power: f32,
    floats: &[f32],
    snap: &PackedSnapshot,
    bounds: Bounds,
    skip_air: bool,
    out: &mut Vec<(i32, i32, i32)>,
    air_out: &mut Vec<(i32, i32, i32)>,
) -> bool {
    let cells = (snap.dims.0 * snap.dims.1 * snap.dims.2) as usize;
    air_out.clear();
    matches!(
        sweep_core(
            center,
            power,
            floats,
            cells,
            bounds,
            |x, y, z| {
                if !snap.in_box(x, y, z) {
                    return None;
                }
                Some((snap.res_at(x, y, z), snap.local_index(x, y, z)))
            },
            out,
            if skip_air { Some(air_out) } else { None },
        ),
        SweepStatus::Ok
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn snap_uniform(_origin: (i32, i32, i32), dims: (i32, i32, i32), r: f32) -> (Vec<f32>, Bounds) {
        (
            vec![r; (dims.0 * dims.1 * dims.2) as usize],
            Bounds {
                min_y: -64,
                max_y: 320,
            },
        )
    }

    #[test]
    fn all_air_explosion_carves_sphere() {
        let dims = (33, 33, 33);
        let origin = (-16, 48, -16);
        let (res, bounds) = snap_uniform(origin, dims, -1.0); // all air
        let snap = Snapshot {
            origin,
            dims,
            resistance: &res,
        };
        let floats = vec![0.5f32; 1352];
        let mut out = Vec::new();
        assert!(sweep(
            (0.5, 64.5, 0.5),
            4.0,
            &floats,
            &snap,
            bounds,
            &mut out
        ));
        assert!(
            out.len() > 500,
            "air sphere should carve hundreds of positions, got {}",
            out.len()
        );
        // dedup: no repeats
        let mut s = std::collections::HashSet::new();
        for p in &out {
            assert!(s.insert(*p), "duplicate {p:?}");
        }
        // centre block destroyed first (ray 0 starts there)
        assert_eq!(out[0], (0, 64, 0));
    }

    #[test]
    fn bedrock_resistance_stops_rays() {
        let dims = (33, 33, 33);
        let origin = (-16, 48, -16);
        let (mut res, bounds) = snap_uniform(origin, dims, -1.0);
        // a 3600-resistance shell can't be pierced: only the centre column
        // cells before the shell appear.
        for v in res.iter_mut() {
            *v = 3600.0;
        }
        let snap = Snapshot {
            origin,
            dims,
            resistance: &res,
        };
        let floats = vec![0.5f32; 1352];
        let mut out = Vec::new();
        assert!(sweep(
            (0.5, 64.5, 0.5),
            4.0,
            &floats,
            &snap,
            bounds,
            &mut out
        ));
        assert!(
            out.is_empty(),
            "bedrock everywhere → nothing destroyed, got {}",
            out.len()
        );
    }

    #[test]
    fn stone_crater_smaller_than_air() {
        let dims = (33, 33, 33);
        let origin = (-16, 48, -16);
        let (res_air, bounds) = snap_uniform(origin, dims, -1.0);
        let res_stone = vec![6.0f32; res_air.len()]; // stone blast resistance
        let floats = vec![0.5f32; 1352];
        let mut air_out = Vec::new();
        let mut stone_out = Vec::new();
        let s1 = Snapshot {
            origin,
            dims,
            resistance: &res_air,
        };
        let s2 = Snapshot {
            origin,
            dims,
            resistance: &res_stone,
        };
        assert!(sweep(
            (0.5, 64.5, 0.5),
            4.0,
            &floats,
            &s1,
            bounds,
            &mut air_out
        ));
        assert!(sweep(
            (0.5, 64.5, 0.5),
            4.0,
            &floats,
            &s2,
            bounds,
            &mut stone_out
        ));
        assert!(!stone_out.is_empty() && stone_out.len() < air_out.len());
    }

    #[test]
    fn escape_returns_false() {
        let dims = (3, 3, 3); // deliberately tiny — rays leave immediately
        let origin = (-1, 63, -1);
        let (res, bounds) = snap_uniform(origin, dims, -1.0);
        let snap = Snapshot {
            origin,
            dims,
            resistance: &res,
        };
        let floats = vec![0.5f32; 1352];
        let mut out = Vec::new();
        assert!(!sweep(
            (0.5, 64.5, 0.5),
            4.0,
            &floats,
            &snap,
            bounds,
            &mut out
        ));
    }

    #[test]
    fn build_limit_breaks_ray_without_escape() {
        let dims = (33, 33, 33);
        let origin = (-16, 48, -16);
        let (res, _) = snap_uniform(origin, dims, -1.0);
        let snap = Snapshot {
            origin,
            dims,
            resistance: &res,
        };
        // world bottom right under the explosion: downward rays break at the
        // limit instead of escaping the snapshot.
        let bounds = Bounds {
            min_y: 60,
            max_y: 80,
        };
        let floats = vec![0.5f32; 1352];
        let mut out = Vec::new();
        assert!(sweep(
            (0.5, 64.5, 0.5),
            4.0,
            &floats,
            &snap,
            bounds,
            &mut out
        ));
        assert!(out.iter().all(|p| p.1 >= 60));
    }

    /// Build the packed form of a dense world function, mixing all three
    /// section modes, and require bit-identical sweep results.
    #[test]
    fn packed_matches_dense() {
        let origin = (-13, 50, -9); // deliberately not section-aligned
        let dims = (27, 27, 27);
        let bounds = Bounds {
            min_y: -64,
            max_y: 320,
        };

        // Deterministic pseudo-world: palette of 6 resistances incl. air.
        let palette = [-1.0f32, -1.0, 0.0, 6.0, 100.0, 0.5];
        let world = |x: i32, y: i32, z: i32| -> usize {
            let h = (x.wrapping_mul(374761393)
                ^ y.wrapping_mul(668265263)
                ^ z.wrapping_mul(2147483647))
            .wrapping_mul(1274126177);
            ((h >> 8) & 7).min(5) as usize
        };

        // dense
        let mut dense = vec![0.0f32; (dims.0 * dims.1 * dims.2) as usize];
        let mut idx = 0usize;
        for y in 0..dims.1 {
            for z in 0..dims.2 {
                for x in 0..dims.0 {
                    dense[idx] = palette[world(origin.0 + x, origin.1 + y, origin.2 + z)];
                    idx += 1;
                }
            }
        }
        let dsnap = Snapshot {
            origin,
            dims,
            resistance: &dense,
        };

        // packed: section grid covering the box
        let sec_origin = (origin.0 >> 4, origin.1 >> 4, origin.2 >> 4);
        let sec_max = (
            (origin.0 + dims.0 - 1) >> 4,
            (origin.1 + dims.1 - 1) >> 4,
            (origin.2 + dims.2 - 1) >> 4,
        );
        let sec_n = (
            sec_max.0 - sec_origin.0 + 1,
            sec_max.1 - sec_origin.1 + 1,
            sec_max.2 - sec_origin.2 + 1,
        );
        let mut blob: Vec<u8> = Vec::new();
        let mut descriptors = Vec::new();
        let mut mode_rr = 0; // rotate uniform-ish/packed/dense handling
        for sy in 0..sec_n.1 {
            for sz in 0..sec_n.2 {
                for sx in 0..sec_n.0 {
                    let bx0 = (sec_origin.0 + sx) << 4;
                    let by0 = (sec_origin.1 + sy) << 4;
                    let bz0 = (sec_origin.2 + sz) << 4;
                    mode_rr += 1;
                    if mode_rr % 3 == 0 {
                        // dense mini-grid
                        let data_off = blob.len();
                        for cell in 0..4096 {
                            let (cy, cz, cx) = (cell >> 8, (cell >> 4) & 15, cell & 15);
                            let r = palette[world(bx0 + cx, by0 + cy, bz0 + cz)];
                            blob.extend_from_slice(&r.to_le_bytes());
                        }
                        descriptors.push(SectionDesc {
                            mode: MODE_DENSE,
                            bits: 0,
                            vpl: 0,
                            pal_off: 0,
                            data_off: data_off as i32,
                            _pad: 0,
                        });
                    } else {
                        // packed with 3-bit entries (6-entry palette)
                        let bits = 3i32;
                        let vpl = 64 / bits;
                        let pal_off = blob.len();
                        for r in palette {
                            blob.extend_from_slice(&r.to_le_bytes());
                        }
                        let data_off = blob.len();
                        let nlongs = (4096 + vpl as usize - 1) / vpl as usize;
                        let mut longs = vec![0u64; nlongs];
                        for cell in 0..4096usize {
                            let (cy, cz, cx) = (cell >> 8, (cell >> 4) & 15, cell & 15);
                            let pidx =
                                world(bx0 + cx as i32, by0 + cy as i32, bz0 + cz as i32) as u64;
                            let li = cell / vpl as usize;
                            let sh = (cell - li * vpl as usize) * bits as usize;
                            longs[li] |= pidx << sh;
                        }
                        for w in longs {
                            blob.extend_from_slice(&w.to_le_bytes());
                        }
                        descriptors.push(SectionDesc {
                            mode: MODE_PACKED,
                            bits,
                            vpl,
                            pal_off: pal_off as i32,
                            data_off: data_off as i32,
                            _pad: 0,
                        });
                    }
                }
            }
        }
        let psnap = PackedSnapshot {
            origin,
            dims,
            sec_origin,
            sec_n,
            descriptors: &descriptors,
            blob: &blob,
        };

        let floats: Vec<f32> = (0..1352)
            .map(|i| ((i * 7919) % 1000) as f32 / 1000.0)
            .collect();
        let center = (0.5, 64.5, 0.5);

        let mut dense_out = Vec::new();
        assert!(sweep(center, 4.0, &floats, &dsnap, bounds, &mut dense_out));

        // no skip: identical lists
        let (mut p_out, mut p_air) = (Vec::new(), Vec::new());
        assert!(sweep_packed(
            center, 4.0, &floats, &psnap, bounds, false, &mut p_out, &mut p_air
        ));
        assert_eq!(dense_out, p_out, "packed (no skip) must equal dense");
        assert!(p_air.is_empty());

        // skip-air: non-air stream == dense filtered; air stream == dense air
        let (mut s_out, mut s_air) = (Vec::new(), Vec::new());
        assert!(sweep_packed(
            center, 4.0, &floats, &psnap, bounds, true, &mut s_out, &mut s_air
        ));
        let dense_non_air: Vec<_> = dense_out
            .iter()
            .copied()
            .filter(|p| dense[dsnap.local_index(p.0, p.1, p.2)] >= 0.0)
            .collect();
        let dense_air: Vec<_> = dense_out
            .iter()
            .copied()
            .filter(|p| dense[dsnap.local_index(p.0, p.1, p.2)] < 0.0)
            .collect();
        assert_eq!(dense_non_air, s_out, "skip-air non-air stream mismatch");
        assert_eq!(dense_air, s_air, "skip-air air stream mismatch");
    }

    /// Uniform sections (incl. negative coords) keep parity too.
    #[test]
    fn packed_uniform_sections() {
        let origin = (-20, -10, -20);
        let dims = (25, 25, 25);
        let bounds = Bounds {
            min_y: -64,
            max_y: 320,
        };
        let sec_origin = (origin.0 >> 4, origin.1 >> 4, origin.2 >> 4);
        let sec_max = (
            (origin.0 + 24) >> 4,
            (origin.1 + 24) >> 4,
            (origin.2 + 24) >> 4,
        );
        let sec_n = (
            sec_max.0 - sec_origin.0 + 1,
            sec_max.1 - sec_origin.1 + 1,
            sec_max.2 - sec_origin.2 + 1,
        );

        // all air except the bottom section layer = stone 6.0
        let mut blob: Vec<u8> = Vec::new();
        let air_off = blob.len();
        blob.extend_from_slice(&(-1.0f32).to_le_bytes());
        let stone_off = blob.len();
        blob.extend_from_slice(&6.0f32.to_le_bytes());
        let mut descriptors = Vec::new();
        for sy in 0..sec_n.1 {
            for _sz in 0..sec_n.2 {
                for _sx in 0..sec_n.0 {
                    descriptors.push(SectionDesc {
                        mode: MODE_UNIFORM,
                        bits: 0,
                        vpl: 0,
                        pal_off: if sy == 0 {
                            stone_off as i32
                        } else {
                            air_off as i32
                        },
                        data_off: 0,
                        _pad: 0,
                    });
                }
            }
        }
        let psnap = PackedSnapshot {
            origin,
            dims,
            sec_origin,
            sec_n,
            descriptors: &descriptors,
            blob: &blob,
        };

        let mut dense = vec![0.0f32; (dims.0 * dims.1 * dims.2) as usize];
        let mut idx = 0;
        for y in 0..dims.1 {
            for z in 0..dims.2 {
                for x in 0..dims.0 {
                    let sy = ((origin.1 + y) >> 4) - sec_origin.1;
                    dense[idx] = if sy == 0 { 6.0 } else { -1.0 };
                    idx += 1;
                    let _ = (x, z);
                }
            }
        }
        let dsnap = Snapshot {
            origin,
            dims,
            resistance: &dense,
        };

        let floats: Vec<f32> = (0..1352).map(|i| ((i * 31) % 997) as f32 / 997.0).collect();
        let center = (-8.5, 2.5, -8.5);
        let mut a = Vec::new();
        let (mut b, mut air) = (Vec::new(), Vec::new());
        assert!(sweep(center, 4.0, &floats, &dsnap, bounds, &mut a));
        assert!(sweep_packed(
            center, 4.0, &floats, &psnap, bounds, false, &mut b, &mut air
        ));
        assert_eq!(a, b);
    }
}
