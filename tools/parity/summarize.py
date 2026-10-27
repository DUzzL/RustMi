#!/usr/bin/env python3
"""Turns harness artefacts into METRIC/ASI lines.

usage: summarize.py <backend>...   (reads stand/runs/{R,LR}-<backend>, e.g. nalim ffm)

vanilla_failures (primary, lower is better) counts every observed deviation from vanilla
or crash-class problem, over every backend; perf/RAM numbers are secondary metrics,
reported per run as <cfg>-<backend>_<metric>.
"""
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
STAND = os.environ.get("RUSTMI_STAND", os.path.join(os.path.dirname(os.path.dirname(HERE)), "stand"))
BOX = ["60", "60", "67", "67"]


def load(name):
    p = os.path.join(STAND, "runs", name, "result.json")
    if not os.path.exists(p):
        return None
    with open(p) as f:
        return json.load(f)


def regions(a, b, smin, smax, mode="exact"):
    out = subprocess.run([sys.executable, os.path.join(HERE, "regions.py"),
                          os.path.join(STAND, "runs", a), os.path.join(STAND, "runs", b), *BOX,
                          str(smin), str(smax), mode], capture_output=True, text=True)
    if out.returncode != 0:
        return None
    return json.loads(out.stdout)


def main():
    fails = {}
    metrics = {}
    asi = {}
    backends = sys.argv[1:] or ["nalim"]
    runs = [(f"{cfg}-{b}", ref, b) for b in backends for cfg, ref in (("R", "ref-V"), ("LR", "ref-L"))]

    for cfg, ref, backend in runs:
        r = load(cfg)
        if r is None:
            fails[f"{cfg}_missing"] = 100
            continue
        if "error" in r:
            fails[f"{cfg}_run_error"] = 50
            asi[f"{cfg}_error"] = r["error"][:200]
        fails[f"{cfg}_log_errors"] = len(r.get("log_errors", []))
        if r.get("hs_err"):
            fails[f"{cfg}_jvm_crash"] = 50
            asi[f"{cfg}_hs_err"] = r["hs_err"]
        # A crash on the way out (e.g. a native fault at shutdown) only shows in the exit code.
        if r.get("exit_code") != 0:
            fails[f"{cfg}_exit_code"] = 50
            asi[f"{cfg}_exit_code"] = r.get("exit_code")
        # The run must have used the backend it forced (-Drustmi.backend=<backend>).
        if (r.get("backend") or "").lower() != backend:
            fails[f"{cfg}_wrong_backend"] = 50
            asi[f"{cfg}_backend"] = r.get("backend")
        if r.get("log_errors"):
            asi[f"{cfg}_first_log_error"] = r["log_errors"][0][:200]
        rs = regions(f"{ref}/rs", f"{cfg}/rs", 18, 19)
        if rs is None or rs["compared"] == 0:
            fails[f"{cfg}_contraption_unreadable"] = 50
        else:
            fails[f"{cfg}_contraption_sections"] = rs["diff"] + rs["missingA"] + rs["missingB"]
        tg = regions(f"{ref}/gen", f"{cfg}/gen", -4, 17, "terrain")
        if tg is None:
            fails[f"{cfg}_worldgen_unreadable"] = 50
        else:
            fails[f"{cfg}_worldgen_real_cells"] = tg["real_cells"] + tg["biome_cells"]
            metrics[f"{cfg}_worldgen_noise_cells"] = tg["noise_cells"]
        cs = r.get("collision_shadow", {})
        fails[f"{cfg}_collision_bad"] = cs.get("bad", 0)
        es = r.get("explosion_shadow", {})
        et = r.get("explosion_takeover")
        if not et or not et["takeovers"] or not et["fieldChanged"] or not et["deterministic"]:
            fails[f"{cfg}_explosion_takeover_vacuous"] = 50
            asi[f"{cfg}_explosion_takeover"] = et
        elif not et["sameAsVanilla"]:
            fails[f"{cfg}_explosion_takeover_differs"] = 50
        if not r.get("explosion_engine_restored"):
            fails[f"{cfg}_explosion_engine_not_restored"] = 50
        fails[f"{cfg}_explosion_bad"] = es.get("bad", 0) + es.get("seenBad", 0)
        fails[f"{cfg}_explosion_order"] = es.get("orderOnly", 0)
        cr = r.get("cramming_shadow", {})
        fails[f"{cfg}_cramming_set"] = cr.get("set", 0)
        fails[f"{cfg}_cramming_order"] = cr.get("order", 0)
        fails[f"{cfg}_sensor_set"] = cr.get("sensorSet", 0)
        fails[f"{cfg}_sensor_order"] = cr.get("sensorOrder", 0)
        metrics[f"{cfg}_sensor_calls"] = cr.get("sensorCalls", 0)
        if r.get("strips_missing") is None:
            fails[f"{cfg}_strip_check_absent"] = 50
        else:
            fails[f"{cfg}_strips_missing"] = len(r["strips_missing"])
            if r["strips_missing"]:
                asi[f"{cfg}_strips_missing"] = ",".join(r["strips_missing"])
        wc = r.get("wirecheck")
        if wc is None or wc["native"] == 0:
            fails[f"{cfg}_wirecheck_vacuous"] = 50
        else:
            fails[f"{cfg}_wire_bad"] = wc["writeBad"] + wc["sizeBad"] + wc["roundTripBad"] + wc.get("packBad", 0)
            metrics[f"{cfg}_wire_native_containers"] = wc["native"]
        rd = r.get("redstone", {})
        metrics[f"{cfg}_redstone_takeovers"] = rd.get("takeovers", 0)
        metrics[f"{cfg}_redstone_aborts"] = rd.get("abortMismatch", 0) + rd.get("abortMutation", 0) + rd.get("abortLeftover", 0)
        for k in ("perf_mspt", "shadow_mspt", "collision_native_pct", "heap_used_mb",
                  "nonheap_committed_mb", "rss_mb", "native_sections_mib"):
            if k in r:
                metrics[f"{cfg}_{k}"] = r[k]
        metrics[f"{cfg}_collision_calls"] = cs.get("calls", 0)
        metrics[f"{cfg}_cramming_calls"] = cr.get("calls", 0)
        # A zero "bad" count proves nothing when the engine never ran: every checked
        # feature must show activity (sensor queries exist only with Fabrolia, i.e. LR).
        active = {"collision": cs.get("calls", 0), "cramming": cr.get("calls", 0),
                  "explosion": es.get("explosions", 0), "redstone": rd.get("takeovers", 0)}
        if cfg.startswith("LR-"):
            active["sensor"] = cr.get("sensorCalls", 0)
        for feature, count in active.items():
            if not count:
                fails[f"{cfg}_{feature}_vacuous"] = 50

    total = sum(fails.values())
    print(f"METRIC vanilla_failures={total}")
    for k, v in sorted(metrics.items()):
        print(f"METRIC {k}={v}")
    nz = {k: v for k, v in fails.items() if v}
    print("ASI failures=" + json.dumps(nz, separators=(",", ":")))
    for k, v in asi.items():
        print(f"ASI {k}={json.dumps(v)}")
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main())
