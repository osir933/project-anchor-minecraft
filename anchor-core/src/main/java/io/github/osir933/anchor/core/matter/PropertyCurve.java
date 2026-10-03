package io.github.osir933.anchor.core.matter;

import java.util.Arrays;
import java.util.Objects;

/**
 * A material property as a function of temperature: piecewise linear between tabulated points and held
 * constant beyond the first and last point.
 *
 * <p>Holding the end values constant keeps every derived quantity continuous. Whether a temperature lies
 * inside the measured range is reported separately by {@link #isTabulatedAt(double)}, so the engine can
 * tell a measured value from an extrapolated one.
 */
public final class PropertyCurve {

    private final double[] temperaturesK;
    private final double[] values;
    private final Source source;

    private PropertyCurve(double[] temperaturesK, double[] values, Source source) {
        this.temperaturesK = temperaturesK;
        this.values = values;
        this.source = Objects.requireNonNull(source, "source");
    }

    /**
     * Creates a property that does not depend on temperature.
     *
     * @param value the value, in coherent SI units
     * @param source where the value comes from
     * @return the curve
     */
    public static PropertyCurve constant(double value, Source source) {
        requireFinite(value);
        return new PropertyCurve(new double[] {Double.NEGATIVE_INFINITY}, new double[] {value}, source);
    }

    /**
     * Creates a tabulated property from (temperature, value) pairs.
     *
     * @param source where the values come from
     * @param pairs alternating temperatures in kelvin (strictly increasing) and values in SI units
     * @return the curve
     * @throws IllegalArgumentException if the pairs are malformed
     */
    public static PropertyCurve table(Source source, double... pairs) {
        if (pairs.length < 2 || pairs.length % 2 != 0) {
            throw new IllegalArgumentException("table needs (temperature, value) pairs");
        }
        int n = pairs.length / 2;
        double[] t = new double[n];
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = pairs[2 * i];
            v[i] = pairs[2 * i + 1];
            requireFinite(t[i]);
            requireFinite(v[i]);
            if (!(t[i] > 0)) {
                throw new IllegalArgumentException("temperatures are in kelvin and must be positive: " + t[i]);
            }
            if (i > 0 && !(t[i] > t[i - 1])) {
                throw new IllegalArgumentException("temperatures must be strictly increasing at index " + i);
            }
        }
        return new PropertyCurve(t, v, source);
    }

    /**
     * Returns the value at a temperature.
     *
     * @param temperatureK the temperature in kelvin
     * @return the interpolated value, or the nearest end value outside the table
     */
    public double at(double temperatureK) {
        int n = temperaturesK.length;
        if (n == 1 || temperatureK <= temperaturesK[0]) {
            return values[0];
        }
        if (temperatureK >= temperaturesK[n - 1]) {
            return values[n - 1];
        }
        int i = segmentIndex(temperatureK);
        return interpolate(i, temperatureK);
    }

    /**
     * Returns the largest value the property takes between two temperatures, both included.
     *
     * @param fromK one limit in kelvin
     * @param toK the other limit in kelvin, above or below the first
     * @return the largest value
     */
    public double max(double fromK, double toK) {
        double lo = Math.min(fromK, toK);
        double hi = Math.max(fromK, toK);
        double largest = Math.max(at(lo), at(hi));
        for (int i = 0; i < temperaturesK.length; i++) {
            if (temperaturesK[i] > lo && temperaturesK[i] < hi) {
                largest = Math.max(largest, values[i]);
            }
        }
        return largest;
    }

    /**
     * Returns the smallest value the property takes between two temperatures, both included.
     *
     * @param fromK one limit in kelvin
     * @param toK the other limit in kelvin, above or below the first
     * @return the smallest value
     */
    public double min(double fromK, double toK) {
        double lo = Math.min(fromK, toK);
        double hi = Math.max(fromK, toK);
        double smallest = Math.min(at(lo), at(hi));
        for (int i = 0; i < temperaturesK.length; i++) {
            if (temperaturesK[i] > lo && temperaturesK[i] < hi) {
                smallest = Math.min(smallest, values[i]);
            }
        }
        return smallest;
    }

    /**
     * Integrates the property over temperature exactly, for example specific heat to specific enthalpy.
     *
     * @param fromK the lower limit in kelvin
     * @param toK the upper limit in kelvin
     * @return the integral; negative when {@code toK < fromK}
     */
    public double integrate(double fromK, double toK) {
        if (fromK == toK) {
            return 0.0;
        }
        if (fromK > toK) {
            return -integrate(toK, fromK);
        }
        int n = temperaturesK.length;
        if (n == 1) {
            return values[0] * (toK - fromK);
        }
        double total = 0.0;
        double a = fromK;
        if (a < temperaturesK[0]) {
            double hi = Math.min(toK, temperaturesK[0]);
            total += values[0] * (hi - a);
            a = hi;
        }
        for (int i = 0; i < n - 1 && a < toK; i++) {
            double t1 = temperaturesK[i + 1];
            if (a >= t1) {
                continue;
            }
            double lo = Math.max(a, temperaturesK[i]);
            double hi = Math.min(toK, t1);
            total += (interpolate(i, lo) + interpolate(i, hi)) * 0.5 * (hi - lo);
            a = hi;
        }
        if (a < toK) {
            total += values[n - 1] * (toK - a);
        }
        return total;
    }

    /**
     * Tells whether a temperature lies within the tabulated range, where values are measured rather than
     * held constant.
     *
     * @param temperatureK the temperature in kelvin
     * @return {@code true} for constants, and for tables between their first and last temperature
     */
    public boolean isTabulatedAt(double temperatureK) {
        int n = temperaturesK.length;
        return n == 1 || (temperatureK >= temperaturesK[0] && temperatureK <= temperaturesK[n - 1]);
    }

    /**
     * Returns the tabulated temperatures strictly between two limits, for building exact piecewise segments.
     *
     * @param fromK the exclusive lower limit
     * @param toK the exclusive upper limit
     * @return the breakpoints in increasing order
     */
    public double[] breakpointsBetween(double fromK, double toK) {
        return Arrays.stream(temperaturesK).filter(t -> t > fromK && t < toK).toArray();
    }

    /**
     * Returns where the values come from.
     *
     * @return the source
     */
    public Source source() {
        return source;
    }

    /**
     * Tells whether this property is a constant.
     *
     * @return {@code true} if the value does not depend on temperature
     */
    public boolean isConstant() {
        return temperaturesK.length == 1;
    }

    private int segmentIndex(double temperatureK) {
        int lo = 0;
        int hi = temperaturesK.length - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (temperaturesK[mid] <= temperatureK) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private double interpolate(int segment, double temperatureK) {
        double t0 = temperaturesK[segment];
        double t1 = temperaturesK[segment + 1];
        double v0 = values[segment];
        double v1 = values[segment + 1];
        return v0 + (v1 - v0) * ((temperatureK - t0) / (t1 - t0));
    }

    private static void requireFinite(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("value must be finite: " + value);
        }
    }
}
