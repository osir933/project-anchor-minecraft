package io.github.osir933.anchor.core.physics.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The ground's stiffness under a footing and the pressure at which it gives way. */
class SoilTest {

    @Test
    void bearingCapacityFactorsAreThoseEurocodeSevenTabulates() {
        // N_q = e^(π tan φ) tan²(45° + φ/2), N_c = (N_q - 1) cot φ and N_γ = 2 (N_q - 1) tan φ (EN 1997-1 D.4).
        double[][] table = {
            {20, 6.40, 14.83, 3.93},
            {25, 10.66, 20.72, 9.01},
            {30, 18.40, 30.14, 20.09},
            {35, 33.30, 46.12, 45.23},
            {40, 64.20, 75.31, 106.05},
        };
        for (double[] row : table) {
            double t = tan(row[0]);
            assertEquals(row[1], Soil.surchargeFactor(t), 0.005 * row[1], () -> row[0] + "°");
            assertEquals(row[2], Soil.cohesionFactor(t), 0.005 * row[2], () -> row[0] + "°");
            assertEquals(row[3], Soil.weightFactor(t), 0.005 * row[3], () -> row[0] + "°");
        }
        // Without friction the cohesion factor is Prandtl's, π + 2.
        assertEquals(Math.PI + 2, Soil.cohesionFactor(0), 1e-15);
        assertEquals(1, Soil.surchargeFactor(0), 1e-15);
        assertEquals(0, Soil.weightFactor(0), 1e-15);
    }

    @Test
    void cohesionComesFromCompressiveStrengthAsMohrAndCoulombSay() {
        // An unconfined sample fails where its Mohr circle, from 0 to σc, touches the line τ = c + σ tan φ: then
        // σc = 2 c cos φ / (1 - sin φ).
        for (double phi : new double[] {0, 15, 30, 45}) {
            double c = Soil.cohesion(100e3, tan(phi));
            double r = Math.toRadians(phi);
            assertEquals(100e3, 2 * c * StrictMath.cos(r) / (1 - StrictMath.sin(r)), 1e-9 * 100e3, () -> phi + "°");
        }
    }

    @Test
    void aSquareFootingOnSandBearsAsEurocodeSevenWorksItOut() {
        // A footing 2 m square, 1 m down in sand of φ = 35° that weighs 18 kN/m³, pressed straight down: the
        // weight of the sand beside it and of the wedge beneath it resist, grown by the shape factors
        // s_q = 1 + sin φ and s_γ = 0.7, so q = 18 kPa × 33.30 × 1.574 + 0.5 × 18 kN/m³ × 2 m × 45.23 × 0.7.
        double t = tan(35);
        double sq = 1 + StrictMath.sin(Math.toRadians(35));
        double expected = 18e3 * 33.30 * sq + 0.5 * 18e3 * 2 * 45.23 * 0.7;
        double v = 4e6;
        assertEquals(expected, Soil.bearing(0, t, 18e3, 18e3, 2, 2, 4, v, 0, 1), 1e-3 * expected);
        // Leaning by a tenth, the load lowers the factors by (1 - H/V)^m and (1 - H/V)^(m + 1), with m = 1.5 for a
        // square footing whichever way it leans.
        double leaning = 18e3 * 33.30 * sq * Math.pow(0.9, 1.5) + 0.5 * 18e3 * 2 * 45.23 * 0.7 * Math.pow(0.9, 2.5);
        assertEquals(leaning, Soil.bearing(0, t, 18e3, 18e3, 2, 2, 4, v, 0.1 * v, 1), 1e-3 * leaning);
        assertEquals(leaning, Soil.bearing(0, t, 18e3, 18e3, 2, 2, 4, v, 0.1 * v, 0.3), 1e-3 * leaning);
        // A load that leans as much as friction allows slides the footing instead.
        assertEquals(0, Soil.bearing(0, t, 18e3, 18e3, 2, 2, 4, v, v, 1));
    }

    @Test
    void aLongFootingBearsAsAStrip() {
        // A strip 1.5 m wide, 1 m down in soil with c = 10 kPa and φ = 25° that weighs 19 kN/m³:
        // q = c N_c + q N_q + 0.5 γ B N_γ, with no shape factors for a footing so much longer than it is wide.
        double expected = 10e3 * 20.72 + 19e3 * 10.66 + 0.5 * 19e3 * 1.5 * 9.01;
        double length = 1e6;
        double q = Soil.bearing(10e3, tan(25), 19e3, 19e3, 1.5, length, 1.5 * length, 1e9, 0, 1);
        assertEquals(expected, q, 1e-3 * expected);
    }

    @Test
    void aLoadLeaningAlongAFootingLowersItsBearingLessThanOneLeaningAcross() {
        // For a footing B' by L', m = (2 + B'/L') / (1 + B'/L') across it and (2 + L'/B') / (1 + L'/B') along it.
        double t = tan(30);
        double v = 1e6;
        double h = 0.2 * v;
        double nq = Soil.surchargeFactor(t);
        double ng = Soil.weightFactor(t);
        double sq = 1 + 0.5 * 0.5;
        double sg = 1 - 0.3 * 0.5;
        double across = 5 / 3.0;
        double along = 4 / 3.0;
        for (double toLength : new double[] {0, 1, 0.25}) {
            double m = along * toLength + across * (1 - toLength);
            double expected = 10e3 * nq * sq * Math.pow(0.8, m) + 0.5 * 18e3 * 1 * ng * sg * Math.pow(0.8, m + 1);
            assertEquals(expected, Soil.bearing(0, t, 18e3, 10e3, 1, 2, 2, v, h, toLength), 1e-9 * expected);
        }
    }

    @Test
    void clayThatCannotDrainBearsAsPrandtlFound() {
        // Without friction, q = (π + 2) c s_c i_c + q, with s_c = 1 + 0.2 B'/L' and, for a load leaning by H,
        // i_c = (1 + √(1 - H / (A' c))) / 2 (EN 1997-1 D.3).
        double c = 50e3;
        assertEquals((Math.PI + 2) * c * 1.2 + 20e3, Soil.bearing(c, 0, 18e3, 20e3, 1, 1, 1, 1e6, 0, 1), 1e-9);
        double ic = (1 + Math.sqrt(0.5)) / 2;
        assertEquals((Math.PI + 2) * c * 1.1 * ic, Soil.bearing(c, 0, 18e3, 0, 1, 2, 2, 1e6, c, 1), 1e-6);
        assertEquals(0, Soil.bearing(c, 0, 18e3, 0, 1, 2, 2, 1e6, 2 * c, 1));
    }

    @Test
    void theHalfSpaceHoldsASquareFootingAsItHoldsACircleOfTheSameSize() {
        // A rigid circular footing of radius R on an elastic half-space presses with K = 4GR / (1 - ν) and slides
        // with K = 8GR / (2 - ν) (Lysmer; Bycroft), and a square of the same area comes within a percent of both.
        // Rocking, 8GR³ / 3(1 - ν), it comes within a sixteenth of, for the circle of the same second moment.
        double g = 1e7;
        double nu = 0.3;
        double area = 4;
        double r = Math.sqrt(area / Math.PI);
        double pressing = 4 * g * r / (1 - nu);
        assertEquals(pressing, Soil.pressing(g, nu, area, 1), 0.01 * pressing);
        double sliding = 8 * g * r / (2 - nu);
        assertEquals(sliding, Soil.sliding(g, nu, area, 1, 1, true), 0.01 * sliding);
        assertEquals(sliding, Soil.sliding(g, nu, area, 1, 1, false), 0.01 * sliding);
        double inertia = 16 / 12.0;
        double rr = StrictMath.pow(4 * inertia / Math.PI, 0.25);
        double rocking = 8 * g * rr * rr * rr / (3 * (1 - nu));
        assertEquals(rocking, Soil.rocking(g, nu, inertia, 1, 1), rocking / 16);
    }

    @Test
    void aLongFootingSlidesMoreEasilyAlongItThanAcross() {
        // Gazetas: a strip 2L by 2B slides across with 2GL / (2 - ν) (2 + 2.5 χ^0.85), χ = A / 4L², and along with
        // 0.2 / (0.75 - ν) G L (1 - B/L) less.
        double g = 1e7;
        double nu = 0.3;
        double across = 2 * g * 3 / (2 - nu) * (2 + 2.5 * StrictMath.pow(6 / 36.0, 0.85));
        assertEquals(across, Soil.sliding(g, nu, 6, 3, 1, false), 1e-9 * across);
        double along = across - 0.2 / (0.75 - nu) * g * 3 * (1 - 1 / 3.0);
        assertEquals(along, Soil.sliding(g, nu, 6, 3, 1, true), 1e-9 * along);
    }

    private static double tan(double degrees) {
        return StrictMath.tan(Math.toRadians(degrees));
    }
}
