package io.github.osir933.anchor.core.physics.structure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.osir933.anchor.core.matter.Material;
import io.github.osir933.anchor.core.matter.MaterialLibrary;
import io.github.osir933.anchor.core.matter.Mechanics;
import io.github.osir933.anchor.core.physics.structure.StructuralAnalysis.BondResult;
import io.github.osir933.anchor.core.physics.structure.StructuralAnalysis.Crack;
import io.github.osir933.anchor.core.physics.structure.StructuralAnalysis.Mode;
import io.github.osir933.anchor.core.physics.structure.StructuralAnalysis.Result;
import io.github.osir933.anchor.core.space.Direction;
import io.github.osir933.anchor.core.space.GridPos;
import io.github.osir933.anchor.core.units.PhysicalConstants;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StructuralAnalysisTest {

    private static final double ROOM = Mechanics.REFERENCE_K;
    private static final double G = PhysicalConstants.STANDARD_GRAVITY;
    private static final GridPos ORIGIN = new GridPos(0, 0, 0);

    @Test
    void cantileverDeflectsAsTimoshenkoTheorySays() {
        int n = 5;
        Material granite = MaterialLibrary.GRANITE;
        Result result = StructuralAnalysis.analyse(cantilever(granite, n, Direction.EAST, ROOM, null));
        assertTrue(result.settled());
        assertTrue(result.falling().isEmpty());
        Mechanics m = granite.mechanics();
        double e = m.youngsModulus(ROOM);
        double ei = e / 12.0;
        double kga = m.shearModulus(ROOM) * 5.0 / 6.0;
        double p = granite.referenceDensity() * G;
        // Each block's weight acts at its centre, k - 1/2 metres out from the clamped face.
        double tip = n - 0.5;
        double deflection = 0;
        double slope = 0;
        for (int k = 1; k <= n; k++) {
            double x = k - 0.5;
            deflection += p * (x * x * (3 * tip - x) / (6 * ei) + x / kga);
            slope += p * x * x / (2 * ei);
        }
        double[] move = result.block(new GridPos(n, 0, 0)).displacement();
        double[] turn = result.block(new GridPos(n, 0, 0)).rotation();
        assertEquals(-deflection, move[1], 1e-9 * deflection);
        assertEquals(0.0, move[0], 1e-9 * deflection);
        assertEquals(0.0, move[2], 1e-9 * deflection);
        assertEquals(-slope, turn[2], 1e-9 * slope);
    }

    @Test
    void cantileverBendsTheSameWhicheverWayItPoints() {
        Material granite = MaterialLibrary.GRANITE;
        double east = StructuralAnalysis.analyse(cantilever(granite, 6, Direction.EAST, ROOM, null))
                .block(new GridPos(6, 0, 0)).displacement()[1];
        for (Direction d : List.of(Direction.WEST, Direction.NORTH, Direction.SOUTH)) {
            Result result = StructuralAnalysis.analyse(cantilever(granite, 6, d, ROOM, null));
            GridPos tip = new GridPos(6 * d.dx(), 0, 6 * d.dz());
            assertEquals(east, result.block(tip).displacement()[1], 1e-9 * Math.abs(east), d.name());
        }
    }

    @Test
    void columnShortensUnderItsOwnWeight() {
        int n = 10;
        Material granite = MaterialLibrary.GRANITE;
        Frame.Builder b = Frame.builder().ground(ORIGIN, null, ROOM, 1);
        double mass = granite.referenceDensity();
        GridPos below = ORIGIN;
        for (int k = 1; k <= n; k++) {
            GridPos pos = new GridPos(0, k, 0);
            b.block(pos, granite.mechanics(), ROOM, 1, mass).bond(below, Direction.UP, Contact.FULL,
                    Frame.Joint.INTACT);
            below = pos;
        }
        Result result = StructuralAnalysis.analyse(b.build());
        // The top drops by the sum of each part's squeeze: rho g n^2 / 2E for a column of unit section.
        double expected = mass * G * n * n / (2 * granite.mechanics().youngsModulus(ROOM));
        assertEquals(-expected, result.block(below).displacement()[1], 1e-9 * expected);
        assertTrue(result.cracks().isEmpty());
    }

    @Test
    void graniteHoldsElevenBlocksOutAndBreaksAtTwelve() {
        Material granite = MaterialLibrary.GRANITE;
        Mechanics m = granite.mechanics();
        Result eleven = StructuralAnalysis.analyse(cantilever(granite, 11, Direction.EAST, ROOM, m));
        assertTrue(eleven.settled());
        assertTrue(eleven.cracks().isEmpty());
        assertTrue(eleven.falling().isEmpty());
        // At the root the top edge is stretched by 3 rho g n^2 for a cantilever of unit section.
        BondResult root = eleven.bonds().get(0);
        assertEquals(ORIGIN, root.pos());
        double stress = 3 * granite.referenceDensity() * G * 11 * 11;
        assertEquals(stress / m.tensileStrength(ROOM), root.load(), 1e-9);
        assertEquals(Mode.TENSION, root.mode());
        assertEquals(root.load(), eleven.block(new GridPos(1, 0, 0)).load(), 1e-15);

        Result twelve = StructuralAnalysis.analyse(cantilever(granite, 12, Direction.EAST, ROOM, m));
        assertTrue(twelve.settled());
        assertEquals(1, twelve.cracks().size());
        Crack crack = twelve.cracks().get(0);
        assertEquals(ORIGIN, crack.pos());
        assertEquals(0, crack.axis());
        assertEquals(Mode.TENSION, crack.mode());
        assertEquals(12, twelve.falling().size());
        assertFalse(twelve.bonds().get(0).holds());
        assertEquals(Frame.Joint.CRACKED, twelve.bonds().get(0).state());
        assertTrue(twelve.block(new GridPos(12, 0, 0)).fell());
    }

    @Test
    void ironYieldsAtThirtyTwoBlocksAndMuchSoonerWhenHot() {
        Material iron = MaterialLibrary.IRON;
        assertTrue(StructuralAnalysis.analyse(cantilever(iron, 31, Direction.EAST, ROOM, null)).falling().isEmpty());
        Result cold = StructuralAnalysis.analyse(cantilever(iron, 32, Direction.EAST, ROOM, null));
        assertEquals(Mode.YIELDING, cold.cracks().get(0).mode());
        assertEquals(32, cold.falling().size());
        // At 600 °C steel keeps 47 percent of its yield strength, so the reach shrinks by the square root.
        double hot = 873.15;
        assertTrue(StructuralAnalysis.analyse(cantilever(iron, 21, Direction.EAST, hot, null)).falling().isEmpty());
        assertEquals(22, StructuralAnalysis.analyse(cantilever(iron, 22, Direction.EAST, hot, null)).falling().size());
    }

    @Test
    void aLongIronOverhangSagsAndTheResultSaysSo() {
        Result result = StructuralAnalysis.analyse(cantilever(MaterialLibrary.IRON, 31, Direction.EAST, ROOM, null));
        assertTrue(result.falling().isEmpty());
        assertTrue(result.block(new GridPos(31, 0, 0)).displacement()[1] < -StructuralAnalysis.LARGE_DEFLECTION_M);
        assertTrue(result.notes().stream().anyMatch(s -> s.contains("deflect")), result.notes().toString());
        Result granite = StructuralAnalysis.analyse(cantilever(MaterialLibrary.GRANITE, 5, Direction.EAST, ROOM,
                null));
        assertTrue(granite.notes().isEmpty(), granite.notes().toString());
    }

    @Test
    void partlyMoltenBlocksAreWeaker() {
        Material granite = MaterialLibrary.GRANITE;
        Frame.Builder whole = Frame.builder().ground(ORIGIN, null, ROOM, 1);
        Frame.Builder half = Frame.builder().ground(ORIGIN, null, ROOM, 1);
        for (int k = 1; k <= 9; k++) {
            GridPos pos = new GridPos(k, 0, 0);
            whole.block(pos, granite.mechanics(), ROOM, 1.0, granite.referenceDensity());
            half.block(pos, granite.mechanics(), ROOM, 0.5, granite.referenceDensity());
            whole.bond(pos, Direction.WEST, Contact.FULL, Frame.Joint.INTACT);
            half.bond(pos, Direction.WEST, Contact.FULL, Frame.Joint.INTACT);
        }
        assertTrue(StructuralAnalysis.analyse(whole.build()).falling().isEmpty());
        assertEquals(9, StructuralAnalysis.analyse(half.build()).falling().size());
    }

    @Test
    void sandStandsOnTheGroundButNotOutFromAWall() {
        Material sand = MaterialLibrary.SAND;
        double mass = sand.referenceDensity();
        Frame.Builder column = Frame.builder().ground(ORIGIN, MaterialLibrary.SOIL.mechanics(), ROOM, 1);
        GridPos below = ORIGIN;
        for (int k = 1; k <= 5; k++) {
            GridPos pos = new GridPos(0, k, 0);
            column.block(pos, sand.mechanics(), ROOM, 1, mass).bond(pos, Direction.DOWN, Contact.FULL,
                    Frame.Joint.INTACT);
            below = pos;
        }
        Result standing = StructuralAnalysis.analyse(column.build());
        assertTrue(standing.falling().isEmpty());
        assertTrue(standing.cracks().isEmpty());
        assertNotNull(standing.block(below));

        Frame wall = Frame.builder().ground(ORIGIN, MaterialLibrary.GRANITE.mechanics(), ROOM, 1)
                .block(new GridPos(1, 0, 0), sand.mechanics(), ROOM, 1, mass)
                .bond(ORIGIN, Direction.EAST, Contact.FULL, Frame.Joint.INTACT)
                .build();
        Result fallen = StructuralAnalysis.analyse(wall);
        assertEquals(List.of(new GridPos(1, 0, 0)), fallen.falling());
        // Sand never bonds, so nothing cracks: the contact just lets go.
        assertTrue(fallen.cracks().isEmpty());
        assertFalse(fallen.bonds().get(0).holds());
    }

    @Test
    void aBlockWithNothingToRestOnFalls() {
        Frame frame = Frame.builder()
                .block(new GridPos(0, 5, 0), MaterialLibrary.GRANITE.mechanics(), ROOM, 1, 2630)
                .ground(ORIGIN, null, ROOM, 1)
                .build();
        Result result = StructuralAnalysis.analyse(frame);
        assertEquals(List.of(new GridPos(0, 5, 0)), result.falling());
        assertEquals(0, result.rounds());
        assertTrue(result.settled());
    }

    @Test
    void aCrackedJointBearsPressingButNotAnOverhang() {
        Mechanics granite = MaterialLibrary.GRANITE.mechanics();
        Frame resting = Frame.builder().ground(ORIGIN, granite, ROOM, 1)
                .block(new GridPos(0, 1, 0), granite, ROOM, 1, 2630)
                .bond(ORIGIN, Direction.UP, Contact.FULL, Frame.Joint.CRACKED)
                .build();
        assertTrue(StructuralAnalysis.analyse(resting).falling().isEmpty());
        Frame overhang = Frame.builder().ground(ORIGIN, granite, ROOM, 1)
                .block(new GridPos(1, 0, 0), granite, ROOM, 1, 2630)
                .bond(ORIGIN, Direction.EAST, Contact.FULL, Frame.Joint.CRACKED)
                .build();
        Result result = StructuralAnalysis.analyse(overhang);
        assertEquals(1, result.falling().size());
        assertTrue(result.cracks().isEmpty());
        assertEquals(Mode.TIPPING, result.bonds().get(0).mode());
    }

    @Test
    void stoneHangsFromStoneButNotFromSoil() {
        Mechanics granite = MaterialLibrary.GRANITE.mechanics();
        GridPos below = new GridPos(0, -1, 0);
        Frame fromStone = Frame.builder().ground(ORIGIN, granite, ROOM, 1)
                .block(below, granite, ROOM, 1, 2630)
                .bond(ORIGIN, Direction.DOWN, Contact.FULL, Frame.Joint.INTACT)
                .build();
        assertTrue(StructuralAnalysis.analyse(fromStone).falling().isEmpty());
        Frame fromSoil = Frame.builder().ground(ORIGIN, MaterialLibrary.SOIL.mechanics(), ROOM, 1)
                .block(below, granite, ROOM, 1, 2630)
                .bond(ORIGIN, Direction.DOWN, Contact.FULL, Frame.Joint.INTACT)
                .build();
        Result result = StructuralAnalysis.analyse(fromSoil);
        assertEquals(List.of(below), result.falling());
        assertEquals(Mode.TENSION, result.cracks().get(0).mode());
    }

    @Test
    void theGroundIsNotCrushedByATallTower() {
        // Twenty blocks of granite press on the soil at half a megapascal, ten times what loose soil could
        // take on its own; hemmed in by the earth around it, the ground holds.
        Material granite = MaterialLibrary.GRANITE;
        Frame.Builder b = Frame.builder().ground(ORIGIN, MaterialLibrary.SOIL.mechanics(), ROOM, 1);
        for (int k = 1; k <= 20; k++) {
            GridPos pos = new GridPos(0, k, 0);
            b.block(pos, granite.mechanics(), ROOM, 1, granite.referenceDensity()).bond(pos, Direction.DOWN,
                    Contact.FULL, Frame.Joint.INTACT);
        }
        Result result = StructuralAnalysis.analyse(b.build());
        assertTrue(result.falling().isEmpty());
        assertTrue(result.cracks().isEmpty());
    }

    @Test
    void aPillarOfLooseSoilCrushesItself() {
        // Built of soil, the bottom block of a pillar is not hemmed in: three blocks crush it.
        Material soil = MaterialLibrary.SOIL;
        Frame.Builder b = Frame.builder().ground(ORIGIN, soil.mechanics(), ROOM, 1);
        for (int k = 1; k <= 3; k++) {
            GridPos pos = new GridPos(0, k, 0);
            b.block(pos, soil.mechanics(), ROOM, 1, soil.referenceDensity()).bond(pos, Direction.DOWN, Contact.FULL,
                    Frame.Joint.INTACT);
        }
        Result result = StructuralAnalysis.analyse(b.build());
        // The joint to the ground cracks on the soil block's side, then the crushed block lets the pillar down.
        assertEquals(Mode.COMPRESSION, result.cracks().get(0).mode());
        assertEquals(ORIGIN, result.cracks().get(0).pos());
        assertEquals(3, result.falling().size());
    }

    @Test
    void aSymmetricBridgeBreaksAtBothEndsTogether() {
        Material granite = MaterialLibrary.GRANITE;
        // Clamped at both ends, a uniform beam is bent hardest at its ends, by w L^2 / 12: 27 blocks hold.
        Result holding = StructuralAnalysis.analyse(bridge(granite, 27));
        assertTrue(holding.falling().isEmpty());
        assertTrue(holding.cracks().isEmpty());
        Result breaking = StructuralAnalysis.analyse(bridge(granite, 28));
        assertEquals(2, breaking.cracks().size());
        assertEquals(List.of(ORIGIN, new GridPos(28, 0, 0)),
                breaking.cracks().stream().map(Crack::pos).toList());
        double first = breaking.cracks().get(0).load();
        assertEquals(first, breaking.cracks().get(1).load(), 1e-9 * first);
        // Cracked ends do not wedge into an arch, so the whole span falls, with no need to solve again.
        assertEquals(28, breaking.falling().size());
        assertEquals(1, breaking.rounds());
    }

    @Test
    void resultsDoNotDependOnTheOrderBlocksAreAdded() {
        Mechanics granite = MaterialLibrary.GRANITE.mechanics();
        Mechanics wood = MaterialLibrary.HARDWOOD.mechanics();
        // A tower with an arm: (0, 1..6, 0) up from the ground, then (1..4, 6, 0) out.
        List<GridPos> positions = new ArrayList<>();
        for (int k = 1; k <= 6; k++) {
            positions.add(new GridPos(0, k, 0));
        }
        for (int k = 1; k <= 4; k++) {
            positions.add(new GridPos(k, 6, 0));
        }
        Frame.Builder forward = Frame.builder().ground(ORIGIN, null, ROOM, 1);
        for (GridPos p : positions) {
            forward.block(p, p.x() == 0 ? granite : wood, ROOM, 1, p.x() == 0 ? 2630 : 700);
        }
        for (GridPos p : positions) {
            forward.bond(p, p.x() == 0 ? Direction.DOWN : Direction.WEST, Contact.FULL, Frame.Joint.INTACT);
        }
        Frame.Builder backward = Frame.builder();
        for (int i = positions.size() - 1; i >= 0; i--) {
            GridPos p = positions.get(i);
            GridPos neighbour = p.x() == 0 ? p.offset(Direction.DOWN) : p.offset(Direction.WEST);
            backward.bond(neighbour, p.x() == 0 ? Direction.UP : Direction.EAST, Contact.FULL, Frame.Joint.INTACT);
            backward.block(p, p.x() == 0 ? granite : wood, ROOM, 1, p.x() == 0 ? 2630 : 700);
        }
        backward.ground(ORIGIN, null, ROOM, 1);
        Result a = StructuralAnalysis.analyse(forward.build());
        Result b = StructuralAnalysis.analyse(backward.build());
        assertEquals(a.blocks().size(), b.blocks().size());
        for (int i = 0; i < a.blocks().size(); i++) {
            assertEquals(a.blocks().get(i).pos(), b.blocks().get(i).pos());
            assertArrayEquals(a.blocks().get(i).displacement(), b.blocks().get(i).displacement());
            assertArrayEquals(a.blocks().get(i).rotation(), b.blocks().get(i).rotation());
        }
        assertEquals(a.bonds(), b.bonds());
        // The arm pulls the tower over: it leans towards the arm.
        assertTrue(a.block(new GridPos(0, 6, 0)).displacement()[0] > 0);
    }

    @Test
    void oneArmBreaksOffAndTheRestStands() {
        Result result = StructuralAnalysis.analyse(tee(12, 9));
        assertTrue(result.settled());
        // The long arm's root cracks and lets go; solved again without it, the tower and short arm hold.
        assertEquals(2, result.rounds());
        assertEquals(List.of(new GridPos(0, 2, 0)), result.cracks().stream().map(Crack::pos).toList());
        assertEquals(12, result.falling().size());
        assertTrue(result.falling().stream().allMatch(p -> p.x() > 0));
        assertFalse(result.block(new GridPos(-9, 2, 0)).fell());
        assertTrue(result.block(new GridPos(-9, 2, 0)).load() < 1);
    }

    @Test
    void aShortRoundLimitLeavesTheResultUnsettled() {
        StructuralAnalysis.Settings oneRound = new StructuralAnalysis.Settings(G, 1, 0.02, 1e-4);
        Result result = StructuralAnalysis.analyse(tee(12, 9), oneRound);
        assertFalse(result.settled());
        assertEquals(1, result.rounds());
        assertEquals(12, result.falling().size());
        assertTrue(result.notes().stream().anyMatch(s -> s.contains("giving way")), result.notes().toString());
    }

    @Test
    void rejectsSettingsThatMakeNoSense() {
        assertThrows(IllegalArgumentException.class, () -> new StructuralAnalysis.Settings(-1, 64, 0.02, 1e-4));
        assertThrows(IllegalArgumentException.class, () -> new StructuralAnalysis.Settings(G, 0, 0.02, 1e-4));
        assertThrows(IllegalArgumentException.class, () -> new StructuralAnalysis.Settings(G, 64, 1.0, 1e-4));
        assertThrows(IllegalArgumentException.class, () -> new StructuralAnalysis.Settings(G, 64, 0.02, 0));
    }

    /**
     * Returns a straight horizontal overhang of {@code n} blocks of one material, reaching out from a block of
     * ground at the origin.
     */
    private static Frame cantilever(Material material, int n, Direction direction, double temperatureK,
            Mechanics ground) {
        Frame.Builder b = Frame.builder().ground(ORIGIN, ground, temperatureK, 1);
        GridPos previous = ORIGIN;
        for (int k = 1; k <= n; k++) {
            GridPos pos = previous.offset(direction);
            b.block(pos, material.mechanics(), temperatureK, 1, material.referenceDensity());
            b.bond(previous, direction, Contact.FULL, Frame.Joint.INTACT);
            previous = pos;
        }
        return b.build();
    }

    /**
     * Returns a granite tower two blocks tall with an arm of {@code east} blocks reaching east from its top and
     * one of {@code west} blocks reaching west.
     */
    private static Frame tee(int east, int west) {
        Mechanics granite = MaterialLibrary.GRANITE.mechanics();
        double mass = MaterialLibrary.GRANITE.referenceDensity();
        GridPos top = new GridPos(0, 2, 0);
        Frame.Builder b = Frame.builder().ground(ORIGIN, null, ROOM, 1)
                .block(new GridPos(0, 1, 0), granite, ROOM, 1, mass)
                .block(top, granite, ROOM, 1, mass)
                .bond(ORIGIN, Direction.UP, Contact.FULL, Frame.Joint.INTACT)
                .bond(top, Direction.DOWN, Contact.FULL, Frame.Joint.INTACT);
        for (int k = 1; k <= east; k++) {
            b.block(new GridPos(k, 2, 0), granite, ROOM, 1, mass)
                    .bond(new GridPos(k, 2, 0), Direction.WEST, Contact.FULL, Frame.Joint.INTACT);
        }
        for (int k = 1; k <= west; k++) {
            b.block(new GridPos(-k, 2, 0), granite, ROOM, 1, mass)
                    .bond(new GridPos(-k, 2, 0), Direction.EAST, Contact.FULL, Frame.Joint.INTACT);
        }
        return b.build();
    }

    /** Returns a straight span of {@code n} blocks between two blocks of ground of the same material. */
    private static Frame bridge(Material material, int n) {
        Frame.Builder b = Frame.builder()
                .ground(ORIGIN, material.mechanics(), ROOM, 1)
                .ground(new GridPos(n + 1, 0, 0), material.mechanics(), ROOM, 1);
        for (int k = 1; k <= n; k++) {
            GridPos pos = new GridPos(k, 0, 0);
            b.block(pos, material.mechanics(), ROOM, 1, material.referenceDensity());
            b.bond(pos, Direction.WEST, Contact.FULL, Frame.Joint.INTACT);
        }
        b.bond(new GridPos(n, 0, 0), Direction.EAST, Contact.FULL, Frame.Joint.INTACT);
        return b.build();
    }
}
