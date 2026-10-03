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
solved with explicit finite volumes in substeps short enough to be stable. Every face moves the same heat out
of one cell and into the other, so energy is conserved to rounding, and phase changes happen by themselves as
enthalpy crosses a material's latent heat. Until the fluid model exists, two correlations stand in for moving
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
changed slower than one kelvin per hour for a while falls asleep. Sleeping sections are paused, not cooled.

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

Phase 0 (this foundation) is followed by: heat and phase change with the first playable alpha; structure and
fracture; rigid bodies, contact and emergent tools; materials processing and microstructure; fluids and
chemistry; electricity and control; causal targeting and molecular dynamics; and finally life and society,
on the way to 1.0.
