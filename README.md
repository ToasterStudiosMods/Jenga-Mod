# Jenga Mod — Fabric 1.21.1

Physics Jenga for Minecraft using **Sable** as the physics engine.
Each Jenga piece is a 1×3×9 stripped-log group assembled into its own Sable
sub-level, so every piece falls, tips, and collides independently.

---

## Quick start

```bash
# Windows
setup.bat
gradlew.bat genSources
gradlew.bat build

# macOS / Linux
chmod +x setup.sh gradlew && ./setup.sh
./gradlew genSources && ./gradlew build
```

Output jar: `build/libs/jengamod-1.0.0.jar`

---

## Requirements

| Dependency | Version | Where |
|---|---|---|
| Java JDK | **21** (LTS) | https://adoptium.net |
| Fabric Loader | ≥ 0.16 | https://fabricmc.net |
| Fabric API | 0.116+ for 1.21.1 | Modrinth / CurseForge |
| **Sable** | `1.2.2` for 1.21.1 Fabric | https://modrinth.com/mod/sable |

Sable must be in your `mods/` folder at runtime. The build pulls Sable from
`https://maven.ryanhcode.dev/releases` automatically — no local jar drop needed.

---

## Commands

| Command | Description |
|---|---|
| `/stack <N>` | Spawn an N-layer Jenga tower (1–999). Async — 1 layer per server tick. |
| `/stack cancel` | Abort a build that's in progress. |
| `/stack status` | Check whether a build is running for you. |

The tower's bottom-north-west corner spawns **one block below your feet**.

### Grabbing a piece

With an **empty main hand**, right-click any Jenga piece to grab it. While grabbed:

- Scroll the hotbar wheel to rotate the piece in 90° steps (yaw).
- Right-click again, or sneak, to release.

Internally this dispatches `/sable teleport @v ^ ^ ^<dist> <yaw> 0` every
2 server ticks, where `<dist>` is the player-to-piece distance captured at
grab time and `<yaw>` is the accumulated scroll rotation.

### Gamerule

```
/gamerule jengaMaxLayers        ← query current value (default: 999)
/gamerule jengaMaxLayers 50     ← cap all /stack calls on this world to 50
```

`/stack` silently clamps to `min(requested, jengaMaxLayers, 999)` and tells
you when it does.

---

## Tower geometry

```
Layer N+1 (odd)  — pieces run along Z
  [=========]   x=0–2,  z=0–8
  [=========]   x=3–5,  z=0–8
  [=========]   x=6–8,  z=0–8

Layer N (even)  — pieces run along X
  [==][==][==]  z=0–2,  x=0–8
  [==][==][==]  z=3–5,  x=0–8
  [==][==][==]  z=6–8,  x=0–8
```

- **Piece:** 1 tall × 3 wide × 9 long
- **Layer:** 3 pieces → 9×9 footprint
- **Log grain:** axis aligned to piece length

---

## VS Code setup

### 1. Install Java 21 JDK

```
# Windows
winget install EclipseAdoptium.Temurin.21.JDK

# macOS
brew install --cask temurin@21
```

Or download from https://adoptium.net

### 2. Install VS Code extensions

Open VS Code → Extensions (Ctrl+Shift+X) → install:
- **Extension Pack for Java** (`vscjava.vscode-java-pack`)
- **Gradle for Java** (`vscjava.vscode-gradle`)

### 3. Get gradle-wrapper.jar (one-time)

The wrapper JAR is a binary not included in the zip. Run the setup script:

```bash
# Windows:
setup.bat

# macOS / Linux:
chmod +x setup.sh && ./setup.sh
```

### 4. Open and generate sources

```bash
code .                    # open project
./gradlew genSources      # downloads MC 1.21.1, remaps with Yarn (first run ~5 min)
```

After `genSources` finishes, VS Code's Java extension will resolve all imports.

### 5. Build

```bash
./gradlew build
# jar → build/libs/jengamod-1.0.0.jar
```

---

## Project layout

```
jenga-mod/
├── setup.sh / setup.bat              ← run first to get gradle-wrapper.jar
├── build.gradle                      ← Fabric + Sable deps
├── gradle.properties                 ← versions (MC 1.21.1, Sable 1.2.2)
├── src/main/java/com/ToasterStudios/jenga/
│   ├── JengaMod.java                 ← entry point; gamerule + tick events
│   ├── JengaGameRules.java           ← jengaMaxLayers gamerule (default 999)
│   ├── JengaBuildQueue.java          ← async queue; 3 pieces per tick
│   ├── JengaDragHandler.java         ← right-click grab + scroll-yaw drag
│   ├── builder/JengaBuilder.java     ← creates Deque<Runnable> of assembly tasks
│   ├── command/StackCommand.java     ← /stack <N> | cancel | status
│   └── mixin/ScrollMixin.java        ← intercepts hotbar scroll for rotation
└── src/main/resources/fabric.mod.json
```

---

## Sable API reference

| Class | Package | Role |
|---|---|---|
| `SubLevelAssemblyHelper` | `dev.ryanhcode.sable.api.sublevel` | `assembleBlocks(level, anchor, blocks, bounds)` — turns a set of world blocks into one physics sub-level |
| `ServerSubLevel` | `dev.ryanhcode.sable.sublevel` | Server-side handle to a sub-level (returned by `assembleBlocks`) |
| `AABBi` | `org.joml.primitives` | Integer AABB used as the assembly bounds |

### Sub-level selectors

Sable extends Brigadier with sub-level target selectors usable in `/sable` commands:

| Selector | Meaning |
|---|---|
| `@e` | All sub-levels |
| `@i` | Sub-level the executor is inside |
| `@l` | Latest sub-level |
| `@n` | Nearest sub-level |
| `@r` | Random sub-level |
| `@t` | Sub-level the executor is tracking |
| `@v` | Sub-level the executor is viewing |

This mod uses `@v` for the drag handler — whichever sub-level the player is
currently looking at receives the teleport.

### Assembly flow per piece

1. `world.setBlockState()` — place 27 stripped-log blocks in world space.
2. `SubLevelAssemblyHelper.assembleBlocks(world, anchor, positions, bounds)` —
   migrates those blocks into a freshly allocated sub-level. Sable handles the
   coordinate transform; the world cells are cleared automatically.

Because each piece is assembled before the next is placed, pieces never share
a contiguous block region and cannot merge during assembly's flood-fill.

---

## Troubleshooting

| Problem | Fix |
|---|---|
| `Could not resolve dev.ryanhcode.sable:sable-fabric-1.21.1` | Run `./gradlew --refresh-dependencies`. Check internet access to `https://maven.ryanhcode.dev` |
| `cannot find symbol: SubLevelAssemblyHelper` | Verify `modApi` (not `modImplementation`) in `build.gradle`. Confirm `sable_version` in `gradle.properties` matches a published artifact. |
| Pieces merge into one sub-level | Reduce `PIECES_PER_TICK` in `JengaBuildQueue.java` to 1 to add a tick gap between each piece. |
| `assembleBlocks` throws | Sable shipworld isn't ready, or the chosen anchor is off-grid. Run `/stack` a few seconds after world load. |
| Server hangs on `/stack 999` | Building 999 layers × 3 pieces = 2997 ticks ≈ 2.5 minutes. Reduce with `/gamerule jengaMaxLayers`. |
| Right-click does nothing on a piece | Make sure your main hand is empty. The grab handler ignores right-click while holding any item to avoid hijacking normal interactions. |

---

## Reporting bugs & requests

Everything lives in this one repo. Bugs, crash logs, and feature requests go to the
[Issues tab](https://github.com/ToasterStudiosMods/Jenga-Mod/issues).

When reporting a bug, include:
- Minecraft version and Fabric loader version
- Jenga Mod version
- Other mods installed (especially Valkyrien Skies / Sable and their versions)
- What you were doing, what you expected, and what actually happened
- The crash log if it crashed: paste it to https://mclo.gs and link it, don't dump the whole thing into the issue

"It crashed" with no log isn't actionable.

## License

ToasterStudios Mod License v1 (TSMLv1). See [LICENSE.md](LICENSE.md) for the full terms.

Short version: free to bundle in modpacks and to fork privately, but you may not
re-upload or redistribute the mod (source or compiled) without permission. The source
is public under this license. Official download: https://modrinth.com/mod/jenga-mod

To request permission for anything the license doesn't allow, email
toasterstudiosmods@gmail.com.
