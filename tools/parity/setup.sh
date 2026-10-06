#!/usr/bin/env bash
# Creates the parity stand in stand/ (outside git): a Fabric 26.3 dedicated server and the
# mod stack the LR config runs with (Lithium, Fabric API, Fabrolia). Idempotent; rerun
# after changing a pin below, then delete stand/runs/ref-* so the references regenerate.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
STAND=${RUSTMI_STAND:-$REPO/stand}

MC=26.3
LOADER=0.19.5
INSTALLER=1.1.2
# name|url|sha512 — the versions the production server runs.
JARS=(
  "lithium-fabric-0.26.2+mc26.3.jar|https://cdn.modrinth.com/data/gvQqBUqZ/versions/xS0Q8LSi/lithium-fabric-0.26.2%2Bmc26.3.jar|4d7fee66132eedc71feab9390b92c95d7058edbdad0fecfac1d836a2950b97a7ca463afbede61c7ef361ce65e1f927ab9e89dde5bf2e0e0ce486afb5c5dbee40"
  "fabric-api-0.161.0+26.3.jar|https://cdn.modrinth.com/data/P7dR8mSH/versions/bNnaTiuM/fabric-api-0.161.0%2B26.3.jar|ed6b2586d6fde11fde8472f5a527c51e99b67026e46f94d4bfd85e7e28ce5ee299173ee16ad576ceb51f39f98d30a811086a6deb1a86a524859cc16e12da109d"
)
# Fabrolia comes from the sibling project's release folder unless FABROLIA_JAR is set.
FABROLIA_JAR=${FABROLIA_JAR:-$(ls "$REPO"/../Fabrolia/release/fabrolia-fabric-*.jar 2>/dev/null | sort -V | tail -1)}

mkdir -p "$STAND/server/config" "$STAND/jars" "$STAND/runs" "$STAND/logs"

if [ ! -f "$STAND/server/fabric-server-launch.jar" ]; then
    # Fabric's server launcher downloads Minecraft and the loader libraries on first start.
    curl -fsSL -o "$STAND/server/fabric-server-launch.jar" \
        "https://meta.fabricmc.net/v2/versions/loader/$MC/$LOADER/$INSTALLER/server/jar"
fi
echo "eula=true" > "$STAND/server/eula.txt"
cp "$HERE/config/"* "$STAND/server/config/"

for entry in "${JARS[@]}"; do
    IFS='|' read -r name url sha <<< "$entry"
    dest="$STAND/jars/$name"
    if [ ! -f "$dest" ]; then
        curl -fsSL -o "$dest.part" "$url"
        mv "$dest.part" "$dest"
    fi
    actual=$(sha512sum "$dest" | cut -d" " -f1)
    if [ "$actual" != "$sha" ]; then
        echo "sha512 mismatch for $name: $actual, expected $sha" >&2
        exit 1
    fi
done

if [ -z "$FABROLIA_JAR" ] || [ ! -f "$FABROLIA_JAR" ]; then
    echo "Fabrolia jar not found; set FABROLIA_JAR=/path/to/fabrolia-fabric-*.jar" >&2
    exit 1
fi
rm -f "$STAND"/jars/fabrolia-fabric-*.jar
cp "$FABROLIA_JAR" "$STAND/jars/"

echo "Stand ready in $STAND (stack: $(ls "$STAND/jars" | tr '\n' ' '))"
