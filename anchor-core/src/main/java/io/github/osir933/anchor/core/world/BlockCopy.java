package io.github.osir933.anchor.core.world;

import io.github.osir933.anchor.core.space.CellId;
import io.github.osir933.anchor.core.space.GridPos;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * An exact copy of one block's state, to {@linkplain PhysicalWorld#restoreBlocks put back} later to the last bit:
 * the whole block as one cell, or, if the block is refined, every leaf of its octree with its own state.
 *
 * <p>Cells are named within their block by a packed number ({@link #pack}) and listed depth first in octant order,
 * the order in which {@link RefinedBlock#forEachLeaf} visits leaves, so that together they cover the block exactly
 * once. A whole block is the one cell {@code 0}. Because the cells of a block end exactly where they have covered
 * it, the cells of many blocks can be stored one after another and told apart again ({@link #cellsOfBlock}).
 * Materials are indices into the world's {@link MaterialRegistry}.
 */
public final class BlockCopy {

    /** The packed number of the whole block, the level 0 cell. */
    public static final long WHOLE = 0L;

    private static final int SUB_BITS = 10;
    private static final long SUB_MASK = (1L << SUB_BITS) - 1;
    /** Bits a packed cell may use: four for the level and ten for each sub-coordinate. */
    private static final long USED = (1L << (4 + 3 * SUB_BITS)) - 1;
    /** The Morton codes of the finest cells of a block run up to this. */
    private static final long END = 1L << (3 * CellId.MAX_LEVEL);
    private static final GridPos ANYWHERE = new GridPos(0, 0, 0);

    private final long[] cells;
    private final CellState[] states;
    private final CellState total;

    private BlockCopy(long[] cells, CellState[] states, CellState total) {
        this.cells = cells;
        this.states = states;
        this.total = total;
    }

    /**
     * Returns a copy of a block that is not refined.
     *
     * @param state the block's state; it is copied
     * @return the copy
     */
    public static BlockCopy whole(CellState state) {
        CellState copy = state.copy();
        return new BlockCopy(new long[] {WHOLE}, new CellState[] {copy}, copy);
    }

    /**
     * Returns a copy of a block made of the given cells.
     *
     * @param cells the packed cells, depth first in octant order, covering the block exactly once; the array is
     *     copied
     * @param states each cell's state, in the same order; they are copied
     * @return the copy, refined unless it is the one cell {@link #WHOLE}
     * @throws IllegalArgumentException if the cells do not cover a block exactly once, in order, or there is not
     *     one state per cell
     */
    public static BlockCopy of(long[] cells, List<CellState> states) {
        if (cells.length != states.size()) {
            throw new IllegalArgumentException(cells.length + " cells need as many states, not " + states.size());
        }
        if (cellsOfBlock(cells, 0) != cells.length) {
            throw new IllegalArgumentException("the cells cover more than one block");
        }
        if (cells.length == 1) {
            return whole(states.get(0));
        }
        CellState[] copies = new CellState[cells.length];
        for (int i = 0; i < copies.length; i++) {
            copies[i] = states.get(i).copy();
        }
        long[] own = cells.clone();
        RefinedBlock tree = new RefinedBlock(ANYWHERE, copies[0]);
        apply(own, copies, tree);
        return new BlockCopy(own, copies, tree.aggregate());
    }

    /** Copies a refined block's leaves. */
    static BlockCopy of(RefinedBlock block) {
        List<CellState> states = new ArrayList<>(block.leafCount());
        long[] cells = new long[block.leafCount()];
        block.visitLive((cell, state) -> {
            cells[states.size()] = pack(cell);
            states.add(state.copy());
        });
        return new BlockCopy(cells, states.toArray(new CellState[0]), block.aggregate());
    }

    /**
     * Packs a cell's level and sub-coordinates into a number: the level in bits 0 to 3, then ten bits for each of
     * the x, y and z indices.
     *
     * @param cell the cell; its block is left out
     * @return the packed cell
     */
    public static long pack(CellId cell) {
        return cell.level() | (long) cell.subX() << 4 | (long) cell.subY() << (4 + SUB_BITS)
                | (long) cell.subZ() << (4 + 2 * SUB_BITS);
    }

    /**
     * Unpacks a cell.
     *
     * @param block the block the cell lies in
     * @param cell the packed cell
     * @return the cell
     * @throws IllegalArgumentException if the number is no packed cell
     */
    public static CellId unpack(GridPos block, long cell) {
        if ((cell & ~USED) != 0) {
            throw new IllegalArgumentException("no cell is packed as " + cell);
        }
        int level = (int) (cell & 15);
        if (level > CellId.MAX_LEVEL) {
            throw new IllegalArgumentException("cell " + cell + " is at level " + level + ", finer than level "
                    + CellId.MAX_LEVEL);
        }
        return new CellId(block, level, (int) (cell >> 4 & SUB_MASK), (int) (cell >> (4 + SUB_BITS) & SUB_MASK),
                (int) (cell >> (4 + 2 * SUB_BITS) & SUB_MASK));
    }

    /**
     * Counts the cells of one block in a run of packed cells stored block after block.
     *
     * @param cells packed cells
     * @param from where the block's cells begin
     * @return how many cells, from {@code from} on, cover the block
     * @throws IllegalArgumentException if they do not cover it exactly once, depth first in octant order
     */
    public static int cellsOfBlock(long[] cells, int from) {
        long covered = 0;
        int i = from;
        while (covered < END) {
            if (i >= cells.length) {
                throw new IllegalArgumentException("the cells from " + from + " end before they cover their block");
            }
            CellId cell = unpack(ANYWHERE, cells[i]);
            if (cell.mortonCode() != covered) {
                throw new IllegalArgumentException("cell " + i + " is out of place: a block's cells must cover it "
                        + "once, depth first in octant order");
            }
            covered += 1L << (3 * (CellId.MAX_LEVEL - cell.level()));
            i++;
        }
        return i - from;
    }

    /**
     * Tells whether the copy is of a refined block.
     *
     * @return {@code true} if it has more than one cell
     */
    public boolean isRefined() {
        return cells.length > 1;
    }

    /**
     * Returns how many cells the copy has.
     *
     * @return 1 for a whole block, otherwise the leaves of its octree
     */
    public int cellCount() {
        return cells.length;
    }

    /**
     * Returns a cell.
     *
     * @param i the cell's number, from {@code 0} to {@code cellCount() - 1}
     * @return the packed cell
     */
    public long cell(int i) {
        return cells[i];
    }

    /**
     * Returns a cell's state.
     *
     * @param i the cell's number
     * @return a copy of its state
     */
    public CellState state(int i) {
        return states[i].copy();
    }

    /**
     * Returns the block's coarse view, the one {@link PhysicalWorld#readBlock} gives once the copy is put back.
     *
     * @return a copy of the state of a whole block, or the aggregate of the cells of a refined one
     */
    public CellState total() {
        return total.copy();
    }

    /**
     * Gives an octree that is a single leaf the copy's cells.
     *
     * @return the leaves added
     */
    int applyTo(RefinedBlock block) {
        return apply(cells, states, block);
    }

    private static int apply(long[] cells, CellState[] states, RefinedBlock block) {
        int added = 0;
        for (int i = 0; i < cells.length; i++) {
            CellId cell = unpack(block.pos(), cells[i]);
            added += block.refineTo(cell);
            block.writeLive(block.liveLeaf(cell), states[i]);
        }
        return added;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BlockCopy other && Arrays.equals(cells, other.cells) && Arrays.equals(states, other.states);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(cells), Arrays.hashCode(states));
    }

    @Override
    public String toString() {
        return isRefined() ? "BlockCopy[" + cells.length + " cells, total " + total + "]" : "BlockCopy[" + total + "]";
    }
}
