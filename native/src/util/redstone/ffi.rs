//! FFI for the wire-cascade replay: Java (CascadeEngine for the R3 takeover,
//! CascadeShadow for the R2 compare) marshals a network snapshot, drives entries
//! and reads back the event script. Only CascadeShadow uses `undo`.
//!
//! Layouts (i32 everywhere, native endianness):
//!   node record (28 B): x, y, z, pw, sp, edge_start, edge_len
//!   edges:              flat i32 node indices
//!   out event (20 B):   tag (0=EVAL, 1=SET, 2=UPD), x, y, z, val
//!
//! Registry mirrors the cramming sensor retention: begin/end bracket one
//! burst comparison; a handle is unique per call site. `entry` auto-saves
//! the power state so `undo` can roll back a mismatched discipline attempt.

use parking_lot::RwLock;
use std::collections::HashMap;

use super::sim::{replay_entry, EntryMode, Ev, Node, WireNetwork};

struct ReplayState {
    net: WireNetwork,
    saved: Option<Vec<i32>>,
}

static REGISTRY: RwLock<Option<HashMap<u64, ReplayState>>> = RwLock::new(None);

/// Build the network from the marshalled snapshot and retain it.
///
/// # Safety
/// `nodes_ptr` must point at `n` 7-i32 records; `edges_ptr` at `edge_count`
/// i32 indices, all < n. Buffers are owned by Java and outlive begin..end.
#[no_mangle]
pub unsafe extern "C" fn oxi_redstone_begin(
    handle: u64,
    nodes_ptr: *const i32,
    n: i32,
    edges_ptr: *const i32,
    edge_count: i32,
) -> i32 {
    if nodes_ptr.is_null()
        || n <= 0
        || n > 1 << 20
        || edge_count < 0
        || edge_count > 1 << 24
        || (edge_count > 0 && edges_ptr.is_null())
    {
        return -1;
    }
    let raw = std::slice::from_raw_parts(nodes_ptr, n as usize * 7);
    let edges_raw = if edge_count > 0 {
        std::slice::from_raw_parts(edges_ptr, edge_count as usize)
    } else {
        &[]
    };
    let mut nodes = Vec::with_capacity(n as usize);
    for i in 0..n as usize {
        let r = &raw[i * 7..i * 7 + 7];
        if r[5] < 0 || r[6] < 0 {
            return -1;
        }
        let (start, len) = (r[5] as usize, r[6] as usize);
        let Some(end) = start.checked_add(len) else {
            return -1;
        };
        if end > edges_raw.len() {
            return -1;
        }
        let edge_slice = &edges_raw[start..end];
        if edge_slice.iter().any(|&edge| edge < 0 || edge >= n) {
            return -1;
        }
        let edges = edge_slice.iter().map(|&edge| edge as usize).collect();
        nodes.push(Node {
            pos: (r[0], r[1], r[2]),
            pw: r[3],
            sp: r[4],
            edges,
        });
    }
    let mut reg = REGISTRY.write();
    reg.get_or_insert_with(HashMap::new).insert(
        handle,
        ReplayState {
            net: WireNetwork::new(nodes),
            saved: None,
        },
    );
    0
}

/// Run one cascade entry (trigger = node index; mode 0=InDrain, 1=Depth0),
/// writing predicted events to `out_ptr`. Auto-saves power state for
/// `oxi_redstone_undo`. Returns the event count, or -1 (unknown handle /
/// bad index / `out_cap` exceeded).
///
/// # Safety
/// `out_ptr` must hold `out_cap` 5-i32 records.
#[no_mangle]
pub unsafe extern "C" fn oxi_redstone_entry(
    handle: u64,
    trigger_idx: i32,
    mode: i32,
    out_ptr: *mut i32,
    out_cap: i32,
) -> i32 {
    if out_ptr.is_null() || out_cap <= 0 || trigger_idx < 0 {
        return -1;
    }
    let mut reg = REGISTRY.write();
    let Some(map) = reg.as_mut() else { return -1 };
    let Some(st) = map.get_mut(&handle) else {
        return -1;
    };
    if trigger_idx as usize >= st.net.nodes.len() {
        return -1;
    }
    st.saved = Some(st.net.nodes.iter().map(|nd| nd.pw).collect());
    let trigger = st.net.nodes[trigger_idx as usize].pos;
    let m = if mode == 0 {
        EntryMode::InDrain
    } else {
        EntryMode::Depth0
    };
    let (events, _res) = replay_entry(&mut st.net, trigger, m);
    if events.len() > out_cap as usize {
        return -1;
    }
    let out = std::slice::from_raw_parts_mut(out_ptr, events.len() * 5);
    for (i, ev) in events.iter().enumerate() {
        let (tag, p, val) = match ev {
            Ev::Eval(p, pw) => (0, p, *pw),
            Ev::Set(p, pw) => (1, p, *pw),
            Ev::Upd(p) => (2, p, 0),
        };
        let o = &mut out[i * 5..i * 5 + 5];
        o[0] = tag;
        o[1] = p.0;
        o[2] = p.1;
        o[3] = p.2;
        o[4] = val;
    }
    events.len() as i32
}

/// Roll the power state back to before the last `oxi_redstone_entry`
/// (adaptive discipline retry). Returns 0, or -1 when there is nothing to
/// undo / the handle is unknown.
#[no_mangle]
pub extern "C" fn oxi_redstone_undo(handle: u64) -> i32 {
    let mut reg = REGISTRY.write();
    let Some(map) = reg.as_mut() else { return -1 };
    let Some(st) = map.get_mut(&handle) else {
        return -1;
    };
    let Some(saved) = st.saved.take() else {
        return -1;
    };
    for (nd, pw) in st.net.nodes.iter_mut().zip(saved) {
        nd.pw = pw;
    }
    0
}

/// Drop the retention.
#[no_mangle]
pub extern "C" fn oxi_redstone_end(handle: u64) {
    let mut reg = REGISTRY.write();
    if let Some(map) = reg.as_mut() {
        map.remove(&handle);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ffi_roundtrip_line_cascade() {
        // 3-wire line, sp=15 at node 0, all unpowered: begin → entry → events.
        // node: x y z pw sp edge_start edge_len
        let nodes: Vec<i32> = vec![
            0, 64, 0, 0, 15, 0, 1, //   0: edges [1]
            1, 64, 0, 0, 0, 1, 2, //    1: edges [0, 2]
            2, 64, 0, 0, 0, 3, 1, //    2: edges [1]
        ];
        let edges: Vec<i32> = vec![1, 0, 2, 1];
        unsafe {
            assert_eq!(
                oxi_redstone_begin(7001, nodes.as_ptr(), 3, edges.as_ptr(), 4),
                0
            );
            let mut out = vec![0i32; 5 * 4096];
            let count = oxi_redstone_entry(7001, 0, 0, out.as_mut_ptr(), 4096);
            assert!(count > 0, "entry failed: {count}");
            // first events: EVAL(0,64,0 old=0) then SET(0,64,0 new=15)
            assert_eq!(&out[0..5], &[0, 0, 64, 0, 0]);
            assert_eq!(&out[5..10], &[1, 0, 64, 0, 15]);
            // undo restores, second run reproduces identically
            let first: Vec<i32> = out[..count as usize * 5].to_vec();
            assert_eq!(oxi_redstone_undo(7001), 0);
            let count2 = oxi_redstone_entry(7001, 0, 0, out.as_mut_ptr(), 4096);
            assert_eq!(count, count2);
            assert_eq!(&out[..count as usize * 5], &first[..]);
            oxi_redstone_end(7001);
        }
    }

    #[test]
    fn begin_rejects_negative_and_overflowing_ranges() {
        let valid = [0, 64, 0, 0, 0, 0, 0];
        unsafe {
            assert_eq!(
                oxi_redstone_begin(7002, valid.as_ptr(), 1, std::ptr::null(), -1),
                -1
            );
            assert_eq!(
                oxi_redstone_begin(7002, valid.as_ptr(), (1 << 20) + 1, std::ptr::null(), 0,),
                -1
            );
        }

        for (start, len) in [(-1, 0), (0, -1), (i32::MAX, 1)] {
            let invalid = [0, 64, 0, 0, 0, start, len];
            unsafe {
                assert_eq!(
                    oxi_redstone_begin(7002, invalid.as_ptr(), 1, std::ptr::null(), 0),
                    -1
                );
            }
        }

        let with_edge = [0, 64, 0, 0, 0, 0, 1];
        let negative_edge = [-1];
        unsafe {
            assert_eq!(
                oxi_redstone_begin(7002, with_edge.as_ptr(), 1, negative_edge.as_ptr(), 1,),
                -1
            );
        }
    }
}
