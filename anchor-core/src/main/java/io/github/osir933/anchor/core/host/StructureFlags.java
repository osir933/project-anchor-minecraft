package io.github.osir933.anchor.core.host;

/**
 * The structural state of a block, as bits: whether it was built, and which of its joints have cracked.
 *
 * <p>A block is <em>built</em> when its matter came after its section was first simulated, as a placed block's
 * does; the world as it was found is ground, which holds still. A joint is kept with the block on its negative
 * side, so each block has bits for its joints with the next blocks along x, y and z.
 */
public final class StructureFlags {

    /** Set for a built block. */
    public static final int BUILT = 1;

    /** Set when a block's joint with the next block along x has cracked; the next two bits are for y and z. */
    public static final int CRACKED_X = 2;

    /** Every flag there is. */
    static final int ALL = BUILT | CRACKED_X | CRACKED_X << 1 | CRACKED_X << 2;

    private StructureFlags() {
    }

    /**
     * Returns the bit for a cracked joint.
     *
     * @param axis the axis the joint runs along: 0 for x, 1 for y, 2 for z
     * @return the bit
     */
    public static int cracked(int axis) {
        return CRACKED_X << axis;
    }
}
