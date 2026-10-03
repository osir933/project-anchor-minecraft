package io.github.osir933.anchor.core.physics.thermal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.osir933.anchor.core.math.DeterministicRandom;
import io.github.osir933.anchor.core.matter.MaterialLibrary;
import io.github.osir933.anchor.core.model.Domain;
import io.github.osir933.anchor.core.model.SimulationScope;
import io.github.osir933.anchor.core.model.StepContext;
import io.github.osir933.anchor.core.space.CellId;
import io.github.osir933.anchor.core.space.GridPos;
import io.github.osir933.anchor.core.world.CellState;
import io.github.osir933.anchor.core.world.MaterialRegistry;
import io.github.osir933.anchor.core.world.PhysicalWorld;
import io.github.osir933.anchor.core.world.Provenance;
import io.github.osir933.anchor.core.world.WorldSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HeatSourceModelTest {

    private static final GridPos POS = new GridPos(3, 4, 5);

    private static PhysicalWorld vacuumWorld() {
        return new PhysicalWorld(WorldSettings.vacuum(5), MaterialRegistry.withLibrary());
    }

    private static double temperature(PhysicalWorld world, GridPos pos) {
        CellState s = world.readBlock(pos);
        return world.materials().get(s.material()).stateFor(s.specificEnthalpy()).temperatureK();
    }

    private static void step(PhysicalWorld world, HeatSourceModel model, double dt) {
        step(world, model, dt, SimulationScope.everywhere());
    }

    private static void step(PhysicalWorld world, HeatSourceModel model, double dt, SimulationScope scope) {
        model.step(new StepContext(world, dt, new DeterministicRandom(1), scope));
    }

    @Test
    void powerLimitsTheHeatReleased() {
        PhysicalWorld world = vacuumWorld();
        world.placeMaterial(POS, MaterialLibrary.GRANITE, 300.0);
        HeatSourceModel sources = new HeatSourceModel();
        sources.put(POS, new HeatSourceModel.Source(1000.0, 500.0));
        double before = world.readBlock(POS).enthalpy();
        step(world, sources, 10.0);
        assertEquals(5000.0, world.readBlock(POS).enthalpy() - before, 1e-6);
        assertEquals(5000.0, sources.lastStep().energyJ(), 1e-9);
        assertEquals(1, sources.lastStep().blocks());
        assertEquals(Provenance.SIMULATED, world.readBlock(POS).provenance());
        assertTrue(world.audit().balanced(), () -> world.audit().toString());
    }

    @Test
    void heatsUpToItsTemperatureAndNoFurther() {
        PhysicalWorld world = vacuumWorld();
        world.placeMaterial(POS, MaterialLibrary.GRANITE, 300.0);
        HeatSourceModel sources = new HeatSourceModel();
        sources.put(POS, new HeatSourceModel.Source(350.0, 1e6));
        for (int i = 0; i < 5; i++) {
            step(world, sources, 60.0);
            assertTrue(temperature(world, POS) <= 350.0 + 1e-9);
        }
        assertEquals(350.0, temperature(world, POS), 1e-9);
        assertEquals(0.0, sources.lastStep().energyJ());
        assertTrue(world.audit().balanced(), () -> world.audit().toString());
    }

    @Test
    void sourcesNeverCool() {
        PhysicalWorld world = vacuumWorld();
        world.placeMaterial(POS, MaterialLibrary.GRANITE, 600.0);
        HeatSourceModel sources = new HeatSourceModel();
        sources.put(POS, new HeatSourceModel.Source(350.0, 1e6));
        CellState before = world.readBlock(POS);
        step(world, sources, 60.0);
        assertEquals(before, world.readBlock(POS));
        assertEquals(0, sources.lastStep().blocks());
    }

    @Test
    void theCellsOfARefinedBlockShareThePower() {
        PhysicalWorld world = vacuumWorld();
        world.placeMaterial(POS, MaterialLibrary.COPPER, 300.0);
        world.refine(new CellId(POS, 1, 0, 0, 0));
        List<Double> before = new ArrayList<>();
        world.refinedBlock(POS).forEachLeaf((cell, state) -> before.add(state.enthalpy()));
        HeatSourceModel sources = new HeatSourceModel();
        sources.put(POS, new HeatSourceModel.Source(400.0, 800.0));
        step(world, sources, 1.0);
        List<Double> after = new ArrayList<>();
        world.refinedBlock(POS).forEachLeaf((cell, state) -> after.add(state.enthalpy()));
        assertEquals(8, after.size());
        for (int i = 0; i < 8; i++) {
            assertEquals(100.0, after.get(i) - before.get(i), 1e-6);
        }
        assertTrue(world.audit().balanced(), () -> world.audit().toString());
    }

    @Test
    void sourcesOutsideTheScopeWait() {
        PhysicalWorld world = vacuumWorld();
        GridPos far = new GridPos(100, 4, 5);
        world.placeMaterial(POS, MaterialLibrary.GRANITE, 300.0);
        world.placeMaterial(far, MaterialLibrary.GRANITE, 300.0);
        HeatSourceModel sources = new HeatSourceModel();
        sources.put(POS, new HeatSourceModel.Source(400.0, 100.0));
        sources.put(far, new HeatSourceModel.Source(400.0, 100.0));
        CellState farBefore = world.readBlock(far);
        step(world, sources, 1.0, SimulationScope.of(Map.of(Domain.THERMAL, List.of(POS.sectionKey()))));
        assertEquals(farBefore, world.readBlock(far));
        assertTrue(temperature(world, POS) > 300.0);
    }

    @Test
    void removingASectionsSourcesLeavesTheOthers() {
        HeatSourceModel sources = new HeatSourceModel();
        GridPos far = new GridPos(100, 4, 5);
        sources.put(POS, new HeatSourceModel.Source(400.0, 100.0));
        sources.put(POS.offset(1, 0, 0), new HeatSourceModel.Source(400.0, 100.0));
        sources.put(far, new HeatSourceModel.Source(400.0, 100.0));
        sources.removeSection(POS.sectionKey());
        assertEquals(List.of(far), List.copyOf(sources.sources().keySet()));
        sources.remove(far);
        assertTrue(sources.sources().isEmpty());
    }

    @Test
    void targetsOutsideTheMaterialDataAreReported() {
        PhysicalWorld world = vacuumWorld();
        world.placeMaterial(POS, MaterialLibrary.GLASS, 300.0);
        HeatSourceModel sources = new HeatSourceModel();
        sources.put(POS, new HeatSourceModel.Source(1500.0, 100.0));
        step(world, sources, 1.0);
        assertFalse(sources.checkValidity(world).isEmpty());
    }

    @Test
    void nonsenseSourcesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new HeatSourceModel.Source(0.0, 100.0));
        assertThrows(IllegalArgumentException.class, () -> new HeatSourceModel.Source(Double.NaN, 100.0));
        assertThrows(IllegalArgumentException.class, () -> new HeatSourceModel.Source(400.0, -1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new HeatSourceModel.Source(400.0, Double.POSITIVE_INFINITY));
    }
}
