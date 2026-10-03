package io.github.osir933.anchor.core.physics.thermal;

import io.github.osir933.anchor.core.space.Direction;

/**
 * The rays that sample what a block face sees: for each of the six face directions, the same number of
 * starting points spread over the face and directions into the half space in front of it.
 *
 * <p>Directions are cosine weighted, so each ray stands for the same share of the face's view: the fraction
 * of a face's rays that reach a surface estimates the view factor from the face to that surface, the share of
 * the face's emitted radiation that arrives there. Each ray is a point in four dimensions, two for where it
 * starts on the face and two for its direction, and the points are spread more evenly than random ones
 * would be. For the ray counts in {@link #LATTICES} they form a rank-1 lattice, point {@code j} of {@code n}
 * being the fractional part of {@code (j·g + ½) / n} for a generating vector {@code g} that was chosen, out of
 * all vectors, for reproducing best the exact view factors from a face to the unit squares around it. With 32
 * rays, the squares facing it one, two and three blocks away get the whole number of rays nearest their exact
 * share, and the four squares standing on its edges are within a ray of theirs. Other counts use a Hammersley
 * set for the directions and a Halton set for the starting points. The pattern is fixed, so every face of a
 * direction samples alike and results do not depend on anything but the world.
 */
final class RayPattern {

    private static final Direction[] DIRECTIONS = Direction.values();

    /**
     * Rank-1 lattices for common ray counts: the count, then the generating vector's entries for the polar
     * angle, the azimuth and the two coordinates of the starting point.
     */
    static final int[][] LATTICES = {
        {32, 1, 6, 30, 10},
    };

    private final int rays;
    /** Per direction, the starting points as offsets from the emitting block's minimum corner. */
    private final double[][] originX;
    private final double[][] originY;
    private final double[][] originZ;
    /** Per direction, the unit directions. */
    private final double[][] dirX;
    private final double[][] dirY;
    private final double[][] dirZ;

    /**
     * Builds a pattern.
     *
     * @param rays the number of rays per face, at least one
     */
    RayPattern(int rays) {
        if (rays < 1) {
            throw new IllegalArgumentException("a face needs at least one ray: " + rays);
        }
        this.rays = rays;
        int n = DIRECTIONS.length;
        originX = new double[n][rays];
        originY = new double[n][rays];
        originZ = new double[n][rays];
        dirX = new double[n][rays];
        dirY = new double[n][rays];
        dirZ = new double[n][rays];
        int[] lattice = latticeFor(rays);
        for (int j = 0; j < rays; j++) {
            double r1;
            double turn;
            double u;
            double v;
            if (lattice != null) {
                r1 = latticePoint(j, lattice[1], rays);
                turn = latticePoint(j, lattice[2], rays);
                u = latticePoint(j, lattice[3], rays);
                v = latticePoint(j, lattice[4], rays);
            } else {
                // A Hammersley point for the direction and a Halton point, which does not line up with it,
                // for the start.
                r1 = (j + 0.5) / rays;
                turn = radicalInverse(j, 2);
                u = radicalInverse(j + 1, 3);
                v = radicalInverse(j + 1, 5);
            }
            // Cosine weighted: a point spread evenly over the unit disk, lifted onto the hemisphere.
            double phi = 2.0 * StrictMath.PI * turn;
            double radius = Math.sqrt(r1);
            double a = radius * StrictMath.cos(phi);
            double b = radius * StrictMath.sin(phi);
            double up = Math.sqrt(1.0 - r1);
            for (Direction d : DIRECTIONS) {
                int k = d.ordinal();
                double sign = d.isPositive() ? 1.0 : -1.0;
                double plane = d.isPositive() ? 1.0 : 0.0;
                switch (d.axis()) {
                    case 0 -> {
                        set(k, j, plane, u, v, sign * up, a, b);
                    }
                    case 1 -> {
                        set(k, j, u, plane, v, a, sign * up, b);
                    }
                    default -> {
                        set(k, j, u, v, plane, a, b, sign * up);
                    }
                }
            }
        }
    }

    private void set(int direction, int ray, double ox, double oy, double oz, double dx, double dy, double dz) {
        originX[direction][ray] = ox;
        originY[direction][ray] = oy;
        originZ[direction][ray] = oz;
        dirX[direction][ray] = dx;
        dirY[direction][ray] = dy;
        dirZ[direction][ray] = dz;
    }

    /** Returns the lattice for a ray count, or {@code null} if there is none. */
    private static int[] latticeFor(int rays) {
        for (int[] lattice : LATTICES) {
            if (lattice[0] == rays) {
                return lattice;
            }
        }
        return null;
    }

    /** Returns a coordinate of a lattice point, the fractional part of {@code (index·generator + ½) / n}. */
    static double latticePoint(int index, int generator, int n) {
        return ((long) index * generator % n + 0.5) / n;
    }

    /** Returns the digits of a number in a base, mirrored about the point, as a fraction in [0, 1). */
    static double radicalInverse(int index, int base) {
        double inverse = 0.0;
        double scale = 1.0 / base;
        int i = index;
        while (i > 0) {
            inverse += (i % base) * scale;
            i /= base;
            scale /= base;
        }
        return inverse;
    }

    /** Returns the number of rays per face. */
    int rays() {
        return rays;
    }

    /** Returns where a ray starts along x, as an offset from the emitting block's minimum corner. */
    double originX(Direction face, int ray) {
        return originX[face.ordinal()][ray];
    }

    /** Returns where a ray starts along y, as an offset from the emitting block's minimum corner. */
    double originY(Direction face, int ray) {
        return originY[face.ordinal()][ray];
    }

    /** Returns where a ray starts along z, as an offset from the emitting block's minimum corner. */
    double originZ(Direction face, int ray) {
        return originZ[face.ordinal()][ray];
    }

    /** Returns the x component of a ray's unit direction. */
    double dirX(Direction face, int ray) {
        return dirX[face.ordinal()][ray];
    }

    /** Returns the y component of a ray's unit direction. */
    double dirY(Direction face, int ray) {
        return dirY[face.ordinal()][ray];
    }

    /** Returns the z component of a ray's unit direction. */
    double dirZ(Direction face, int ray) {
        return dirZ[face.ordinal()][ray];
    }
}
