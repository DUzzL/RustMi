#!/usr/bin/env python3
"""Logical (palette-independent) section contents from Anvil region snapshots.

usage: regions.py <dirA> <dirB> <cx0> <cz0> <cx1> <cz1> <secMin> <secMax>
Prints JSON {"compared": n, "diff": n, "missingA": n, "missingB": n, "examples": [...]}
Only chunks with Status minecraft:full inside the chunk box are compared; sections are
decoded to per-cell block-state strings and biome names, so different palette order or
packing (e.g. Lithium chunk.serialization) does not count as a difference.
"""
import gzip
import hashlib
import io
import json
import math
import os
import re
import struct
import sys
import zlib


def read_nbt(buf):
    def r(fmt, n):
        nonlocal pos
        v = struct.unpack_from(fmt, buf, pos)
        pos += n
        return v[0]

    def string():
        nonlocal pos
        ln = r(">H", 2)
        s = buf[pos:pos + ln].decode("utf-8", "replace")
        pos += ln
        return s

    def payload(t):
        nonlocal pos
        if t == 1: return r(">b", 1)
        if t == 2: return r(">h", 2)
        if t == 3: return r(">i", 4)
        if t == 4: return r(">q", 8)
        if t == 5: return r(">f", 4)
        if t == 6: return r(">d", 8)
        if t == 7:
            n = r(">i", 4); v = buf[pos:pos + n]; pos += n; return v
        if t == 8: return string()
        if t == 9:
            et = r(">b", 1); n = r(">i", 4)
            return [payload(et) for _ in range(n)]
        if t == 10:
            d = {}
            while True:
                ct = r(">b", 1)
                if ct == 0:
                    return d
                name = string()
                d[name] = payload(ct)
        if t == 11:
            n = r(">i", 4); v = struct.unpack_from(">%di" % n, buf, pos); pos += 4 * n; return list(v)
        if t == 12:
            n = r(">i", 4); v = struct.unpack_from(">%dq" % n, buf, pos); pos += 8 * n; return list(v)
        raise ValueError("bad tag %d" % t)

    pos = 0
    t = r(">b", 1)
    string()
    return payload(t)


def chunks(path):
    with open(path, "rb") as f:
        data = f.read()
    for i in range(1024):
        off = struct.unpack_from(">I", data, i * 4)[0]
        sec, cnt = off >> 8, off & 0xFF
        if sec == 0:
            continue
        ln = struct.unpack_from(">I", data, sec * 4096)[0]
        comp = data[sec * 4096 + 4]
        body = data[sec * 4096 + 5: sec * 4096 + 4 + ln]
        if comp == 2:
            raw = zlib.decompress(body)
        elif comp == 1:
            raw = gzip.decompress(body)
        elif comp == 3:
            raw = body
        else:
            raise ValueError("unsupported compression %d in %s" % (comp, path))
        yield read_nbt(raw)


def unpack(data, n_values, palette_len, min_bits):
    if palette_len <= 1:
        return [0] * n_values
    bits = max(min_bits, math.ceil(math.log2(palette_len)))
    per = 64 // bits
    words = (n_values + per - 1) // per
    if len(data) != words:
        # fall back: derive bits from the word count
        for b in range(1, 33):
            if (n_values + (64 // b) - 1) // (64 // b) == len(data) and (1 << b) >= palette_len:
                bits, per = b, 64 // b
                break
        else:
            raise ValueError("cannot derive bits: %d words for %d values" % (len(data), n_values))
    mask = (1 << bits) - 1
    out = []
    for w in data:
        w &= 0xFFFFFFFFFFFFFFFF
        for j in range(per):
            if len(out) == n_values:
                break
            out.append((w >> (j * bits)) & mask)
    return out


def state_str(e):
    if isinstance(e, dict) and "" in e:   # heterogeneous-list wrapper {"": value}
        e = e[""]
    if isinstance(e, str):          # compact form: "minecraft:x[k=v,...]"
        if e.endswith("]") and "[" in e:
            name, props = e[:-1].split("[", 1)
            return name + "[" + ",".join(sorted(props.split(","))) + "]"
        return e
    name = e.get("Name", e.get("id"))
    props = e.get("Properties", e.get("properties"))
    if name is None:
        raise ValueError("unknown block state shape: %r" % (e,))
    if not props:
        return name
    return name + "[" + ",".join(f"{k}={props[k]}" for k in sorted(props)) + "]"


def section_cells(sec):
    bs = sec.get("block_states")
    cells = None
    if bs is not None:
        pal = [state_str(e) for e in bs["palette"]]
        cells = [pal[v] for v in unpack(bs.get("data", []), 4096, len(pal), 4)]
    bi = sec.get("biomes")
    biomes = None
    if bi is not None:
        pal = bi["palette"]
        biomes = [pal[v] for v in unpack(bi.get("data", []), 64, len(pal), 1)]
    return cells, biomes


def section_hash(sec):
    cells, biomes = section_cells(sec)
    h = hashlib.sha1()
    if cells is not None:
        h.update("\x00".join(cells).encode())
    h.update(b"|")
    if biomes is not None:
        h.update("\x00".join(biomes).encode())
    return h.hexdigest()


# Vanilla worldgen is not reproducible where decorations from neighbouring chunks
# overlap (the decoration order follows thread scheduling): ore/stone-variant blobs,
# competing trees and surface plants. A cell difference is scheduling noise when both
# values are in these classes; anything else (terrain shape, fluids, stone/air layout,
# biomes) is a real diff.
_STONEISH = {"stone", "granite", "diorite", "andesite", "deepslate", "tuff", "dirt", "gravel",
             "calcite", "smooth_basalt", "clay", "sand", "sandstone", "coarse_dirt", "infested_stone",
             "infested_deepslate", "amethyst_block", "budding_amethyst"}
_VEGETATION = re.compile(
    r"(^air$|^cave_air$|_log$|_wood$|_leaves$|_sapling$|vine|grass|fern|flower|tulip|orchid|"
    r"daisy|bluet|poppy|dandelion|lilac|rose|peony|allium|bush|mushroom|petals|litter|lichen|"
    r"moss|azalea|roots|cocoa|nest|pumpkin|melon|cactus|sugar_cane|berry|shrub|kelp|seagrass|"
    r"lily|^dirt$|^podzol$|^snow$|^bamboo|^propagule|^hanging_roots$|dripleaf|spore)")


_AQUATIC = re.compile(r"(seagrass|kelp|coral|sea_pickle|^water$)")


def _base(s):
    n = s.split("[", 1)[0].split(":", 1)[-1]
    return n


def _noise(a, b):
    a, b = _base(a), _base(b)
    ore_a = a.endswith("_ore") or a in _STONEISH
    ore_b = b.endswith("_ore") or b in _STONEISH
    if ore_a and ore_b:
        return True
    if bool(_VEGETATION.search(a)) and bool(_VEGETATION.search(b)):
        return True
    # aquatic decorations replace water/lava-free water cells (seagrass, kelp, coral, pickles)
    return (a == "water" and _AQUATIC.search(b) is not None) or (b == "water" and _AQUATIC.search(a) is not None) \
        or (_AQUATIC.search(a) is not None and _AQUATIC.search(b) is not None)


def load(d, box, smin, smax, cells=False):
    cx0, cz0, cx1, cz1 = box
    out = {}
    for name in os.listdir(d):
        if not name.endswith(".mca"):
            continue
        for c in chunks(os.path.join(d, name)):
            cx, cz = c.get("xPos"), c.get("zPos")
            if cx is None or not (cx0 <= cx <= cx1 and cz0 <= cz <= cz1):
                continue
            if c.get("Status") != "minecraft:full":
                continue
            for sec in c.get("sections", []):
                y = sec.get("Y")
                if smin <= y <= smax:
                    out[(cx, cz, y)] = section_cells(sec) if cells else section_hash(sec)
    return out


def main():
    a, b = sys.argv[1], sys.argv[2]
    box = tuple(int(v) for v in sys.argv[3:7])
    smin, smax = int(sys.argv[7]), int(sys.argv[8])
    mode = sys.argv[9] if len(sys.argv) > 9 else "exact"
    if mode == "terrain":
        A, B = load(a, box, smin, smax, True), load(b, box, smin, smax, True)
        real, noise, biome, examples = 0, 0, 0, []
        for k in sorted(set(A) & set(B)):
            (ca, ba), (cb, bb) = A[k], B[k]
            if ba != bb:
                biome += sum(1 for i in range(64) if ba[i] != bb[i])
            if ca == cb:
                continue
            for i in range(4096):
                if ca[i] != cb[i]:
                    if _noise(ca[i], cb[i]):
                        noise += 1
                    else:
                        real += 1
                        if len(examples) < 8:
                            examples.append([*k, i, ca[i], cb[i]])
        print(json.dumps({"compared": len(set(A) & set(B)), "real_cells": real, "noise_cells": noise,
                          "biome_cells": biome, "missing": len(set(A) ^ set(B)), "examples": examples}))
        return
    A, B = load(a, box, smin, smax), load(b, box, smin, smax)
    keys = set(A) | set(B)
    diff = sorted(k for k in keys if k in A and k in B and A[k] != B[k])
    res = {"compared": len(set(A) & set(B)), "diff": len(diff),
           "missingA": len(set(B) - set(A)), "missingB": len(set(A) - set(B)),
           "examples": [list(k) for k in diff[:8]]}
    print(json.dumps(res))


if __name__ == "__main__":
    main()
