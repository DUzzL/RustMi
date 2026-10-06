#!/usr/bin/env bash
# Rustmi vanilla-parity / perf / RAM harness. Prints METRIC/ASI lines; the verdict is
# `METRIC vanilla_failures=0`. Needs the stand (tools/parity/setup.sh) and the build
# toolchain (tools/setup-toolchain.sh). Extra JVM flags for the R/LR servers go in
# RUSTMI_EXTRA_JVM, e.g. RUSTMI_EXTRA_JVM=-Drustmi.backend=ffm.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
STAND=${RUSTMI_STAND:-$REPO/stand}
export RUSTMI_STAND=$STAND
if [ -z "${JAVA_HOME:-}" ]; then
    echo "Set JAVA_HOME to a JDK 25" >&2
    exit 1
fi
if [ ! -f "$STAND/server/fabric-server-launch.jar" ]; then
    echo "No stand at $STAND; run tools/parity/setup.sh first" >&2
    exit 1
fi
mkdir -p "$STAND/logs" "$STAND/runs"

cd "$REPO" || exit 1
if ! ./gradlew compileLocally --console=plain > "$STAND/logs/build.log" 2>&1; then
    echo "METRIC vanilla_failures=1000"
    echo "ASI stage=build"
    grep -E 'error:|FAILED|What went wrong' -A2 "$STAND/logs/build.log" | head -20 | sed 's/^/ASI build: /'
    exit 1
fi

cd "$REPO/native" || exit 1
cargo test --release --locked > "$STAND/logs/cargo.log" 2>&1
cargo_failed=$(grep -oE '[0-9]+ failed' "$STAND/logs/cargo.log" | awk '{s+=$1} END {print s+0}')
if ! grep -q '^test result: ok' "$STAND/logs/cargo.log"; then
    cargo_failed=$((cargo_failed + 1))
fi

cd "$HERE" || exit 1
# Vanilla (V) and Lithium+Fabric API+Fabrolia (L) references depend only on the stand:
# generated once. Delete stand/runs/ref-* after changing the jars in stand/jars.
for ref in V L; do
    if [ ! -f "$STAND/runs/ref-$ref/result.json" ] || grep -q '"error"' "$STAND/runs/ref-$ref/result.json"; then
        python3 drive.py "$ref" "$STAND/runs/ref-$ref" > "$STAND/logs/ref-$ref.log" 2>&1
    fi
done
python3 drive.py R "$STAND/runs/R" > "$STAND/logs/R.log" 2>&1
python3 drive.py LR "$STAND/runs/LR" > "$STAND/logs/LR.log" 2>&1
python3 summarize.py "$cargo_failed"
