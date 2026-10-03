package io.github.osir933.anchor.core.space;

/**
 * Packing of section coordinates into a {@code long}, bit-compatible with Minecraft's
 * {@code SectionPos.asLong}: 22 bits of x, 22 bits of z and 20 bits of y.
 *
 * <p>Section coordinates are block coordinates divided by 16. Local indices of blocks inside a section use
 * Minecraft's order, {@code y << 8 | z << 4 | x}.
 */
public final class SectionPos {

    /** Number of blocks in a section. */
    public static final int BLOCKS = 4096;

    private static final int X_BITS = 22;
    private static final int Z_BITS = 22;
    private static final int Y_BITS = 20;
    private static final long X_MASK = (1L << X_BITS) - 1;
    private static final long Z_MASK = (1L << Z_BITS) - 1;
    private static final long Y_MASK = (1L << Y_BITS) - 1;
    private static final int Y_OFFSET = 0;
    private static final int Z_OFFSET = Y_BITS;
    private static final int X_OFFSET = Y_BITS + Z_BITS;

    private SectionPos() {
    }

    /**
     * Packs section coordinates.
     *
     * @param x the section x
     * @param y the section y
     * @param z the section z
     * @return the packed key
     */
    public static long pack(int x, int y, int z) {
        return ((x & X_MASK) << X_OFFSET) | ((y & Y_MASK) << Y_OFFSET) | ((z & Z_MASK) << Z_OFFSET);
    }

    /**
     * Unpacks the section x coordinate.
     *
     * @param key the packed key
     * @return the section x
     */
    public static int x(long key) {
        return (int) (key << (64 - X_OFFSET - X_BITS) >> (64 - X_BITS));
    }

    /**
     * Unpacks the section y coordinate.
     *
     * @param key the packed key
     * @return the section y
     */
    public static int y(long key) {
        return (int) (key << (64 - Y_OFFSET - Y_BITS) >> (64 - Y_BITS));
    }

    /**
     * Unpacks the section z coordinate.
     *
     * @param key the packed key
     * @return the section z
     */
    public static int z(long key) {
        return (int) (key << (64 - Z_OFFSET - Z_BITS) >> (64 - Z_BITS));
    }

    /**
     * Returns the key of a neighbouring section.
     *
     * @param key the packed key
     * @param dx the x offset in sections
     * @param dy the y offset in sections
     * @param dz the z offset in sections
     * @return the neighbour's packed key
     */
    public static long offset(long key, int dx, int dy, int dz) {
        return pack(x(key) + dx, y(key) + dy, z(key) + dz);
    }

    /**
     * Returns the local index of a block within its section.
     *
     * @param localX x in [0, 16)
     * @param localY y in [0, 16)
     * @param localZ z in [0, 16)
     * @return the index in [0, 4096)
     */
    public static int localIndex(int localX, int localY, int localZ) {
        return (localY << 8) | (localZ << 4) | localX;
    }

    /**
     * Returns the local x of an index.
     *
     * @param index the local index
     * @return x in [0, 16)
     */
    public static int localX(int index) {
        return index & 15;
    }

    /**
     * Returns the local y of an index.
     *
     * @param index the local index
     * @return y in [0, 16)
     */
    public static int localY(int index) {
        return (index >>> 8) & 15;
    }

    /**
     * Returns the local z of an index.
     *
     * @param index the local index
     * @return z in [0, 16)
     */
    public static int localZ(int index) {
        return (index >>> 4) & 15;
    }

    /**
     * Formats a key for logs and the inspector.
     *
     * @param key the packed key
     * @return text such as {@code [3, -1, 7]}
     */
    public static String toString(long key) {
        return "[" + x(key) + ", " + y(key) + ", " + z(key) + "]";
    }
}
