package io.github.osir933.anchor.core.host;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.osir933.anchor.core.matter.MaterialLibrary;
import io.github.osir933.anchor.core.matter.Phase;
import io.github.osir933.anchor.core.physics.structure.Frame;
import io.github.osir933.anchor.core.physics.structure.Shape;
import io.github.osir933.anchor.core.physics.structure.StructuralAnalysis;
import io.github.osir933.anchor.core.space.Direction;
import io.github.osir933.anchor.core.space.GridPos;
import io.github.osir933.anchor.core.space.SectionPos;
import io.github.osir933.anchor.core.world.MaterialRegistry;
import io.github.osir933.anchor.core.world.PhysicalWorld;
import io.github.osir933.anchor.core.world.WorldSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;

/** Built blocks, cracked joints and the surveys that read structures out of a hosted world. */
class HostedStructuresTest {

    private static final int AIR = 0;
    private static final int STONE = 1;
    private static final int SLAB = 2;
    private static final int TOP_SLAB = 3;
    private static final int TORCH = 4;
    private static final int MOSS = 5;
    private static final int IRON = 6;
    private static final int WATER = 7;
    private static final int FENCE = 8;
    private static final int ICE = 9;
    private static final int SNOW_LAYER = 10;
    private static final int SNOW_LAYERS = 11;
    private static final int BARRIER = 12;
    private static final int SAND = 13;

    private static final BlockAppearance[] LOOKS = {
        BlockAppearance.of("anchor:air"),
        BlockAppearance.of("anchor:granite"),
        BlockAppearance.of("anchor:granite").withFill(0.5),
        BlockAppearance.of("anchor:granite").withFill(0.5).withShape(Shape.of(new double[] {0, 0.5, 0, 1, 1, 1})),
        BlockAppearance.of("anchor:hardwood").withFill(0.01).withShape(Shape.NONE),
        BlockAppearance.of("anchor:soil"),
        BlockAppearance.of("anchor:iron"),
        BlockAppearance.of("anchor:water").shownAs(Phase.LIQUID),
        BlockAppearance.of("anchor:air").withShape(Shape.of(new double[] {0.375, 0, 0.375, 0.625, 1, 0.625}))
                .framedIn("anchor:hardwood"),
        BlockAppearance.of("anchor:water").shownAs(Phase.SOLID),
        // One layer of snow is nothing to stand on; more are.
        BlockAppearance.of("anchor:powder_snow").withFill(0.125).withShape(Shape.NONE),
        BlockAppearance.of("anchor:powder_snow").withFill(0.25).withShape(Shape.bottom(0.125)),
        BlockAppearance.of("anchor:granite").asImmovable(),
        BlockAppearance.of("anchor:sand"),
    };

    private static final long ORIGIN = SectionPos.pack(0, 0, 0);
    private static final long EAST = SectionPos.pack(1, 0, 0);
    private static final double CLIMATE = 293.15;

    private static HostedWorld hosted() {
        PhysicalWorld world = new PhysicalWorld(WorldSettings.airAt20C(3), MaterialRegistry.withLibrary());
        return new HostedWorld(world, id -> LOOKS[id], HostedWorld.Settings.defaults());
    }

    /** Stone below y = 8 and air above, with some blocks replaced. */
    private static IntUnaryOperator ground(Map<GridPos, Integer> blocks) {
        TreeMap<Integer, Integer> byIndex = new TreeMap<>();
        blocks.forEach((pos, id) -> byIndex.put(pos.indexInSection(), id));
        return i -> byIndex.getOrDefault(i, SectionPos.localY(i) < 8 ? STONE : AIR);
    }

    /** A wall of stone two blocks thick on the section's west side, and air elsewhere. */
    private static IntUnaryOperator wall() {
        return i -> SectionPos.localX(i) < 2 ? STONE : AIR;
    }

    private static void place(HostedWorld h, GridPos pos, int id) {
        h.reconcile(pos, id, Double.NaN);
    }

    @Test
    void placedBlocksAreBuiltAndTheWorldAsFoundIsGround() {
        HostedWorld h = hosted();
        h.importSection(ORIGIN, ground(Map.of()), CLIMATE);
        GridPos placed = new GridPos(4, 8, 4);
        assertFalse(h.isBuilt(placed.offset(Direction.DOWN)), "the ground is not built");
        place(h, placed, STONE);
        assertTrue(h.isBuilt(placed));
        GridPos torch = new GridPos(6, 8, 6);
        place(h, torch, TORCH);
        assertFalse(h.isBuilt(torch), "a torch touches nothing, so it carries nothing");
        GridPos water = new GridPos(8, 8, 8);
        place(h, water, WATER);
        assertFalse(h.isBuilt(water), "liquids carry no loads");
        // Moss spreading over the ground where it lies is ground; new matter in a built block is built.
        GridPos mossy = new GridPos(2, 7, 2);
        place(h, mossy, MOSS);
        assertFalse(h.isBuilt(mossy));
        place(h, placed, MOSS);
        assertTrue(h.isBuilt(placed));
        place(h, placed, AIR);
        assertFalse(h.isBuilt(placed));
        assertFalse(h.isBuilt(new GridPos(40, 8, 4)), "nothing is built where nothing is imported");
    }

    @Test
    void waterThatFreezesAndSnowThatPilesUpStayGround() {
        HostedWorld h = hosted();
        h.importSection(ORIGIN, ground(Map.of()), CLIMATE);
        // Ice shown where water was, frozen by the engine or by the host, is the same matter in another phase.
        GridPos pond = new GridPos(4, 8, 4);
        place(h, pond, WATER);
        place(h, pond, ICE);
        assertFalse(h.isBuilt(pond), "water that froze where it lay");
        GridPos chilled = new GridPos(6, 8, 6);
        place(h, chilled, WATER);
        assertTrue(h.setTemperature(chilled, 250.0));
        place(h, chilled, ICE);
        assertEquals("anchor:water", h.inspect(chilled).orElseThrow().material());
        assertFalse(h.isBuilt(chilled), "water the engine froze");
        GridPos placed = new GridPos(8, 8, 8);
        place(h, placed, ICE);
        assertTrue(h.isBuilt(placed), "ice brought into the air");
        // Snow piling up on a layer that carried nothing.
        GridPos snow = new GridPos(10, 8, 10);
        place(h, snow, SNOW_LAYER);
        assertFalse(h.isBuilt(snow));
        place(h, snow, SNOW_LAYERS);
        assertFalse(h.isBuilt(snow), "the snow was not brought, it fell");
        assertEquals(1, h.uncheckedStructures(), "only the placed ice waits to be checked");
    }

    @Test
    void aDroppedAnalysisLeavesItsStructureWaiting() {
        HostedWorld h = hosted();
        h.importSection(ORIGIN, ground(Map.of()), CLIMATE);
        GridPos placed = new GridPos(4, 8, 4);
        place(h, placed, STONE);
        StructureSurvey survey = h.nextStructure(100).orElseThrow();
        assertEquals(0, h.uncheckedStructures(), "taken to be checked");
        h.checkLater(survey);
        assertEquals(1, h.uncheckedStructures());
        assertEquals(placed, h.nextStructure(100).orElseThrow().start());
        HostedWorld other = hosted();
        assertThrows(IllegalArgumentException.class, () -> other.checkLater(survey));
    }

    @Test
    void everyBuiltBlockWaitsToBeCheckedAgainWhenTheRulesChange() {
        HostedWorld h = hosted();
        h.importSection(ORIGIN, ground(Map.of()), CLIMATE);
        place(h, new GridPos(4, 8, 4), STONE);
        place(h, new GridPos(4, 9, 4), STONE);
        place(h, new GridPos(10, 8, 10), STONE);
        while (h.nextStructure(100).isPresent()) {
            assertTrue(h.uncheckedStructures() < 3);
        }
        assertEquals(0, h.uncheckedStructures());
        h.recheckStructures();
        assertEquals(3, h.uncheckedStructures(), "the built blocks wait, the ground does not");
    }

    @Test
    void blocksThatHoldStillAreNeverBuiltAndHoldUpWhatHangsFromThem() {
        HostedWorld h = hosted();
        h.importSection(ORIGIN, ground(Map.of()), CLIMATE);
        GridPos barrier = new GridPos(4, 12, 4);
        place(h, barrier, BARRIER);
        assertFalse(h.isBuilt(barrier), "put in the air, it still holds still");
        assertEquals(0, h.setBuilt(barrier, barrier, true), "it cannot be made built");
        GridPos hanging = barrier.offset(Direction.DOWN);
        place(h, hanging, STONE);
        assertTrue(h.isBuilt(hanging));
        StructureSurvey survey = h.survey(hanging, 100).orElseThrow();
        assertEquals(1, survey.blocks());
        assertTrue(survey.whole(), "the block it hangs from is ground, not the survey's edge");
        assertTrue(StructuralAnalysis.analyse(survey.frame()).falling().isEmpty());
    }

    @Test
    void aFenceCountsAsAirForHeatButHoldsUpWhatStandsOnIt() {
        HostedWorld h = hosted();
        h.importSection(ORIGIN, ground(Map.of()), CLIMATE);
        GridPos fence = new GridPos(4, 8, 4);
        place(h, fence, FENCE);
        assertTrue(h.isBuilt(fence), "putting up a fence in the air builds it, though the air stays");
        assertEquals("anchor:air", h.inspect(fence).orElseThrow().material());
        GridPos stone = fence.offset(Direction.UP);
        place(h, stone, STONE);
        StructureSurvey survey = h.survey(stone, 100).orElseThrow();
        assertEquals(2, survey.blocks());
        Frame.Block post = survey.frame().blocks().get(1);
        assertEquals(fence, post.pos());
        assertEquals(MaterialLibrary.HARDWOOD.mechanics(), post.mechanics());
        assertEquals(0.0625 * MaterialLibrary.HARDWOOD.referenceDensity(), post.massKg(),
                0.01 * post.massKg(), "a post a quarter of a block wide");
        assertEquals(CLIMATE, post.temperatureK(), 1e-6, "at the temperature of the air it stands in");
        for (Frame.Bond b : survey.frame().bonds()) {
            assertEquals(0.0625, b.contact().area(), 1e-12, "it touches its neighbours with its post");
        }
        assertTrue(StructuralAnalysis.analyse(survey.frame()).falling().isEmpty());
        // Taking the fence down leaves the stone on nothing.
        place(h, fence, AIR);
        assertFalse(h.isBuilt(fence));
        StructureSurvey alone = h.survey(stone, 100).orElseThrow();
        assertEquals(List.of(stone), StructuralAnalysis.analyse(alone.frame()).falling());
    }

    @Test
    void changesAroundBuiltBlocksMakeThemWaitToBeChecked() {
        HostedWorld h = hosted();
        h.importSection(ORIGIN, ground(Map.of()), CLIMATE);
        assertEquals(0, h.uncheckedStructures());
        GridPos bottom = new GridPos(4, 8, 4);
        place(h, bottom, STONE);
        place(h, bottom.offset(Direction.UP), STONE);
        assertEquals(2, h.uncheckedStructures());
        StructureSurvey survey = h.nextStructure(100).orElseThrow();
        assertEquals(2, survey.blocks());
        assertTrue(survey.whole());
        assertEquals(0, h.uncheckedStructures(), "one survey takes in the whole tower");
        assertTrue(h.nextStructure(100).isEmpty());
        // Digging out the ground somewhere else changes nothing; under the tower, it does.
        place(h, new GridPos(10, 7, 10), AIR);
        assertEquals(0, h.uncheckedStructures());
        place(h, bottom.offset(Direction.DOWN), AIR);
        assertEquals(1, h.uncheckedStructures());
        assertEquals(bottom, h.nextStructure(100).orElseThrow().start());
    }

    @Test
    void aSurveyReadsTheBuiltBlocksAndTheGroundTheyStandOn() {
        HostedWorld h = hosted();
        h.importSection(ORIGIN, ground(Map.of()), CLIMATE);
        GridPos base = new GridPos(4, 8, 4);
        place(h, base, STONE);
        place(h, base.offset(0, 1, 0), STONE);
        place(h, base.offset(0, 2, 0), STONE);
        place(h, base.offset(1, 2, 0), STONE);
        StructureSurvey survey = h.survey(base.offset(1, 2, 0), 100).orElseThrow();
        assertEquals(4, survey.blocks());
        assertTrue(survey.whole());
        Frame frame = survey.frame();
        assertEquals(5, frame.blocks().size(), "four free blocks and the ground under the tower");
        Frame.Block soil = frame.blocks().get(0);
        assertEquals(base.offset(Direction.DOWN), soil.pos());
        assertTrue(soil.ground());
        assertEquals(MaterialLibrary.GRANITE.mechanics(), soil.mechanics());
        assertEquals(4, frame.bonds().size());
        Frame.Block top = frame.blocks().get(frame.blocks().size() - 1);
        assertEquals(base.offset(1, 2, 0), top.pos());
        assertEquals(CLIMATE, top.temperatureK(), 1e-9);
        assertEquals(1.0, top.solidFraction());
        assertEquals(h.world().readBlock(top.pos()).mass(), top.massKg());
        StructuralAnalysis.Result result = StructuralAnalysis.analyse(frame);
        HostedWorld.Settled settled = h.settle(survey, result);
        assertFalse(settled.stale());
        assertTrue(settled.cracks().isEmpty());
        assertTrue(settled.falling().isEmpty());
        assertTrue(h.survey(new GridPos(4, 7, 4), 100).isEmpty(), "ground is not surveyed");
        assertTrue(h.survey(new GridPos(4, 12, 4), 100).isEmpty(), "nor is air");
        assertThrows(IllegalArgumentException.class, () -> h.survey(base, 0));
    }

    @Test
    void aSurveyStopsAtItsLimitAndHoldsTheRestStill() {
        HostedWorld h = hosted();
        h.importSection(ORIGIN, ground(Map.of()), CLIMATE);
        for (int x = 2; x < 12; x++) {
            place(h, new GridPos(x, 8, 4), STONE);
        }
        StructureSurvey survey = h.survey(new GridPos(2, 8, 4), 4).orElseThrow();
        assertEquals(4, survey.blocks());
        assertEquals(1, survey.edge(), "the fifth block of the row holds still");
        assertFalse(survey.whole());
        Frame.Block held = null;
        for (Frame.Block b : survey.frame().blocks()) {
            if (b.pos().equals(new GridPos(6, 8, 4))) {
                held = b;
            }
        }
        assertNotNull(held);
        assertTrue(held.ground());
        assertNull(held.mechanics(), "where the survey stops nothing can break");
    }

    @Test
    void sectionsThatAreNotSimulatedHoldStill() {
        HostedWorld h = hosted();
        h.importSection(ORIGIN, ground(Map.of()), CLIMATE);
        GridPos edge = new GridPos(15, 8, 4);
        place(h, edge, STONE);
        StructureSurvey survey = h.survey(edge, 100).orElseThrow();
        assertEquals(1, survey.blocks());
        assertEquals(1, survey.edge());
        assertTrue(survey.frame().blocks().stream().anyMatch(b -> b.pos().equals(new GridPos(16, 8, 4))
                && b.ground() && b.mechanics() == null));
        // Importing the section next door makes the built blocks facing it wait to be checked again.
        assertTrue(h.nextStructure(100).isPresent());
        assertEquals(0, h.uncheckedStructures());
        h.importSection(EAST, i -> AIR, CLIMATE);
        assertEquals(1, h.uncheckedStructures());
        // A survey whose blocks have changed since is not settled.
        StructuralAnalysis.Result result = StructuralAnalysis.analyse(survey.frame());
        assertTrue(h.settle(survey, result).stale(), "the section it took to hold still is simulated now");
    }

    @Test
    void shapesDecideWhatTouches() {
        HostedWorld h = hosted();
        h.importSection(ORIGIN, ground(Map.of()), CLIMATE);
        // A block above a bottom slab floats clear of it, and falls.
        GridPos slab = new GridPos(4, 8, 4);
        place(h, slab, SLAB);
        place(h, slab.offset(Direction.UP), STONE);
        StructureSurvey above = h.survey(slab.offset(Direction.UP), 100).orElseThrow();
        assertEquals(1, above.blocks());
        assertTrue(above.frame().bonds().isEmpty());
        assertEquals(List.of(slab.offset(Direction.UP)), StructuralAnalysis.analyse(above.frame()).falling());
        // The slab itself rests on the ground.
        StructureSurvey resting = h.survey(slab, 100).orElseThrow();
        assertEquals(1, resting.frame().bonds().size());
        assertTrue(StructuralAnalysis.analyse(resting.frame()).falling().isEmpty());
        // A top slab hangs clear of the ground, and takes the block on it down with it.
        GridPos top = new GridPos(8, 8, 8);
        place(h, top, TOP_SLAB);
        place(h, top.offset(Direction.UP), STONE);
        StructureSurvey hanging = h.survey(top, 100).orElseThrow();
        assertEquals(2, hanging.blocks());
        assertEquals(List.of(top, top.offset(Direction.UP)), StructuralAnalysis.analyse(hanging.frame()).falling());
    }

    @Test
    void anOverhangThatIsTooLongCracksAtTheWallAndFalls() {
        HostedWorld h = hosted();
        h.importSection(ORIGIN, wall(), CLIMATE);
        List<GridPos> overhang = new ArrayList<>();
        for (int x = 2; x < 16; x++) {
            GridPos pos = new GridPos(x, 8, 8);
            place(h, pos, STONE);
            overhang.add(pos);
        }
        StructureSurvey survey = h.nextStructure(100).orElseThrow();
        assertEquals(14, survey.blocks());
        assertEquals(1, survey.edge(), "the far end touches the next section, which is not simulated");
        // That next section would hold the far end up, so look at an overhang that stops short of it.
        place(h, new GridPos(15, 8, 8), AIR);
        survey = h.nextStructure(100).orElseThrow();
        assertEquals(13, survey.blocks());
        assertTrue(survey.whole());
        StructuralAnalysis.Result result = StructuralAnalysis.analyse(survey.frame());
        HostedWorld.Settled settled = h.settle(survey, result);
        assertFalse(settled.stale());
        assertEquals(1, settled.cracks().size());
        assertEquals(new GridPos(1, 8, 8), settled.cracks().get(0).pos(), "it cracks at the wall");
        assertEquals(overhang.subList(0, 13), settled.falling());
        assertTrue(h.isCracked(new GridPos(2, 8, 8), Direction.WEST));
        assertTrue(h.isCracked(new GridPos(1, 8, 8), Direction.EAST));
        // A short overhang holds.
        for (int x = 9; x < 15; x++) {
            place(h, new GridPos(x, 8, 8), AIR);
        }
        place(h, new GridPos(2, 8, 8), AIR);
        place(h, new GridPos(2, 8, 8), STONE);
        assertFalse(h.isCracked(new GridPos(2, 8, 8), Direction.WEST), "new matter starts with intact joints");
        survey = h.nextStructure(100).orElseThrow();
        assertEquals(7, survey.blocks());
        settled = h.settle(survey, StructuralAnalysis.analyse(survey.frame()));
        assertTrue(settled.cracks().isEmpty());
        assertTrue(settled.falling().isEmpty());
    }

    @Test
    void aPillarTooHeavyForTheSandUnderItSinksIntoIt() {
        // Sand bears about 170 kPa under a block's footing at its surface: six blocks of granite, not seven.
        HostedWorld h = hosted();
        h.importSection(ORIGIN, i -> SectionPos.localY(i) < 8 ? SAND : AIR, CLIMATE);
        GridPos foot = new GridPos(4, 8, 4);
        GridPos sand = foot.offset(Direction.DOWN);
        for (int k = 0; k < 7; k++) {
            place(h, foot.offset(0, k, 0), STONE);
        }
        StructureSurvey survey = h.survey(foot, 100).orElseThrow();
        Frame.Block ground = survey.frame().blocks().get(0);
        assertEquals(sand, ground.pos());
        assertTrue(ground.ground());
        assertEquals(h.world().readBlock(sand).mass(), ground.massKg(), "how much sand weighs sets what it bears");
        HostedWorld.Settled settled = h.settle(survey, StructuralAnalysis.analyse(survey.frame()));
        assertFalse(settled.stale());
        assertEquals(List.of(sand), settled.sunk());
        assertEquals(7, settled.falling().size());
        assertTrue(settled.cracks().isEmpty());
        assertFalse(h.isCracked(foot, Direction.DOWN), "the sand is pushed aside, not cracked");
        place(h, foot.offset(0, 6, 0), AIR);
        survey = h.survey(foot, 100).orElseThrow();
        settled = h.settle(survey, StructuralAnalysis.analyse(survey.frame()));
        assertTrue(settled.sunk().isEmpty());
        assertTrue(settled.falling().isEmpty());
    }

    @Test
    void nothingIsSettledOnceTheStructureHasChanged() {
        HostedWorld h = hosted();
        h.importSection(ORIGIN, wall(), CLIMATE);
        for (int x = 2; x < 15; x++) {
            place(h, new GridPos(x, 8, 8), STONE);
        }
        StructureSurvey survey = h.nextStructure(100).orElseThrow();
        StructuralAnalysis.Result result = StructuralAnalysis.analyse(survey.frame());
        assertFalse(result.cracks().isEmpty());
        place(h, new GridPos(14, 8, 8), AIR);
        HostedWorld.Settled settled = h.settle(survey, result);
        assertTrue(settled.stale());
        assertTrue(settled.cracks().isEmpty());
        assertTrue(settled.falling().isEmpty());
        assertFalse(h.isCracked(new GridPos(2, 8, 8), Direction.WEST));
        assertTrue(h.uncheckedStructures() > 0, "the structure waits to be checked again");
        HostedWorld other = hosted();
        assertThrows(IllegalArgumentException.class, () -> other.settle(survey, result));
    }

    /** A tower of two built blocks with the joint between them cracked, as an analysis might leave it. */
    private static HostedWorld crackedTower(GridPos bottom) {
        HostedWorld h = hosted();
        h.importSection(ORIGIN, ground(Map.of()), CLIMATE);
        place(h, bottom, STONE);
        place(h, bottom.offset(Direction.UP), STONE);
        StructureSurvey survey = h.nextStructure(100).orElseThrow();
        StructuralAnalysis.Crack crack = new StructuralAnalysis.Crack(bottom, 1, StructuralAnalysis.Mode.TENSION,
                1.2, false, false);
        StructuralAnalysis.Result result = new StructuralAnalysis.Result(List.of(),
                List.of(new StructuralAnalysis.BondResult(bottom, 1, Frame.Joint.CRACKED, true, 0.4,
                        StructuralAnalysis.Mode.TIPPING, 0.4, 0.4, false, -1000.0)), List.of(crack), List.of(), 2,
                true, Double.POSITIVE_INFINITY, List.of(), List.of());
        long before = h.sectionVersion(ORIGIN);
        assertEquals(List.of(crack), h.settle(survey, result).cracks());
        assertTrue(h.sectionVersion(ORIGIN) > before, "cracking changes the section");
        return h;
    }

    @Test
    void builtBlocksAndCracksAreSavedWithTheirSection() {
        GridPos bottom = new GridPos(4, 8, 4);
        HostedWorld h = crackedTower(bottom);
        assertTrue(h.isCracked(bottom, Direction.UP));
        assertTrue(h.isCracked(bottom.offset(Direction.UP), Direction.DOWN));
        assertFalse(h.isCracked(bottom, Direction.DOWN));
        SectionSnapshot saved = h.snapshot(ORIGIN).orElseThrow();
        assertArrayEquals(new int[] {bottom.indexInSection(), bottom.offset(Direction.UP).indexInSection()},
                saved.structureBlocks());
        assertArrayEquals(new byte[] {(byte) (StructureFlags.BUILT | StructureFlags.cracked(1)), StructureFlags.BUILT},
                saved.structureFlags());
        Map<GridPos, Integer> tower = Map.of(bottom, STONE, bottom.offset(Direction.UP), STONE);
        HostedWorld again = hosted();
        again.importSection(ORIGIN, ground(tower), CLIMATE, saved);
        assertTrue(again.isBuilt(bottom));
        assertTrue(again.isBuilt(bottom.offset(Direction.UP)));
        assertTrue(again.isCracked(bottom, Direction.UP));
        assertEquals(2, again.uncheckedStructures(), "anything may have happened while the section was away");
        assertEquals(saved, again.snapshot(ORIGIN).orElseThrow());
        // A block that changed while its section was away is no longer built.
        HostedWorld changed = hosted();
        changed.importSection(ORIGIN, ground(Map.of(bottom, STONE)), CLIMATE, saved);
        assertTrue(changed.isBuilt(bottom));
        assertFalse(changed.isBuilt(bottom.offset(Direction.UP)));
        // A section of untouched ground saves nothing.
        HostedWorld plain = hosted();
        plain.importSection(ORIGIN, ground(Map.of()), CLIMATE);
        assertTrue(plain.snapshot(ORIGIN).isEmpty());
    }

    @Test
    void newMatterStartsWithIntactJoints() {
        GridPos bottom = new GridPos(4, 8, 4);
        HostedWorld h = crackedTower(bottom);
        place(h, bottom.offset(Direction.UP), AIR);
        assertFalse(h.isCracked(bottom, Direction.UP));
        place(h, bottom.offset(Direction.UP), STONE);
        assertFalse(h.isCracked(bottom, Direction.UP));
        HostedWorld again = crackedTower(bottom);
        place(again, bottom, MOSS);
        assertTrue(again.isBuilt(bottom), "new matter in a built block is built");
        assertFalse(again.isCracked(bottom, Direction.UP));
    }

    @Test
    void savedBoxesKeepTheirStructureButNotTheirCracksWithTheOutside() {
        GridPos bottom = new GridPos(4, 8, 4);
        HostedWorld h = crackedTower(bottom);
        GridPos top = bottom.offset(Direction.UP);
        Map<GridPos, Integer> blocks = new TreeMap<>(Map.of(bottom, STONE, top, STONE));
        RegionSnapshot both = h.capture(bottom, top, p -> blocks.getOrDefault(p, p.y() < 8 ? STONE : AIR));
        RegionSnapshot lower = h.capture(bottom, bottom, p -> blocks.getOrDefault(p, p.y() < 8 ? STONE : AIR));
        place(h, bottom, AIR);
        place(h, top, AIR);
        assertFalse(h.isBuilt(bottom));
        place(h, bottom, STONE);
        place(h, top, STONE);
        h.restore(both, bottom, p -> blocks.getOrDefault(p, AIR));
        assertTrue(h.isBuilt(bottom));
        assertTrue(h.isCracked(bottom, Direction.UP));
        assertTrue(h.uncheckedStructures() >= 2);
        h.restore(lower, bottom, p -> blocks.getOrDefault(p, AIR));
        assertTrue(h.isBuilt(bottom));
        assertFalse(h.isCracked(bottom, Direction.UP), "the joint with a block outside the box starts intact");
        // Built ground cannot be restored where the host now shows air.
        h.restore(both, bottom, p -> AIR);
        assertFalse(h.isBuilt(bottom));
    }

    @Test
    void heatThatWeakensABuiltBlockMakesItWaitToBeChecked() {
        HostedWorld h = hosted();
        h.importSection(ORIGIN, ground(Map.of()), CLIMATE);
        GridPos iron = new GridPos(4, 8, 4);
        place(h, iron, IRON);
        assertTrue(h.nextStructure(100).isPresent());
        assertEquals(0, h.uncheckedStructures());
        // Iron keeps 47 % of its strength at 600 C.
        h.setTemperature(iron, 873.15);
        for (int i = 0; i < HostedWorld.STRENGTH_CHECK_STEPS; i++) {
            h.tick();
        }
        assertEquals(1, h.uncheckedStructures());
        StructureSurvey survey = h.nextStructure(100).orElseThrow();
        assertEquals(h.temperature(iron), survey.frame().blocks().get(1).temperatureK(), 1e-6);
        // A few kelvin more changes its strength by less than the margin.
        h.setTemperature(iron, h.temperature(iron) + 2.0);
        for (int i = 0; i < HostedWorld.STRENGTH_CHECK_STEPS; i++) {
            h.tick();
        }
        assertEquals(0, h.uncheckedStructures());
    }

    @Test
    void groundCanBeMadeBuiltAndBack() {
        HostedWorld h = hosted();
        h.importSection(ORIGIN, ground(Map.of()), CLIMATE);
        GridPos min = new GridPos(0, 6, 0);
        GridPos max = new GridPos(1, 8, 1);
        assertEquals(8, h.setBuilt(min, max, true), "the air in the box cannot be built");
        assertTrue(h.isBuilt(new GridPos(1, 7, 1)));
        assertFalse(h.isBuilt(new GridPos(1, 8, 1)));
        assertEquals(8, h.uncheckedStructures());
        assertEquals(0, h.setBuilt(min, max, true));
        assertEquals(8, h.setBuilt(min, max, false));
        assertFalse(h.isBuilt(new GridPos(1, 7, 1)));
        assertThrows(IllegalStateException.class, () -> h.setBuilt(min, new GridPos(20, 8, 1), true));
    }

    @Test
    void structureFlagsInSnapshotsAreChecked() {
        double[] none = new double[0];
        int[] noBlocks = new int[0];
        List<SectionSnapshot.Entry> palette = List.of();
        assertThrows(IllegalArgumentException.class, () -> new SectionSnapshot(palette, noBlocks, noBlocks, none,
                none, new int[] {3}, new byte[] {0}), "no flags");
        assertThrows(IllegalArgumentException.class, () -> new SectionSnapshot(palette, noBlocks, noBlocks, none,
                none, new int[] {3}, new byte[] {32}), "unknown flags");
        assertThrows(IllegalArgumentException.class, () -> new SectionSnapshot(palette, noBlocks, noBlocks, none,
                none, new int[] {5, 3}, new byte[] {1, 1}), "out of order");
        assertThrows(IllegalArgumentException.class, () -> new SectionSnapshot(palette, noBlocks, noBlocks, none,
                none, new int[] {4096}, new byte[] {1}), "outside the section");
        assertThrows(IllegalArgumentException.class, () -> new SectionSnapshot(palette, noBlocks, noBlocks, none,
                none, new int[] {3}, new byte[0]), "one set of flags per block");
        SectionSnapshot flags = new SectionSnapshot(palette, noBlocks, noBlocks, none, none, new int[] {3, 9},
                new byte[] {1, 5});
        assertEquals(flags, new SectionSnapshot(palette, noBlocks, noBlocks, none, none, new int[] {3, 9},
                new byte[] {1, 5}));
        assertFalse(flags.equals(new SectionSnapshot(palette, noBlocks, noBlocks, none, none)));
    }
}
