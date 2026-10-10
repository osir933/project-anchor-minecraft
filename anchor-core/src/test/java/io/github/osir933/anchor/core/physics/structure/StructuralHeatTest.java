package io.github.osir933.anchor.core.physics.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.osir933.anchor.core.matter.Mechanics;
import io.github.osir933.anchor.core.matter.PropertyCurve;
import io.github.osir933.anchor.core.matter.Source;
import io.github.osir933.anchor.core.physics.structure.StructuralAnalysis.BondResult;
import io.github.osir933.anchor.core.physics.structure.StructuralAnalysis.Crack;
import io.github.osir933.anchor.core.physics.structure.StructuralAnalysis.Mode;
import io.github.osir933.anchor.core.physics.structure.StructuralAnalysis.Result;
import io.github.osir933.anchor.core.space.Direction;
import io.github.osir933.anchor.core.space.GridPos;
import io.github.osir933.anchor.core.units.PhysicalConstants;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Structures that heat stretches, and how their joints take it. */
class StructuralHeatTest {

    private static final Source TEST = new Source("test", "test", Source.DataQuality.ESTIMATED);
    private static final double ROOM = Mechanics.REFERENCE_K;
    private static final double G = PhysicalConstants.STANDARD_GRAVITY;
    private static final double MASS = 2700.0;
    private static final GridPos ORIGIN = new GridPos(0, 0, 0);

    /** A brittle stone whose properties do not change with temperature, so that results can be worked by hand. */
    private static final Mechanics STONE = new Mechanics(Mechanics.Failure.BRITTLE,
            PropertyCurve.constant(50e9, TEST), 0.25, PropertyCurve.constant(10e6, TEST),
            PropertyCurve.constant(150e6, TEST), 0.6, PropertyCurve.constant(8e-6, TEST), 1.5e6, TEST);

    /** A metal that yields at 250 MPa, whatever its temperature. */
    private static final Mechanics METAL = new Mechanics(Mechanics.Failure.DUCTILE,
            PropertyCurve.constant(200e9, TEST), 0.3, PropertyCurve.constant(250e6, TEST),
            PropertyCurve.constant(250e6, TEST), 0.6, PropertyCurve.constant(12e-6, TEST), Double.NaN, TEST);

    private static final StructuralAnalysis.Settings WEIGHTLESS = new StructuralAnalysis.Settings(0, 64, 0.02, 1e-4);

    @Test
    void aBarHeldAtBothEndsPushesOnThemByItsStiffnessTimesItsStrain() {
        // Stretched by 4e-4 and held to its length, the bar is pressed by E times that: 20 MPa, 2/15 of what
        // crushes it.
        Result r = StructuralAnalysis.analyse(span(STONE, 6, new Frame.Expansion(4e-4, 0, 0, 0), Frame.Joint.INTACT),
                WEIGHTLESS);
        assertTrue(r.cracks().isEmpty());
        assertEquals(7, r.bonds().size());
        for (BondResult b : r.bonds()) {
            assertEquals(20e6 / 150e6, b.load(), 1e-9, b::toString);
            assertEquals(Mode.COMPRESSION, b.mode());
            assertEquals(0.0, b.withoutHeat(), 1e-12);
        }
        // Each joint is held to its length, so no block moves.
        for (StructuralAnalysis.BlockResult b : r.blocks()) {
            assertEquals(0.0, b.displacement()[0], 1e-12);
        }
    }

    @Test
    void aBarFreeAtOneEndJustGrows() {
        int n = 5;
        double strain = 4e-4;
        Result r = StructuralAnalysis.analyse(cantilever(n, new Frame.Expansion(strain, 0, 0, 0)), WEIGHTLESS);
        for (BondResult b : r.bonds()) {
            assertEquals(0.0, b.load(), 1e-9, b::toString);
        }
        // From the face it is clamped at to the far block's centre is n - 1/2 metres of stretched stone.
        double[] move = r.block(new GridPos(n, 0, 0)).displacement();
        assertEquals(strain * (n - 0.5), move[0], 1e-12);
        assertEquals(0.0, move[1], 1e-12);
    }

    @Test
    void aBarHotterOnTopBendsDownIfNothingHoldsIt() {
        int n = 5;
        double gradient = 2e-4;
        Result r = StructuralAnalysis.analyse(cantilever(n, new Frame.Expansion(0, 0, gradient, 0)), WEIGHTLESS);
        for (BondResult b : r.bonds()) {
            assertEquals(0.0, b.load(), 1e-9, b::toString);
        }
        // A curvature of the gradient over n - 1/2 metres: the far end turns by kL and drops by kL^2/2.
        double length = n - 0.5;
        StructuralAnalysis.BlockResult tip = r.block(new GridPos(n, 0, 0));
        assertEquals(-gradient * length * length / 2, tip.displacement()[1], 1e-12);
        assertEquals(-gradient * length, tip.rotation()[2], 1e-12);
    }

    @Test
    void aBarHotterOnOneSideAndHeldStraightIsBentByItsStiffnessTimesThatCurvature() {
        // Held straight, a curvature of 2e-4 per metre takes a moment EI k, whose stress at the faces is E k / 2:
        // 5 MPa, pulling on the cooler side, half its tensile strength. The same holds sideways, and either way up.
        for (int axis : new int[] {1, 2}) {
            for (double gradient : new double[] {2e-4, -2e-4}) {
                Frame.Expansion bent = axis == 1 ? new Frame.Expansion(0, 0, gradient, 0)
                        : new Frame.Expansion(0, 0, 0, gradient);
                Result r = StructuralAnalysis.analyse(span(STONE, 4, bent, Frame.Joint.INTACT), WEIGHTLESS);
                for (BondResult b : r.bonds()) {
                    assertEquals(0.5, b.load(), 1e-9, () -> axis + " " + gradient + " " + b);
                    assertEquals(Mode.TENSION, b.mode());
                }
                for (StructuralAnalysis.BlockResult b : r.blocks()) {
                    for (int k = 0; k < 3; k++) {
                        assertEquals(0.0, b.displacement()[k], 1e-12);
                        assertEquals(0.0, b.rotation()[k], 1e-12);
                    }
                }
            }
        }
    }

    @Test
    void aSpanThatHoldsItsWeightFallsWhenColdShrinksIt() {
        int n = 20;
        // Clamped at both ends, the weight of its blocks bends the ends by w (L^2 / 12 + 1/24), w at each block's
        // centre, which stretches their top edge by six times that: 5.3 MPa.
        double bending = MASS * G * (n * n / 2.0 + 0.25);
        Result warm = StructuralAnalysis.analyse(span(STONE, n, Frame.Expansion.NONE, Frame.Joint.INTACT));
        assertTrue(warm.cracks().isEmpty());
        assertEquals(bending / 10e6, warm.bonds().get(0).load(), 1e-9);
        // 15 K of cold would shorten it by 1.2e-4: held to its length, it is pulled by 6 MPa more, and the ends
        // crack. Cracked, they cannot hold the pull, so they let go and the whole span falls.
        Result cold = StructuralAnalysis.analyse(span(STONE, n, new Frame.Expansion(-1.2e-4, 0, 0, 0),
                Frame.Joint.INTACT));
        assertEquals(List.of(ORIGIN, new GridPos(n, 0, 0)), cold.cracks().stream().map(Crack::pos).toList());
        for (Crack c : cold.cracks()) {
            assertEquals(Mode.TENSION, c.mode());
            assertEquals((bending + 6e6) / 10e6, c.load(), 1e-9);
            assertTrue(c.heat(), "its weight alone would have left it whole: " + c);
        }
        assertEquals(n, cold.falling().size());
        BondResult end = cold.bonds().get(0);
        assertEquals(Frame.Joint.CRACKED, end.state());
        assertEquals(Mode.PULLED_APART, end.mode());
    }

    @Test
    void heatThatPressesACrackedSpanHoldsItUp() {
        // Cracked through at every joint, a span hangs only by friction, which needs the joints pressed together.
        Result loose = StructuralAnalysis.analyse(span(STONE, 3, Frame.Expansion.NONE, Frame.Joint.CRACKED));
        assertEquals(3, loose.falling().size());
        // Heat that would stretch it presses its joints together instead, and friction then holds it.
        Result pressed = StructuralAnalysis.analyse(span(STONE, 3, new Frame.Expansion(1e-4, 0, 0, 0),
                Frame.Joint.CRACKED));
        assertTrue(pressed.falling().isEmpty());
        assertTrue(pressed.settled());
        for (BondResult b : pressed.bonds()) {
            assertTrue(b.holds());
            assertTrue(b.load() < 0.05, b::toString);
            assertTrue(b.withoutHeat() > 1, "without heat the joint would slide: " + b);
        }
    }

    @Test
    void aBlockThatHeatPushesAlongTheGroundSlidesAHairlineAndStays() {
        // A warm block pushes a loose one resting beside it on the ground. Held there by friction alone, the loose
        // one cannot stop the push: about 50 kN against the 16 kN friction gives it, so it slides, by the few
        // thousandths of a millimetre the push needs, and then rests as before. The contact between them slips up
        // and down a hairline as the warm block grows, too.
        GridPos warm = new GridPos(0, 1, 0);
        GridPos loose = new GridPos(1, 1, 0);
        Frame frame = Frame.builder().ground(ORIGIN, null, ROOM, 1).ground(new GridPos(1, 0, 0), null, ROOM, 1)
                .block(warm, STONE, ROOM, 1, MASS, new Frame.Expansion(1e-5, 0, 0, 0))
                .block(loose, STONE, ROOM, 1, MASS, Frame.Expansion.NONE)
                .bond(ORIGIN, Direction.UP, Contact.FULL, Frame.Joint.INTACT)
                .bond(new GridPos(1, 0, 0), Direction.UP, Contact.FULL, Frame.Joint.CRACKED)
                .bond(warm, Direction.EAST, Contact.FULL, Frame.Joint.CRACKED)
                .build();
        Result r = StructuralAnalysis.analyse(frame);
        assertTrue(r.falling().isEmpty());
        assertTrue(r.cracks().isEmpty());
        assertTrue(r.settled());
        for (BondResult b : r.bonds()) {
            assertTrue(b.holds(), b::toString);
            assertTrue(b.load() < 0.05, b::toString);
        }
        assertTrue(r.block(loose).displacement()[0] > 5e-7, "the warm block pushed it east");
    }

    @Test
    void heatThatCrushesTheEdgeOfAContactBreaksIt() {
        // Wedged between the ground, a block cracked loose at both ends is held up by heat pressing it, until the
        // pressing passes what its edges can bear: 4e-3 of strain held back presses it by 200 MPa, past 150 MPa.
        GridPos block = new GridPos(1, 0, 0);
        Result held = StructuralAnalysis.analyse(span(STONE, 1, new Frame.Expansion(2e-3, 0, 0, 0),
                Frame.Joint.CRACKED));
        assertTrue(held.falling().isEmpty());
        assertEquals(Mode.CRUSHING, held.bonds().get(0).mode());
        assertEquals(100e6 / 150e6, held.bonds().get(0).load(), 1e-3);
        Result crushed = StructuralAnalysis.analyse(span(STONE, 1, new Frame.Expansion(4e-3, 0, 0, 0),
                Frame.Joint.CRACKED));
        assertEquals(List.of(block), crushed.falling());
        for (BondResult b : crushed.bonds()) {
            assertEquals(Mode.CRUSHING, b.mode());
            assertEquals(200e6 / 150e6, b.load(), 1e-3);
            assertTrue(!b.holds());
        }
    }

    @Test
    void metalYieldsALittleAndLetsTheStrainOfHeatGo() {
        // 300 K of heat held back would press iron-like metal by 720 MPa, far past yielding; it yields a little,
        // keeps its strength, and the joints take only the weight.
        Result r = StructuralAnalysis.analyse(span(METAL, 6, new Frame.Expansion(3.6e-3, 0, 0, 0),
                Frame.Joint.INTACT));
        assertTrue(r.cracks().isEmpty());
        assertTrue(r.falling().isEmpty());
        Result cold = StructuralAnalysis.analyse(span(METAL, 6, Frame.Expansion.NONE, Frame.Joint.INTACT));
        for (int k = 0; k < r.bonds().size(); k++) {
            assertEquals(cold.bonds().get(k).load(), r.bonds().get(k).load(), 1e-9);
            assertEquals(r.bonds().get(k).load(), r.bonds().get(k).withoutHeat(), 0.0);
        }
    }

    @Test
    void stoneJoinedToMetalCracksOnItsOwnSide() {
        // The pressing of heat held back counts on the stone's side of a joint, but not on the metal's.
        Frame.Builder b = Frame.builder().ground(ORIGIN, null, ROOM, 1).ground(new GridPos(3, 0, 0), null, ROOM, 1)
                .block(new GridPos(1, 0, 0), STONE, ROOM, 1, MASS, new Frame.Expansion(4e-4, 0, 0, 0))
                .block(new GridPos(2, 0, 0), METAL, ROOM, 1, MASS, new Frame.Expansion(4e-4, 0, 0, 0))
                .bond(ORIGIN, Direction.EAST, Contact.FULL, Frame.Joint.INTACT)
                .bond(new GridPos(1, 0, 0), Direction.EAST, Contact.FULL, Frame.Joint.INTACT)
                .bond(new GridPos(2, 0, 0), Direction.EAST, Contact.FULL, Frame.Joint.INTACT);
        Result r = StructuralAnalysis.analyse(b.build(), WEIGHTLESS);
        // Held between unbreakable ground, the pair is pressed by one force all along: the stone's share of the
        // stretch is held back at 50 GPa, the metal's at 200 GPa, each over a metre, in series: 32 MN.
        double force = 4e-4 * 2 / (1 / 50e9 + 1 / 200e9);
        assertEquals(force / 150e6, r.bonds().get(0).load(), 1e-9);
        assertEquals(force / 150e6, r.bonds().get(1).load(), 1e-9);
        assertEquals(0.0, r.bonds().get(2).load(), 1e-12);
    }

    @Test
    void groundDoesNotExpand() {
        assertThrows(IllegalArgumentException.class, () -> new Frame.Block(ORIGIN, STONE, ROOM, 1, 0, true,
                new Frame.Expansion(1e-4, 0, 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> new Frame.Expansion(Double.NaN, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Frame.Expansion(0, 0, Double.POSITIVE_INFINITY, 0));
        assertThrows(IllegalArgumentException.class, () -> new Frame.Expansion(0, 0, 0, 0, 0, Double.NaN, 0));
        Frame.Expansion straight = new Frame.Expansion(0, 1e-4, 2e-4, 3e-4);
        assertEquals(2e-4, straight.gradient(1), 0.0);
        assertEquals(3e-4, straight.stretch(2), 0.0);
        assertTrue(new Frame.Expansion(0, 0, 0, 0, 1e-4, 0, 0).any());
    }

    /** Returns a straight span of {@code n} blocks between two blocks of unbreakable ground. */
    private static Frame span(Mechanics mechanics, int n, Frame.Expansion expansion, Frame.Joint joints) {
        Frame.Builder b = Frame.builder().ground(ORIGIN, null, ROOM, 1).ground(new GridPos(n + 1, 0, 0), null, ROOM, 1);
        for (int k = 1; k <= n; k++) {
            GridPos pos = new GridPos(k, 0, 0);
            b.block(pos, mechanics, ROOM, 1, MASS, expansion).bond(pos, Direction.WEST, Contact.FULL, joints);
        }
        b.bond(new GridPos(n, 0, 0), Direction.EAST, Contact.FULL, joints);
        return b.build();
    }

    /** Returns a straight overhang of {@code n} blocks of stone reaching east from a block of unbreakable ground. */
    private static Frame cantilever(int n, Frame.Expansion expansion) {
        Frame.Builder b = Frame.builder().ground(ORIGIN, null, ROOM, 1);
        for (int k = 1; k <= n; k++) {
            GridPos pos = new GridPos(k, 0, 0);
            b.block(pos, STONE, ROOM, 1, MASS, expansion).bond(pos, Direction.WEST, Contact.FULL, Frame.Joint.INTACT);
        }
        return b.build();
    }
}
