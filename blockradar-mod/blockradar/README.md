# Block Radar

Client-side Fabric mod for Minecraft 26.1.2 (single-player/modded-friendly, doesn't
touch the server). Highlights any blocks you choose within an X and Z range around
you, with a per-block color you set from an in-game menu (hex code or R/G/B/Alpha
sliders).

## Features

- In-game menu (default keybind unbound — set it yourself in *Options > Controls > Key
  Binds > Block Radar*) to:
  - Add/remove any number of "block ID + color" rules.
  - Set `X min`/`X max` and `Z min`/`Z max` (offsets from your current position, can be
    negative), plus a `Y radius` (needed to bound the search vertically — not asked for
    explicitly but required to make a 3D scan finite).
  - Toggle the mod on/off and toggle "see through walls".
  - Set the color per block by typing a hex code (`#RRGGBB`) or by dragging R/G/B/Alpha
    sliders — both stay in sync live, with a color preview swatch.
- Settings persist to `config/blockradar.json`.
- Re-scans the world for matching blocks a few times a second (configurable), not every
  frame, to keep it cheap.

## Project layout

Standard single-source-set Fabric mod (this mod is 100% client-side, so there's no
`src/client`/`src/main` split — everything lives in `src/main/java` and
`"environment": "client"` in `fabric.mod.json` keeps it from being required server-side).

```
build.gradle
gradle.properties
settings.gradle
src/main/resources/fabric.mod.json
src/main/resources/blockradar.mixins.json
src/main/java/com/blockradar/BlockRadar.java          - entrypoint, keybind, tick/scan loop
src/main/java/com/blockradar/config/                  - config model + JSON load/save
src/main/java/com/blockradar/render/BoxRenderer.java   - draws the highlight boxes
src/main/java/com/blockradar/gui/                      - the two menu screens
src/main/java/com/blockradar/mixin/GameRendererMixin.java
```

## Building

1. Install a JDK **25** (26.1 requires it) and use **IntelliJ IDEA 2025.3+** if you want
   mixins to resolve correctly in the IDE.
2. Open this folder as a Gradle project (or `./gradlew build` from a terminal).
3. The built jar lands in `build/libs/`. Drop it, plus a matching **Fabric API** jar for
   26.1.2 and **Fabric Loader 0.18.4+**, into your instance's `mods` folder.

## A note on why this needed research, not just memory

Minecraft 26.1 shipped only a few months before this was written, and it's an unusually
disruptive release for modders: it's the **first unobfuscated Minecraft version ever**,
Fabric dropped Yarn mappings entirely in favor of Mojang's official ones, and the whole
world-rendering pipeline was rewritten around a new extraction/drawing split with direct
GPU buffer + `RenderPass` calls (no more simply grabbing a `VertexConsumerProvider` in a
render callback like older versions). Key bindings were also reworked (`KeyMapping` was
literally renamed to `KeyBinding` by Mojang), and GUI screens moved to an
`extractRenderState(GuiGraphicsExtractor, ...)` pattern instead of the old `render(...)`.

So rather than writing this from pre-2026 training knowledge (which would confidently
produce code for an API that no longer exists), I pulled the **current, version-pinned
Fabric documentation for 26.1.2 specifically** (`docs.fabricmc.net/26.1.2/...`) and based
`BoxRenderer.java` and the screen classes directly on the official examples there. The
config/GUI wiring around that (sliders, hex sync, JSON persistence, the scan loop) is
original code built on top of those confirmed patterns.

Because this mapping/rendering system is genuinely new, a couple of things are the
most likely spots to need a small fix if something doesn't compile first try:

- **`GuiGraphicsExtractor.fill(...)`** — I've assumed it keeps the same signature as the
  old `GuiGraphics#fill`. If it doesn't compile, check
  `docs.fabricmc.net/26.1.2/develop/rendering/gui-graphics` for the exact method name.
- **`Checkbox.builder(...)`** — assumed stable from recent versions; if renamed, swap for
  two `Button`s or a `CycleButton` toggling text between "On"/"Off".
- **Exact Loom/Fabric API patch versions** in `gradle.properties` — I pinned versions I
  found published for 26.1.2 at time of writing, but check
  `https://fabricmc.net/develop/` and `https://modrinth.com/mod/fabric-api` for anything
  newer before building.

If anything doesn't compile, the single best resource is **mcsrc.dev** — Fabric's new
in-browser decompiled-source viewer for the exact game version — search the class name
there to get the real current method signature, or ask in the Fabric Discord.

## Extending it

- The list UI in `ConfigScreen` is a simple fixed list (no scrolling) — fine for a
  handful of block rules; swap in a `ContainerObjectSelectionList` if you want dozens.
- `BoxRenderer` currently renders a filled translucent box per match. If you'd rather
  have a wireframe outline, change the vertex topology in `renderFilledBox` to draw
  line pairs instead of quads, and switch the pipeline snippet accordingly.
