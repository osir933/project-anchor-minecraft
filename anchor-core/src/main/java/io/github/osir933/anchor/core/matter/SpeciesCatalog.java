package io.github.osir933.anchor.core.matter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Common chemical species used by the built-in materials. Data packs can add more through
 * {@link #register(Species)}.
 */
public final class SpeciesCatalog {

    /** Water. */
    public static final Species WATER = Species.of("anchor:h2o", "water", "H2O");
    /** Nitrogen gas. */
    public static final Species NITROGEN = Species.of("anchor:n2", "nitrogen", "N2");
    /** Oxygen gas. */
    public static final Species OXYGEN = Species.of("anchor:o2", "oxygen", "O2");
    /** Argon. */
    public static final Species ARGON = Species.of("anchor:ar", "argon", "Ar");
    /** Carbon dioxide. */
    public static final Species CARBON_DIOXIDE = Species.of("anchor:co2", "carbon dioxide", "CO2");
    /** Iron. */
    public static final Species IRON = Species.of("anchor:fe", "iron", "Fe");
    /** Copper. */
    public static final Species COPPER = Species.of("anchor:cu", "copper", "Cu");
    /** Aluminium. */
    public static final Species ALUMINIUM = Species.of("anchor:al", "aluminium", "Al");
    /** Gold. */
    public static final Species GOLD = Species.of("anchor:au", "gold", "Au");
    /** Silicon dioxide (quartz, silica). */
    public static final Species SILICA = Species.of("anchor:sio2", "silica", "SiO2");
    /** Potassium feldspar (orthoclase). */
    public static final Species ORTHOCLASE = Species.of("anchor:kalsi3o8", "orthoclase", "KAlSi3O8");
    /** Sodium feldspar (albite). */
    public static final Species ALBITE = Species.of("anchor:naalsi3o8", "albite", "NaAlSi3O8");
    /** Calcium feldspar (anorthite). */
    public static final Species ANORTHITE = Species.of("anchor:caal2si2o8", "anorthite", "CaAl2Si2O8");
    /** Diopside, a calcium-magnesium pyroxene. */
    public static final Species DIOPSIDE = Species.of("anchor:camgsi2o6", "diopside", "CaMgSi2O6");
    /** Forsterite, the magnesium end member of olivine. */
    public static final Species FORSTERITE = Species.of("anchor:mg2sio4", "forsterite", "Mg2SiO4");
    /** Magnetite. */
    public static final Species MAGNETITE = Species.of("anchor:fe3o4", "magnetite", "Fe3O4");
    /** Hematite. */
    public static final Species HEMATITE = Species.of("anchor:fe2o3", "hematite", "Fe2O3");
    /** Sodium oxide. */
    public static final Species SODIUM_OXIDE = Species.of("anchor:na2o", "sodium oxide", "Na2O");
    /** Calcium oxide (lime). */
    public static final Species CALCIUM_OXIDE = Species.of("anchor:cao", "calcium oxide", "CaO");
    /** Magnesium oxide. */
    public static final Species MAGNESIUM_OXIDE = Species.of("anchor:mgo", "magnesium oxide", "MgO");
    /** Cellulose, written per glucose unit. */
    public static final Species CELLULOSE = Species.of("anchor:cellulose", "cellulose", "C6H10O5");
    /** Hemicellulose, written per xylose unit. */
    public static final Species HEMICELLULOSE = Species.of("anchor:hemicellulose", "hemicellulose", "C5H8O4");
    /** Lignin, written per coniferyl alcohol unit. */
    public static final Species LIGNIN = Species.of("anchor:lignin", "lignin", "C10H12O3");

    private static final Map<String, Species> REGISTRY = new LinkedHashMap<>();

    static {
        for (Species s : new Species[] {
                WATER, NITROGEN, OXYGEN, ARGON, CARBON_DIOXIDE, IRON, COPPER, ALUMINIUM, GOLD, SILICA,
                ORTHOCLASE, ALBITE, ANORTHITE, DIOPSIDE, FORSTERITE, MAGNETITE, HEMATITE, SODIUM_OXIDE,
                CALCIUM_OXIDE, MAGNESIUM_OXIDE, CELLULOSE, HEMICELLULOSE, LIGNIN}) {
            register(s);
        }
    }

    private SpeciesCatalog() {
    }

    /**
     * Adds a species to the catalogue.
     *
     * @param species the species
     * @throws IllegalArgumentException if a different species already uses the id
     */
    public static synchronized void register(Species species) {
        Species existing = REGISTRY.putIfAbsent(species.id(), species);
        if (existing != null && !existing.equals(species)) {
            throw new IllegalArgumentException("species id already registered: " + species.id());
        }
    }

    /**
     * Looks up a species by id.
     *
     * @param id the species id
     * @return the species, if registered
     */
    public static synchronized Optional<Species> byId(String id) {
        return Optional.ofNullable(REGISTRY.get(id));
    }

    /**
     * Returns every registered species in registration order.
     *
     * @return an unmodifiable snapshot of the catalogue
     */
    public static synchronized Map<String, Species> all() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(REGISTRY));
    }
}
