# FarmBuilder Client

Fabric client mod for Minecraft **1.21.11** (Java 21, Fabric Loader ≥ 0.19, Fabric API).

* **Survival** – exactly the original behaviour (Baritone pathing, `/order`, auto eat/totem, lava safety …).
* **Singleplayer + Creative** – blocks/buckets are pulled from the creative menu automatically; the builder never
  runs out of materials and skips `/order`, `/sell`, auto-eat and auto-totem. Servers are never affected.
* **Schematics** – load `.litematic` (Litematica), `.schem` (Sponge v1-v3 / WorldEdit) and `.nbt` (vanilla structure).

## Using schematics

| How | What happens |
|---|---|
| **Drag the file onto the game window** | converted and loaded straight away |
| `/farmschem list` | lists files in `.minecraft/schematics` (Litematica's folder) and `.minecraft/farmschem` |
| `/farmschem load <file>` | convert **and** load into the builder |
| `/farmschem import <file>` | convert only (then `/farmbuild load <name>`) |
| `/farmschem creative [on\|off]` | toggle creative-menu supply (saved in `config/farmbuilder-ext.properties`) |

Then `/farmbuild start` as usual. Converted blueprints are saved in `.minecraft/farms/<name>.json`.
Empty air around the build is trimmed; limit is 1,000,000 blocks per schematic.
Old MCEdit `.schematic` (numeric IDs) is not supported – re-save it as `.schem` or `.litematic`.

## Shop mode (survival / SMP)

Menu → **Order / Sell** tab: the old *Auto Sell Empty Buckets (/sell)* toggle is now **Shop** and the *Sell Buckets*
button is now **Scan /shop**. (The `/sell` routine is removed; set `legacySell=true` in `config/farmbuilder-ext.properties` to bring it back.)

With **Shop ON**, whenever the builder runs out of something it needs (blocks, redstone, farm items, food, ...)
it no longer sends `/order`. Instead it:

1. asks `/bal` for your balance,
2. opens `/shop` and walks the menus (the most likely category first - redstone items → *Redstone*, seeds → *Farming*, otherwise *Blocks*),
   turning pages and trying sub-menus, never pressing anything named sell/close/auction/etc.,
3. checks the price against your balance - **if you can't afford it, nothing is bought**,
4. buys until your inventory holds what the build needs (shift-click stacks when a whole stack is missing),
5. closes the menu and the builder carries on. If the item isn't sold or the server refuses, it waits 120 s before retrying that item.

Everything it learns is saved in `config/farmbuilder-shop-catalog.tsv`, so the next purchase goes straight to the right page.

| Command | |
|---|---|
| `/farmshop on` / `off` | same as the Shop toggle (also turns *Auto Order Materials* on) |
| `/farmshop scan` | walk the whole shop and remember every item, price and location |
| `/farmshop buy <item> [count]` | test a purchase by hand, e.g. `/farmshop buy obsidian 64` |
| `/farmshop stop` · `status` · `catalog` | |

Every menu the bot sees is written to `logs/farmshop-scan.txt`. Servers lay their shops out differently; if yours
isn't handled, run `/farmshop scan` and send that file.
Settings (`shopCommand`, `balanceCommand`, `shopMaxSpend`, `shopRetryCooldownSec`, ...) are in `config/farmbuilder-ext.properties`.
Prices must be written in the item's lore (e.g. `Price: $12`), which is how almost every shop plugin shows them.

Self-test with simulated shops: `python3 tools/run_shop_tests.py`.

## Building

GitHub Actions builds it on every push: **Actions → latest run → Artifacts → FarmBuilder** (the jar).
Push a tag like `v1.0.1` to get a GitHub Release with the jar attached.

Locally: install JDK 21 and Gradle 9.x, then `gradle build` → `build/dist/FarmBuilder-<version>.jar`.
(Optionally run `gradle wrapper` once and commit the wrapper so contributors can use `./gradlew`.)

Self-tests (no Minecraft needed): `python3 tools/run_schem_tests.py` and `python3 tools/check_mixin_targets.py`.

## Layout

| Path | |
|---|---|
| `legacy/classes/` | the original FarmBuilder, precompiled (no source available). Merged into the jar untouched. |
| `src/main/java/com/farmbuilder/ext/` | new code: creative supply, `/farmschem`, drag-and-drop |
| `src/main/java/com/farmbuilder/ext/schem/` | schematic → blueprint converter (pure Java, unit-tested) |
| `src/main/java/com/farmbuilder/ext/shop/` | `/shop` auto-buy: decision logic (`ShopBrain`, unit-tested) + in-game glue (`ShopEngine`) |
| `src/main/java/com/farmbuilder/mixin/` | hooks into the original classes (creative supply, order → shop redirect) |
| `src/main/resources/` | `fabric.mod.json`, mixin config, default blueprints `farms/32.json` `farms/64.json`, Baritone 1.15.0 jar-in-jar |

Baritone is LGPL-3.0; keep its notice and the unmodified jar when you distribute.
The license/auth classes (`com.farmbuilder.auth.*`) are still the "Free Edition" stubs from the upload; nothing calls them.
