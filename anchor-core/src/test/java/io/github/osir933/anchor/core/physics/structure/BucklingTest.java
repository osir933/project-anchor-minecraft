package io.github.osir933.anchor.core.physics.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.osir933.anchor.core.matter.Material;
import io.github.osir933.anchor.core.matter.MaterialLibrary;
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
import org.junit.jupiter.api.Test;

/** Slender structures that bow under what presses them, and buckle. */
class BucklingTest {

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

    /** The stone's bending stiffness, EI, for a whole face: 50 GPa times 1/12 m⁴. */
    private static final double EI = 50e9 / 12;

    private static final StructuralAnalysis.Settings WEIGHTLESS =
            new StructuralAnalysis.Settings(0, 64, 0.02, 1e-4, true, true);

    /** A post the width of a fence, a quarter of a metre square, in the middle of a face. */
    private static final Contact POST = Contact.rectangle(0.375, 0.375, 0.625, 0.625);

    @Test
    void geometricStiffnessIsSymmetricAndGrowsWithTheForce() {
        double[] k = BeamElement.geometric(1.0, -1000.0, Contact.FULL);
        double[] twice = BeamElement.geometric(1.0, -2000.0, Contact.FULL);
        for (int r = 0; r < BeamElement.DOFS; r++) {
            for (int c = 0; c < BeamElement.DOFS; c++) {
                assertEquals(k[r * BeamElement.DOFS + c], k[c * BeamElement.DOFS + r], 1e-12);
                assertEquals(2 * k[r * BeamElement.DOFS + c], twice[r * BeamElement.DOFS + c], 1e-9);
            }
            // Sliding the whole beam aside tilts nothing, so the force pushes nothing.
            double sideways = k[r * BeamElement.DOFS + 1] + k[r * BeamElement.DOFS + 7];
            assertEquals(0.0, sideways, 1e-9);
        }
        // Pressing softens a beam against being pushed aside: (6/5) N / L.
        assertEquals(-1200.0, k[1 * BeamElement.DOFS + 1], 1e-9);
        assertEquals(-1200.0, k[2 * BeamElement.DOFS + 2], 1e-9);
        for (double v : BeamElement.geometric(1.0, 0.0, Contact.FULL)) {
            assertEquals(0.0, v);
        }
    }

    @Test
    void aWeightOnAColumnBucklesItWhereEulerSaysAndBowsItAsTheImperfectionSays() {
        // A column clamped at the ground and free at the top buckles under pi² EI / 4L², L from the ground's face to
        // the centre of the top block, where the weight sits. Half that weight on top leaves a factor of two.
        int n = 20;
        double length = n - 0.5;
        double euler = Math.PI * Math.PI * EI / (4 * length * length);
        Result r = StructuralAnalysis.analyse(column(STONE, n, 1e-3, euler / (2 * G), Contact.FULL));
        assertTrue(r.cracks().isEmpty() && r.falling().isEmpty());
        assertEquals(2.0, r.buckling(), 2.0 * 0.005);
        // It starts bowed by 1/500 of the length over which it buckles, twice the column's, and bows that much
        // again under the load, 1/(factor - 1) times: the top moves aside by 2L/500/(factor - 1).
        double[] top = r.block(new GridPos(0, n, 0)).displacement();
        double aside = Math.sqrt(top[0] * top[0] + top[2] * top[2]);
        assertEquals(2 * length / 500 / (r.buckling() - 1), aside, 0.03 * aside);
    }

    @Test
    void aTowerBucklesUnderItsOwnWeightAtGreenhillsHeight() {
        // A column of its own weight q per metre buckles once q L³ reaches 7.837 EI: 107 m of stone a metre wide.
        double greenhill = Math.cbrt(7.837347 * EI / (MASS * G));
        for (int n : new int[] {60, 80}) {
            Result r = StructuralAnalysis.analyse(column(STONE, n, MASS, MASS, Contact.FULL));
            assertTrue(r.cracks().isEmpty() && r.falling().isEmpty(), () -> n + ": " + r.cracks());
            double expected = Math.pow(greenhill / n, 3);
            assertEquals(expected, r.buckling(), expected * 1e-3, () -> n + " blocks");
        }
    }

    @Test
    void aTowerTooTallForItsOwnWeightBucklesAndFalls() {
        int n = 115;
        Result r = StructuralAnalysis.analyse(column(STONE, n, MASS, MASS, Contact.FULL));
        assertFalse(r.cracks().isEmpty());
        assertEquals(ORIGIN, r.cracks().get(0).pos(), "it breaks at its foot, where it bends most");
        for (Crack c : r.cracks()) {
            assertTrue(c.buckled(), "standing straight, it would have held: " + c);
            assertFalse(c.heat(), "its weight broke it: " + c);
            assertEquals(Mode.TENSION, c.mode());
            assertTrue(c.load() > 1.0);
        }
        assertTrue(r.block(new GridPos(0, n, 0)).fell());
        assertTrue(r.falling().size() > n / 2, r.falling().size() + " fell");

        // Judged straight, as before buckling was worked out, it stands: its foot is pressed by under 3 MPa.
        Result straight = StructuralAnalysis.analyse(column(STONE, n, MASS, MASS, Contact.FULL),
                StructuralAnalysis.Settings.defaults().withBuckling(false));
        assertTrue(straight.cracks().isEmpty() && straight.falling().isEmpty());
        assertEquals(Double.POSITIVE_INFINITY, straight.buckling());
    }

    @Test
    void aStockyStructureIsJudgedStraight() {
        // Ten blocks of stone could carry nearly 1200 times their weight before buckling; bowing does not matter.
        Result r = StructuralAnalysis.analyse(column(STONE, 10, MASS, MASS, Contact.FULL));
        assertEquals(Double.POSITIVE_INFINITY, r.buckling());
        for (BondResult b : r.bonds()) {
            assertEquals(b.unbowed(), b.load(), b::toString);
        }
    }

    @Test
    void aWoodenPostHoldsAnIronBlockUpToAboutElevenBlocksTall() {
        // A hardwood post a quarter of a metre square: EI = 13 GPa x 3.3e-4 m⁴. Under a block of iron, 77 kN, it
        // buckles once the block's centre is 11.6 m above the ground, a little lower for the post's own weight.
        Result ten = StructuralAnalysis.analyse(post(10));
        assertTrue(ten.cracks().isEmpty() && ten.falling().isEmpty(), ten.cracks()::toString);
        assertTrue(ten.buckling() > 1.15 && ten.buckling() < 1.25, "factor " + ten.buckling());
        // Bowing loads it many times more than its load would straight.
        BondResult worst = worst(ten);
        assertTrue(worst.load() > 10 * worst.unbowed(), worst::toString);
        assertTrue(worst.load() < 1.0);

        Result twelve = StructuralAnalysis.analyse(post(12));
        assertTrue(twelve.buckling() > 1.0, "what is left stands");
        assertEquals(new GridPos(0, 0, 0), twelve.cracks().get(0).pos(), twelve.cracks()::toString);
        assertTrue(twelve.cracks().get(0).buckled());
        assertTrue(twelve.block(new GridPos(0, 13, 0)).fell(), "the iron falls");
    }

    @Test
    void aSlabOnFourSlenderPostsSwaysAsideOnceThePostsAreTooTall() {
        // Four hardwood posts carry a 7 by 7 slab of granite, a quarter each. The slab ties their tops together and
        // keeps them from turning, so they sway aside as one, each buckling as a column clamped at both ends but free
        // to sway: under pi² EI / L², L between the posts' height and half a block more, as the slab gives a little.
        double perPost = 49 * MaterialLibrary.GRANITE.referenceDensity() * G / 4;
        double ei = MaterialLibrary.HARDWOOD.mechanics().youngsModulus(ROOM) * POST.inertiaS();
        int h = 9;
        Result nine = StructuralAnalysis.analyse(table(7, h));
        assertTrue(nine.cracks().isEmpty() && nine.falling().isEmpty(), nine.cracks()::toString);
        double low = Math.PI * Math.PI * ei / ((h + 0.5) * (h + 0.5)) / perPost;
        double high = Math.PI * Math.PI * ei / (h * h) / perPost;
        assertTrue(nine.buckling() > low && nine.buckling() < high, () -> low + " < " + nine.buckling() + " < " + high);

        Result twelve = StructuralAnalysis.analyse(table(7, 12));
        assertFalse(twelve.cracks().isEmpty());
        assertTrue(twelve.cracks().get(0).buckled(), twelve.cracks()::toString);
        assertTrue(twelve.block(new GridPos(3, 13, 3)).fell(), "the slab falls");
    }

    @Test
    void heatPressingALongSpanBucklesIt() {
        // Sixty blocks of stone clamped between ground at both ends buckle once heat presses them with 4π² EI / L²,
        // L being the 60 m between the ground's faces; held to its length, a strain s presses the span with E s.
        int n = 60;
        double critical = 4 * Math.PI * Math.PI * EI / (n * n);
        double strain = critical / 50e9 / 2;
        Result warm = StructuralAnalysis.analyse(span(STONE, n, strain), WEIGHTLESS);
        assertTrue(warm.cracks().isEmpty() && warm.falling().isEmpty(), warm.cracks()::toString);
        assertEquals(2.0, warm.buckling(), 2.0 * 0.005);

        Result hot = StructuralAnalysis.analyse(span(STONE, n, critical / 50e9 * 1.25), WEIGHTLESS);
        assertFalse(hot.cracks().isEmpty());
        for (Crack c : hot.cracks()) {
            assertTrue(c.buckled(), c::toString);
            assertTrue(c.heat(), "without heat nothing presses it: " + c);
        }
    }

    @Test
    void heatDoesNotBuckleMetalThatYields() {
        // Metal pressed by heat past its buckling load bows a little and so lets the push go, as it lets go strain
        // that would press it past its yield. Pressed by 1e-3 of strain, 200 MPa, this span would buckle 12 times.
        Result r = StructuralAnalysis.analyse(span(METAL, 60, 1e-3), WEIGHTLESS);
        assertTrue(r.cracks().isEmpty() && r.falling().isEmpty(), r.cracks()::toString);
        assertEquals(Double.POSITIVE_INFINITY, r.buckling());
    }

    @Test
    void theSameFrameBucklesTheSameWayEveryTime() {
        Result a = StructuralAnalysis.analyse(post(12));
        Result b = StructuralAnalysis.analyse(post(12));
        assertEquals(a.cracks(), b.cracks());
        assertEquals(a.falling(), b.falling());
        assertEquals(a.bonds(), b.bonds());
        Result c = StructuralAnalysis.analyse(post(10));
        Result d = StructuralAnalysis.analyse(post(10));
        assertEquals(c.bonds(), d.bonds());
        assertEquals(c.buckling(), d.buckling());
    }

    @Test
    void jacobiFindsTheEigenvaluesOfASmallSymmetricMatrix() {
        double[][] a = {{2, -1, 0}, {-1, 2, -1}, {0, -1, 2}};
        double[][] vectors = new double[3][3];
        double[] values = Buckling.eigen(new double[][] {a[0].clone(), a[1].clone(), a[2].clone()}, vectors);
        double[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        assertEquals(2 - Math.sqrt(2), sorted[0], 1e-12);
        assertEquals(2.0, sorted[1], 1e-12);
        assertEquals(2 + Math.sqrt(2), sorted[2], 1e-12);
        for (int k = 0; k < 3; k++) {
            for (int r = 0; r < 3; r++) {
                double sum = 0;
                for (int c = 0; c < 3; c++) {
                    sum += a[r][c] * vectors[c][k];
                }
                assertEquals(values[k] * vectors[r][k], sum, 1e-12);
            }
        }
    }

    /** A column of n blocks standing on unbreakable ground; each weighs mass but the top one, which weighs top. */
    private static Frame column(Mechanics mechanics, int n, double mass, double top, Contact contact) {
        Frame.Builder b = Frame.builder().ground(ORIGIN, null, ROOM, 1);
        GridPos below = ORIGIN;
        for (int k = 1; k <= n; k++) {
            GridPos pos = new GridPos(0, k, 0);
            b.block(pos, mechanics, ROOM, 1, k == n ? top : mass).bond(below, Direction.UP, contact,
                    Frame.Joint.INTACT);
            below = pos;
        }
        return b.build();
    }

    /** A hardwood post of h blocks on granite ground with a block of iron on top. */
    private static Frame post(int h) {
        Material wood = MaterialLibrary.HARDWOOD;
        Frame.Builder b = Frame.builder().ground(ORIGIN, MaterialLibrary.GRANITE.mechanics(), ROOM, 1);
        GridPos below = ORIGIN;
        for (int k = 1; k <= h; k++) {
            GridPos pos = new GridPos(0, k, 0);
            b.block(pos, wood.mechanics(), ROOM, 1, wood.referenceDensity() * POST.area()).bond(below, Direction.UP,
                    POST, Frame.Joint.INTACT);
            below = pos;
        }
        GridPos top = new GridPos(0, h + 1, 0);
        b.block(top, MaterialLibrary.IRON.mechanics(), ROOM, 1, MaterialLibrary.IRON.referenceDensity())
                .bond(below, Direction.UP, POST, Frame.Joint.INTACT);
        return b.build();
    }

    /** A square slab of granite, size blocks a side, on four hardwood posts h blocks tall at its corners. */
    private static Frame table(int size, int h) {
        Material wood = MaterialLibrary.HARDWOOD;
        Material granite = MaterialLibrary.GRANITE;
        Frame.Builder b = Frame.builder();
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                b.ground(new GridPos(x, 0, z), granite.mechanics(), ROOM, 1);
            }
        }
        int last = size - 1;
        int[][] corners = {{0, 0}, {0, last}, {last, 0}, {last, last}};
        for (int[] c : corners) {
            GridPos below = new GridPos(c[0], 0, c[1]);
            for (int k = 1; k <= h; k++) {
                GridPos pos = new GridPos(c[0], k, c[1]);
                b.block(pos, wood.mechanics(), ROOM, 1, wood.referenceDensity() * POST.area()).bond(below,
                        Direction.UP, POST, Frame.Joint.INTACT);
                below = pos;
            }
        }
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                b.block(new GridPos(x, h + 1, z), granite.mechanics(), ROOM, 1, granite.referenceDensity());
            }
        }
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                GridPos pos = new GridPos(x, h + 1, z);
                if (x + 1 < size) {
                    b.bond(pos, Direction.EAST, Contact.FULL, Frame.Joint.INTACT);
                }
                if (z + 1 < size) {
                    b.bond(pos, Direction.SOUTH, Contact.FULL, Frame.Joint.INTACT);
                }
            }
        }
        for (int[] c : corners) {
            b.bond(new GridPos(c[0], h, c[1]), Direction.UP, POST, Frame.Joint.INTACT);
        }
        return b.build();
    }

    /** A row of n blocks along x between two blocks of unbreakable ground, each stretched by heat by strain. */
    private static Frame span(Mechanics mechanics, int n, double strain) {
        Frame.Expansion expansion = new Frame.Expansion(strain, 0, 0, 0);
        Frame.Builder b = Frame.builder().ground(ORIGIN, null, ROOM, 1).ground(new GridPos(n + 1, 0, 0), null, ROOM, 1);
        for (int k = 1; k <= n; k++) {
            GridPos pos = new GridPos(k, 0, 0);
            b.block(pos, mechanics, ROOM, 1, MASS, expansion).bond(pos, Direction.WEST, Contact.FULL,
                    Frame.Joint.INTACT);
        }
        b.bond(new GridPos(n, 0, 0), Direction.EAST, Contact.FULL, Frame.Joint.INTACT);
        return b.build();
    }

    private static BondResult worst(Result r) {
        BondResult worst = null;
        for (BondResult b : r.bonds()) {
            if (b.holds() && (worst == null || b.load() > worst.load())) {
                worst = b;
            }
        }
        return worst;
    }
}
