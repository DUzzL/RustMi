#!/usr/bin/env python3
"""Drives one dedicated-server run of the Rustmi parity harness.

usage: drive.py <config> <outdir>
  config: V (vanilla), R (rustmi), L (lithium+fabric-api+fabrolia), LR (L + rustmi)

Every config runs the same deterministic part on a fresh world:
  freeze ticks -> deterministic gamerules -> forceload a box far from spawn (pure
  worldgen while frozen) -> save + snapshot region (worldgen compare) -> build a redstone
  contraption at y>=300 -> step exactly STEPS ticks -> save + snapshot region (contraption
  compare).
Configs with rustmi then run the shadow/perf scenario and dump counters.
Results: <outdir>/result.json, <outdir>/gen/*.mca, <outdir>/rs/*.mca, <outdir>/server.log
"""
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import queue

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
# Stand data (server, stack jars, runs) lives outside git; tools/parity/setup.sh creates it.
STAND = os.environ.get("RUSTMI_STAND", os.path.join(REPO, "stand"))
SERVER = os.path.join(STAND, "server")
JAVA = os.path.join(os.environ["JAVA_HOME"], "bin", "java") if os.environ.get("JAVA_HOME") else "java"


def rustmi_jar():
    """The jar under test: $RUSTMI_JAR, else the one compileLocally built."""
    if os.environ.get("RUSTMI_JAR"):
        return os.environ["RUSTMI_JAR"]
    libs = os.path.join(REPO, "nalim", "build", "libs")
    jars = [j for j in os.listdir(libs) if j.startswith("rustmi-") and j.endswith(".jar")
            and not j.endswith("-sources.jar")] if os.path.isdir(libs) else []
    if len(jars) != 1:
        raise SystemExit(f"expected one rustmi-<version>.jar in {libs} (build first), found {jars}")
    return os.path.join(libs, jars[0])


# The L/LR stack: every jar setup.sh put in stand/jars (Lithium, Fabric API, Fabrolia).
STACK_JARS = sorted(os.path.join(STAND, "jars", j) for j in os.listdir(os.path.join(STAND, "jars"))
                    if j.endswith(".jar")) if os.path.isdir(os.path.join(STAND, "jars")) else []
SEED = "rustmi-parity-263"
# Forceloaded worldgen box, in chunks (inclusive), far from the spawn area so every
# chunk in it is generated after `tick freeze`.
BOX = (60, 60, 67, 67)
STEPS = 400
# Contraption origin (block coords) inside chunk (60,60): x=960.., z=960..
OX, OY, OZ = 960, 300, 960

PROPERTIES = f"""online-mode=false
level-seed={SEED}
view-distance=4
simulation-distance=4
region-file-compression=deflate
server-port=25599
sync-chunk-writes=false
max-tick-time=-1
white-list=false
enforce-whitelist=false
spawn-protection=0
pause-when-empty-seconds=-1
spawn-monsters=false
spawn-animals=false
generate-structures=true
"""


class Server:
    def __init__(self, cfg, outdir, extra_jvm):
        self.lines = []
        self.q = queue.Queue()
        self.outdir = outdir
        log = open(os.path.join(outdir, "server.log"), "w")
        cmd = [JAVA, "-Xms2G", "-Xmx6G", "-XX:+UseG1GC", "-XX:NativeMemoryTracking=summary",
               "--add-modules=jdk.internal.vm.ci", "--enable-native-access=ALL-UNNAMED",
               *extra_jvm, "-jar", "fabric-server-launch.jar", "nogui"]
        self.p = subprocess.Popen(cmd, cwd=SERVER, stdin=subprocess.PIPE,
                                  stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                  text=True, bufsize=1)

        def pump():
            for line in self.p.stdout:
                log.write(line)
                log.flush()
                self.lines.append(line.rstrip("\n"))
                self.q.put(line.rstrip("\n"))
            self.q.put(None)

        threading.Thread(target=pump, daemon=True).start()

    def cmd(self, c):
        self.p.stdin.write(c + "\n")
        self.p.stdin.flush()

    def wait(self, pattern, timeout, start=None):
        """Wait for a log line matching pattern; returns the match. Lines before
        index `start` (if given) are skipped."""
        rx = re.compile(pattern)
        deadline = time.time() + timeout
        idx = len(self.lines) if start is None else start
        while time.time() < deadline:
            while idx < len(self.lines):
                m = rx.search(self.lines[idx])
                idx += 1
                if m:
                    return m
            if self.p.poll() is not None and idx >= len(self.lines):
                raise RuntimeError(f"server exited while waiting for {pattern!r}")
            time.sleep(0.05)
        raise TimeoutError(f"timeout waiting for {pattern!r}")

    def ask(self, c, pattern, timeout=30):
        mark = len(self.lines)
        self.cmd(c)
        return self.wait(pattern, timeout, start=mark)

    def gametime(self):
        return int(self.ask("time query gametime", r"time is (\d+)").group(1))

    def stop(self, timeout=120):
        try:
            self.cmd("stop")
        except BrokenPipeError:
            pass
        try:
            self.p.wait(timeout)
        except subprocess.TimeoutExpired:
            self.p.kill()
            self.p.wait()


def prepare(cfg):
    # config/rustmi-runtime holds toggles persisted by /rustmi commands; a run must start from defaults.
    for d in ("world", "config/rustmi", "config/rustmi-runtime", "logs"):
        shutil.rmtree(os.path.join(SERVER, d), ignore_errors=True)
    mods = os.path.join(SERVER, "mods")
    shutil.rmtree(mods, ignore_errors=True)
    os.makedirs(mods)
    jars = []
    if cfg in ("L", "LR"):
        jars += STACK_JARS
    if cfg in ("R", "LR"):
        jars.append(rustmi_jar())
    # Extra mods for one-off compatibility runs (e.g. C2ME); never used by harness.sh.
    jars += [j for j in os.environ.get("RUSTMI_EXTRA_MODS", "").split(os.pathsep) if j]
    for j in jars:
        shutil.copy(j, mods)
    with open(os.path.join(SERVER, "server.properties"), "w") as f:
        f.write(PROPERTIES)


def snapshot_region(dst):
    src = os.path.join(SERVER, "world", "dimensions", "minecraft", "overworld", "region")
    shutil.copytree(src, dst)


def wait_box_loaded(s, timeout=240):
    x0, z0, x1, z1 = BOX
    probes = " ".join(f"if loaded {cx * 16} 0 {cz * 16}" for cx in range(x0, x1 + 1)
                      for cz in range(z0, z1 + 1))
    deadline = time.time() + timeout
    while time.time() < deadline:
        mark = len(s.lines)
        s.cmd(f"execute {probes} run say BOXREADY")
        try:
            s.wait(r"BOXREADY", 2, start=mark)
            return
        except TimeoutError:
            pass
    raise TimeoutError("forceload box never became loaded")


def save(s):
    s.ask("save-all flush", r"Saved the game", timeout=180)


def contraption():
    x, y, z = OX, OY, OZ
    c = []
    c.append(f"fill {x - 2} {y - 1} {z - 2} {x + 33} {y - 1} {z + 17} minecraft:stone")
    # (a) 2-tick observer clock -> 19-wire line -> sticky piston shuttling a conductor
    c.append(f"fill {x + 3} {y} {z + 1} {x + 21} {y} {z + 1} minecraft:redstone_wire")
    c.append(f"setblock {x + 22} {y} {z + 1} minecraft:sticky_piston[facing=east]")
    c.append(f"setblock {x + 23} {y} {z + 1} minecraft:stone")
    # line beside the shuttle, powered through a repeater from line (a)
    c.append(f"setblock {x + 20} {y} {z + 2} minecraft:repeater[facing=north,delay=2]")
    c.append(f"fill {x + 20} {y} {z + 3} {x + 31} {y} {z + 3} minecraft:redstone_wire")
    c.append(f"fill {x + 21} {y} {z + 2} {x + 31} {y} {z + 2} minecraft:redstone_wire")
    # (b) repeater branch -> long north-south line with a lamp and a slab neighbour
    c.append(f"setblock {x + 10} {y} {z + 2} minecraft:repeater[facing=north,delay=3]")
    c.append(f"fill {x + 10} {y} {z + 3} {x + 10} {y} {z + 14} minecraft:redstone_wire")
    c.append(f"setblock {x + 9} {y} {z + 6} minecraft:redstone_lamp")
    c.append(f"setblock {x + 11} {y} {z + 5} minecraft:smooth_stone_slab[type=double]")
    c.append(f"setblock {x + 11} {y + 1} {z + 6} minecraft:redstone_wire")
    c.append(f"setblock {x + 11} {y} {z + 6} minecraft:stone")
    c.append(f"setblock {x + 11} {y} {z + 9} minecraft:repeater[facing=west,delay=1]")
    c.append(f"fill {x + 12} {y} {z + 9} {x + 17} {y} {z + 9} minecraft:redstone_wire")
    c.append(f"setblock {x + 18} {y} {z + 9} minecraft:observer[facing=west]")
    c.append(f"fill {x + 19} {y} {z + 9} {x + 26} {y} {z + 9} minecraft:redstone_wire")
    # (c) analog source without block writes: hopper fills a chest, comparator reads it
    items = ",".join(f'{{Slot:{i}b,id:"minecraft:bucket",count:1}}' for i in range(27))
    c.append(f"setblock {x + 5} {y + 1} {z + 12} minecraft:chest{{Items:[{items}]}}")
    c.append(f"setblock {x + 5} {y} {z + 12} minecraft:hopper[facing=east]")
    c.append(f"setblock {x + 6} {y} {z + 12} minecraft:chest")
    c.append(f"setblock {x + 7} {y} {z + 12} minecraft:comparator[facing=west]")
    c.append(f"fill {x + 8} {y} {z + 12} {x + 22} {y} {z + 12} minecraft:redstone_wire")
    c.append(f"setblock {x + 23} {y} {z + 12} minecraft:redstone_lamp")
    c.append(f"setblock {x + 15} {y} {z + 13} minecraft:comparator[facing=north,mode=subtract]")
    c.append(f"fill {x + 15} {y} {z + 14} {x + 15} {y} {z + 16} minecraft:redstone_wire")
    # start the clock last (second observer completes the pair)
    c.append(f"setblock {x + 1} {y} {z + 1} minecraft:observer[facing=east]")
    c.append(f"setblock {x + 2} {y} {z + 1} minecraft:observer[facing=west]")
    return c


def deterministic_part(s, outdir, res):
    s.wait(r"Done \(", 600)
    s.cmd("tick freeze")
    s.ask("tick query", r"(?i)frozen", 30)
    for rule in ("random_tick_speed 0", "spawn_mobs false", "mob_griefing false",
                 "fire_spread_radius_around_player 0", "advance_time false", "advance_weather false",
                 "spawn_wandering_traders false", "spawn_patrols false", "spawn_phantoms false"):
        s.cmd("gamerule " + rule)
    x0, z0, x1, z1 = BOX
    t0 = time.time()
    # Vanilla worldgen is not reproducible at chunk borders (ore/flower overlap order
    # depends on scheduling), so the worldgen compare is informational; the contraption
    # compare below is exact.
    s.cmd(f"forceload add {x0 * 16} {z0 * 16} {x1 * 16 + 15} {z1 * 16 + 15}")
    wait_box_loaded(s)
    res["gen_seconds"] = round(time.time() - t0, 2)
    save(s)
    snapshot_region(os.path.join(outdir, "gen"))
    for c in contraption():
        s.cmd(c)
    g0 = s.gametime()
    s.cmd("tick rate 500")
    s.cmd(f"tick step {STEPS}")
    deadline = time.time() + 120
    while s.gametime() < g0 + STEPS:
        if time.time() > deadline:
            raise TimeoutError("tick step did not complete")
        time.sleep(0.2)
    res["steps_done"] = s.gametime() - g0
    save(s)
    snapshot_region(os.path.join(outdir, "rs"))
    s.cmd("tick rate 20")


def mirror_invalidation_scenarios(s):
    """Block writes the collision mirror must track, made AFTER mobs built the section
    snapshot, then observed by the collision shadow for the rest of the shadow phase:
    a nested same-cell write (TNT primed inside onPlace removes itself) and a write that
    bypasses LevelChunk.setBlockState (OreFeature via BulkSectionAccess turns netherrack,
    a full cube, into soul sand)."""
    tx, ty, tz = 1050, 200, 1050
    s.cmd(f"fill {tx - 4} {ty} {tz - 4} {tx + 4} {ty + 5} {tz + 4} minecraft:air")
    s.cmd(f"fill {tx - 4} {ty - 1} {tz - 4} {tx + 4} {ty - 1} {tz + 4} minecraft:stone")
    for i in range(6):
        s.cmd(f"summon pig {tx - 2.5 + i} {ty} {tz + 0.5 - (i % 3)}")
    nx, ny, nz = 120, 99, 120
    s.cmd(f"execute in minecraft:the_nether run forceload add {nx - 8} {nz - 8} {nx + 8} {nz + 8}")
    deadline = time.time() + 120
    while True:
        mark = len(s.lines)
        s.cmd(f"execute in minecraft:the_nether if loaded {nx} {ny} {nz} run say NETHERREADY")
        try:
            s.wait(r"NETHERREADY", 2, start=mark)
            break
        except TimeoutError:
            if time.time() > deadline:
                raise
    s.cmd(f"execute in minecraft:the_nether run fill {nx - 6} {ny + 1} {nz - 6} {nx + 6} {ny + 6} {nz + 6} minecraft:air")
    s.cmd(f"execute in minecraft:the_nether run fill {nx - 6} {ny} {nz - 6} {nx + 6} {ny} {nz + 6} minecraft:netherrack")
    for i in range(12):
        s.cmd(f"execute in minecraft:the_nether run summon pig {nx - 4.5 + (i % 4) * 3} {ny + 1} {nz - 4.5 + (i // 4) * 3} {{PersistenceRequired:1b}}")
    # Team membership (cramming predicate reads Entity.getTeam per pair): memos are built
    # during the sprints below, then every scoreboard writer path changes them.
    # Entity-ticking status change under memoized isPushable: a pen straddling the border of
    # the forceloaded box (x=1087|1088). Chunk 68 is forceloaded while memos are built, then
    # released below, so its pigs stop being entity-ticking (vanilla: not pushable).
    s.cmd("forceload add 1088 1040")
    deadline = time.time() + 120
    while True:
        mark = len(s.lines)
        s.cmd("execute if loaded 1089 200 1042 run say EDGEREADY")
        try:
            s.wait(r"EDGEREADY", 2, start=mark)
            break
        except TimeoutError:
            if time.time() > deadline:
                raise
    s.cmd("fill 1086 199 1040 1089 204 1043 minecraft:glass hollow")
    s.cmd("fill 1087 200 1041 1088 203 1042 minecraft:air")
    for i in range(8):
        # columns x=1087.6 (chunk 67) and x=1088.4 (chunk 68): boxes overlap across the border
        s.cmd(f"summon pig {1087.6 + (i % 2) * 0.8} 200 {1041.5 + ((i // 2) % 2) * 0.8} {{NoAI:1b,PersistenceRequired:1b}}")
    px, pz = 1040, 1060
    s.cmd(f"fill {px - 2} {ty - 1} {pz - 2} {px + 2} {ty + 4} {pz + 2} minecraft:glass hollow")
    s.cmd(f"fill {px - 1} {ty} {pz - 1} {px + 1} {ty + 3} {pz + 1} minecraft:air")
    for i in range(12):
        s.cmd(f"summon pig {px - 0.6 + (i % 3) * 0.6} {ty + 0.5} {pz - 0.6 + (i // 3) * 0.4} {{PersistenceRequired:1b}}")
    s.cmd("team add rustmi_a")
    s.cmd("team modify rustmi_a collisionRule pushOwnTeam")
    s.cmd("team add rustmi_b")
    s.cmd("team modify rustmi_b collisionRule never")
    s.cmd(f"team join rustmi_a @e[type=pig,x={px},y={ty},z={pz},distance=..3]")
    s.ask("tick sprint 20", r"Sprint completed", 60)
    s.cmd(f"team join rustmi_b @e[type=pig,x={px},y={ty},z={pz},distance=..3,limit=6,sort=nearest]")
    s.ask("tick sprint 10", r"Sprint completed", 60)
    s.cmd("team remove rustmi_a")
    s.cmd("forceload remove 1088 1040")
    s.cmd(f"setblock {tx + 1} {ty} {tz} minecraft:redstone_block")
    s.cmd(f"setblock {tx} {ty} {tz} minecraft:tnt")
    s.cmd("kill @e[type=tnt,distance=..3,x=%d,y=%d,z=%d]" % (tx, ty, tz))
    for i in range(6):
        s.cmd(f"summon pig {tx + 0.2 + i * 0.1} {ty + 2 + i} {tz + 0.3 + i * 0.07}")
    # Ore shape is random: several origins, then mobs dropped onto every platform cell
    # (their snapshot predates the ore) so a stale full cube under soul sand is observed.
    for dx, dz in ((0, 0), (-3, 2), (3, -2), (-2, -3), (2, 3), (-4, -4), (4, 4), (-4, 4), (4, -4)):
        s.cmd(f"execute in minecraft:the_nether run place feature minecraft:ore_soul_sand {nx + dx} {ny} {nz + dz}")
    for gx in range(nx - 6, nx + 7):
        for gz in range(nz - 6, nz + 7):
            s.cmd(f"execute in minecraft:the_nether run summon pig {gx + 0.5} {ny + 3} {gz + 0.5} {{PersistenceRequired:1b}}")


def collider_window_scenarios(s, x, y, z):
    """Entity colliders (boats, a closed shulker) under falling mobs: a false 'no entity
    collider' answer lets the native path drop mobs through them. A giant whose own
    section lies below an adjacent raised pig's vanilla section window: vanilla's
    getEntities never visits it, so a box-intersection superset would push it. Brain mobs
    (NearestLivingEntitySensor) exercise the sensor candidate path under Fabrolia."""
    bx, bz = 1072, 1036
    s.cmd(f"fill {bx - 4} {y} {bz - 4} {bx + 4} {y + 8} {bz + 4} minecraft:air")
    s.cmd(f"fill {bx - 4} {y - 1} {bz - 4} {bx + 4} {y - 1} {bz + 4} minecraft:stone")
    targets = [(bx - 2.5, bz - 2.5, "oak_boat"), (bx + 1.5, bz + 1.5, "birch_boat"),
               (bx + 2.5, bz - 2.5, "oak_chest_boat"), (bx - 2.0, bz + 3.0, "shulker")]
    for tx, tz, kind in targets:
        extra = " {NoAI:1b,Peek:0b}" if kind == "shulker" else ""
        cx, cz = (int(tx // 1) + 0.0, int(tz // 1) + 0.0) if kind == "shulker" else (tx, tz)
        s.cmd(f"summon minecraft:{kind} {cx} {y} {cz}{extra}")
        for i in range(3):
            s.cmd(f"summon pig {tx + 0.15 * i} {y + 3 + i} {tz - 0.1 * i} {{PersistenceRequired:1b}}")
    gx, gy, gz = 1064, 207, 1064
    s.cmd(f"fill {gx - 4} {gy - 1} {gz - 4} {gx + 4} {gy + 13} {gz + 4} minecraft:air")
    s.cmd(f"fill {gx - 4} {gy - 1} {gz - 4} {gx + 4} {gy - 1} {gz + 4} minecraft:stone")
    s.cmd(f"fill {gx + 1} {gy + 4} {gz - 1} {gx + 3} {gy + 4} {gz + 1} minecraft:stone")
    s.cmd(f"summon minecraft:giant {gx + 0.5} {gy} {gz + 0.5} {{NoAI:1b,PersistenceRequired:1b}}")
    for i in range(3):
        s.cmd(f"summon pig {gx + 2.5} {gy + 5} {gz - 0.5 + i * 0.5} {{NoAI:1b,PersistenceRequired:1b}}")
    for i, kind in enumerate(("goat", "goat", "armadillo", "armadillo", "frog", "frog", "camel", "villager")):
        s.cmd(f"summon minecraft:{kind} {x - 10 + 3 * i} {y + 1} {z + 14} {{PersistenceRequired:1b}}")


def rustmi_scenario(s, res):
    """Shadow counters + perf/RAM workload (rustmi configs only)."""
    s.cmd("tick unfreeze")
    m = s.ask("rustmi mathcheck", r"Rustmi.*(СОВПАЛО|РАСХОЖДЕНИЙ: (\d+))", 120)
    res["mathcheck_mismatch"] = int(m.group(2)) if m.group(2) else 0
    s.cmd("rustmi explosion shadow on")
    s.cmd("rustmi collisionshadow on")
    s.cmd("rustmi cramming shadow on")
    mirror_invalidation_scenarios(s)
    collider_window_scenarios(s, OX, 200, OZ + 40)
    x, y, z = OX, 200, OZ + 40
    cmds = [
        f"fill {x - 24} {y - 1} {z - 24} {x + 24} {y + 6} {z + 24} minecraft:air",
        f"fill {x - 24} {y - 1} {z - 24} {x + 24} {y - 1} {z + 24} minecraft:stone",
        f"fill {x - 24} {y} {z - 24} {x - 12} {y} {z + 24} minecraft:farmland",
        f"fill {x - 11} {y} {z - 24} {x - 6} {y} {z + 24} minecraft:dirt_path",
        f"fill {x - 5} {y} {z - 24} {x - 2} {y} {z + 24} minecraft:snow[layers=5]",
        f"fill {x + 14} {y} {z - 24} {x + 16} {y} {z + 24} minecraft:white_wool_slab",
        f"fill {x + 18} {y} {z - 24} {x + 20} {y} {z + 24} minecraft:soul_sand",
        f"fill {x - 1} {y} {z - 1} {x + 1} {y + 3} {z + 1} minecraft:glass hollow",
        f"setblock {x + 8} {y} {z + 8} minecraft:chest",
        f"setblock {x + 8} {y} {z - 8} minecraft:straw_bed[facing=north,part=foot]",
    ]
    # One strip per state-only collision class the mirror classifies (audited declarers),
    # placed strict (no neighbour updates) so survival rules can't remove them, with pigs
    # dropped onto each so collide + supporting-block shadow cover them.
    strips = [
        (3, "honey_block"), (4, "mud"), (5, "lectern[facing=north]"),
        (6, "bell[attachment=floor,facing=north]"), (7, "grindstone[face=floor,facing=north]"),
        (9, "big_dripleaf[tilt=partial,facing=north]"), (10, "zombie_head[rotation=4]"),
        (11, "piglin_head[rotation=0]"), (12, "pale_moss_carpet[bottom=true]"),
        (13, "pitcher_crop[half=lower,age=0]"), (17, "oak_wall_hanging_sign[facing=north]"),
        (21, "sculk_shrieker"), (22, "composter[level=3]"),
    ]
    for dx, block in strips:
        cmds.append(f"fill {x + dx} {y} {z - 24} {x + dx} {y} {z + 24} minecraft:{block} strict")
    cmds.append(f"fill {x + 9} {y - 1} {z - 24} {x + 9} {y - 1} {z + 24} minecraft:moss_block strict")
    cmds.append(f"fill {x + 13} {y - 1} {z - 24} {x + 13} {y - 1} {z + 24} minecraft:farmland strict")
    cmds.append(f"fill {x + 24} {y - 1} {z - 24} {x + 24} {y - 1} {z + 24} minecraft:sand strict")
    for cz in range(z - 24, z + 25, 2):
        cmds.append(f"setblock {x + 24} {y} {cz} minecraft:cactus strict")
    # Climbable cells (true branch of onClimbable) next to the strips: ladders and an open
    # trapdoor above a ladder (trapdoorUsableAsLadder).
    cmds.append(f"fill {x + 23} {y} {z - 24} {x + 23} {y + 1} {z + 24} minecraft:ladder[facing=east] strict")
    cmds.append(f"setblock {x + 23} {y + 2} {z} minecraft:oak_trapdoor[open=true,facing=east] strict")
    # A 1x1 ladder shaft with a NoAI pig standing IN the ladder cell (inBlockState = ladder),
    # so the #climbable branch of onClimbable is exercised, not just neighbouring cells.
    lx, lz = x + 26, z + 4
    cmds.append(f"fill {lx - 1} {y} {lz - 1} {lx + 1} {y + 3} {lz + 1} minecraft:glass strict")
    cmds.append(f"fill {lx} {y} {lz} {lx} {y + 3} {lz} minecraft:ladder[facing=south] strict")
    cmds.append(f"summon pig {lx + 0.5} {y + 1} {lz + 0.5} {{NoAI:1b,PersistenceRequired:1b}}")
    cmds.append(f"summon pig {lx + 0.5} {y + 2.2} {lz + 0.5} {{NoAI:1b,PersistenceRequired:1b}}")
    for dx, _ in strips + [(24, "cactus"), (23, "ladder")]:
        for dz in (-12, 0, 12):
            cmds.append(f"summon pig {x + dx + 0.5} {y + 2.5} {z + dz + 0.3} {{PersistenceRequired:1b}}")
    for c in cmds:
        s.cmd(c)
    for i in range(24):
        s.cmd(f"summon pig {x + 0.5} {y + 1} {z + 0.5}")
    for i, (dx, dz) in enumerate([(-18, -10), (-8, 12), (-3, 3), (15, -15), (19, 10), (6, 6), (-20, 20), (12, -6)]):
        s.cmd(f"summon zombie {x + dx} {y + 3} {z + dz} {{PersistenceRequired:1b}}")
        s.cmd(f"summon cow {x + dz} {y + 6} {z + dx}")
        s.cmd(f"summon sheep {x - dx} {y + 6} {z - dz}")
    for i, (dx, dz, fuse) in enumerate([(8, 8, 40), (-14, 12, 55), (-6, -12, 70), (4, -18, 90)]):
        s.cmd(f"summon tnt {x + dx} {y} {z + dz} {{fuse:{fuse}}}")
    # Piles straddling chunk (x=1008, z=1008), section (y=208) and Fabrolia region
    # (x/z=1024, grid shift 3) boundaries: candidate order spans several sections.
    for (px, pz) in ((1008, 1008), (1024, 1024)):
        s.cmd(f"fill {px - 3} 205 {pz - 3} {px + 2} 205 {pz + 2} minecraft:stone")
        s.cmd(f"fill {px - 2} 206 {pz - 2} {px + 1} 211 {pz + 1} minecraft:glass hollow")
        s.cmd(f"fill {px - 1} 206 {pz - 1} {px} 210 {pz} minecraft:air")
        for i in range(30):
            s.cmd(f"summon pig {px - 0.5 + (i % 2) * 0.6} {207.5 + (i % 3) * 0.4} {pz - 0.5 + (i % 5) * 0.2}")
    # Explosions in natural terrain (mixed resistances, fluids, vegetation).
    for i, (tx, tz) in enumerate([(972, 972), (1000, 1050), (1040, 980), (1066, 1066), (990, 1030),
                                  (1060, 1010), (965, 1080), (1080, 965)]):
        s.cmd(f"execute positioned {tx} 0 {tz} positioned over world_surface run summon tnt ~ ~ ~ {{fuse:{30 + 7 * i}}}")
        s.cmd(f"execute positioned {tx + 3} 0 {tz} positioned over ocean_floor run summon tnt ~ ~ ~ {{fuse:{33 + 7 * i}}}")
    m = s.ask("tick sprint 1500", r"Sprint completed with .* ([0-9.]+) ms per tick", 300)
    res["shadow_mspt"] = float(m.group(1))
    # Coverage guard: every strip (and the ladder shaft) must still stand after the sprint,
    # otherwise the shadow silently compared against air.
    probes = [(x + dx, y, z, block.split("[")[0]) for dx, block in strips]
    probes += [(x + 24, y, z, "cactus"), (x + 23, y, z, "ladder"), (lx, y + 1, lz, "ladder")]
    missing = []
    for px_, py_, pz_, block in probes:
        mark = len(s.lines)
        s.cmd(f"execute if block {px_} {py_} {pz_} minecraft:{block} run say STRIP_OK {block}")
        try:
            s.wait(r"STRIP_OK " + block, 5, start=mark)
        except TimeoutError:
            missing.append(block)
    res["strips_missing"] = missing
    m = s.ask("rustmi collision status",
              r"calls=(\d+) fast=(\d+) bailPartial=(\d+) bailOther=(\d+) \| shadow ok=(\d+) bad=(\d+)")
    res["collision_shadow"] = dict(zip(["calls", "fast", "bailPartial", "bailOther", "ok", "bad"],
                                       map(int, m.groups())))
    m = s.ask("rustmi explosion status",
              r"explosions=(\d+) takeovers=(\d+) javaFallback=(\d+) disqualified=(\d+).*sweepShadow ok=(\d+) orderOnly=(\d+) bad=(\d+) \| seen fast=(\d+) vanilla=(\d+) shadowBad=(\d+)")
    res["explosion_shadow"] = dict(zip(["explosions", "takeovers", "javaFallback", "disqualified", "ok",
                                        "orderOnly", "bad", "seenFast", "seenVanilla", "seenBad"],
                                       map(int, m.groups())))
    m = s.ask("rustmi cramming status",
              r"cramming shadow=\S+ \| calls=(\d+) setMismatch=(\d+) orderMismatch=(\d+) \| sensorCalls=(\d+) sensorSet=(\d+) sensorOrder=(\d+)")
    res["cramming_shadow"] = dict(zip(["calls", "set", "order", "sensorCalls", "sensorSet", "sensorOrder"],
                                      map(int, m.groups())))
    res["cramming_examples"] = s.lines[-1][-600:]
    s.cmd("rustmi cramming shadow off")
    # perf workload with every engine in production mode
    s.cmd("rustmi collisionshadow off")
    s.cmd("rustmi collision on")
    s.cmd("rustmi explosion shadow off")
    s.cmd("rustmi explosion on")
    for (px, pz) in ((962, 1010), (966, 1010), (970, 1010), (962, 1016)):
        s.cmd(f"fill {px - 1} {y} {pz - 1} {px + 1} {y + 3} {pz + 1} minecraft:glass hollow")
        for i in range(75):
            s.cmd(f"summon pig {px + 0.5} {y + 1} {pz + 0.5}")
    for i in range(100):
        s.cmd(f"summon zombie {x - 20 + (i * 7) % 40} {y + 1} {z - 20 + (i * 13) % 40} {{PersistenceRequired:1b}}")
    s.ask("tick sprint 300", r"Sprint completed", 300)
    m = s.ask("tick sprint 3000", r"Sprint completed with .* ([0-9.]+) ms per tick", 600)
    res["perf_mspt"] = float(m.group(1))
    m = s.ask("rustmi collision status", r"calls=(\d+) fast=(\d+) bailPartial=(\d+) bailOther=(\d+)")
    calls, fast = int(m.group(1)), int(m.group(2))
    res["collision_native_pct"] = round(100.0 * fast / calls, 2) if calls else 0.0
    m = s.ask("rustmi stats", r"Sections: (\d+) MiB", 30)
    res["native_sections_mib"] = int(m.group(1))
    m = s.ask("rustmi sections wirecheck",
              r"wirecheck sections=(\d+) containers=(\d+) native=(\d+) \| writeBad=(\d+) sizeBad=(\d+) roundTripBad=(\d+) packBad=(\d+)", 120)
    res["wirecheck"] = dict(zip(["sections", "containers", "native", "writeBad", "sizeBad", "roundTripBad", "packBad"],
                                map(int, m.groups())))


def ram(s, res):
    jcmd = os.path.join(os.path.dirname(JAVA), "jcmd")
    pid = s.p.pid
    subprocess.run([jcmd, str(pid), "GC.run"], capture_output=True, timeout=120)
    out = subprocess.run([jcmd, str(pid), "GC.heap_info"], capture_output=True, text=True, timeout=60).stdout
    m = re.search(r"used (\d+)K", out)
    res["heap_used_mb"] = round(int(m.group(1)) / 1024, 1) if m else -1
    nmt = subprocess.run([jcmd, str(pid), "VM.native_memory", "summary", "scale=KB"],
                         capture_output=True, text=True, timeout=60).stdout
    tot = re.search(r"Total: reserved=\d+KB, committed=(\d+)KB", nmt)
    heap = re.search(r"Java Heap \(reserved=\d+KB, committed=(\d+)KB\)", nmt)
    if tot and heap:
        res["nonheap_committed_mb"] = round((int(tot.group(1)) - int(heap.group(1))) / 1024, 1)
    for cat in ("Class", "Thread", "Code", "GC", "Internal", "Other", "Metaspace", "Arena Chunk"):
        m2 = re.search(r"- +%s \(reserved=\d+KB, committed=(\d+)KB" % re.escape(cat), nmt)
        if m2:
            res.setdefault("nmt_mb", {})[cat] = round(int(m2.group(1)) / 1024, 1)
    with open(f"/proc/{pid}/status") as f:
        m = re.search(r"VmRSS:\s+(\d+) kB", f.read())
    res["rss_mb"] = round(int(m.group(1)) / 1024, 1) if m else -1


def main():
    cfg, outdir = sys.argv[1], sys.argv[2]
    shutil.rmtree(outdir, ignore_errors=True)
    os.makedirs(outdir)
    prepare(cfg)
    res = {"config": cfg}
    s = Server(cfg, outdir, os.environ.get("RUSTMI_EXTRA_JVM", "").split())
    try:
        deterministic_part(s, outdir, res)
        if cfg in ("R", "LR"):
            m = s.ask("rustmi redstone status", r"engine=\S+ \| takeovers=(\d+) scriptedEvals=(\d+).*abort\{evalMismatch=(\d+) mutation=(\d+) leftover=(\d+)\}")
            res["redstone"] = dict(zip(["takeovers", "scriptedEvals", "abortMismatch", "abortMutation", "abortLeftover"],
                                       map(int, m.groups())))
        ram(s, res)
        if cfg in ("R", "LR"):
            rustmi_scenario(s, res)
    except Exception as e:  # recorded, harness counts it as a failure
        res["error"] = f"{type(e).__name__}: {e}"
    finally:
        s.stop()
    text = "\n".join(s.lines)
    res["log_errors"] = [l for l in s.lines if re.search(r"/(ERROR|FATAL)\]", l)
                         or "Exception" in l and "Error loading class" not in l][:50]
    res["exit_code"] = s.p.returncode
    with open(os.path.join(outdir, "result.json"), "w") as f:
        json.dump(res, f, indent=1)
    print(json.dumps({k: v for k, v in res.items() if k != "log_errors"}))
    print("log_errors:", len(res["log_errors"]))


if __name__ == "__main__":
    main()
