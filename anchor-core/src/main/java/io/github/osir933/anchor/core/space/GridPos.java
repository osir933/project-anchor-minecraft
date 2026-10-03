package io.github.osir933.anchor.core.space;

/**
 * Integer coordinates of a coarse cell, which is one Minecraft block: a 1 m cube whose minimum corner is
 * at ({@code x}, {@code y}, {@code z}) metres.
 *
 * @param x the x coordinate in blocks
 * @param y the y coordinate in blocks
 * @param z the z coordinate in blocks
 */
public record GridPos(int x, int y, int z) implements Comparable<GridPos> {

    /** Edge length of a section in blocks, matching a Minecraft chunk section. */
    public static final int SECTION_SIZE = 16;

    /**
     * Returns the packed position of the section containing this block.
     *
     * @return the section key, see {@link SectionPos}
     */
    public long sectionKey() {
        return SectionPos.pack(x >> 4, y >> 4, z >> 4);
    }

    /**
     * Returns this block's index within its section, in [0, 4096).
     *
     * @return the local index, {@code (y & 15) << 8 | (z & 15) << 4 | (x & 15)}
     */
    public int indexInSection() {
        return SectionPos.localIndex(x & 15, y & 15, z & 15);
    }

    /**
     * Returns the neighbouring block one step along an axis direction.
     *
     * @param direction the direction
     * @return the neighbour's position
     */
    public GridPos offset(Direction direction) {
        return new GridPos(x + direction.dx(), y + direction.dy(), z + direction.dz());
    }

    /**
     * Returns the block offset by a vector.
     *
     * @param dx the x offset
     * @param dy the y offset
     * @param dz the z offset
     * @return the offset position
     */
    public GridPos offset(int dx, int dy, int dz) {
        return new GridPos(x + dx, y + dy, z + dz);
    }

    /**
     * Returns the position of the block with the given local index in the given section.
     *
     * @param sectionKey the packed section position
     * @param index the local index in [0, 4096)
     * @return the block position
     */
    public static GridPos of(long sectionKey, int index) {
        return new GridPos(
                (SectionPos.x(sectionKey) << 4) | SectionPos.localX(index),
                (SectionPos.y(sectionKey) << 4) | SectionPos.localY(index),
                (SectionPos.z(sectionKey) << 4) | SectionPos.localZ(index));
    }

    /**
     * Orders positions by section key and then by local index, the order in which Anchor iterates the
     * world.
     *
     * @param other the other position
     * @return a negative, zero or positive number
     */
    @Override
    public int compareTo(GridPos other) {
        int bySection = Long.compare(sectionKey(), other.sectionKey());
        return bySection != 0 ? bySection : Integer.compare(indexInSection(), other.indexInSection());
    }
}
