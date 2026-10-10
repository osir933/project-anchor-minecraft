package io.github.osir933.anchor.core.physics.structure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.osir933.anchor.core.matter.Material;
import io.github.osir933.anchor.core.matter.MaterialLibrary;
import io.github.osir933.anchor.core.matter.Mechanics;
import io.github.osir933.anchor.core.physics.structure.StructuralAnalysis.Footing;
import io.github.osir933.anchor.core.physics.structure.StructuralAnalysis.Mode;
import io.github.osir933.anchor.core.physics.structure.StructuralAnalysis.Result;
import io.github.osir933.anchor.core.space.Direction;
import io.github.osir933.anchor.core.space.GridPos;
import io.github.osir933.anchor.core.units.PhysicalConstants;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Ground that gives under what stands on it, and footings that press it harder than it bears. */
class SoftGroundTest {

    private static final double ROOM = Mechanics.REFERENCE_K;
    private static final double G = PhysicalConstants.STANDARD_GRAVITY;
    private static final GridPos ORIGIN = new GridPos(0, 0, 0);
    private static final Material GRANITE = MaterialLibrary.GRANITE;
    private static final Material SOIL = MaterialLibrary.SOIL;
    private static final Material SAND = MaterialLibrary.SAND;
    /** The weight of a block of granite, in newtons. */
    private static final double BLOCK = GRANITE.referenceDensity() * G;
    /** Settings that judge every structure in first-order theory, so loads come out as the closed forms say. */
    private static final StructuralAnalysis.Settings FIRST_ORDER = StructuralAnalysis.Settings.defaults()
            .withBuckling(false);

    @Test
    void aBlockSettlesIntoSoilAsAnElasticHalfSpaceSays() {
        // A block of granite presses a square metre of soil, which gives as an elastic half-space does under a rigid
        // square footing: by its weight over K = 2GL / (1 - ν) (0.73 + 1.54 χ^0.75), with L = 0.5 m and χ = 1
        // (Gazetas), within a percent of what a rigid disc of the same area meets, 4GR / (1 - ν). The block's centre
        // sinks that far and as far again as its lower half squeezes.
        Mechanics soil = SOIL.mechanics();
        double shear = shear(soil);
        double nu = soil.poissonRatio();
        double k = 2 * shear * 0.5 / (1 - nu) * (0.73 + 1.54);
        double disc = 4 * shear * Math.sqrt(1 / Math.PI) / (1 - nu);
        assertEquals(disc, k, 0.01 * disc);
        Result r = StructuralAnalysis.analyse(pillar(SOIL, 1), FIRST_ORDER);
        assertEquals(1, r.footings().size());
        Footing f = r.footings().get(0);
        assertEquals(List.of(ORIGIN), f.ground());
        assertEquals(BLOCK / k, f.settlement(), 1e-9 * BLOCK / k);
        double squeeze = BLOCK * 0.5 / GRANITE.mechanics().youngsModulus(ROOM);
        assertEquals(-(BLOCK / k + squeeze), r.block(new GridPos(0, 1, 0)).displacement()[1], 1e-9 * BLOCK / k);
        assertTrue(f.sunk().isEmpty());
        // On ground that does not give, the block only squeezes.
        Result rigid = StructuralAnalysis.analyse(pillar(SOIL, 1), FIRST_ORDER.withSoftGround(false));
        assertTrue(rigid.footings().isEmpty());
        assertEquals(-squeeze, rigid.block(new GridPos(0, 1, 0)).displacement()[1], 1e-9 * squeeze);
    }

    @Test
    void soilBearsAsEurocodeSevenSays() {
        // Twenty blocks of granite on a square metre of soil press it with 516 kPa. The soil's cohesion,
        // c = σc (1 - sin φ) / 2 cos φ, and the weight of the wedge it would shear through resist, grown by Eurocode
        // 7's factors for a square footing: q = c N_c s_c + γ B N_γ s_γ / 2, about 800 kPa.
        Mechanics soil = SOIL.mechanics();
        double phi = StrictMath.atan(soil.friction());
        double c = soil.compressiveStrength(ROOM) * (1 - StrictMath.sin(phi)) / (2 * StrictMath.cos(phi));
        double nq = nq(phi);
        double nc = (nq - 1) / StrictMath.tan(phi);
        double ng = 2 * (nq - 1) * StrictMath.tan(phi);
        double sq = 1 + StrictMath.sin(phi);
        double sc = (sq * nq - 1) / (nq - 1);
        double bears = c * nc * sc + 0.5 * SOIL.referenceDensity() * G * ng * 0.7;
        int n = 20;
        Result r = StructuralAnalysis.analyse(pillar(SOIL, n), FIRST_ORDER);
        assertTrue(r.falling().isEmpty());
        assertEquals(n * BLOCK / bears, r.footings().get(0).load(), 1e-9);
    }

    @Test
    void aPillarSinksIntoSandThatCannotBearIt() {
        // Sand has no cohesion, so at its surface only the weight of the wedge it would shear through bears a
        // footing: q = γ B N_γ s_γ / 2, 170 kPa for a block's footing, with φ = 33°. Six blocks of granite press it
        // with 155 kPa and stand; seven, with 181 kPa, sink, and nothing cracks.
        double bears = 0.5 * SAND.referenceDensity() * G * ng(StrictMath.atan(SAND.mechanics().friction())) * 0.7;
        Result six = StructuralAnalysis.analyse(pillar(SAND, 6));
        assertTrue(six.falling().isEmpty());
        assertTrue(six.sunk().isEmpty());
        assertEquals(6 * BLOCK / bears, StructuralAnalysis.analyse(pillar(SAND, 6), FIRST_ORDER).footings().get(0)
                .load(), 1e-9);
        Result seven = StructuralAnalysis.analyse(pillar(SAND, 7));
        assertEquals(7, seven.falling().size());
        assertEquals(List.of(ORIGIN), seven.sunk());
        assertEquals(List.of(ORIGIN), seven.footings().get(0).sunk());
        assertEquals(Mode.SINKING, seven.bonds().get(0).mode());
        assertTrue(seven.footings().get(0).load() > 1);
        assertTrue(seven.cracks().isEmpty());
        // Where the ground does not give, it bears whatever presses it.
        assertTrue(StructuralAnalysis.analyse(pillar(SAND, 7), StructuralAnalysis.Settings.defaults()
                .withSoftGround(false)).falling().isEmpty());
    }

    @Test
    void sandAroundAFootingLetsItBearMore() {
        // Set a block deep in sand, a footing presses ground that the sand beside it weighs down, which the wedge
        // must lift as well: that adds q N_q s_q, with q the 15 kPa the sand beside it weighs on each square metre
        // and s_q = 1 + sin φ. The footing now bears 771 kPa, four and a half times as much.
        double phi = StrictMath.atan(SAND.mechanics().friction());
        double gamma = SAND.referenceDensity() * G;
        double surface = 0.5 * gamma * ng(phi) * 0.7;
        double buried = surface + gamma * nq(phi) * (1 + StrictMath.sin(phi));
        int n = 20;
        Frame.Builder b = pillarBuilder(SAND, n);
        for (Direction d : List.of(Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH)) {
            b.ground(new GridPos(0, 1, 0).offset(d), SAND.mechanics(), ROOM, 1, SAND.referenceDensity());
        }
        Result r = StructuralAnalysis.analyse(b.build(), FIRST_ORDER);
        assertEquals(n * BLOCK / buried, r.footings().get(0).load(), 1e-9);
        assertTrue(StructuralAnalysis.analyse(b.build()).falling().isEmpty());
        assertEquals(20, StructuralAnalysis.analyse(pillar(SAND, n)).falling().size());
    }

    @Test
    void aTowerOnSoftGroundLeansOverOnceItsWeightTurnsItHarderThanTheGroundTurnsItBack() {
        // A tower that leans by θ about its base is turned further by its weight, by θ times each block's weight
        // times how high it stands, W n² / 2 for n blocks, and turned back by the ground, K θ, with
        // K = 3G / (1 - ν) I^0.75 for a square footing (Gazetas). Granite bends too little to matter, so ten blocks
        // on soil carry K / (W n² / 2) = 2 times their weight before they lean over.
        Mechanics soil = SOIL.mechanics();
        double k = 3 * shear(soil) / (1 - soil.poissonRatio()) * StrictMath.pow(1 / 12.0, 0.75);
        int n = 10;
        Result ten = StructuralAnalysis.analyse(pillar(SOIL, n));
        double expected = k / (BLOCK * n * n / 2.0);
        assertEquals(expected, ten.buckling(), 0.01 * expected);
        assertTrue(ten.falling().isEmpty());
        // Twenty blocks would need the ground twice as stiff: the tower leans over, presses the edge of its footing
        // into the ground until it gives way, and falls. On granite, thousands of times stiffer, the same tower stands.
        Result twenty = StructuralAnalysis.analyse(pillar(SOIL, 20));
        assertEquals(20, twenty.falling().size());
        assertEquals(List.of(ORIGIN), twenty.sunk());
        assertEquals(Mode.SINKING, twenty.bonds().get(0).mode());
        assertTrue(twenty.cracks().isEmpty());
        assertTrue(StructuralAnalysis.analyse(pillar(GRANITE, 20)).falling().isEmpty());
    }

    @Test
    void aWideTowerRocksAsARigidFootingOfItsShapeDoes() {
        // Nine blocks side by side press the ground as one footing 3 m square, which rocks against
        // K = 3G / (1 - ν) (81 / 12)^0.75. Each block rests on its share of the footing's springs; pressing in step
        // they make up less than half of that, and springs that turn each face add the rest.
        Mechanics soil = SOIL.mechanics();
        double k = 3 * shear(soil) / (1 - soil.poissonRatio()) * StrictMath.pow(81 / 12.0, 0.75);
        int n = 16;
        Frame.Builder b = Frame.builder();
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                b.ground(new GridPos(x, 0, z), soil, ROOM, 1, SOIL.referenceDensity());
                for (int y = 1; y <= n; y++) {
                    GridPos pos = new GridPos(x, y, z);
                    b.block(pos, GRANITE.mechanics(), ROOM, 1, GRANITE.referenceDensity());
                    b.bond(pos, Direction.DOWN, Contact.FULL, Frame.Joint.INTACT);
                    if (x > 0) {
                        b.bond(pos, Direction.WEST, Contact.FULL, Frame.Joint.INTACT);
                    }
                    if (z > 0) {
                        b.bond(pos, Direction.NORTH, Contact.FULL, Frame.Joint.INTACT);
                    }
                }
            }
        }
        Result r = StructuralAnalysis.analyse(b.build());
        double expected = k / (9 * BLOCK * n * n / 2.0);
        assertEquals(expected, r.buckling(), 0.02 * expected);
        assertTrue(r.falling().isEmpty());
        assertEquals(1, r.footings().size());
        assertEquals(9, r.footings().get(0).ground().size());
    }

    @Test
    void aFootingSinksWhereItIsPressed() {
        // Two blocks of granite side by side on sand, with a pillar of eight on one of them: pressed 0.4 m off its
        // middle, the footing bears on the part 1.2 m long centred there, which takes in all of the one block's face
        // and a fifth of the other's. That bears 219 kN, and 258 kN presses it, so the sand under the loaded block
        // gives way, while under the other it holds.
        Frame.Builder b = Frame.builder();
        for (int x = 0; x < 2; x++) {
            GridPos pos = new GridPos(x, 1, 0);
            b.ground(new GridPos(x, 0, 0), SAND.mechanics(), ROOM, 1, SAND.referenceDensity());
            b.block(pos, GRANITE.mechanics(), ROOM, 1, GRANITE.referenceDensity());
            b.bond(pos, Direction.DOWN, Contact.FULL, Frame.Joint.INTACT);
        }
        b.bond(new GridPos(1, 1, 0), Direction.WEST, Contact.FULL, Frame.Joint.INTACT);
        for (int y = 2; y <= 9; y++) {
            GridPos pos = new GridPos(1, y, 0);
            b.block(pos, GRANITE.mechanics(), ROOM, 1, GRANITE.referenceDensity());
            b.bond(pos, Direction.DOWN, Contact.FULL, Frame.Joint.INTACT);
        }
        Result r = StructuralAnalysis.analyse(b.build(), FIRST_ORDER);
        assertEquals(List.of(new GridPos(1, 0, 0)), r.sunk());
        assertEquals(List.of(new GridPos(0, 0, 0), new GridPos(1, 0, 0)), r.footings().get(0).ground());
        assertEquals(10, r.falling().size());
    }

    @Test
    void sharingAnAnalysisWithSoftGroundAmongThreadsChangesNoBit() {
        // A slab of granite 20 blocks square on soil, with a tower on one corner that leans it into the ground: one
        // footing of 400 blocks, large enough for the solver to share the work.
        int n = 20;
        Frame.Builder b = Frame.builder();
        for (int x = 0; x < n; x++) {
            for (int z = 0; z < n; z++) {
                GridPos pos = new GridPos(x, 1, z);
                b.ground(new GridPos(x, 0, z), SOIL.mechanics(), ROOM, 1, SOIL.referenceDensity());
                b.block(pos, GRANITE.mechanics(), ROOM, 1, GRANITE.referenceDensity());
                b.bond(pos, Direction.DOWN, Contact.FULL, Frame.Joint.INTACT);
                if (x > 0) {
                    b.bond(pos, Direction.WEST, Contact.FULL, Frame.Joint.INTACT);
                }
                if (z > 0) {
                    b.bond(pos, Direction.NORTH, Contact.FULL, Frame.Joint.INTACT);
                }
            }
        }
        for (int y = 2; y <= 12; y++) {
            GridPos pos = new GridPos(0, y, 0);
            b.block(pos, GRANITE.mechanics(), ROOM, 1, GRANITE.referenceDensity());
            b.bond(pos, Direction.DOWN, Contact.FULL, Frame.Joint.INTACT);
        }
        Frame slab = b.build();
        Result alone = StructuralAnalysis.analyse(slab);
        Result shared = StructuralAnalysis.analyse(slab, StructuralAnalysis.Settings.defaults(), 4);
        assertEquals(1, alone.footings().size());
        assertTrue(alone.footings().get(0).settlement() > 0);
        assertEquals(alone.footings(), shared.footings());
        assertEquals(alone.bonds(), shared.bonds());
        assertEquals(alone.falling(), shared.falling());
        assertEquals(alone.buckling(), shared.buckling());
        for (int i = 0; i < alone.blocks().size(); i++) {
            assertArrayEquals(alone.blocks().get(i).displacement(), shared.blocks().get(i).displacement());
            assertArrayEquals(alone.blocks().get(i).rotation(), shared.blocks().get(i).rotation());
        }
    }

    /** Returns a pillar of {@code n} blocks of granite standing on a block of ground of the given matter. */
    private static Frame pillar(Material ground, int n) {
        return pillarBuilder(ground, n).build();
    }

    private static Frame.Builder pillarBuilder(Material ground, int n) {
        Frame.Builder b = Frame.builder().ground(ORIGIN, ground.mechanics(), ROOM, 1, ground.referenceDensity());
        for (int k = 1; k <= n; k++) {
            GridPos pos = new GridPos(0, k, 0);
            b.block(pos, GRANITE.mechanics(), ROOM, 1, GRANITE.referenceDensity());
            b.bond(pos, Direction.DOWN, Contact.FULL, Frame.Joint.INTACT);
        }
        return b;
    }

    private static double shear(Mechanics m) {
        return m.youngsModulus(ROOM) / (2 * (1 + m.poissonRatio()));
    }

    /** Returns N_q = e^(π tan φ) tan²(45° + φ/2). */
    private static double nq(double phi) {
        double t = StrictMath.tan(Math.PI / 4 + phi / 2);
        return StrictMath.exp(Math.PI * StrictMath.tan(phi)) * t * t;
    }

    /** Returns N_γ = 2 (N_q - 1) tan φ. */
    private static double ng(double phi) {
        return 2 * (nq(phi) - 1) * StrictMath.tan(phi);
    }
}
