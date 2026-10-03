package io.github.osir933.anchor.core.physics.thermal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.osir933.anchor.core.math.DeterministicRandom;
import io.github.osir933.anchor.core.matter.Composition;
import io.github.osir933.anchor.core.matter.Material;
import io.github.osir933.anchor.core.matter.MaterialLibrary;
import io.github.osir933.anchor.core.matter.PhaseRegion;
import io.github.osir933.anchor.core.matter.SpeciesCatalog;
import io.github.osir933.anchor.core.matter.ThermalState;
import io.github.osir933.anchor.core.model.Domain;
import io.github.osir933.anchor.core.model.SimulationScope;
import io.github.osir933.anchor.core.model.StepContext;
import io.github.osir933.anchor.core.space.GridPos;
import io.github.osir933.anchor.core.units.PhysicalConstants;
import io.github.osir933.anchor.core.world.CellState;
import io.github.osir933.anchor.core.world.MaterialRegistry;
import io.github.osir933.anchor.core.world.PhysicalWorld;
import io.github.osir933.anchor.core.world.WorldSettings;
import org.junit.jupiter.api.Test;

class LiquidConvectionTest {

    private static final GridPos A = new GridPos(0, 0, 0);
    private static final GridPos ABOVE = A.offset(0, 1, 0);
    private static final GridPos BESIDE = A.offset(1, 0, 0);
    private static final Material WATER = MaterialLibrary.WATER;

    private static PhysicalWorld vacuumWorld() {
        return new PhysicalWorld(WorldSettings.vacuum(5), MaterialRegistry.withLibrary());
    }

    private static void step(PhysicalWorld world, ConductionModel model, double dt) {
        model.step(new StepContext(world, dt, new DeterministicRandom(1), SimulationScope.everywhere()));
    }

    private static ThermalState state(Material material, double temperatureK) {
        return material.stateFor(material.specificEnthalpy(temperatureK));
    }

    private static double temperature(PhysicalWorld world, GridPos pos) {
        CellState s = world.readBlock(pos);
        return world.materials().get(s.material()).stateFor(s.specificEnthalpy()).temperatureK();
    }

    /** Water with all its properties but its viscosity, so nothing says how it would flow. */
    private static Material stillWater() {
        PhaseRegion liquid = WATER.thermal().regions().get(1);
        return Material.builder("test:still_water", "Water without a viscosity")
                .composition(Composition.pure(SpeciesCatalog.WATER))
                .region(new PhaseRegion(liquid.phase(), liquid.structure(), liquid.fromK(), liquid.toK(),
                        liquid.specificHeat(), liquid.conductivity(), liquid.density(), liquid.emissivity()))
                .build();
    }

    /** Puts water at A and at a neighbour, and returns the heat A gives the neighbour in one second. */
    private static double heatPassedOn(Material material, double temperatureK, GridPos other, double otherK) {
        PhysicalWorld world = vacuumWorld();
        world.materials().register(material);
        world.placeMaterial(A, material, temperatureK);
        world.placeMaterial(other, material, otherK);
        double before = world.readBlock(A).enthalpy();
        step(world, new ConductionModel(), 1.0);
        assertTrue(world.audit().balanced(), () -> world.audit().toString());
        return before - world.readBlock(A).enthalpy();
    }

    /** The conductance between two whole blocks of water that only conduct, in W/K. */
    private static double still(double aK, double bK) {
        return 1 / (0.5 / WATER.conductivity(state(WATER, aK)) + 0.5 / WATER.conductivity(state(WATER, bK)));
    }

    /**
     * The coefficient with which two whole blocks of water mix, in W/(m²·K), worked out from their properties
     * as the model's documentation gives it: {@code 0.135·ρc·v}, with v the buoyant speed between them.
     */
    private static double mixing(double lowerK, double upperK, boolean layered) {
        ThermalState lower = state(WATER, lowerK);
        ThermalState upper = state(WATER, upperK);
        double rhoLower = WATER.density(lower);
        double rhoUpper = WATER.density(upper);
        double reduced = PhysicalConstants.STANDARD_GRAVITY * Math.abs(rhoUpper - rhoLower)
                / ((rhoLower + rhoUpper) / 2);
        double viscosities = WATER.viscosity(lower) / rhoLower + WATER.viscosity(upper) / rhoUpper;
        double speed = Math.min(Math.sqrt(reduced), reduced / (9 * viscosities)) * (layered ? 1.0 : 0.5);
        double heatPerVolume = (rhoLower * WATER.specificHeat(lower) + rhoUpper * WATER.specificHeat(upper)) / 2;
        return ConductionModel.LIQUID_MIXING_EFFICIENCY * heatPerVolume * speed;
    }

    @Test
    void warmWaterRisesThroughCoolWaterButDoesNotSinkIntoIt() {
        double rising = heatPassedOn(WATER, 320.0, ABOVE, 290.0);
        double sinking = heatPassedOn(WATER, 290.0, ABOVE, 320.0);
        double sideways = heatPassedOn(WATER, 320.0, BESIDE, 290.0);
        double mixing = mixing(320.0, 290.0, true);
        assertEquals(mixing * 30.0, rising, 1e-9 * mixing * 30.0);
        assertEquals(-still(290.0, 320.0) * 30.0, sinking, 1e-7 * still(290.0, 320.0) * 30.0,
                "warm water over cool water stays layered and only conducts");
        assertEquals(rising / 2, sideways, 1e-9 * rising, "the denser water slumps under the lighter at half speed");
        assertTrue(rising > 10_000 * -sinking, "buoyancy carries far more heat than conduction: " + rising);
    }

    @Test
    void waterNearFreezingFloatsOnWarmerWater() {
        // Water is densest at 4 °C. At 1 °C it is lighter, so it stays on top of water at 4 °C...
        double floating = heatPassedOn(WATER, 277.15, ABOVE, 274.15);
        // A few joules out of tens of megajoules of enthalpy: rounding leaves them good to about one part in 1e8.
        assertEquals(still(277.15, 274.15) * 3.0, floating, 1e-7 * still(277.15, 274.15) * 3.0);
        // ...while water at 4 °C sinks into water at 10 °C.
        double sinking = heatPassedOn(WATER, 283.15, ABOVE, 277.15);
        double mixing = mixing(283.15, 277.15, true);
        assertEquals(mixing * 6.0, sinking, 1e-9 * mixing * 6.0);
        assertTrue(sinking > 1000 * floating, sinking + " W against " + floating + " W");
    }

    @Test
    void aColumnHeatedFromBelowEvensOutWithinMinutes() {
        double[] mixed = heatFromBelow(WATER);
        assertTrue(mixed[1] - mixed[0] < 2.0, "the column turns over: " + mixed[0] + " to " + mixed[1] + " K");
        double[] still = heatFromBelow(stillWater());
        assertTrue(still[1] > 329.9, "without a viscosity the water only conducts: " + still[1] + " K");
        assertTrue(still[0] < 290.1, "and the top stays cool: " + still[0] + " K");
    }

    /** Heats the bottom of a four-block column of a liquid, leaves it ten minutes, and returns its extremes. */
    private static double[] heatFromBelow(Material liquid) {
        PhysicalWorld world = vacuumWorld();
        world.materials().register(liquid);
        for (int y = 0; y < 4; y++) {
            world.placeMaterial(new GridPos(0, y, 0), liquid, y == 0 ? 330.0 : 290.0);
        }
        ConductionModel model = new ConductionModel();
        for (int i = 0; i < 42; i++) {
            step(world, model, 14.4);
            assertTrue(world.audit().balanced(), () -> world.audit().toString());
        }
        double coolest = Double.POSITIVE_INFINITY;
        double warmest = Double.NEGATIVE_INFINITY;
        for (int y = 0; y < 4; y++) {
            double t = temperature(world, new GridPos(0, y, 0));
            coolest = Math.min(coolest, t);
            warmest = Math.max(warmest, t);
        }
        return new double[] {coolest, warmest};
    }

    @Test
    void aPondCooledFromAboveTurnsOverThenFreezesFromTheTop() {
        // A column of water at 10 °C under a block of very cold copper.
        PhysicalWorld world = vacuumWorld();
        for (int y = 0; y < 4; y++) {
            world.placeMaterial(new GridPos(0, y, 0), WATER, 283.15);
        }
        GridPos top = new GridPos(0, 3, 0);
        world.placeMaterial(top.offset(0, 1, 0), MaterialLibrary.COPPER, 150.0);
        // The model weighs the water once a step, so steps far longer than the minutes the game takes would miss
        // the cooled water sinking; ten minutes is short enough.
        ConductionModel model = new ConductionModel();
        double tenMinutes = 600.0;
        for (int i = 0; i < 60; i++) {
            step(world, model, tenMinutes);
        }
        // Water cooled at the top sinks while it is above 4 °C, so the whole column cools together.
        for (int y = 0; y < 3; y++) {
            double t = temperature(world, new GridPos(0, y, 0));
            assertTrue(t > 276.9 && t < 278.0, "the column turned over to near 4 °C: " + t + " K at " + y);
        }
        double surface = temperature(world, top);
        assertTrue(surface > 273.15 && surface < 277.15, "the top is cooling past 4 °C: " + surface + " K");
        // Below 4 °C the cold water is lighter: it stays on top and only conducts, so the top cools to its
        // freezing point while the water beneath stays near 4 °C.
        for (int i = 0; i < 300; i++) {
            step(world, model, tenMinutes);
            assertTrue(world.audit().balanced(), () -> world.audit().toString());
        }
        ThermalState freezing = WATER.stateFor(world.readBlock(top).specificEnthalpy());
        assertTrue(freezing.inTransition(), "the top has started to freeze: " + freezing);
        for (int y = 0; y < 3; y++) {
            double t = temperature(world, new GridPos(0, y, 0));
            assertTrue(t > 277.0, "the water beneath stays near 4 °C: " + t + " K at " + y);
        }
    }

    @Test
    void lavaPassesItsHeatToTheIceBesideIt() {
        Material basalt = MaterialLibrary.BASALT;
        PhysicalWorld world = vacuumWorld();
        world.placeMaterial(A, basalt, 1450.0);
        world.placeMaterial(BESIDE, WATER, 263.15);
        double before = world.readBlock(BESIDE).enthalpy();
        step(world, new ConductionModel(), 1.0);
        double heat = world.readBlock(BESIDE).enthalpy() - before;

        // Churchill and Chu for a wall one block tall. The lava touching the ice is held at its freezing point,
        // 1423 K, the coldest it gets while liquid, and the heavier for it.
        ThermalState lava = state(basalt, 1450.0);
        double rho = basalt.density(lava);
        double coldest = basalt.thermal().regions().get(lava.region()).density().at(1423.0);
        double reduced = PhysicalConstants.STANDARD_GRAVITY * (coldest - rho) / rho;
        double nu = basalt.viscosity(lava) / rho;
        double alpha = basalt.conductivity(lava) / (rho * basalt.specificHeat(lava));
        double rayleigh = reduced / (nu * alpha);
        double root = 0.825 + 0.387 * Math.pow(rayleigh, 1.0 / 6.0)
                / Math.pow(1 + Math.pow(0.492 * alpha / nu, 9.0 / 16.0), 8.0 / 27.0);
        double coefficient = root * root * basalt.conductivity(lava);
        double ice = 0.5 / WATER.conductivity(state(WATER, 263.15));
        double expected = (1450.0 - 263.15) / (1 / coefficient + ice);
        assertEquals(expected, heat, 1e-9 * expected);
        double conducting = (1450.0 - 263.15) / (0.5 / basalt.conductivity(lava) + ice);
        assertTrue(heat > 2 * conducting, "flowing lava passes on twice the heat it could conduct: " + heat);
        assertTrue(world.audit().balanced(), () -> world.audit().toString());
    }

    @Test
    void lavaLiesStillOnAColdFloorButSinksOffAColdCeiling() {
        Material basalt = MaterialLibrary.BASALT;
        double conducting = 1 / (0.5 / basalt.conductivity(state(basalt, 1450.0))
                + 0.5 / MaterialLibrary.GRANITE.conductivity(state(MaterialLibrary.GRANITE, 300.0)));
        // Lava on cold granite: the lava the granite chills is heavier and stays on it.
        assertEquals(conducting * 1150.0, lavaLosing(A.offset(0, -1, 0)), 1e-9 * conducting * 1150.0);
        // Lava under cold granite: the chilled lava sinks away and warmer lava takes its place.
        assertTrue(lavaLosing(ABOVE) > 2 * conducting * 1150.0, "lava convects under a cold ceiling");
        assertTrue(lavaLosing(BESIDE) > 2 * conducting * 1150.0, "and beside a cold wall");
    }

    /** Puts lava at A and cold granite at a neighbour, and returns the heat the lava loses in one second. */
    private static double lavaLosing(GridPos granite) {
        PhysicalWorld world = vacuumWorld();
        world.placeMaterial(A, MaterialLibrary.BASALT, 1450.0);
        world.placeMaterial(granite, MaterialLibrary.GRANITE, 300.0);
        double before = world.readBlock(A).enthalpy();
        step(world, new ConductionModel(), 1.0);
        assertTrue(world.audit().balanced(), () -> world.audit().toString());
        return before - world.readBlock(A).enthalpy();
    }

    @Test
    void meltedSnowMixesWithWaterAsOneLiquid() {
        PhysicalWorld world = vacuumWorld();
        world.placeMaterial(A, MaterialLibrary.SNOW, 320.0);
        world.placeMaterial(ABOVE, WATER, 290.0);
        double before = world.readBlock(A).enthalpy();
        step(world, new ConductionModel(), 1.0);
        double heat = before - world.readBlock(A).enthalpy();
        double mixing = mixing(320.0, 290.0, true);
        assertEquals(mixing * 30.0, heat, 1e-9 * mixing * 30.0);
    }

    @Test
    void flowingLiquidAsksForNoFinerCells() {
        // Lava against cold granite: the granite needs finer cells to follow the heat flooding into it, but the
        // lava's own flow carries its heat to the face, so cells of lava would gain nothing.
        PhysicalWorld world = vacuumWorld();
        world.placeMaterial(A, MaterialLibrary.BASALT, 1450.0);
        world.placeMaterial(BESIDE, MaterialLibrary.GRANITE, 300.0);
        ThermalRefinement refinement = new ThermalRefinement(ThermalRefinement.Settings.DEFAULT);
        step(world, new ConductionModel(refinement), 1.0);
        refinement.endStep(true);
        refinement.update(world, SimulationScope.everywhere().sections(world, Domain.THERMAL), pos -> false);
        assertTrue(world.isRefined(BESIDE), "the granite is split");
        assertFalse(world.isRefined(A), "the lava is not");
    }
}
