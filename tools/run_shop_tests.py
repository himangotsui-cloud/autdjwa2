#!/usr/bin/env python3
"""
Compiles the pure-Java shop brain and runs it against simulated /shop servers (category menus, paged
menus, quantity+confirm screens, menus that close after each buy, no-money and inventory-full cases).

  python3 tools/run_shop_tests.py          (needs a JDK on PATH)
"""
import os, shutil, subprocess, sys, tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PURE = ["Money", "ShopSlot", "ShopScreen", "ShopAction", "ShopSettings", "ItemHints", "ShopCatalog", "ShopBrain"]

def main():
    compile_cmd = [shutil.which("javac")] if shutil.which("javac") else ["java", "-m", "jdk.compiler/com.sun.tools.javac.Main"]
    out = tempfile.mkdtemp(prefix="shoptest_")
    srcs = [os.path.join(ROOT, "src/main/java/com/farmbuilder/ext/shop", n + ".java") for n in PURE]
    srcs.append(os.path.join(ROOT, "tools/ShopSimTest.java"))
    subprocess.run(compile_cmd + ["-d", out] + srcs, check=True)
    r = subprocess.run(["java", "-cp", out, "ShopSimTest"])
    shutil.rmtree(out, ignore_errors=True)
    sys.exit(r.returncode)

if __name__ == "__main__":
    main()
