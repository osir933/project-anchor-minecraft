package io.github.osir933.anchor.core.world;

/**
 * How uniform a group of cells must be before they may merge into one.
 *
 * <p>Coarsening throws away detail, so it is only allowed where there is no detail worth keeping: the same
 * material and owner throughout, the same phase, and temperatures, melt fractions and densities that agree
 * within these limits. Anything that refinement produced, such as a crack, a melt pocket or a boundary
 * between materials, shows up as a difference and keeps the cells apart. That is what guarantees that
 * coarsening never undoes an outcome.
 *
 * @param maxTemperatureSpreadK the largest difference in temperature, in kelvin
 * @param maxTransitionFractionSpread the largest difference in progress through a phase transition
 * @param maxRelativeDensitySpread the largest difference in mass per volume, relative to the mean
 */
public record CoarseningRule(double maxTemperatureSpreadK, double maxTransitionFractionSpread,
        double maxRelativeDensitySpread) {

    /** One millikelvin, one part per million of a transition, one part per billion of density. */
    public static final CoarseningRule DEFAULT = new CoarseningRule(1e-3, 1e-6, 1e-9);

    /**
     * Validates the limits.
     *
     * @param maxTemperatureSpreadK the temperature limit
     * @param maxTransitionFractionSpread the transition limit
     * @param maxRelativeDensitySpread the density limit
     */
    public CoarseningRule {
        if (!(maxTemperatureSpreadK >= 0) || !(maxTransitionFractionSpread >= 0)
                || !(maxRelativeDensitySpread >= 0)) {
            throw new IllegalArgumentException("coarsening limits must be non-negative");
        }
    }
}
