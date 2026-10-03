package io.github.osir933.anchor.core.matter;

import io.github.osir933.anchor.core.units.PhysicalConstants;
import java.util.List;

/**
 * The specific enthalpy of a material as a function of temperature, including latent heat, and its exact
 * inverse.
 *
 * <p>Anchor stores enthalpy, not temperature, as a cell's thermal state. Enthalpy is conserved by heat
 * flow, and melting or boiling then happens by itself: the temperature stops at the transition while the
 * latent heat is absorbed. Specific enthalpy is zero at 25 °C in the phase stable there (or at the lowest
 * described temperature if 25 °C is outside the material's range).
 *
 * <p>Within each phase region the specific heat is piecewise linear in temperature, so enthalpy is
 * piecewise quadratic and both directions are computed in closed form, without iteration.
 */
public final class EnthalpyCurve {

    private final List<PhaseRegion> regions;
    private final List<PhaseTransition> transitions;
    private final double[][] breakpointsK;
    private final double[][] enthalpyAt;
    private final double[][] specificHeatAt;
    private final double[] latentHeat;

    /**
     * Builds the curve for contiguous phase regions.
     *
     * @param regions the phase regions in order of increasing temperature
     * @param transitions the transitions between consecutive regions; one fewer than the regions
     * @throws IllegalArgumentException if the regions are not contiguous or do not match the transitions
     */
    public EnthalpyCurve(List<PhaseRegion> regions, List<PhaseTransition> transitions) {
        if (regions.isEmpty()) {
            throw new IllegalArgumentException("at least one phase region is required");
        }
        if (transitions.size() != regions.size() - 1) {
            throw new IllegalArgumentException("expected " + (regions.size() - 1) + " transitions, got "
                    + transitions.size());
        }
        for (int i = 0; i < transitions.size(); i++) {
            double t = transitions.get(i).temperatureK();
            if (regions.get(i).toK() != t || regions.get(i + 1).fromK() != t) {
                throw new IllegalArgumentException("regions " + i + " and " + (i + 1)
                        + " must meet at the transition temperature " + t + " K");
            }
        }
        this.regions = List.copyOf(regions);
        this.transitions = List.copyOf(transitions);
        int n = regions.size();
        this.breakpointsK = new double[n][];
        this.enthalpyAt = new double[n][];
        this.specificHeatAt = new double[n][];
        this.latentHeat = new double[n];

        double h = 0.0;
        for (int i = 0; i < n; i++) {
            PhaseRegion region = regions.get(i);
            double[] inner = region.specificHeat().breakpointsBetween(region.fromK(), region.toK());
            double[] points = new double[inner.length + 2];
            points[0] = region.fromK();
            System.arraycopy(inner, 0, points, 1, inner.length);
            points[points.length - 1] = region.toK();
            double[] hs = new double[points.length];
            double[] cps = new double[points.length];
            for (int k = 0; k < points.length; k++) {
                cps[k] = region.specificHeat().at(points[k]);
                if (!(cps[k] > 0)) {
                    throw new IllegalArgumentException("specific heat must be positive in region " + i);
                }
                if (k > 0) {
                    h += (cps[k - 1] + cps[k]) * 0.5 * (points[k] - points[k - 1]);
                }
                hs[k] = h;
            }
            breakpointsK[i] = points;
            enthalpyAt[i] = hs;
            specificHeatAt[i] = cps;
            if (i < n - 1) {
                latentHeat[i] = transitions.get(i).latentHeat();
                h += latentHeat[i];
            }
        }

        double referenceK = PhysicalConstants.STANDARD_TEMPERATURE;
        double offset = rawEnthalpy(Math.max(referenceK, regions.get(0).fromK()));
        for (double[] hs : enthalpyAt) {
            for (int k = 0; k < hs.length; k++) {
                hs[k] -= offset;
            }
        }
    }

    /**
     * Returns the specific enthalpy of matter at a temperature, in the phase stable there. Exactly at a
     * transition temperature the lower-temperature phase is assumed, before any latent heat is absorbed.
     *
     * @param temperatureK the temperature in kelvin
     * @return the specific enthalpy in J/kg
     */
    public double specificEnthalpy(double temperatureK) {
        return rawEnthalpy(temperatureK);
    }

    /**
     * Returns the specific enthalpy of a given thermal state.
     *
     * @param state the state, as returned by {@link #stateFor(double)}
     * @return the specific enthalpy in J/kg
     */
    public double specificEnthalpy(ThermalState state) {
        if (state.inTransition()) {
            int i = state.region();
            double[] hs = enthalpyAt[i];
            return hs[hs.length - 1] + state.transitionFraction() * latentHeat[i];
        }
        return rawEnthalpy(state.temperatureK());
    }

    /**
     * Finds the thermal state for a specific enthalpy.
     *
     * @param specificEnthalpy the specific enthalpy in J/kg
     * @return the temperature, phase region and transition progress
     */
    public ThermalState stateFor(double specificEnthalpy) {
        int n = regions.size();
        double[] first = enthalpyAt[0];
        if (specificEnthalpy < first[0]) {
            double cp = specificHeatAt[0][0];
            double t = breakpointsK[0][0] - (first[0] - specificEnthalpy) / cp;
            return new ThermalState(t, 0, 0.0, true);
        }
        for (int i = 0; i < n; i++) {
            double[] hs = enthalpyAt[i];
            double end = hs[hs.length - 1];
            if (specificEnthalpy <= end) {
                return new ThermalState(solveInRegion(i, specificEnthalpy), i, 0.0, false);
            }
            if (i < n - 1) {
                double nextStart = enthalpyAt[i + 1][0];
                if (specificEnthalpy < nextStart) {
                    double fraction = (specificEnthalpy - end) / latentHeat[i];
                    return new ThermalState(transitions.get(i).temperatureK(), i, fraction, false);
                }
            }
        }
        int last = n - 1;
        double[] hs = enthalpyAt[last];
        double[] cps = specificHeatAt[last];
        double t = breakpointsK[last][hs.length - 1] + (specificEnthalpy - hs[hs.length - 1]) / cps[cps.length - 1];
        return new ThermalState(t, last, 0.0, true);
    }

    /**
     * Returns the temperature for a specific enthalpy.
     *
     * @param specificEnthalpy the specific enthalpy in J/kg
     * @return the temperature in kelvin
     */
    public double temperatureFor(double specificEnthalpy) {
        // The same search as stateFor, without building the state; the two must agree exactly.
        int n = regions.size();
        double[] first = enthalpyAt[0];
        if (specificEnthalpy < first[0]) {
            return breakpointsK[0][0] - (first[0] - specificEnthalpy) / specificHeatAt[0][0];
        }
        for (int i = 0; i < n; i++) {
            double[] hs = enthalpyAt[i];
            if (specificEnthalpy <= hs[hs.length - 1]) {
                return solveInRegion(i, specificEnthalpy);
            }
            if (i < n - 1 && specificEnthalpy < enthalpyAt[i + 1][0]) {
                return transitions.get(i).temperatureK();
            }
        }
        int last = n - 1;
        double[] hs = enthalpyAt[last];
        double[] cps = specificHeatAt[last];
        return breakpointsK[last][hs.length - 1] + (specificEnthalpy - hs[hs.length - 1]) / cps[cps.length - 1];
    }

    /**
     * Returns the phase regions.
     *
     * @return the regions in order of increasing temperature
     */
    public List<PhaseRegion> regions() {
        return regions;
    }

    /**
     * Returns the transitions.
     *
     * @return the transitions between consecutive regions
     */
    public List<PhaseTransition> transitions() {
        return transitions;
    }

    /**
     * Returns the specific enthalpy at the cold end of a phase region, after the latent heat of the
     * transition into it.
     *
     * @param region the region index
     * @return the specific enthalpy in J/kg
     */
    public double regionStartEnthalpy(int region) {
        return enthalpyAt[region][0];
    }

    /**
     * Returns the specific enthalpy at the hot end of a phase region, before the latent heat of the
     * transition out of it.
     *
     * @param region the region index
     * @return the specific enthalpy in J/kg
     */
    public double regionEndEnthalpy(int region) {
        double[] hs = enthalpyAt[region];
        return hs[hs.length - 1];
    }

    /**
     * Returns the lowest temperature the material description covers.
     *
     * @return the temperature in kelvin
     */
    public double minTemperatureK() {
        return regions.get(0).fromK();
    }

    /**
     * Returns the highest temperature the material description covers.
     *
     * @return the temperature in kelvin
     */
    public double maxTemperatureK() {
        return regions.get(regions.size() - 1).toK();
    }

    private double rawEnthalpy(double temperatureK) {
        int n = regions.size();
        double[] p0 = breakpointsK[0];
        if (temperatureK < p0[0]) {
            return enthalpyAt[0][0] - specificHeatAt[0][0] * (p0[0] - temperatureK);
        }
        for (int i = 0; i < n; i++) {
            double[] points = breakpointsK[i];
            if (temperatureK <= points[points.length - 1]) {
                int k = segment(points, temperatureK);
                double t0 = points[k];
                double t1 = points[k + 1];
                double c0 = specificHeatAt[i][k];
                double c1 = specificHeatAt[i][k + 1];
                double cpT = c0 + (c1 - c0) * ((temperatureK - t0) / (t1 - t0));
                return enthalpyAt[i][k] + (c0 + cpT) * 0.5 * (temperatureK - t0);
            }
        }
        int last = n - 1;
        double[] points = breakpointsK[last];
        double[] hs = enthalpyAt[last];
        double[] cps = specificHeatAt[last];
        return hs[hs.length - 1] + cps[cps.length - 1] * (temperatureK - points[points.length - 1]);
    }

    private double solveInRegion(int region, double specificEnthalpy) {
        double[] points = breakpointsK[region];
        double[] hs = enthalpyAt[region];
        double[] cps = specificHeatAt[region];
        int k = 0;
        while (k < hs.length - 2 && specificEnthalpy > hs[k + 1]) {
            k++;
        }
        double t0 = points[k];
        double t1 = points[k + 1];
        double c0 = cps[k];
        double slope = (cps[k + 1] - c0) / (t1 - t0);
        double dh = Math.max(0.0, specificEnthalpy - hs[k]);
        // Solve c0*u + slope/2*u^2 = dh for u >= 0 in the cancellation-free form.
        double discriminant = Math.max(0.0, c0 * c0 + 2.0 * slope * dh);
        double u = 2.0 * dh / (c0 + Math.sqrt(discriminant));
        return Math.min(t1, t0 + u);
    }

    private static int segment(double[] points, double temperatureK) {
        int lo = 0;
        int hi = points.length - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (points[mid] <= temperatureK) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return lo;
    }
}
