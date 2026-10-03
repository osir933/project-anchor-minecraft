package io.github.osir933.anchor.core.physics.thermal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.osir933.anchor.core.math.DeterministicRandom;
import io.github.osir933.anchor.core.matter.Element;
import io.github.osir933.anchor.core.matter.Material;
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
import io.github.osir933.anchor.core.world.RefinedBlock;
import io.github.osir933.anchor.core.world.Section;
import io.github.osir933.anchor.core.world.Totals;
import io.github.osir933.anchor.core.world.WorldSettings;
import java.util.EnumMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class ThermalRefinementTest {

    private static final GridPos A = new GridPos(0, 0, 0);
    private static final GridPos B = new GridPos(1, 0, 0);

    private static PhysicalWorld vacuumWorld() {
        return new PhysicalWorld(WorldSettings.vacuum(11), MaterialRegistry.withLibrary());
    }

    private static SortedSet<Long> scope(PhysicalWorld world) {
        return SimulationScope.everywhere().sections(world, Domain.THERMAL);
    }

    private static ThermalRefinement refinement() {
        return new ThermalRefinement(ThermalRefinement.Settings.DEFAULT);
    }

    private static void step(PhysicalWorld world, ConductionModel model, double dt) {
        model.step(new StepContext(world, dt, new DeterministicRandom(1), SimulationScope.everywhere()));
    }

    /** One step of conduction followed by the refinement's update, as a hosted world runs them. */
    private static ThermalRefinement.Report tick(PhysicalWorld world, ConductionModel model,
            ThermalRefinement refinement, double dt) {
        step(world, model, dt);
        refinement.endStep(true);
        ThermalRefinement.Report report = refinement.update(world, scope(world), pos -> false);
        world.advanceTick();
        return report;
    }

    /** Sets a cell's temperature, declaring the heat that takes so that audits still balance. */
    private static void setTemperature(PhysicalWorld world, CellId cell, double temperatureK) {
        CellState s = world.readLeaf(cell);
        Material material = world.materials().get(s.material());
        double enthalpy = s.mass() * material.specificEnthalpy(temperatureK);
        world.writeLeaf(cell, new CellState(s.material(), s.mass(), enthalpy, s.owner(), Provenance.SIMULATED));
        double added = enthalpy - s.enthalpy();
        world.recordExchange(new Totals(0, added, Math.abs(added), new EnumMap<>(Element.class)), "test");
    }

    /** Returns how many cells each refined block holds. */
    private static TreeMap<GridPos, Integer> leaves(PhysicalWorld world) {
        TreeMap<GridPos, Integer> leaves = new TreeMap<>();
        for (Section s : world.sections()) {
            for (RefinedBlock block : s.refinedBlocks()) {
                leaves.put(block.pos(), block.leafCount());
            }
        }
        return leaves;
    }

    @Test
    void settingsAreChecked() {
        ThermalRefinement.Settings d = ThermalRefinement.Settings.DEFAULT;
        assertTrue(d.enabled());
        assertFalse(ThermalRefinement.Settings.OFF.enabled());
        assertFalse(d.withMaxLevel(0).enabled());
        assertFalse(d.withMaxLeaves(0).enabled());
        assertThrows(IllegalArgumentException.class, () -> new ThermalRefinement.Settings(0, 0, 3, 8, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ThermalRefinement.Settings(20, 20, 3, 8, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> d.withMaxLevel(CellId.MAX_LEVEL + 1));
        assertThrows(IllegalArgumentException.class, () -> d.withMaxLeaves(-1));
        assertEquals(Double.POSITIVE_INFINITY, new ThermalRefinement(ThermalRefinement.Settings.OFF).threshold());
    }

    @Test
    void aSteepGradientSplitsTheBlocksOnEitherSideDownToTheFinestLevel() {
        // Granite at 1000 K against granite at 300 K: each block needs a 350 K drop between its centre and the
        // face they share, far above the 50 K at which cells split. Each step splits one level more on the
        // face, down to 25 cm cells, and no further.
        PhysicalWorld world = vacuumWorld();
        world.placeMaterial(A, MaterialLibrary.GRANITE, 1000.0);
        world.placeMaterial(B, MaterialLibrary.GRANITE, 300.0);
        ThermalRefinement refinement = refinement();
        ConductionModel model = new ConductionModel(refinement);
        ThermalRefinement.Report first = tick(world, model, refinement, 1.0);
        assertEquals(2, first.split());
        assertTrue(world.isRefined(A) && world.isRefined(B));
        for (int i = 0; i < 5; i++) {
            tick(world, model, refinement, 1.0);
        }
        assertEquals(2, world.refinedBlock(A).depth(), "split to the finest level");
        assertEquals(2, world.refinedBlock(B).depth());
        assertEquals(0, tick(world, model, refinement, 1.0).merged(), "the drop is still steep");
        // Away from the shared face the cells stay coarse: the hot block's far side has no gradient to follow.
        CellId far = new CellId(A, 3, 0, 0, 0);
        assertEquals(1, world.refinedBlock(A).leafCovering(far).level());
        assertTrue(world.audit().balanced(), () -> world.audit().toString());
    }

    @Test
    void gentleGradientsLeaveBlocksWhole() {
        PhysicalWorld world = vacuumWorld();
        world.placeMaterial(A, MaterialLibrary.GRANITE, 330.0);
        world.placeMaterial(B, MaterialLibrary.GRANITE, 300.0);
        ThermalRefinement refinement = refinement();
        ConductionModel model = new ConductionModel(refinement);
        assertEquals(ThermalRefinement.Report.NOTHING, tick(world, model, refinement, 1.0),
                "15 K on either side of the face is below the split drop");
        assertFalse(world.isRefined(A) || world.isRefined(B));
    }

    @Test
    void gasAndHeldBlocksAreNeverSplit() {
        PhysicalWorld world = vacuumWorld();
        GridPos air = new GridPos(3, 0, 0);
        world.placeMaterial(A, MaterialLibrary.GRANITE, 300.0);
        world.placeMaterial(air, MaterialLibrary.AIR, 300.0);
        ThermalRefinement refinement = refinement();
        refinement.suggestSplit(CellId.of(A), 100.0);
        refinement.suggestSplit(CellId.of(air), 100.0);
        assertEquals(0, refinement.update(world, scope(world), A::equals).split());
        assertFalse(world.isRefined(A), "its temperature is held, as a heat source's is");
        assertFalse(world.isRefined(air), "gas passes heat on by moving, whatever its cells' size");
        refinement.suggestSplit(CellId.of(A), 100.0);
        assertEquals(1, refinement.update(world, scope(world), pos -> false).split());
        assertTrue(world.isRefined(A));
    }

    @Test
    void cellsAtTheFinestLevelAreNotSplitAgain() {
        PhysicalWorld world = vacuumWorld();
        world.placeMaterial(A, MaterialLibrary.GRANITE, 300.0);
        CellId finest = new CellId(A, 2, 0, 0, 0);
        world.refine(finest);
        int leaves = world.leafCount();
        ThermalRefinement refinement = refinement();
        refinement.suggestSplit(finest, 1000.0);
        refinement.suggestSplit(new CellId(A, 1, 1, 1, 1), 10.0);
        assertEquals(0, refinement.update(world, scope(world), pos -> false).split(),
                "one is as fine as cells go, the other's drop is small");
        assertEquals(leaves, world.leafCount());
    }

    @Test
    void theLeafBudgetStopsSplitting() {
        PhysicalWorld world = vacuumWorld();
        world.fill(A, new GridPos(2, 0, 0), MaterialLibrary.GRANITE, 300.0);
        ThermalRefinement refinement = new ThermalRefinement(ThermalRefinement.Settings.DEFAULT.withMaxLeaves(10));
        refinement.suggestSplit(CellId.of(A), 60.0);
        refinement.suggestSplit(CellId.of(B), 70.0);
        refinement.suggestSplit(CellId.of(new GridPos(2, 0, 0)), 80.0);
        ThermalRefinement.Report report = refinement.update(world, scope(world), pos -> false);
        assertEquals(new ThermalRefinement.Report(1, 0, true), report);
        assertTrue(world.isRefined(new GridPos(2, 0, 0)), "the steepest goes first");
        assertEquals(8, world.leafCount());
    }

    @Test
    void evenCellsMergeAfterAStepInWhichEveryModelRan() {
        PhysicalWorld world = vacuumWorld();
        world.placeMaterial(A, MaterialLibrary.GRANITE, 300.0);
        world.refine(new CellId(A, 2, 0, 0, 0));
        ThermalRefinement refinement = refinement();
        refinement.endStep(false);
        assertEquals(0, refinement.update(world, scope(world), pos -> false).merged(),
                "a model was put off, so its drops are unknown");
        assertTrue(world.isRefined(A));
        refinement.endStep(true);
        assertEquals(1, refinement.update(world, scope(world), pos -> false).merged(),
                "one merge a step per group of eight");
        refinement.endStep(true);
        assertEquals(1, refinement.update(world, scope(world), pos -> false).merged());
        assertFalse(world.isRefined(A), "merged back into a whole block");
        assertTrue(world.audit().balanced(), () -> world.audit().toString());
    }

    @Test
    void cellsStaySplitWhileAModelReportsADropInTheirBlock() {
        PhysicalWorld world = vacuumWorld();
        world.placeMaterial(A, MaterialLibrary.GRANITE, 300.0);
        world.refine(CellId.of(A).child(0));
        ThermalRefinement refinement = refinement();
        refinement.reportBlockDrop(A, 13.0);
        refinement.endStep(true);
        refinement.update(world, scope(world), pos -> false);
        assertTrue(world.isRefined(A), "13 K is more than a quarter of the split drop");
        refinement.reportBlockDrop(A, 12.0);
        refinement.endStep(true);
        refinement.update(world, scope(world), pos -> false);
        assertFalse(world.isRefined(A));
    }

    @Test
    void unevenCellsAndMeltingFrontsKeepTheirCells() {
        PhysicalWorld world = vacuumWorld();
        world.placeMaterial(A, MaterialLibrary.GRANITE, 300.0);
        world.refine(CellId.of(A).child(0));
        setTemperature(world, CellId.of(A).child(5), 306.0);
        world.placeMaterial(B, MaterialLibrary.WATER, 273.15);
        world.refine(CellId.of(B).child(0));
        // Half of one cell's ice melted: every cell is at 0 °C, but they are not equally far through melting.
        CellId melting = CellId.of(B).child(2);
        CellState ice = world.readLeaf(melting);
        double latent = ice.mass() * (world.materials().get(ice.material()).specificEnthalpy(273.16)
                - ice.specificEnthalpy());
        world.writeLeaf(melting, new CellState(ice.material(), ice.mass(), ice.enthalpy() + 0.5 * latent, 0,
                Provenance.SIMULATED));
        world.recordExchange(new Totals(0, 0.5 * latent, 0.5 * latent, new EnumMap<>(Element.class)), "test");
        ThermalRefinement refinement = refinement();
        refinement.endStep(true);
        assertEquals(0, refinement.update(world, scope(world), pos -> false).merged());
        assertTrue(world.isRefined(A), "6 K apart, more than the 5 K cells may differ by and merge");
        assertTrue(world.isRefined(B), "a melting front keeps its cells");
    }

    @Test
    void aCoolingBlockSplitsThenMergesWithoutFlippingBetweenThem() {
        // A hot iron block in an iron slab cools as its heat spreads: blocks split while the gradients are
        // steep and merge as they even out. A block whose cells merged must not split again, and in the end
        // the slab is whole again.
        PhysicalWorld world = vacuumWorld();
        world.fill(new GridPos(-1, 0, -1), new GridPos(1, 0, 1), MaterialLibrary.IRON, 300.0);
        world.placeMaterial(A, MaterialLibrary.IRON, 1000.0);
        ThermalRefinement refinement = refinement();
        ConductionModel model = new ConductionModel(refinement);
        TreeSet<GridPos> merged = new TreeSet<>();
        TreeMap<GridPos, Integer> before = leaves(world);
        int splits = 0;
        int merges = 0;
        for (int i = 0; i < 3000; i++) {
            ThermalRefinement.Report report = tick(world, model, refinement, 60.0);
            splits += report.split();
            merges += report.merged();
            TreeMap<GridPos, Integer> after = leaves(world);
            for (GridPos pos : new TreeSet<>(before.keySet())) {
                int now = after.getOrDefault(pos, 1);
                if (now < before.get(pos)) {
                    merged.add(pos);
                }
            }
            for (var e : after.entrySet()) {
                int was = before.getOrDefault(e.getKey(), 1);
                assertFalse(e.getValue() > was && merged.contains(e.getKey()),
                        "step " + i + ": " + e.getKey() + " split again after merging");
            }
            before = after;
        }
        assertTrue(splits > 0 && merges > 0, splits + " splits, " + merges + " merges");
        assertTrue(leaves(world).isEmpty(), "the slab evened out and merged back: " + leaves(world));
        assertTrue(world.audit().balanced(), () -> world.audit().toString());
    }

    @Test
    void replaysMakeTheSameDecisions() {
        PhysicalWorld first = vacuumWorld();
        PhysicalWorld second = vacuumWorld();
        ThermalRefinement r1 = refinement();
        ThermalRefinement r2 = refinement();
        ConductionModel m1 = new ConductionModel(r1);
        ConductionModel m2 = new ConductionModel(r2);
        for (PhysicalWorld world : new PhysicalWorld[] {first, second}) {
            world.fill(new GridPos(-1, 0, -1), new GridPos(1, 0, 1), MaterialLibrary.GRANITE, 300.0);
            world.placeMaterial(A, MaterialLibrary.IRON, 1200.0);
        }
        for (int i = 0; i < 40; i++) {
            assertEquals(tick(first, m1, r1, 30.0), tick(second, m2, r2, 30.0), "step " + i);
            assertEquals(first.stateHash(), second.stateHash(), "step " + i);
        }
    }
}
