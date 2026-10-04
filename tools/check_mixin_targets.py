#!/usr/bin/env python3
"""
Verifies that every method our mixins inject into really exists (static, unique name, right
descriptor) inside the precompiled original classes in legacy/classes.  Fails the build early
instead of crashing Minecraft at launch if the legacy classes are ever swapped for a different build.
"""
import os, struct, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

def methods(path):
    d = open(path, "rb").read()
    p = 8
    n = struct.unpack(">H", d[p:p + 2])[0]; p += 2
    cp = [None] * n
    i = 1
    while i < n:
        t = d[p]; p += 1
        if t == 1:
            l = struct.unpack(">H", d[p:p + 2])[0]; p += 2
            cp[i] = d[p:p + l].decode("utf8", "replace"); p += l
        elif t in (3, 4, 9, 10, 11, 12, 17, 18): p += 4
        elif t in (5, 6): p += 8; i += 1
        elif t in (7, 8, 16, 19, 20): p += 2
        elif t == 15: p += 3
        else: raise SystemExit("bad constant pool tag %d in %s" % (t, path))
        i += 1
    p += 6                                   # access, this, super
    p += 2 + 2 * struct.unpack(">H", d[p:p + 2])[0]          # interfaces
    def skip_attrs(p):
        c = struct.unpack(">H", d[p:p + 2])[0]; p += 2
        for _ in range(c):
            p += 2; ln = struct.unpack(">I", d[p:p + 4])[0]; p += 4 + ln
        return p
    nf = struct.unpack(">H", d[p:p + 2])[0]; p += 2
    for _ in range(nf):                                      # fields
        p += 6; p = skip_attrs(p)
    out = []
    nm = struct.unpack(">H", d[p:p + 2])[0]; p += 2
    for _ in range(nm):                                      # methods
        acc, ni, di = struct.unpack(">HHH", d[p:p + 6]); p += 6
        out.append((cp[ni], cp[di], acc)); p = skip_attrs(p)
    return out

TARGETS = {
    "com/farmbuilder/build/AutoBuildCore": [
        ("ensureItemInHand", "(Lnet/minecraft/class_310;Lnet/minecraft/class_1792;)Z"),
        ("countItemInInventory", "(Lnet/minecraft/class_310;Ljava/lang/String;)I")],
    "com/farmbuilder/order/AutoOrderEngine": [
        ("startOrder", "(Ljava/lang/String;II)V"), ("startSellBuckets", "()V"), ("isBusy", "()Z")],
    "com/farmbuilder/safety/AutoEatEngine": [("tick", "(Lnet/minecraft/class_310;Z)Z")],
    "com/farmbuilder/safety/AutoTotemEngine": [("tick", "(Lnet/minecraft/class_310;Z)V")],
}

bad = 0
for cls, wanted in TARGETS.items():
    ms = methods(os.path.join(ROOT, "legacy/classes", cls + ".class"))
    for name, desc in wanted:
        same = [m for m in ms if m[0] == name]
        good = len(same) == 1 and same[0][1] == desc and bool(same[0][2] & 0x8)
        print("%s %s.%s%s" % ("ok  " if good else "BAD ", cls.rsplit("/", 1)[1], name, desc))
        bad += 0 if good else 1
sys.exit(1 if bad else 0)
