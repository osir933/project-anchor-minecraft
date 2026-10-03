package io.github.osir933.anchor.core.world;

import java.util.Objects;

/**
 * The conserved state of one cell: which material it holds, how much of it, its enthalpy, which physical
 * entity it belongs to, and where the state came from.
 *
 * <p>Temperature and phase are not stored; they follow from enthalpy through the material's
 * {@link io.github.osir933.anchor.core.matter.EnthalpyCurve}. That way heat flow conserves energy exactly
 * and melting needs no special rule.
 */
public final class CellState {

    private int material;
    private double mass;
    private double enthalpy;
    private long owner;
    private Provenance provenance;

    /**
     * Creates a vacuum cell.
     *
     * @param provenance where this state came from
     * @return an empty cell
     */
    public static CellState vacuum(Provenance provenance) {
        return new CellState(MaterialRegistry.VACUUM, 0.0, 0.0, 0L, provenance);
    }

    /**
     * Creates a state.
     *
     * @param material the material's index in the world's {@link MaterialRegistry}
     * @param mass the mass in kilograms, non-negative; zero for {@link MaterialRegistry#VACUUM}
     * @param enthalpy the enthalpy in joules, relative to the material's reference state; zero without mass
     * @param owner the id of the physical entity the matter belongs to, or {@code 0} for none
     * @param provenance where this state came from
     */
    public CellState(int material, double mass, double enthalpy, long owner, Provenance provenance) {
        set(material, mass, enthalpy, owner, provenance);
    }

    /**
     * Replaces every field.
     *
     * @param material the material index
     * @param mass the mass in kilograms
     * @param enthalpy the enthalpy in joules
     * @param owner the owning entity id
     * @param provenance the provenance
     * @return this state
     */
    public CellState set(int material, double mass, double enthalpy, long owner, Provenance provenance) {
        if (material < 0) {
            throw new IllegalArgumentException("material index must be non-negative: " + material);
        }
        if (!(mass >= 0) || !Double.isFinite(mass)) {
            throw new IllegalArgumentException("mass must be finite and non-negative: " + mass);
        }
        if (!Double.isFinite(enthalpy)) {
            throw new IllegalArgumentException("enthalpy must be finite: " + enthalpy);
        }
        if (mass == 0 && enthalpy != 0) {
            throw new IllegalArgumentException("a cell without mass cannot hold enthalpy: " + enthalpy);
        }
        if (material == MaterialRegistry.VACUUM && mass != 0) {
            throw new IllegalArgumentException("vacuum cannot have mass: " + mass);
        }
        this.material = material;
        this.mass = mass;
        this.enthalpy = enthalpy;
        this.owner = owner;
        this.provenance = Objects.requireNonNull(provenance, "provenance");
        return this;
    }

    /**
     * Copies another state into this one.
     *
     * @param other the state to copy
     * @return this state
     */
    public CellState set(CellState other) {
        return set(other.material, other.mass, other.enthalpy, other.owner, other.provenance);
    }

    /**
     * Returns a copy of this state.
     *
     * @return the copy
     */
    public CellState copy() {
        return new CellState(material, mass, enthalpy, owner, provenance);
    }

    /**
     * Returns the material index.
     *
     * @return the index in the world's material registry
     */
    public int material() {
        return material;
    }

    /**
     * Returns the mass.
     *
     * @return the mass in kilograms
     */
    public double mass() {
        return mass;
    }

    /**
     * Returns the enthalpy.
     *
     * @return the enthalpy in joules
     */
    public double enthalpy() {
        return enthalpy;
    }

    /**
     * Returns the specific enthalpy, or zero for an empty cell.
     *
     * @return the specific enthalpy in J/kg
     */
    public double specificEnthalpy() {
        return mass > 0 ? enthalpy / mass : 0.0;
    }

    /**
     * Returns the owning entity id.
     *
     * @return the entity id, or {@code 0} for none
     */
    public long owner() {
        return owner;
    }

    /**
     * Returns where this state came from.
     *
     * @return the provenance
     */
    public Provenance provenance() {
        return provenance;
    }

    /**
     * Sets the provenance.
     *
     * @param provenance the provenance
     * @return this state
     */
    public CellState provenance(Provenance provenance) {
        this.provenance = Objects.requireNonNull(provenance, "provenance");
        return this;
    }

    @Override
    public boolean equals(Object o) {
        // Bitwise comparison of the doubles: the engine promises bit-identical replays, not approximate ones.
        return o instanceof CellState other
                && material == other.material
                && Double.doubleToLongBits(mass) == Double.doubleToLongBits(other.mass)
                && Double.doubleToLongBits(enthalpy) == Double.doubleToLongBits(other.enthalpy)
                && owner == other.owner
                && provenance == other.provenance;
    }

    @Override
    public int hashCode() {
        return Objects.hash(material, mass, enthalpy, owner, provenance);
    }

    @Override
    public String toString() {
        return "CellState[material=" + material + ", mass=" + mass + ", enthalpy=" + enthalpy + ", owner=" + owner
                + ", provenance=" + provenance + "]";
    }
}
