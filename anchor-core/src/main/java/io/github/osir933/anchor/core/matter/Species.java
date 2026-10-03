package io.github.osir933.anchor.core.matter;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A chemical species: a substance with a definite formula, independent of its phase. Water is one species
 * whether it is ice, liquid or steam.
 *
 * @param id a namespaced identifier, such as {@code anchor:h2o}
 * @param name the common name, such as {@code water}
 * @param formula the chemical formula
 */
public record Species(String id, String name, Formula formula) {

    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    /**
     * Validates the species.
     *
     * @param id the identifier
     * @param name the name
     * @param formula the formula
     */
    public Species {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(formula, "formula");
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("species id must look like namespace:path, got " + id);
        }
    }

    /**
     * Creates a species, parsing its formula.
     *
     * @param id the identifier
     * @param name the name
     * @param formula the formula text
     * @return the species
     */
    public static Species of(String id, String name, String formula) {
        return new Species(id, name, Formula.parse(formula));
    }

    /**
     * Returns the molar mass.
     *
     * @return the molar mass in kg/mol
     */
    public double molarMassKgPerMol() {
        return formula.molarMassKgPerMol();
    }
}
