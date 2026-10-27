# Rustmi (26.3 server fork)

Server-side Fabric optimizations for Minecraft 26.3 with the hot paths in Rust and vanilla
results kept bit for bit: block collision, entity cramming and sensors, explosions,
redstone wire cascades, off-heap block storage, and (with Fabrolia) spawn caps. One jar ships
the native library for Linux, Windows and macOS on x86_64 and arm64; only Linux x86_64 has
been run live so far.

- **Working on the code: read [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) first** (contract,
  build, harness, backends, release).
- Code map and each feature's vanilla rules: [`docs/CODEMAP.md`](docs/CODEMAP.md).
- Parity/perf harness: [`tools/parity/`](tools/parity) (`setup.sh`, then `harness.sh`).

## Requirements

- Minecraft 26.3, Fabric Loader (built against 0.19.5), Java 25. Fabric API is not required.
- Linux builds need glibc 2.17+ (not musl/Alpine).
- Runs in production with Lithium 0.26.2, Fabrolia 0.2.38, C2ME 0.4.2; the parity harness
  covers Lithium + Fabric API + Fabrolia. It refuses to start next to the old
  `rustmi-nalim` / `rustmi-panama` / `rustmi-membrane` jars.

## Install

Stop the server, put `rustmi-<version>.jar` in `mods/` (released jars: `release/`, not in
git), start. For the faster Nalim backend on Linux x86_64 add the launch flags

```bash
java --add-modules=jdk.internal.vm.ci --enable-native-access=ALL-UNNAMED \
     -XX:-UseCodeCacheFlushing -jar fabric-server-launch.jar nogui   # last flag: Java 26+
```

Without them Rustmi uses the FFM API (same features, a few nanoseconds more per native
call). `-Drustmi.backend=nalim|ffm` forces one. The startup log line
`RustMI native backend: …` and `/rustmi stats` show which is active.

## Commands

All under `/rustmi` (operator level): `stats`; `cramming|collision|explosion|redstone|
sections|spawncap on|off`; `… status`; shadow modes that run vanilla alongside and count
mismatches (`collisionshadow`, `cramming shadow`, `explosion shadow`); `sections wirecheck`.
Full list: `src/main/java/com/github/tatercertified/nalim/redstone/RustmiCommands.java`.

## Build

```bash
tools/setup-toolchain.sh    # rustup targets, cargo-zigbuild, zig 0.14.1
./gradlew build             # JAVA_HOME = JDK 25; jar: build/libs/rustmi-<version>.jar
```

## License

[`LICENSE.txt`](LICENSE.txt) (MIT, original Rustmi by QPCrummer) and
[`LICENSE-GPL-3.0.txt`](LICENSE-GPL-3.0.txt) (shipped with the former `nalim` module,
`com.github.tatercertified.nalim.*`). The bundled Nalim library is GPL-2.0-or-later.
