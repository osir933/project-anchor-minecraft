package io.github.osir933.anchor.core.space;

import java.util.List;

/** The six axis-aligned face directions of a cell. */
public enum Direction {
    /** Negative x (west). */
    WEST(-1, 0, 0),
    /** Positive x (east). */
    EAST(1, 0, 0),
    /** Negative y (down). */
    DOWN(0, -1, 0),
    /** Positive y (up). */
    UP(0, 1, 0),
    /** Negative z (north). */
    NORTH(0, 0, -1),
    /** Positive z (south). */
    SOUTH(0, 0, 1);

    private static final Direction[] VALUES = values();

    /** The three directions that point along a positive axis, in axis order: east, up, south. */
    public static final List<Direction> POSITIVE = List.of(EAST, UP, SOUTH);

    private final int dx;
    private final int dy;
    private final int dz;

    Direction(int dx, int dy, int dz) {
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
    }

    /**
     * Returns the x step.
     *
     * @return -1, 0 or 1
     */
    public int dx() {
        return dx;
    }

    /**
     * Returns the y step.
     *
     * @return -1, 0 or 1
     */
    public int dy() {
        return dy;
    }

    /**
     * Returns the z step.
     *
     * @return -1, 0 or 1
     */
    public int dz() {
        return dz;
    }

    /**
     * Returns the opposite direction.
     *
     * @return the direction pointing the other way
     */
    public Direction opposite() {
        return VALUES[ordinal() ^ 1];
    }

    /**
     * Returns the axis this direction runs along.
     *
     * @return 0 for x, 1 for y, 2 for z
     */
    public int axis() {
        return ordinal() >> 1;
    }

    /**
     * Tells whether this direction points along the positive axis.
     *
     * @return {@code true} for east, up and south
     */
    public boolean isPositive() {
        return (ordinal() & 1) == 1;
    }
}
