package io.github.osir933.anchor.core.physics.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
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
    void aHingeTurnsFreelyButCarriesEveryOtherForce() {
        double[] k = BeamElement.stiffness(new double[] {0.5}, new double[] {5e10}, new double[] {2e10}, Contact.FULL);
        // A hinge where a half beam meets the ground, near the bottom edge of the face, its line tilted a little.
        double[] mode = BeamElement.hingeMode(0.5, 0.0, -0.45, 0.02, 0.1, Math.sqrt(0.99));
        double softness = 1e-6;
        double[] hinged = BeamElement.released(k, mode, softness);
        double scale = Math.abs(k[N + 1]);
        for (int r = 0; r < N; r++) {
            for (int c = 0; c < N; c++) {
                assertEquals(hinged[r * N + c], hinged[c * N + r], 1e-9 * scale);
            }
        }
        assertEquals(1 / (1 + softness), BeamElement.hingeTurn(k, mode, softness, mode), 1e-15);
        Random random = new Random(7);
        for (int trial = 0; trial < 5; trial++) {
            double[] d = new double[N];
            for (int i = 0; i < N; i++) {
                d[i] = random.nextGaussian() * 1e-4;
            }
            // Whatever the ends do, the forces leave next to no moment about the hinge line: the work they would do
            // turning the far end about it.
            double free = dot(mode, times(k, d));
            double held = dot(mode, times(hinged, d));
            assertTrue(Math.abs(held) <= 2 * softness * Math.abs(free) + 1e-12 * scale, held + " against " + free);
        }
        // The hinge turns without straining the beam, and moving both ends alike still strains nothing.
        double[] turned = times(hinged, mode);
        for (int r = 0; r < N; r++) {
            assertEquals(0.0, turned[r], 2 * softness * scale);
        }
        double[] shifted = new double[N];
        shifted[1] = 1;
        shifted[7] = 1;
        for (double f : times(hinged, shifted)) {
            assertEquals(0.0, f, 1e-9 * scale);
        }
    }

    @Test
    void aHingedBeamsGeometricStiffnessIsTheEnergyOfItsKinkedShape() {
        double length = 1.0;
        double at = 0.5;
        double axial = -3e6;
        double ay = 0.28;
        double az = -0.96;
        double softness = 1e-6;
        double[] k = BeamElement.stiffness(new double[] {0.5, 0.5}, new double[] {5e10, 5e10},
                new double[] {2e10, 2e10}, Contact.FULL);
        double[] mode = BeamElement.hingeMode(length, at, 0.4, -0.1, ay, az);
        double[] kg = BeamElement.geometric(length, axial, Contact.FULL);
        double[] hinged = BeamElement.releasedGeometric(kg, k, mode, softness, length, at, axial, ay, az);
        double[] kmode = times(k, mode);
        double turnScale = dot(mode, kmode) * (1 + softness);
        Random random = new Random(11);
        for (int trial = 0; trial < 5; trial++) {
            double[] d = new double[N];
            for (int i = 0; i < N; i++) {
                d[i] = i == 3 || i == 9 ? 0 : random.nextGaussian() * 1e-3;
            }
            // The hinge turns by theta; the rest bends as cubic shapes, and beyond the hinge the beam tilts by theta.
            double theta = dot(kmode, d) / turnScale;
            double[] bent = new double[N];
            for (int i = 0; i < N; i++) {
                bent[i] = d[i] - mode[i] * theta;
            }
            // N times the integral of the slope squared, by Gauss's rule on each side of the kink.
            double energy = 0;
            double[] nodes = {-0.9061798459386640, -0.5384693101056831, 0, 0.5384693101056831, 0.9061798459386640};
            double[] weights = {0.2369268850561891, 0.4786286704993665, 0.5688888888888889, 0.4786286704993665,
                0.2369268850561891};
            for (double[] piece : new double[][] {{0, at}, {at, length}}) {
                double half = (piece[1] - piece[0]) / 2;
                for (int q = 0; q < nodes.length; q++) {
                    double x = piece[0] + half * (1 + nodes[q]);
                    double xi = x / length;
                    double h1 = (-6 * xi + 6 * xi * xi) / length;
                    double h2 = 1 - 4 * xi + 3 * xi * xi;
                    double h3 = (6 * xi - 6 * xi * xi) / length;
                    double h4 = 3 * xi * xi - 2 * xi;
                    // Slopes: v' takes the turns about z, w' minus the turns about y.
                    double v = h1 * bent[1] + h2 * bent[5] + h3 * bent[7] + h4 * bent[11];
                    double w = h1 * bent[2] - h2 * bent[4] + h3 * bent[8] - h4 * bent[10];
                    if (x > at) {
                        v += theta * az;
                        w -= theta * ay;
                    }
                    energy += weights[q] * half * axial * (v * v + w * w);
                }
            }
            double matrix = dot(d, times(hinged, d));
            assertEquals(energy, matrix, 1e-9 * Math.abs(energy));
        }
    }

    private static double[] times(double[] m, double[] v) {
        double[] out = new double[N];
        for (int r = 0; r < N; r++) {
            for (int c = 0; c < N; c++) {
                out[r] += m[r * N + c] * v[c];
            }
        }
        return out;
    }

    private static double dot(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
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
