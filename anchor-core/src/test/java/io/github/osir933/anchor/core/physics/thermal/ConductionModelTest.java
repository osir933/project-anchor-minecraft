package io.github.osir933.anchor.core.physics.thermal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.osir933.anchor.core.math.DeterministicRandom;
import io.github.osir933.anchor.core.matter.Element;
import io.github.osir933.anchor.core.matter.Material;
import io.github.osir933.anchor.core.matter.MaterialLibrary;
import io.github.osir933.anchor.core.matter.ThermalState;
import io.github.osir933.anchor.core.model.Domain;
import io.github.osir933.anchor.core.model.Scheduler;
import io.github.osir933.anchor.core.model.SimulationScope;
import io.github.osir933.anchor.core.model.StepContext;
import io.github.osir933.anchor.core.space.CellId;
import io.github.osir933.anchor.core.space.GridPos;
import io.github.osir933.anchor.core.world.CellState;
import io.github.osir933.anchor.core.world.MaterialRegistry;
import io.github.osir933.anchor.core.world.PhysicalWorld;
import io.github.osir933.anchor.core.world.Provenance;
import io.github.osir933.anchor.core.world.Totals;
import io.github.osir933.anchor.core.world.WorldSettings;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConductionModelTest {

    private static final GridPos A = new GridPos(0, 0, 0);
    private static final GridPos B = new GridPos(1, 0, 0);

    private static PhysicalWorld vacuumWorld() {
        return new PhysicalWorld(WorldSettings.vacuum(11), MaterialRegistry.withLibrary());
    }

    private static double temperature(PhysicalWorld world, CellState s) {
        return world.materials().get(s.material()).stateFor(s.specificEnthalpy()).temperatureK();
    }

    private static double temperature(PhysicalWorld world, GridPos pos) {
        return temperature(world, world.readBlock(pos));
    }

    private static ThermalGraph graph(PhysicalWorld world) {
        return ThermalGraph.build(world, SimulationScope.everywhere().sections(world, Domain.THERMAL),
                new Isotherms());
    }

    private static void step(PhysicalWorld world, ConductionModel model, double dt) {
        step(world, model, dt, SimulationScope.everywhere());
    }

    private static void step(PhysicalWorld world, ConductionModel model, double dt, SimulationScope scope) {
        model.step(new StepContext(world, dt, new DeterministicRandom(1), scope));
    }

    @Test
    void twoBlocksRelaxExactlyAsTheDiscreteSolutionSays() {
        PhysicalWorld world = vacuumWorld();
        world.placeMaterial(A, MaterialLibrary.GRANITE, 400.0);
        world.placeMaterial(B, MaterialLibrary.GRANITE, 300.0);
        ConductionModel model = new ConductionModel();
        double capacity = 2630.0 * 775.0;
        double conductance = 1.0 / (0.5 / 2.79 + 0.5 / 2.79);
        double h = 1000.0;
        int steps = 40;
        for (int i = 0; i < steps; i++) {
            step(world, model, h);
        }
        double expected = 100.0 * Math.pow(1 - 2 * conductance * h / capacity, steps);
        double actual = temperature(world, A) - temperature(world, B);
        assertEquals(expected, actual, 1e-9 * 100);
        assertEquals(350.0, (temperature(world, A) + temperature(world, B)) / 2, 1e-9);
        assertTrue(world.audit().balanced());
        assertEquals(Provenance.SIMULATED, world.readBlock(A).provenance());
    }

    @Test
    void heatCrossesRefinementBoundariesWithoutLoss() {
        PhysicalWorld world = vacuumWorld();
        world.fill(new GridPos(0, 0, 0), new GridPos(3, 0, 0), MaterialLibrary.COPPER, 300.0);
        world.refine(new CellId(A, 2, 3, 1, 2));
        world.refine(new CellId(new GridPos(2, 0, 0), 1, 0, 0, 0));
        CellId hot = new CellId(A, 2, 3, 1, 2);
        CellState s = world.readLeaf(hot);
        world.writeLeaf(hot, new CellState(s.material(), s.mass(), s.enthalpy() + 2e6, 0, Provenance.SIMULATED));
        world.recordExchange(new Totals(0, 2e6, 2e6, new EnumMap<>(Element.class)), "test heater");
        double hottest = temperature(world, world.readLeaf(hot));
        ConductionModel model = new ConductionModel();
        for (int i = 0; i < 60; i++) {
            step(world, model, 5.0);
            assertTrue(world.audit().balanced(), () -> world.audit().toString());
        }
        double[] range = {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        world.forEachLeaf((cell, state) -> {
            if (state.mass() > 0) {
                double t = temperature(world, state);
                range[0] = Math.min(range[0], t);
                range[1] = Math.max(range[1], t);
            }
        });
        assertTrue(range[0] >= 300.0 - 1e-9, "no cell may cool below the coldest start");
        assertTrue(range[1] < hottest, "the hot spot spreads out");
        assertTrue(temperature(world, new GridPos(3, 0, 0)) > 300.0, "heat reached the far block");
    }

    @Test
    void uniformTemperaturesStayExactlyUnchanged() {
        PhysicalWorld world = vacuumWorld();
        world.fill(new GridPos(-2, 0, -2), new GridPos(2, 2, 2), MaterialLibrary.GRANITE, 290.0);
        world.refine(new CellId(new GridPos(0, 1, 0), 3, 1, 2, 3));
        String before = world.stateHash();
        ConductionModel model = new ConductionModel();
        for (int i = 0; i < 5; i++) {
            step(world, model, 10.0);
        }
        assertEquals(before, world.stateHash());
    }

    @Test
    void iceMeltsAtConstantTemperature() {
        PhysicalWorld world = vacuumWorld();
        world.fill(new GridPos(-1, -1, -1), new GridPos(1, 1, 1), MaterialLibrary.GRANITE, 300.0);
        world.placeMaterial(A, MaterialLibrary.WATER, 263.15);
        ConductionModel model = new ConductionModel();
        Material water = MaterialLibrary.WATER;
        boolean sawMelting = false;
        for (int i = 0; i < 400 && !sawMelting; i++) {
            step(world, model, 2000.0);
            CellState ice = world.readBlock(A);
            ThermalState state = water.stateFor(ice.specificEnthalpy());
            if (state.inTransition()) {
                sawMelting = true;
                assertEquals(273.15, state.temperatureK());
                assertTrue(state.transitionFraction() > 0 && state.transitionFraction() < 1);
            } else {
                assertTrue(state.temperatureK() < 273.15 + 1e-9);
            }
        }
        assertTrue(sawMelting, "the ice should start melting");
        assertTrue(world.audit().balanced());
    }

    @Test
    void sectionsOutsideTheScopeStandStill() {
        PhysicalWorld world = vacuumWorld();
        GridPos inside = new GridPos(15, 0, 0);
        GridPos outside = new GridPos(16, 0, 0);
        world.placeMaterial(inside, MaterialLibrary.COPPER, 500.0);
        world.placeMaterial(inside.offset(-1, 0, 0), MaterialLibrary.COPPER, 300.0);
        world.placeMaterial(outside, MaterialLibrary.COPPER, 300.0);
        SimulationScope scope = SimulationScope.of(Map.of(Domain.THERMAL, List.of(inside.sectionKey())));
        CellState frozen = world.readBlock(outside);
        ConductionModel model = new ConductionModel();
        for (int i = 0; i < 20; i++) {
            step(world, model, 50.0, scope);
        }
        assertEquals(frozen, world.readBlock(outside));
        assertTrue(temperature(world, inside.offset(-1, 0, 0)) > 300.0);
        assertTrue(world.audit().balanced());
    }

    @Test
    void quietSectionsAreSkipped() {
        PhysicalWorld world = vacuumWorld();
        world.fill(new GridPos(0, 0, 0), new GridPos(47, 15, 15), MaterialLibrary.GRANITE, 280.0);
        assertEquals(0, graph(world).leafCount);
        world.placeMaterial(new GridPos(40, 5, 5), MaterialLibrary.GRANITE, 300.0);
        assertEquals(4096, graph(world).leafCount, "only the edited section is simulated: its neighbour "
                + "touches it where it is still at 280 K");
        world.placeMaterial(new GridPos(32, 5, 5), MaterialLibrary.GRANITE, 300.0);
        assertEquals(2 * 4096, graph(world).leafCount, "a warm block on the shared face brings the neighbour in");
    }

    @Test
    void facesAreFoundOnceAcrossLevels() {
        PhysicalWorld world = vacuumWorld();
        world.placeMaterial(A, MaterialLibrary.IRON, 310.0);
        world.placeMaterial(B, MaterialLibrary.IRON, 300.0);
        world.refine(new CellId(B, 2, 0, 0, 0));
        ThermalGraph g = graph(world);
        double areaAB = 0;
        int ia = -1;
        for (int i = 0; i < g.leafCount; i++) {
            if (g.cell(i).equals(CellId.of(A))) {
                ia = i;
            }
        }
        for (int f = 0; f < g.faceCount; f++) {
            if (g.faceA[f] == ia && g.cell(g.faceB[f]).block().equals(B)) {
                areaAB += g.faceArea[f];
            }
        }
        assertEquals(1.0, areaAB, 1e-15, "A's east face is fully covered by B's west leaves");
    }

    @Test
    void surfacesInGasLoseHeatByConvection() {
        PhysicalWorld world = vacuumWorld();
        world.placeMaterial(A, MaterialLibrary.GRANITE, 350.0);
        world.placeMaterial(B, MaterialLibrary.AIR, 300.0);
        double before = world.readBlock(A).enthalpy();
        step(world, new ConductionModel(), 1.0);
        double conductance = 1.0 / (1.0 / ConductionModel.CONVECTION_COEFFICIENT + 0.5 / 2.79);
        assertEquals(-conductance * 50.0, world.readBlock(A).enthalpy() - before, 1e-9 * conductance * 50.0);
        assertTrue(world.audit().balanced(), () -> world.audit().toString());
    }

    @Test
    void warmGasRisesButDoesNotSink() {
        GridPos above = A.offset(0, 1, 0);
        double rising = heatGoingUp(330.0, 300.0, above);
        double sinking = heatGoingUp(300.0, 330.0, above);
        double sideways = heatGoingUp(330.0, 300.0, B);
        double mixing = ConductionModel.BUOYANT_MIXING_COEFFICIENT * Math.sqrt(30.0);
        assertEquals(mixing * 30.0, rising, 1e-9 * mixing * 30.0);
        Material air = MaterialLibrary.AIR;
        double still = 1.0 / (0.5 / air.conductivity(air.stateFor(air.specificEnthalpy(330.0)))
                + 0.5 / air.conductivity(air.stateFor(air.specificEnthalpy(300.0))));
        assertEquals(-still * 30.0, sinking, 1e-9 * still * 30.0);
        assertEquals(still * 30.0, sideways, 1e-9 * still * 30.0);
    }

    /** Puts warm air at A and cooler air at a neighbour, and returns the heat A loses in one second. */
    private static double heatGoingUp(double lowerK, double upperK, GridPos other) {
        PhysicalWorld world = vacuumWorld();
        world.placeMaterial(A, MaterialLibrary.AIR, lowerK);
        world.placeMaterial(other, MaterialLibrary.AIR, upperK);
        double before = world.readBlock(A).enthalpy();
        step(world, new ConductionModel(), 1.0);
        assertTrue(world.audit().balanced(), () -> world.audit().toString());
        return before - world.readBlock(A).enthalpy();
    }

    @Test
    void theSchedulerRunsConductionDeterministically() {
        assertEquals(run(), run());
    }

    private static String run() {
        PhysicalWorld world = new PhysicalWorld(WorldSettings.airAt20C(9), MaterialRegistry.withLibrary());
        world.fill(new GridPos(0, 0, 0), new GridPos(5, 1, 5), MaterialLibrary.BASALT, 1400.0);
        world.placeMaterial(new GridPos(2, 2, 2), MaterialLibrary.WATER, 280.0);
        world.refine(new CellId(new GridPos(2, 1, 2), 2, 1, 3, 1));
        Scheduler scheduler = new Scheduler(0.05, 10_000_000, 20, 1);
        scheduler.register(new ConductionModel());
        for (int i = 0; i < 30; i++) {
            assertTrue(scheduler.tick(world).conserved());
        }
        return world.stateHash();
    }
}
