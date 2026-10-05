package io.github.osir933.anchor.core.host;

import io.github.osir933.anchor.core.physics.structure.Frame;
import io.github.osir933.anchor.core.space.GridPos;
import java.util.Objects;

/**
 * A structure read from a {@link HostedWorld}, ready to {@linkplain
 * io.github.osir933.anchor.core.physics.structure.StructuralAnalysis analyse}: the built blocks joined to one
 * block, the ground they stand on, and the state of every block it looked at, so that the outcome is only
 * {@linkplain HostedWorld#settle settled} into the world if those blocks are still as they were.
 *
 * <p>A survey spreads from its first block through the built blocks joined to it, nearest first, up to a limit.
 * Built blocks beyond the limit, and blocks in sections that are not simulated, are taken to hold still as ground
 * does, so a large structure is analysed around the change that called for it; the survey counts them as its
 * {@linkplain #edge edge}.
 */
public final class StructureSurvey {

    /** The flags recorded for a position whose section was not simulated. */
    static final byte NOT_SIMULATED = -1;

    private final HostedWorld world;
    private final GridPos start;
    private final Frame frame;
    private final int blocks;
    private final int edge;
    private final GridPos[] examined;
    private final int[] hostIds;
    private final byte[] flags;

    StructureSurvey(HostedWorld world, GridPos start, Frame frame, int blocks, int edge, GridPos[] examined,
            int[] hostIds, byte[] flags) {
        this.world = world;
        this.start = start;
        this.frame = frame;
        this.blocks = blocks;
        this.edge = edge;
        this.examined = examined;
        this.hostIds = hostIds;
        this.flags = flags;
    }

    /**
     * Returns the block the survey spread from.
     *
     * @return its position
     */
    public GridPos start() {
        return start;
    }

    /**
     * Returns the structure as the structural model sees it.
     *
     * @return the frame
     */
    public Frame frame() {
        return frame;
    }

    /**
     * Returns how many built blocks the survey found joined together, the blocks free to move in its frame.
     *
     * @return the number of free blocks
     */
    public int blocks() {
        return blocks;
    }

    /**
     * Returns how many blocks at the survey's edge were taken to hold still because they lie beyond its limit or
     * in sections that are not simulated.
     *
     * @return the number of blocks; 0 if the survey found the whole structure
     */
    public int edge() {
        return edge;
    }

    /**
     * Tells whether the survey found the whole structure, so its analysis is exact.
     *
     * @return {@code true} if nothing at the edge was taken to hold still
     */
    public boolean whole() {
        return edge == 0;
    }

    /** Returns the hosted world that made the survey. */
    HostedWorld world() {
        return world;
    }

    /** Returns how many blocks the survey looked at. */
    int examinedCount() {
        return examined.length;
    }

    /** Returns a block the survey looked at, in position order. */
    GridPos examined(int k) {
        return examined[k];
    }

    /** Returns the host's id for a block the survey looked at, as it was then. */
    int hostId(int k) {
        return hostIds[k];
    }

    /** Returns the structural flags of a block the survey looked at, or {@link #NOT_SIMULATED}. */
    byte flags(int k) {
        return flags[k];
    }

    @Override
    public String toString() {
        return "StructureSurvey[from " + Objects.toString(start) + ", " + blocks + " blocks, " + edge
                + " held at the edge]";
    }
}
