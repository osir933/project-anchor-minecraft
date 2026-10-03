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
- **Matter melts, freezes and boils.** Ice melts into water, water freezes or boils away and snow melts when
  its matter reaches the melting or boiling point and has taken in or given up the latent heat.
- **Heat sources.** Torches, lanterns, candles, campfires, fire, lit furnaces, magma and lava give off heat.
- **Blocks refine where heat is steep.** Where temperatures change steeply across a block, as in stone beside
  lava, the block splits into smaller cells, down to 25 cm, so heat soaks in from the face first, and the cells
  merge back once they even out. Ice beside lava melts in twelve minutes instead of nineteen. The thermometer
  and the thermal camera read the cell they touch, and `/anchor heat inspect` shows the range of a refined
  block's cells.
- **Saved with the world.** The blocks heat has changed are saved with their chunk and come back exactly.
- **Tools for experiments.** `/anchor heat inspect` shows what the simulation knows about a block,
  `/anchor heat status` how the simulation is doing, and operators can set a block's temperature with
  `/anchor heat set`. A craftable thermometer reads blocks and the air.
- **Thermal camera.** Held in either hand, it shows the temperatures of what you look at as coloured dots
  with a scale, of surfaces or of the air that stands out, such as the plume above a torch. Its scale can be
  locked to compare what you see over time.

Known limits of this alpha:

- Vanilla's own rules still run: ice melts near bright light and water freezes in cold biomes as usual.
- Big changes take time: Anchor's clock follows the day (a day is 24 simulated hours) and a block is a full
  cubic metre. Two layers of snow beside a fire melt in about four and a half minutes of play, a block of ice
  beside lava in about twelve.
- Burning and flowing liquids are not simulated yet, and the sun does not warm anything. Flames and torches
  heat the air around them but do not radiate, and lava passes its heat on as if it were still, so it melts
  ice more slowly than real lava would.
- Land nobody is near is paused, not cooled: it carries on from where it was when a player returns.
