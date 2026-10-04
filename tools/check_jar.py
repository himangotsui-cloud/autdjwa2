#!/usr/bin/env python3
"""
Fails if the built jar references a com.farmbuilder class that is not inside it (the cause of a
NoClassDefFoundError crash at startup), or if an entrypoint / mixin class from the metadata is missing.

  python3 tools/check_jar.py build/dist/FarmBuilder-1.0.0.jar
"""
import glob, json, struct, sys, zipfile

def class_refs(data):
    """(this_class, set of referenced class names) from a .class file."""
    p = 8
    n = struct.unpack(">H", data[p:p + 2])[0]; p += 2
    cp = [None] * n; i = 1
    while i < n:
        t = data[p]; p += 1
        if t == 1:
            l = struct.unpack(">H", data[p:p + 2])[0]; p += 2
            cp[i] = ("u", data[p:p + l].decode("utf8", "replace")); p += l
        elif t in (7,):
            cp[i] = ("c", struct.unpack(">H", data[p:p + 2])[0]); p += 2
        elif t in (8, 16, 19, 20): p += 2
        elif t in (3, 4, 9, 10, 11, 12, 17, 18): p += 4
        elif t in (5, 6): p += 8; i += 1
        elif t == 15: p += 3
        else: raise ValueError("bad tag %d" % t)
        i += 1
    name = lambda k: cp[cp[k][1]][1]
    this = struct.unpack(">H", data[p + 2:p + 4])[0]
    refs = {name(k) for k in range(1, n) if cp[k] and cp[k][0] == "c"}
    return name(this), refs

def main(jar_path):
    z = zipfile.ZipFile(jar_path)
    classes = {}
    for nme in z.namelist():
        if nme.startswith("com/farmbuilder/") and nme.endswith(".class"):
            this, refs = class_refs(z.read(nme)); classes[this] = refs
    bad = 0
    missing = {}
    for this, refs in classes.items():
        for r in refs:
            if r.startswith("com/farmbuilder/") and r not in classes:
                missing.setdefault(r, set()).add(this.rsplit("/", 1)[-1])
    for r in sorted(missing):
        print("MISSING  %s   (needed by %s)" % (r, ", ".join(sorted(missing[r]))[:70])); bad += 1
    fm = json.loads(z.read("fabric.mod.json"))
    for group in fm.get("entrypoints", {}).values():
        for e in group:
            if e.replace(".", "/") not in classes:
                print("MISSING entrypoint class", e); bad += 1
    for cfg in fm.get("mixins", []):
        mx = json.loads(z.read(cfg))
        for side in ("mixins", "client", "server"):
            for m in mx.get(side, []):
                if (mx["package"] + "." + m).replace(".", "/") not in classes:
                    print("MISSING mixin class", m); bad += 1
    print("%d classes checked, %d problem(s)" % (len(classes), bad))
    return 1 if bad else 0

if __name__ == "__main__":
    paths = [p for a in sys.argv[1:] for p in glob.glob(a)]
    if not paths:
        sys.exit("usage: check_jar.py <jar>")
    sys.exit(main(paths[0]))
