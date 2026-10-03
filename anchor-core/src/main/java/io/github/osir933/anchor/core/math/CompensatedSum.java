package io.github.osir933.anchor.core.math;

/**
 * A running sum with Neumaier compensation, which keeps the rounding error of long sums near one ulp.
 *
 * <p>Used wherever totals are compared for conservation, so that numerical noise is not mistaken for a
 * physical gain or loss. The result depends on the order of additions, so callers add in a fixed order.
 */
public final class CompensatedSum {

    private double sum;
    private double compensation;

    /** Creates an empty sum. */
    public CompensatedSum() {
    }

    /**
     * Adds a value.
     *
     * @param value the value to add
     * @return this sum, for chaining
     */
    public CompensatedSum add(double value) {
        double t = sum + value;
        if (Math.abs(sum) >= Math.abs(value)) {
            compensation += (sum - t) + value;
        } else {
            compensation += (value - t) + sum;
        }
        sum = t;
        return this;
    }

    /**
     * Returns the compensated total.
     *
     * @return the sum of all added values
     */
    public double value() {
        return sum + compensation;
    }

    /**
     * Empties the sum.
     *
     * @return this sum, for chaining
     */
    public CompensatedSum reset() {
        sum = 0.0;
        compensation = 0.0;
        return this;
    }

    /**
     * Sums an array in index order.
     *
     * @param values the values
     * @return the compensated total
     */
    public static double of(double... values) {
        CompensatedSum s = new CompensatedSum();
        for (double v : values) {
            s.add(v);
        }
        return s.value();
    }
}
