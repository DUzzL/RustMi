// Fixture replay harness — the bit-exact arbiter for the pathfinding kernel.
// Loads fixtures/path/*.json (recorder v3, PathRecorder.java), rebuilds the
// recorded world + mob, runs the Rust find_path, and compares the node
// sequence / reached flag / chosen target against vanilla's recorded Path.
//
// Inconclusive fixtures (out-of-grid reads — vanilla consulted live world
// beyond the recorded bbox) are reported but don't fail the run; mismatches
// on conclusive fixtures do.

use std::collections::HashMap;
use std::fs;
use std::path::PathBuf;

use serde_json::Value;

use super::walk::{MobSpec, WalkEvaluator};
use super::world::{World, TYPE_COUNT, TYPE_NAMES};
use super::{find_path, Node};

fn fixtures_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../fixtures/path")
}

fn b64_decode(s: &str) -> Vec<u8> {
    fn val(c: u8) -> i32 {
        match c {
            b'A'..=b'Z' => (c - b'A') as i32,
            b'a'..=b'z' => (c - b'a') as i32 + 26,
            b'0'..=b'9' => (c - b'0') as i32 + 52,
            b'+' => 62,
            b'/' => 63,
            b'=' => -1,
            _ => panic!("bad base64 byte {c}"),
        }
    }
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len() / 4 * 3);
    let mut acc: u32 = 0;
    let mut bits = 0;
    for &c in bytes {
        let v = val(c);
        if v < 0 {
            break; // padding
        }
        acc = (acc << 6) | v as u32;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((acc >> bits) as u8);
        }
    }
    out
}

fn f32_of(v: &Value) -> f32 {
    v.as_f64().unwrap() as f32
}

fn i32_of(v: &Value) -> i32 {
    v.as_i64().unwrap() as i32
}

fn triple(v: &Value) -> (i32, i32, i32) {
    let a = v.as_array().unwrap();
    (i32_of(&a[0]), i32_of(&a[1]), i32_of(&a[2]))
}

struct Outcome {
    name: String,
    verdict: &'static str,
    detail: String,
}

fn run_fixture(name: &str, fx: &Value) -> Outcome {
    let out = |verdict: &'static str, detail: String| Outcome {
        name: name.to_string(),
        verdict,
        detail,
    };

    if fx["v"].as_i64() != Some(3) {
        return out("skip", "not a v3 fixture".into());
    }
    if fx["evaluator"].as_str() != Some("WalkNodeEvaluator") {
        return out("skip", format!("evaluator {}", fx["evaluator"]));
    }
    let flags = &fx["flags"];
    if flags["canStandOnFluidAtFeet"].as_bool() == Some(true) {
        return out("skip", "fluid-walking mob".into());
    }

    // Recorded PathType ordinal → kernel ordinal, resolved by constant name so
    // a fixture recorded against a different enum order still replays.
    let names_v = fx["typeNames"].as_array().unwrap();
    let remap: Vec<u8> = names_v
        .iter()
        .map(|n| {
            let n = n.as_str().unwrap();
            TYPE_NAMES
                .iter()
                .position(|&k| k == n)
                .unwrap_or_else(|| panic!("{name}: unknown PathType {n}")) as u8
        })
        .collect();
    assert_eq!(remap.len(), TYPE_COUNT, "{name}: PathType count");

    let bbox_v = fx["bbox"].as_array().unwrap();
    let bbox: [i32; 6] = std::array::from_fn(|i| i32_of(&bbox_v[i]));

    let mut shapes: HashMap<i32, Vec<[f64; 6]>> = HashMap::new();
    for entry in fx["shapes"].as_array().unwrap() {
        let e = entry.as_array().unwrap();
        let gi = i32_of(&e[0]);
        let mut boxes = Vec::with_capacity((e.len() - 1) / 6);
        let mut i = 1;
        while i + 6 <= e.len() {
            boxes.push(std::array::from_fn(|k| e[i + k].as_f64().unwrap()));
            i += 6;
        }
        shapes.insert(gi, boxes);
    }

    let mut world = World::new(
        bbox,
        i32_of(&fx["minY"]),
        i32_of(&fx["maxY"]),
        b64_decode(fx["typeGrid"].as_str().unwrap())
            .into_iter()
            .map(|t| remap[t as usize])
            .collect(),
        b64_decode(fx["emptyBits"].as_str().unwrap()),
        b64_decode(fx["waterBits"].as_str().unwrap()),
        b64_decode(fx["airBits"].as_str().unwrap()),
        b64_decode(fx["passLandBits"].as_str().unwrap()),
        shapes,
    );

    let malus_v = fx["malus"].as_array().unwrap();
    assert_eq!(malus_v.len(), TYPE_COUNT, "{name}: malus table size");
    let mut malus = [0.0f32; TYPE_COUNT];
    for (rec, &k) in remap.iter().enumerate() {
        malus[k as usize] = f32_of(&malus_v[rec]);
    }
    let pos_v = fx["pos"].as_array().unwrap();
    let mob = MobSpec {
        pos: [
            pos_v[0].as_f64().unwrap(),
            pos_v[1].as_f64().unwrap(),
            pos_v[2].as_f64().unwrap(),
        ],
        block_pos: {
            let (x, y, z) = triple(&fx["blockPos"]);
            [x, y, z]
        },
        bb_width: f32_of(&fx["bbWidth"]),
        bb_height: f32_of(&fx["bbHeight"]),
        malus,
        can_pass_doors: flags["canPassDoors"].as_bool().unwrap(),
        can_open_doors: flags["canOpenDoors"].as_bool().unwrap(),
        can_float: flags["canFloat"].as_bool().unwrap(),
        can_walk_over_fences: flags["canWalkOverFences"].as_bool().unwrap(),
        on_ground: flags["onGround"].as_bool().unwrap(),
        in_floatable_fluid: flags["inWater"].as_bool().unwrap(),
        step_height: f32_of(&fx["stepHeight"]),
        max_fall_distance: i32_of(&fx["maxFallDistance"]),
    };

    let targets: Vec<(i32, i32, i32)> = fx["targets"]
        .as_array()
        .unwrap()
        .iter()
        .map(triple)
        .collect();

    let mut eval = WalkEvaluator::new(&mut world, mob);
    let mut arena: Vec<Node> = Vec::with_capacity(1024);
    let got = find_path(
        &mut eval,
        &mut arena,
        &targets,
        f32_of(&fx["maxRange"]),
        i32_of(&fx["accuracy"]),
        f32_of(&fx["depthMult"]),
        i32_of(&fx["maxVisitedNodes"]),
    );
    let misses = world.misses;

    let expected = &fx["result"];
    let verdict_detail = match (expected.is_null(), &got) {
        (true, None) => None,
        (true, Some(_)) => Some("vanilla null, kernel Some".to_string()),
        (false, None) => Some("vanilla path, kernel None".to_string()),
        (false, Some(g)) => {
            let want_reach = expected["canReach"].as_bool().unwrap();
            let want_target = triple(&expected["target"]);
            let want_nodes: Vec<(i32, i32, i32)> = expected["nodes"]
                .as_array()
                .unwrap()
                .iter()
                .map(triple)
                .collect();
            if g.reached != want_reach {
                Some(format!("reached {} != vanilla {}", g.reached, want_reach))
            } else if g.target != want_target {
                Some(format!(
                    "target {:?} != vanilla {:?}",
                    g.target, want_target
                ))
            } else if g.nodes != want_nodes {
                let diverge = g
                    .nodes
                    .iter()
                    .zip(want_nodes.iter())
                    .position(|(a, b)| a != b)
                    .unwrap_or_else(|| g.nodes.len().min(want_nodes.len()));
                Some(format!(
                    "nodes differ at {} (kernel {} vs vanilla {} nodes): kernel {:?} vanilla {:?}",
                    diverge,
                    g.nodes.len(),
                    want_nodes.len(),
                    g.nodes.get(diverge),
                    want_nodes.get(diverge)
                ))
            } else {
                None
            }
        }
    };

    match verdict_detail {
        None if misses == 0 => out("ok", String::new()),
        None => out("ok-with-misses", format!("{misses} out-of-grid reads")),
        Some(d) if misses > 0 => out("inconclusive", format!("{d} [{misses} out-of-grid reads]")),
        Some(d) => out("FAIL", d),
    }
}

#[test]
fn fixtures_bit_exact() {
    let dir = fixtures_dir();
    let mut entries: Vec<_> = fs::read_dir(&dir)
        .unwrap_or_else(|e| panic!("fixtures dir {dir:?}: {e}"))
        .map(|e| e.unwrap().path())
        .filter(|p| p.extension().map(|x| x == "json").unwrap_or(false))
        .collect();
    entries.sort();
    assert!(!entries.is_empty(), "no fixtures in {dir:?}");

    let mut ok = 0;
    let mut skip = 0;
    let mut inconclusive = 0;
    let mut failures = Vec::new();
    for path in &entries {
        let name = path.file_stem().unwrap().to_string_lossy().to_string();
        let fx: Value = serde_json::from_str(&fs::read_to_string(path).unwrap())
            .unwrap_or_else(|e| panic!("{name}: bad json: {e}"));
        let o = run_fixture(&name, &fx);
        match o.verdict {
            "ok" | "ok-with-misses" => {
                ok += 1;
                if !o.detail.is_empty() {
                    println!("{}: {} ({})", o.name, o.verdict, o.detail);
                }
            }
            "skip" => {
                skip += 1;
                println!("{}: skip ({})", o.name, o.detail);
            }
            "inconclusive" => {
                inconclusive += 1;
                println!("{}: INCONCLUSIVE — {}", o.name, o.detail);
            }
            _ => {
                println!("{}: FAIL — {}", o.name, o.detail);
                failures.push(o);
            }
        }
    }
    println!(
        "path fixtures: {ok} ok, {inconclusive} inconclusive, {skip} skipped, {} failed of {}",
        failures.len(),
        entries.len()
    );
    assert!(
        failures.is_empty(),
        "{} fixture(s) diverged:\n{}",
        failures.len(),
        failures
            .iter()
            .map(|f| format!("  {}: {}", f.name, f.detail))
            .collect::<Vec<_>>()
            .join("\n")
    );
}
