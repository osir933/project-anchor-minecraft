package io.github.osir933.anchor.core.matter;

import java.util.Objects;

/**
 * A first-order transition between two neighbouring phase regions at standard atmospheric pressure, such
 * as melting, boiling or a change of crystal structure.
 *
 * @param name a short name, such as {@code melting}
 * @param temperatureK the transition temperature in kelvin
 * @param latentHeat the specific latent heat absorbed on heating through the transition, J/kg
 * @param source where the values come from
 */
public record PhaseTransition(String name, double temperatureK, double latentHeat, Source source) {

    /**
     * Validates the transition.
     *
     * @param name the name
     * @param temperatureK the temperature
     * @param latentHeat the latent heat
     * @param source the source
     */
    public PhaseTransition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(source, "source");
        if (!(temperatureK > 0) || !Double.isFinite(temperatureK)) {
            throw new IllegalArgumentException("transition temperature must be positive: " + temperatureK);
        }
        if (!(latentHeat >= 0) || !Double.isFinite(latentHeat)) {
            throw new IllegalArgumentException("latent heat must be non-negative: " + latentHeat);
        }
    }
}
