#!/usr/bin/env bash
# Installs what `./gradlew build` needs besides a JDK 25 and rustup (Linux host): the Rust
# toolchain pinned in rust-toolchain.toml (with its six targets), cargo-zigbuild 0.23.4, and
# zig 0.14.1 in ~/.local/opt/zig-0.14.1
# (where gradle/natives.gradle looks when ZIG is unset). Tested with zig 0.14.1; 0.16
# cannot link the macOS libraries.
set -euo pipefail

cd "$(dirname "$0")/.."
rustup toolchain install   # reads rust-toolchain.toml: channel, components, targets
cargo install cargo-zigbuild --locked --version 0.23.4

ZIG_DIR=$HOME/.local/opt/zig-0.14.1
if [ ! -x "$ZIG_DIR/zig" ]; then
    # SHA-256 from https://ziglang.org/download/index.json
    case "$(uname -m)" in
        x86_64) arch=x86_64; sha=24aeeec8af16c381934a6cd7d95c807a8cb2cf7df9fa40d359aa884195c4716c ;;
        aarch64|arm64) arch=aarch64; sha=f7a654acc967864f7a050ddacfaa778c7504a0eca8d2b678839c21eea47c992b ;;
        *) echo "unsupported host $(uname -m)" >&2; exit 1 ;;
    esac
    # Temp dir next to the target: the final mv is then a rename on one filesystem.
    mkdir -p "$(dirname "$ZIG_DIR")"
    tmp=$(mktemp -d "$(dirname "$ZIG_DIR")/.zig-install.XXXXXX")
    trap 'rm -rf "$tmp"' EXIT
    curl -fsSL -o "$tmp/zig.tar.xz" "https://ziglang.org/download/0.14.1/zig-$arch-linux-0.14.1.tar.xz"
    echo "$sha  $tmp/zig.tar.xz" | sha256sum -c --quiet -
    # Extract next to the target and move it in place, so a failed run never leaves a
    # half-extracted directory that the -x check above would accept.
    mkdir -p "$tmp/zig"
    tar -xJf "$tmp/zig.tar.xz" -C "$tmp/zig" --strip-components=1
    rm -rf "$ZIG_DIR"
    mv "$tmp/zig" "$ZIG_DIR"
fi
"$ZIG_DIR/zig" version
