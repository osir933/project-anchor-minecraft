package io.github.osir933.anchor.core.world;

import java.util.Arrays;

/**
 * 4096 bytes, one per block of a section, stored as a single value until the first cell differs. Holds
 * small enumerations such as {@link Provenance}.
 */
final class ByteGrid {

    private byte uniform;
    private byte[] dense;

    ByteGrid(byte initial) {
        this.uniform = initial;
    }

    byte get(int index) {
        return dense == null ? uniform : dense[index];
    }

    void set(int index, byte value) {
        if (dense == null) {
            if (value == uniform) {
                return;
            }
            dense = new byte[4096];
            Arrays.fill(dense, uniform);
        }
        dense[index] = value;
    }

    void fill(byte value) {
        dense = null;
        uniform = value;
    }

    boolean isUniform() {
        return dense == null;
    }

    byte uniformValue() {
        return uniform;
    }

    /** Returns to the single-value form if every cell holds the same value. */
    void compact() {
        if (dense == null) {
            return;
        }
        byte first = dense[0];
        for (int i = 1; i < dense.length; i++) {
            if (dense[i] != first) {
                return;
            }
        }
        uniform = first;
        dense = null;
    }

    ByteGrid copy() {
        ByteGrid c = new ByteGrid(uniform);
        c.dense = dense == null ? null : dense.clone();
        return c;
    }
}
