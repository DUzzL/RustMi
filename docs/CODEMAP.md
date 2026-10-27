# Rustmi code map

Where each feature lives, how it reaches vanilla, and the vanilla rules it must keep.
Paths: `R/` = `src/main/java/com/github/tatercertified/rustmi/`,
`N/` = `src/main/java/com/github/tatercertified/nalim/`, `rs/` = `native/src/util/`.
The `nalim` package name is historical (it was the Nalim-backend module); it is not the
Nalim library (`one.nalim`, `../nalim`).

## Startup and native calls

1. Mixin selects `rustmi.mixins.json` and calls `R/RustmiMixinPlugin.onLoad` — before any
   class is transformed. It runs, in order:
   - `R/LoadRustBinary.onPreLaunch`: picks this platform's library from the jar
     (`librustmi_linux_x86.so`, `rustmi_windows_arm64.dll`, …), extracts it
     to `libraries/rustmi/` when missing or its SHA-256 differs from the bundled `.hash`,
     rejects musl JVMs, sets `-Drustmi.native.path`;
   - `N/NativeBackend.select`: Nalim or FFM (rules in `docs/DEVELOPMENT.md`), then a layout probe
     (cramming record sizes 64/512 from both sides);
   - `N/cramming/CrammingHooks.register`, `N/spawncap/SpawnCapHooks.register`.
   Any `RuntimeException` is rethrown as `Error` so startup stops.
2. Every native call goes through the generated `RustmiLib` (`build/generated/nativeBindings`),
   which forwards to `N/rust/NalimBindings` (Nalim) or the generated `FfmBindings`.
   `NalimBindings.java` is the only declaration list; `native/lib.h` is cbindgen's view of
   the Rust exports and `NativeBindingsContractTest` keeps the two equal.
3. Buffers cross the boundary as raw addresses of direct `ByteBuffer`s or of native memory
   (`RustmiCramming.addressOf`, `theUnsafe()`); no Java object reaches Rust.

## Features

Every feature is on by default and toggled with `/rustmi <feature> on|off`
(`N/redstone/RustmiCommands`); toggles persist under `config/rustmi/` or
`config/rustmi-runtime/`, except `spawncap`, which is back on after a restart.
Each has a vanilla fallback per call: when a precondition is not proven, vanilla runs.

### Collision (block collision of `Entity.move`)
- Entry: `N/mixin/EntityCollideMixin` (`Entity.collide`), `EntitySupportingBlockMixin`
  (`findSupportingBlock`), `EntityColliderQueryMixin` (only without Lithium:
  `@IfModAbsent("lithium")`).
- Java: `N/collision/CollisionEngine` (`tryCollide`, classifier, shadow),
  `CubeMirror` (per-section class bitsets, built by `N/mixin/LevelChunkMirrorMixin`,
  invalidated there and by `BulkSectionAccessMirrorMixin`), `SupportingBlock`,
  `HardColliders` + `HardColliderBridge` (index of entities that can be collided with,
  maintained in `EntitySectionSeqMixin` / `LevelHardColliderMixin`).
- Rust: `rs/collision/` (`oxi_collide` full cubes, `oxi_collide2` cubes + state boxes).
- Rules: axis order Y→X→Z and the 1e-7 epsilons of `Shapes.collide`; FULL means the
  `Shapes.block()` instance; states whose shape depends on `CollisionContext` (powder snow,
  scaffolding, …) are PARTIAL → vanilla; the mirror must see every write to a section
  (setBlockState and `BulkSectionAccess`); unloaded chunk cells are skipped like vanilla;
  world border, entity colliders, sub-epsilon motion, experimental minecart context → vanilla.
- Check: `/rustmi collisionshadow on` (runs vanilla too, counts `bad`); fixtures in
  `fixtures/collision/` (cargo tests); `CollisionCompatibilityTest`.
- Toggles: `config/rustmi-runtime/collision-engine.flag` (shadow modes are not persisted).

### Cramming and sensors (`LivingEntity.pushEntities`, nearest-living sensor)
- Entry: `N/mixin/MinecraftServerTickMixin` (tick head → `CrammingHooks.onTickStart`),
  `LivingEntityCrammingMixin` (consumes candidates), `EntityCrammingTrackMixin`,
  `EntitySectionSeqMixin` (section stamps), memo mixins below.
- Java: `CrammingHooks` (collect + batch per tick, Fabrolia `ParallelProcessor` by
  reflection), `RustmiCramming` (direct buffers, 64-byte input / 512-byte output records),
  `RegionalCramming` (per-region batches under Fabrolia's region scheduler),
  `CrammingEscapes` (exactness: entities that left their snapshot box, new entities,
  vanilla section order), `CrammingShadow`.
- Rust: `rs/cramming/` (`oxi_cramming_batch`, sorted cell runs + pair loop; `sensor.rs`
  for `oxi_sensor_*` / `oxi_cramming_query`).
- Rules: Rust only finds *who* may collide; vanilla decides *what* happens, in vanilla's
  candidate order (entity sections by key, then insertion); over-capacity ticks run vanilla.
- Memos that keep the per-pair predicates cheap, each exact by an epoch:
  `N/entity/ClimbCache` (onClimbable never-climbable per BlockState, tag generation),
  `TeamMemo` (getTeam, scoreboard epoch), `TickingMemo` (isPositionEntityTicking,
  visibility/ticket epoch; off under Fabrolia).
- Check: `/rustmi cramming shadow on` + `/rustmi cramming status` (set/order mismatches);
  `ActiveRegionBatchesTest`, `RegionalCrammingGeometryTest`; cargo tests incl. a
  brute-force equivalence test.
- Toggle: `config/rustmi/cramming.properties`. Yields to Sepals if
  `enableSepalsEntitiesCramming=true` in `config/sepals.json`.

### Explosions (`ServerExplosion`)
- Entry: `N/mixin/ServerExplosionMixin`; `LithiumExplosionFloatCaptureMixin` (Lithium's
  raycast draws the floats inside its own handler).
- Java: `N/explosion/ExplosionEngine`. Rust: `rs/explosion/` (`oxi_explosion_sweep_packed`).
- Rules: Java draws the 1352 `nextFloat`s in vanilla's order and passes them in, so the
  world RNG stays in step for the shuffle and fire rolls; Rust returns positions in
  discovery order and Java rebuilds the list the server without Rustmi would build: a real
  `HashSet<BlockPos>`, or Lithium's `LongOpenHashSet(0)` when Lithium's block_raycast handles
  the explosion; explosions in one tick see each other's craters, so no batching
  across explosions; takeover only for the vanilla damage calculator or an entity-based one whose entity class
  does not override the block-resistance/explode hooks; after
  the floats are drawn a kernel failure runs the exact Java port with the same floats.
- Check: `/rustmi explosion shadow on` (turns the engine off until `shadow off`, which
  restores it; any `orderOnly` is a failure); `ExplosionSeenClipTest`.
- E2 seen-percent (`/rustmi explosion seenfast`) is off: its shadow showed mismatches.
- Toggles: `config/rustmi/explosion-{engine,seen-fast}.properties` (shadow is not persisted).

### Redstone wire (R3 cascade takeover)
- Entry: `N/mixin/DefaultRedstoneEvaluatorMixin`, `CollectingNeighborUpdaterMixin`,
  `RedStoneWireShortcutMixin`, wire power tracking in `LevelChunkMirrorMixin`.
- Java: `N/redstone/CascadeEngine` (takeover, abort counters), `NetworkCache` (static
  topology per network, live powers), `CascadeRecorder` (network walk, signal reads; also
  the fixture recorder), `ReplayBuffers`, `CascadeShadow` (R2 compare).
- Rust: `rs/redstone/` — `sim.rs` (wire evaluator replay), `jorder.rs` (BlockPos.hashCode +
  `HashSet` order of the 7-position update set), `collector.rs`
  (`CollectingNeighborUpdater` queue discipline), `ffi.rs`.
- Rules: the script is emitted through the same `setBlock(flags=2)` / `updateNeighborsAt`
  calls in the same order, including intermediate powers; two entry disciplines (InDrain,
  Depth0) read from the live collector depth; any foreign write to the network during
  emission aborts the rest of the script and vanilla continues from the current state.
- Check: harness contraption compare and `/rustmi redstone status` (`abort{…}` counters);
  `fixtures/redstone/` (cargo tests). The fixture recorder and R2 shadow have no command:
  `enabled=true` in `config/rustmi/redstone-recorder.properties` / `redstone-shadow.properties`.
- Toggle: `config/rustmi/redstone-engine.properties`.

### Native sections (block-state storage off-heap)
- Entry: `N/mixin/PalettedContainerMixin` (`createOrReuseData` → `NativeSections.swapToNative`).
- Java: `N/sections/NativeSections`, `NativeBitStorage` (vanilla `SimpleBitStorage` bit math,
  words via Unsafe, freed by a `Cleaner`), `WireCheck`. Rust: `rs/sections.rs` (pooled
  64-byte-aligned blocks).
- Rules: Lithium's `chunk.serialization` must stay off (`fabric.mod.json` `lithium:options`);
  a toggle affects new storages only.
- Check: `/rustmi sections wirecheck` (chunk-packet bytes, size, round trip and packing vs
  vanilla for every native section in force-loaded chunks).
- Toggle: `config/rustmi/native-sections.properties`.

### Spawn caps and sensor sort (with Fabrolia only)
- `N/spawncap/SpawnCapHooks` registers `RustLocalCapProvider` with Fabrolia's
  `LocalCapBridge` when present; `SpawnCap` is the arena API. Rust: `rs/spawncap/`.
  Rules: `canSpawnForCategoryGlobal` integer division, "no near player ⇒ cannot spawn",
  `PotentialCalculator` in vanilla summation order. Known approximation: vanilla's
  8-chunk player tracker lags a player who just moved more than 8 chunks; Rust uses the
  live 128-block test only (`rs/spawncap/mod.rs`).
- `N/sort/DistanceSort`: stable distance sort for Fabrolia sensors, reached by name from
  `DistanceSortBridge` in Fabrolia. Rust: `rs/sort/`.

## Compatibility pieces

- `R/compat/DebugifyMixinCanceller` (mixinsquared entrypoint): drops Debugify's
  `Mth` sin/cos patch, which changes vanilla results.
- `fabric.mod.json` `lithium:options`: `math.sine_lut=false` (Lithium's table differs from
  vanilla for |x| > ~2e5 rad), `chunk.serialization=false` (native sections).

## Tests

| What | Where | Runs in |
|---|---|---|
| Java test mains | `src/test/java/...` (`*Test` with `main`) | `./gradlew check` |
| Rust unit + fixture tests | `native/src/**` `#[cfg(test)]`, `fixtures/` | `./gradlew check` (`cargoTest`) |
| Bindings vs `native/lib.h` | `NativeBindingsContractTest` | `./gradlew check` |
| Parity, perf, RAM on a real server | `tools/parity/` | `tools/parity/harness.sh` |
