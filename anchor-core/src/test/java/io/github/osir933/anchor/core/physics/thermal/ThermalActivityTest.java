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
import io.github.osir933.anchor.core.space.SectionPos;
import io.github.osir933.anchor.core.world.CellState;
import io.github.osir933.anchor.core.world.MaterialRegistry;
import io.github.osir933.anchor.core.world.PhysicalWorld;
import io.github.osir933.anchor.core.world.Provenance;
import io.github.osir933.anchor.core.world.WorldSettings;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import org.junit.jupiter.api.Test;

class ThermalActivityTest {

    private static PhysicalWorld vacuumWorld() {
        return new PhysicalWorld(WorldSettings.vacuum(7), MaterialRegistry.withLibrary());
    }

    @Test
    void anAwakeSectionBringsItsNeighboursIntoScope() {
        PhysicalWorld world = vacuumWorld();
        long centre = SectionPos.pack(0, 0, 0);
        long east = SectionPos.pack(1, 0, 0);
        long up = SectionPos.pack(0, 1, 0);
        long diagonal = SectionPos.pack(1, 1, 0);
        for (long key : List.of(centre, east, up, diagonal)) {
            world.addSection(key);
        }
        ThermalActivity activity = new ThermalActivity(world.materials(), 1e-3, 3);
        assertTrue(activity.scope(world).isEmpty());
        activity.wake(centre);
        assertTrue(activity.isAwake(centre));
        assertEquals(List.of(centre, up, east), List.copyOf(activity.scope(world)));
        activity.wake(SectionPos.pack(50, 0, 0));
        assertEquals(3, activity.scope(world).size(), "sections that do not exist stay out of scope");
    }

    @Test
    void calmSectionsFallAsleepAfterTheirCalmSteps() {
        PhysicalWorld world = vacuumWorld();
        long key = SectionPos.pack(0, 0, 0);
        world.addSection(key);
        ThermalActivity activity = new ThermalActivity(world.materials(), 1e-3, 3);
        activity.wake(key);
        activity.endStep(world, 1.0);
        activity.endStep(world, 1.0);
        assertTrue(activity.isAwake(key));
        activity.endStep(world, 1.0);
        assertFalse(activity.isAwake(key));
        assertTrue(activity.awakeSections().isEmpty());
    }

    @Test
    void changeFasterThanTheCalmRateKeepsSectionsAwakeAndWakesOthers() {
        PhysicalWorld world = vacuumWorld();
        GridPos a = new GridPos(0, 0, 0);
        GridPos b = new GridPos(40, 0, 0);
        world.placeMaterial(a, MaterialLibrary.GRANITE, 300.0);
        world.placeMaterial(b, MaterialLibrary.GRANITE, 300.0);
        ThermalActivity activity = new ThermalActivity(world.materials(), 1e-3, 2);
        world.addWriteListener(activity);
        activity.wake(a.sectionKey());
        double capacity = 2630.0 * 775.0;
        heat(world, a, 0.5e-3 * capacity);
        heat(world, b, 2e-3 * capacity);
        activity.endStep(world, 1.0);
        assertTrue(activity.isAwake(a.sectionKey()), "0.5 mK in a second is calm, but one calm step is not enough");
        assertTrue(activity.isAwake(b.sectionKey()), "2 mK in a second wakes a sleeping section");
        heat(world, b, 2e-3 * capacity);
        activity.endStep(world, 1.0);
        assertFalse(activity.isAwake(a.sectionKey()));
        assertTrue(activity.isAwake(b.sectionKey()));
    }

    @Test
    void latentHeatCountsAsChangeAndSoDoesNewMatter() {
        PhysicalWorld world = vacuumWorld();
        GridPos ice = new GridPos(0, 0, 0);
        GridPos other = new GridPos(40, 0, 0);
        world.placeMaterial(ice, MaterialLibrary.WATER, 273.15);
        world.placeMaterial(other, MaterialLibrary.GRANITE, 300.0);
        ThermalActivity activity = new ThermalActivity(world.materials(), 1e-3, 5);
        world.addWriteListener(activity);
        heat(world, ice, 1e4);
        CellState s = world.readBlock(other);
        world.writeLeaf(CellId.of(other), new CellState(s.material(), s.mass() * 0.5, s.enthalpy() * 0.5, 0,
                Provenance.SIMULATED));
        activity.endStep(world, 1.0);
        assertTrue(activity.isAwake(ice.sectionKey()), "melting at a constant temperature is still change");
        assertTrue(activity.isAwake(other.sectionKey()), "matter appearing or vanishing always wakes");
    }

    @Test
    void heatThatComesAndGoesInOneStepIsCalm() {
        PhysicalWorld world = vacuumWorld();
        GridPos flame = new GridPos(0, 0, 0);
        world.placeMaterial(flame, MaterialLibrary.GRANITE, 300.0);
        ThermalActivity activity = new ThermalActivity(world.materials(), 1e-3, 2);
        world.addWriteListener(activity);
        activity.wake(flame.sectionKey());
        for (int i = 0; i < 2; i++) {
            heat(world, flame, 1e6);
            heat(world, flame, -1e6);
            activity.endStep(world, 1.0);
        }
        assertFalse(activity.isAwake(flame.sectionKey()), "a flame balanced by its losses lets a section sleep");
        heat(world, flame, 1e6);
        activity.endStep(world, 1.0);
        assertTrue(activity.isAwake(flame.sectionKey()), "net heat wakes it again");
    }

    @Test
    void forgettingASectionPutsItToSleep() {
        PhysicalWorld world = vacuumWorld();
        ThermalActivity activity = new ThermalActivity(world.materials(), 1e-3, 5);
        activity.wake(1L);
        activity.wake(2L);
        activity.forget(1L);
        assertEquals(List.of(2L), List.copyOf(activity.awakeSections()));
        activity.clear();
        assertTrue(activity.awakeSections().isEmpty());
    }

    @Test
    void aHotSpotWakesWhatItHeatsAndEverythingSleepsOnceItHasSpread() {
        PhysicalWorld world = vacuumWorld();
        world.fill(new GridPos(12, 0, 0), new GridPos(19, 0, 0), MaterialLibrary.COPPER, 300.0);
        GridPos hot = new GridPos(15, 0, 0);
        world.placeMaterial(hot, MaterialLibrary.COPPER, 400.0);
        long west = hot.sectionKey();
        long east = new GridPos(16, 0, 0).sectionKey();
        ThermalActivity activity = new ThermalActivity(world.materials(), 1e-4, 5);
        world.addWriteListener(activity);
        activity.wake(west);
        ConductionModel conduction = new ConductionModel();
        boolean eastWoke = false;
        int steps = 0;
        while (!activity.awakeSections().isEmpty() && steps < 5000) {
            SortedSet<Long> scope = activity.scope(world);
            conduction.step(new StepContext(world, 100.0, new DeterministicRandom(1),
                    SimulationScope.of(Map.of(Domain.THERMAL, scope))));
            activity.endStep(world, 100.0);
            eastWoke |= activity.isAwake(east);
            assertTrue(world.audit().balanced(), () -> world.audit().toString());
            steps++;
        }
        assertTrue(eastWoke, "heat crossing into the next section wakes it");
        assertTrue(activity.awakeSections().isEmpty(), "everything falls asleep once the heat has spread");
        double t = temperature(world, new GridPos(19, 0, 0));
        assertTrue(t > 300.0 + 1.0, "the far end warmed up: " + t);
    }

    @Test
    void aRefinedBlockChangesByTheAverageChangeOfItsMatter() {
        // Heat moving between a refined block's cells counts, however it nets out, but measured against the
        // whole block, so refining a block does not keep its section awake longer than the whole block would.
        PhysicalWorld world = vacuumWorld();
        long key = SectionPos.pack(0, 0, 0);
        GridPos pos = new GridPos(1, 1, 1);
        world.placeMaterial(pos, MaterialLibrary.GRANITE, 300.0);
        world.refine(CellId.of(pos).child(0));
        double capacity = world.readBlock(pos).mass() * MaterialLibrary.GRANITE.minSpecificHeat();
        ThermalActivity activity = new ThermalActivity(world.materials(), 1e-3, 1);
        world.addWriteListener(activity);
        heatLeaf(world, CellId.of(pos).child(0), 0.6e-3 * capacity);
        heatLeaf(world, CellId.of(pos).child(7), -0.6e-3 * capacity);
        activity.endStep(world, 1.0);
        assertTrue(activity.isAwake(key), "1.2 mK moved inside the block in a second");
        heatLeaf(world, CellId.of(pos).child(3), 0.8e-3 * capacity);
        activity.endStep(world, 1.0);
        assertFalse(activity.isAwake(key), "6.4 mK in an eighth of the block is 0.8 mK for the block as a whole");
    }

    @Test
    void heatFromOutsideTheWorldDoesNotCount() {
        // The sun warming a block is declared as forced, so a section only the sky changes can sleep; heat that
        // moves on from there still counts.
        PhysicalWorld world = vacuumWorld();
        long key = SectionPos.pack(0, 0, 0);
        GridPos pos = new GridPos(1, 1, 1);
        world.placeMaterial(pos, MaterialLibrary.GRANITE, 300.0);
        double capacity = world.readBlock(pos).mass() * MaterialLibrary.GRANITE.minSpecificHeat();
        ThermalActivity activity = new ThermalActivity(world.materials(), 1e-3, 1);
        world.addWriteListener(activity);
        heat(world, pos, 0.5 * capacity);
        activity.forced(key, pos.indexInSection(), 0.5 * capacity);
        activity.endStep(world, 1.0);
        assertFalse(activity.isAwake(key), "half a kelvin of sunshine in a second wakes nothing");
        heat(world, pos, 0.5 * capacity);
        activity.forced(key, pos.indexInSection(), 0.4 * capacity);
        activity.endStep(world, 1.0);
        assertTrue(activity.isAwake(key), "a tenth of a kelvin from elsewhere does");

        GridPos refined = new GridPos(5, 5, 5);
        world.placeMaterial(refined, MaterialLibrary.GRANITE, 300.0);
        world.refine(CellId.of(refined).child(0));
        activity.clear();
        CellId top = CellId.of(refined).child(2);
        heatLeaf(world, top, 0.5 * capacity);
        activity.forced(key, refined.indexInSection(), top, 0.5 * capacity);
        activity.endStep(world, 1.0);
        assertFalse(activity.isAwake(key), "nor in a refined block's cell");
        // Forced heat only counts for the step it came in.
        heatLeaf(world, top, 0.01 * capacity);
        activity.endStep(world, 1.0);
        assertTrue(activity.isAwake(key));
    }

    @Test
    void nonsenseSettingsAreRejected() {
        PhysicalWorld world = vacuumWorld();
        MaterialRegistry materials = world.materials();
        assertThrows(IllegalArgumentException.class, () -> new ThermalActivity(materials, -1.0, 5));
        assertThrows(IllegalArgumentException.class, () -> new ThermalActivity(materials, 1e-3, 0));
        ThermalActivity activity = new ThermalActivity(materials, 1e-3, 5);
        assertThrows(IllegalArgumentException.class, () -> activity.endStep(world, 0.0));
    }

    /** Adds heat to a block the way a model would. */
    private static void heat(PhysicalWorld world, GridPos pos, double joules) {
        CellState s = world.readBlock(pos);
        world.writeLeaf(CellId.of(pos), new CellState(s.material(), s.mass(), s.enthalpy() + joules, 0,
                Provenance.SIMULATED));
    }

    /** Adds heat to a cell of a refined block the way a model would. */
    private static void heatLeaf(PhysicalWorld world, CellId cell, double joules) {
        CellState s = world.readLeaf(cell);
        world.writeLeaf(cell, new CellState(s.material(), s.mass(), s.enthalpy() + joules, 0, Provenance.SIMULATED));
    }

    private static double temperature(PhysicalWorld world, GridPos pos) {
        CellState s = world.readBlock(pos);
        return world.materials().get(s.material()).stateFor(s.specificEnthalpy()).temperatureK();
    }
}
