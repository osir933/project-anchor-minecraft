package io.github.osir933.anchor.core.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.osir933.anchor.core.matter.Element;
import io.github.osir933.anchor.core.matter.Material;
import io.github.osir933.anchor.core.matter.MaterialLibrary;
import io.github.osir933.anchor.core.space.CellId;
import io.github.osir933.anchor.core.space.GridPos;
import io.github.osir933.anchor.core.space.SectionPos;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PhysicalWorldTest {

    private static final GridPos ORIGIN = new GridPos(0, 64, 0);

    private static PhysicalWorld airWorld() {
        return new PhysicalWorld(WorldSettings.airAt20C(42), MaterialRegistry.withLibrary());
    }

    private static double temperature(PhysicalWorld world, CellState state) {
        return world.materials().get(state.material()).stateFor(state.specificEnthalpy()).temperatureK();
    }

    @Test
    void emptySpaceIsAmbient() {
        PhysicalWorld world = airWorld();
        CellState block = world.readBlock(ORIGIN);
        assertEquals(world.materials().indexOf(MaterialLibrary.AIR), block.material());
        assertEquals(Provenance.AMBIENT, block.provenance());
        assertEquals(293.15, temperature(world, block), 1e-9);
        assertEquals(0, world.sectionCount());
        assertTrue(world.audit().balanced());
    }

    @Test
    void vacuumWorldIsEmpty() {
        PhysicalWorld world = new PhysicalWorld(WorldSettings.vacuum(1), MaterialRegistry.withLibrary());
        CellState block = world.readBlock(ORIGIN);
        assertEquals(MaterialRegistry.VACUUM, block.material());
        assertEquals(0.0, block.mass());
    }

    @Test
    void placingMatterIsDeclaredInTheLedger() {
        PhysicalWorld world = airWorld();
        world.placeMaterial(ORIGIN, MaterialLibrary.GRANITE, 300.0);
        CellState block = world.readBlock(ORIGIN);
        assertEquals(2630.0, block.mass(), 1e-9);
        assertEquals(300.0, temperature(world, block), 1e-9);
        assertEquals(Provenance.INITIAL, block.provenance());
        assertEquals(1, world.sectionCount());
        assertTrue(world.audit().balanced(), () -> world.audit().toString());
    }

    @Test
    void refinementConservesToTheLastBit() {
        PhysicalWorld world = airWorld();
        world.placeMaterial(ORIGIN, MaterialLibrary.GRANITE, 300.0);
        CellState before = world.readBlock(ORIGIN);
        Totals totalsBefore = world.totals();

        CellId target = new CellId(ORIGIN, 3, 5, 2, 7);
        TransitionReport report = world.refine(target);

        assertTrue(report.applied(), report::reason);
        assertEquals(1 + 7 * 3, world.leafCount());
        assertEquals(22, report.leafDelta());
        assertTrue(world.isRefined(ORIGIN));
        assertEquals(target, world.leafCovering(target));
        CellState leaf = world.readLeaf(target);
        assertEquals(Provenance.RECONSTRUCTED, leaf.provenance());
        assertEquals(before.mass() / 512, leaf.mass());
        CellState aggregate = world.readBlock(ORIGIN);
        assertEquals(before.mass(), aggregate.mass());
        assertEquals(before.enthalpy(), aggregate.enthalpy());
        assertEquals(before.material(), aggregate.material());
        Totals totalsAfter = world.totals();
        assertEquals(totalsBefore.mass(), totalsAfter.mass());
        assertEquals(totalsBefore.enthalpy(), totalsAfter.enthalpy());
        assertTrue(world.audit().balanced());
    }

    @Test
    void refiningAgainChangesNothing() {
        PhysicalWorld world = airWorld();
        CellId target = new CellId(ORIGIN, 2, 1, 1, 1);
        assertTrue(world.refine(target).applied());
        int leaves = world.leafCount();
        TransitionReport again = world.refine(target);
        assertEquals(TransitionReport.Outcome.UNCHANGED, again.outcome());
        assertEquals(TransitionReport.Outcome.UNCHANGED, world.refine(target.parent()).outcome());
        assertEquals(TransitionReport.Outcome.UNCHANGED, world.refine(CellId.of(ORIGIN)).outcome());
        assertEquals(leaves, world.leafCount());
    }

    @Test
    void uniformCellsMergeBackExactly() {
        PhysicalWorld world = airWorld();
        world.placeMaterial(ORIGIN, MaterialLibrary.IRON, 500.0);
        CellState before = world.readBlock(ORIGIN);
        world.refine(new CellId(ORIGIN, 4, 0, 0, 0));

        TransitionReport report = world.coarsen(CellId.of(ORIGIN), CoarseningRule.DEFAULT);

        assertTrue(report.applied(), report::reason);
        assertFalse(world.isRefined(ORIGIN));
        assertEquals(0, world.leafCount());
        assertEquals(-29, report.leafDelta());
        CellState after = world.readBlock(ORIGIN);
        assertEquals(before.mass(), after.mass());
        assertEquals(before.enthalpy(), after.enthalpy());
        assertEquals(Provenance.COARSENED, after.provenance());
        assertTrue(world.audit().balanced());
    }

    @Test
    void partialCoarseningKeepsTheRestOfTheTree() {
        PhysicalWorld world = airWorld();
        world.placeMaterial(ORIGIN, MaterialLibrary.COPPER, 300.0);
        world.refine(new CellId(ORIGIN, 3, 0, 0, 0));
        CellId middle = new CellId(ORIGIN, 1, 0, 0, 0);
        assertTrue(world.coarsen(middle, CoarseningRule.DEFAULT).applied());
        assertEquals(middle, world.leafCovering(new CellId(ORIGIN, 3, 1, 1, 1)));
        assertEquals(8, world.leafCount());
        assertEquals(TransitionReport.Outcome.UNCHANGED, world.coarsen(middle, CoarseningRule.DEFAULT).outcome());
    }

    @Test
    void differentMaterialsNeverMerge() {
        PhysicalWorld world = airWorld();
        world.placeMaterial(ORIGIN, MaterialLibrary.GRANITE, 300.0);
        CellId corner = new CellId(ORIGIN, 1, 0, 0, 0);
        world.refine(corner);
        CellState iron = world.fullBlock(MaterialLibrary.IRON, 300.0);
        world.writeLeaf(corner, new CellState(iron.material(), iron.mass() / 8, iron.enthalpy() / 8, 0,
                Provenance.SIMULATED));

        TransitionReport report = world.coarsen(CellId.of(ORIGIN), CoarseningRule.DEFAULT);

        assertEquals(TransitionReport.Outcome.REFUSED, report.outcome());
        assertTrue(report.reason().contains("different materials"), report.reason());
        assertTrue(world.isRefined(ORIGIN));
    }

    @Test
    void aggregateShowsTheMaterialWithTheMostMass() {
        PhysicalWorld world = airWorld();
        world.placeMaterial(ORIGIN, MaterialLibrary.GRANITE, 300.0);
        world.refine(new CellId(ORIGIN, 1, 0, 0, 0));
        CellState iron = world.fullBlock(MaterialLibrary.IRON, 300.0);
        for (int octant = 0; octant < 3; octant++) {
            world.writeLeaf(CellId.of(ORIGIN).child(octant), new CellState(iron.material(), iron.mass() / 8,
                    iron.enthalpy() / 8, 0, Provenance.SIMULATED));
        }
        // Three eighths iron (2953 kg) outweighs five eighths granite (1644 kg).
        assertEquals(iron.material(), world.readBlock(ORIGIN).material());
        assertEquals(Provenance.SIMULATED, world.readBlock(ORIGIN).provenance());
    }

    @Test
    void aTemperatureDifferenceKeepsCellsApart() {
        PhysicalWorld world = airWorld();
        world.placeMaterial(ORIGIN, MaterialLibrary.GRANITE, 300.0);
        world.refine(new CellId(ORIGIN, 1, 0, 0, 0));
        moveHeat(world, CellId.of(ORIGIN).child(0), CellId.of(ORIGIN).child(7), 5000.0);

        TransitionReport report = world.coarsen(CellId.of(ORIGIN), CoarseningRule.DEFAULT);

        assertEquals(TransitionReport.Outcome.REFUSED, report.outcome());
        assertTrue(report.reason().contains("temperature"), report.reason());
        assertTrue(world.audit().balanced(), "moving heat between cells conserves energy");
    }

    @Test
    void aMeltFrontKeepsCellsApart() {
        PhysicalWorld world = airWorld();
        Material water = MaterialLibrary.WATER;
        world.placeMaterial(ORIGIN, water, 273.15);
        world.refine(new CellId(ORIGIN, 1, 0, 0, 0));
        double latentPerLeaf = world.readLeaf(CellId.of(ORIGIN).child(0)).mass() * 333.7e3;
        // Melt a quarter of every leaf, then move heat so one leaf is half melted and another not at all.
        for (int octant = 0; octant < 8; octant++) {
            CellId leaf = CellId.of(ORIGIN).child(octant);
            CellState s = world.readLeaf(leaf);
            world.writeLeaf(leaf, new CellState(s.material(), s.mass(), s.enthalpy() + 0.25 * latentPerLeaf,
                    s.owner(), Provenance.SIMULATED));
        }
        moveHeat(world, CellId.of(ORIGIN).child(1), CellId.of(ORIGIN).child(2), 0.25 * latentPerLeaf);

        TransitionReport report = world.coarsen(CellId.of(ORIGIN), CoarseningRule.DEFAULT);

        assertEquals(TransitionReport.Outcome.REFUSED, report.outcome());
        assertTrue(report.reason().contains("phase change"), report.reason());
    }

    @Test
    void theLeafBudgetIsEnforced() {
        WorldSettings tight = new WorldSettings(1, "anchor:air", 293.15, 10);
        PhysicalWorld world = new PhysicalWorld(tight, MaterialRegistry.withLibrary());

        TransitionReport report = world.refine(new CellId(ORIGIN, 2, 0, 0, 0));

        assertEquals(TransitionReport.Outcome.REFUSED, report.outcome());
        assertEquals(0, world.leafCount());
        assertEquals(0, world.sectionCount(), "a refused refinement must not load anything");
        assertTrue(world.refine(new CellId(ORIGIN, 1, 0, 0, 0)).applied());
        assertEquals(8, world.leafCount());
    }

    @Test
    void leavesAreVisitedInCellOrder() {
        PhysicalWorld world = airWorld();
        world.placeMaterial(ORIGIN, MaterialLibrary.GOLD, 300.0);
        world.refine(new CellId(ORIGIN, 2, 3, 0, 1));
        world.refine(new CellId(ORIGIN.offset(1, 0, 0), 1, 1, 1, 0));
        world.refine(new CellId(new GridPos(-20, 3, 40), 3, 7, 7, 7));
        List<CellId> visited = new ArrayList<>();
        world.forEachLeaf((cell, state) -> visited.add(cell));

        List<CellId> sorted = new ArrayList<>(visited);
        sorted.sort(null);
        assertEquals(sorted, visited);
        long blocks = (long) world.sectionCount() * SectionPos.BLOCKS;
        assertEquals(blocks - 3 + world.leafCount(), visited.size());
    }

    @Test
    void fillingCoversWholeAndPartialSections() {
        PhysicalWorld world = airWorld();
        world.fill(new GridPos(-3, 0, -3), new GridPos(16, 15, 16), MaterialLibrary.SAND, 290.0);

        assertEquals(world.materials().indexOf(MaterialLibrary.SAND), world.readBlock(new GridPos(-3, 0, -3))
                .material());
        assertEquals(world.materials().indexOf(MaterialLibrary.SAND), world.readBlock(new GridPos(5, 7, 5))
                .material());
        assertEquals(world.materials().indexOf(MaterialLibrary.AIR), world.readBlock(new GridPos(17, 0, 0))
                .material());
        assertEquals(9, world.sectionCount());
        assertTrue(world.section(SectionPos.pack(0, 0, 0)).isUniform());
        assertTrue(world.audit().balanced(), () -> world.audit().toString());
    }

    @Test
    void replacingARefinedBlockRemovesItsCells() {
        PhysicalWorld world = airWorld();
        world.refine(new CellId(ORIGIN, 3, 0, 0, 0));
        world.placeMaterial(ORIGIN, MaterialLibrary.ALUMINIUM, 300.0);
        assertFalse(world.isRefined(ORIGIN));
        assertEquals(0, world.leafCount());
        assertTrue(world.audit().balanced());
    }

    @Test
    void removingASectionIsDeclared() {
        PhysicalWorld world = airWorld();
        world.placeMaterial(ORIGIN, MaterialLibrary.BASALT, 1000.0);
        world.refine(new CellId(ORIGIN, 2, 0, 0, 0));
        assertTrue(world.removeSection(ORIGIN.sectionKey()));
        assertFalse(world.removeSection(ORIGIN.sectionKey()));
        assertEquals(0, world.leafCount());
        assertTrue(world.audit().balanced());
    }

    @Test
    void anUndeclaredGainIsCaught() {
        PhysicalWorld world = airWorld();
        world.placeMaterial(ORIGIN, MaterialLibrary.WATER, 300.0);
        CellState s = world.readBlock(ORIGIN);
        world.writeLeaf(CellId.of(ORIGIN), new CellState(s.material(), s.mass() * 1.001, s.enthalpy() * 1.001,
                s.owner(), Provenance.SIMULATED));

        ConservationLedger.Audit audit = world.audit();

        assertFalse(audit.balanced());
        List<String> quantities = audit.discrepancies().stream().map(ConservationLedger.Discrepancy::quantity)
                .toList();
        assertTrue(quantities.contains("mass"));
        assertTrue(quantities.contains("enthalpy"));
        assertTrue(quantities.contains(Element.H.name()));
        assertTrue(quantities.contains(Element.O.name()));
        assertEquals(WorldEvent.Kind.CONSERVATION, world.events().recent(1).get(0).kind());
    }

    @Test
    void writingALeafThatDoesNotExistFails() {
        PhysicalWorld world = airWorld();
        assertThrows(IllegalStateException.class, () -> world.writeLeaf(CellId.of(ORIGIN), world.ambientBlock()));
        world.addSection(ORIGIN.sectionKey());
        assertThrows(IllegalArgumentException.class,
                () -> world.writeLeaf(new CellId(ORIGIN, 1, 0, 0, 0), world.ambientBlock()));
        assertThrows(IllegalArgumentException.class, () -> world.readLeaf(new CellId(ORIGIN, 1, 0, 0, 0)));
    }

    @Test
    void aSnapshotRewindsExactly() {
        PhysicalWorld world = airWorld();
        world.placeMaterial(ORIGIN, MaterialLibrary.GRANITE, 300.0);
        world.refine(new CellId(ORIGIN, 2, 1, 2, 3));
        WorldSnapshot snapshot = world.snapshot();
        assertEquals(world.stateHash(), snapshot.stateHash());

        world.advanceTick();
        world.placeMaterial(ORIGIN.offset(0, 1, 0), MaterialLibrary.IRON, 1200.0);
        world.coarsen(CellId.of(ORIGIN), CoarseningRule.DEFAULT);
        assertNotEquals(snapshot.stateHash(), world.stateHash());

        world.restore(snapshot);

        assertEquals(snapshot.stateHash(), world.stateHash());
        assertEquals(0, world.tick());
        assertTrue(world.isRefined(ORIGIN));
        assertTrue(world.audit().balanced());
        assertEquals(WorldEvent.Kind.RESTORE, world.events().recent(1).get(0).kind());
        // The snapshot stays usable after a restore.
        world.clearBlock(ORIGIN);
        world.restore(snapshot);
        assertEquals(snapshot.stateHash(), world.stateHash());
    }

    @Test
    void theStateHashIgnoresHowSectionsAreStored() {
        PhysicalWorld compact = airWorld();
        PhysicalWorld dense = airWorld();
        compact.addSection(ORIGIN.sectionKey());
        dense.addSection(ORIGIN.sectionKey());
        dense.placeMaterial(ORIGIN, MaterialLibrary.GOLD, 300.0);
        dense.setBlock(ORIGIN, compact.readBlock(ORIGIN));
        assertFalse(dense.section(ORIGIN.sectionKey()).isUniform());

        assertEquals(compact.stateHash(), dense.stateHash());
    }

    @Test
    void theSameStepsGiveTheSameWorld() {
        assertEquals(scriptedWorld().stateHash(), scriptedWorld().stateHash());
    }

    @Test
    void theAmbientMaterialMustBeRegistered() {
        assertThrows(IllegalArgumentException.class,
                () -> new PhysicalWorld(WorldSettings.airAt20C(1), new MaterialRegistry()));
    }

    private static PhysicalWorld scriptedWorld() {
        PhysicalWorld world = airWorld();
        world.fill(new GridPos(-8, 60, -8), new GridPos(8, 63, 8), MaterialLibrary.GRANITE, 285.0);
        world.placeMaterial(ORIGIN, MaterialLibrary.WATER, 280.0);
        world.refine(new CellId(ORIGIN, 3, 4, 4, 4));
        world.refine(new CellId(new GridPos(1, 63, 0), 2, 0, 3, 0));
        moveHeat(world, new CellId(ORIGIN, 3, 4, 4, 4), new CellId(ORIGIN, 3, 4, 4, 5), 12.5);
        world.coarsen(CellId.of(new GridPos(1, 63, 0)), CoarseningRule.DEFAULT);
        world.advanceTick();
        return world;
    }

    private static void moveHeat(PhysicalWorld world, CellId from, CellId to, double joules) {
        CellState a = world.readLeaf(from);
        CellState b = world.readLeaf(to);
        world.writeLeaf(from, new CellState(a.material(), a.mass(), a.enthalpy() - joules, a.owner(),
                Provenance.SIMULATED));
        world.writeLeaf(to, new CellState(b.material(), b.mass(), b.enthalpy() + joules, b.owner(),
                Provenance.SIMULATED));
    }

    @Test
    void importedSectionsAreDeclaredAndReplaceWhatWasThere() {
        PhysicalWorld world = airWorld();
        long key = ORIGIN.sectionKey();
        world.placeMaterial(ORIGIN, MaterialLibrary.GOLD, 300.0);
        world.refine(new CellId(ORIGIN, 2, 1, 1, 1));
        CellState stone = world.fullBlock(MaterialLibrary.GRANITE, 290.0);
        CellState air = world.fullBlock(MaterialLibrary.AIR, 290.0);
        Section imported = world.importSection(key, i -> SectionPos.localY(i) < 4 ? stone : air, "chunk load");
        assertSame(imported, world.section(key));
        assertEquals(0, world.leafCount(), "the refined block went with the old section");
        assertEquals(world.materials().indexOf(MaterialLibrary.GRANITE), imported.material(0));
        assertEquals(world.materials().indexOf(MaterialLibrary.AIR), imported.material(SectionPos.BLOCKS - 1));
        assertTrue(world.audit().balanced(), () -> world.audit().toString());
        WorldEvent event = world.events().recent(1).get(0);
        assertEquals(WorldEvent.Kind.SECTION_ADDED, event.kind());
        assertEquals("chunk load", event.detail());

        Section uniform = world.importSection(key, i -> air, "reload");
        assertTrue(uniform.isUniform(), "imported sections are stored compactly");
        assertTrue(world.audit().balanced(), () -> world.audit().toString());
    }

    @Test
    void importingUnknownMaterialsFails() {
        PhysicalWorld world = airWorld();
        assertThrows(IllegalArgumentException.class, () -> world.importSection(ORIGIN.sectionKey(),
                i -> new CellState(999, 1.0, 0.0, 0, Provenance.INITIAL), "bad"));
        assertNull(world.section(ORIGIN.sectionKey()));
    }

    @Test
    void sectionVersionsCountEveryChange() {
        PhysicalWorld world = airWorld();
        world.placeMaterial(ORIGIN, MaterialLibrary.GRANITE, 300.0);
        Section s = world.section(ORIGIN.sectionKey());
        long v0 = s.version();
        CellState b = world.readBlock(ORIGIN);
        world.writeLeaf(CellId.of(ORIGIN), new CellState(b.material(), b.mass(), b.enthalpy() + 1, 0,
                Provenance.SIMULATED));
        long v1 = s.version();
        world.refine(new CellId(ORIGIN, 1, 0, 0, 0));
        long v2 = s.version();
        world.placeMaterial(ORIGIN.offset(1, 0, 0), MaterialLibrary.GRANITE, 300.0);
        long v3 = s.version();
        assertTrue(v0 < v1 && v1 < v2 && v2 < v3, v0 + " " + v1 + " " + v2 + " " + v3);
        WorldSnapshot snapshot = world.snapshot();
        world.restore(snapshot);
        assertEquals(v3, world.section(ORIGIN.sectionKey()).version());
    }

    @Test
    void writeListenersSeeEveryModelWriteButNoEdits() {
        PhysicalWorld world = airWorld();
        world.placeMaterial(ORIGIN, MaterialLibrary.GRANITE, 300.0);
        CellId fine = new CellId(ORIGIN.offset(1, 0, 0), 1, 1, 0, 1);
        world.placeMaterial(fine.block(), MaterialLibrary.COPPER, 300.0);
        world.refine(fine);
        List<String> seen = new ArrayList<>();
        PhysicalWorld.WriteListener listener = (key, block, cell, before, after) -> seen.add(GridPos.of(key, block)
                + (cell == null ? "" : " " + cell) + ": " + (after.enthalpy() - before.enthalpy()));
        world.addWriteListener(listener);
        CellState block = world.readBlock(ORIGIN);
        world.writeLeaf(CellId.of(ORIGIN), new CellState(block.material(), block.mass(), block.enthalpy() + 5,
                0, Provenance.SIMULATED));
        CellState leaf = world.readLeaf(fine);
        world.writeLeaf(fine, new CellState(leaf.material(), leaf.mass(), leaf.enthalpy() - 5, 0,
                Provenance.SIMULATED));
        world.placeMaterial(ORIGIN.offset(2, 0, 0), MaterialLibrary.GRANITE, 300.0);
        assertEquals(List.of(ORIGIN + ": 5.0", fine.block() + " " + fine + ": -5.0"), seen);
        world.removeWriteListener(listener);
        world.writeLeaf(CellId.of(ORIGIN), block);
        assertEquals(2, seen.size());
    }

    @Test
    void aBlockThatIsNotRefinedIsItsOwnLeaf() {
        PhysicalWorld world = airWorld();
        assertEquals(CellId.of(ORIGIN), world.leafCovering(new CellId(ORIGIN, 4, 3, 3, 3)));
        world.refine(new CellId(ORIGIN, 2, 0, 0, 0));
        assertNull(world.leafCovering(CellId.of(ORIGIN)));
    }
}
