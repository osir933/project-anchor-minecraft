# Changelog

What changed in each version of Anchor, newest first. The release workflow publishes the section of the
version it releases as that version's notes.

## 0.1.0-alpha.1 (unreleased)

The first alpha: heat is the first physics in the game, and what players build stands or falls by the strength of
its blocks. Anchor needs Minecraft 26.3 with NeoForge 26.3 and Java 25, on both the client and the server.

- **Every block near a player is matter.** Each block is a material such as granite, oak, iron, water or
  air, with a mass and a temperature. Blocks Anchor does not know are described from their name, sound and
  shape, and data packs can describe any block in the `anchor:materials` data map.
- **Heat moves by real physics.** It conducts through blocks, rises through the air and drains into the
  weather: each biome's temperature, cooler higher up. Energy and mass are audited to stay balanced.
- **Heat radiates.** Hot surfaces send heat across air and vacuum onto whatever they face, by the
  Stefan–Boltzmann law, and cold ones take in their surroundings' radiation. Glass and water stop it. Lava
  and magma are strong enough sources to stay hot while they radiate.
- **Hot things glow.** Blocks hot enough glow in the colour and brightness of a black body at their
  temperature, from Planck's law and how the eye sees colour: dull red from about 500 °C, brightening through
  orange as they heat. Shiny surfaces such as gold glow dimmer. Each face glows spot by spot, so a block that is
  hotter on one side glows brighter there. Lava and other blocks that already give off light look as before.
- **Liquids stir themselves.** Water and lava carry heat by moving, from their measured viscosity and thermal
  expansion: warmed from below or cooled from above they turn over, and beside something hotter or colder they
  flow along it. Water is densest at 4 °C, so cooled from above it keeps its coldest water on top and freezes
  from the surface down.
- **The sun and the night sky.** The sun warms what it shines on, and the ground cools under a clear night sky
  that is colder than the air. Dark blocks take in more sunlight than pale ones, dyed wool, concrete and
  terracotta by their colour, and water, ice and glass let light through. Rain and storms dim the sun and keep
  the nights mild, and so does humid air, which each biome has as its rainfall says. Water evaporates from
  ponds, wet soil and leaves, the faster in dry air, and dew and frost settle on cold ground. In air at 5 °C a
  layer of snow melts in about ten hours of clear sunshine. The thermometer and `/anchor heat inspect` read the
  sunlit top of a block, and the `sunAndSky` setting turns the sun and sky off.
- **Matter melts, freezes and boils.** Ice melts into water, water freezes or boils away and snow melts when
  its matter reaches the melting or boiling point and has taken in or given up the latent heat.
- **Heat sources.** Torches, lanterns, candles, campfires, fire, lit furnaces, magma and lava give off heat.
- **Blocks refine where heat is steep.** Where temperatures change steeply across a block, as in stone beside
  lava, the block splits into smaller cells, down to 25 cm, so heat soaks in from the face first, and the cells
  merge back once they even out. Ice beside lava melts in six minutes instead of sixteen. The thermometer
  and the thermal camera read the cell they touch, and `/anchor heat inspect` shows the range of a refined
  block's cells.
- **Saved with the world.** The blocks heat has changed are saved with their chunk and come back exactly.
- **Tools for experiments.** `/anchor heat inspect` shows what the simulation knows about a block,
  `/anchor heat status` how the simulation is doing, and operators can set a block's temperature with
  `/anchor heat set`. A craftable thermometer reads blocks and the air.
- **Thermal camera.** Held in either hand, it shows the temperatures of what you look at as coloured dots
  with a scale, of surfaces or of the air that stands out, such as the plume above a torch. Its scale can be
  locked to compare what you see over time.
- **Probes and charts.** Sneak and use a thermometer on a block, or run `/anchor probe add`, to leave a probe
  that records the temperature there every step. `/anchor probe list` shows each probe's latest reading, how fast
  it is changing and a line of what it recorded. `/anchor probe chart` turns an empty map into a live chart of up
  to four probes that can hang in an item frame, and `/anchor probe export` writes recordings as CSV files. A
  probe keeps its whole recording in fixed memory, keeping the lowest and highest temperature of every stretch so
  brief peaks survive, and probes are saved with the world.
- **Time control.** Operators can pause heat in their dimension while the game runs on and take steps by hand with
  `/anchor time pause` and `/anchor time step`, run heat from 0.01 to 1000 times as fast as normal with
  `/anchor time speed`, or send it ahead by a stretch of simulated time such as `10h` with `/anchor time advance`,
  which shows a bar of how far it has come. Faster heat takes extra steps only while they fit into a budget of
  each game tick, 20 ms unless the `stepBudgetMillis` setting says otherwise, and `/anchor time` says how fast
  heat has managed. The pause and the speed are saved with the world, and a thermometer says when heat is paused.
- **Snapshots.** Operators can save a box of up to 64 blocks a side with `/anchor snapshot save`: its blocks, what
  they hold and the heat of every cell, refined blocks cell for cell. `/anchor snapshot restore` puts it back
  exactly as saved, to run an experiment again, or with the box moved, to run it again beside the first.
  Snapshots are compressed files in the world's folder, which can be copied into another world.
- **The Laboratory.** A world type for experiments, Anchor Laboratory on the Create New World screen: a flat floor
  of light grey concrete in air that stays at 20 °C and 50 % humidity, where it never rains, nothing spawns, random
  ticks are off and the time stands at noon, and where heat follows no sun or night sky, so experiments run in
  steady surroundings. Choosing it switches the new world to Creative with commands allowed, and each player is
  given a thermometer and a thermal camera the first time they join.
- **Ready-made experiments.** Operators can build one of four experiments in front of them with
  `/anchor experiment build`, each sized with the simulation to show its result within minutes: glowing iron
  cooling, a race of heat up rods of copper, iron, stone and brick, ice that stays at 0 °C while it melts beside
  stone that warms on, and iron cooling bare, in glass and in wool. Each comes with probes, a chart of them and a
  snapshot to run it again, and `/anchor experiment list` tells what each shows.
- **Structures stand or fall.** Every block a player places is built and stands or falls by the strength of its
  blocks, while the world as it was found holds still. Anchor works out how each block's weight passes through the
  patches where blocks touch, from each material's measured strength, stiffness and friction, softened by heat as
  in fire. A joint loaded beyond what it can take cracks, with the sound of its block breaking and a puff of dust,
  and blocks left with nothing to hold them up fall: a stone overhang breaks at 12 blocks long, an iron one at 32,
  or 22 at 600 °C. Blocks touch where their shapes do, so slabs, stairs and fences carry what their shapes can.
  Trees, crops and the stone lava makes where it meets water grow natural. `/anchor structure inspect` tells
  whether a block is built and how loaded its structure is, operators can mark a box of blocks built or natural
  with `/anchor structure mark`, and the `[structures]` settings switch it off or bound how many blocks are
  analysed together. Big buildings are analysed in the background and fall at the same moment on every machine.
- **Uneven heat cracks blocks.** Built blocks of brittle matter crack through when the side that warms or cools first
  strains against the rest, as a cold glass cracks under hot water: from the temperatures of a refined block's cells,
  Anchor works out the stress at which the rest of the block holds each part and checks it against the material's
  measured strength. Stone put beside lava cracks into cobblestone in about half a minute of play and stone bricks
  into cracked stone bricks, glass beside lava shatters in about two minutes, and a campfire cracks a concrete wall
  in about a minute and stone in about four, while torches and lanterns crack nothing. A cracked block keeps its heat,
  holds only by pressing and friction and does not crack again, and the world as it was found never cracks.
  `/anchor heat inspect` says how close a block comes to cracking, data packs can name what a block cracks into, and
  the `thermalShock` setting turns it off.

Known limits of this alpha:

- Vanilla's own rules still run: ice melts near bright light and water freezes in cold biomes as usual, except in
  a laboratory world.
- Big changes take time: Anchor's clock follows the day (a day is 24 simulated hours) and a block is a full
  cubic metre. Two layers of snow beside a fire melt in about four minutes of play, a block of ice beside lava
  in about six.
- Burning is not simulated yet, and liquids stir in place but do not spread from block to block by Anchor's
  physics. Flames and torches heat the air around them but do not radiate.
- The sun shines straight down onto the tops of blocks, so their sides stay in shade, and every surface sees
  the whole sky, even at the bottom of a pit. The air keeps its biome's temperature by day and by night, clear
  weather is a cloudless sky, stained glass lets light through as clear glass does, and water that evaporates
  stays in its block. In land where nothing else is happening, light that sinks into water or through glass
  warms the top block instead.
- Land nobody is near is paused, not cooled: it carries on from where it was when a player returns.
- The sun and the weather keep the game's own time, so heat that runs faster or is sent ahead sees the sun move
  more slowly than it would.
- Snapshots keep blocks and heat, not things that move such as items and animals, and restoring a box leaves the
  blocks around it alone, so water that ran out of the box stays where it went.
- The glow is drawn as an eye used to the dark sees it, so in bright daylight it shows more than a real glow
  would, and glowing blocks do not light up what is around them.
- Structures carry only the weight of their blocks, not the players, animals or items on them. Steel that yields
  gives way at once instead of bending, cracked blocks do not wedge into arches, slender columns do not buckle and
  expansion that the blocks around hold back does not yet stress a structure.
- Blocks crack from heat only where they are refined into smaller cells, where neighbouring temperatures differ by
  50 K or more, and a 25 cm cell blurs the steep fall in temperature at a quenched face, so hot glass doused with
  water does not crack.
