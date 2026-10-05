package io.github.osir933.anchor.core.physics.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BeamElementTest {

    private static final int N = BeamElement.DOFS;

    @Test
    void shearRigidBeamMatchesTheTextbookMatrix() {
        double e = 2e11;
        double length = 1.0;
        double[] k = BeamElement.stiffness(new double[] {length}, new double[] {e}, new double[] {1e30},
                Contact.FULL);
        double ei = e / 12.0;
        // Euler-Bernoulli terms in the x-y plane: v and rz.
        assertEquals(12 * ei, k[1 * N + 1], 1e-3 * ei);
        assertEquals(6 * ei, k[1 * N + 5], 1e-3 * ei);
        assertEquals(4 * ei, k[5 * N + 5], 1e-3 * ei);
        assertEquals(2 * ei, k[5 * N + 11], 1e-3 * ei);
        assertEquals(-12 * ei, k[1 * N + 7], 1e-3 * ei);
        // And in the x-z plane, where the couplings change sign.
        assertEquals(12 * ei, k[2 * N + 2], 1e-3 * ei);
        assertEquals(-6 * ei, k[2 * N + 4], 1e-3 * ei);
        assertEquals(4 * ei, k[4 * N + 4], 1e-3 * ei);
        assertEquals(e, k[0], 1e-6 * e);
        assertEquals(-e, k[6], 1e-6 * e);
    }

    @Test
    void matrixIsSymmetricAndRigidMotionsNeedNoForce() {
        double[] k = BeamElement.stiffness(new double[] {0.5, 0.5}, new double[] {5e10, 2e11},
                new double[] {2e10, 8e10}, Contact.rectangle(0, 0, 1, 0.5));
        for (int r = 0; r < N; r++) {
            for (int c = 0; c < N; c++) {
                assertEquals(k[r * N + c], k[c * N + r], 1e-6 * Math.abs(k[r * N + r]) + 1e-6);
            }
        }
        // Rigid motions of the whole beam: translations, and turns about each axis through end i.
        double length = 1.0;
        double[][] modes = new double[6][N];
        for (int a = 0; a < 3; a++) {
            modes[a][a] = 1;
            modes[a][6 + a] = 1;
        }
        // Turn about x: both ends turn alike.
        modes[3][3] = 1;
        modes[3][9] = 1;
        // Turn about y by 1 rad: end j moves -L along z.
        modes[4][4] = 1;
        modes[4][10] = 1;
        modes[4][8] = -length;
        // Turn about z by 1 rad: end j moves +L along y.
        modes[5][5] = 1;
        modes[5][11] = 1;
        modes[5][7] = length;
        for (double[] mode : modes) {
            for (int r = 0; r < N; r++) {
                double f = 0;
                for (int c = 0; c < N; c++) {
                    f += k[r * N + c] * mode[c];
                }
                assertEquals(0.0, f, 1e-6 * Math.abs(k[r * N + r]));
            }
        }
    }

    @Test
    void clampedBeamDeflectsAsTimoshenkoTheorySays() {
        double e = 5e10;
        double g = e / 2.5;
        double length = 1.0;
        double[] k = BeamElement.stiffness(new double[] {length}, new double[] {e}, new double[] {g}, Contact.FULL);
        // Clamp end i and push end j along y with 1 N: solve the free end's 2 by 2 system in the x-y plane.
        double a = k[7 * N + 7];
        double b = k[7 * N + 11];
        double d = k[11 * N + 11];
        double det = a * d - b * b;
        double deflection = d / det;
        double expected = length * length * length / (3 * e / 12.0) + length / (g * 5.0 / 6.0);
        assertEquals(expected, deflection, 1e-12 * expected);
    }

    @Test
    void twoMaterialsAddTheirFlexibilities() {
        double soft = 1e9;
        double hard = 1e11;
        double[] k = BeamElement.stiffness(new double[] {0.5, 0.5}, new double[] {soft, hard},
                new double[] {soft / 2.5, hard / 2.5}, Contact.FULL);
        // Pulling end j with end i held: springs in series.
        double expected = 1.0 / (0.5 / soft + 0.5 / hard);
        assertEquals(expected, k[6 * N + 6], 1e-9 * expected);
        // Bending: the clamped end's half is soft, so the tip deflects as integrated piece by piece.
        double a = k[7 * N + 7];
        double b = k[7 * N + 11];
        double d = k[11 * N + 11];
        double deflection = d / (a * d - b * b);
        double i = 1.0 / 12.0;
        double bending = (1.0 - 0.125) / 3.0 / (soft * i) + 0.125 / 3.0 / (hard * i);
        double shear = 0.5 / (soft / 2.5 * 5.0 / 6.0) + 0.5 / (hard / 2.5 * 5.0 / 6.0);
        assertEquals(bending + shear, deflection, 1e-9 * deflection);
    }
}
