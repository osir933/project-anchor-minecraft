package io.github.osir933.anchor.core.world;

import io.github.osir933.anchor.core.math.CompensatedSum;
import io.github.osir933.anchor.core.space.CellId;
import io.github.osir933.anchor.core.space.GridPos;
import io.github.osir933.anchor.core.space.SectionPos;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * A 16×16×16 cube of blocks, the unit in which the world is stored, loaded and saved. It lines up with a
 * Minecraft chunk section.
 *
 * <p>Each field is kept as a single value until the first block differs, so untouched terrain costs almost
 * nothing. Refined blocks keep their octree beside the grids; for them the accessors here return the
 * block's aggregate. Changes go through {@link PhysicalWorld}, which keeps the conservation ledger and
 * event log in step.
 */
public final class Section {

    private static final Provenance[] PROVENANCES = Provenance.values();

    private final long key;
    private final IntGrid material;
    private final DoubleGrid mass;
    private final DoubleGrid enthalpy;
    private final LongGrid owner;
    private final ByteGrid provenance;
    private final TreeMap<Integer, RefinedBlock> refined;
    private final long[] refinedMask;
    private int leafCount;

    private boolean totalsValid;
    private double totalMass;
    private double totalEnthalpy;
    private double totalAbsEnthalpy;

    Section(long key, CellState fill) {
        this.key = key;
        this.material = new IntGrid(fill.material());
        this.mass = new DoubleGrid(fill.mass());
        this.enthalpy = new DoubleGrid(fill.enthalpy());
        this.owner = new LongGrid(fill.owner());
        this.provenance = new ByteGrid((byte) fill.provenance().ordinal());
        this.refined = new TreeMap<>();
        this.refinedMask = new long[SectionPos.BLOCKS / 64];
    }

    private Section(Section other) {
        this.key = other.key;
        this.material = other.material.copy();
        this.mass = other.mass.copy();
        this.enthalpy = other.enthalpy.copy();
        this.owner = other.owner.copy();
        this.provenance = other.provenance.copy();
        this.refined = new TreeMap<>();
        for (var e : other.refined.entrySet()) {
            this.refined.put(e.getKey(), e.getValue().copy());
        }
        this.refinedMask = other.refinedMask.clone();
        this.leafCount = other.leafCount;
        this.totalsValid = other.totalsValid;
        this.totalMass = other.totalMass;
        this.totalEnthalpy = other.totalEnthalpy;
        this.totalAbsEnthalpy = other.totalAbsEnthalpy;
    }

    /** Returns a deep copy, used by snapshots. */
    Section copy() {
        return new Section(this);
    }

    /**
     * Returns the packed section position.
     *
     * @return the key, as packed by {@link SectionPos#pack}
     */
    public long key() {
        return key;
    }

    /**
     * Returns the position of a block in this section.
     *
     * @param index the block's index, as from {@link SectionPos#localIndex}
     * @return the block position
     */
    public GridPos blockPos(int index) {
        return GridPos.of(key, index);
    }

    /**
     * Tells whether a block is subdivided.
     *
     * @param index the block's index
     * @return {@code true} if the block has an octree of smaller cells
     */
    public boolean isRefined(int index) {
        return (refinedMask[index >>> 6] & (1L << index)) != 0;
    }

    /**
     * Returns a refined block's octree.
     *
     * @param index the block's index
     * @return the octree, or {@code null} if the block is not refined
     */
    public RefinedBlock refinedBlock(int index) {
        return isRefined(index) ? refined.get(index) : null;
    }

    /**
     * Returns the refined blocks in index order.
     *
     * @return an unmodifiable view
     */
    public Collection<RefinedBlock> refinedBlocks() {
        return Collections.unmodifiableCollection(refined.values());
    }

    /**
     * Returns the number of leaves in this section's refined blocks.
     *
     * @return the leaf count, zero if nothing is refined
     */
    public int leafCount() {
        return leafCount;
    }

    /**
     * Returns a block's material index.
     *
     * @param index the block's index
     * @return the material index; for a refined block, the material with the most mass
     */
    public int material(int index) {
        return isRefined(index) ? refined.get(index).aggregate().material() : material.get(index);
    }

    /**
     * Returns a block's mass.
     *
     * @param index the block's index
     * @return the mass in kilograms
     */
    public double mass(int index) {
        return isRefined(index) ? refined.get(index).aggregate().mass() : mass.get(index);
    }

    /**
     * Returns a block's enthalpy.
     *
     * @param index the block's index
     * @return the enthalpy in joules
     */
    public double enthalpy(int index) {
        return isRefined(index) ? refined.get(index).aggregate().enthalpy() : enthalpy.get(index);
    }

    /**
     * Returns a block's owning entity.
     *
     * @param index the block's index
     * @return the entity id, or {@code 0} for none
     */
    public long owner(int index) {
        return isRefined(index) ? refined.get(index).aggregate().owner() : owner.get(index);
    }

    /**
     * Returns where a block's state came from.
     *
     * @param index the block's index
     * @return the provenance
     */
    public Provenance provenance(int index) {
        return isRefined(index) ? refined.get(index).aggregate().provenance() : PROVENANCES[provenance.get(index)];
    }

    /**
     * Returns a copy of a block's state.
     *
     * @param index the block's index
     * @return the state, or the aggregate for a refined block
     */
    public CellState blockState(int index) {
        if (isRefined(index)) {
            return refined.get(index).aggregate();
        }
        return new CellState(material.get(index), mass.get(index), enthalpy.get(index), owner.get(index),
                PROVENANCES[provenance.get(index)]);
    }

    /**
     * Tells whether every block holds the same state and nothing is refined.
     *
     * @return {@code true} for a uniform section
     */
    public boolean isUniform() {
        return refined.isEmpty() && material.isUniform() && mass.isUniform() && enthalpy.isUniform()
                && owner.isUniform() && provenance.isUniform();
    }

    /**
     * Returns the total mass of the section.
     *
     * @return the mass in kilograms
     */
    public double totalMass() {
        updateTotals();
        return totalMass;
    }

    /**
     * Returns the total enthalpy of the section.
     *
     * @return the enthalpy in joules
     */
    public double totalEnthalpy() {
        updateTotals();
        return totalEnthalpy;
    }

    /**
     * Returns the sum of the absolute enthalpies of the section's blocks, the scale against which rounding
     * in the total is judged.
     *
     * @return the sum in joules
     */
    public double totalAbsoluteEnthalpy() {
        updateTotals();
        return totalAbsEnthalpy;
    }

    /**
     * Visits every leaf: whole blocks in index order, and the cells of refined blocks depth first.
     *
     * @param visitor receives each leaf and a view of its state that is only valid during the call
     */
    public void forEachLeaf(LeafVisitor visitor) {
        CellState scratch = CellState.vacuum(Provenance.INITIAL);
        for (int i = 0; i < SectionPos.BLOCKS; i++) {
            if (isRefined(i)) {
                refined.get(i).forEachLeaf(visitor);
            } else {
                scratch.set(material.get(i), mass.get(i), enthalpy.get(i), owner.get(i),
                        PROVENANCES[provenance.get(i)]);
                visitor.visit(CellId.of(blockPos(i)), scratch);
            }
        }
    }

    /** Visits every leaf's live state without building cell ids; for totals and hashing. */
    void visitLeafStates(Consumer<CellState> consumer) {
        CellState scratch = CellState.vacuum(Provenance.INITIAL);
        for (int i = 0; i < SectionPos.BLOCKS; i++) {
            if (isRefined(i)) {
                refined.get(i).visitLive((cell, state) -> consumer.accept(state));
            } else {
                scratch.set(material.get(i), mass.get(i), enthalpy.get(i), owner.get(i),
                        PROVENANCES[provenance.get(i)]);
                consumer.accept(scratch);
            }
        }
    }

    /** Tells whether two unrefined blocks hold bit-identical states. */
    boolean sameBlockState(int a, int b) {
        return material.get(a) == material.get(b)
                && Double.doubleToRawLongBits(mass.get(a)) == Double.doubleToRawLongBits(mass.get(b))
                && Double.doubleToRawLongBits(enthalpy.get(a)) == Double.doubleToRawLongBits(enthalpy.get(b))
                && owner.get(a) == owner.get(b)
                && provenance.get(a) == provenance.get(b);
    }

    // ---- changes, made through PhysicalWorld ----

    void setBlock(int index, CellState state) {
        Objects.requireNonNull(state, "state");
        if (isRefined(index)) {
            RefinedBlock block = refined.remove(index);
            leafCount -= block.leafCount();
            refinedMask[index >>> 6] &= ~(1L << index);
        }
        material.set(index, state.material());
        mass.set(index, state.mass());
        enthalpy.set(index, state.enthalpy());
        owner.set(index, state.owner());
        provenance.set(index, (byte) state.provenance().ordinal());
        totalsValid = false;
    }

    void fill(CellState state) {
        refined.clear();
        Arrays.fill(refinedMask, 0L);
        leafCount = 0;
        material.fill(state.material());
        mass.fill(state.mass());
        enthalpy.fill(state.enthalpy());
        owner.fill(state.owner());
        provenance.fill((byte) state.provenance().ordinal());
        totalsValid = false;
    }

    /** Returns the octree of a block, creating a single-leaf one from the block's state if needed. */
    RefinedBlock refinedBlockForUpdate(int index) {
        RefinedBlock block = refined.get(index);
        if (block == null) {
            block = new RefinedBlock(blockPos(index), blockState(index));
            refined.put(index, block);
            refinedMask[index >>> 6] |= 1L << index;
            leafCount += block.leafCount();
        }
        totalsValid = false;
        return block;
    }

    /** Records a change in a refined block's leaf count, and drops the octree if only its root is left. */
    void afterTreeChange(int index, int leafDelta) {
        leafCount += leafDelta;
        RefinedBlock block = refined.get(index);
        if (block != null && block.leafCount() == 1) {
            CellState root = block.rootState();
            refined.remove(index);
            refinedMask[index >>> 6] &= ~(1L << index);
            leafCount -= 1;
            material.set(index, root.material());
            mass.set(index, root.mass());
            enthalpy.set(index, root.enthalpy());
            owner.set(index, root.owner());
            provenance.set(index, (byte) root.provenance().ordinal());
        }
        totalsValid = false;
    }

    /** Marks the cached totals stale after a leaf was written. */
    void invalidateTotals() {
        totalsValid = false;
    }

    /** Returns grids to their single-value form where possible. */
    void compact() {
        material.compact();
        mass.compact();
        enthalpy.compact();
        owner.compact();
        provenance.compact();
    }

    private void updateTotals() {
        if (totalsValid) {
            return;
        }
        if (refined.isEmpty() && mass.isUniform() && enthalpy.isUniform()) {
            // 4096 is a power of two, so these products are exact.
            totalMass = SectionPos.BLOCKS * mass.uniformValue();
            totalEnthalpy = SectionPos.BLOCKS * enthalpy.uniformValue();
            totalAbsEnthalpy = Math.abs(totalEnthalpy);
        } else {
            CompensatedSum m = new CompensatedSum();
            CompensatedSum h = new CompensatedSum();
            CompensatedSum abs = new CompensatedSum();
            for (int i = 0; i < SectionPos.BLOCKS; i++) {
                if (isRefined(i)) {
                    RefinedBlock block = refined.get(i);
                    CellState a = block.aggregate();
                    m.add(a.mass());
                    h.add(a.enthalpy());
                    block.visitLive((cell, state) -> abs.add(Math.abs(state.enthalpy())));
                } else {
                    m.add(mass.get(i));
                    double hi = enthalpy.get(i);
                    h.add(hi);
                    abs.add(Math.abs(hi));
                }
            }
            totalMass = m.value();
            totalEnthalpy = h.value();
            totalAbsEnthalpy = abs.value();
        }
        totalsValid = true;
    }

    @Override
    public String toString() {
        return "Section[" + SectionPos.toString(key) + "]";
    }
}
