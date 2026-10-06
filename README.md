# Rustmi - Inject Ferris into your server! 🦀
---
## Summary

RustMI is a server-side Fabric optimization mod for Minecraft 26.3. It moves
selected hot paths into a Rust `cdylib`. One jar runs on Linux, Windows and
macOS (x86_64 and arm64): it carries the native library for all six platforms
and picks the fastest way to call it that the JVM allows (see Backends).

The native optimization surface covers entity collision, pathfinding,
explosions, cramming and sensor queries, redstone cascade simulation and
Redpiler, off-heap chunk-section storage, and spawn-cap accounting. `Mth`
helpers are vanilla-exact Java: a JVM body is cheaper than any native call for
them, and trigonometry uses vanilla's own table.

Native behavior is checked by Rust unit tests plus recorded collision,
pathfinding, and redstone fixtures, and in game by shadow modes
(`/rustmi collisionshadow`, `/rustmi cramming shadow`, `/rustmi explosion
shadow`) that compare every native result bit-for-bit with vanilla. The
collision shadow also re-checks the cached answers of the per-pair cramming
predicates (`onClimbable`, `getTeam`, entity-ticking status) and the supporting
block. `/rustmi sections wirecheck` compares the chunk-packet bytes of every
native section in force-loaded chunks with vanilla's encoding, its declared
size, a read-back round trip, and vanilla's bit packing. FFI layout probes fail
startup rather than allowing Java and Rust record layouts to drift silently.

## Compatibility

RustMI is server-side and has explicit integration with Lithium and Fabrolia.
Fabrolia remains an optional runtime dependency; its pinned jar in
`nalim/libs` is used only to compile the optional integration surface and is
not packaged into RustMI.

## Backends

RustMI extracts its platform's native library to `libraries/rustmi/`, loads it
by absolute path and links the complete binding surface at startup, so a
missing or stale library fails immediately instead of producing delayed
`UnsatisfiedLinkError`s in tick code. The calls then go through one of two
backends; features and results are identical, only the per-call cost differs.
`/rustmi stats` and the startup log (`RustMI native backend: ...`) show which
one is active.

### Nalim (preferred)

[Nalim](https://github.com/FurryMileon/nalim) (bundled, `1.2.1-patched-autoinline`)
installs native calls as JIT intrinsics through JVMCI — no JNI or FFM
transition. RustMI uses it automatically on **Linux x86_64, Java 25–26, C2 JIT**
when the launch flags allow it:

```bash
java --add-modules=jdk.internal.vm.ci --enable-native-access=ALL-UNNAMED \
     -jar fabric-server-launch.jar nogui
# Java 26+: also -XX:-UseCodeCacheFlushing
```

Other platforms are not enabled automatically because Nalim is unverified there
and its failures (JVM aborts, argument corruption) cannot be caught and turned
into a fallback.

### FFM (everywhere else)

Without those flags, or on another OS/CPU, RustMI uses the JDK Foreign Function
& Memory API (Java 25+). Add `--enable-native-access=ALL-UNNAMED` to silence
the JDK's native-access warning.

### Forcing a backend

`-Drustmi.backend=nalim` requires Nalim (startup fails with the reason instead
of falling back); `-Drustmi.backend=ffm` always uses FFM; the default is `auto`.

Linux builds require glibc 2.17+ (musl distributions such as Alpine are not
supported).

## Testing

The `Rustmi-Tester` mod can be loaded with RustMI to compare its `Mth`
overwrites against Minecraft's Java implementation; `/rustmi mathcheck` runs
the same comparison on a live server. Native fixture and FFI tests run with:

```bash
cd native
cargo test --release --locked
```

## Downloading
[Modrinth](https://modrinth.com/mod/rustmi) is the only distribution site for this mod<p>
GitHub releases can also be used, but Modrinth is recommended to support the developers<p>
The latest releases can be found on [our Discord Server](https://discord.gg/XGw3Te7QYr) or on GitHub Actions

---

## Compiling

Use a JDK 25+ installation, a Rust toolchain, zig 0.14.x and
[cargo-zigbuild](https://github.com/rust-cross/cargo-zigbuild):

```bash
rustup target add aarch64-unknown-linux-gnu x86_64-pc-windows-gnu \
    aarch64-pc-windows-gnullvm x86_64-apple-darwin aarch64-apple-darwin
cargo install cargo-zigbuild --locked
ZIG=/path/to/zig-0.14/zig JAVA_HOME=/path/to/jdk-25-or-newer ./gradlew compileLocally --no-daemon
```

`compileLocally` cross-compiles the Rust library for all six platforms with the
checked-in `Cargo.lock` (`ZIG` may be omitted when `zig` is on `PATH`),
generates the Nalim facade and FFM bindings from `NalimBindings.java`, checks
them against the cbindgen header, and builds `nalim/build/libs/rustmi-<version>.jar`
plus the tester mod. The build fails if any platform's library is missing.

## Running in a development environment

Use `./gradlew :rustmi:runServer` for the base development environment or
`./gradlew :testMod:runClient` for the tester UI.