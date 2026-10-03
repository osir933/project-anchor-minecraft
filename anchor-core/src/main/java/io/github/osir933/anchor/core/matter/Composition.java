package io.github.osir933.anchor.core.matter;

import io.github.osir933.anchor.core.math.CompensatedSum;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * What a piece of matter is made of, as mass fractions of chemical species.
 *
 * <p>Compositions are immutable, sorted by species id and normalised so the fractions sum to one. Element
 * mass fractions follow from the species' formulas, which is what lets the engine check that no process
 * creates or destroys an element.
 */
public final class Composition {

    /** Largest deviation of the input fractions' sum from one that is still accepted as rounding. */
    public static final double SUM_TOLERANCE = 1e-6;

    private static final Comparator<Species> BY_ID = Comparator.comparing(Species::id);

    private final List<Component> components;
    private final Map<Element, Double> elementFractions;

    private Composition(List<Component> components) {
        this.components = Collections.unmodifiableList(components);
        EnumMap<Element, Double> elements = new EnumMap<>(Element.class);
        for (Component c : components) {
            for (Map.Entry<Element, Double> e : c.species().formula().elementMassFractions().entrySet()) {
                elements.merge(e.getKey(), e.getValue() * c.massFraction(), Double::sum);
            }
        }
        this.elementFractions = Collections.unmodifiableMap(elements);
    }

    /**
     * Creates the composition of a pure substance.
     *
     * @param species the only species
     * @return a composition with mass fraction one
     */
    public static Composition pure(Species species) {
        Objects.requireNonNull(species, "species");
        return new Composition(List.of(new Component(species, 1.0)));
    }

    /**
     * Starts a composition listed species by species.
     *
     * @return an empty builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a composition from mass fractions.
     *
     * @param fractions mass fraction per species; they must sum to one within {@link #SUM_TOLERANCE}
     * @return the normalised composition
     * @throws IllegalArgumentException if a fraction is negative or the fractions do not sum to one
     */
    public static Composition ofMassFractions(Map<Species, Double> fractions) {
        Objects.requireNonNull(fractions, "fractions");
        TreeMap<Species, Double> sorted = new TreeMap<>(BY_ID);
        for (Map.Entry<Species, Double> e : fractions.entrySet()) {
            double f = e.getValue();
            if (!(f >= 0) || !Double.isFinite(f)) {
                throw new IllegalArgumentException("mass fraction must be non-negative: " + e);
            }
            if (f > 0) {
                Double previous = sorted.put(e.getKey(), f);
                if (previous != null) {
                    throw new IllegalArgumentException("two species share the id " + e.getKey().id());
                }
            }
        }
        if (sorted.isEmpty()) {
            throw new IllegalArgumentException("composition needs at least one species");
        }
        // Summed in id order, not the caller's map order, so the normalised fractions are bit-identical
        // however the input map iterates.
        CompensatedSum total = new CompensatedSum();
        for (double f : sorted.values()) {
            total.add(f);
        }
        double sum = total.value();
        if (Math.abs(sum - 1.0) > SUM_TOLERANCE) {
            throw new IllegalArgumentException("mass fractions sum to " + sum + ", not 1");
        }
        List<Component> components = new ArrayList<>(sorted.size());
        for (Map.Entry<Species, Double> e : sorted.entrySet()) {
            components.add(new Component(e.getKey(), e.getValue() / sum));
        }
        return new Composition(components);
    }

    /**
     * Mixes two portions of matter.
     *
     * @param a the first composition
     * @param massA the mass of the first portion, kg
     * @param b the second composition
     * @param massB the mass of the second portion, kg
     * @return the composition of the combined matter
     */
    public static Composition mix(Composition a, double massA, Composition b, double massB) {
        if (!(massA >= 0) || !(massB >= 0) || !(massA + massB > 0)) {
            throw new IllegalArgumentException("masses must be non-negative and not both zero");
        }
        TreeMap<Species, Double> masses = new TreeMap<>(BY_ID);
        for (Component c : a.components) {
            masses.merge(c.species(), c.massFraction() * massA, Double::sum);
        }
        for (Component c : b.components) {
            masses.merge(c.species(), c.massFraction() * massB, Double::sum);
        }
        double total = massA + massB;
        TreeMap<Species, Double> fractions = new TreeMap<>(BY_ID);
        for (Map.Entry<Species, Double> e : masses.entrySet()) {
            fractions.put(e.getKey(), e.getValue() / total);
        }
        return ofMassFractions(fractions);
    }

    /**
     * Returns the components, sorted by species id.
     *
     * @return an unmodifiable list of species with their mass fractions
     */
    public List<Component> components() {
        return components;
    }

    /**
     * Returns the mass fraction of a species.
     *
     * @param species the species
     * @return its mass fraction, zero if absent
     */
    public double massFraction(Species species) {
        for (Component c : components) {
            if (c.species().id().equals(species.id())) {
                return c.massFraction();
            }
        }
        return 0.0;
    }

    /**
     * Returns the mass fraction of each element.
     *
     * @return an unmodifiable map from element to mass fraction; the fractions sum to one
     */
    public Map<Element, Double> elementMassFractions() {
        return elementFractions;
    }

    /**
     * Returns the mean molar mass, the mass of one mole of the mixture's formula units.
     *
     * @return the mean molar mass in kg/mol
     */
    public double meanMolarMassKgPerMol() {
        double molesPerKg = 0.0;
        for (Component c : components) {
            molesPerKg += c.massFraction() / c.species().molarMassKgPerMol();
        }
        return 1.0 / molesPerKg;
    }

    /**
     * Tells whether this is a single species.
     *
     * @return {@code true} for pure substances
     */
    public boolean isPure() {
        return components.size() == 1;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Composition other && components.equals(other.components);
    }

    @Override
    public int hashCode() {
        return components.hashCode();
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder("Composition[");
        for (int i = 0; i < components.size(); i++) {
            Component c = components.get(i);
            if (i > 0) {
                out.append(", ");
            }
            out.append(c.species().id()).append('=').append(c.massFraction());
        }
        return out.append(']').toString();
    }

    /**
     * One species in a composition.
     *
     * @param species the species
     * @param massFraction its mass fraction, in (0, 1]
     */
    public record Component(Species species, double massFraction) {
    }

    /** Collects mass fractions species by species; see {@link #ofMassFractions(Map)}. */
    public static final class Builder {
        private final Map<Species, Double> fractions = new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Adds a species.
         *
         * @param species the species, not yet added
         * @param massFraction its mass fraction
         * @return this builder
         */
        public Builder add(Species species, double massFraction) {
            Objects.requireNonNull(species, "species");
            if (fractions.putIfAbsent(species, massFraction) != null) {
                throw new IllegalArgumentException(species.id() + " was added twice");
            }
            return this;
        }

        /**
         * Builds the composition.
         *
         * @return the normalised composition
         * @throws IllegalArgumentException if the fractions do not sum to one
         */
        public Composition build() {
            return ofMassFractions(fractions);
        }
    }
}
