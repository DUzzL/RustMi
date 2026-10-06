//! ChainRestrictedNeighborUpdater (mojmap CollectingNeighborUpdater)
//! queue discipline, ported 1:1 from the 26.1.2 source (unchanged in 26.3). Pure
//! math — no world access — exactly as observed:
//!
//! ```text
//! enqueue(entry):
//!     running  = depth > 0
//!     too_many = max >= 0 && depth >= max
//!     depth++
//!     if !too_many { if running { pending.add(entry) } else { queue.push_front(entry) } }
//!     else if depth-1 == max { log skip-once }
//!     if !running { run_queued() }
//!
//! run_queued():
//!     while queue or pending non-empty:
//!         push pending REVERSED onto queue front; pending.clear()
//!         head = queue.front()                    // peek, not pop!
//!         while pending.is_empty():
//!             if !head.update(world) { queue.pop_front(); break }
//!     finally: clear all, depth = 0
//! ```
//!
//! `SixWayEntry` is a resumable generator: each update() fires ONE direction
//! (UPDATE_ORDER = W,E,D,U,N,S) and returns has-more; `except` is skipped both
//! at construction and after each step. The result is depth-first traversal
//! with generator resumption — the cascade shape every wire fixture must obey.

use super::Pos;

/// NeighborUpdater.UPDATE_ORDER: WEST, EAST, DOWN, UP, NORTH, SOUTH.
pub const UPDATE_ORDER: [Pos; 6] = [
    (-1, 0, 0),
    (1, 0, 0),
    (0, -1, 0),
    (0, 1, 0),
    (0, 0, -1),
    (0, 0, 1),
];

/// A queued update entry. SixWay mirrors vanilla's resumable six-direction
/// generator; Simple fires a single neighborChanged at `pos`.
#[derive(Debug, Clone)]
pub enum Entry {
    Simple {
        pos: Pos,
    },
    SixWay {
        pos: Pos,
        except: Option<usize>,
        idx: usize,
    },
}

impl Entry {
    pub fn six_way(pos: Pos, except: Option<usize>) -> Entry {
        let mut idx = 0;
        if let Some(e) = except {
            if e == 0 {
                idx = 1; // constructor-skip, per vanilla SixWayEntry::new
            }
        }
        Entry::SixWay { pos, except, idx }
    }

    /// One update() step: the neighbour position this step fires at, plus
    /// whether the entry has more steps after this one.
    fn step(&mut self) -> (Pos, bool) {
        match self {
            Entry::Simple { pos } => (*pos, false),
            Entry::SixWay { pos, except, idx } => {
                let d = UPDATE_ORDER[*idx];
                *idx += 1;
                // post-step except-skip, per vanilla
                if *idx < UPDATE_ORDER.len() && Some(*idx) == *except {
                    *idx += 1;
                }
                let target = (pos.0 + d.0, pos.1 + d.1, pos.2 + d.2);
                (target, *idx < UPDATE_ORDER.len())
            }
        }
    }
}

/// The simulation a drain runs against: one neighborChanged execution at
/// `target`, returning every entry it enqueued (in enqueue order) — e.g. a
/// wire evaluator reacting with setBlock + seven updateNeighborsAt SixWays.
pub trait Sim {
    fn run_update(&mut self, target: Pos) -> Vec<Entry>;
}

/// Drain statistics + budget outcome.
#[derive(Debug, Default, PartialEq, Eq)]
pub struct DrainResult {
    pub updates_run: u64,
    pub enqueues: u64,
    pub budget_exceeded: bool,
}

pub struct Collector {
    max_chain_depth: i64,
    depth: i64,
    queue: Vec<Entry>, // front = end (use as stack via push/pop at tail)
    pending: Vec<Entry>,
    result: DrainResult,
}

impl Collector {
    pub fn new(max_chain_depth: i64) -> Collector {
        Collector {
            max_chain_depth,
            depth: 0,
            queue: Vec::new(),
            pending: Vec::new(),
            result: DrainResult::default(),
        }
    }

    /// Top-level entry: vanilla enqueue() at depth 0 + the drain loop.
    /// Returns the targets fired, in exact vanilla order.
    pub fn run<S: Sim>(&mut self, sim: &mut S, first: Entry) -> (Vec<Pos>, DrainResult) {
        debug_assert_eq!(self.depth, 0);
        self.enqueue_bookkeeping(first);
        let fired = self.run_queued(sim);
        let result = std::mem::take(&mut self.result);
        (fired, result)
    }

    fn enqueue_bookkeeping(&mut self, entry: Entry) {
        let running = self.depth > 0;
        let too_many = self.max_chain_depth >= 0 && self.depth >= self.max_chain_depth;
        self.depth += 1;
        self.result.enqueues += 1;
        if !too_many {
            if running {
                self.pending.push(entry);
            } else {
                self.queue.push(entry); // stack top = tail
            }
        } else if self.depth - 1 == self.max_chain_depth {
            self.result.budget_exceeded = true;
        }
    }

    fn run_queued<S: Sim>(&mut self, sim: &mut S) -> Vec<Pos> {
        let mut fired = Vec::new();
        while !self.queue.is_empty() || !self.pending.is_empty() {
            // pending reversed onto the stack top → preserves pending order
            // at the head, exactly like vanilla's reverse-push loop.
            while let Some(e) = self.pending.pop() {
                self.queue.push(e);
            }
            // head = peek (stays until exhausted)
            while self.pending.is_empty() {
                let (target, has_more) = {
                    let head = self.queue.last_mut().expect("queue non-empty");
                    head.step()
                };
                self.result.updates_run += 1;
                fired.push(target);
                let reactions = sim.run_update(target);
                for r in reactions {
                    self.enqueue_bookkeeping(r); // depth > 0 here → pending
                }
                if !has_more {
                    self.queue.pop();
                    break;
                }
            }
        }
        // finally-block semantics
        self.queue.clear();
        self.pending.clear();
        self.depth = 0;
        fired
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct ScriptedSim {
        /// (when this target is fired) -> entries it enqueues
        reactions: Vec<(Pos, Vec<Entry>)>,
    }

    impl Sim for ScriptedSim {
        fn run_update(&mut self, target: Pos) -> Vec<Entry> {
            if let Some(i) = self.reactions.iter().position(|(p, _)| *p == target) {
                return self.reactions.remove(i).1;
            }
            Vec::new()
        }
    }

    const O: Pos = (0, 0, 0);
    fn west(p: Pos) -> Pos {
        (p.0 - 1, p.1, p.2)
    }
    fn east(p: Pos) -> Pos {
        (p.0 + 1, p.1, p.2)
    }
    fn down(p: Pos) -> Pos {
        (p.0, p.1 - 1, p.2)
    }
    fn up(p: Pos) -> Pos {
        (p.0, p.1 + 1, p.2)
    }
    fn north(p: Pos) -> Pos {
        (p.0, p.1, p.2 - 1)
    }
    fn south(p: Pos) -> Pos {
        (p.0, p.1, p.2 + 1)
    }

    #[test]
    fn six_way_plain_order_is_wedons() {
        let mut sim = ScriptedSim { reactions: vec![] };
        let (fired, res) = Collector::new(1_000_000).run(&mut sim, Entry::six_way(O, None));
        assert_eq!(
            fired,
            vec![west(O), east(O), down(O), up(O), north(O), south(O)]
        );
        assert_eq!(res.updates_run, 6);
        assert!(!res.budget_exceeded);
    }

    #[test]
    fn except_skips_at_construction_and_mid_run() {
        let mut sim = ScriptedSim { reactions: vec![] };
        let (fired, _) = Collector::new(-1).run(&mut sim, Entry::six_way(O, Some(0)));
        assert_eq!(fired, vec![east(O), down(O), up(O), north(O), south(O)]);

        let mut sim2 = ScriptedSim { reactions: vec![] };
        let (fired2, _) = Collector::new(-1).run(&mut sim2, Entry::six_way(O, Some(2)));
        assert_eq!(fired2, vec![west(O), east(O), up(O), north(O), south(O)]);
    }

    #[test]
    fn dfs_with_generator_resumption() {
        // Firing WEST of O enqueues a Simple at (5,5,5): vanilla drains the
        // child BEFORE resuming the SixWay's EAST step (pending → stack top,
        // head re-peeked).
        let child: Pos = (5, 5, 5);
        let mut sim = ScriptedSim {
            reactions: vec![(west(O), vec![Entry::Simple { pos: child }])],
        };
        let (fired, _) = Collector::new(-1).run(&mut sim, Entry::six_way(O, None));
        assert_eq!(
            fired,
            vec![west(O), child, east(O), down(O), up(O), north(O), south(O)]
        );
    }

    #[test]
    fn pending_order_preserved_for_sibling_enqueues() {
        // One update enqueues TWO entries: reverse-push onto the stack must
        // preserve their enqueue order at the head (A runs before B).
        let a: Pos = (7, 0, 0);
        let b: Pos = (8, 0, 0);
        let mut sim = ScriptedSim {
            reactions: vec![(
                west(O),
                vec![Entry::Simple { pos: a }, Entry::Simple { pos: b }],
            )],
        };
        let (fired, _) = Collector::new(-1).run(&mut sim, Entry::six_way(O, None));
        assert_eq!(&fired[..3], &[west(O), a, b]);
    }

    #[test]
    fn nested_children_run_depth_first() {
        // child A enqueues grandchild G: G must run before sibling B AND
        // before the root SixWay resumes.
        let a: Pos = (7, 0, 0);
        let b: Pos = (8, 0, 0);
        let g: Pos = (9, 0, 0);
        let mut sim = ScriptedSim {
            reactions: vec![
                (
                    west(O),
                    vec![Entry::Simple { pos: a }, Entry::Simple { pos: b }],
                ),
                (a, vec![Entry::Simple { pos: g }]),
            ],
        };
        let (fired, _) = Collector::new(-1).run(&mut sim, Entry::six_way(O, None));
        assert_eq!(&fired[..4], &[west(O), a, g, b]);
        assert_eq!(fired[4], east(O));
    }

    #[test]
    fn budget_drops_excess_enqueues() {
        // max=3: the root SixWay consumes budget slot 1; its first fired
        // update enqueues 5 children — only those within budget survive.
        let kids: Vec<Entry> = (0..5)
            .map(|i| Entry::Simple {
                pos: (100 + i, 0, 0),
            })
            .collect();
        let mut sim = ScriptedSim {
            reactions: vec![(west(O), kids)],
        };
        let (fired, res) = Collector::new(3).run(&mut sim, Entry::six_way(O, None));
        assert!(res.budget_exceeded);
        // budget 3: root(depth0→1), kid0(1→2), kid1(2→3), kid2+ dropped.
        let ran_kids = fired.iter().filter(|p| p.0 >= 100).count();
        assert_eq!(ran_kids, 2);
    }
}
