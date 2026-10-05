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
    void rejectsPatchesThatDoNotFit() {
        assertThrows(IllegalArgumentException.class, () -> Contact.rectangle(0, 0, 1.5, 1));
        assertThrows(IllegalArgumentException.class, () -> Contact.rectangle(0.5, 0, 0.5, 1));
        assertThrows(IllegalArgumentException.class, () -> Contact.of(new double[] {0, 0, 1, 1, 0.5, 0.5, 1, 1}));
        assertThrows(IllegalArgumentException.class, () -> Contact.of(new double[] {0, 0, 1}));
    }
}
