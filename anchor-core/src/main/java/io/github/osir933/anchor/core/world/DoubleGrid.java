package io.github.osir933.anchor.core.world;

import java.util.Arrays;

/**
 * 4096 doubles, one per block of a section, stored as a single value until the first cell differs.
 *
 * <p>Most of a world is untouched terrain at a uniform state, so most grids never allocate their array.
 */
final class DoubleGrid {

    private double uniform;
    private double[] dense;

    DoubleGrid(double initial) {
        this.uniform = initial;
    }

    double get(int index) {
        return dense == null ? uniform : dense[index];
    }

    void set(int index, double value) {
        if (dense == null) {
            if (Double.doubleToRawLongBits(value) == Double.doubleToRawLongBits(uniform)) {
                return;
            }
            dense = new double[4096];
            Arrays.fill(dense, uniform);
        }
        dense[index] = value;
    }

    void fill(double value) {
        dense = null;
        uniform = value;
    }

    boolean isUniform() {
        return dense == null;
    }

    double uniformValue() {
        return uniform;
    }

    /** Returns to the single-value form if every cell holds the same value. */
    void compact() {
        if (dense == null) {
            return;
        }
        long first = Double.doubleToRawLongBits(dense[0]);
        for (int i = 1; i < dense.length; i++) {
            if (Double.doubleToRawLongBits(dense[i]) != first) {
                return;
            }
        }
        uniform = dense[0];
        dense = null;
    }

    DoubleGrid copy() {
        DoubleGrid c = new DoubleGrid(uniform);
        c.dense = dense == null ? null : dense.clone();
        return c;
    }
}
