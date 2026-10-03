#!/usr/bin/env python3
"""
Self-test for the schematic converter (no Minecraft needed).

  python3 tools/run_schem_tests.py

Builds .litematic / .schem (v2, v3) / .nbt files with known contents, converts them with
com.farmbuilder.ext.schem.SchematicConverter, and checks every block against what was written.
Needs a JDK (javac) on PATH.
"""
import gzip, io, json, os, random, shutil, struct, subprocess, sys, tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# ---------------------------------------------------------------- NBT writer -------------
def _s(v):
    b = v.encode("utf-8")
    return struct.pack(">H", len(b)) + b

def tag(kind, v):
    """kind: byte short int long str bytes ints longs list(compound) compound"""
    if kind == "byte":   return 1, struct.pack(">b", v)
    if kind == "short":  return 2, struct.pack(">h", v)
    if kind == "int":    return 3, struct.pack(">i", v)
    if kind == "long":   return 4, struct.pack(">q", v)
    if kind == "str":    return 8, _s(v)
    if kind == "bytes":  return 7, struct.pack(">i", len(v)) + bytes(v)
    if kind == "longs":  return 12, struct.pack(">i", len(v)) + b"".join(struct.pack(">q", x) for x in v)
    if kind == "compound":
        return 10, b"".join(bytes([t]) + _s(n) + p for n, (t, p) in v.items()) + b"\x00"
    if kind == "list":   # v = (elem_kind, [values])
        ek, items = v
        out = b""
        et = 0
        for it in items:
            et, p = tag(ek, it)
            out += p
        if not items:
            et = 0
        return 9, bytes([et]) + struct.pack(">i", len(items)) + out
    raise ValueError(kind)

def nbt_file(root, gz=True):
    t, p = tag("compound", root)
    raw = bytes([t]) + _s("") + p
    return gzip.compress(raw) if gz else raw

def pal_entry(name, props=None):
    d = {"Name": tag("str", name)}
    if props:
        d["Properties"] = tag("compound", {k: tag("str", v) for k, v in props.items()})
    return d

# ---------------------------------------------------------------- bit packing ------------
def pack_litematic(values, bits):
    total_bits = len(values) * bits
    longs = [0] * ((total_bits + 63) // 64)
    for i, v in enumerate(values):
        start = i * bits
        a, off = start >> 6, start & 63
        longs[a] |= (v << off) & 0xFFFFFFFFFFFFFFFF
        if off + bits > 64:
            longs[a + 1] |= v >> (64 - off)
    return [x - (1 << 64) if x >= (1 << 63) else x for x in longs]

def varint(v):
    out = bytearray()
    while True:
        b = v & 0x7F
        v >>= 7
        if v:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)

# ---------------------------------------------------------------- cases ------------------
AIR = ("minecraft:air", {})

def key(x, y, z, bid, props):
    return (x, y, z, bid, tuple(sorted(props.items())))

def normalise(blocks):
    mn = [min(b[i] for b in blocks) for i in range(3)]
    return {key(b[0] - mn[0], b[1] - mn[1], b[2] - mn[2], b[3], b[4]) for b in blocks}

def case_litematic(rng):
    """Two regions; one with a negative size; one with a 20-entry palette (5 bits => straddles longs)."""
    blocks, regions = [], {}
    # Region A: 5x4x3 at (10,64,10), 5-entry palette (3 bits)
    palA = [("minecraft:air", {}), ("minecraft:stone", {}),
            ("minecraft:oak_stairs", {"facing": "north", "half": "bottom", "shape": "straight", "waterlogged": "false"}),
            ("minecraft:water", {"level": "0"}), ("minecraft:lava", {"level": "0"})]
    sx, sy, sz, px, py, pz = 5, 4, 3, 10, 64, 10
    vals = [rng.randrange(len(palA)) for _ in range(sx * sy * sz)]
    for i, v in enumerate(vals):
        if v:
            y, r = divmod(i, sx * sz); z, x = divmod(r, sx)
            blocks.append((px + x, py + y, pz + z, palA[v][0], palA[v][1]))
    regions["A"] = tag("compound", {
        "Position": tag("compound", {k: tag("int", n) for k, n in zip("xyz", (px, py, pz))}),
        "Size": tag("compound", {k: tag("int", n) for k, n in zip("xyz", (sx, sy, sz))}),
        "BlockStatePalette": tag("list", ("compound", [pal_entry(n, p) for n, p in palA])),
        "BlockStates": tag("longs", pack_litematic(vals, 3)),
    })
    # Region B: negative size (-3,2,-2) anchored at (20,70,12), 20 palette entries (5 bits)
    palB = [("minecraft:air", {})] + [("minecraft:test_block_%d" % i, {"n": str(i)}) for i in range(1, 20)]
    sx, sy, sz, px, py, pz = -3, 2, -2, 20, 70, 12
    ax, ay, az = abs(sx), abs(sy), abs(sz)
    ox = px + sx + 1 if sx < 0 else px   # only axes with a NEGATIVE size extend backwards from Position
    oy = py + sy + 1 if sy < 0 else py
    oz = pz + sz + 1 if sz < 0 else pz
    vals = [rng.randrange(len(palB)) for _ in range(ax * ay * az)]
    for i, v in enumerate(vals):
        if v:
            y, r = divmod(i, ax * az); z, x = divmod(r, ax)
            blocks.append((ox + x, oy + y, oz + z, palB[v][0], palB[v][1]))
    regions["B"] = tag("compound", {
        "Position": tag("compound", {k: tag("int", n) for k, n in zip("xyz", (px, py, pz))}),
        "Size": tag("compound", {k: tag("int", n) for k, n in zip("xyz", (sx, sy, sz))}),
        "BlockStatePalette": tag("list", ("compound", [pal_entry(n, p) for n, p in palB])),
        "BlockStates": tag("longs", pack_litematic(vals, 5)),
    })
    root = {"Version": tag("int", 6), "MinecraftDataVersion": tag("int", 4671),
            "Regions": tag("compound", regions)}
    return nbt_file(root), normalise(blocks)

def sponge_blocks(rng, w, h, l, pal):
    vals = [rng.randrange(len(pal)) for _ in range(w * h * l)]
    blocks = []
    for i, v in enumerate(vals):
        if pal[v][0] != "minecraft:air":
            y, r = divmod(i, w * l); z, x = divmod(r, w)
            blocks.append((x + 3, y + 5, z + 7, pal[v][0], pal[v][1]))   # offset => tests trimming
    return vals, blocks

def sponge_key(name, props):
    return name + ("[" + ",".join("%s=%s" % kv for kv in props.items()) + "]" if props else "")

def case_sponge(rng, v3):
    """200-entry palette so many var-ints take two bytes."""
    pal = [("minecraft:air", {}), ("minecraft:stone", {}),
           ("minecraft:oak_stairs", {"facing": "east", "half": "top"})]
    pal += [("minecraft:block_%d" % i, {}) for i in range(3, 200)]
    w, h, l = 6, 4, 5
    # shift all by (3,5,7) via trimming test: put air border of 3/5/7 by building bigger volume
    W, H, L = w + 3, h + 5, l + 7
    vals_inner = [rng.randrange(len(pal)) for _ in range(w * h * l)]
    vals = []
    blocks = []
    for y in range(H):
        for z in range(L):
            for x in range(W):
                inside = x >= 3 and y >= 5 and z >= 7
                v = vals_inner[(y - 5) * w * l + (z - 7) * w + (x - 3)] if inside else 0
                vals.append(v)
                if v and pal[v][0] != "minecraft:air":
                    blocks.append((x, y, z, pal[v][0], pal[v][1]))
    data = b"".join(varint(v) for v in vals)
    palette = {sponge_key(n, p): tag("int", i) for i, (n, p) in enumerate(pal)}
    if v3:
        root = {"Schematic": tag("compound", {
            "Version": tag("int", 3), "DataVersion": tag("int", 4671),
            "Width": tag("short", W), "Height": tag("short", H), "Length": tag("short", L),
            "Blocks": tag("compound", {"Palette": tag("compound", palette), "Data": tag("bytes", data)})})}
    else:
        root = {"Version": tag("int", 2), "DataVersion": tag("int", 4671),
                "Width": tag("short", W), "Height": tag("short", H), "Length": tag("short", L),
                "PaletteMax": tag("int", len(pal)), "Palette": tag("compound", palette),
                "BlockData": tag("bytes", data)}
    return nbt_file(root), normalise(blocks)

def case_structure(rng):
    pal = [("minecraft:stone", {}), ("minecraft:air", {}), ("minecraft:repeater", {"delay": "2", "facing": "south", "locked": "false", "powered": "false"})]
    blocks, entries = [], []
    for x in range(3):
        for y in range(3):
            for z in range(3):
                s = rng.randrange(3)
                entries.append({"pos": tag("list", ("int", [x, y, z])), "state": tag("int", s)})
                if pal[s][0] != "minecraft:air":
                    blocks.append((x, y, z, pal[s][0], pal[s][1]))
    root = {"size": tag("list", ("int", [3, 3, 3])), "DataVersion": tag("int", 4671),
            "palette": tag("list", ("compound", [pal_entry(n, p) for n, p in pal])),
            "blocks": tag("list", ("compound", entries))}
    return nbt_file(root, gz=False), normalise(blocks)      # raw (uncompressed) on purpose

def case_all_air():
    root = {"size": tag("list", ("int", [2, 2, 2])),
            "palette": tag("list", ("compound", [pal_entry("minecraft:air")])),
            "blocks": tag("list", ("compound", [{"pos": tag("list", ("int", [0, 0, 0])), "state": tag("int", 0)}]))}
    return nbt_file(root)

# ---------------------------------------------------------------- runner -----------------
def main():
    javac = shutil.which("javac")
    if not javac:   # fall back to the compiler module of a plain `java`
        compile_cmd = ["java", "-m", "jdk.compiler/com.sun.tools.javac.Main"]
    else:
        compile_cmd = [javac]
    tmp = tempfile.mkdtemp(prefix="schemtest_")
    classes = os.path.join(tmp, "classes"); os.makedirs(classes)
    srcs = [os.path.join(ROOT, "src/main/java/com/farmbuilder/ext/schem", f)
            for f in ("NbtReader.java", "SchematicConverter.java")] + [os.path.join(ROOT, "tools/SchemTool.java")]
    subprocess.run(compile_cmd + ["-d", classes] + srcs, check=True)

    rng = random.Random(1234)
    cases = {
        "lit.litematic": case_litematic(rng),
        "v2.schem": case_sponge(rng, False),
        "v3.schem": case_sponge(rng, True),
        "struct.nbt": case_structure(rng),
    }
    failures = 0
    for fname, (data, expected) in cases.items():
        src = os.path.join(tmp, fname); open(src, "wb").write(data)
        outdir = os.path.join(tmp, "out_" + fname.replace(".", "_"))
        p = subprocess.run(["java", "-cp", classes, "SchemTool", src, outdir, "t"], capture_output=True, text=True)
        if p.returncode != 0:
            print("FAIL %-14s converter crashed: %s" % (fname, p.stderr.strip() or p.stdout.strip())); failures += 1; continue
        bp = json.load(open(os.path.join(outdir, "t.json")))
        got = {key(b["rel"]["x"], b["rel"]["y"], b["rel"]["z"], b["block"], b["state"]) for b in bp["blocks"]}
        sz = bp["size"]
        ok = got == expected and len(bp["blocks"]) == len(expected)
        print("%s %-14s %5d blocks, size %dx%dx%d%s" % ("ok  " if ok else "FAIL", fname, len(got), sz["x"], sz["y"], sz["z"],
              "" if ok else "  (missing %d, extra %d)" % (len(expected - got), len(got - expected))))
        failures += 0 if ok else 1

    # error handling
    for fname, data, needle in (("air.nbt", case_all_air(), "no solid blocks"),
                                ("junk.schem", b"this is not nbt", "")):
        src = os.path.join(tmp, fname); open(src, "wb").write(data)
        p = subprocess.run(["java", "-cp", classes, "SchemTool", src, os.path.join(tmp, "o"), "e"], capture_output=True, text=True)
        ok = p.returncode != 0 and needle in (p.stderr + p.stdout)
        print("%s %-14s rejected cleanly" % ("ok  " if ok else "FAIL", fname)); failures += 0 if ok else 1

    # the project's own default blueprints must still be valid input for the planner format
    for f in ("32.json", "64.json"):
        d = json.load(open(os.path.join(ROOT, "src/main/resources/farms", f)))
        assert d["version"] == "2.0" and all(set(b) == {"rel", "block", "state"} for b in d["blocks"][:100])
    print("ok   default blueprints use the format the converter writes")

    shutil.rmtree(tmp, ignore_errors=True)
    if failures:
        print("\n%d FAILED" % failures); sys.exit(1)
    print("\nALL PASSED")

if __name__ == "__main__":
    main()
