# Architecture

Anchor is split into a simulation engine that knows nothing about Minecraft and a thin mod that connects the
two. This page describes the engine as it stands and the rules every part of it follows.

## Modules

- **`anchor-core`** is plain Java 21 with no dependencies. It compiles with `-Xlint:all -Werror` and strict
  doclint, and its test suite runs anywhere.
- **`anchor-neoforge`** is the NeoForge mod for Minecraft 26.3 on Java 25. It ships the core's classes inside
  its own jar. Its job is to show the core's world in game and to turn player actions into physical inputs;
  it holds no physical rules of its own.

## Matter

A **material** (`matter.Material`) is a description, not a game item: a chemical composition plus property
curves for each phase. Compositions are mass fractions of **species** (water, silica, iron, cellulose…), and
species have parsed chemical **formulas**, so the engine always knows how much of each element a piece of
matter contains. Every property curve carries the **source** it came from and a data-quality grade.

Temperature is never stored. Each material has an **enthalpy curve** (`matter.EnthalpyCurve`): specific
enthalpy as a function of temperature, zero at 25 °C, with latent heat at every phase transition. A cell
stores its total enthalpy in joules; its temperature, phase and melt fraction follow from enthalpy divided by
mass. Heat flow therefore conserves energy exactly, and melting, freezing and boiling need no special rules.
Outside a material's measured range the state is still computed but flagged as extrapolated.

## World

The world (`world.PhysicalWorld`) is made of **sections** of 16×16×16 blocks that line up with Minecraft
chunk sections. Each block is a cell with a **state** (`world.CellState`): material, mass, enthalpy, the
physical object it belongs to, and **provenance**, which says whether the value was placed, filled in from
the ambient default, simulated, reconstructed during refinement, or merged during coarsening. Section fields
are stored as a single value until a block differs, so untouched terrain costs almost nothing.

Space without a section is filled with the world's ambient material, such as air at 20 °C or vacuum.

### Refinement and coarsening

Any block can be **refined** into an octree of smaller cells, halving the edge length at each level down to
level 10, about a millimetre. Level 4 is a Minecraft pixel. Refinement splits mass and enthalpy by eight,
which is exact in binary floating point; the new cells assume the parent was uniform, and their provenance
says so. Refinement can change outcomes: once detail exists, physics acts on it.

**Coarsening** merges cells back, and it may never undo an outcome. A `CoarseningRule` only allows a merge
when every cell has the same material and owner, the same phase, and temperatures, melt fractions and
densities that agree within tight limits. A crack, a melt pocket or a material boundary shows up as a
difference and keeps the cells apart. Merging sums pairwise down the tree, so a block that was refined and
then left alone merges back to its exact original state.

The only way back in time is an explicit **snapshot** restore (`world.WorldSnapshot`), recorded in the event
log as a rewind.

### Conservation

The **conservation ledger** (`world.ConservationLedger`) tracks mass, enthalpy and the mass of every chemical
element. Edits from outside the simulation (a player placing a block, a section loading) are declared by the
world itself. Physics models write cells without declaring anything, so an **audit** compares the world's
totals with the baseline plus declared exchanges and catches any model that creates or destroys something.
Totals use compensated (Neumaier) summation, and the tolerance scales with the magnitudes involved, so
rounding is never mistaken for a physical gain or loss.

## Time

**Physics models** (`model.PhysicsModel`) advance the world. Each declares its domain, its assumptions, and an
estimate of the work a step will take. The **scheduler** (`model.Scheduler`) runs models each tick within a
budget of work units. A model that does not fit is deferred and catches up later with a longer step; a model
deferred too long runs on credit so nothing starves. After the models run, the world is audited. Models can
report where they are being used outside their assumptions; those reports go to the event log.

## Heat

Heat moves by **conduction** (`physics.thermal.ConductionModel`): Fourier's law between touching cells,
solved with explicit finite volumes. Every face moves the same heat out of one cell and into the other, so
energy is conserved to rounding, and phase changes happen by themselves as enthalpy crosses a material's
latent heat. Each cell takes substeps short enough to be stable for it alone, the step halved as often as it
needs: a hot plume above a flame may take hundreds of substeps while the rock around it takes one. Until the fluid model exists, two correlations stand in for moving
air: surfaces in gas exchange heat with a natural-convection coefficient of 10 W/(m²·K), and warm gas below
cooler gas mixes with a coefficient that grows with the square root of the temperature difference, so heat
rises.

Two models connect the simulated region to the rest of the world, and both declare the energy they exchange:

- **Heat sources** (`physics.thermal.HeatSourceModel`) stand for things the simulation does not model yet,
  such as a flame. Each heats its block towards a temperature with at most a set power, and never cools it.
- **The atmosphere** (`physics.thermal.AtmosphereModel`) relaxes gas cells towards their section's weather
  temperature with a time constant, exactly for any step length. Without it the simulated region would be a
  closed box in which heat piles up.

Heat costs what is happening, not what is loaded. A section whose matter and touching neighbours all sit at
one temperature is skipped (`physics.thermal.Isotherms` caches each section's temperature range by version,
overall and per face). On top of that, `physics.thermal.ThermalActivity` keeps only sections where something
changes awake: an edit or a new source wakes a section, each step simulates the awake sections and their
neighbours, a neighbour that starts changing faster than the calm rate wakes in turn, and a section that has
changed slower than one kelvin per hour for a while falls asleep. Change is measured net over a step, so a
room that a torch heats exactly as fast as it loses heat counts as calm and sleeps in that steady state.
Sleeping sections are paused, not cooled.

## Hosting

The engine runs inside a game through `host.HostedWorld`, which knows nothing about Minecraft. The game names
its blocks by integer ids of its own and describes each id once as a `host.BlockAppearance`: a material, the
fraction of the block it fills, the phase the game shows, a temperature of its own (lava's) and a heat source
for processes the engine does not model yet, such as burning. It also names the block to show instead once
the shown phase has gone completely, so ice turns into water only when it has melted through.

The game imports the sections it wants simulated, each with the temperature of its surroundings, and
reports every block that changes. A change that leaves the same matter in a block, such as water the engine
froze now shown as ice, keeps the block's physical state; anything else replaces it, declared like any edit.
Each step the hosted world runs heat where something is happening and returns the blocks the game should
now show differently. `host.ImportPlanner` picks the sections around the players, nearest first and a few
per tick, and lets them go a margin further out.

To save a section, the game asks for its **snapshot** (`host.SectionSnapshot`): the blocks whose material,
mass, enthalpy or owner differ from what importing the section afresh would give them, with masses and
enthalpies exact and materials named by id. Everything else comes back from the game's blocks, so a section
where nothing happened needs no snapshot, and a placed block, which starts at its surroundings' temperature,
adds nothing. Importing a section with its snapshot puts each saved block back where its matter still fits
the game's block there; a block that changed while the section was not simulated starts afresh.

## In Minecraft

`anchor-neoforge` connects a hosted world to each Minecraft dimension and holds no physical rules of its own.

- **Blocks.** `BlockMapper` describes each block state the first time the simulation meets its id. The
  `anchor:materials` data map comes first, so data packs can describe any block. Built-in rules cover the
  blocks whose heat or phase matters: water, ice, snow, lava, magma, fire, torches, lanterns, candles,
  campfires and lit furnaces. Everything else is guessed from its name (`MaterialGuess`) or its sound, with
  its fill from its collision shape. A block filling less than a fifth of its space counts as the air or
  water around it, so a torch is a heat source in air. Reloading tags or data packs describes every block
  again.
- **Dimensions.** `LevelHeat` runs one hosted world per dimension, started when the dimension first ticks.
  Block changes arrive through NeoForge's neighbour notifications and are taken in, in sorted order, at the
  start of the next tick. Every few game ticks it imports the sections players have come near, lets go of
  those they have left, compares one imported section with the level to catch any change nobody reported,
  and steps the simulation. Unloading a chunk saves its sections into it and lets go of them. Frozen time
  (`/tick freeze`) pauses heat with everything else.
- **Phase changes.** A step returns the blocks whose matter now shows a different phase. `LevelHeat` checks
  that block and matter still agree with the step, then places the replacement the appearance names. Ice
  holds the same matter as water, so the block keeps its exact state: water frozen at −5 °C becomes ice at
  −5 °C. Steam leaves in a puff of cloud, and the air that takes its place starts at the steam's
  temperature.
- **Weather.** A section's surroundings are the base temperature of the biome at its centre. Minecraft's
  snow line (0.15) maps to 0 °C at 23 °C per unit, it cools by 0.05 units per 40 blocks above y = 80 as
  vanilla does, and the result is held between −30 and 45 °C (`Climate`).
- **Time.** Each game tick is 3.6 simulated seconds, so a Minecraft day lasts 24 simulated hours, and the
  simulation steps every four game ticks. Both are settings.
- **Saving.** `ChunkHeat` holds the snapshots of a chunk's sections as a NeoForge data attachment, so they
  are written and read with the chunk: a short palette per section, then each saved block's position and
  palette index packed in an int array, and masses and enthalpies as the raw bits of their doubles in long
  arrays. A section is written into its chunk when it is let go, when its chunk unloads (before the chunk
  is saved), when the level saves, when the server stops, and every minute in between, and only if it
  changed. Land nobody is near is paused: brought in again, it carries on from its saved state without
  catching up on the time that passed.
- **Failure.** An error stops heat in that dimension, logs it and shows it in `/anchor heat status`; the game
  carries on.

The adapter's plain-Java parts have unit tests. Everything that needs Minecraft is covered by game tests
(`AnchorGameTests`) that run on a real server in CI: packed ice warmed past 0 °C becomes water, water chilled
below it becomes ice, water heated past boiling leaves air, a block placed and heated in the same tick takes
the temperature, a torch warms the air above it, a section written into its chunk and brought in again comes
back exactly, and the save format keeps every number. The game tests load the mod from the build
directories, so CI also installs a NeoForge server the way players do, starts it with the released jar and
checks that the mod loads, its self-test passes, heat runs, the server stops cleanly and nothing is logged as an
error (`.github/scripts/smoke_test.py`).

## Requests

Detail is spent where someone asks for it. A **simulation request** (`request.SimulationRequest`) names WHAT
phenomena matter, WHERE (a box or sphere), WHEN (a window of ticks) and HOW finely (a refinement level). The
**representation manager** (`request.RepresentationManager`) refines what active requests cover, a bounded
number of operations per tick, and tries to coarsen refined blocks no request needs. Blocks whose detail
matters refuse to merge and stay refined.

## Determinism

The same world and the same inputs give bit-identical results on every machine. The rules:

- Everything is visited in a fixed order: sections by key, blocks by index, cells depth first by octant,
  which is also the natural order of `space.CellId`.
- Budgets count estimated work, never wall-clock time.
- Random numbers come from `math.DeterministicRandom`, a SplitMix64 stream keyed by world seed, tick and
  model.
- Transcendental functions use `StrictMath`. Hash-ordered collections, `Set.of`/`Map.of`, parallel streams and
  clocks are banned in the core; `DeterminismLintTest` fails the build if they appear.
- Sums that are compared for conservation use compensated summation in a fixed order.

`PhysicalWorld.stateHash()` hashes the physical state with SHA-256 so runs can be compared directly.

## Roadmap

Phase 0 (the foundation) and phase 1 (heat and phase change, the first playable alpha) are in, and
temperatures are saved with the world. Next for heat: radiation, convection in liquids, and refining blocks
where temperatures change steeply. After that come structure and fracture; rigid bodies, contact and emergent
tools; materials processing and microstructure; fluids and chemistry; electricity and control; causal
targeting and molecular dynamics; and finally life and society, on the way to 1.0.
