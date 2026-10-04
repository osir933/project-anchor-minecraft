# Anchor

Anchor turns Minecraft into a physical world. Matter, heat, forces and chemistry follow real physical laws, so
tools, machines and industry emerge from physics instead of recipes. It is meant as a science sandbox: build
experiments, inspect what the simulation knows and how sure it is, and watch societies grow on top of it.

**Status: first alpha.** Heat is the first physics in the game; everything else above is still ahead (see
the [roadmap](docs/architecture.md#roadmap) and the [changelog](CHANGELOG.md)). Anchor needs Minecraft 26.3
with NeoForge 26.3 and Java 25, installed on both the game and the server.

## Heat in the game

Every block near a player is matter: a material such as granite, oak, iron or water, with a mass and a
temperature. Heat conducts through it, radiates from hot surfaces across the air onto whatever they face,
rises through the air and drains away into the weather, and blocks change when their matter does:

- Ice melts into water, water freezes into ice or boils away, and snow melts, each when its matter reaches
  the melting or boiling point and has taken in or given up the latent heat. Ice turns into water only once
  it has melted through.
- Torches, lanterns, candles, campfires, fire, lit furnaces, magma and lava give off heat. Burning is not
  simulated yet, so each holds its block at a flame's temperature with a power typical of it. Lava and magma
  never cool, as in Minecraft, so they glow on the walls of their caves for as long as they are there.
- Water and lava stir themselves. Warmed from below or cooled from above they turn over, and beside something
  hotter or colder they flow along it, so they pass on far more heat than they could conduct. Water is densest
  at 4 °C: cooled from above, it turns over until it reaches 4 °C, then keeps its coldest water on top, which
  is why lakes freeze from the surface down.
- Hot surfaces radiate by the Stefan–Boltzmann law, and cold ones take in the radiation of what they face.
  Glass and water stop it, as they do for heat radiation in reality, so a thermal camera cannot see through
  them either.
- The sun warms what it shines on, and the ground cools under the night sky. Sunlight falls straight down, as
  Minecraft's daylight does, onto the first block in its way. Dark blocks take in more of it than pale ones,
  dyed wool, concrete and terracotta by their colour, and water, ice and glass let much of it through. At night
  the ground radiates its heat to a sky colder than the air, the more so in dry air, so clear nights bring
  frost. Rain and storms dim the sun and keep the nights mild. Water evaporates from ponds, wet soil and leaves
  and cools them, the faster in dry air, and dew and frost settle on cold ground. In air at 5 °C a layer of
  snow melts in about ten hours of clear sunshine; at 0 °C it lasts.
- The weather sets the temperature heat escapes to: the biome's temperature, cooler higher up, with air as
  moist as the biome is rainy. Snowy biomes sit below freezing, deserts around 40 °C with dry air, and jungles
  are humid.

Heat is simulated in the land around each player: the player's 16-block section and two more in every
direction. Anchor's clock follows the day: a 20-minute Minecraft day is 24 simulated hours, so each game tick
is 3.6 simulated seconds. Heat moves at its real pace on that clock and a block is a full cubic metre, so big
changes take a while: two layers of snow beside a fire melt in about four minutes of play, and a block of ice
beside lava in about six.

Where temperatures change steeply across a block, as in stone beside lava, Anchor refines the block into eight
smaller cells, and the cells nearest the heat into eight again, down to 25 cm. Heat then soaks in from the face,
as it does in reality, instead of spreading through the whole cubic metre at once. With whole blocks the ice
would take sixteen minutes, and the snow would still be melting after an hour. Once the cells even out they
merge back into one block, and a block is always saved as one. When an experiment cannot wait,
`/anchor heat set` changes a temperature directly.

Temperatures are saved with the world. Anchor stores, with each chunk, the blocks heat has changed, exactly
as the simulation has them; blocks heat never touched need nothing, because they come back from the block
itself. A block that changed while its land was not simulated starts again from the new block.

Three things to know in this alpha. Vanilla's own rules still run alongside Anchor's: ice still melts near
bright light and water still freezes in cold biomes, and Anchor follows the blocks they change. Time stands
still in land nobody is near: a warm room you walk away from is just as warm when you come back. And the sky
reaches only the tops of blocks, while the air keeps its biome's temperature by day and by night.

### Commands

| Command | What it does |
| --- | --- |
| `/anchor heat inspect` | What the simulation knows about the block you are looking at: material, temperature, phase, mass, enthalpy, where the value came from, for a block open to the sky how warm its top is and how much sunlight it takes in, and, for a refined block, how warm its coolest and hottest cells are. Operators can name any block with `/anchor heat inspect <pos>`. |
| `/anchor heat status` | Heat in your dimension: sections simulated and awake, the cost of each step, how many blocks are refined into how many cells, the sunlight on level ground and how many surfaces are open to the sky, how many blocks came back as they were saved, and whether energy and mass balanced at the last audit. |
| `/anchor heat set <pos> <celsius>` | For operators: sets a block's temperature, keeping its matter, to start an experiment. |
| `/anchor probe add` | Leaves a probe where you are looking, which records the temperature there every step. Operators can put one in the middle of any block, and name it, with `/anchor probe add <pos> [<name>]`. |
| `/anchor probe list` | Every probe in your dimension with its latest reading, how fast it is changing and a line of bars of what it recorded. `/anchor probe show <name>` tells more about one. |
| `/anchor probe chart [<names>]` | Turns an empty map from your inventory into a live chart of as many as four probes. |
| `/anchor probe export [<name>]` | Writes recordings into the world's folder as CSV files, for spreadsheets and plotting tools. |
| `/anchor probe rename`, `reset`, `remove` | Renames a probe, makes it start recording afresh, or removes it. Operators can remove them all with `/anchor probe clear`. |
| `/anchor selftest` | Runs the engine's self-check. |

### Thermometer

Craft a thermometer from a glass pane, redstone and a copper ingot stacked in a column, glass on top. Use it
on a block to read the block's temperature where you touch it, or in the air to read the air around your head.
On a refined block it reads the cell you touch, and on the top of a block open to the sky it reads the top
itself, which the sun warms and a clear night chills far faster than the block as a whole. Sneak and use it on a
block to leave a probe where you touch it, and again to take the probe away.

### Thermal camera

Craft a thermal camera from a spyglass on top, a thermometer between two copper ingots in the middle row, and
copper ingots around a redstone dust in the bottom row. Hold it in either hand and it shows the temperatures of
what you look at as coloured dots, twice a second: dark violet for the coldest in view, through red and orange,
to near white for the hottest. Above the hotbar it shows the temperature at your crosshair and the scale, which
follows what is in view the way a real thermal camera's automatic range does. Each dot reads the spot it marks,
so the face of stone beside lava shows hotter than the stone behind it. Use it to switch to the air
view, which shows the air that is warmer or colder than the rest, such as the plume above a torch. Sneak and use
it to lock the scale, so that what you see later compares with what you see now. Like a real thermal camera, it
cannot see through glass or water. Only you see your camera's images.

### Probes and charts

A probe records the temperature at one spot every simulation step, so you can see how fast a block warms, how a
pond follows the day or how long snow lasts in the sun. Leave one by sneaking and using a thermometer on a block,
or with `/anchor probe add`; it measures where you touched, so a probe on a block's top reads the sunlit top. A
thermometer used on a probed block also says how fast the probe finds it warming or cooling. A probe whose block
is not simulated, because nobody is near, records nothing until someone comes back, and the gap shows.

`/anchor probe chart` turns an empty map into a chart of up to four probes, which Anchor keeps drawing as they
record, about once a second. Time runs left to right up to now, and each probe is a coloured line through its
readings over a paler band from the lowest to the highest, so a brief peak still shows on a long chart. Hold the
map to read it, copy it on a cartography table, or hang it in an item frame as a display on the wall of your lab.
Players in creative mode and operators need no map.

A probe keeps all it recorded however long it runs: it holds 512 stretches of time, and when they are full, each
two neighbouring stretches merge into one, keeping the lowest, mean and highest temperature in it. Probes and
their recordings are saved with the world. `/anchor probe export` writes them as CSV files, one row per stretch,
to `anchor/probes/<dimension>` in the world's folder. A dimension holds up to 32 probes.

### Settings

Anchor's settings are kept with each world, under `[heat]` in its config file, and each is described there:
`enabled`, `secondsPerGameTick` (3.6), `gameTicksPerStep` (4), `radius` and `verticalRadius` (2 sections),
`sectionsLoadedPerStep` (8), `calmKelvinPerHour` (1.0), `refinementLevels` (2, for 25 cm cells; 0 turns
refinement off), `maxRefinedCells` (16384 in each dimension), `showPhaseChanges` (true) and `sunAndSky`
(true).

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
`python3 .github/scripts/smoke_test.py` installs a NeoForge server the way players do and checks that the
built jar works on it.

Setting up Minecraft needs access to Mojang's and NeoForged's servers. Without it, build and test only the
engine:

```sh
./gradlew :anchor-core:build -Panchor.coreOnly=true
```

## Releasing

Set `mod_version` in `gradle.properties`, describe the version under its own heading in
[CHANGELOG.md](CHANGELOG.md), and push the tag `v<mod_version>`. The release workflow builds and tests the
mod, tries the jar on a real server, publishes a GitHub release with the jar and those notes, and uploads the
jar to CurseForge when `curseforge_project_id` is set and the repository has a `CURSEFORGE_TOKEN` secret.
Running the workflow by hand checks the CurseForge settings without publishing anything.

## Licence

[MIT](LICENSE)
