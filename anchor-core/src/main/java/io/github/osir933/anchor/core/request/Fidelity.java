package io.github.osir933.anchor.core.request;

import io.github.osir933.anchor.core.space.CellId;

/**
 * How finely a request wants space resolved: the HOW of a request. Later phases add accuracy targets and
 * model choices here.
 *
 * @param level the refinement level cells should reach, from 0 (whole blocks) to {@link CellId#MAX_LEVEL}
 */
public record Fidelity(int level) {

    /** Whole blocks. */
    public static final Fidelity BLOCK = new Fidelity(0);

    /** Minecraft pixels, 1/16 m. */
    public static final Fidelity PIXEL = new Fidelity(4);

    /**
     * Validates the level.
     *
     * @param level the level
     */
    public Fidelity {
        if (level < 0 || level > CellId.MAX_LEVEL) {
            throw new IllegalArgumentException("level must be in [0, " + CellId.MAX_LEVEL + "]: " + level);
        }
    }

    /**
     * Returns the edge length of cells at this fidelity.
     *
     * @return the cell size in metres
     */
    public double cellSize() {
        return CellId.edgeLength(level);
    }
}
