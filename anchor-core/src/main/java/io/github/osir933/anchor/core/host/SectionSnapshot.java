package io.github.osir933.anchor.core.host;

import io.github.osir933.anchor.core.space.SectionPos;
import io.github.osir933.anchor.core.world.Provenance;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The blocks of one section that a host has to save for them to come back as they were: those whose state
 * differs from what importing the section afresh from the host's blocks would give them. Every other block
 * comes back from the host's block at its section's climate, as on any import, so a section where nothing has
 * happened needs no snapshot at all.
 *
 * <p>Materials are named by id rather than by their index in a registry, so a snapshot survives a change in
 * the order materials are registered. Masses and enthalpies are kept exactly. A refined block is saved as its
 * totals and comes back whole.
 *
 * <p>Saved blocks that share a material, owner and provenance share one {@linkplain Entry palette entry}, so a
 * host stores a short palette and, per saved block, its position, palette index, mass and enthalpy.
 */
public final class SectionSnapshot {

    /**
     * What saved blocks in a palette entry have in common.
     *
     * @param material the material id
     * @param owner the id of the physical entity the matter belongs to, or {@code 0} for none
     * @param provenance where the state came from
     */
    public record Entry(String material, long owner, Provenance provenance) {

        /**
         * Validates the entry.
         *
         * @param material the material id
         * @param owner the owner
         * @param provenance the provenance
         */
        public Entry {
            Objects.requireNonNull(material, "material");
            Objects.requireNonNull(provenance, "provenance");
        }
    }

    private final List<Entry> palette;
    private final int[] blocks;
    private final int[] entries;
    private final double[] mass;
    private final double[] enthalpy;

    /**
     * Creates a snapshot. The arrays are copied.
     *
     * @param palette the distinct palette entries
     * @param blocks the local index of each saved block, as numbered by {@link SectionPos#localIndex}, in
     *     ascending order
     * @param entries each saved block's palette index
     * @param mass each saved block's mass in kilograms
     * @param enthalpy each saved block's enthalpy in joules
     * @throws IllegalArgumentException if the arrays differ in length, the blocks are not ascending local
     *     indices, a palette index is outside the palette, or a value could not be a block's state
     */
    public SectionSnapshot(List<Entry> palette, int[] blocks, int[] entries, double[] mass, double[] enthalpy) {
        this.palette = List.copyOf(palette);
        this.blocks = blocks.clone();
        this.entries = entries.clone();
        this.mass = mass.clone();
        this.enthalpy = enthalpy.clone();
        int n = this.blocks.length;
        if (this.entries.length != n || this.mass.length != n || this.enthalpy.length != n) {
            throw new IllegalArgumentException("a snapshot needs one palette index, mass and enthalpy per block: "
                    + n + " blocks, " + this.entries.length + " palette indices, " + this.mass.length
                    + " masses, " + this.enthalpy.length + " enthalpies");
        }
        for (int k = 0; k < n; k++) {
            int index = this.blocks[k];
            if (index < 0 || index >= SectionPos.BLOCKS || (k > 0 && index <= this.blocks[k - 1])) {
                throw new IllegalArgumentException("saved block " + k + " is at " + index
                        + "; blocks are local indices in ascending order");
            }
            if (this.entries[k] < 0 || this.entries[k] >= this.palette.size()) {
                throw new IllegalArgumentException("block " + index + " names palette entry " + this.entries[k]
                        + " of " + this.palette.size());
            }
            if (!(this.mass[k] >= 0) || !Double.isFinite(this.mass[k])) {
                throw new IllegalArgumentException("block " + index + " has mass " + this.mass[k]);
            }
            if (!Double.isFinite(this.enthalpy[k]) || (this.mass[k] == 0 && this.enthalpy[k] != 0)) {
                throw new IllegalArgumentException("block " + index + " has enthalpy " + this.enthalpy[k]
                        + " for mass " + this.mass[k]);
            }
        }
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
     * @return its local index in the section
     */
    public int block(int k) {
        return blocks[k];
    }

    /**
     * Returns a saved block's palette index.
     *
     * @param k the saved block's number
     * @return the index into {@link #palette()}
     */
    public int paletteIndex(int k) {
        return entries[k];
    }

    /**
     * Returns what a saved block shares with others.
     *
     * @param k the saved block's number
     * @return its palette entry
     */
    public Entry entry(int k) {
        return palette.get(entries[k]);
    }

    /**
     * Returns a saved block's mass.
     *
     * @param k the saved block's number
     * @return the mass in kilograms
     */
    public double mass(int k) {
        return mass[k];
    }

    /**
     * Returns a saved block's enthalpy.
     *
     * @param k the saved block's number
     * @return the enthalpy in joules
     */
    public double enthalpy(int k) {
        return enthalpy[k];
    }

    /**
     * Returns the palette.
     *
     * @return the distinct entries, unmodifiable
     */
    public List<Entry> palette() {
        return palette;
    }

    /**
     * Returns the local index of every saved block, for storing.
     *
     * @return a copy, in ascending order
     */
    public int[] blocks() {
        return blocks.clone();
    }

    /**
     * Returns every saved block's palette index, for storing.
     *
     * @return a copy, in the order of {@link #blocks()}
     */
    public int[] paletteIndices() {
        return entries.clone();
    }

    /**
     * Returns every saved block's mass, for storing.
     *
     * @return a copy, in the order of {@link #blocks()}
     */
    public double[] masses() {
        return mass.clone();
    }

    /**
     * Returns every saved block's enthalpy, for storing.
     *
     * @return a copy, in the order of {@link #blocks()}
     */
    public double[] enthalpies() {
        return enthalpy.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SectionSnapshot s && palette.equals(s.palette) && Arrays.equals(blocks, s.blocks)
                && Arrays.equals(entries, s.entries) && Arrays.equals(mass, s.mass)
                && Arrays.equals(enthalpy, s.enthalpy);
    }

    @Override
    public int hashCode() {
        return Objects.hash(palette, Arrays.hashCode(blocks), Arrays.hashCode(entries), Arrays.hashCode(mass),
                Arrays.hashCode(enthalpy));
    }

    @Override
    public String toString() {
        return "SectionSnapshot[" + blocks.length + " blocks, " + palette.size() + " palette entries]";
    }
}
