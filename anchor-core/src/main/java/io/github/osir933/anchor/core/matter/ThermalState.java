package io.github.osir933.anchor.core.matter;

/**
 * The thermal state that follows from a material's specific enthalpy: its temperature and how far it is
 * through a phase transition.
 *
 * <p>During a first-order transition the temperature stays at the transition temperature while the
 * enthalpy rises; {@code transitionFraction} then tells how much of the matter has already changed into
 * the next phase region.
 *
 * @param temperatureK the temperature in kelvin
 * @param region index of the phase region the matter is in (or leaving, during a transition)
 * @param transitionFraction mass fraction already converted to region {@code region + 1}, in [0, 1)
 * @param extrapolated {@code true} if the state lies outside the material's described temperature range
 */
public record ThermalState(double temperatureK, int region, double transitionFraction, boolean extrapolated) {

    /**
     * Tells whether the matter is part-way through a transition.
     *
     * @return {@code true} if both phase regions are present
     */
    public boolean inTransition() {
        return transitionFraction > 0.0;
    }
}
