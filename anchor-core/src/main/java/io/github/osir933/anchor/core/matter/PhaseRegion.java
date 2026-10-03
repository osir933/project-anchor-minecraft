package io.github.osir933.anchor.core.matter;

import java.util.Objects;

/**
 * A temperature range in which a material keeps one phase and crystal structure, with that phase's
 * thermal properties. Regions are given at standard atmospheric pressure.
 *
 * @param phase the state of aggregation
 * @param structure a name for the structure, such as {@code ice Ih} or {@code gamma iron (fcc)}
 * @param fromK the lower end of the range in kelvin
 * @param toK the upper end of the range in kelvin
 * @param specificHeat specific heat capacity at constant pressure, J/(kg·K)
 * @param conductivity thermal conductivity, W/(m·K)
 * @param density density, kg/m³; how it changes with temperature is what makes a warm liquid rise
 * @param emissivity total hemispherical emissivity of a typical surface, dimensionless
 * @param viscosity dynamic viscosity of a liquid, Pa·s, or {@code null} for a solid, a gas, or a liquid without
 *     data; a liquid without it conducts heat but does not convect
 */
public record PhaseRegion(
        Phase phase,
        String structure,
        double fromK,
        double toK,
        PropertyCurve specificHeat,
        PropertyCurve conductivity,
        PropertyCurve density,
        PropertyCurve emissivity,
        PropertyCurve viscosity) {

    /**
     * Validates the region.
     *
     * @param phase the phase
     * @param structure the structure name
     * @param fromK the lower temperature
     * @param toK the upper temperature
     * @param specificHeat the specific heat curve
     * @param conductivity the conductivity curve
     * @param density the density curve
     * @param emissivity the emissivity curve
     * @param viscosity the viscosity curve, or {@code null}
     */
    public PhaseRegion {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(structure, "structure");
        Objects.requireNonNull(specificHeat, "specificHeat");
        Objects.requireNonNull(conductivity, "conductivity");
        Objects.requireNonNull(density, "density");
        Objects.requireNonNull(emissivity, "emissivity");
        if (!(fromK > 0) || !(toK > fromK) || !Double.isFinite(toK)) {
            throw new IllegalArgumentException("invalid temperature range [" + fromK + ", " + toK + "]");
        }
        if (viscosity != null && phase != Phase.LIQUID) {
            throw new IllegalArgumentException("only liquids take a viscosity for now: " + structure);
        }
    }

    /**
     * Creates a region without a viscosity, as for a solid or a gas.
     *
     * @param phase the phase
     * @param structure the structure name
     * @param fromK the lower temperature
     * @param toK the upper temperature
     * @param specificHeat the specific heat curve
     * @param conductivity the conductivity curve
     * @param density the density curve
     * @param emissivity the emissivity curve
     */
    public PhaseRegion(Phase phase, String structure, double fromK, double toK, PropertyCurve specificHeat,
            PropertyCurve conductivity, PropertyCurve density, PropertyCurve emissivity) {
        this(phase, structure, fromK, toK, specificHeat, conductivity, density, emissivity, null);
    }

    /**
     * Tells whether a temperature lies in this region's range.
     *
     * @param temperatureK the temperature in kelvin
     * @return {@code true} if {@code fromK <= temperatureK <= toK}
     */
    public boolean contains(double temperatureK) {
        return temperatureK >= fromK && temperatureK <= toK;
    }
}
