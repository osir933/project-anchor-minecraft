package io.github.osir933.anchor.core.units;

import java.util.Objects;

/**
 * A unit of measurement, defined by how it converts to the coherent SI unit of its dimension.
 *
 * <p>The SI value of a reading {@code v} in this unit is {@code v * scale + offset}. Only temperature
 * scales such as degrees Celsius use a non-zero offset.
 *
 * @param symbol the symbol shown to players, such as {@code °C} or {@code kJ/(kg·K)}
 * @param name the full name, such as {@code degree Celsius}
 * @param dimension the physical dimension this unit measures
 * @param scale the SI value of one unit, ignoring any offset
 * @param offset the SI value of a zero reading
 */
public record Unit(String symbol, String name, Dimension dimension, double scale, double offset) {

    /**
     * Validates the definition.
     *
     * @param symbol the unit symbol
     * @param name the unit name
     * @param dimension the dimension
     * @param scale the conversion scale
     * @param offset the conversion offset
     */
    public Unit {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(dimension, "dimension");
        if (!(scale > 0) || !Double.isFinite(scale)) {
            throw new IllegalArgumentException("scale must be positive and finite: " + scale);
        }
        if (!Double.isFinite(offset)) {
            throw new IllegalArgumentException("offset must be finite: " + offset);
        }
    }

    /**
     * Creates a unit without offset.
     *
     * @param symbol the unit symbol
     * @param name the unit name
     * @param dimension the dimension
     * @param scale the SI value of one unit
     * @return the unit
     */
    public static Unit of(String symbol, String name, Dimension dimension, double scale) {
        return new Unit(symbol, name, dimension, scale, 0.0);
    }

    /**
     * Converts a reading in this unit to the coherent SI unit.
     *
     * @param value the reading in this unit
     * @return the SI value
     */
    public double toSi(double value) {
        return value * scale + offset;
    }

    /**
     * Converts an SI value to a reading in this unit.
     *
     * @param siValue the value in the coherent SI unit
     * @return the reading in this unit
     */
    public double fromSi(double siValue) {
        return (siValue - offset) / scale;
    }

    /**
     * Tells whether this unit converts with an offset, which makes it unsuitable for differences.
     *
     * @return {@code true} for offset scales such as degrees Celsius
     */
    public boolean hasOffset() {
        return offset != 0.0;
    }
}
