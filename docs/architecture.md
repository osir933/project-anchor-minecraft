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

The only way back in time is an explicit **snapshot** restore, of the whole world (`world.WorldSnapshot`) or of
a box of blocks (`host.RegionSnapshot`, see [Hosting](#hosting)), recorded in the event log.

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
refined room still falls asleep. The snapshots sections are saved with store refined blocks' totals, so a block
is saved and loaded whole and splits again if its heat is still steep; snapshots of experiments keep every cell.

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

Hot surfaces glow. `physics.thermal.Incandescence` gives the colour and brightness of the light a surface gives
off by its temperature alone: Planck's law weighted with the CIE 1931 colour matching functions, in the multi-lobe
fit of Wyman, Sloan and Shirley, from 360 to 830 nm gives its tristimulus values, 683 lm/W times Y its luminance,
and a grey body gives off its emissivity times a black body's light. For a screen the colour becomes sRGB scaled to
its brightest channel, and the luminance a glow level on a logarithmic scale, as the eye judges brightness: 0 at
10<sup>−2.5</sup> cd/m², where a black body of about 775 K is just seen in the dark, and 1 from 10<sup>4</sup>
cd/m², which it reaches at about 1520 K. A table of black bodies every 10 K, interpolated, makes it cheap enough
for every spot of every glowing face. `HostedWorld.glowingBlocks` lists the blocks of a section that glow, each
(`host.GlowingBlock`) with its emissivity and, on every face no opaque block hides, the temperatures of 4 by 4
spots just inside the face: the cell there in a refined block, and the sky's skin temperature on a top open to the
sky. Blocks whose enthalpy is too low to glow are skipped before anything else, gases have no surface to glow
from, and the game names the blocks it already draws glowing, such as lava, which are left out.

## Structure

Phase 2 asks of every structure whether it stands under its own weight, and what breaks if it does not
(`physics.structure`).

Materials that carry loads have **mechanics** (`matter.Mechanics`): how they fail (brittle, ductile or
granular), Young's modulus, Poisson's ratio, tensile and compressive strength, a friction coefficient, thermal
expansion and fracture toughness, each with its source. Stiffness and strength are curves over temperature, so heat
softens matter: iron follows the reduction factors of Eurocode 3 for steel in fire and keeps 47 % of its yield
strength at 600 °C, concrete and timber follow EN 1992-1-2 and EN 1995-1-2, rock and brick published tests. A
ductile material's tensile strength is its yield stress, and granular matter such as sand carries no tension at all.
Matter carries loads in proportion to how much of it is solid: partly molten matter keeps that fraction of its
strength and stiffness, liquids carry none, and air has no mechanics at all.

A structure is a **frame** (`Frame`) of blocks. Each free block is a rigid node with six degrees of freedom at its
centre, carrying its weight there. Each pair of blocks that touch is joined by a Timoshenko beam from centre to
centre, half its length in each block's material, with the cross-section of the patch where they touch
(`Contact`): a union of rectangles on the shared face, so two full blocks touch over a square metre and a slab
beside a block over half of it. `BeamElement` builds a joint's 12 by 12 stiffness matrix by integrating the
flexibility of its two pieces, which is exact for a beam that is uniform piece by piece, shear deformation
included. **Ground** blocks hold still; a joint to the ground is the half beam inside the free block, clamped at
the face. The ground is hemmed in by the earth around and below it, so pressing does not crush it, but a joint to it
can still crack on its side: stone cannot hang from soil.

A joint is **intact**, joining its blocks like one piece of material, or **cracked**, carrying only what contact
can. `StructuralAnalysis` solves for the blocks' displacements under their weight, linear and elastic, takes each
joint's forces and moments at the face where its blocks meet, and checks them on each block's side. An intact joint
of brittle material holds while the stress at every corner of its patch stays within the tensile and compressive
strengths (Rankine), and the principal stresses at its centre too, where shear peaks at 1.5 times its average and
twisting adds Roark's peak for a rectangle. An intact ductile joint holds while (N/N<sub>p</sub>)² +
|M<sub>y</sub>|/M<sub>py</sub> + |M<sub>z</sub>|/M<sub>pz</sub> stays below one, the plastic interaction of a
rectangle, with Eurocode 3's reduction of bending strength under heavy shear. A cracked joint, and any joint of
granular matter, holds while it is pressed together, the resultant stays within the patch so the blocks do not
tip, shear and twist stay within friction, and the most pressed corner is not crushed. Forces within a millionth
of a newton plus a billionth of the structure's weight count as round-off, so contacts that carry nothing hold.

When joints are overloaded, the worst gives way, and with it every joint within 2 % of it, so a symmetric structure
breaks symmetrically: an intact joint cracks, a cracked one lets go. A crack leaves the frame as stiff as before, so
the analysis only checks the joints again; a joint letting go changes the frame, which is solved again. Blocks left
with no path of holding joints to the ground fall. The analysis stops after 64 solutions, letting everything still
overloaded go at once, and says so. Its result lists each block's displacement, rotation and the load of its worst
joint, each joint's state, load and limiting mode, the cracks in the order they formed, the falling blocks, and
notes where the answer is less certain: when deflections exceed 0.1 m or 0.1 rad, small-deflection theory is
doubtful and the result says so.

The equations are solved exactly by `BlockCholesky`, a sparse Cholesky factorization in 6 by 6 blocks. Nodes are
ordered by nested dissection of their positions: the structure is cut by a plane of blocks through its longest
side, each half is ordered the same way, and the plane comes last, so the order depends only on positions. The
factorization is multifrontal: the nodes of a cutting plane are eliminated together in one dense front, 48 columns
at a time, and fronts that differ only by a few zeros merge. A direct solver suits slender structures, where
iterative ones converge slowly. A wall 1024 blocks long and 16 high (16,384 blocks) solves in about a second; solid
masses cost more, as they do for any direct solver: a solid cube of 16 blocks a side in about 3 seconds.

The model is checked against beam theory. A cantilever's tip deflection and slope match Timoshenko's closed forms to
a part in a billion, whichever way it points, and a column shortens by ρgn²/2E. Granite, at 10 MPa in tension,
holds an overhang of 11 blocks and cracks at the root at 12, where the top edge carries 3ρgn²; iron yields at 32
blocks, when the root's moment reaches the plastic moment f<sub>y</sub>/4, and at 22 at 600 °C. A granite span
clamped at both ends holds 27 blocks and cracks at both ends at once at 28, as wL²/12 says. Sand stands in a column
but falls from a wall, a long arm breaks off a tower while the short arm holds, and partly molten granite holds
less.

**Thermal stress** (`ThermalShock`) cracks a block from within. A body that warms evenly, or whose temperature
changes in a straight line across it, expands freely and is not stressed: the straight-line part only bends it. Only
what departs from a straight line is held back by the rest of the body. So the thermal strain of each cell of a
refined block, the expansion its temperature gives it from the block's mean temperature, is fitted by least squares
with a field that changes in a straight line across the block, each cell weighted by its mass and stiffness so that
the stresses left over balance as they must in a free body. A cell colder than the field is pulled and a hotter one
pressed, at E/(1 − ν) times its departure from it, as a thin layer of a large body held along it in both directions
is. That is exact for a slab heated or cooled on its faces, and errs high at the corners of a cube: a block of four
cells a side whose temperature falls along a parabola by ΔT from its middle to two opposite faces has its outer cells
pulled by EαΔT/4(1 − ν), which the tests check to a part in a billion. Each cell is checked at its own temperature,
against the tensile strength where it is pulled and the compressive strength where it is pressed, so a block cracks
once a cell departs from the field by σ<sub>t</sub>(1 − ν)/Eα: 19 K for granite, 27 K for brick, 57 K for glass, 7 K
for concrete and 1.5 K for ice. How finely a block is refined decides how much of its stress shows: two cells along a
line always lie on a straight line, and the steep fall in temperature at a quenched face spreads over the cell beside
it.

**Expansion held back** (`Frame.Expansion`) loads a structure. Heat stretches a block's matter beyond the length it
has at the temperature where it is free of thermal strain: by its thermal strain at its centre, and more on its warmer
side, which bends it. For a refined block the cells say how: the straight line that best fits their strain, as for
thermal stress, gives the gradient that bends the block, and the mean strain of each half of the block along each
axis says how far that half grows. The two agree where the temperature changes in a straight line; beside lava, where
it does not, a straight line that bends the block rightly would make its far half shorter than it is. A block that is
not refined is stretched evenly by the strain of its temperature, and the ground does not stretch at all, held as it
is by the earth around and below it. Each half-beam of the frame would move its far end, were nothing to hold it, by
its half's strain along the beam and by the curvature of its gradient across it; held back, those movements s become
loads K·s, solved with the same factorization as the weight, and the forces at a joint's ends are K(d − s).

Heat moves blocks by hairlines, not lengths, so what it does to a joint depends on whether that movement lets its
strain go. Brittle matter cracks before it moves, so its side of a joint takes the forces heat adds. Ductile matter
yields a little and keeps its strength, so its side takes only the weight. A cracked joint or a contact rocks rather
than bends; where heat alone would make it slip, rock or open, it does so by a hairline and holds as well as it does
without heat, heat that presses it together lends it friction, and heat that crushes its edge has nowhere to go. A
joint that cracks stops bending with its blocks, so the analysis solves again. The tests check a bar held at both
ends against EAε, a free one growing by ε(n − ½), a bar warmer on top bending by gL²/2 when free and held straight by
EIκ, a span holding its weight that 15 K of cold cracks at both ends and drops, a cracked span that heat pressing it
holds up, metal that yields the strain away, stone joined to metal pressed by 2ε/(1/E₁ + 1/E₂) and cracking on its
own side, a block that heat pushes along the ground sliding a hairline and staying, and heat crushing a crack's edge.
In 14.4 s steps, granite built beside lava cracks from uneven heat in about 115 steps, and with that switched off its
foot, held straight while the block bends away from the lava, cracks in about 200. A granite bridge two blocks above
a pool of lava cracks at its ends in about 30 steps as it bows up; cracked through by uneven heat first, it stands
on the pressing of its expansion held back between its pillars. Walls of concrete, granite, brick and glass in the
sun through two days take at most a third of what cracks them.

What the model leaves out, so far: yielding steel gives way at once instead of hinging and handing its load on, so
redundant metal frames fall somewhat early; cracked joints do not wedge into arches, though heat held back can press
them together; slender columns do not buckle, not even when heat pushes on them; the ground neither gives nor
stretches; deflections are small; and loads are static: the weight of blocks and the strain of heat.

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

A **region snapshot** (`host.RegionSnapshot`) keeps a box of blocks for an experiment to be rewound to. The same
rule picks the blocks it stores, comparing their provenance too, but a refined block is stored cell for cell
(`world.BlockCopy`): each cell packed into a number that says its level and place in the block, depth first, so
the cells of one block tell where the next begins. It holds no coordinates, so it can be restored anywhere.
Restoring sets every block in the box, exactly as saved where the saved matter still fits the game's block there
and afresh otherwise, as one exchange declared to the ledger and one event; a copy that would take the world past
its budget of cells comes back as its totals. It wakes every section the box touches and makes the sky forget the
temperatures of the surfaces in the box, which then follow the restored blocks.

A block's appearance also gives its **shape** (`physics.structure.Shape`), boxes inside its cube that say where it
touches its neighbours, so a slab touches the block beside it over half a face and a fence only where its post is.
A block too thin to count for heat, such as a fence, may name a **frame**, the material that carries its loads, at
the temperature of the air or water around it and with the mass its shape holds. A block **carries** loads when its
matter, or its frame's, has mechanics and is not all molten, and its shape reaches a face of its cube. The world as
it was found is **ground**, which holds still. Matter brought into a block that carried no load, as a placed block's
is, is **built**, and so is new matter in a built block. Brought matter is another material, or a frame put up in
the block, so water freezing into ice, snow piling up and moss spreading over stone stay ground. Blocks the game marks
**immovable**, as Minecraft's unbreakable blocks are, are never built and hold up whatever hangs from them. Each
block keeps whether it is built and which of its joints have cracked (`host.StructureFlags`), and section and region
snapshots save both.

A change that touches loads marks the built blocks around it to be checked, and so do imports, restores and heat that
changes a built block's strength or stiffness by more than 2 % since it was last checked, or, for brittle matter,
stretches it by enough to change its stress by a tenth of its tensile strength, which each section looks for once in
8 steps. A built block is free of thermal strain at the climate of its section, where it was put in place; one held
by a heat source, whose temperature the source sets, is not stretched, and the game can switch stretching off. `nextStructure` takes the next block waiting, in a fixed order, and **surveys** the structure it
belongs to (`host.StructureSurvey`): the survey spreads through the built blocks joined to it, nearest first, up to a
limit, and takes the natural blocks that carry loads as the ground it stands on. Built blocks beyond the limit, and
blocks in sections that are not imported, hold still, so a large structure is analysed around the change that
called for it. The game analyses the survey's frame when and where it likes, then **settles** the result: joints that
cracked or let go stay cracked, and the blocks left with nothing to hold them up are handed back for the game to let
fall. If a block the survey looked at changed meanwhile, nothing is settled and the structure waits to be checked
again, as it does when the game drops an analysis (`checkLater`).

Each step, once heat has run, the hosted world works out the thermal stress in every refined built block of brittle
matter in the sections heat ran in, from its cells that are mostly solid. A block whose most loaded cell reaches its
strength **cracks through**: it and every joint it has crack, so it holds only by pressing and friction, and its flags
say so, saved with them, so that it is not checked again until new matter replaces it. The built blocks around it
wait to be checked, and the step returns it (`host.Fracture`) with the block its appearance names to show instead
(`fractured`), if any, and its temperature. The world as it was found does not crack, since importing a section starts
its blocks at temperatures of their own, all at once, which would crack the lining of every lava pool brought in, and
neither do blocks held by a heat source, whose inside the source sets, nor thin blocks carried in a frame. The game can
switch cracking off.

When a hosted world steps is up to `host.Pacer`. At normal speed it steps every few ticks of the game's clock;
it can also be paused, take steps asked for by hand, run at a speed given in hundredths of normal, or work
through a number of steps as fast as it may. What a speed is owed is counted in whole hundredths of a tick, so
every speed keeps an exact rhythm however long it runs. The steps normal speed would take are always taken; the
ones beyond them only while one more step like the recent ones fits into a budget of milliseconds per tick, and
what a speed is owed that does not fit is forgiven at the end of the tick, so a slow machine runs as fast as it
can instead of falling ever further behind. Steps asked for by hand are never forgiven and come at least one a
tick, and while they wait the speed earns nothing. Only how many steps fit into a tick depends on the machine;
the steps themselves are the same everywhere.

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
  sources outrun what they radiate even with every face open. A block's shape comes from its collision boxes,
  with sides within 2/16 of a face taken out to it, so a chest, soul sand or mud holds up what stands on it; a
  block with no collision, such as a fluid, a flower or one layer of snow, touches nothing, and one of more than 32
  boxes is taken as its bounding box. A thin block is framed in the material its name suggests when that material
  carries loads, so a fence is a post of wood and iron bars a lattice of iron. Blocks that cannot be broken, such
  as bedrock and barriers, are immovable. Reloading tags or data packs describes every block again.
- **Dimensions.** `LevelHeat` runs one hosted world per dimension, started when the dimension first ticks.
  Block changes arrive through NeoForge's neighbour notifications and are taken in, in sorted order, at the
  start of the next tick. Every few game ticks it imports the sections players have come near, lets go of
  those they have left and compares one imported section with the level to catch any change nobody reported,
  paused or not. It steps the simulation when its `Pacer` says, every step of a tick under the same sky.
  Unloading a chunk saves its sections into it and lets go of them. Frozen time (`/tick freeze`) pauses heat
  with everything else, and `/tick sprint` runs it faster with everything else.
- **Phase changes.** A step returns the blocks whose matter now shows a different phase. `LevelHeat` checks
  that block and matter still agree with the step, then places the replacement the appearance names. Ice
  holds the same matter as water, so the block keeps its exact state: water frozen at −5 °C becomes ice at
  −5 °C. Steam leaves in a puff of cloud, and the air that takes its place starts at the steam's
  temperature.
- **Structures.** Each game tick `LevelStructures` takes the built blocks waiting to be checked and analyses the
  structure each belongs to, up to `maxBlocks` (4096) built blocks at a time. Structures of up to 256 blocks are
  analysed in the tick, as many as fit into 5 ms; a larger one is analysed on a thread shared by every level and
  settled exactly max(10, n/64) game ticks after its survey, n being its blocks, waiting for the analysis if it is
  late, so it falls at the same tick on any machine. Nothing else is analysed meanwhile. A joint that cracks makes
  its block's breaking sound, lower, and a puff of its dust where the two blocks meet. Blocks left with nothing to
  hold them up fall as falling blocks, lowest first and at most 256 a tick, and hurt what they land on as pointed
  dripstone does; blocks that cannot fall whole, such as chests, doors and beds, break where they stand, and blocks
  the game itself breaks for want of support, such as torches, are left to it. What grows is natural: blocks that
  change in the tick a tree or another feature grows, up to 16 blocks to its sides, 8 below and 48 above, crops and
  the blocks around them, and the stone, cobblestone and obsidian lava makes where it meets water. `StructureCommands`
  holds `/anchor structure inspect`, which tells whether a block is built, how loaded its structure is and which of
  its joints have cracked, and `/anchor structure mark`, with which operators make a box of blocks built or natural.
  The structures section of the config switches structures off or bounds one analysis. An error stops structures in
  that dimension, logs it and shows it in `/anchor heat status`, and heat carries on.
- **Thermal shock.** `BlockMapper` gives each block of brittle matter the block it turns into once uneven heat cracks
  it through: cobblestone for stone, cobbled deepslate for deepslate, the infested forms of cobblestone and cracked
  stone bricks for infested stone and stone bricks, the block named `cracked_` and its own name wherever the registry
  has one, as for stone, deepslate and nether bricks and deepslate tiles, and air for blocks named glass, which
  shatter. Other blocks stay as they are, and a data pack can name the block in the material entry's `fractured`.
  After each step `LevelHeat` shows up to 64 cracked blocks a tick: it checks that the block is still cracked and
  still the same kind of block, places its cracked form with the block's breaking sound pitched low and a puff of its
  dust, or breaks it, dropping what it drops, when the form is air, and takes the change in at the block's
  temperature, so cobblestone keeps the stone's heat and its cracks. `/anchor heat inspect` and
  `/anchor structure inspect` say how close uneven heat comes to cracking a block, and `/anchor heat status` how many
  blocks have cracked. The `thermalShock` setting in the structures section switches it off, whether or not
  structures stand or fall.
- **Expansion.** `LevelHeat` passes the `thermalExpansion` setting of the structures section on each tick, and
  structures that heat stretches are checked like any other. `/anchor structure inspect` and `/anchor heat inspect`
  say how far heat has made a built block longer or shorter than at the climate where it stands and on which side it
  is warmest, where that bends it, and the structure's most loaded joint says how loaded it would be without heat.
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
  (`BlockMapper`). The Nether, the End and laboratories have no sun or sky in the simulation.
- **Laboratory.** The Laboratory world type (`data/anchor/worldgen/world_preset/laboratory.json`, listed in the
  `minecraft:normal` world preset tag so the Create New World screen offers it) has a flat Overworld of bedrock, 62
  layers of stone and one of light grey concrete, its top at y = −1, in the `anchor:laboratory` biome: a base
  temperature of 1.0195652 and a downfall of 0.46153846, which `Climate` turns into 20 °C and 50 % humidity, no
  precipitation, no mob spawns, no features and no carvers. A level is a laboratory when every biome its generator
  can place is that biome (`Laboratory.is`), and `LevelHeat` gives it no sun or sky. The first time a laboratory
  world's server starts, `Laboratory` holds time and weather, stops mob, phantom, patrol and trader spawning and
  random ticks through the game rules, moves the Overworld's clock to noon and clears the weather, and notes in an
  attachment on the level which version of this setup it has had, so a later version can add to it. A player's
  first join gives them a thermometer and a thermal camera, noted in an attachment on the player that survives
  death. On the client, `LaboratoryScreen` switches the Create New World screen to Creative with commands allowed
  when the world type changes to the Laboratory.
- **Experiments.** `Experiments` holds the ready-made experiments, each a plan in its own frame: blocks right, up and
  ahead of the middle of its near edge, turned to the way the player faces, with the blocks it places, the temperatures
  it starts them at and the probes it leaves. Building one checks that its space fits in the world, that heat runs in
  all of it and that it is clear (air or plants above the bench, nothing with contents where the bench goes, and solid
  ground under any liquid poured into the bench), then clears the space from the top down, lays a bench of smooth stone,
  places solids before liquids, marks it all natural so that nothing of it falls, sets the starting temperatures through
  `LevelHeat.setTemperature`, adds the probes under free names and saves the box as the snapshot `experiment-<name>`.
  `ExperimentCommands` holds `/anchor experiment list` and `build`, which gives the builder a chart of the probes and
  offers the speed the experiment was sized for. The experiments were sized by running the engine on the same blocks in
  20 °C air until each showed its result within minutes.
- **Time.** Each game tick is 3.6 simulated seconds, so a Minecraft day lasts 24 simulated hours, and the
  simulation steps every four game ticks. Both are settings. `TimeCommands` holds the `/anchor time` commands,
  with which operators pause a dimension's heat, step it by hand, run it from 0.01 to 1000 times as fast, or
  send it ahead by a stretch of simulated time; steps beyond the usual ones fit into `stepBudgetMillis` (20 ms)
  of each tick. While heat goes ahead by more than 20 steps, the dimension's players see a boss bar of how far
  it has come and are told when it arrives. The pause and the speed are saved with the level as the `LevelPace`
  attachment; steps still waiting are not. The sun and the weather keep the game's time, so heat running faster
  sees the sun move more slowly.
- **Saving.** `ChunkHeat` holds the snapshots of a chunk's sections as a NeoForge data attachment, so they
  are written and read with the chunk: a short palette per section, then each saved block's position and
  palette index packed in an int array, and masses and enthalpies as the raw bits of their doubles in long
  arrays, with each built or cracked block's position and flags packed in one more int array. A section is
  written into its chunk when it is let go, when its chunk unloads (before the chunk
  is saved), when the level saves, when the server stops, and every minute in between, and only if it
  changed. Land nobody is near is paused: brought in again, it carries on from its saved state without
  catching up on the time that passed.
- **Snapshots.** `Snapshots` keeps a box of up to 64 blocks a side as one compressed NBT file in
  `anchor/snapshots/<dimension>` in the world's folder: its blocks as a vanilla structure template, marked with the
  game's data version so a later game can bring them up to date, and its heat as a region snapshot, masses and
  enthalpies as the raw bits of their doubles, with which blocks are built and which joints have cracked. A file is
  written beside the old one and then moved over it, so a crash never leaves half a snapshot. A restore places the
  template without updating neighbours, dropping items or setting off the blocks' reactions, as vanilla's structure
  blocks do, takes the placed blocks into the simulation and gives them their heat, all in one tick. Saving and
  restoring need every section the box touches simulated; entities are not kept. `SnapshotCommands` holds the `/anchor
  snapshot` commands.
- **Failure.** An error stops heat in that dimension, logs it and shows it in `/anchor heat status`; the game
  carries on.
- **Probes and charts.** `LevelProbes` holds a dimension's probes and charts as a NeoForge data attachment on the
  level, saved with it, recordings as the raw bits of their doubles. After each step `LevelHeat` gives every probe
  the temperature at its point, the same reading a thermometer touching there gets, or a missing reading if the
  point is not simulated. A chart is an ordinary filled map, locked so the game never draws the land on it,
  centred far beyond the world border so item frames holding it leave no marker on it, and `ProbeCharts` draws its
  picture again once a second while heat steps, however fast it runs, changing only the pixels that differ so
  players are sent only those. A chart whose map data is gone is forgotten, and a dimension keeps drawing at most
  16 charts. `ProbeCommands` holds the `/anchor probe` commands; the thermometer leaves and takes probes when used
  while sneaking.
- **Thermal camera.** While a player holds one, `ThermalCamera` takes an image of what they look at every ten
  game ticks: 24 by 14 rays across 48° stop at the first block outline or fluid within 24 blocks, and each hit
  becomes a dot on that face showing the temperature of the cell it hit, or in the air view the air along the
  rays that differs from the typical air in view. Dots are vanilla trail particles sent to that player alone,
  which stay where they are put until the next image, so the client needs no mod code for them. `ThermalScale`
  maps temperatures to colours that brighten from violet to near white and follows the view with a span that
  widens at once and narrows slowly.
- **Glow.** Every ten game ticks `GlowSender` goes through the simulated sections within 128 blocks of each player,
  lists what glows in a section again only when it has changed or every 100 ticks, and sends each player the
  sections whose glow differs from what they were sent before, one message per section (`GlowPayload`): a block's
  index, emissivity and faces, then each face's temperatures in whole kelvin, one for a face as hot all over
  (`GlowData`). A section that stops glowing, or that the player leaves behind, is sent empty. A full, opaque
  block hides the face next to it, and blocks that give off light are left out. On the client, `GlowClient` draws
  a sheet of light just in front of each glowing face, following the boxes of
  the block's shape: whole where the face glows evenly and spot by spot where it does not, in the black body's
  colour with the glow level as its alpha, added to what is drawn behind it as lightning is.

The adapter's plain-Java parts have unit tests. Everything that needs Minecraft is covered by game tests
(`AnchorGameTests`) that run on a real server in CI: packed ice warmed past 0 °C becomes water, water chilled below it
becomes ice, water heated past boiling leaves air, a block placed and heated in the same tick takes the temperature, a
torch warms the air above it, a section written into its chunk and brought in again comes back exactly, the save format
keeps every number, a thermal camera reads a hot iron block at its crosshair and shows it among the cold floor, its air
view shows the warm air above the iron and none of the still air, its tooltip says how to use it, an iron block at 1500
K warms a stone block across two blocks of air, the stone walls of a lava pool are refined so that their faces read
hotter than the stone behind, on the thermometer too, an iron block at 1300 K glows with oxidised iron's emissivity on
every face but the one on the floor and the one against a stone block, and no longer once cooled to 300 K, while a lava
pool beside it is left out, and in a noon sun black wool takes in more than three times the sunlight of white wool
beside it, grows more than 10 K hotter on top, and reads so on the thermometer. A probe in a hot iron block records it
cooling and a thermometer names the probe, a chart of it is a locked map with its line on white paper, a thermometer
used while sneaking leaves a probe where it touches and takes it away again, the format probes are saved in keeps every
reading, and the thermometer's tooltip says how to use it. Paused, heat holds a hot iron block's temperature while the
game runs on and a thermometer says heat is paused; it then takes exactly the three steps asked for and stays paused,
sent 60 steps ahead it takes several a tick, and at twice normal speed it takes twice the steps. Packed ice saved in a
snapshot at −23 °C and then melted comes back from it as packed ice with exactly the heat it had, and the water that
spread from it is gone; the snapshot file keeps every number, those of a refined block's cells too, and damaged files
are refused. The laboratory's biome gives air at 20 °C and 50 % humidity with no rain, the Laboratory world type is
there, and the game test world is no laboratory, so heat there follows the sun. Each ready-made experiment is built on a
bench of stone, in an environment of its own since it asks heat for the steps it needs, and after them its probes show
what it promises: after 300 steps the cooling iron's top is below 900 °C and more than 100 K cooler than its middle, and
its snapshot brings the iron back at 1500 K; after 2400 steps the top of the copper rod is more than 30 K warmer than
the iron's, the iron's 5 K warmer than the stone's and the stone's 1.5 K warmer than the brick's; after 3500 steps the
ice is at 0 °C and still ice while the stone beside it is past 4 °C; and after 1200 steps the iron in wool is within 5 K
of its start and more than 15 K warmer than the one in glass, which is more than 5 K warmer than the bare one. A stone
block put up in the air falls to the floor, a stone block on an oak fence stands, analysed with the fence and still
there 40 ticks later, taking the foot out of a cobblestone pillar three blocks high lets the two above fall into its
place, and a placed block is still built when its section is written into its chunk and brought in again, while the
floor under it is still natural. In a test of its own, which sends heat 900 steps ahead, stone and glass walls put up
around lava crack through: the stone turns to cobblestone that keeps its heat, and the glass shatters. The game tests
load the mod from the build directories, so CI also installs a NeoForge server the way players do, starts it with the
released jar and checks that the mod loads, its self-test passes, heat runs and can be paused and resumed, snapshots can
be listed and are refused where heat does not run, experiments can be listed and are not built where heat does not run,
the server stops cleanly and nothing is logged as an error (`.github/scripts/smoke_test.py`). Last, CI starts the game
itself under a virtual display with software drawing. Started with `-Danchor.renderTest=true`, the mod's `RenderTest`
creates a laboratory world and checks that its floor's top is light grey concrete at y = −1, that its game rules hold
time and weather and stop spawning and random ticks, that its clock stands at noon with clear weather, that heat there
follows no sun and that the player was given a thermometer and a thermal camera. It then builds a dark room with two
iron blocks in it, heats them to 1100 K and 1600 K, photographs them, cools them and photographs them again, and checks
that the laboratory's clock did not move meanwhile; `.github/scripts/render_check.py` checks that both blocks glowed
where they are, red to orange, the hotter one brighter and yellower, and that the glow was gone once they cooled.

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
moving, the sun and the night sky warm and cool the land, probes record temperatures and chart them on maps, and
operators can pause heat, step it, run it faster or slower, send it ahead, and save an experiment as a snapshot to
rewind it to, hot blocks glow in the colours of a black body, and the Laboratory world type gives experiments steady
surroundings, with ready-made experiments to build there. Phase 2, structure and fracture, has begun: materials
have mechanical properties that heat softens, what players build stands or falls by the strength of its blocks,
cracking where it is overloaded, uneven heat cracks brittle blocks from within, and expansion held back by a
structure loads its joints. Next, buckling and fracture inside blocks. After
that come rigid bodies, contact and emergent tools; materials processing and microstructure; fluids and chemistry;
electricity and control; causal targeting and molecular dynamics; and finally life and society, on the way to 1.0.
