package io.github.osir933.anchor.core.request;

import io.github.osir933.anchor.core.space.CellId;
import io.github.osir933.anchor.core.space.GridPos;
import java.util.Objects;

/** The part of space a simulation request covers: the WHERE of a request. */
public sealed interface Region permits Region.Box, Region.Sphere {

    /**
     * Tells whether the region overlaps a cell. Touching along a face does not count.
     *
     * @param cell the cell
     * @return {@code true} if some of the cell's volume lies inside the region
     */
    boolean intersects(CellId cell);

    /**
     * Returns the smallest box of whole blocks that contains the region.
     *
     * @return the bounding box
     */
    Box bounds();

    /**
     * An axis-aligned box of whole blocks.
     *
     * @param min the corner with the smallest coordinates, inclusive
     * @param max the corner with the largest coordinates, inclusive
     */
    record Box(GridPos min, GridPos max) implements Region {

        /**
         * Normalises the corners so that {@code min} is below {@code max} on every axis.
         *
         * @param min one corner
         * @param max the opposite corner
         */
        public Box {
            Objects.requireNonNull(min, "min");
            Objects.requireNonNull(max, "max");
            GridPos lo = new GridPos(Math.min(min.x(), max.x()), Math.min(min.y(), max.y()),
                    Math.min(min.z(), max.z()));
            GridPos hi = new GridPos(Math.max(min.x(), max.x()), Math.max(min.y(), max.y()),
                    Math.max(min.z(), max.z()));
            min = lo;
            max = hi;
        }

        /**
         * Returns a box holding a single block.
         *
         * @param block the block
         * @return the box
         */
        public static Box of(GridPos block) {
            return new Box(block, block);
        }

        @Override
        public boolean intersects(CellId cell) {
            double e = cell.edgeLength();
            return cell.minX() < max.x() + 1 && cell.minX() + e > min.x()
                    && cell.minY() < max.y() + 1 && cell.minY() + e > min.y()
                    && cell.minZ() < max.z() + 1 && cell.minZ() + e > min.z();
        }

        @Override
        public Box bounds() {
            return this;
        }

        /**
         * Returns the number of blocks in the box.
         *
         * @return the block count
         */
        public long blockCount() {
            return (long) (max.x() - min.x() + 1) * (max.y() - min.y() + 1) * (max.z() - min.z() + 1);
        }
    }

    /**
     * A ball around a point.
     *
     * @param centerX the centre's x coordinate in metres
     * @param centerY the centre's y coordinate in metres
     * @param centerZ the centre's z coordinate in metres
     * @param radius the radius in metres
     */
    record Sphere(double centerX, double centerY, double centerZ, double radius) implements Region {

        /**
         * Validates the sphere.
         *
         * @param centerX the centre x
         * @param centerY the centre y
         * @param centerZ the centre z
         * @param radius the radius
         */
        public Sphere {
            if (!Double.isFinite(centerX) || !Double.isFinite(centerY) || !Double.isFinite(centerZ)) {
                throw new IllegalArgumentException("sphere centre must be finite");
            }
            if (!(radius > 0) || !Double.isFinite(radius)) {
                throw new IllegalArgumentException("sphere radius must be positive: " + radius);
            }
        }

        @Override
        public boolean intersects(CellId cell) {
            double e = cell.edgeLength();
            double dx = gap(centerX, cell.minX(), e);
            double dy = gap(centerY, cell.minY(), e);
            double dz = gap(centerZ, cell.minZ(), e);
            return dx * dx + dy * dy + dz * dz < radius * radius;
        }

        private static double gap(double c, double min, double edge) {
            if (c < min) {
                return min - c;
            }
            return c > min + edge ? c - (min + edge) : 0.0;
        }

        @Override
        public Box bounds() {
            return new Box(
                    new GridPos((int) Math.floor(centerX - radius), (int) Math.floor(centerY - radius),
                            (int) Math.floor(centerZ - radius)),
                    new GridPos((int) Math.floor(centerX + radius), (int) Math.floor(centerY + radius),
                            (int) Math.floor(centerZ + radius)));
        }
    }
}
