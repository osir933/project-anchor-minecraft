# Changelog

What changed in each version of Anchor, newest first. The release workflow publishes the section of the
version it releases as that version's notes.

## 0.1.0-alpha.1 (unreleased)

The first alpha: heat is the first physics in the game. Anchor needs Minecraft 26.3 with NeoForge 26.3 and
Java 25, on both the client and the server.

- **Every block near a player is matter.** Each block is a material such as granite, oak, iron, water or
  air, with a mass and a temperature. Blocks Anchor does not know are described from their name, sound and
  shape, and data packs can describe any block in the `anchor:materials` data map.
- **Heat moves by real physics.** It conducts through blocks, rises through the air and drains into the
  weather: each biome's temperature, cooler higher up. Energy and mass are audited to stay balanced.
- **Heat radiates.** Hot surfaces send heat across air and vacuum onto whatever they face, by the
  Stefan–Boltzmann law, and cold ones take in their surroundings' radiation. Glass and water stop it. Lava
  and magma are strong enough sources to stay hot while they radiate.
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

Known limits of this alpha:

- Vanilla's own rules still run: ice melts near bright light and water freezes in cold biomes as usual.
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
