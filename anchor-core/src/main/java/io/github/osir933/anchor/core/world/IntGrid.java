package io.github.osir933.anchor.core.world;

import java.util.Arrays;

/**
 * 4096 ints, one per block of a section, stored as a single value until the first cell differs. Holds
 * material indices.
 */
final class IntGrid {

    private int uniform;
    private int[] dense;

    IntGrid(int initial) {
        this.uniform = initial;
    }

    int get(int index) {
        return dense == null ? uniform : dense[index];
    }

    void set(int index, int value) {
        if (dense == null) {
            if (value == uniform) {
                return;
            }
            dense = new int[4096];
            Arrays.fill(dense, uniform);
        }
        dense[index] = value;
    }

    void fill(int value) {
        dense = null;
        uniform = value;
    }

    /** Copies all 4096 values into an array, starting at an offset. */
    void copyTo(int[] out, int offset) {
        if (dense == null) {
            Arrays.fill(out, offset, offset + 4096, uniform);
        } else {
            System.arraycopy(dense, 0, out, offset, 4096);
        }
    }

    boolean isUniform() {
        return dense == null;
    }

    int uniformValue() {
        return uniform;
    }

    /** Returns to the single-value form if every cell holds the same value. */
    void compact() {
        if (dense == null) {
            return;
        }
        int first = dense[0];
        for (int i = 1; i < dense.length; i++) {
            if (dense[i] != first) {
                return;
            }
        }
        uniform = first;
        dense = null;
    }

    IntGrid copy() {
        IntGrid c = new IntGrid(uniform);
        c.dense = dense == null ? null : dense.clone();
        return c;
    }
}
