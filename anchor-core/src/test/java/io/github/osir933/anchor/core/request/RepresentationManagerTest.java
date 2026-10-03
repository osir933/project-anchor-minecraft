package io.github.osir933.anchor.core.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.osir933.anchor.core.matter.MaterialLibrary;
import io.github.osir933.anchor.core.model.Domain;
import io.github.osir933.anchor.core.space.CellId;
import io.github.osir933.anchor.core.space.GridPos;
import io.github.osir933.anchor.core.world.CellState;
import io.github.osir933.anchor.core.world.CoarseningRule;
import io.github.osir933.anchor.core.world.MaterialRegistry;
import io.github.osir933.anchor.core.world.PhysicalWorld;
import io.github.osir933.anchor.core.world.Provenance;
import io.github.osir933.anchor.core.world.WorldSettings;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RepresentationManagerTest {

    private static final GridPos BLOCK = new GridPos(2, 70, -3);

    private static PhysicalWorld world() {
        PhysicalWorld world = new PhysicalWorld(WorldSettings.airAt20C(3), MaterialRegistry.withLibrary());
        world.placeMaterial(BLOCK, MaterialLibrary.GRANITE, 300.0);
        return world;
    }

    private static SimulationRequest request(String id, Region where, TimeWindow when, int level) {
        return new SimulationRequest(id, EnumSet.of(Domain.THERMAL), where, when, new Fidelity(level), "test");
    }

    @Test
    void requestsRefineAndReleasedDetailMergesBack() {
        PhysicalWorld world = world();
        RepresentationManager manager = new RepresentationManager(CoarseningRule.DEFAULT, 1000);
        manager.submit(request("probe", Region.Box.of(BLOCK), TimeWindow.from(0), 2));

        RepresentationManager.Report report = manager.update(world);

        assertTrue(report.demandMet());
        assertEquals(8, report.refined());
        assertEquals(64, world.leafCount());
        assertEquals(new CellId(BLOCK, 2, 3, 3, 3), world.leafCovering(new CellId(BLOCK, 2, 3, 3, 3)));
        assertEquals(0, manager.update(world).refined(), "nothing more to do");

        manager.withdraw("probe");
        RepresentationManager.Report release = manager.update(world);

        assertEquals(1, release.coarsened());
        assertFalse(world.isRefined(BLOCK));
        assertTrue(world.audit().balanced());
    }

    @Test
    void detailThatMattersIsKept() {
        PhysicalWorld world = world();
        RepresentationManager manager = new RepresentationManager(CoarseningRule.DEFAULT, 1000);
        manager.submit(request("probe", Region.Box.of(BLOCK), TimeWindow.from(0), 1));
        manager.update(world);
        CellId hot = CellId.of(BLOCK).child(0);
        CellId cold = CellId.of(BLOCK).child(7);
        CellState h = world.readLeaf(hot);
        CellState c = world.readLeaf(cold);
        world.writeLeaf(hot, new CellState(h.material(), h.mass(), h.enthalpy() + 1e5, 0, Provenance.SIMULATED));
        world.writeLeaf(cold, new CellState(c.material(), c.mass(), c.enthalpy() - 1e5, 0, Provenance.SIMULATED));

        manager.withdraw("probe");
        RepresentationManager.Report release = manager.update(world);

        assertEquals(0, release.coarsened());
        assertEquals(1, release.coarseningRefused());
        assertTrue(world.isRefined(BLOCK));
    }

    @Test
    void largeRequestsAreMetOverSeveralUpdates() {
        PhysicalWorld world = world();
        RepresentationManager manager = new RepresentationManager(CoarseningRule.DEFAULT, 5);
        manager.submit(request("probe", Region.Box.of(BLOCK), TimeWindow.from(0), 2));
        RepresentationManager.Report first = manager.update(world);
        assertFalse(first.demandMet());
        assertEquals(5, first.refined());
        RepresentationManager.Report second = manager.update(world);
        assertTrue(second.demandMet());
        assertEquals(3, second.refined());
        assertEquals(64, world.leafCount());
    }

    @Test
    void requestsOnlyApplyInsideTheirTimeWindow() {
        PhysicalWorld world = world();
        RepresentationManager manager = new RepresentationManager(CoarseningRule.DEFAULT, 1000);
        manager.submit(request("later", Region.Box.of(BLOCK), TimeWindow.during(2, 2), 1));
        manager.update(world);
        assertFalse(world.isRefined(BLOCK));
        world.advanceTick();
        world.advanceTick();
        assertEquals(Set.copyOf(EnumSet.of(Domain.THERMAL)), Set.copyOf(manager.activeDomains(world.tick())));
        manager.update(world);
        assertTrue(world.isRefined(BLOCK));
        world.advanceTick();
        world.advanceTick();
        manager.update(world);
        assertFalse(world.isRefined(BLOCK));
    }

    @Test
    void spheresRefineOnlyTheCellsTheyTouch() {
        PhysicalWorld world = world();
        RepresentationManager manager = new RepresentationManager(CoarseningRule.DEFAULT, 1000);
        Region tip = new Region.Sphere(BLOCK.x() + 0.1, BLOCK.y() + 0.1, BLOCK.z() + 0.1, 0.05);
        manager.submit(request("tip", tip, TimeWindow.from(0), 3));
        manager.update(world);
        assertEquals(1 + 7 * 3, world.leafCount());
        assertEquals(new CellId(BLOCK, 3, 0, 0, 0), world.leafCovering(new CellId(BLOCK, 3, 0, 0, 0)));
        assertEquals(new CellId(BLOCK, 1, 1, 1, 1), world.leafCovering(new CellId(BLOCK, 3, 7, 7, 7)));
    }
}
