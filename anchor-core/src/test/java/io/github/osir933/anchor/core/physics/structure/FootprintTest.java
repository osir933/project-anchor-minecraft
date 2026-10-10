package io.github.osir933.anchor.core.physics.structure;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Where a footing touches the ground, and the part of it that bears a force pressed off centre. */
class FootprintTest {

    private static final double[] FULL = Contact.FULL.rectangles();

    @Test
    void aStripPressedOffCentreBearsOnThePartCentredWhereItIsPressed() {
        // Meyerhof: a footing B wide pressed e off centre bears as one B - 2e wide.
        Footprint strip = new Footprint(new int[] {0, 1, 2}, new int[] {0, 0, 0}, new double[][] {FULL, FULL, FULL});
        assertEquals(3, strip.area(), 1e-15);
        assertEquals(1.5, strip.centroidS(), 1e-15);
        assertEquals(0.5, strip.centroidT(), 1e-15);
        assertEquals(27 / 12.0, strip.spreadS(), 1e-15);
        assertEquals(3 / 12.0, strip.spreadT(), 1e-15);
        assertEquals(3, strip.widthS(), 1e-15);
        assertEquals(1, strip.widthT(), 1e-15);
        Footprint.Effective whole = strip.effective(1.5, 0.5);
        assertEquals(3, whole.area(), 1e-15);
        assertEquals(8, whole.perimeter(), 1e-15);
        assertEquals(1, whole.width(), 1e-12);
        assertEquals(3, whole.length(), 1e-12);
        assertEquals(1, Math.abs(whole.axisS()), 1e-15);
        assertArrayEquals(new double[] {1, 1, 1}, whole.overlap(), 1e-15);
        // Half a metre toward one end it bears on two metres of the strip, which still bears as a strip a metre wide.
        Footprint.Effective off = strip.effective(2, 0.5);
        assertEquals(2, off.area(), 1e-15);
        assertEquals(1, off.width(), 1e-12);
        assertEquals(2, off.length(), 1e-12);
        assertArrayEquals(new double[] {0, 1, 1}, off.overlap(), 1e-15);
        // Off centre both ways, on a rectangle 2 m by 0.6 m.
        Footprint.Effective corner = strip.effective(2, 0.3);
        assertEquals(1.2, corner.area(), 1e-12);
        assertEquals(0.6, corner.width(), 1e-12);
        assertEquals(2, corner.length(), 1e-12);
        assertArrayEquals(new double[] {0, 0.6, 0.6}, corner.overlap(), 1e-12);
        // Pressed beyond its edge, nothing of it bears.
        assertEquals(0, strip.effective(3.2, 0.5).area());
        assertEquals(0, strip.effective(1.5, -0.1).area());
    }

    @Test
    void aRingOfWallsBearsAsAStripAsWideAsItsWalls() {
        // Eight blocks round an empty middle, as the walls of a small house stand. The rectangle with the same area,
        // 8 m², and the same outline, 16 m, is 1.17 m wide and 6.83 m long: a strip about as wide as the walls.
        int[] s = {0, 1, 2, 0, 2, 0, 1, 2};
        int[] t = {0, 0, 0, 1, 1, 2, 2, 2};
        double[][] patches = new double[8][];
        java.util.Arrays.fill(patches, FULL);
        Footprint ring = new Footprint(s, t, patches);
        assertEquals(8, ring.area(), 1e-15);
        assertEquals(6.75 - 1 / 12.0, ring.spreadS(), 1e-12);
        Footprint.Effective e = ring.effective(1.5, 1.5);
        assertEquals(8, e.area(), 1e-12);
        assertEquals(16, e.perimeter(), 1e-12);
        assertEquals(16 / (8 + Math.sqrt(32)), e.width(), 1e-12);
        assertEquals(8 / e.width(), e.length(), 1e-12);
    }

    @Test
    void aFootprintLongOnTheDiagonalBearsAlongTheDiagonal() {
        // Blocks corner to corner along a diagonal spread farthest along it.
        Footprint steps = new Footprint(new int[] {0, 1, 2}, new int[] {0, 1, 2}, new double[][] {FULL, FULL, FULL});
        Footprint.Effective e = steps.effective(1.5, 1.5);
        assertEquals(3, e.area(), 1e-12);
        // They touch only at their corners, so the outline runs all round each of them.
        assertEquals(12, e.perimeter(), 1e-12);
        assertEquals(Math.sqrt(0.5), Math.abs(e.axisS()), 1e-12);
        assertEquals(e.axisS(), e.axisT(), 1e-12);
        assertEquals(1, e.toLength(1, 1), 1e-12);
        assertEquals(0, e.toLength(1, -1), 1e-12);
        assertEquals(0.5, e.toLength(1, 0), 1e-12);
        assertEquals(1, e.toLength(0, 0));
    }

    @Test
    void partPatchesBearOnlyWhereTheyTouch() {
        // A slab covers the lower half of each of two faces: the footprint is a strip 2 m by 0.5 m.
        double[] half = Contact.rectangle(0, 0, 1, 0.5).rectangles();
        Footprint slabs = new Footprint(new int[] {4, 5}, new int[] {-3, -3}, new double[][] {half, half});
        assertEquals(1, slabs.area(), 1e-15);
        assertEquals(5, slabs.centroidS(), 1e-15);
        assertEquals(-2.75, slabs.centroidT(), 1e-15);
        Footprint.Effective e = slabs.effective(5, -2.75);
        assertEquals(1, e.area(), 1e-12);
        assertEquals(5, e.perimeter(), 1e-12);
        assertEquals(0.5, e.width(), 1e-12);
        assertEquals(2, e.length(), 1e-12);
        assertArrayEquals(new double[] {0.5, 0.5}, e.overlap(), 1e-12);
    }
}
