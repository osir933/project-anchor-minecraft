package io.github.osir933.anchor.core.host;

import io.github.osir933.anchor.core.world.BlockCopy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The state of a box of blocks, {@linkplain HostedWorld#capture saved} to {@linkplain HostedWorld#restore rewind}
 * the box to later, such as the starting point of an experiment.
 *
 * <p>Like a {@link SectionSnapshot}, it keeps only the blocks whose state differs from what the host's block there
 * starts as, so a box where nothing has happened needs almost nothing; the blocks it leaves out come back from the
 * host's blocks. Unlike one, it keeps refined blocks cell for cell, so the whole box comes back to the last bit. It
 * is not tied to a place: it can be restored with its lowest corner anywhere.
 *
 * <p>Blocks are numbered within the box by {@link #indexOf}, x fastest, then z, then y. Each saved block has one or
 * more cells, packed as {@link BlockCopy#pack} describes and stored block after block: the cells of one block cover
 * it exactly once, depth first in octant order, which tells where the next block's cells begin, and a whole block
 * is the one cell {@link BlockCopy#WHOLE}. Materials are named by id, in a palette of the same entries section
 * snapshots use; masses and enthalpies are kept exactly.
 */
public final class RegionSnapshot {

    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final List<SectionSnapshot.Entry> palette;
    private final int[] blocks;
    /** Where each saved block's cells begin; the last entry is where they end. */
    private final int[] firstCell;
    private final long[] cells;
    private final int[] entries;
    private final double[] mass;
    private final double[] enthalpy;

    /**
     * Creates a snapshot. The arrays are copied.
     *
     * @param sizeX the box's length along x, in blocks
     * @param sizeY the box's height, in blocks
     * @param sizeZ the box's length along z, in blocks
     * @param palette the distinct palette entries
     * @param blocks the index in the box of each saved block, as numbered by {@link #indexOf}, in ascending order
     * @param cells the packed cells of the saved blocks, block after block
     * @param entries each cell's palette index
     * @param mass each cell's mass in kilograms
     * @param enthalpy each cell's enthalpy in joules
     * @throws IllegalArgumentException if the box is empty or too large to number its blocks, the arrays of cells
     *     differ in length, the blocks are not ascending indices in the box, their cells do not cover them exactly
     *     once in order, a palette index is outside the palette, or a value could not be a cell's state
     */
    public RegionSnapshot(int sizeX, int sizeY, int sizeZ, List<SectionSnapshot.Entry> palette, int[] blocks,
            long[] cells, int[] entries, double[] mass, double[] enthalpy) {
        if (sizeX < 1 || sizeY < 1 || sizeZ < 1 || (long) sizeX * sizeY * sizeZ > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("a box of " + sizeX + " by " + sizeY + " by " + sizeZ
                    + " blocks cannot be saved");
        }
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.palette = List.copyOf(palette);
        this.blocks = blocks.clone();
        this.cells = cells.clone();
        this.entries = entries.clone();
        this.mass = mass.clone();
        this.enthalpy = enthalpy.clone();
        int n = this.cells.length;
        if (this.entries.length != n || this.mass.length != n || this.enthalpy.length != n) {
            throw new IllegalArgumentException("a snapshot needs one palette index, mass and enthalpy per cell: "
                    + n + " cells, " + this.entries.length + " palette indices, " + this.mass.length + " masses, "
                    + this.enthalpy.length + " enthalpies");
        }
        int volume = volume();
        this.firstCell = new int[this.blocks.length + 1];
        int cell = 0;
        for (int k = 0; k < this.blocks.length; k++) {
            int index = this.blocks[k];
            if (index < 0 || index >= volume || (k > 0 && index <= this.blocks[k - 1])) {
                throw new IllegalArgumentException("saved block " + k + " is at " + index
                        + "; blocks are indices in the box in ascending order");
            }
            firstCell[k] = cell;
            cell += BlockCopy.cellsOfBlock(this.cells, cell);
        }
        firstCell[this.blocks.length] = cell;
        if (cell != n) {
            throw new IllegalArgumentException((n - cell) + " cells belong to no saved block");
        }
        for (int c = 0; c < n; c++) {
            if (this.entries[c] < 0 || this.entries[c] >= this.palette.size()) {
                throw new IllegalArgumentException("cell " + c + " names palette entry " + this.entries[c] + " of "
                        + this.palette.size());
            }
            if (!(this.mass[c] >= 0) || !Double.isFinite(this.mass[c])) {
                throw new IllegalArgumentException("cell " + c + " has mass " + this.mass[c]);
            }
            if (!Double.isFinite(this.enthalpy[c]) || (this.mass[c] == 0 && this.enthalpy[c] != 0)) {
                throw new IllegalArgumentException("cell " + c + " has enthalpy " + this.enthalpy[c] + " for mass "
                        + this.mass[c]);
            }
        }
    }

    /**
     * Returns the box's length along x.
     *
     * @return the length in blocks
     */
    public int sizeX() {
        return sizeX;
    }

    /**
     * Returns the box's height.
     *
     * @return the height in blocks
     */
    public int sizeY() {
        return sizeY;
    }

    /**
     * Returns the box's length along z.
     *
     * @return the length in blocks
     */
    public int sizeZ() {
        return sizeZ;
    }

    /**
     * Returns how many blocks the box holds.
     *
     * @return the product of its three sizes
     */
    public int volume() {
        return sizeX * sizeY * sizeZ;
    }

    /**
     * Numbers a block of the box.
     *
     * @param dx how far the block lies from the box's lowest corner along x, from {@code 0} to {@code sizeX() - 1}
     * @param dy how far along y
     * @param dz how far along z
     * @return the block's index, {@code dx + sizeX * (dz + sizeZ * dy)}
     */
    public int indexOf(int dx, int dy, int dz) {
        if (dx < 0 || dy < 0 || dz < 0 || dx >= sizeX || dy >= sizeY || dz >= sizeZ) {
            throw new IllegalArgumentException("block " + dx + " " + dy + " " + dz + " lies outside a box of "
                    + sizeX + " by " + sizeY + " by " + sizeZ);
        }
        return dx + sizeX * (dz + sizeZ * dy);
    }

    /**
     * Returns how many blocks are saved.
     *
     * @return the count
     */
    public int size() {
        return blocks.length;
    }

    /**
     * Returns where a saved block is.
     *
     * @param k the saved block's number, from {@code 0} to {@code size() - 1}
     * @return its index in the box
     */
    public int block(int k) {
        return blocks[k];
    }

    /**
     * Returns where a saved block's cells begin.
     *
     * @param k the saved block's number
     * @return the number of its first cell
     */
    public int firstCell(int k) {
        return firstCell[k];
    }

    /**
     * Returns how many cells a saved block has.
     *
     * @param k the saved block's number
     * @return 1 for a whole block, more for a refined one
     */
    public int cellCount(int k) {
        return firstCell[k + 1] - firstCell[k];
    }

    /**
     * Returns a cell.
     *
     * @param c the cell's number, from {@code 0} to the number of cells saved, minus one
     * @return the packed cell
     */
    public long cell(int c) {
        return cells[c];
    }

    /**
     * Returns a cell's palette index.
     *
     * @param c the cell's number
     * @return the index into {@link #palette()}
     */
    public int paletteIndex(int c) {
        return entries[c];
    }

    /**
     * Returns what a cell shares with others.
     *
     * @param c the cell's number
     * @return its palette entry
     */
    public SectionSnapshot.Entry entry(int c) {
        return palette.get(entries[c]);
    }

    /**
     * Returns a cell's mass.
     *
     * @param c the cell's number
     * @return the mass in kilograms
     */
    public double mass(int c) {
        return mass[c];
    }

    /**
     * Returns a cell's enthalpy.
     *
     * @param c the cell's number
     * @return the enthalpy in joules
     */
    public double enthalpy(int c) {
        return enthalpy[c];
    }

    /**
     * Returns the palette.
     *
     * @return the distinct entries, unmodifiable
     */
    public List<SectionSnapshot.Entry> palette() {
        return palette;
    }

    /**
     * Returns the index in the box of every saved block, for storing.
     *
     * @return a copy, in ascending order
     */
    public int[] blocks() {
        return blocks.clone();
    }

    /**
     * Returns every saved cell, for storing.
     *
     * @return a copy of the packed cells, block after block
     */
    public long[] cells() {
        return cells.clone();
    }

    /**
     * Returns every cell's palette index, for storing.
     *
     * @return a copy, in the order of {@link #cells()}
     */
    public int[] paletteIndices() {
        return entries.clone();
    }

    /**
     * Returns every cell's mass, for storing.
     *
     * @return a copy, in the order of {@link #cells()}
     */
    public double[] masses() {
        return mass.clone();
    }

    /**
     * Returns every cell's enthalpy, for storing.
     *
     * @return a copy, in the order of {@link #cells()}
     */
    public double[] enthalpies() {
        return enthalpy.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RegionSnapshot s && sizeX == s.sizeX && sizeY == s.sizeY && sizeZ == s.sizeZ
                && palette.equals(s.palette) && Arrays.equals(blocks, s.blocks) && Arrays.equals(cells, s.cells)
                && Arrays.equals(entries, s.entries) && Arrays.equals(mass, s.mass)
                && Arrays.equals(enthalpy, s.enthalpy);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sizeX, sizeY, sizeZ, palette, Arrays.hashCode(blocks), Arrays.hashCode(cells),
                Arrays.hashCode(entries), Arrays.hashCode(mass), Arrays.hashCode(enthalpy));
    }

    @Override
    public String toString() {
        return "RegionSnapshot[" + sizeX + "x" + sizeY + "x" + sizeZ + ", " + blocks.length + " blocks saved in "
                + cells.length + " cells]";
    }

    /** Gathers the saved blocks of a snapshot, in the order of their indices in the box. */
    static final class Builder {
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;
        private final List<SectionSnapshot.Entry> palette = new ArrayList<>();
        private int[] blocks = new int[16];
        private int blockCount;
        private long[] cells = new long[16];
        private int[] entries = new int[16];
        private double[] mass = new double[16];
        private double[] enthalpy = new double[16];
        private int cellCount;
        private int lastEntry = -1;

        Builder(int sizeX, int sizeY, int sizeZ) {
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
        }

        /** Starts the next saved block; its cells follow. */
        void block(int index) {
            if (blockCount == blocks.length) {
                blocks = Arrays.copyOf(blocks, 2 * blockCount);
            }
            blocks[blockCount++] = index;
        }

        /** Adds a cell to the block last started. */
        void cell(long cell, SectionSnapshot.Entry entry, double cellMass, double cellEnthalpy) {
            if (cellCount == cells.length) {
                int grown = 2 * cellCount;
                cells = Arrays.copyOf(cells, grown);
                entries = Arrays.copyOf(entries, grown);
                mass = Arrays.copyOf(mass, grown);
                enthalpy = Arrays.copyOf(enthalpy, grown);
            }
            if (lastEntry < 0 || !palette.get(lastEntry).equals(entry)) {
                lastEntry = palette.indexOf(entry);
                if (lastEntry < 0) {
                    lastEntry = palette.size();
                    palette.add(entry);
                }
            }
            cells[cellCount] = cell;
            entries[cellCount] = lastEntry;
            mass[cellCount] = cellMass;
            enthalpy[cellCount] = cellEnthalpy;
            cellCount++;
        }

        RegionSnapshot build() {
            return new RegionSnapshot(sizeX, sizeY, sizeZ, palette, Arrays.copyOf(blocks, blockCount),
                    Arrays.copyOf(cells, cellCount), Arrays.copyOf(entries, cellCount),
                    Arrays.copyOf(mass, cellCount), Arrays.copyOf(enthalpy, cellCount));
        }
    }
}
