#!/usr/bin/env python3
"""Investigation only (not part of the harness): A/B engine toggles on the perf workload.

usage: ab.py <config R|LR> <outdir> <feature>[,<feature>...]
feature ∈ collision, cramming, redstone, sections
Prints sprint ms/tick for alternating on/off rounds.
"""
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import drive  # noqa: E402


def sprint(s, n):
    m = s.ask(f"tick sprint {n}", r"Sprint completed with .* ([0-9.]+) ms per tick", 900)
    return float(m.group(1))


def main():
    cfg, outdir, feats = sys.argv[1], sys.argv[2], sys.argv[3].split(",")
    import shutil
    shutil.rmtree(outdir, ignore_errors=True)
    os.makedirs(outdir)
    drive.prepare(cfg)
    extra = [f"-XX:StartFlightRecording=filename={outdir}/rec.jfr,settings=profile"] if os.environ.get("JFR") else []
    s = drive.Server(cfg, outdir, extra)
    try:
        s.wait(r"Done \(", 600)
        x0, z0, x1, z1 = drive.BOX
        s.cmd(f"forceload add {x0 * 16} {z0 * 16} {x1 * 16 + 15} {z1 * 16 + 15}")
        drive.wait_box_loaded(s)
        x, y, z = drive.OX, 200, drive.OZ + 40
        for c in (f"fill {x - 24} {y - 1} {z - 24} {x + 24} {y + 6} {z + 24} minecraft:air",
                  f"fill {x - 24} {y - 1} {z - 24} {x + 24} {y - 1} {z + 24} minecraft:stone",
                  f"fill {x - 25} {y} {z - 25} {x + 25} {y + 1} {z + 25} minecraft:oak_fence hollow"):
            s.cmd(c)
        for (px, pz) in ((962, 1010), (966, 1010), (970, 1010), (962, 1016), (966, 1016), (970, 1016)):
            s.cmd(f"fill {px - 1} {y} {pz - 1} {px + 1} {y + 3} {pz + 1} minecraft:glass hollow")
            for i in range(75):
                s.cmd(f"summon pig {px + 0.5} {y + 1} {pz + 0.5}")
        for i in range(300):
            s.cmd(f"summon zombie {x - 20 + (i * 7) % 40} {y + 1} {z - 20 + (i * 13) % 40} {{PersistenceRequired:1b}}")
        s.cmd("gamerule random_tick_speed 0")
        s.cmd("gamerule max_entity_cramming 0")
        sprint(s, 600)  # warm-up (JIT, mirrors, quarantine)
        rounds = []
        for r in range(int(os.environ.get("ROUNDS", "4"))):
            for state in (("on",) if os.environ.get("ON_ONLY") else ("on", "off")):
                for f in feats:
                    s.cmd(f"rustmi {f} {state}")
                time.sleep(0.5)
                rounds.append((state, sprint(s, int(os.environ.get("TICKS", "2000")))))
        on = [v for k, v in rounds if k == "on"]
        off = [v for k, v in rounds if k == "off"] or [float("nan")]
        print(f"{cfg} {','.join(feats)}: on={on} off={off} mean_on={sum(on) / len(on):.3f} mean_off={sum(off) / len(off):.3f}")
    finally:
        s.stop()


if __name__ == "__main__":
    main()
