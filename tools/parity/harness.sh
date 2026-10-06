#!/usr/bin/env bash
# Rustmi vanilla-parity / perf / RAM harness. Builds the jar (`./gradlew build`, which also
# runs the Java and Rust tests), runs the deterministic scenario on a vanilla and a
# Lithium+Fabric API+Fabrolia reference, then the same with Rustmi once per native backend,
# and prints METRIC/ASI lines. Verdict: `METRIC vanilla_failures=0`.
#
# Needs the stand (tools/parity/setup.sh), JAVA_HOME = JDK 25 and the build toolchain
# (tools/setup-toolchain.sh). Env: RUSTMI_BACKENDS (default "nalim ffm"),
# RUSTMI_EXTRA_JVM (extra flags for the Rustmi servers), RUSTMI_STAND (stand dir).
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
STAND=${RUSTMI_STAND:-$REPO/stand}
export RUSTMI_STAND=$STAND
BACKENDS=${RUSTMI_BACKENDS:-nalim ffm}
USER_JVM=${RUSTMI_EXTRA_JVM:-}
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
if ! ./gradlew build --console=plain > "$STAND/logs/build.log" 2>&1; then
    echo "METRIC vanilla_failures=1000"
    echo "ASI stage=build"
    grep -E 'error:|FAILED|What went wrong|panicked' -A2 "$STAND/logs/build.log" | head -20 | sed 's/^/ASI build: /'
    exit 1
fi

cd "$HERE" || exit 1
cp "$HERE/config/"* "$STAND/server/config/"
# A reference is valid for the exact server, stack jars, config and scenario it was recorded with.
ref_key() {
    {
        sha256sum "$STAND/server/fabric-server-launch.jar" "$HERE/drive.py" "$HERE"/config/* | cut -d' ' -f1
        if [ "$1" = L ]; then sha256sum "$STAND"/jars/*.jar | cut -d' ' -f1; fi
    } | sha256sum | cut -d' ' -f1
}
for ref in V L; do
    dir="$STAND/runs/ref-$ref"
    key=$(ref_key "$ref")
    if [ ! -f "$dir/result.json" ] || grep -q '"error"' "$dir/result.json" \
            || [ "$(cat "$dir/stack.key" 2>/dev/null)" != "$key" ]; then
        python3 drive.py "$ref" "$dir" > "$STAND/logs/ref-$ref.log" 2>&1
        echo "$key" > "$dir/stack.key"
    fi
done
for backend in $BACKENDS; do
    for cfg in R LR; do
        RUSTMI_EXTRA_JVM="-Drustmi.backend=$backend $USER_JVM" \
            python3 drive.py "$cfg" "$STAND/runs/$cfg-$backend" > "$STAND/logs/$cfg-$backend.log" 2>&1
    done
done
# shellcheck disable=SC2086
python3 summarize.py $BACKENDS
