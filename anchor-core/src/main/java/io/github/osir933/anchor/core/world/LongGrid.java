package io.github.osir933.anchor.core.world;

import java.util.Arrays;

/**
 * 4096 longs, one per block of a section, stored as a single value until the first cell differs. Also
 * used for small integer fields such as material indices.
 */
final class LongGrid {

    private long uniform;
    private long[] dense;

    LongGrid(long initial) {
        this.uniform = initial;
    }

    long get(int index) {
        return dense == null ? uniform : dense[index];
    }

    void set(int index, long value) {
        if (dense == null) {
            if (value == uniform) {
                return;
            }
            dense = new long[4096];
            Arrays.fill(dense, uniform);
        }
        dense[index] = value;
    }

    void fill(long value) {
        dense = null;
        uniform = value;
    }

    boolean isUniform() {
        return dense == null;
    }

    long uniformValue() {
        return uniform;
    }

    /** Returns to the single-value form if every cell holds the same value. */
    void compact() {
        if (dense == null) {
            return;
        }
        long first = dense[0];
        for (int i = 1; i < dense.length; i++) {
            if (dense[i] != first) {
                return;
            }
        }
        uniform = first;
        dense = null;
    }

    LongGrid copy() {
        LongGrid c = new LongGrid(uniform);
        c.dense = dense == null ? null : dense.clone();
        return c;
    }
}
