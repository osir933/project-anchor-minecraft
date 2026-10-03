package io.github.osir933.anchor.core.space;

import java.util.Objects;

/**
 * Identifies a cell in the spatial hierarchy: a block, or a cube inside a block after refinement.
 *
 * <p>Level 0 is the block itself, 1 m across. Each level halves the edge length, so a level {@code L} cell
 * is {@code 2^-L} m across and its sub-coordinates run from 0 to {@code 2^L - 1} inside the block. Level 4
 * matches a Minecraft pixel (1/16 m).
 *
 * @param block the block containing the cell
 * @param level the refinement level, from 0 to {@link #MAX_LEVEL}
 * @param subX the x index within the block at this level
 * @param subY the y index within the block at this level
 * @param subZ the z index within the block at this level
 */
public record CellId(GridPos block, int level, int subX, int subY, int subZ) implements Comparable<CellId> {

    /**
     * The deepest refinement level, 2^-10 m (about 1 mm). Finer physics, such as microstructure or atoms,
     * uses its own representations rather than smaller cells.
     */
    public static final int MAX_LEVEL = 10;

    /**
     * Validates the id.
     *
     * @param block the block
     * @param level the level
     * @param subX the x index
     * @param subY the y index
     * @param subZ the z index
     */
    public CellId {
        Objects.requireNonNull(block, "block");
        if (level < 0 || level > MAX_LEVEL) {
            throw new IllegalArgumentException("level must be in [0, " + MAX_LEVEL + "]: " + level);
        }
        int size = 1 << level;
        if (subX < 0 || subY < 0 || subZ < 0 || subX >= size || subY >= size || subZ >= size) {
            throw new IllegalArgumentException("sub-coordinates out of range for level " + level);
        }
    }

    /**
     * Returns the whole-block cell.
     *
     * @param block the block
     * @return the level 0 cell
     */
    public static CellId of(GridPos block) {
        return new CellId(block, 0, 0, 0, 0);
    }

    /**
     * Returns the edge length of this cell.
     *
     * @return the edge length in metres
     */
    public double edgeLength() {
        return edgeLength(level);
    }

    /**
     * Returns the volume of this cell.
     *
     * @return the volume in cubic metres
     */
    public double volume() {
        double e = edgeLength();
        return e * e * e;
    }

    /**
     * Returns the edge length of cells at a level.
     *
     * @param level the refinement level
     * @return {@code 2^-level} metres
     */
    public static double edgeLength(int level) {
        return 1.0 / (1 << level);
    }

    /**
     * Returns the cell one level coarser that contains this one.
     *
     * @return the parent cell
     * @throws IllegalStateException for a level 0 cell
     */
    public CellId parent() {
        if (level == 0) {
            throw new IllegalStateException("a block has no parent cell");
        }
        return new CellId(block, level - 1, subX >> 1, subY >> 1, subZ >> 1);
    }

    /**
     * Returns one of the eight cells one level finer.
     *
     * @param octant bit 0 selects +x, bit 1 selects +y, bit 2 selects +z
     * @return the child cell
     */
    public CellId child(int octant) {
        if (octant < 0 || octant > 7) {
            throw new IllegalArgumentException("octant must be in [0, 8): " + octant);
        }
        return new CellId(block, level + 1,
                (subX << 1) | (octant & 1),
                (subY << 1) | ((octant >> 1) & 1),
                (subZ << 1) | ((octant >> 2) & 1));
    }

    /**
     * Returns the cell of the same size next to this one, which may lie in the neighbouring block.
     *
     * @param direction which face to cross
     * @return the adjacent cell
     */
    public CellId neighbor(Direction direction) {
        int size = 1 << level;
        int x = subX + direction.dx();
        int y = subY + direction.dy();
        int z = subZ + direction.dz();
        int bx = block.x();
        int by = block.y();
        int bz = block.z();
        if (x < 0) {
            x += size;
            bx--;
        } else if (x >= size) {
            x -= size;
            bx++;
        }
        if (y < 0) {
            y += size;
            by--;
        } else if (y >= size) {
            y -= size;
            by++;
        }
        if (z < 0) {
            z += size;
            bz--;
        } else if (z >= size) {
            z -= size;
            bz++;
        }
        return new CellId(new GridPos(bx, by, bz), level, x, y, z);
    }

    /**
     * Returns the cell's index along an axis at its own level.
     *
     * @param axis 0 for x, 1 for y, 2 for z
     * @return the sub-coordinate
     */
    public int sub(int axis) {
        return switch (axis) {
            case 0 -> subX;
            case 1 -> subY;
            case 2 -> subZ;
            default -> throw new IllegalArgumentException("axis must be 0, 1 or 2: " + axis);
        };
    }

    /**
     * Returns which child of its parent this cell is.
     *
     * @return the octant in [0, 8)
     */
    public int octant() {
        return (subX & 1) | ((subY & 1) << 1) | ((subZ & 1) << 2);
    }

    /**
     * Tells whether this cell contains another cell (or is the same cell).
     *
     * @param other the other cell
     * @return {@code true} if {@code other} lies inside this cell
     */
    public boolean contains(CellId other) {
        if (!block.equals(other.block) || other.level < level) {
            return false;
        }
        int shift = other.level - level;
        return (other.subX >> shift) == subX && (other.subY >> shift) == subY && (other.subZ >> shift) == subZ;
    }

    /**
     * Returns the world x coordinate of the cell's minimum corner.
     *
     * @return x in metres
     */
    public double minX() {
        return block.x() + subX * edgeLength();
    }

    /**
     * Returns the world y coordinate of the cell's minimum corner.
     *
     * @return y in metres
     */
    public double minY() {
        return block.y() + subY * edgeLength();
    }

    /**
     * Returns the world z coordinate of the cell's minimum corner.
     *
     * @return z in metres
     */
    public double minZ() {
        return block.z() + subZ * edgeLength();
    }

    /**
     * Returns the cell's position in Morton (Z-order) code at the finest level: bit {@code 3k} is bit
     * {@code k} of the x index, bit {@code 3k+1} of y and bit {@code 3k+2} of z, after scaling the indices
     * to {@link #MAX_LEVEL}. Sorting by this code visits an octree depth first, octant by octant.
     *
     * @return a 30-bit code
     */
    public int mortonCode() {
        int shift = MAX_LEVEL - level;
        return spread(subX << shift) | (spread(subY << shift) << 1) | (spread(subZ << shift) << 2);
    }

    /** Moves bit {@code k} of a 10-bit number to bit {@code 3k}. */
    private static int spread(int v) {
        int r = v & 0x3FF;
        r = (r | (r << 16)) & 0x030000FF;
        r = (r | (r << 8)) & 0x0300F00F;
        r = (r | (r << 4)) & 0x030C30C3;
        return (r | (r << 2)) & 0x09249249;
    }

    /**
     * Orders cells by block, then depth first through the block's octree: a parent comes before its
     * children and children come in octant order. This is the engine's iteration order.
     *
     * @param other the other cell
     * @return a negative, zero or positive number
     */
    @Override
    public int compareTo(CellId other) {
        int c = block.equals(other.block) ? 0 : block.compareTo(other.block);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(mortonCode(), other.mortonCode());
        return c != 0 ? c : Integer.compare(level, other.level);
    }
}
