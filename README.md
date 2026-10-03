# Anchor

Anchor turns Minecraft into a physical world. Matter, heat, forces and chemistry follow real physical laws, so
tools, machines and industry emerge from physics instead of recipes. It is meant as a science sandbox: build
experiments, inspect what the simulation knows and how sure it is, and watch societies grow on top of it.

**Status: first alpha.** Heat is the first physics in the game; everything else above is still ahead (see
the [roadmap](docs/architecture.md#roadmap)).

## Heat in the game

Every block near a player is matter: a material such as granite, oak, iron or water, with a mass and a
temperature. Heat conducts through it, rises through the air and drains away into the weather, and blocks
change when their matter does:

- Ice melts into water, water freezes into ice or boils away, and snow melts, each when its matter reaches
  the melting or boiling point and has taken in or given up the latent heat. Ice turns into water only once
  it has melted through.
- Torches, lanterns, candles, campfires, fire, lit furnaces, magma and lava give off heat. Burning is not
  simulated yet, so each holds its block at a flame's temperature with a power typical of it.
- The weather sets the temperature heat escapes to: the biome's temperature, cooler higher up. Snowy biomes
  sit below freezing and deserts around 40 °C.

Heat is simulated in the land around each player: the player's 16-block section and two more in every
direction. Anchor's clock follows the day: a 20-minute Minecraft day is 24 simulated hours, so each game tick
is 3.6 simulated seconds. Heat moves at its real pace on that clock and a block is a full cubic metre, so big
changes take a while. A snow layer beside a fire melts in about five minutes of play; a block of ice beside
lava takes about fifty. That second one is too slow: at block scale, heat crosses each block as if it had to
travel the full metre, which undersells how fast a hot block heats its neighbour at first. Refining blocks
where temperatures change steeply will fix it. Until then, `/anchor heat set` changes a temperature directly
when an experiment cannot wait.

Two things to know in this alpha. Vanilla's own rules still run alongside Anchor's: ice still melts near
bright light and water still freezes in cold biomes, and Anchor follows the blocks they change. Temperatures
are not saved yet, so land that unloads starts again at the temperature of its surroundings.

### Commands

| Command | What it does |
| --- | --- |
| `/anchor heat inspect` | What the simulation knows about the block you are looking at: material, temperature, phase, mass, enthalpy and where the value came from. Operators can name any block with `/anchor heat inspect <pos>`. |
| `/anchor heat status` | Heat in your dimension: sections simulated and awake, the cost of each step, and whether energy and mass balanced at the last audit. |
| `/anchor heat set <pos> <celsius>` | For operators: sets a block's temperature, keeping its matter, to start an experiment. |
| `/anchor selftest` | Runs the engine's self-check. |

### Thermometer

Craft a thermometer from a glass pane, redstone and a copper ingot stacked in a column, glass on top. Use it
on a block to read the block's temperature, or in the air to read the air around your head.

### Settings

Anchor's settings are kept with each world, under `[heat]` in its config file, and each is described there:
`enabled`, `secondsPerGameTick` (3.6), `gameTicksPerStep` (4), `radius` and `verticalRadius` (2 sections),
`sectionsLoadedPerStep` (8), `calmKelvinPerHour` (1.0) and `showPhaseChanges` (true).

### Describing blocks in a data pack

Anchor knows water, ice, snow, lava, magma and the common heat sources itself. Every other block, modded ones
included, is guessed from its name (`oak_planks` is hardwood, `iron_block` is iron) or its sound, and how much
of its space it fills from its collision shape. A data pack can describe any block in the `anchor:materials`
data map, at `data/anchor/data_maps/block/materials.json`:

```json
{
  "values": {
    "minecraft:torch": { "material": "anchor:air", "source": { "temperature": 1300, "power": 1500 } },
    "minecraft:water": {
      "material": "anchor:water",
      "phase": "liquid",
      "becomes": { "solid": "minecraft:ice", "gas": "minecraft:air" }
    }
  }
}
```

| Field | Meaning |
| --- | --- |
| `material` | The Anchor material: `air`, `water`, `snow`, `powder_snow`, `granite`, `basalt`, `slate`, `tuff`, `limestone`, `marble`, `quartzite`, `sandstone`, `netherrack`, `obsidian`, `sand`, `gravel`, `soil`, `clay`, `brick`, `concrete`, `glass`, `hardwood`, `softwood`, `foliage`, `wool`, `coal`, `diamond`, `iron`, `copper`, `aluminium` or `gold`, each prefixed with `anchor:`. |
| `fill` | Optional: the fraction of the block's space the material fills, from 0 to 1. Left out, it follows from the block's shape, snow layers or fluid level. |
| `phase` | Optional: `solid`, `liquid` or `gas`, the phase the block shows its material in. |
| `temperature` | Optional: the temperature the block starts at, in kelvin, instead of its surroundings'. |
| `source` | Optional: a heat source that holds the block at `temperature` (kelvin) with up to `power` (watts). Blocks with a `lit` property only heat while lit. |
| `becomes` | Optional: the block to show once the material has melted, frozen or boiled into another phase. |

## Design principles

- **One physical world.** Everything is matter with a state: material, mass, enthalpy, owner. Temperature and
  phase follow from enthalpy, so melting, freezing and boiling need no special rules.
- **Multiscale.** A block can be refined into smaller cells, down to about a millimetre, where the physics needs
  it. Refinement can change what happens; coarsening only merges cells that are uniform, so it never undoes a
  result.
- **Conservation is audited.** Mass, energy and every chemical element are accounted for, with compensated
  sums so rounding is never mistaken for a gain or loss.
- **Deterministic.** The same world and inputs give the same result bit for bit on every machine. Budgets are
  measured in simulated work, never wall-clock time.
- **Transparent.** Every value carries its provenance and every material property its source, so you can tell a
  measurement from an assumption.

## Layout

| Module | What it is |
| --- | --- |
| `anchor-core` | The simulation engine in plain Java 21: units, matter and materials, the world and its refinement, conservation, scheduling. No Minecraft code. |
| `anchor-neoforge` | The NeoForge mod for Minecraft 26.3 (Java 25). It shows the core's world in game and turns player actions into physical inputs. |

## Building

You need JDK 25. Then:

```sh
./gradlew build
```

The mod jar lands in `anchor-neoforge/build/libs/`. Run the game with `./gradlew :anchor-neoforge:runClient`,
and the in-game tests on a real server with `./gradlew :anchor-neoforge:runGameTestServer`.

Setting up Minecraft needs access to Mojang's and NeoForged's servers. Without it, build and test only the
engine:

```sh
./gradlew :anchor-core:build -Panchor.coreOnly=true
```

## Licence

[MIT](LICENSE)
