package io.github.osir933.anchor.core.host;

import io.github.osir933.anchor.core.physics.thermal.Incandescence;
import io.github.osir933.anchor.core.space.Direction;
import java.util.Arrays;
import java.util.Objects;

/**
 * A block hot enough to glow, as {@link HostedWorld#glowingBlocks} finds it, with the temperatures across the faces
 * it shows. A host draws the glow from them with {@link Incandescence#glow}.
 *
 * <p>Each face is read on a grid of {@value #GRID} by {@value #GRID} spots, just inside the block: a whole block
 * is as hot all over, apart from a top open to the sky, while a refined block shows where its cells are hotter. Spot
 * (u, v) of a face covers the square from u / {@value #GRID} to (u + 1) / {@value #GRID} along the face's
 * {@linkplain #uAxis first axis} and likewise for v along its {@linkplain #vAxis second}, both counted from the
 * block's lower corner.
 *
 * @param index the block's index in its section
 * @param emissivity the emissivity of its surface, from 0 to 1
 * @param faces one bit for each face it shows, {@code 1 << face.ordinal()}; the faces of neighbours that hide them
 *     are left out
 * @param temperatures the temperatures of its faces in K, {@value #SAMPLES} for each face in {@link Direction}
 *     order, spot (u, v) at {@link #sample}; {@link Double#NaN} for faces it does not show, or spots with no matter
 */
public record GlowingBlock(int index, double emissivity, int faces, double[] temperatures) {

    /** The number of spots along each edge of a face. */
    public static final int GRID = 4;

    /** The number of spots on a face. */
    public static final int SAMPLES = GRID * GRID;

    /** How far inside the block a face's temperature is read, as a fraction of the block's edge. */
    public static final double INSET = 1.0 / 64.0;

    private static final int FACES = Direction.values().length;

    /**
     * Validates the block and keeps its temperatures as given.
     *
     * @param index the index in the section
     * @param emissivity the emissivity
     * @param faces the faces shown
     * @param temperatures the temperatures of every spot
     */
    public GlowingBlock {
        Objects.requireNonNull(temperatures, "temperatures");
        if (temperatures.length != FACES * SAMPLES) {
            throw new IllegalArgumentException("expected " + FACES * SAMPLES + " temperatures: " + temperatures.length);
        }
        if (faces < 0 || faces >= 1 << FACES) {
            throw new IllegalArgumentException("not a set of faces: " + faces);
        }
    }

    /**
     * Tells whether the block shows a face.
     *
     * @param face the face
     * @return {@code true} if no neighbour hides it
     */
    public boolean shows(Direction face) {
        return (faces & 1 << face.ordinal()) != 0;
    }

    /**
     * Returns the temperature of one spot of a face.
     *
     * @param face the face
     * @param u the spot along the face's first axis, from 0 to {@value #GRID} − 1
     * @param v the spot along its second axis
     * @return the temperature in K, or {@link Double#NaN}
     */
    public double temperature(Direction face, int u, int v) {
        return temperatures[sample(face, u, v)];
    }

    /**
     * Returns where in {@link #temperatures} a spot's temperature is.
     *
     * @param face the face
     * @param u the spot along the face's first axis
     * @param v the spot along its second axis
     * @return the index
     */
    public static int sample(Direction face, int u, int v) {
        return face.ordinal() * SAMPLES + v * GRID + u;
    }

    /**
     * Returns the axis along which a face's spots are counted first: z for the faces looking along x, x for the
     * others.
     *
     * @param face the face
     * @return 0 for x, 1 for y, 2 for z
     */
    public static int uAxis(Direction face) {
        return face.axis() == 0 ? 2 : 0;
    }

    /**
     * Returns the axis along which a face's spots are counted second: z for the top and bottom, y for the others.
     *
     * @param face the face
     * @return 0 for x, 1 for y, 2 for z
     */
    public static int vAxis(Direction face) {
        return face.axis() == 1 ? 2 : 1;
    }

    /**
     * Returns the point where a spot's temperature is read, just inside the block.
     *
     * @param face the face
     * @param u the spot along the face's first axis
     * @param v the spot along its second axis
     * @return the point's x, y and z within the block, each from 0 to 1
     */
    public static double[] point(Direction face, int u, int v) {
        double[] p = new double[3];
        p[face.axis()] = face.isPositive() ? 1.0 - INSET : INSET;
        p[uAxis(face)] = (u + 0.5) / GRID;
        p[vAxis(face)] = (v + 0.5) / GRID;
        return p;
    }

    /**
     * Returns the hottest spot on the faces the block shows.
     *
     * @return the temperature in K, or {@link Double#NaN} if no spot has matter
     */
    public double hottest() {
        double hottest = Double.NaN;
        for (double t : temperatures) {
            if (t > hottest || Double.isNaN(hottest) && !Double.isNaN(t)) {
                hottest = t;
            }
        }
        return hottest;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof GlowingBlock other && index == other.index
                && Double.compare(emissivity, other.emissivity) == 0 && faces == other.faces
                && Arrays.equals(temperatures, other.temperatures);
    }

    @Override
    public int hashCode() {
        return Objects.hash(index, emissivity, faces, Arrays.hashCode(temperatures));
    }

    @Override
    public String toString() {
        return "GlowingBlock[index=" + index + ", emissivity=" + emissivity + ", faces=" + Integer.toBinaryString(faces)
                + ", hottest=" + hottest() + "]";
    }
}
