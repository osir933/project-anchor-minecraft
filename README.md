# Anchor

Anchor turns Minecraft into a physical world. Matter, heat, forces and chemistry follow real physical laws, so
tools, machines and industry emerge from physics instead of recipes. It is meant as a science sandbox: build
experiments, inspect what the simulation knows and how sure it is, and watch societies grow on top of it.

**Status: first alpha.** Heat is the first physics in the game, and what players build stands or falls by the
strength of its blocks; everything else above is still ahead (see
the [roadmap](docs/architecture.md#roadmap) and the [changelog](CHANGELOG.md)). Anchor needs Minecraft 26.3
with NeoForge 26.3 and Java 25, installed on both the game and the server. To get started, create a world of the
Anchor Laboratory type: a flat floor in steady 20 °C air, with a thermometer and a thermal camera in hand (see
[the Laboratory](#the-laboratory)), and build a [ready-made experiment](#ready-made-experiments) there.

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
- Hot things glow, in the colour and brightness a black body has at their temperature, worked out from
  Planck's law and how the eye sees colour: dull red from about 500 °C, then brighter and more orange, at full
  brightness from about 1250 °C. Shiny surfaces such as gold give off less light, so they glow dimmer and only
  when hotter. Each face glows spot by spot, so a block that is hotter on one side glows brighter there. Lava,
  magma and other blocks that already give off light look as they always do.
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
bright light and water still freezes in cold biomes, and Anchor follows the blocks they change; a laboratory world
turns them off. Time stands
still in land nobody is near: a warm room you walk away from is just as warm when you come back. And the sky
reaches only the tops of blocks, while the air keeps its biome's temperature by day and by night.

### Commands

| Command | What it does |
| --- | --- |
| `/anchor heat inspect` | What the simulation knows about the block you are looking at: material, temperature, phase, mass, enthalpy, where the value came from, for a block open to the sky how warm its top is and how much sunlight it takes in, for a refined block how warm its coolest and hottest cells are and how close uneven heat comes to cracking it, and for a built block how far heat has stretched it. Operators can name any block with `/anchor heat inspect <pos>`. |
| `/anchor heat status` | Heat in your dimension: sections simulated and awake, the cost of each step, how many blocks are refined into how many cells, the sunlight on level ground and how many surfaces are open to the sky, how many blocks came back as they were saved, whether energy and mass balanced at the last audit, what structures have done and how many built blocks uneven heat has cracked. |
| `/anchor heat set <pos> <celsius>` | For operators: sets a block's temperature, keeping its matter, to start an experiment. |
| `/anchor probe add` | Leaves a probe where you are looking, which records the temperature there every step. Operators can put one in the middle of any block, and name it, with `/anchor probe add <pos> [<name>]`. |
| `/anchor probe list` | Every probe in your dimension with its latest reading, how fast it is changing and a line of bars of what it recorded. `/anchor probe show <name>` tells more about one. |
| `/anchor probe chart [<names>]` | Turns an empty map from your inventory into a live chart of as many as four probes. |
| `/anchor probe export [<name>]` | Writes recordings into the world's folder as CSV files, for spreadsheets and plotting tools. |
| `/anchor probe rename`, `reset`, `remove` | Renames a probe, makes it start recording afresh, or removes it. Operators can remove them all with `/anchor probe clear`. |
| `/anchor time` | How heat is paced in your dimension: paused, or at what speed and how fast the server has kept up, or how far it has gone ahead. |
| `/anchor time pause`, `resume` | For operators: holds every temperature in your dimension while the game runs on, and lets heat run again. |
| `/anchor time step [<count>]` | For operators: takes a step of heat by hand, or as many as asked, paused or not. |
| `/anchor time speed <multiple>` | For operators: runs heat from 0.01 to 1000 times as fast as normal, as far as the server keeps up. |
| `/anchor time advance <time>` | For operators: sends heat ahead by a length of simulated time, such as `90s`, `15m`, `10h` or `2d`, as fast as the server allows. `/anchor time cancel` stops it. |
| `/anchor snapshot list` | The snapshots saved in your dimension: the size of each box, where it was saved from, how many of its blocks hold heat of their own and when it was saved. `/anchor snapshot` alone does the same. |
| `/anchor snapshot save <name> [<from> <to>]` | For operators: saves the blocks from one corner to the other, up to 64 blocks a side, or those within 16 blocks of you, with the heat of every cell in them. |
| `/anchor snapshot restore <name> [<corner>]` | For operators: puts a snapshot's blocks back as they were saved, heat and all, where they were saved or with the box's lowest corner somewhere else. |
| `/anchor snapshot remove <name>` | For operators: deletes a snapshot. |
| `/anchor experiment list` | The ready-made experiments and what each shows. `/anchor experiment` alone does the same. |
| `/anchor experiment build <name>` | For operators: builds a ready-made experiment in front of you and starts it, with probes where its result shows, a chart of them in your inventory and a snapshot to run it again. |
| `/anchor structure inspect` | Whether the block you are looking at is built or natural, which of its joints have cracked, how close uneven heat comes to cracking it through and, for a built block, how loaded its structure is: how many blocks were analysed with it, its most loaded joint and how loaded that would be without heat, what would fall, and how far heat has stretched the block. Operators can name any block with `/anchor structure inspect <pos>`. |
| `/anchor structure mark <from> <to> built\|natural` | For operators: makes a box of up to 32,768 blocks built, so that it stands or falls by its strength, or natural, so that it holds still. |
| `/anchor selftest` | Runs the engine's self-check. |

### Thermometer

Craft a thermometer from a glass pane, redstone and a copper ingot stacked in a column, glass on top. Use it
on a block to read the block's temperature where you touch it, or in the air to read the air around your head.
On a refined block it reads the cell you touch, and on the top of a block open to the sky it reads the top
itself, which the sun warms and a clear night chills far faster than the block as a whole. Sneak and use it on a
block to leave a probe where you touch it, and again to take the probe away. A reading taken while heat is paused
says so.

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

### Time control

Heat normally runs on the clock of the game: a step of 14.4 simulated seconds every four game ticks. Operators
can change that in their dimension for an experiment. `/anchor time pause` holds every temperature while the game
runs on, so you can build, read thermometers and compare probes at leisure, and `/anchor time step` then moves heat
on a step at a time. `/anchor time speed 10` runs heat ten times as fast as normal and `/anchor time speed 0.25`
a quarter as fast. `/anchor time advance 10h` sends heat ten simulated hours ahead as fast as the server allows,
showing everyone in the dimension a bar of how far it has come and telling them when it arrives.

Running faster never makes heat slower than normal. The steps beyond the usual ones are taken only while they fit
into 20 milliseconds of each 50-millisecond game tick (the `stepBudgetMillis` setting), leaving the rest to the
game, so how much faster heat runs depends on how much is going on near players and on the server. `/anchor time`
says how fast it has managed lately. Whether heat is paused, and its speed, are saved with the world; heat on its
way ahead stops there when the world is closed.

The sun and the weather keep the game's own time, so heat that runs faster or goes ahead sees the sun move more
slowly than it would: ten hours sent ahead at noon pass under the noon sun. Vanilla's `/tick sprint` runs the whole
game faster instead, the sun with it.

### Snapshots

A snapshot saves an experiment so you can run it again. `/anchor snapshot save before` keeps the blocks within 16
blocks of you, and `/anchor snapshot save before <from> <to>` those in a box of up to 64 blocks a side: every block
with what it holds, chests and signs included, and the heat of every cell, a refined block's cell for cell. Let the
ice melt and the iron cool, and `/anchor snapshot restore before` puts every block back as it was saved, with
exactly the temperatures it had, so the second run starts where the first did. Give a corner, as in
`/anchor snapshot restore before ~5 ~ ~`, and the box goes there instead, its lowest corner at that spot, for a
second run beside the first. Probes keep recording through a restore, so a chart shows one run after the other.

Saving and restoring need heat to run in all of the box, so stay near it. Things that move, such as dropped items,
animals and players, are not part of a snapshot, and a restore leaves the blocks around the box alone: water that
ran out of it stays where it went until something disturbs it. Snapshots are compressed files in
`anchor/snapshots/<dimension>` in the world's folder; copy one into another world to run the same experiment there.

### The Laboratory

The Laboratory is a world type for experiments. On the Create New World screen, choose Anchor Laboratory as the
world type on the World tab. The game mode then switches to Creative with commands allowed, which time control and
snapshots need; you can change either back before creating the world. On a server, set
`level-type=anchor\:laboratory` in `server.properties` before the world is first created.

Its Overworld is a flat floor of light grey concrete over stone, on which you stand at y = 0, all of it in the
Laboratory biome: air at 20 °C and 50 % humidity, where it never rains and no animals or monsters spawn. Experiments
there run in steady surroundings. The sun lights the lab but gives no heat, and no night sky cools it, so whatever
nothing heats or cools settles at the air's temperature. When the world is first loaded, the time of day is held at
noon and the weather at clear; mobs, phantoms, patrols and wandering traders stop spawning; and random ticks stop,
so crops, grass and copper stay as they are and Minecraft's own melting of ice and snow is off, leaving only
Anchor's physics to change blocks. These are ordinary game rules, which operators can change back with `/gamerule`.
The first time each player joins, they are given a thermometer and a thermal camera and a few lines on where to
begin.

The Nether and the End of a laboratory world are as they always are. A world of the Single Biome type made of the
Laboratory biome counts as a laboratory too, hills and all.

### Ready-made experiments

`/anchor experiment build <name>` builds one of four experiments in front of you, on a bench of smooth stone that
takes the place of the floor there. It sets the temperatures the experiment starts from, leaves probes where its
result shows, gives you a chart of them and saves it all as the snapshot `experiment-<name>`, so
`/anchor snapshot restore experiment-<name>` runs it again from the start. `/anchor experiment list` tells what each
shows. Each was sized with the simulation itself to show its result within minutes in a laboratory's 20 °C air, and
those that take longer say how much to speed heat up.

| Experiment | What it shows | How long it takes |
| --- | --- | --- |
| `cooling` | A block of iron at 1227 °C cools in still air. Its top cools faster than its middle, which must conduct its heat out first, and its glow fades from orange through dull red to nothing. | About five minutes. |
| `conduction` | Rods of copper, iron, stone and brick, three blocks tall, stand on lava. The heat reaches the top of the copper first and then the iron, while the tops of the stone and brick barely warm. | A minute or two at 5× speed. |
| `melting` | Ice and stone at −10 °C warm side by side in glass cups on copper hot plates over lava. Their temperatures rise together until the ice reaches 0 °C; then the ice stays there while it takes in the heat it needs to melt, and the stone warms on. | About a minute at 10× speed. |
| `insulation` | Three blocks of iron at 150 °C: one bare, one wrapped in glass and one in wool. The bare one cools fastest, the one in glass a little slower, and the wool keeps its iron near 150 °C for hours. | A few minutes. |

An experiment needs a clear space in front of you, up to 13 blocks wide, 3 deep and 4 tall. It is not built if
anything but air and plants is in the way, if a chest or anything else with contents is where the bench goes, or if
there is no solid ground under the lava it pours into its bench. It works in any world where heat runs, though the
sun, the weather and the air of other places change its numbers.

### Settings

Anchor's settings are in `config/anchor-synced.toml` in the game's folder, or the server's, under `[heat]` and
`[structures]`, and each is described there. Every world shares them; to give one world settings of its own, copy the
file into the `serverconfig` folder in that world's folder. The settings are: `enabled`, `secondsPerGameTick` (3.6),
`gameTicksPerStep` (4), `stepBudgetMillis` (20), `radius` and `verticalRadius` (2 sections), `sectionsLoadedPerStep`
(8), `calmKelvinPerHour` (1.0), `refinementLevels` (2, for 25 cm cells; 0 turns refinement off), `maxRefinedCells`
(16384 in each dimension), `showPhaseChanges` (true) and `sunAndSky` (true) under `[heat]`, and `enabled` (true),
`maxBlocks` (4096), `thermalShock` (true) and `thermalExpansion` (true) under `[structures]`.

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
    },
    "minecraft:smooth_stone": { "material": "anchor:granite", "fractured": "minecraft:cobblestone" }
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
| `fractured` | Optional: the block to show once uneven heat has cracked a built block through, such as `minecraft:air` for a block that shatters. Left out, stone becomes cobblestone, a block with a `cracked_` form becomes that, glass shatters and other blocks stay as they are. |

## Structures in the game

What players build stands or falls by the strength of its blocks. Every block a player places is **built**; the
world as it was found, overhangs and floating islands included, is **natural** and holds still. Whenever something
changes, Anchor checks the structures of the built blocks around it: how the weight of each block passes through the
patches where blocks touch down to the natural ground, and whether each joint can take its load, from its
material's measured strength, stiffness and friction (see [Structure](docs/architecture.md#structure)).

- A block with nothing to hold it up falls, as sand does. Blocks that cannot fall whole, such as chests, doors and
  beds, break where they stand.
- A joint loaded beyond what it can take cracks, with the sound of its block breaking and a puff of dust, and from
  then on holds only by pressing and friction. A stone overhang breaks at 12 blocks long, an iron one at 32.
- Blocks touch where their shapes do: a slab touches the block beside it over half a face, a fence holds up what
  stands on its post, and a block that rests on nothing but a torch or a flower falls.
- Heat softens matter as fire does: iron keeps less than half its strength at 600 °C, so heated that far an iron
  overhang breaks at 22 blocks long.
- Uneven heat cracks brittle blocks from within, as hot water cracks a cold glass: the side of a block that warms
  first expands, and the rest of the block holds it back. Stone put beside lava cracks into cobblestone in about half
  a minute of play and stone bricks into cracked stone bricks, glass beside lava shatters in about two minutes, and a
  campfire cracks a concrete wall in about a minute and stone in about four. Torches and lanterns crack nothing. A
  cracked block keeps its heat and its place, holds only by pressing and friction, so what it held up may fall, and
  does not crack again. The world as it was found does not crack, and the `thermalShock` setting turns cracking off.
- Heat stretches what players build, and a structure that holds a block in place takes the strain: a stone span
  built between two walls is pulled by the cold and pressed by heat, and a block heated on one side bends. A joint
  strained past its strength cracks, so a span cooled far enough cracks loose and falls, and a stone bridge over lava
  cracks at its ends as it bows. Blocks are free of strain at the climate where they stand, so the sun's warmth
  cracks nothing; metal yields a little instead of cracking, and a cracked joint rocks and slips by a hairline and
  lets the strain go. The `thermalExpansion` setting turns it off.
- Trees and crops grow natural, and so do the stone, cobblestone and obsidian that lava makes where it meets water.
  The blocks of a ready-made experiment are natural too.

`/anchor structure inspect` tells whether a block is built and how loaded its structure is, and operators can make
a box of blocks built or natural with `/anchor structure mark`, for instance to let a village that came with the
world stand or fall by its strength. Structures are checked where heat runs, near players. Small ones are analysed
at once; a big building is analysed in the background and falls a moment later, at the same moment on every machine.
Up to 4096 built blocks are analysed together, or what the `maxBlocks` setting says; in a larger building, the part
around a change is analysed with the rest held still.

What this alpha leaves out: steel that yields gives way at once instead of bending, cracked blocks do not wedge
into arches, slender columns do not buckle, expansion that the blocks around hold back does not yet stress a
structure, and only the weight of blocks loads a structure, not the players, animals or items on it.

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
