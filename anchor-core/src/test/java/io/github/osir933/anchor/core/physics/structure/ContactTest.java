package io.github.osir933.anchor.core.physics.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ContactTest {

    @Test
    void wholeFacesTouchOverASquareMetre() {
        Contact c = Contact.FULL;
        assertEquals(1.0, c.area(), 1e-15);
        assertEquals(5.0 / 6.0, c.shearArea(), 1e-15);
        assertEquals(1.0 / 12.0, c.inertiaS(), 1e-15);
        assertEquals(1.0 / 12.0, c.inertiaT(), 1e-15);
        // Saint-Venant's torsion constant of a unit square is 0.1406.
        assertEquals(0.1406, c.torsionConstant(), 5e-4);
        assertEquals(0.5, c.halfS(), 1e-15);
        assertEquals(0.25, c.plasticModulusS(), 1e-15);
        assertEquals(1.0 / 3.0, c.plasticTorque(), 1e-15);
        assertEquals(4, c.corners());
        // A unit square twisted by 1 N·m peaks at 4.8 Pa, Roark's 1/(0.208 a³).
        assertEquals(1.0 / 0.208, c.peakShearStress(0, 1), 0.02);
        assertEquals(1.5, c.peakShearStress(1, 0), 1e-15);
    }

    @Test
    void aSlabBesideABlockTouchesOverHalfAFace() {
        Contact slab = Contact.between(new double[] {0, 0, 1, 1}, new double[] {0, 0, 1, 0.5});
        assertNotNull(slab);
        assertEquals(0.5, slab.area(), 1e-15);
        assertEquals(0.5 * 1.0 / 12.0, slab.inertiaS(), 1e-15);
        assertEquals(1.0 * 0.125 / 12.0, slab.inertiaT(), 1e-15);
        assertEquals(0.25, slab.halfT(), 1e-15);
        // A bottom slab and a top slab side by side do not touch.
        assertNull(Contact.between(new double[] {0, 0, 1, 0.5}, new double[] {0, 0.5, 1, 1}));
    }

    @Test
    void anLShapedPatchAddsItsParts() {
        // Two rectangles: the bottom half of the face and the left half of the top half, as a stair's side.
        Contact c = Contact.of(new double[] {0, 0, 1, 0.5, 0, 0.5, 0.5, 1});
        assertEquals(0.75, c.area(), 1e-15);
        // Centroid along s: (0.5 * 0.5 + 0.25 * 0.25) / 0.75.
        double cs = (0.5 * 0.5 + 0.25 * 0.25) / 0.75;
        double expected = 0.5 * 1.0 / 12.0 + 0.5 * (0.5 - cs) * (0.5 - cs)
                + 0.5 * 0.125 / 12.0 + 0.25 * (0.25 - cs) * (0.25 - cs);
        assertEquals(expected, c.inertiaS(), 1e-15);
        assertEquals(8, c.corners());
    }

    @Test
    void aForceOffCentreSpreadsOverThePartOfThePatchCentredWhereItActs() {
        Contact c = Contact.FULL;
        // Over a whole face, the part centred e from its middle is (1 - 2|ey|)(1 - 2|ez|), as Meyerhof took it.
        assertEquals(1.0, c.effectiveArea(0, 0), 1e-15);
        assertEquals(0.6 * 0.9, c.effectiveArea(0.2, -0.05), 1e-15);
        assertEquals(0.0, c.effectiveArea(0.5, 0), 1e-15);
        assertEquals(0.0, c.effectiveArea(0.7, 0.1), 1e-15);
        double[] grow = c.effectiveAreaGradient(0.2, -0.05);
        assertEquals(-2 * 0.9, grow[0], 1e-12);
        assertEquals(2 * 0.6, grow[1], 1e-12);
        // A force that needs half the face may act a quarter of a metre out, where 1 - 2e is a half: 5/8 of the way
        // to a point 0.4 m out.
        assertEquals(0.625, c.bearable(0.4, 0, 0.5), 1e-12);
        assertEquals(0.0, c.bearable(0.4, 0, 1.0), 0.0);
        assertEquals(Double.POSITIVE_INFINITY, c.bearable(0, 0, 0.5));
        assertEquals(0.8, c.reach(0.4, 0), 1e-15);
        assertEquals(1.0, c.chord(0.3, 0.1, 0, 1), 1e-15);
        assertEquals(Math.sqrt(2), c.chord(0, 0, Math.sqrt(0.5), Math.sqrt(0.5)), 1e-12);
        // Moving in from 0.49 m out and 0.1 m along, a force that needs 0.05 m² has enough of the face around it once
        // (1 - 2|y|) 0.8 is 0.05, at 0.46875 m out; one that needs more than the 0.8 m² beside the middle has nowhere.
        assertEquals(0.49 - 0.46875, c.inward(-0.49, 0.1, 1, 0, 0.05), 1e-12);
        assertEquals(0.0, c.inward(-0.2, 0.1, 1, 0, 0.05), 0.0);
        assertEquals(Double.NaN, c.inward(-0.49, 0.1, 1, 0, 0.9));
        assertEquals(Double.NaN, c.inward(-0.49, 0.1, -1, 0, 0.05));
    }

    @Test
    void aPatchOfSeveralRectanglesBearsAsItsShapeSays() {
        // A square in two halves bears as the whole square does, though the halves are worked by halving the way out.
        Contact halves = Contact.of(new double[] {0, 0, 0.5, 1, 0.5, 0, 1, 1});
        for (double[] p : new double[][] {{0.2, -0.05}, {0.45, 0.3}, {-0.3, 0.1}}) {
            assertEquals(Contact.FULL.effectiveArea(p[0], p[1]), halves.effectiveArea(p[0], p[1]), 1e-12);
            assertEquals(Contact.FULL.bearable(p[0], p[1], 0.1), halves.bearable(p[0], p[1], 0.1), 1e-12);
            assertEquals(Contact.FULL.effectiveAreaGradient(p[0], p[1])[0],
                    halves.effectiveAreaGradient(p[0], p[1])[0], 1e-12);
            assertEquals(Contact.FULL.effectiveAreaGradient(p[0], p[1])[1],
                    halves.effectiveAreaGradient(p[0], p[1])[1], 1e-12);
        }
        // A slab beside a block touches over the bottom half of the face, centred a quarter below its middle.
        Contact slab = Contact.rectangle(0, 0, 1, 0.5);
        assertEquals(0.0, slab.centroidY(), 1e-15);
        assertEquals(-0.25, slab.centroidZ(), 1e-15);
        assertEquals(0.5, slab.effectiveArea(0, -0.25), 1e-15);
        assertEquals(0.3, slab.effectiveArea(0, -0.15), 1e-15);
        assertEquals(0.0, slab.bearable(0, 0, 0.6), 0.0);
        // On a stair's side, an L of two rectangles, the area falls to what is needed where the force may act.
        Contact stair = Contact.of(new double[] {0, 0, 1, 0.5, 0, 0.5, 0.5, 1});
        double cy = stair.centroidY();
        double cz = stair.centroidZ();
        double fraction = stair.bearable(0.45, 0.45, 0.2);
        assertEquals(0.2, stair.effectiveArea(cy + fraction * (0.45 - cy), cz + fraction * (0.45 - cz)), 1e-9);
    }

    @Test
    void rejectsPatchesThatDoNotFit() {
        assertThrows(IllegalArgumentException.class, () -> Contact.rectangle(0, 0, 1.5, 1));
        assertThrows(IllegalArgumentException.class, () -> Contact.rectangle(0.5, 0, 0.5, 1));
        assertThrows(IllegalArgumentException.class, () -> Contact.of(new double[] {0, 0, 1, 1, 0.5, 0.5, 1, 1}));
        assertThrows(IllegalArgumentException.class, () -> Contact.of(new double[] {0, 0, 1}));
    }
}
