package io.github.osir933.anchor.core.units;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A measured or computed value with its physical dimension, stored in coherent SI units.
 *
 * <p>Quantities are used at the edges of the engine (inspection, data files, player input), where
 * mixing up units would be a real risk. Inner simulation loops work on plain SI doubles.
 *
 * @param si the value in the coherent SI unit of {@code dimension}
 * @param dimension the physical dimension
 */
public record Quantity(double si, Dimension dimension) {

    /**
     * Validates the quantity.
     *
     * @param si the SI value
     * @param dimension the dimension
     */
    public Quantity {
        Objects.requireNonNull(dimension, "dimension");
    }

    /**
     * Creates a quantity from a reading in the given unit.
     *
     * @param value the reading
     * @param unit the unit of the reading
     * @return the quantity
     */
    public static Quantity of(double value, Unit unit) {
        return new Quantity(unit.toSi(value), unit.dimension());
    }

    /**
     * Expresses this quantity in another unit of the same dimension.
     *
     * @param unit the target unit
     * @return the reading in {@code unit}
     * @throws IllegalArgumentException if the dimensions differ
     */
    public double in(Unit unit) {
        requireSameDimension(unit.dimension());
        return unit.fromSi(si);
    }

    /**
     * Adds a quantity of the same dimension.
     *
     * @param other the quantity to add
     * @return the sum
     */
    public Quantity plus(Quantity other) {
        requireSameDimension(other.dimension);
        return new Quantity(si + other.si, dimension);
    }

    /**
     * Subtracts a quantity of the same dimension.
     *
     * @param other the quantity to subtract
     * @return the difference
     */
    public Quantity minus(Quantity other) {
        requireSameDimension(other.dimension);
        return new Quantity(si - other.si, dimension);
    }

    /**
     * Multiplies by another quantity.
     *
     * @param other the factor
     * @return the product, with the product dimension
     */
    public Quantity times(Quantity other) {
        return new Quantity(si * other.si, dimension.times(other.dimension));
    }

    /**
     * Divides by another quantity.
     *
     * @param other the divisor
     * @return the quotient, with the quotient dimension
     */
    public Quantity over(Quantity other) {
        return new Quantity(si / other.si, dimension.over(other.dimension));
    }

    /**
     * Scales by a pure number.
     *
     * @param factor the factor
     * @return the scaled quantity
     */
    public Quantity times(double factor) {
        return new Quantity(si * factor, dimension);
    }

    /**
     * Formats this quantity in a unit with a fixed number of significant digits, independent of locale.
     *
     * @param unit the display unit
     * @param significantDigits how many significant digits to show, at least 1
     * @return text such as {@code 1538 °C} or {@code 7.87 kg/m³}
     */
    public String format(Unit unit, int significantDigits) {
        if (significantDigits < 1) {
            throw new IllegalArgumentException("significantDigits must be at least 1");
        }
        double value = in(unit);
        String number;
        if (!Double.isFinite(value)) {
            number = Double.toString(value);
        } else if (value == 0.0) {
            number = "0";
        } else {
            BigDecimal rounded = new BigDecimal(value).round(new MathContext(significantDigits, RoundingMode.HALF_EVEN));
            number = rounded.stripTrailingZeros().toPlainString();
        }
        return unit.symbol().isEmpty() ? number : number + " " + unit.symbol();
    }

    private void requireSameDimension(Dimension other) {
        if (!dimension.equals(other)) {
            throw new IllegalArgumentException("dimension mismatch: " + dimension + " vs " + other);
        }
    }
}
