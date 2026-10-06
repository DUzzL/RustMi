// C ABI surface for the mob-cap arena. Returns are i64 (handle) / i32 (bool as 0|1) / f64
// only — never bool/i8 (the nalim sub-int ABI rule). The handle is the raw pointer to a
// leaked Box<SpawnCapState>; oxi_spawncap_free reclaims it at tick end.
//
// Record layouts (MUST match the Java marshalling in SpawnCap.java / FabroliaSpawnContext):
//   entity (32 bytes): charge f64@0, catOrd i32@8, bx i32@12, by i32@16, bz i32@20,
//                      flags i32@24 (bit0=isMob, bit1=hasCharge), pad@28
//   player (24 bytes): x f64@0, z f64@8, spectator i32@16, pad@20
//   caps           : 8 × i32

use super::{near, ChargeRec, PlayerRec, SpawnCapState, N_CATEGORIES};
use std::sync::atomic::AtomicI32;

const ENT_STRIDE: usize = 32;
const PLY_STRIDE: usize = 24;

#[inline]
unsafe fn rd_f64(p: *const u8, off: usize) -> f64 {
    (p.add(off) as *const f64).read_unaligned()
}
#[inline]
unsafe fn rd_i32(p: *const u8, off: usize) -> i32 {
    (p.add(off) as *const i32).read_unaligned()
}

/// Build the per-tick arena. Single-threaded (tick head). @return handle, or 0 on bad args.
///
/// # Safety
/// `entities`/`players` must reference `n_ent`/`n_play` records of the strides above;
/// `caps` must point to 8 i32.
#[no_mangle]
pub unsafe extern "C" fn oxi_spawncap_build(
    entities: *const u8,
    n_ent: i32,
    players: *const u8,
    n_play: i32,
    spawnable: i32,
    caps: *const i32,
) -> i64 {
    if n_ent < 0 || n_play < 0 || caps.is_null() {
        return 0;
    }
    if (n_ent > 0 && entities.is_null()) || (n_play > 0 && players.is_null()) {
        return 0;
    }

    let np = n_play as usize;
    let ne = n_ent as usize;

    let mut caps_arr = [0i32; N_CATEGORIES];
    for (i, c) in caps_arr.iter_mut().enumerate() {
        *c = *caps.add(i);
    }

    let mut player_vec: Vec<PlayerRec> = Vec::with_capacity(np);
    for i in 0..np {
        let base = players.add(i * PLY_STRIDE);
        player_vec.push(PlayerRec {
            x: rd_f64(base, 0),
            z: rd_f64(base, 8),
            spectator: rd_i32(base, 16) != 0,
        });
    }

    let mut local: Vec<AtomicI32> = (0..np * N_CATEGORIES).map(|_| AtomicI32::new(0)).collect();
    let mut global = [0i32; N_CATEGORIES];
    let mut charges: Vec<ChargeRec> = Vec::new();

    for i in 0..ne {
        let base = entities.add(i * ENT_STRIDE);
        let charge = rd_f64(base, 0);
        let cat = rd_i32(base, 8);
        let bx = rd_i32(base, 12);
        let by = rd_i32(base, 16);
        let bz = rd_i32(base, 20);
        let flags = rd_i32(base, 24);
        if cat < 0 || cat as usize >= N_CATEGORIES {
            continue;
        }
        let cat = cat as usize;
        let is_mob = flags & 1 != 0;
        let has_charge = flags & 2 != 0;
        let cx = bx >> 4;
        let cz = bz >> 4;

        global[cat] += 1;
        if is_mob {
            for (pi, p) in player_vec.iter().enumerate() {
                if near(p, cx, cz) {
                    // build is single-threaded; relaxed atomic add is the cheap path
                    *local[pi * N_CATEGORIES + cat].get_mut() += 1;
                }
            }
        }
        // vanilla PotentialCalculator.addCharge only records charge != 0.0
        if has_charge && charge != 0.0 {
            charges.push(ChargeRec {
                x: bx,
                y: by,
                z: bz,
                charge,
            });
        }
    }

    let st = Box::new(SpawnCapState {
        global: global.map(AtomicI32::new),
        local,
        players: player_vec,
        charges,
        caps: caps_arr,
        spawnable_chunks: spawnable,
    });
    Box::into_raw(st) as i64
}

#[inline]
unsafe fn state<'a>(handle: i64) -> Option<&'a SpawnCapState> {
    if handle == 0 {
        None
    } else {
        Some(&*(handle as *const SpawnCapState))
    }
}

/// vanilla canSpawnForCategoryGlobal → 1 if a spawn is allowed, 0 if capped, -1 on bad args.
///
/// # Safety
/// `handle` must be a live handle from `oxi_spawncap_build`.
#[no_mangle]
pub unsafe extern "C" fn oxi_spawncap_can_global(handle: i64, cat: i32) -> i32 {
    let Some(st) = state(handle) else { return -1 };
    if cat < 0 || cat as usize >= N_CATEGORIES {
        return -1;
    }
    st.can_global(cat as usize) as i32
}

/// vanilla LocalMobCapCalculator.canSpawn → 1 allowed, 0 capped/no-near, -1 on bad args.
///
/// # Safety
/// `handle` must be a live handle from `oxi_spawncap_build`.
#[no_mangle]
pub unsafe extern "C" fn oxi_spawncap_can_local(handle: i64, cat: i32, cx: i32, cz: i32) -> i32 {
    let Some(st) = state(handle) else { return -1 };
    if cat < 0 || cat as usize >= N_CATEGORIES {
        return -1;
    }
    st.can_local(cat as usize, cx, cz) as i32
}

/// Fabrolia fused local-cap check + pack reservation. Returns 1 when reserved,
/// 0 when every near player is capped (all provisional increments rolled back),
/// or -1 on bad args.
///
/// # Safety
/// `handle` must be a live handle from `oxi_spawncap_build`; safe to call concurrently.
#[no_mangle]
pub unsafe extern "C" fn oxi_spawncap_try_reserve_pack(
    handle: i64,
    cat: i32,
    cx: i32,
    cz: i32,
    count: i32,
) -> i32 {
    let Some(st) = state(handle) else { return -1 };
    if cat < 0 || cat as usize >= N_CATEGORIES || count <= 0 {
        return -1;
    }
    st.try_reserve_pack(cat as usize, cx, cz, count) as i32
}

/// Return an unconsumed Fabrolia pack reservation. Returns 0, or -1 on bad args.
///
/// # Safety
/// `handle` must be a live handle from `oxi_spawncap_build`; safe to call concurrently.
#[no_mangle]
pub unsafe extern "C" fn oxi_spawncap_release_pack(
    handle: i64,
    cat: i32,
    cx: i32,
    cz: i32,
    count: i32,
) -> i32 {
    let Some(st) = state(handle) else { return -1 };
    if cat < 0 || cat as usize >= N_CATEGORIES || count <= 0 {
        return -1;
    }
    st.release_pack(cat as usize, cx, cz, count);
    0
}

/// vanilla afterSpawn (global +1, addMob to near players). @return 0, or -1 on bad args.
///
/// # Safety
/// `handle` must be a live handle from `oxi_spawncap_build`; safe to call concurrently.
#[no_mangle]
pub unsafe extern "C" fn oxi_spawncap_add(
    handle: i64,
    cat: i32,
    cx: i32,
    cz: i32,
    is_mob: i32,
) -> i32 {
    let Some(st) = state(handle) else { return -1 };
    if cat < 0 || cat as usize >= N_CATEGORIES {
        return -1;
    }
    st.add(cat as usize, cx, cz, is_mob != 0);
    0
}

/// vanilla PotentialCalculator.getPotentialEnergyChange(pos, charge).
///
/// # Safety
/// `handle` must be a live handle from `oxi_spawncap_build`.
#[no_mangle]
pub unsafe extern "C" fn oxi_spawncap_potential(
    handle: i64,
    x: i32,
    y: i32,
    z: i32,
    charge: f64,
) -> f64 {
    let Some(st) = state(handle) else { return 0.0 };
    st.potential(x, y, z, charge)
}

/// Reclaim the arena (tick end). No-op on 0. @return 0 (i32 to avoid a void-return binding).
///
/// # Safety
/// `handle` must be a handle from `oxi_spawncap_build` not yet freed; no concurrent use.
#[no_mangle]
pub unsafe extern "C" fn oxi_spawncap_free(handle: i64) -> i32 {
    if handle != 0 {
        drop(Box::from_raw(handle as *mut SpawnCapState));
    }
    0
}
