package io.github.osir933.anchor.core.physics.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.osir933.anchor.core.matter.Material;
import io.github.osir933.anchor.core.matter.MaterialLibrary;
import io.github.osir933.anchor.core.matter.Mechanics;
import io.github.osir933.anchor.core.physics.structure.StructuralAnalysis.BondResult;
import io.github.osir933.anchor.core.physics.structure.StructuralAnalysis.Mode;
import io.github.osir933.anchor.core.physics.structure.StructuralAnalysis.Result;
import io.github.osir933.anchor.core.space.Direction;
import io.github.osir933.anchor.core.space.GridPos;
import io.github.osir933.anchor.core.units.PhysicalConstants;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Cracked joints that pivot on the edges they press, so that a cracked span can stand as an arch. */
class ArchingTest {

    private static final double ROOM = Mechanics.REFERENCE_K;
    private static final double G = PhysicalConstants.STANDARD_GRAVITY;
    private static final Material GRANITE = MaterialLibrary.GRANITE;
    /** The weight of a block of granite, in newtons. */
    private static final double BLOCK = GRANITE.referenceDensity() * G;
    /** What crushes granite, in pascals. */
    private static final double CRUSHES = GRANITE.mechanics().compressiveStrength(ROOM);

    @Test
    void aSpanCrackedAtItsEndsStandsOnThemAsAnArch() {
        // Clamped at both ends, 28 blocks of granite crack at their ends, which are bent hardest, and the cracked
        // ends cannot hold the bending. They pivot on their bottom edges instead, which the ground keeps from
        // spreading, so the span pushes on them as it sags and stands as an arch with two hinges.
        int n = 28;
        Result r = StructuralAnalysis.analyse(bridge(n, Frame.Joint.INTACT));
        assertTrue(r.settled());
        assertTrue(r.falling().isEmpty());
        assertEquals(List.of(new GridPos(0, 0, 0), new GridPos(n, 0, 0)),
                r.cracks().stream().map(StructuralAnalysis.Crack::pos).toList());
        assertEquals(List.of(new GridPos(0, 0, 0), new GridPos(n, 0, 0)), hinged(r));
        // A beam pinned at both ends e below its axis, which may not spread, pushes on its pins with
        // H = e ∫M₀ dx / (L (r² + e²)), M₀ the bending moment were it simply supported and r² = I / A: the ends turn
        // by ∫M/EI between them, and that must stretch its bottom as far as H shortens it. Each block's weight acts
        // at its centre, so ∫M₀ dx adds up w a (L - a) / 2 for a block a from one end.
        double moment = 0;
        for (int k = 1; k <= n; k++) {
            double a = k - 0.5;
            moment += BLOCK * a * (n - a) / 2;
        }
        // The hinges start next to the bottom edges, where no force can yet press them, then move in to where the
        // edge can bear the thrust with some room to spare: a strip as wide as twice its distance from the edge.
        double first = thrust(0.5 - StructuralAnalysis.HINGE_EDGE / 2, moment, n);
        double inset = first * StructuralAnalysis.HINGE_ROOM / CRUSHES / 2;
        double expected = thrust(0.5 - inset, moment, n);
        // The hinges keep a millionth of how stiffly their joints resisted turning, which so long a span feels a
        // little: some parts in a hundred thousand of its thrust.
        for (BondResult end : List.of(r.bonds().get(0), r.bonds().get(n))) {
            assertTrue(end.holds());
            assertEquals(-expected, end.force(), 1e-4 * expected, end::toString);
            assertTrue(end.load() < 1);
        }
        assertEquals(3, r.rounds());
        // In the middle the thrust presses the span's bottom, where its weight would pull it apart.
        BondResult middle = r.bonds().get(n / 2);
        assertEquals(Frame.Joint.INTACT, middle.state());
        assertTrue(middle.load() < 0.6, middle::toString);
    }

    @Test
    void withoutArchesTheSameSpanFalls() {
        Result r = StructuralAnalysis.analyse(bridge(28, Frame.Joint.INTACT),
                StructuralAnalysis.Settings.defaults().withArching(false));
        assertEquals(2, r.cracks().size());
        assertEquals(28, r.falling().size());
        assertEquals(1, r.rounds());
        assertTrue(hinged(r).isEmpty());
    }

    @Test
    void aLongerSpanCracksInTheMiddleTooAndStandsOnThreeHinges() {
        // At 40 blocks the arch's middle cracks as well and pivots on its top edge: an arch with three hinges, which
        // statics alone settles. The thrust times the height between the hinges must hold the span's weight up as
        // the bending moment of a simply supported beam would, M₀ = w L² / 8.
        int n = 40;
        Result r = StructuralAnalysis.analyse(bridge(n, Frame.Joint.INTACT));
        assertTrue(r.settled());
        assertTrue(r.falling().isEmpty());
        assertEquals(List.of(new GridPos(0, 0, 0), new GridPos(n / 2, 0, 0), new GridPos(n, 0, 0)), hinged(r));
        double thrust = -r.bonds().get(0).force();
        double moment = BLOCK * n * n / 8.0;
        // Each hinge sits as far in from its edge as half the strip that bears the thrust, with up to 5 percent to
        // spare, so the height between the hinges is 1 m less one or two such strips.
        double strip = thrust / CRUSHES;
        assertTrue(thrust * (1 - StructuralAnalysis.HINGE_ROOM * strip) <= moment, () -> thrust + " N");
        assertTrue(thrust * (1 - strip) >= moment, () -> thrust + " N");
        for (BondResult b : r.bonds()) {
            assertEquals(-thrust, b.force(), 1e-9 * thrust);
        }
    }

    @Test
    void ofTheTwoJointsBesideTheMiddleBlockOnlyOnePivots() {
        // With an odd number of blocks the middle is a block, and the joints on either side of it are loaded alike.
        // Were both to pivot, the middle block could swing between them and the arch would fall; one pivots, and
        // that eases the other.
        int n = 41;
        Result r = StructuralAnalysis.analyse(bridge(n, Frame.Joint.INTACT));
        assertTrue(r.settled());
        assertTrue(r.falling().isEmpty());
        List<GridPos> hinges = hinged(r);
        assertEquals(3, hinges.size(), hinges::toString);
        assertEquals(new GridPos(0, 0, 0), hinges.get(0));
        assertTrue(hinges.get(1).equals(new GridPos(20, 0, 0)) || hinges.get(1).equals(new GridPos(21, 0, 0)),
                hinges::toString);
        assertEquals(new GridPos(n, 0, 0), hinges.get(2));
    }

    @Test
    void aFlatArchTooLongForItsRiseSnapsThrough() {
        // The thrust shortens a flat arch, which lowers its middle; at 60 blocks it would sag through its rise, and
        // in second-order theory the frame cannot carry its loads, so it falls. First-order theory cannot see that:
        // it leaves the arch standing, sagging half a metre, and says it doubts that.
        int n = 60;
        Result r = StructuralAnalysis.analyse(bridge(n, Frame.Joint.INTACT));
        assertEquals(n, r.falling().size());
        Result straight = StructuralAnalysis.analyse(bridge(n, Frame.Joint.INTACT),
                StructuralAnalysis.Settings.defaults().withBuckling(false));
        assertTrue(straight.falling().isEmpty());
        assertTrue(straight.notes().stream().anyMatch(s -> s.startsWith("Parts deflect")), () -> straight.notes()
                .toString());
        // At 50 blocks it still stands, bowing under nearly a quarter of what would buckle it.
        Result shorter = StructuralAnalysis.analyse(bridge(50, Frame.Joint.INTACT));
        assertTrue(shorter.falling().isEmpty());
        assertTrue(shorter.buckling() > 1 && shorter.buckling() < 10, () -> "buckling at " + shorter.buckling());
    }

    @Test
    void aShortCrackedSpanSlidesOffItsEnds() {
        // A span cracked through everywhere has nothing but its thrust to press its ends against the ground, and
        // friction to hold them. The thrust is about w L² / 8 for a metre of rise and the ends must hold w L / 2, so
        // with granite's friction of 0.6 a span slides off its ends unless it is longer than 4 / 0.6 = 6.7 m.
        Result six = StructuralAnalysis.analyse(bridge(6, Frame.Joint.CRACKED));
        assertEquals(6, six.falling().size());
        BondResult end = six.bonds().get(0);
        assertEquals(Mode.SLIDING, end.mode());
        Result seven = StructuralAnalysis.analyse(bridge(7, Frame.Joint.CRACKED));
        assertTrue(seven.falling().isEmpty());
        assertTrue(seven.settled());
        BondResult held = seven.bonds().get(0);
        assertTrue(held.hinged());
        assertTrue(held.load() > 0.9 && held.load() < 1, held::toString);
    }

    @Test
    void aFloorHeldOnAllSidesStandsOnItsCrackedEdges() {
        // A floor of netherrack 16 blocks square, held by the ground on all four sides, is bent hardest along the
        // middle of its edges, which crack. They pivot where they press the ground, and the floor stands on them,
        // arching both ways; letting them go would drop it all.
        int n = 16;
        Frame floor = floor(MaterialLibrary.NETHERRACK, n);
        Result r = StructuralAnalysis.analyse(floor);
        assertTrue(r.settled());
        assertTrue(r.falling().isEmpty());
        assertFalse(r.cracks().isEmpty());
        for (StructuralAnalysis.Crack c : r.cracks()) {
            assertTrue(edge(c.pos(), c.axis(), n), c::toString);
        }
        // The hinges move inward as the thrust through them grows, along edges the force wanders along, yet the
        // floor breaks as symmetrically as it is built.
        List<List<Integer>> hinges = r.bonds().stream().filter(BondResult::hinged).map(b -> middle(b.pos(), b.axis()))
                .toList();
        assertFalse(hinges.isEmpty());
        assertEquals(new HashSet<>(hinges), new HashSet<>(hinges.stream()
                .map(m -> List.of(2 * (n + 1) - m.get(0), m.get(1))).toList()));
        assertEquals(new HashSet<>(hinges), new HashSet<>(hinges.stream()
                .map(m -> List.of(m.get(1) + 2, m.get(0) - 2)).toList()));
        Result loose = StructuralAnalysis.analyse(floor, StructuralAnalysis.Settings.defaults().withArching(false));
        assertEquals(n * n, loose.falling().size());
    }

    @Test
    void anOverhangStillFallsWhenItsRootCracks() {
        // A cracked root holds nothing beyond it but the overhang itself, so pivoting would only let it swing down.
        Frame.Builder b = Frame.builder().ground(new GridPos(0, 0, 0), GRANITE.mechanics(), ROOM, 1);
        int n = 12;
        for (int k = 1; k <= n; k++) {
            GridPos pos = new GridPos(k, 0, 0);
            b.block(pos, GRANITE.mechanics(), ROOM, 1, GRANITE.referenceDensity());
            b.bond(pos, Direction.WEST, Contact.FULL, Frame.Joint.INTACT);
        }
        Result r = StructuralAnalysis.analyse(b.build());
        assertEquals(1, r.cracks().size());
        assertEquals(n, r.falling().size());
        assertEquals(1, r.rounds());
        assertTrue(hinged(r).isEmpty());
        assertFalse(r.bonds().get(0).holds());
    }

    /** Returns the thrust of a span pinned e below its axis at both ends, from ∫M₀ dx, for a unit square section. */
    private static double thrust(double e, double moment, double length) {
        return e * moment / (length * (1.0 / 12.0 + e * e));
    }

    /** Returns where the joints that pivot on an edge are, in frame order. */
    private static List<GridPos> hinged(Result r) {
        return r.bonds().stream().filter(BondResult::hinged).map(BondResult::pos).toList();
    }

    /** Returns whether a joint joins a floor from {@link #floor} to the ground around it. */
    private static boolean edge(GridPos pos, int axis, int n) {
        return axis == 0 ? pos.x() == 0 || pos.x() == n : axis == 2 && (pos.z() == -1 || pos.z() == n - 1);
    }

    /** Returns where the middle of a joint lies across a floor, x and z in half blocks. */
    private static List<Integer> middle(GridPos pos, int axis) {
        return List.of(2 * pos.x() + (axis == 0 ? 1 : 0), 2 * pos.z() + (axis == 2 ? 1 : 0));
    }

    /**
     * Returns a floor of blocks a block deep, {@code n} blocks square from x = 1 and z = 0, held by ground of the same
     * matter all around it.
     */
    private static Frame floor(Material material, int n) {
        Frame.Builder b = Frame.builder();
        for (int k = 0; k < n; k++) {
            b.ground(new GridPos(0, 0, k), material.mechanics(), ROOM, 1);
            b.ground(new GridPos(n + 1, 0, k), material.mechanics(), ROOM, 1);
            b.ground(new GridPos(k + 1, 0, -1), material.mechanics(), ROOM, 1);
            b.ground(new GridPos(k + 1, 0, n), material.mechanics(), ROOM, 1);
        }
        for (int x = 1; x <= n; x++) {
            for (int z = 0; z < n; z++) {
                GridPos pos = new GridPos(x, 0, z);
                b.block(pos, material.mechanics(), ROOM, 1, material.referenceDensity());
                b.bond(pos, Direction.WEST, Contact.FULL, Frame.Joint.INTACT);
                b.bond(pos, Direction.NORTH, Contact.FULL, Frame.Joint.INTACT);
                if (x == n) {
                    b.bond(pos, Direction.EAST, Contact.FULL, Frame.Joint.INTACT);
                }
                if (z == n - 1) {
                    b.bond(pos, Direction.SOUTH, Contact.FULL, Frame.Joint.INTACT);
                }
            }
        }
        return b.build();
    }

    /** Returns a straight span of {@code n} blocks of granite between two blocks of granite ground. */
    private static Frame bridge(int n, Frame.Joint joints) {
        Frame.Builder b = Frame.builder()
                .ground(new GridPos(0, 0, 0), GRANITE.mechanics(), ROOM, 1)
                .ground(new GridPos(n + 1, 0, 0), GRANITE.mechanics(), ROOM, 1);
        for (int k = 1; k <= n; k++) {
            GridPos pos = new GridPos(k, 0, 0);
            b.block(pos, GRANITE.mechanics(), ROOM, 1, GRANITE.referenceDensity());
            b.bond(pos, Direction.WEST, Contact.FULL, joints);
        }
        b.bond(new GridPos(n, 0, 0), Direction.EAST, Contact.FULL, joints);
        return b.build();
    }
}
