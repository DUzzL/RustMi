// Mob-cap state for Fabrolia's parallel spawn — the off-heap, Send+Sync-by-construction
// replacement for `NaturalSpawner.SpawnState` (global per-category counts) +
// `LocalMobCapCalculator` (per-player per-category counts + near-player resolution) +
// `PotentialCalculator` (charge field). Built once per level tick on the server thread,
// then read / atomically incremented LOCK-FREE by the region workers during the parallel
// spawn phase. No HashMap touches the concurrent path, so the whole class of "a map isn't
// safely concurrent" spawn races (the 2026-06-30 getPlayersNear computeIfAbsent bug) is a
// compile-time impossibility here.
//
// Vanilla parity (bit-exact, no behaviour change):
//   * global cap  : count[cat] < maxPerChunk[cat] * spawnableChunks / 289   (int division)
//   * local cap   : SOME near, non-spectator player has localCount[p][cat] < maxPerChunk[cat]
//                   (empty near-list => false — the exact footgun the Java bug tripped)
//   * pack reserve: atomically +count to EVERY near player; approve when any old count < cap,
//                   otherwise roll every touched counter back exactly (Fabrolia contract)
//   * near player : !spectator && euclidean²(chunkCenter, player) < 16384  (128 blocks)
//                   chunkCenter = (cx*16+8, cz*16+8); matches getPlayersCloseForSpawning.
//                   Vanilla first asks DistanceManager.hasPlayersNearby, a fixed 8-chunk
//                   tracker (independent of view distance) fed by the player's registered
//                   chunk; every chunk centre < 128 blocks away is within 8 chunks, so the
//                   two agree while the tracker is current. Known approximation: right after
//                   a player moves more than 8 chunks, before ChunkMap.move re-registers
//                   them, vanilla still sees no player near the new chunks and this test does.
//   * potential   : charge * Σ_i (charge_i / sqrt(distSq_i)), distSq = blockPos.distSqr,
//                   distSq==0 => +inf, charge==0 => 0 (PotentialCalculator.getPotentialEnergyChange);
//                   zero charges are never recorded (addCharge)

use std::sync::atomic::{AtomicI32, Ordering};

pub const N_CATEGORIES: usize = 8;
const MAGIC: i64 = 289; // (2*8+1)^2 — vanilla NaturalSpawner.MAGIC_NUMBER
const NEAR_SQ: f64 = 16384.0; // 128^2

#[derive(Clone, Copy)]
struct PlayerRec {
    x: f64,
    z: f64,
    spectator: bool,
}

/// !spectator && euclidean²(chunkCenter, player) < 128². Free fn so the build phase can use
/// it before the state is constructed (and the methods below delegate to it).
#[inline]
fn near(p: &PlayerRec, cx: i32, cz: i32) -> bool {
    if p.spectator {
        return false;
    }
    let center_x = (cx * 16 + 8) as f64;
    let center_z = (cz * 16 + 8) as f64;
    let dx = center_x - p.x;
    let dz = center_z - p.z;
    dx * dx + dz * dz < NEAR_SQ
}

#[derive(Clone, Copy)]
struct ChargeRec {
    x: i32,
    y: i32,
    z: i32,
    charge: f64,
}

pub struct SpawnCapState {
    global: [AtomicI32; N_CATEGORIES],
    /// flat n_players * N_CATEGORIES
    local: Vec<AtomicI32>,
    players: Vec<PlayerRec>,
    charges: Vec<ChargeRec>,
    caps: [i32; N_CATEGORIES],
    spawnable_chunks: i32,
}

impl SpawnCapState {
    #[inline]
    fn local_idx(&self, player: usize, cat: usize) -> usize {
        player * N_CATEGORIES + cat
    }

    #[inline]
    fn near_local_indices(&self, cat: usize, cx: i32, cz: i32) -> impl Iterator<Item = usize> + '_ {
        self.players
            .iter()
            .enumerate()
            .filter(move |&(_, p)| near(p, cx, cz))
            .map(move |(i, _)| self.local_idx(i, cat))
    }

    /// vanilla canSpawnForCategoryGlobal
    fn can_global(&self, cat: usize) -> bool {
        let limit = (self.caps[cat] as i64 * self.spawnable_chunks as i64 / MAGIC) as i32;
        self.global[cat].load(Ordering::Relaxed) < limit
    }

    /// vanilla LocalMobCapCalculator.canSpawn — ∃ near player with room. Empty near => false.
    fn can_local(&self, cat: usize, cx: i32, cz: i32) -> bool {
        let cap = self.caps[cat];
        self.near_local_indices(cat, cx, cz)
            .any(|idx| self.local[idx].load(Ordering::Relaxed) < cap)
    }

    /// Fabrolia's fused local-cap check + pack reservation. The first pass
    /// must touch every near player even after finding room: reservations and
    /// vanilla addMob have the same all-near-players shape. A rejected pass
    /// restores the exact same immutable near-player set.
    fn try_reserve_pack(&self, cat: usize, cx: i32, cz: i32, count: i32) -> bool {
        let cap = self.caps[cat];
        let mut any_room = false;
        for idx in self.near_local_indices(cat, cx, cz) {
            if self.local[idx].fetch_add(count, Ordering::Relaxed) < cap {
                any_room = true;
            }
        }
        if any_room {
            return true;
        }
        for idx in self.near_local_indices(cat, cx, cz) {
            self.local[idx].fetch_sub(count, Ordering::Relaxed);
        }
        false
    }

    /// Return an unconsumed pack reservation. Actual spawns were counted
    /// separately through add(), so reserve followed by release has zero net
    /// effect beyond those real additions.
    fn release_pack(&self, cat: usize, cx: i32, cz: i32, count: i32) {
        for idx in self.near_local_indices(cat, cx, cz) {
            self.local[idx].fetch_sub(count, Ordering::Relaxed);
        }
    }

    /// vanilla afterSpawn: global +1 and addMob (+1 to every near player's local count).
    fn add(&self, cat: usize, cx: i32, cz: i32, is_mob: bool) {
        self.global[cat].fetch_add(1, Ordering::Relaxed);
        if is_mob {
            for idx in self.near_local_indices(cat, cx, cz) {
                self.local[idx].fetch_add(1, Ordering::Relaxed);
            }
        }
    }

    /// vanilla PotentialCalculator.getPotentialEnergyChange(pos, charge)
    fn potential(&self, x: i32, y: i32, z: i32, charge: f64) -> f64 {
        // Vanilla returns before the sum, so a coincident charge yields 0.0, not inf * 0 = NaN.
        if charge == 0.0 {
            return 0.0;
        }
        let mut sum = 0.0f64;
        for c in &self.charges {
            let dx = (c.x - x) as f64;
            let dy = (c.y - y) as f64;
            let dz = (c.z - z) as f64;
            let dist_sq = dx * dx + dy * dy + dz * dz;
            if dist_sq == 0.0 {
                return f64::INFINITY * charge;
            }
            sum += c.charge / dist_sq.sqrt();
        }
        sum * charge
    }
}

// SpawnCapState is shared across region-worker threads during spawn (read + atomic add).
// Only the build phase mutates players/charges/caps, and it runs single-threaded at tick
// head before any worker claims a region (barrier-ordered) — so concurrent access only ever
// touches the AtomicI32 counters + the immutable players/charges. Safe to share by reference.
unsafe impl Sync for SpawnCapState {}
unsafe impl Send for SpawnCapState {}

pub mod ffi;

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{atomic::AtomicUsize, Arc, Barrier};
    use std::thread;

    fn state(players_and_counts: &[(f64, f64, i32)], cap: i32) -> SpawnCapState {
        let mut players = Vec::with_capacity(players_and_counts.len());
        let mut local = Vec::with_capacity(players_and_counts.len() * N_CATEGORIES);
        for &(x, z, count) in players_and_counts {
            players.push(PlayerRec {
                x,
                z,
                spectator: false,
            });
            local.push(AtomicI32::new(count));
            for _ in 1..N_CATEGORIES {
                local.push(AtomicI32::new(0));
            }
        }
        SpawnCapState {
            global: std::array::from_fn(|_| AtomicI32::new(0)),
            local,
            players,
            charges: Vec::new(),
            caps: [cap; N_CATEGORIES],
            spawnable_chunks: 0,
        }
    }

    fn local_count(state: &SpawnCapState, player: usize) -> i32 {
        state.local[state.local_idx(player, 0)].load(Ordering::Relaxed)
    }

    #[test]
    fn reservation_approval_touches_every_near_player() {
        let state = state(&[(8.0, 8.0, 5), (120.0, 8.0, 4), (200.0, 8.0, 1)], 5);

        assert!(state.try_reserve_pack(0, 0, 0, 4));
        assert_eq!(local_count(&state, 0), 9);
        assert_eq!(local_count(&state, 1), 8);
        assert_eq!(local_count(&state, 2), 1);

        state.release_pack(0, 0, 0, 4);
        assert_eq!(local_count(&state, 0), 5);
        assert_eq!(local_count(&state, 1), 4);
        assert_eq!(local_count(&state, 2), 1);
    }

    #[test]
    fn rejected_reservation_rolls_back_without_residue() {
        let state = state(&[(8.0, 8.0, 5), (120.0, 8.0, 7)], 5);

        assert!(!state.try_reserve_pack(0, 0, 0, 4));
        assert_eq!(local_count(&state, 0), 5);
        assert_eq!(local_count(&state, 1), 7);
    }

    #[test]
    fn reservation_without_near_players_rejects_without_mutation() {
        let state = state(&[(1000.0, 1000.0, 3)], 5);

        assert!(!state.try_reserve_pack(0, 0, 0, 4));
        assert_eq!(local_count(&state, 0), 3);
    }

    #[test]
    fn reservation_release_keeps_only_real_spawns() {
        let state = state(&[(8.0, 8.0, 4)], 5);

        assert!(state.try_reserve_pack(0, 0, 0, 4));
        state.add(0, 0, 0, true);
        state.add(0, 0, 0, true);
        state.release_pack(0, 0, 0, 4);

        assert_eq!(local_count(&state, 0), 6);
    }

    #[test]
    fn concurrent_reservations_keep_the_one_pack_overshoot_bound() {
        const WORKERS: usize = 64;
        let state = Arc::new(state(&[(8.0, 8.0, 0)], 70));
        let start = Arc::new(Barrier::new(WORKERS + 1));
        let reserved = Arc::new(Barrier::new(WORKERS + 1));
        let release = Arc::new(Barrier::new(WORKERS + 1));
        let approved = Arc::new(AtomicUsize::new(0));
        let mut handles = Vec::with_capacity(WORKERS);

        for _ in 0..WORKERS {
            let state = Arc::clone(&state);
            let start = Arc::clone(&start);
            let reserved = Arc::clone(&reserved);
            let release = Arc::clone(&release);
            let approved = Arc::clone(&approved);
            handles.push(thread::spawn(move || {
                start.wait();
                let won = state.try_reserve_pack(0, 0, 0, 4);
                if won {
                    approved.fetch_add(1, Ordering::Relaxed);
                }
                reserved.wait();
                release.wait();
                if won {
                    state.release_pack(0, 0, 0, 4);
                }
            }));
        }

        start.wait();
        reserved.wait();
        assert_eq!(approved.load(Ordering::Relaxed), 18);
        assert_eq!(local_count(&state, 0), 72);
        release.wait();
        for handle in handles {
            handle.join().unwrap();
        }
        assert_eq!(local_count(&state, 0), 0);
    }

    #[test]
    fn potential_matches_vanilla_at_zero_charge_and_coincident_points() {
        let mut st = state(&[], 5);
        st.charges.push(ChargeRec {
            x: 0,
            y: 64,
            z: 0,
            charge: 0.15,
        });
        // vanilla: `if (charge == 0.0) return 0.0;` before any distance is taken
        assert_eq!(st.potential(0, 64, 0, 0.0), 0.0);
        // coincident point: POSITIVE_INFINITY * charge
        assert_eq!(st.potential(0, 64, 0, 0.7), f64::INFINITY);
        // 0.15 / sqrt(9 + 16) * 0.7
        assert_eq!(st.potential(3, 68, 0, 0.7), 0.15 / 5.0 * 0.7);
    }

    #[test]
    fn build_skips_zero_charges_like_add_charge() {
        // entity record: charge f64@0, cat i32@8, bx/by/bz i32@12/16/20, flags i32@24
        let mut rec = [0u8; 32];
        rec[0..8].copy_from_slice(&0.0f64.to_ne_bytes());
        rec[16..20].copy_from_slice(&64i32.to_ne_bytes());
        rec[24..28].copy_from_slice(&2i32.to_ne_bytes()); // hasCharge, not a mob
        let caps = [1i32; N_CATEGORIES];
        unsafe {
            let handle =
                ffi::oxi_spawncap_build(rec.as_ptr(), 1, std::ptr::null(), 0, 289, caps.as_ptr());
            assert_ne!(handle, 0);
            // vanilla addCharge drops charge 0.0, so the coincident probe stays finite
            assert_eq!(ffi::oxi_spawncap_potential(handle, 0, 64, 0, 0.7), 0.0);
            assert_eq!(ffi::oxi_spawncap_free(handle), 0);
        }
    }

    #[test]
    fn reservation_ffi_rejects_invalid_arguments_without_mutation() {
        let state = state(&[(8.0, 8.0, 3)], 5);
        let handle = &state as *const SpawnCapState as i64;

        unsafe {
            assert_eq!(ffi::oxi_spawncap_try_reserve_pack(0, 0, 0, 0, 4), -1);
            assert_eq!(ffi::oxi_spawncap_try_reserve_pack(handle, -1, 0, 0, 4), -1);
            assert_eq!(
                ffi::oxi_spawncap_try_reserve_pack(handle, N_CATEGORIES as i32, 0, 0, 4),
                -1
            );
            assert_eq!(ffi::oxi_spawncap_try_reserve_pack(handle, 0, 0, 0, 0), -1);
            assert_eq!(ffi::oxi_spawncap_try_reserve_pack(handle, 0, 0, 0, -4), -1);
            assert_eq!(ffi::oxi_spawncap_release_pack(0, 0, 0, 0, 4), -1);
            assert_eq!(ffi::oxi_spawncap_release_pack(handle, 0, 0, 0, 0), -1);
        }
        assert_eq!(local_count(&state, 0), 3);
    }
}
