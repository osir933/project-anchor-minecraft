package io.github.osir933.anchor.core.physics.structure;

import io.github.osir933.anchor.core.matter.Mechanics;
import java.util.Objects;

/**
 * The stress inside one block that the temperatures of its parts set up: thermal stress, which cracks glass doused
 * with cold water and stone beside a fire.
 *
 * <p>A body that warms evenly, or whose temperature changes in a straight line across it, expands freely and is not
 * stressed at all: the straight-line part only bends it. Only what departs from a straight line is held back by the
 * rest of the body. So the thermal strain of each part, the expansion its temperature gives it from the block's mean
 * temperature, is fitted by least squares with a field that changes in a straight line across the block, each part
 * weighted by its mass and stiffness so that the stresses left over balance as they must in a free body. What a part
 * departs from that field is the strain the rest of the block forces on it: a part colder than the field is pulled, a
 * hotter one pressed. A thin layer of a large body is held along it in both directions, so the stress is
 * E/(1&nbsp;&minus;&nbsp;&nu;) times that strain. That is exact for a slab heated or cooled on its faces and at the
 * surface of a sphere, and errs high at the corners of a cube.
 *
 * <p>Each part is a cell with a temperature of its own, so how finely a block is refined decides how much of its
 * stress shows: two cells along a line always lie on a straight line, and the steep fall in temperature at a face
 * that is quenched spreads over the cell beside it. The stress is checked as an intact joint of brittle matter is:
 * against the tensile strength where a part is pulled and the compressive strength where it is pressed, both at the
 * part's own temperature, as is its stiffness.
 */
public final class ThermalShock {

    /**
     * The most loaded part of a block.
     *
     * @param load the stress as a fraction of the strength it is checked against; one or more cracks the block
     * @param stressPa the stress there, in pascals: positive where the part is pulled, negative where it is pressed
     * @param part the index of the part, or -1 if no part is stressed
     */
    public record Result(double load, double stressPa, int part) {

        /**
         * Tells whether the most loaded part is pulled rather than pressed.
         *
         * @return {@code true} if it is pulled
         */
        public boolean tension() {
            return stressPa > 0;
        }
    }

    /** A block with no stress in it. */
    public static final Result NONE = new Result(0.0, 0.0, -1);

    /** Pivots smaller than this fraction of the largest are taken as zero, for parts that all lie in a plane. */
    private static final double SINGULAR = 1e-12;

    private ThermalShock() {
    }

    /**
     * Works out the thermal stress in each part of a block and returns the most loaded part.
     *
     * @param mechanics the block's mechanics
     * @param x the x coordinate of each part's centre, in metres
     * @param y the y coordinate of each part's centre, in metres
     * @param z the z coordinate of each part's centre, in metres
     * @param mass each part's mass, in kilograms
     * @param temperatureK each part's temperature, in kelvin
     * @param parts how many parts there are, from the start of each array
     * @return the most loaded part, the first of them if several are as loaded; {@link #NONE} if no part is stressed
     * @throws IllegalArgumentException if an array is shorter than {@code parts}, or a mass or temperature is not
     *     positive and finite
     */
    public static Result analyse(Mechanics mechanics, double[] x, double[] y, double[] z, double[] mass,
            double[] temperatureK, int parts) {
        Objects.requireNonNull(mechanics, "mechanics");
        for (double[] a : new double[][] {x, y, z, mass, temperatureK}) {
            if (a.length < parts) {
                throw new IllegalArgumentException("an array holds " + a.length + " values for " + parts + " parts");
            }
        }
        double total = 0.0;
        double mean = 0.0;
        for (int i = 0; i < parts; i++) {
            if (!(mass[i] > 0) || !Double.isFinite(mass[i]) || !(temperatureK[i] > 0)
                    || !Double.isFinite(temperatureK[i])) {
                throw new IllegalArgumentException("part " + i + " has mass " + mass[i] + " and temperature "
                        + temperatureK[i]);
            }
            total += mass[i];
            mean += mass[i] * temperatureK[i];
        }
        if (parts < 2) {
            return NONE;
        }
        mean /= total;
        double[] strain = new double[parts];
        double[] weight = new double[parts];
        double weights = 0.0;
        double cx = 0.0;
        double cy = 0.0;
        double cz = 0.0;
        for (int i = 0; i < parts; i++) {
            strain[i] = mechanics.thermalStrain(mean, temperatureK[i]);
            weight[i] = mass[i] * mechanics.youngsModulus(temperatureK[i]);
            weights += weight[i];
            cx += weight[i] * x[i];
            cy += weight[i] * y[i];
            cz += weight[i] * z[i];
        }
        if (!(weights > 0)) {
            return NONE;
        }
        cx /= weights;
        cy /= weights;
        cz /= weights;
        // Weighted least squares for strain = a + g · (r - c); with r centred, a is the weighted mean strain and the
        // gradient g solves the 3 by 3 normal equations.
        double a = 0.0;
        double[][] m = new double[3][4];
        for (int i = 0; i < parts; i++) {
            double w = weight[i];
            double[] d = {x[i] - cx, y[i] - cy, z[i] - cz};
            a += w * strain[i];
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 3; c++) {
                    m[r][c] += w * d[r] * d[c];
                }
                m[r][3] += w * d[r] * strain[i];
            }
        }
        a /= weights;
        double[] g = solve(m);
        double best = 0.0;
        double bestStress = 0.0;
        int bestPart = -1;
        double lateral = 1.0 / (1.0 - mechanics.poissonRatio());
        for (int i = 0; i < parts; i++) {
            double fit = a + g[0] * (x[i] - cx) + g[1] * (y[i] - cy) + g[2] * (z[i] - cz);
            double stress = mechanics.youngsModulus(temperatureK[i]) * lateral * (fit - strain[i]);
            double strength = stress > 0 ? mechanics.tensileStrength(temperatureK[i])
                    : mechanics.compressiveStrength(temperatureK[i]);
            double load;
            if (stress == 0) {
                load = 0.0;
            } else {
                load = strength > 0 ? Math.abs(stress) / strength : Double.POSITIVE_INFINITY;
            }
            if (load > best) {
                best = load;
                bestStress = stress;
                bestPart = i;
            }
        }
        return bestPart < 0 ? NONE : new Result(best, bestStress, bestPart);
    }

    /**
     * Solves a 3 by 3 symmetric system given with its right-hand side as a fourth column, by elimination with
     * partial pivoting. An unknown whose pivot is negligible, as for parts that all lie in one plane, is taken as
     * zero.
     */
    private static double[] solve(double[][] m) {
        double scale = Math.max(m[0][0], Math.max(m[1][1], m[2][2]));
        boolean[] used = new boolean[3];
        double[] g = new double[3];
        if (!(scale > 0)) {
            return g;
        }
        int[] pivotRow = {-1, -1, -1};
        for (int c = 0; c < 3; c++) {
            int pivot = -1;
            double largest = SINGULAR * scale;
            for (int r = 0; r < 3; r++) {
                if (!used[r] && Math.abs(m[r][c]) > largest) {
                    largest = Math.abs(m[r][c]);
                    pivot = r;
                }
            }
            if (pivot < 0) {
                continue;
            }
            used[pivot] = true;
            pivotRow[c] = pivot;
            for (int r = 0; r < 3; r++) {
                if (r != pivot) {
                    double f = m[r][c] / m[pivot][c];
                    for (int k = c; k < 4; k++) {
                        m[r][k] -= f * m[pivot][k];
                    }
                }
            }
        }
        for (int c = 0; c < 3; c++) {
            if (pivotRow[c] >= 0) {
                g[c] = m[pivotRow[c]][3] / m[pivotRow[c]][c];
            }
        }
        return g;
    }
}
