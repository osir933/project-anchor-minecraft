package io.github.osir933.anchor.core.physics.thermal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.osir933.anchor.core.math.DeterministicRandom;
import io.github.osir933.anchor.core.matter.Material;
import io.github.osir933.anchor.core.matter.MaterialLibrary;
import io.github.osir933.anchor.core.matter.Phase;
import io.github.osir933.anchor.core.matter.ThermalState;
import io.github.osir933.anchor.core.model.SimulationScope;
import io.github.osir933.anchor.core.model.StepContext;
import io.github.osir933.anchor.core.space.GridPos;
import io.github.osir933.anchor.core.world.CellState;
import io.github.osir933.anchor.core.world.MaterialRegistry;
import io.github.osir933.anchor.core.world.PhysicalWorld;
import io.github.osir933.anchor.core.world.WorldSettings;
import org.junit.jupiter.api.Test;

class AtmosphereModelTest {

    private static final GridPos POS = new GridPos(1, 2, 3);

    private static PhysicalWorld vacuumWorld() {
        return new PhysicalWorld(WorldSettings.vacuum(3), MaterialRegistry.withLibrary());
    }

    private static ThermalState state(PhysicalWorld world, GridPos pos) {
        CellState s = world.readBlock(pos);
        return world.materials().get(s.material()).stateFor(s.specificEnthalpy());
    }

    private static void step(PhysicalWorld world, AtmosphereModel model, double dt) {
        model.step(new StepContext(world, dt, new DeterministicRandom(1), SimulationScope.everywhere()));
    }

    @Test
    void gasClosesTheExponentialFractionOfItsGap() {
        PhysicalWorld world = vacuumWorld();
        world.placeMaterial(POS, MaterialLibrary.AIR, 400.0);
        AtmosphereModel atmosphere = new AtmosphereModel(300.0, 290.0);
        CellState before = world.readBlock(POS);
        double target = before.mass() * MaterialLibrary.AIR.specificEnthalpy(290.0);
        step(world, atmosphere, 60.0);
        double expected = before.enthalpy() + (target - before.enthalpy()) * -StrictMath.expm1(-60.0 / 300.0);
        assertEquals(expected, world.readBlock(POS).enthalpy());
        assertEquals(before.enthalpy() - expected, atmosphere.lastStepEnergy(), 1e-6);
        assertTrue(atmosphere.lastStepEnergy() > 0, "cooling hands heat to the environment");
        assertTrue(world.audit().balanced(), () -> world.audit().toString());
    }

    @Test
    void manyShortStepsMatchOneLongStep() {
        PhysicalWorld a = vacuumWorld();
        PhysicalWorld b = vacuumWorld();
        a.placeMaterial(POS, MaterialLibrary.AIR, 250.0);
        b.placeMaterial(POS, MaterialLibrary.AIR, 250.0);
        AtmosphereModel atmosphere = new AtmosphereModel(300.0, 300.0);
        step(a, atmosphere, 600.0);
        for (int i = 0; i < 100; i++) {
            step(b, atmosphere, 6.0);
        }
        double ha = a.readBlock(POS).enthalpy();
        assertEquals(ha, b.readBlock(POS).enthalpy(), 1e-9 * Math.abs(ha));
    }

    @Test
    void solidsAndLiquidsAreLeftAlone() {
        PhysicalWorld world = vacuumWorld();
        world.placeMaterial(POS, MaterialLibrary.GRANITE, 400.0);
        world.placeMaterial(POS.offset(1, 0, 0), MaterialLibrary.WATER, 330.0);
        String before = world.stateHash();
        step(world, new AtmosphereModel(300.0, 280.0), 600.0);
        assertEquals(before, world.stateHash());
    }

    @Test
    void steamStaysSteamInTheCold() {
        PhysicalWorld world = vacuumWorld();
        world.placeMaterial(POS, MaterialLibrary.WATER, 450.0);
        AtmosphereModel atmosphere = new AtmosphereModel(300.0, 280.0);
        Material water = MaterialLibrary.WATER;
        for (int i = 0; i < 50; i++) {
            step(world, atmosphere, 600.0);
            ThermalState s = state(world, POS);
            assertEquals(Phase.GAS, water.dominantPhase(s));
            assertTrue(s.temperatureK() >= 373.124 - 1e-9);
        }
        assertEquals(373.124, state(world, POS).temperatureK(), 1e-6);
        assertTrue(world.audit().balanced(), () -> world.audit().toString());
    }

    @Test
    void eachSectionHasItsOwnWeather() {
        PhysicalWorld world = vacuumWorld();
        GridPos other = new GridPos(40, 2, 3);
        world.placeMaterial(POS, MaterialLibrary.AIR, 300.0);
        world.placeMaterial(other, MaterialLibrary.AIR, 300.0);
        AtmosphereModel atmosphere = new AtmosphereModel(300.0, 250.0);
        atmosphere.setEnvironment(other.sectionKey(), 330.0);
        assertEquals(330.0, atmosphere.environment(other.sectionKey()));
        assertEquals(250.0, atmosphere.environment(POS.sectionKey()));
        step(world, atmosphere, 60.0);
        assertTrue(state(world, POS).temperatureK() < 300.0);
        assertTrue(state(world, other).temperatureK() > 300.0);
        atmosphere.removeEnvironment(other.sectionKey());
        assertEquals(250.0, atmosphere.environment(other.sectionKey()));
    }

    @Test
    void airAlreadyAtTheEnvironmentIsNotTouched() {
        PhysicalWorld world = vacuumWorld();
        world.fill(new GridPos(0, 0, 0), new GridPos(15, 15, 15), MaterialLibrary.AIR, 290.0);
        long version = world.section(POS.sectionKey()).version();
        String before = world.stateHash();
        step(world, new AtmosphereModel(300.0, 290.0), 60.0);
        assertEquals(before, world.stateHash());
        assertEquals(version, world.section(POS.sectionKey()).version());
    }

    @Test
    void nonsenseSettingsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AtmosphereModel(0.0, 290.0));
        assertThrows(IllegalArgumentException.class, () -> new AtmosphereModel(300.0, -1.0));
        AtmosphereModel atmosphere = new AtmosphereModel(300.0, 290.0);
        assertThrows(IllegalArgumentException.class, () -> atmosphere.setEnvironment(0L, Double.NaN));
    }
}
