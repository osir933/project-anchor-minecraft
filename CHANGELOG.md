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
- **Matter melts, freezes and boils.** Ice melts into water, water freezes or boils away and snow melts when
  its matter reaches the melting or boiling point and has taken in or given up the latent heat.
- **Heat sources.** Torches, lanterns, candles, campfires, fire, lit furnaces, magma and lava give off heat.
- **Saved with the world.** The blocks heat has changed are saved with their chunk and come back exactly.
- **Tools for experiments.** `/anchor heat inspect` shows what the simulation knows about a block,
  `/anchor heat status` how the simulation is doing, and operators can set a block's temperature with
  `/anchor heat set`. A craftable thermometer reads blocks and the air.

Known limits of this alpha:

- Vanilla's own rules still run: ice melts near bright light and water freezes in cold biomes as usual.
- Big changes take time: Anchor's clock follows the day (a day is 24 simulated hours) and a block is a full
  cubic metre. A snow layer beside a fire melts in about five minutes of play, a block of ice beside lava in
  about fifty.
- Burning, radiation and flowing liquids are not simulated yet.
- Land nobody is near is paused, not cooled: it carries on from where it was when a player returns.
