# Rustmi SpawnCap — Rust mob-cap state (counts + local cap + potential)

## Why Rust *here* specifically

Not "spawn is slow" — spawn placement / spawn-rules / biome lists stay in vanilla Java
(too entangled to port). The target is the mob-cap **state**: the "read + execute" data
structure — per-category global counts, per-player local counts, per-chunk near-player
lists, and the `PotentialCalculator` charge field. Reasons it belongs in Rust:

1. **Kills a recurring bug CLASS, not one bug.** The 2026-06-30 "fewer mobs / cap looks
   full" report was a non-atomic `computeIfAbsent` race in `playersNearChunk` (Java
   `Long2ObjectConcurrentHashMap` overrode only the `LongFunction` overload; vanilla
   `LocalMobCapCalculator.getPlayersNear` calls the `Long2ObjectFunction` one → fastutil
   default → non-atomic `get→compute→put` under parallel spawn). Patched in Java. But the
   async spawn history is a string of such map/concurrency fixes. In Rust a map that "isn't
   safely concurrent" is a **compile error** (Send/Sync), so the whole class disappears.
2. **Zero-alloc / no JIT dependence.** Vanilla allocates `SpawnState` + an
   `Object2IntOpenHashMap` + a `LocalMobCapCalculator` (two maps) + charge/mobCap lists
   *every tick* → GC churn + warmup/deopt jitter. A reused off-heap arena allocates nothing
   per tick. (Raw throughput vs hot C2 is ~par — the win is safety + churn + predictability,
   per the nalim FFI-floor note: per-call FFI loses to inline, batches/structure win.)

## Composability contract (the hard requirement)

Two mods, independent but multiplicative:

- **Fabrolia alone** = correct. Its Java mob-cap path (with the `computeIfAbsent` overload
  fix) stays the standalone implementation. No Rustmi → Fabrolia "goes its own way."
- **Rustmi present** = accelerator. `SpawnCapBridge` (Fabrolia) soft-resolves
  `com.github.tatercertified.nalim.spawncap.SpawnCap` by name (mirror of `DistanceSortBridge`).
  Absent → `available()==false` → Java fallback. Present → off-heap arena.
- Each optimization works **separately**; together → bigger win. Same pattern as
  DistanceSort / sensor bridge / RegionalCramming.
- **Spawn stays PARALLEL on the Rust path too** — the arena is built by a parallel count
  reduction and read/CAS'd lock-free by region workers during spawn (see Lifecycle).

## Off-heap arena (NativeSections pattern — no per-call FFI on the hot path)

| Field | Type | Mutation | Notes |
|---|---|---|---|
| `global[8]` | atomic i32 | workers, during spawn (afterSpawn) | per-MobCategory non-persistent count |
| `local[P][8]` | atomic i32 (flat P*8) | workers, during spawn (addMob) | per-player per-category |
| `players_near[chunk] → playerIdx[]` | immutable | built once at tick-head | "players within 8 chunks, spawn-eligible" — self-contained, NO VMP dependency |
| `charges` (PotentialCalculator) | immutable arena | built tick-head | (x,y,z,charge) list + `getPotentialEnergyChange` kernel — Phase 2 |
| `spawnable_chunks`, `caps[8]` | const | input | |

Java reads counts / does the spawn-time CAS increments via `Unsafe`/`VarHandle` on the native
addresses (like NativeSections — "Java reads Unsafe addresses without FFI"). No per-`canSpawn`
FFI call. Build = one batched FFI (entity + player arrays via direct `ByteBuffer`).

## Lifecycle (per level tick)

1. **tick-head build** (replaces `ParallelSpawnHelper.buildState`/`finishState`): marshal the
   visible-entity snapshot (cat ordinal, packed chunk, persistence bit) + player chunk
   positions into direct buffers; Rust does a **parallel reduction** → `global` + `local` +
   `players_near` (+ `charges` P2) into the reused arena. Single writer per cell → no races.
2. **spawn phase** (parallel region workers): `canSpawnGlobal(cat)` = `global[cat] <
   caps[cat]*spawnable/289`; `canSpawnLocal(cat,chunk)` = ∃ player in `players_near[chunk]`
   with `local[p][cat] < caps[cat]`. On spawn: atomic-increment `global[cat]` + each near
   player's `local[p][cat]`. All lock-free reads/CAS on the arena.
3. **tick-end**: logical reset (zero the counts, keep capacity) — zero alloc.

## Bit-exact contract (recorder-certified, like cramming/collision/path)

Record vanilla on the live server, replay against Rust, assert `bad=0` over millions of cases:
- `NaturalSpawner.createState` → global counts + the mobCap/charge entries.
- `SpawnState.canSpawnForCategoryGlobal` → `cnt < maxPerCat*spawnable/289` (exact int division).
- `LocalMobCapCalculator.canSpawn` → "any near player has room"; **empty near-list ⇒ false**
  (the current bug's footgun — must reproduce vanilla, not suppress differently).
- `getPlayersNear` ⇒ players within 8 chunks, spawn-eligible (match `getPlayersCloseForSpawning`).
- `PotentialCalculator.getPotentialEnergyChange` → `Σ charge_i · f(dist)` (P2; RNG-free, but
  float-order-sensitive — keep vanilla summation order, cf. explosion RNG trap).

## Phases

- **P1 — counts**: global + local + players_near, off-heap arena, Unsafe read/CAS, parallel
  build. Certify vs createState + both canSpawn paths. Wire `SpawnCapBridge` into Fabrolia
  with Java fallback. Gate `/rustmi spawncap on|off`.
- **P2 — charges/potential**: port `PotentialCalculator` (addCharge + getPotentialEnergyChange)
  into the same arena. Certify float-exact.
- **P3 — integration hardening**: A/B on the test server (50 bots), confirm counts match the
  Java path (the SPAWNDIAG-style assert), confirm parallel + zero-alloc, perf delta.

## Risks / open

- Local-cap tie/empty semantics must be vanilla-exact (the very thing that broke).
- `players_near` eager-at-tick-head (vs vanilla lazy `computeIfAbsent`): compute for all
  spawnable chunks → immutable read during spawn. Small overhead, removes the race + VMP dep.
- Arena lifecycle: per-tick reset must not race the previous tick's stragglers (barrier-gated,
  like the spawn future).
- Keep the Java path (computeIfAbsent fix) as the certified fallback — do NOT delete it.
