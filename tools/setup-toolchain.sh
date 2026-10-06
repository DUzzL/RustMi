#!/usr/bin/env bash
# Installs what `./gradlew compileLocally` needs besides a JDK 25 and rustup:
# the five extra Rust targets, cargo-zigbuild, and zig 0.14.1 in ~/.local/opt/zig-0.14.1
# (where build.gradle looks when ZIG is unset). zig 0.15+ cannot build the macOS libraries.
set -euo pipefail

rustup target add aarch64-unknown-linux-gnu x86_64-pc-windows-gnu aarch64-pc-windows-gnullvm \
    x86_64-apple-darwin aarch64-apple-darwin
cargo install cargo-zigbuild --locked --version 0.23.4

ZIG_DIR=$HOME/.local/opt/zig-0.14.1
if [ ! -x "$ZIG_DIR/zig" ]; then
    case "$(uname -m)" in
        x86_64) arch=x86_64 ;;
        aarch64|arm64) arch=aarch64 ;;
        *) echo "unsupported host $(uname -m)" >&2; exit 1 ;;
    esac
    mkdir -p "$ZIG_DIR"
    curl -fsSL "https://ziglang.org/download/0.14.1/zig-$arch-linux-0.14.1.tar.xz" \
        | tar -xJ -C "$ZIG_DIR" --strip-components=1
fi
"$ZIG_DIR/zig" version
