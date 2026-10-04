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

Heat moves by **conduction** (`physics.thermal.ConductionModel`): Fourier's law between touching cells, solved
with explicit finite volumes. Every face moves the same heat out of one cell and into the other, so energy is
conserved to rounding, and phase changes happen by themselves as enthalpy crosses a material's latent heat. Each
cell takes substeps short enough to be stable for it alone, the step halved as often as it needs: a hot plume
above a flame may take hundreds of substeps while the rock around it takes one. Until the fluid model exists, two
correlations stand in for moving air: surfaces in gas exchange heat with a natural-convection coefficient of
10 W/(m²·K), and warm gas below cooler gas mixes with a coefficient that grows with the square root of the
temperature difference, so heat rises.

Liquids carry heat by moving too, and their measured viscosity and thermal expansion say how fast, so the same
model estimates it from each liquid's own properties. A liquid against other matter uses the natural-convection
correlations for a plate in a large body of liquid: Churchill and Chu's for a wall, and Incropera's for a floor
or ceiling, with buoyancy from the heaviest or lightest the liquid gets between its own temperature and the
surface's. Liquid that a floor makes heavier or a ceiling makes lighter lies still and only conducts. Two cells
of one liquid exchange heat at 0.135·ρ·c·v, with v the speed buoyancy drives between them as far as viscosity
allows: lighter liquid below denser liquid turns over, liquid side by side exchanges at half that speed, and
lighter liquid above denser liquid stays layered. Water is densest at 4 °C, so a pond cooled from above turns
over until it reaches 4 °C, then keeps its coldest water on top and freezes from the surface down. Lava, a
basaltic melt about a hundred thousand times as viscous as water, still churns enough to keep its face hot:
beside ice it passes on about ten times the heat it could conduct through half a block.

Surfaces also exchange heat by **radiation** (`physics.thermal.RadiationModel`) across gas and vacuum: grey,
diffuse surfaces by the Stefan–Boltzmann law, without reflections. Gas lets radiation through and anything
else stops it, glass and water included, as they do at these wavelengths. Only blocks more than 10 K from
their surroundings' temperature radiate, so the cost follows what is hot or cold. Each of their faces that
touches gas casts a fixed pattern of 32 rays up to 16 blocks (`physics.thermal.RayPattern`, a rank-1 lattice
chosen to reproduce exact view factors between nearby squares); the share of rays that reach a block is the
view factor to it, and rays that get no further reach surroundings at the weather temperature, which is
declared. When both blocks of a pair radiate, each estimates their exchange and counts half. A face keeps its
rays until a block on their way changes whether it lets radiation through, so a steady scene casts none. The
exchange takes as many substeps as the hottest, smallest blocks need, up to 64; blocks that would need more
are damped, which keeps them stable but slow, and reported.

Where much heat flows through a face, a whole block cannot show it: a cell has one temperature and passes heat
on as if from its centre, so heat taken in at one face warms the whole cubic metre at once. **Thermal
refinement** (`physics.thermal.ThermalRefinement`) splits such blocks. Conduction and radiation report the
temperature drop each cell needs between its centre and a face: the heat through the face times the cell's
resistance to it, half its edge over its conductivity. A cell whose drop exceeds 50 K splits into eight, one
level a step, down to level 2 (25 cm cells), the largest drops first, at most 256 a step and 16 384 cells in
all. Radiation only counts where it brings more than 5 kW/m², five times strong sunlight, so sunlight and
distant glow never refine anything. Gas never splits, because convection rather than its cell size sets how it
passes heat on, and neither do blocks a heat source holds; liquid reports no drop where it moves, for the same
reason. The cells of a refined block merge back eight at a
time once every drop in the block is below 12.5 K and the eight agree within 5 K, in the same phase and at the
same stage of any phase change, so a melting front keeps its cells; each step looks at a few refined blocks in
turn. A merged cell's drops are about twice those of its children, well below the split drop, so cells do not
split and merge in turn. Measured at Anchor's default clock, a block of ice beside lava melts in 16 game
minutes with whole blocks, 7 with 50 cm cells, 6 with 25 cm cells and 5.5 with 12.5 cm cells, which cost three
times as much as 25 cm ones.

Refined blocks radiate from their outer cells. Each face of a block casts its rays once, and the cells on that
face share what it sends and takes in by their emissivity and area. Opacity follows the whole block, so
refining a block changes no rays. A refined block counts as calm when its matter as a whole changes more slowly
than the calm rate, its cells' net changes added regardless of sign over the whole block's heat capacity, so a
refined room still falls asleep. Snapshots store refined blocks' totals, so a block is saved and restored whole
and splits again if its heat is still steep.

**Sunlight and the night sky** (`physics.thermal.SkyModel`) reach the ground through the top faces of blocks.
Light falls straight down each column, as Minecraft's skylight does, so a shadow lies right under whatever
casts it, and the host says where the open sky begins in each column. The first block below the sky that is
not gas is the column's surface. Each phase of a material says how it meets the light (`matter.Surface`): its
albedo and, for translucent matter, the fraction of each of two bands that crosses a metre of it, after Paulson
and Simpson's split of sunlight in clear water into blue-green light that crosses tens of metres and red and
near-infrared light that is gone within the first. Light that gets through warms the blocks below, down to 32
blocks, and the bed under a body of liquid hands its share to the liquid on it. `physics.thermal.SkyPhysics`
gives the sunlight on level ground from Meinel and Meinel's clear-sky fit with Kasten and Young's air mass,
dimmed by cloud as Kasten and Czeplak found, and the sky's own thermal radiation from Brutsaert's clear-sky
emissivity, raised by cloud as Unsworth and Monteith found.

At an ordinary surface the model takes the top face over from conduction and radiation and balances it, solving
implicitly for the surface temperature: the sunlight taken in, the sky's radiation, the surface's emission, the
heat the air carries off at 10 W/(m²·K), and latent heat as water evaporates from a wet surface or dew and frost
settle on it, held back by the air and by the surface's own resistance, as in the Penman-Monteith equation. A
block is much thicker than the layer a day's warming reaches, so a solid's surface is a skin that follows
Deardorff's force-restore method: it has the heat capacity per area the daily cycle stirs, e/√(2ω) for the
thermal effusivity e and the day's angular frequency ω, and is pulled back towards its block's temperature at
the rate ω. A skin is no warmer than the melting point of a solid that melts, so sunlit snow stays at 0 °C and
melts. A liquid mixes, so its surface is the whole block. The heat the air carries off goes to the open
atmosphere rather than into the cell above, which would warm by tens of kelvin over sunlit ground; that cell
only relaxes through the surface, so a fire's warm air still reaches the ground. Refined blocks, heat sources
and solids more than 50 K from their weather only take sunlight, and conduction and radiation keep their top
faces, so warm air still rises from a hot block of iron.

The sky's heat is declared as forced, so sunshine keeps no section awake: it reaches sleeping sections too, each
column every fourth step over the time it waited, and only a phase change it finishes or starts wakes a section,
so a host sees the snow it melted. Heat flows below the surface only where sections are simulated, so light
bound for a block in a sleeping section warms the column's surface instead; kept where it fell, it would pile up
there without end. Measured at Anchor's clock with a clear sky: a layer of snow in air at 5 °C melts in about 10
hours of sunshine and 19 under cloud, and lasts in air at 0 °C; a pond's top swings by about 3 °C over a day and
evaporates about 6 mm of water a day in the plains, and about 14 mm in a desert whose air stays at 42 °C through
the night; and the sky costs about 0.5 ms a step for 125 sleeping sections with 6 400 surfaces.

Four models connect the simulated region to the rest of the world, and all declare the energy they exchange:

- **Heat sources** (`physics.thermal.HeatSourceModel`) stand for things the simulation does not model yet,
  such as a flame. Each heats its block towards a temperature with at most a set power, and never cools it.
- **Radiation** that leaves the simulated region, described above, reaches surroundings at the weather
  temperature.
- **The sky**, described above, brings sunlight in and exchanges thermal radiation, heat and vapour with the
  weather over each surface.
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
Sleeping sections are paused, not cooled, apart from what the sky does to their surfaces.

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

## Instruments

`instrument.ProbeSet` holds a world's probes: named points, each with a recording, and one clock of simulated
seconds that every step moves on, so all probes share one time line. A recording (`instrument.TimeSeries`) keeps
any length of readings in fixed memory: readings fill buckets one each, and when the buckets run out neighbours
merge in pairs, each bucket keeping its time span, its count of readings and missing ones, and the lowest, highest
and sum of its values. A long recording so keeps its whole span at a coarser grain without averaging a peak away.
The latest sixteen readings are also kept one by one, and a least-squares line through them gives the current
rate of change. A missing reading, for a point that is not simulated, takes time but no value, and leaves a gap.

`instrument.ChartImage` draws as many as four recordings as a 128 by 128 picture in a handful of inks, the size
of a Minecraft map: time from the first reading to now along the bottom, marked in one unit at round intervals,
values up the side on round marks of 1, 2 or 5 times a power of ten, each recording a line through its buckets'
means over a band from their lowest to their highest values, and a legend with each recording's latest value,
written in a 3 by 5 pixel font. `instrument.Sparkline` draws a recording as a line of block characters, and
`instrument.TimeSeriesCsv` writes one bucket per row.

## In Minecraft

`anchor-neoforge` connects a hosted world to each Minecraft dimension and holds no physical rules of its own.

- **Blocks.** `BlockMapper` describes each block state the first time the simulation meets its id. The
  `anchor:materials` data map comes first, so data packs can describe any block. Built-in rules cover the
  blocks whose heat or phase matters: water, ice, snow, lava, magma, fire, torches, lanterns, candles,
  campfires and lit furnaces. Everything else is guessed from its name (`MaterialGuess`) or its sound, with
  its fill from its collision shape. A block filling less than a fifth of its space counts as the air or
  water around it, so a torch is a heat source in air. Lava and magma never cool in Minecraft, so their
  sources outrun what they radiate even with every face open. Reloading tags or data packs describes every
  block again.
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
  vanilla does, and the result is held between −30 and 45 °C (`Climate`). Its air has a relative humidity of
  20 % plus 65 % of the biome's downfall: 20 % in deserts, 46 % in plains, 72 % in forests.
- **Sun and sky.** A dimension with skylight, no ceiling and the Overworld's kind of sky has a sun: each step
  takes the sky from Minecraft's `sun_angle` environment attribute and its rain and thunder levels, so rain
  overcasts the sky and moistens the air and thunder brings storm clouds; clear weather is a cloudless sky. The
  attribute may differ from place to place, so it is read at the first simulated section. Where the open sky
  begins in each column comes from the `WORLD_SURFACE` heightmap, the highest block that is not air, when a
  section comes in, when a block changes and as each section is compared with the level; whether what lies
  under it lets light through is the engine's to judge. Dyed wool, concrete and terracotta reflect by their map
  colour, their linear luminance between black's 0.05 and white's 0.85, and grass blocks reflect 23 %
  (`BlockMapper`). The Nether and the End have no sun or sky in the simulation.
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
- **Probes and charts.** `LevelProbes` holds a dimension's probes and charts as a NeoForge data attachment on the
  level, saved with it, recordings as the raw bits of their doubles. After each step `LevelHeat` gives every probe
  the temperature at its point, the same reading a thermometer touching there gets, or a missing reading if the
  point is not simulated. A chart is an ordinary filled map, locked so the game never draws the land on it,
  centred far beyond the world border so item frames holding it leave no marker on it, and `ProbeCharts` draws its
  picture again every five steps, changing only the pixels that differ so players are sent only those. A chart
  whose map data is gone is forgotten, and a dimension keeps drawing at most 16 charts. `ProbeCommands` holds the
  `/anchor probe` commands; the thermometer leaves and takes probes when used while sneaking.
- **Thermal camera.** While a player holds one, `ThermalCamera` takes an image of what they look at every ten
  game ticks: 24 by 14 rays across 48° stop at the first block outline or fluid within 24 blocks, and each hit
  becomes a dot on that face showing the temperature of the cell it hit, or in the air view the air along the
  rays that differs from the typical air in view. Dots are vanilla trail particles sent to that player alone,
  which stay where they are put until the next image, so the client needs no mod code for them. `ThermalScale`
  maps temperatures to colours that brighten from violet to near white and follows the view with a span that
  widens at once and narrows slowly.

The adapter's plain-Java parts have unit tests. Everything that needs Minecraft is covered by game tests
(`AnchorGameTests`) that run on a real server in CI: packed ice warmed past 0 °C becomes water, water chilled below
it becomes ice, water heated past boiling leaves air, a block placed and heated in the same tick takes the
temperature, a torch warms the air above it, a section written into its chunk and brought in again comes back
exactly, the save format keeps every number, a thermal camera reads a hot iron block at its crosshair and shows it
among the cold floor, its air view shows the warm air above the iron and none of the still air, its tooltip says
how to use it, an iron block at 1500 K warms a stone block across two blocks of air, the stone walls of a lava pool
are refined so that their faces read hotter than the stone behind, on the thermometer too, and in a noon sun black
wool takes in more than three times the sunlight of white wool beside it, grows more than 10 K hotter on top, and
reads so on the thermometer. A probe in a hot iron block records it cooling and a thermometer names the probe, a
chart of it is a locked map with its line on white paper, a thermometer used while sneaking leaves a probe where it
touches and takes it away again, the format probes are saved in keeps every reading, and the thermometer's tooltip
says how to use it. The game tests load the mod from the build directories, so CI also installs a NeoForge
server the way players do, starts it with the released jar and checks that the mod loads, its self-test passes,
heat runs, the server stops cleanly and nothing is logged as an error (`.github/scripts/smoke_test.py`).

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

Phase 0 (the foundation) and phase 1 (heat and phase change, the first playable alpha) are in, surfaces radiate,
temperatures are saved with the world, blocks refine where temperatures change steeply, liquids carry heat by
moving, the sun and the night sky warm and cool the land, and probes record temperatures and chart them on maps.
Next for phase 1: a simulation console, a laboratory world, and hot metal that glows. After that come structure
and fracture; rigid bodies, contact and emergent tools; materials processing and microstructure; fluids and
chemistry; electricity and control; causal targeting and molecular dynamics; and finally life and society, on the
way to 1.0.
