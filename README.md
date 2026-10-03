# Anchor

Anchor turns Minecraft into a physical world. Matter, heat, forces and chemistry follow real physical laws, so
tools, machines and industry emerge from physics instead of recipes. It is meant as a science sandbox: build
experiments, inspect what the simulation knows and how sure it is, and watch societies grow on top of it.

**Status: pre-alpha.** Phase 0 (the engine foundations) is in progress. Nothing here is playable yet.

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

The mod jar lands in `anchor-neoforge/build/libs/`. Run the game with `./gradlew :anchor-neoforge:runClient`.

Setting up Minecraft needs access to Mojang's and NeoForged's servers. Without it, build and test only the
engine:

```sh
./gradlew :anchor-core:build -Panchor.coreOnly=true
```

In game, `/anchor selftest` runs the engine's self-check.

## Licence

[MIT](LICENSE)
