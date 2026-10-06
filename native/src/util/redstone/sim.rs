//! R1 wire-network simulator: replays a vanilla redstone-wire cascade
//! bit-exactly from a network snapshot, without touching the world.
//!
//! Composition of the three pillars:
//!   * power math   — `DefaultRedstoneWireEvaluator` (26.1.2 source, ported
//!     below): target = sp==15 ? 15 : max(sp, max(neighbour wire power)−1).
//!     Direction order is irrelevant here (pure max).
//!   * update order — [`jorder::evaluator_update_order`]: the evaluator's
//!     7-position HashSet iteration (the "locational" part).
//!   * cascade shape — [`collector::Collector`]: ChainRestrictedNeighborUpdater
//!     queue discipline (DFS resumption, pending reversal, depth budget).
//!
//! Frozen-snapshot validity: a wire SET changes only the POWER property, so
//! the connection graph (`edges`) never changes mid-cascade; every vanilla
//! reactive component (torch, repeater, comparator, observer, piston) defers
//! via scheduled ticks / block events, so external signal (`sp`) is constant
//! for the duration of the synchronous cascade. Both invariants break only
//! under same-tick world mutation by mods — the R2 mutation-abort case.
//!
//! Event log format mirrors CascadeRecorder exactly:
//!   EVAL x y z oldPower   — wire neighborChanged entered the evaluator
//!   SET  x y z newPower   — state write (intermediate values observable!)
//!   UPD  x y z            — updateNeighborsAt emission centre

use std::collections::HashMap;

use super::collector::{Collector, DrainResult, Entry, Sim};
use super::jorder;
use super::Pos;

pub struct Node {
    pub pos: Pos,
    /// Current wire power (mutated during replay).
    pub pw: i32,
    /// External (non-wire) signal at snapshot time — getStrongPowerAt.
    pub sp: i32,
    /// Indices of wires whose power feeds this one (incoming edges per
    /// calculateWirePowerAt: same-level + the two diagonal rules).
    pub edges: Vec<usize>,
}

pub struct WireNetwork {
    pub nodes: Vec<Node>,
    by_pos: HashMap<Pos, usize>,
}

impl WireNetwork {
    pub fn new(nodes: Vec<Node>) -> WireNetwork {
        let by_pos = nodes.iter().enumerate().map(|(i, n)| (n.pos, i)).collect();
        WireNetwork { nodes, by_pos }
    }
}

/// One replay event, string-identical to the recorder's format via `fmt()`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Ev {
    Eval(Pos, i32),
    Set(Pos, i32),
    Upd(Pos),
}

impl Ev {
    pub fn fmt(&self) -> String {
        match self {
            Ev::Eval(p, pw) => format!("EVAL {} {} {} {}", p.0, p.1, p.2, pw),
            Ev::Set(p, pw) => format!("SET {} {} {} {}", p.0, p.1, p.2, pw),
            Ev::Upd(p) => format!("UPD {} {} {}", p.0, p.1, p.2),
        }
    }
}

struct WireSim<'a> {
    net: &'a mut WireNetwork,
    events: &'a mut Vec<Ev>,
}

/// DefaultRedstoneWireEvaluator power step (yarn: DefaultRedstoneController.update), shared by both entry modes:
/// EVAL (+SET when power changes). Returns the evaluator's 7-position
/// update list (jorder) when a SET happened, None otherwise.
fn do_update(net: &mut WireNetwork, events: &mut Vec<Ev>, target: Pos) -> Option<[Pos; 7]> {
    let &i = net.by_pos.get(&target)?;

    let old = net.nodes[i].pw;
    events.push(Ev::Eval(target, old));

    let n = &net.nodes[i];
    let target_power = if n.sp == 15 {
        15
    } else {
        let wire_max = n.edges.iter().map(|&j| net.nodes[j].pw).max().unwrap_or(0);
        n.sp.max((wire_max - 1).max(0))
    };
    if old == target_power {
        return None;
    }

    net.nodes[i].pw = target_power;
    events.push(Ev::Set(target, target_power));
    Some(jorder::evaluator_update_order(target))
}

impl Sim for WireSim<'_> {
    fn run_update(&mut self, target: Pos) -> Vec<Entry> {
        // neighborChanged on a non-wire block enqueues nothing in a pure
        // dust network (reactive blocks are R2 mutation-abort territory).
        let Some(seven) = do_update(self.net, self.events, target) else {
            return Vec::new();
        };
        // In-drain: the 7 updateNeighbors calls all enqueue (pending) and
        // dispatch after this update returns — UPD events come out batched.
        let mut entries = Vec::with_capacity(7);
        for p in seven {
            self.events.push(Ev::Upd(p));
            entries.push(Entry::six_way(p, None));
        }
        entries
    }
}

/// Vanilla default for ChainRestrictedNeighborUpdater's depth budget.
pub const VANILLA_MAX_CHAIN: i64 = 1_000_000;

/// How the cascade's first evaluator call was reached. The production mixin
/// reads this off the live collector; fixtures are detected adaptively.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EntryMode {
    /// The wire was dispatched from inside a running collector drain
    /// (lever toggle → SixWay → wire): its 7 updateNeighbors enqueue as
    /// pending and dispatch together after update() returns.
    InDrain,
    /// The evaluator was invoked directly at collector depth 0 (block
    /// place/remove → onBlockAdded → controller.update): each of the 7
    /// updateNeighbors is its own addAndRun — an immediate full drain —
    /// so dispatches interleave between the UPD emissions.
    Depth0,
}

/// Run one cascade entry in the given mode. The network keeps its mutated
/// powers, so successive entries continue from where the previous one
/// settled — exactly like successive entry points inside one vanilla burst.
pub fn replay_entry(
    net: &mut WireNetwork,
    trigger: Pos,
    mode: EntryMode,
) -> (Vec<Ev>, DrainResult) {
    let mut events = Vec::new();
    let mut total = DrainResult::default();
    match mode {
        EntryMode::InDrain => {
            let mut sim = WireSim {
                net,
                events: &mut events,
            };
            let mut col = Collector::new(VANILLA_MAX_CHAIN);
            let (_fired, result) = col.run(&mut sim, Entry::Simple { pos: trigger });
            total = result;
        }
        EntryMode::Depth0 => {
            if let Some(seven) = do_update(net, &mut events, trigger) {
                for p in seven {
                    events.push(Ev::Upd(p));
                    // addAndRun: a fresh drain per updateNeighbors call;
                    // nested wire updates inside it behave in-drain.
                    let mut sim = WireSim {
                        net,
                        events: &mut events,
                    };
                    let mut col = Collector::new(VANILLA_MAX_CHAIN);
                    let (_fired, r) = col.run(&mut sim, Entry::six_way(p, None));
                    total.updates_run += r.updates_run;
                    total.enqueues += r.enqueues;
                    total.budget_exceeded |= r.budget_exceeded;
                }
            }
        }
    }
    (events, total)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn net(nodes: Vec<(Pos, i32, i32, Vec<usize>)>) -> WireNetwork {
        WireNetwork::new(
            nodes
                .into_iter()
                .map(|(pos, pw, sp, edges)| Node { pos, pw, sp, edges })
                .collect(),
        )
    }

    #[test]
    fn single_wire_powers_up_from_sp() {
        // Lever just turned on next to one dust: sp=15 → wire 0→15.
        let mut w = net(vec![((0, 64, 0), 0, 15, vec![])]);
        let (ev, _) = replay_entry(&mut w, (0, 64, 0), EntryMode::InDrain);
        assert_eq!(ev[0], Ev::Eval((0, 64, 0), 0));
        assert_eq!(ev[1], Ev::Set((0, 64, 0), 15));
        assert_eq!(ev.iter().filter(|e| matches!(e, Ev::Upd(_))).count(), 7);
        // settled: no second SET anywhere
        assert_eq!(ev.iter().filter(|e| matches!(e, Ev::Set(..))).count(), 1);
        assert_eq!(w.nodes[0].pw, 15);
    }

    #[test]
    fn line_decays_by_one_per_dust() {
        // 0 ← 1 ← 2: wire 0 has sp=15, wires connected in a line.
        let mut w = net(vec![
            ((0, 64, 0), 0, 15, vec![1]),
            ((1, 64, 0), 0, 0, vec![0, 2]),
            ((2, 64, 0), 0, 0, vec![1]),
        ]);
        let (_ev, _) = replay_entry(&mut w, (0, 64, 0), EntryMode::InDrain);
        assert_eq!(w.nodes[0].pw, 15);
        assert_eq!(w.nodes[1].pw, 14);
        assert_eq!(w.nodes[2].pw, 13);
    }

    #[test]
    fn power_off_decays_to_zero() {
        // Same line, lever off (sp=0), wires at 15/14/13 → all to 0, with
        // vanilla's multi-step decay (intermediate SETs are expected).
        let mut w = net(vec![
            ((0, 64, 0), 15, 0, vec![1]),
            ((1, 64, 0), 14, 0, vec![0, 2]),
            ((2, 64, 0), 13, 0, vec![1]),
        ]);
        let (ev, _) = replay_entry(&mut w, (0, 64, 0), EntryMode::InDrain);
        assert!(w.nodes.iter().all(|n| n.pw == 0));
        // vanilla's falling-edge churn produces more than one SET per wire
        assert!(ev.iter().filter(|e| matches!(e, Ev::Set(..))).count() >= 3);
    }
}

#[cfg(test)]
mod fixture_tests {
    use super::*;

    fn pos_of(a: &serde_json::Value) -> Pos {
        let p = a.as_array().unwrap();
        (
            p[0].as_i64().unwrap() as i32,
            p[1].as_i64().unwrap() as i32,
            p[2].as_i64().unwrap() as i32,
        )
    }

    /// Replay every recorded lag-machine cascade and require the produced
    /// event stream to be string-identical to the recorder's, entry by
    /// entry. Entries are driven from the fixture itself: whenever our
    /// cascade exhausts, the next unconsumed fixture event must be an EVAL
    /// (vanilla's next entry point — e.g. the lever's SixWay resuming) and
    /// becomes the next trigger against the SAME mutated network.
    #[test]
    fn replays_recorded_cascades_bit_exact() {
        let dir = concat!(env!("CARGO_MANIFEST_DIR"), "/../fixtures/redstone");
        let mut checked = 0;
        for entry in std::fs::read_dir(dir).expect("fixtures dir") {
            let path = entry.unwrap().path();
            if path.extension().map(|e| e != "json").unwrap_or(true) {
                continue;
            }
            let v: serde_json::Value =
                serde_json::from_str(&std::fs::read_to_string(&path).unwrap()).unwrap();
            if v["multiNetwork"].as_bool() == Some(true) || v["overflow"].as_bool() == Some(true) {
                continue;
            }
            let nodes_j = v["nodes"].as_array().unwrap();
            let mut by_pos = HashMap::new();
            for (i, n) in nodes_j.iter().enumerate() {
                by_pos.insert(pos_of(&n["p"]), i);
            }
            let nodes: Vec<Node> = nodes_j
                .iter()
                .map(|n| Node {
                    pos: pos_of(&n["p"]),
                    pw: n["pw"].as_i64().unwrap() as i32,
                    sp: n["sp"].as_i64().unwrap() as i32,
                    edges: n["edges"]
                        .as_array()
                        .unwrap()
                        .iter()
                        .filter_map(|e| by_pos.get(&pos_of(e)).copied())
                        .collect(),
                })
                .collect();
            let mut net = WireNetwork::new(nodes);

            let fixture: Vec<String> = v["events"]
                .as_array()
                .unwrap()
                .iter()
                .map(|e| e.as_str().unwrap().to_string())
                .collect();

            let mut produced: Vec<String> = Vec::new();
            while produced.len() < fixture.len() {
                let next = &fixture[produced.len()];
                assert!(
                    next.starts_with("EVAL "),
                    "{}: at {} expected an EVAL entry point, fixture has {:?}",
                    path.display(),
                    produced.len(),
                    next
                );
                let p: Vec<i32> = next
                    .split_whitespace()
                    .skip(1)
                    .take(3)
                    .map(|s| s.parse().unwrap())
                    .collect();
                let trigger = (p[0], p[1], p[2]);
                let before = produced.len();

                // Adaptive entry mode: the fixture doesn't store the
                // collector depth its burst started at, so try in-drain
                // first; on any divergence roll the network back and try
                // the depth-0 (addAndRun-per-updateNeighbors) discipline.
                let snapshot: Vec<i32> = net.nodes.iter().map(|n| n.pw).collect();
                let mut matched = false;
                for mode in [EntryMode::InDrain, EntryMode::Depth0] {
                    let (evs, res) = replay_entry(&mut net, trigger, mode);
                    assert!(!res.budget_exceeded, "{}: budget", path.display());
                    let strs: Vec<String> = evs.iter().map(Ev::fmt).collect();
                    let ok = !strs.is_empty()
                        && before + strs.len() <= fixture.len()
                        && strs.iter().zip(&fixture[before..]).all(|(a, b)| a == b);
                    if ok {
                        produced.extend(strs);
                        matched = true;
                        break;
                    }
                    // roll back and try the other mode
                    for (n, pw) in net.nodes.iter_mut().zip(&snapshot) {
                        n.pw = *pw;
                    }
                }
                assert!(
                    matched,
                    "{}: entry at event {} ({}) matches neither discipline\n  vanilla: {:?}",
                    path.display(),
                    before,
                    next,
                    &fixture[before..(before + 10).min(fixture.len())]
                );
            }
            assert_eq!(
                produced.len(),
                fixture.len(),
                "{}: stream length",
                path.display()
            );

            for f in v["finalPowers"].as_array().unwrap() {
                let p = pos_of(&f["p"]);
                let want = f["pw"].as_i64().unwrap() as i32;
                assert_eq!(
                    net.nodes[by_pos[&p]].pw,
                    want,
                    "{}: final power at {:?}",
                    path.display(),
                    p
                );
            }
            checked += 1;
        }
        assert!(checked > 0, "no fixtures found");
        println!("replayed {checked} cascades bit-exact");
    }
}

#[cfg(test)]
mod bench_tests {
    use super::fixture_tests_support::load_network;
    use super::*;

    /// Not a correctness test — prints the sim's wall time on the biggest
    /// recorded cascade (vanilla burned durationNanos on the same one).
    #[test]
    fn bench_biggest_cascade() {
        let path = concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../fixtures/redstone/cascade-100.json"
        );
        let Ok(txt) = std::fs::read_to_string(path) else {
            return;
        };
        let v: serde_json::Value = serde_json::from_str(&txt).unwrap();
        let (mut net, fixture, vanilla_nanos) = load_network(&v);

        let t0 = std::time::Instant::now();
        let mut produced = 0usize;
        while produced < fixture.len() {
            let next = &fixture[produced];
            let p: Vec<i32> = next
                .split_whitespace()
                .skip(1)
                .take(3)
                .map(|s| s.parse().unwrap())
                .collect();
            let snapshot: Vec<i32> = net.nodes.iter().map(|n| n.pw).collect();
            let mut ok = false;
            for mode in [EntryMode::InDrain, EntryMode::Depth0] {
                let (evs, _) = replay_entry(&mut net, (p[0], p[1], p[2]), mode);
                if !evs.is_empty()
                    && produced + evs.len() <= fixture.len()
                    && evs
                        .iter()
                        .map(Ev::fmt)
                        .zip(&fixture[produced..])
                        .all(|(a, b)| &a == b)
                {
                    produced += evs.len();
                    ok = true;
                    break;
                }
                for (n, pw) in net.nodes.iter_mut().zip(&snapshot) {
                    n.pw = *pw;
                }
            }
            assert!(ok);
        }
        let took = t0.elapsed();
        println!(
            "biggest cascade: {} events | vanilla {:.2} ms | sim+harness {:.3} ms (x{:.0}, incl. string formatting)",
            fixture.len(), vanilla_nanos as f64 / 1e6,
            took.as_secs_f64() * 1e3,
            vanilla_nanos as f64 / took.as_nanos() as f64
        );

        // pass 2: pure sim — entries pre-decided, no strings, no comparison.
        let (mut net2, _, _) = load_network(&v);
        let entries: Vec<(Pos, EntryMode)> = {
            let (mut net3, _, _) = load_network(&v);
            let mut out = Vec::new();
            let mut produced = 0usize;
            while produced < fixture.len() {
                let pv: Vec<i32> = fixture[produced]
                    .split_whitespace()
                    .skip(1)
                    .take(3)
                    .map(|s| s.parse().unwrap())
                    .collect();
                let trigger = (pv[0], pv[1], pv[2]);
                let snapshot: Vec<i32> = net3.nodes.iter().map(|n| n.pw).collect();
                for mode in [EntryMode::InDrain, EntryMode::Depth0] {
                    let (evs, _) = replay_entry(&mut net3, trigger, mode);
                    if !evs.is_empty()
                        && produced + evs.len() <= fixture.len()
                        && evs
                            .iter()
                            .map(Ev::fmt)
                            .zip(&fixture[produced..])
                            .all(|(a, b)| &a == b)
                    {
                        produced += evs.len();
                        out.push((trigger, mode));
                        break;
                    }
                    for (n, pw) in net3.nodes.iter_mut().zip(&snapshot) {
                        n.pw = *pw;
                    }
                }
            }
            out
        };
        let t1 = std::time::Instant::now();
        let mut total_events = 0usize;
        for &(trigger, mode) in &entries {
            let (evs, _) = replay_entry(&mut net2, trigger, mode);
            total_events += evs.len();
        }
        let pure = t1.elapsed();
        println!(
            "pure sim: {} events in {:.3} ms (x{:.0} vs vanilla's eval side)",
            total_events,
            pure.as_secs_f64() * 1e3,
            vanilla_nanos as f64 / pure.as_nanos() as f64
        );
    }
}

#[cfg(test)]
mod fixture_tests_support {
    use super::*;

    pub fn load_network(v: &serde_json::Value) -> (WireNetwork, Vec<String>, u64) {
        fn pos_of(a: &serde_json::Value) -> Pos {
            let p = a.as_array().unwrap();
            (
                p[0].as_i64().unwrap() as i32,
                p[1].as_i64().unwrap() as i32,
                p[2].as_i64().unwrap() as i32,
            )
        }
        let nodes_j = v["nodes"].as_array().unwrap();
        let mut by_pos = HashMap::new();
        for (i, n) in nodes_j.iter().enumerate() {
            by_pos.insert(pos_of(&n["p"]), i);
        }
        let nodes: Vec<Node> = nodes_j
            .iter()
            .map(|n| Node {
                pos: pos_of(&n["p"]),
                pw: n["pw"].as_i64().unwrap() as i32,
                sp: n["sp"].as_i64().unwrap() as i32,
                edges: n["edges"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .filter_map(|e| by_pos.get(&pos_of(e)).copied())
                    .collect(),
            })
            .collect();
        let events = v["events"]
            .as_array()
            .unwrap()
            .iter()
            .map(|e| e.as_str().unwrap().to_string())
            .collect();
        (
            WireNetwork::new(nodes),
            events,
            v["durationNanos"].as_u64().unwrap_or(0),
        )
    }
}
