package io.github.osir933.anchor.core.world;

import io.github.osir933.anchor.core.math.CompensatedSum;
import io.github.osir933.anchor.core.matter.Element;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Checks that the world gains or loses mass, energy and chemical elements only through exchanges someone
 * declared: a player placing a block, a section loading, heat radiated to the sky.
 *
 * <p>The ledger holds a baseline and the declared exchanges since then. An audit compares the world's
 * actual totals with the expected ones, allowing only for floating-point rounding, judged against the
 * size of the quantities involved. Physics models never declare their internal transfers; if a model
 * leaks, the audit catches it.
 */
public final class ConservationLedger {

    /** Default relative tolerance of an audit; rounding in one tick is many orders of magnitude smaller. */
    public static final double DEFAULT_RELATIVE_TOLERANCE = 1e-12;

    private Totals baseline = Totals.ZERO;
    private final CompensatedSum mass = new CompensatedSum();
    private final CompensatedSum enthalpy = new CompensatedSum();
    private final Map<Element, CompensatedSum> elements = new EnumMap<>(Element.class);
    private double massScale;
    private double enthalpyScale;
    private long exchangeCount;

    /** Creates a ledger with a zero baseline. */
    public ConservationLedger() {
    }

    /**
     * Starts a new accounting period from known totals.
     *
     * @param totals the world's current totals
     */
    public void rebase(Totals totals) {
        baseline = totals;
        mass.reset();
        enthalpy.reset();
        elements.clear();
        massScale = 0;
        enthalpyScale = 0;
        exchangeCount = 0;
    }

    /**
     * Declares matter or energy entering (positive) or leaving (negative) the world.
     *
     * @param change the totals that crossed the boundary
     */
    public void recordExchange(Totals change) {
        mass.add(change.mass());
        enthalpy.add(change.enthalpy());
        for (Map.Entry<Element, Double> e : change.elementMasses().entrySet()) {
            elements.computeIfAbsent(e.getKey(), k -> new CompensatedSum()).add(e.getValue());
        }
        massScale += Math.abs(change.mass());
        enthalpyScale += change.absoluteEnthalpy();
        exchangeCount++;
    }

    /**
     * Returns the number of exchanges declared since the last rebase.
     *
     * @return the count
     */
    public long exchangeCount() {
        return exchangeCount;
    }

    /**
     * Returns the baseline of the current period.
     *
     * @return the totals at the last rebase
     */
    public Totals baseline() {
        return baseline;
    }

    /**
     * Audits the world's totals with the default tolerance.
     *
     * @param actual the world's current totals
     * @return the audit
     */
    public Audit audit(Totals actual) {
        return audit(actual, DEFAULT_RELATIVE_TOLERANCE);
    }

    /**
     * Audits the world's totals.
     *
     * @param actual the world's current totals
     * @param relativeTolerance the accepted discrepancy as a fraction of the quantities involved
     * @return the audit
     */
    public Audit audit(Totals actual, double relativeTolerance) {
        List<Discrepancy> found = new ArrayList<>();
        double massTolerance = tolerance(relativeTolerance, Math.max(baseline.mass(), actual.mass()) + massScale);
        check(found, "mass", "kg", actual.mass(), baseline.mass() + mass.value(), massTolerance);
        double enthalpyTolerance = tolerance(relativeTolerance,
                Math.max(baseline.absoluteEnthalpy(), actual.absoluteEnthalpy()) + enthalpyScale);
        check(found, "enthalpy", "J", actual.enthalpy(), baseline.enthalpy() + enthalpy.value(), enthalpyTolerance);
        for (Element e : Element.values()) {
            CompensatedSum exchanged = elements.get(e);
            double expected = baseline.elementMass(e) + (exchanged == null ? 0 : exchanged.value());
            double observed = actual.elementMass(e);
            if (expected != 0 || observed != 0) {
                check(found, e.name(), "kg", observed, expected, massTolerance);
            }
        }
        return new Audit(found);
    }

    private static double tolerance(double relative, double scale) {
        return relative * scale + Double.MIN_NORMAL;
    }

    private static void check(List<Discrepancy> found, String quantity, String unit, double actual, double expected,
            double tolerance) {
        double residual = actual - expected;
        if (!(Math.abs(residual) <= tolerance)) {
            found.add(new Discrepancy(quantity, unit, expected, actual, tolerance));
        }
    }

    /**
     * One quantity that did not balance.
     *
     * @param quantity what was conserved: mass, enthalpy, or an element symbol
     * @param unit the unit of the values
     * @param expected the baseline plus declared exchanges
     * @param actual the world's total
     * @param tolerance the largest residual accepted as rounding
     */
    public record Discrepancy(String quantity, String unit, double expected, double actual, double tolerance) {

        /**
         * Returns the unexplained gain (positive) or loss (negative).
         *
         * @return actual minus expected
         */
        public double residual() {
            return actual - expected;
        }

        @Override
        public String toString() {
            return quantity + ": expected " + expected + " " + unit + ", found " + actual + " " + unit
                    + " (residual " + residual() + ", tolerance " + tolerance + ")";
        }
    }

    /**
     * The result of an audit.
     *
     * @param discrepancies every quantity that did not balance, empty if all did
     */
    public record Audit(List<Discrepancy> discrepancies) {

        /**
         * Copies the list.
         *
         * @param discrepancies the discrepancies
         */
        public Audit {
            discrepancies = Collections.unmodifiableList(new ArrayList<>(discrepancies));
        }

        /**
         * Tells whether everything balanced.
         *
         * @return {@code true} if there are no discrepancies
         */
        public boolean balanced() {
            return discrepancies.isEmpty();
        }
    }
}
