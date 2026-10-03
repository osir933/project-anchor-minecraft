package io.github.osir933.anchor.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.osir933.anchor.core.matter.MaterialLibrary;
import io.github.osir933.anchor.core.space.CellId;
import io.github.osir933.anchor.core.space.GridPos;
import io.github.osir933.anchor.core.world.CellState;
import io.github.osir933.anchor.core.world.MaterialRegistry;
import io.github.osir933.anchor.core.world.PhysicalWorld;
import io.github.osir933.anchor.core.world.Provenance;
import io.github.osir933.anchor.core.world.WorldSettings;
import java.util.List;
import org.junit.jupiter.api.Test;

class SchedulerTest {

    private static final GridPos A = new GridPos(0, 0, 0);
    private static final GridPos B = new GridPos(1, 0, 0);

    /** Moves heat from A to B at a fixed rate; a stand-in for a real conduction model. */
    private static final class HeatPump implements PhysicsModel {
        private final String id;
        private final long cost;
        private final double watts;
        private final int priority;
        private double leak;

        HeatPump(String id, long cost, double watts, int priority) {
            this.id = id;
            this.cost = cost;
            this.watts = watts;
            this.priority = priority;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Domain domain() {
            return Domain.THERMAL;
        }

        @Override
        public String assumptions() {
            return "test model";
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public long estimateCost(StepContext context) {
            return cost;
        }

        @Override
        public void step(StepContext context) {
            double q = watts * context.dt() * (1 + context.random().nextDouble());
            add(context.world(), A, -q);
            add(context.world(), B, q + leak);
        }

        private static void add(PhysicalWorld world, GridPos pos, double joules) {
            CellState s = world.readBlock(pos);
            world.writeLeaf(CellId.of(pos), new CellState(s.material(), s.mass(), s.enthalpy() + joules, s.owner(),
                    Provenance.SIMULATED));
        }
    }

    private static PhysicalWorld world() {
        PhysicalWorld world = new PhysicalWorld(WorldSettings.vacuum(5), MaterialRegistry.withLibrary());
        world.placeMaterial(A, MaterialLibrary.COPPER, 400.0);
        world.placeMaterial(B, MaterialLibrary.COPPER, 300.0);
        return world;
    }

    @Test
    void modelsWithinBudgetRunEveryTickAndConserve() {
        PhysicalWorld world = world();
        Scheduler scheduler = new Scheduler(0.05, 100, 20, 1);
        scheduler.register(new HeatPump("test:pump", 10, 1000, 0));
        double before = world.readBlock(A).enthalpy();
        for (int i = 0; i < 5; i++) {
            TickReport report = scheduler.tick(world);
            assertEquals(i, report.tick());
            assertEquals(TickReport.Status.RAN, report.runs().get(0).status());
            assertTrue(report.conserved(), () -> report.audit().toString());
        }
        assertEquals(5, world.tick());
        assertTrue(world.readBlock(A).enthalpy() < before);
    }

    @Test
    void expensiveModelsWaitAndCatchUp() {
        PhysicalWorld world = world();
        Scheduler scheduler = new Scheduler(0.05, 100, 20, 1);
        scheduler.register(new HeatPump("test:pump", 300, 1000, 0));
        assertEquals(TickReport.Status.DEFERRED, scheduler.tick(world).runs().get(0).status());
        assertEquals(TickReport.Status.DEFERRED, scheduler.tick(world).runs().get(0).status());
        TickReport third = scheduler.tick(world);
        assertEquals(TickReport.Status.RAN, third.runs().get(0).status());
        assertEquals(0.15, third.runs().get(0).dt(), 1e-12);
        assertEquals(0, third.budgetBalance());
    }

    @Test
    void starvedModelsRunOnCredit() {
        PhysicalWorld world = world();
        Scheduler scheduler = new Scheduler(0.05, 100, 5, 1);
        scheduler.register(new HeatPump("test:pump", 1000, 1000, 0));
        for (int i = 0; i < 5; i++) {
            assertEquals(TickReport.Status.DEFERRED, scheduler.tick(world).runs().get(0).status());
        }
        TickReport sixth = scheduler.tick(world);
        assertEquals(TickReport.Status.RAN_ON_CREDIT, sixth.runs().get(0).status());
        assertEquals(0.3, sixth.runs().get(0).dt(), 1e-12);
        assertEquals(400 - 1000, sixth.budgetBalance());
    }

    @Test
    void aLeakingModelFailsTheAuditOnce() {
        PhysicalWorld world = world();
        Scheduler scheduler = new Scheduler(0.05, 100, 20, 1);
        HeatPump pump = new HeatPump("test:pump", 10, 1000, 0);
        scheduler.register(pump);
        pump.leak = 1.0;
        TickReport leaky = scheduler.tick(world);
        assertFalse(leaky.conserved());
        assertEquals("enthalpy", leaky.audit().discrepancies().get(0).quantity());
        pump.leak = 0;
        assertTrue(scheduler.tick(world).conserved(), "the discrepancy is reported once");
    }

    @Test
    void auditsRunAtTheirInterval() {
        PhysicalWorld world = world();
        Scheduler scheduler = new Scheduler(0.05, 100, 20, 3);
        scheduler.register(new HeatPump("test:pump", 10, 1000, 0));
        assertNull(scheduler.tick(world).audit());
        assertNull(scheduler.tick(world).audit());
        assertTrue(scheduler.tick(world).audit().balanced());
    }

    @Test
    void modelsRunByPriorityThenId() {
        Scheduler scheduler = new Scheduler(0.05, 100, 20, 1);
        scheduler.register(new HeatPump("test:b", 1, 1, 0));
        scheduler.register(new HeatPump("test:a", 1, 1, 0));
        scheduler.register(new HeatPump("test:first", 1, 1, -1));
        List<String> ids = scheduler.models().stream().map(PhysicsModel::id).toList();
        assertEquals(List.of("test:first", "test:a", "test:b"), ids);
        assertThrows(IllegalArgumentException.class, () -> scheduler.register(new HeatPump("test:a", 1, 1, 0)));
    }

    @Test
    void runsAreReproducible() {
        assertEquals(run(), run());
    }

    private static String run() {
        PhysicalWorld world = world();
        Scheduler scheduler = new Scheduler(0.05, 100, 20, 1);
        scheduler.register(new HeatPump("test:pump", 30, 1000, 0));
        scheduler.register(new HeatPump("test:slow", 250, 10, 1));
        for (int i = 0; i < 40; i++) {
            scheduler.tick(world);
        }
        return world.stateHash();
    }
}
