package io.github.osir933.anchor.core.units;

/**
 * A physical dimension, written as integer exponents of the seven SI base quantities.
 *
 * <p>For example, energy is mass·length²·time⁻², so {@code Dimension.ENERGY} has
 * {@code mass = 1}, {@code length = 2} and {@code time = -2}.
 *
 * @param mass exponent of mass (kilogram)
 * @param length exponent of length (metre)
 * @param time exponent of time (second)
 * @param temperature exponent of thermodynamic temperature (kelvin)
 * @param amount exponent of amount of substance (mole)
 * @param current exponent of electric current (ampere)
 * @param luminosity exponent of luminous intensity (candela)
 */
public record Dimension(int mass, int length, int time, int temperature, int amount, int current, int luminosity) {

    /** A dimensionless quantity, such as a ratio or a mass fraction. */
    public static final Dimension NONE = new Dimension(0, 0, 0, 0, 0, 0, 0);
    /** Mass. */
    public static final Dimension MASS = new Dimension(1, 0, 0, 0, 0, 0, 0);
    /** Length. */
    public static final Dimension LENGTH = new Dimension(0, 1, 0, 0, 0, 0, 0);
    /** Time. */
    public static final Dimension TIME = new Dimension(0, 0, 1, 0, 0, 0, 0);
    /** Thermodynamic temperature. */
    public static final Dimension TEMPERATURE = new Dimension(0, 0, 0, 1, 0, 0, 0);
    /** Amount of substance. */
    public static final Dimension AMOUNT = new Dimension(0, 0, 0, 0, 1, 0, 0);
    /** Electric current. */
    public static final Dimension CURRENT = new Dimension(0, 0, 0, 0, 0, 1, 0);
    /** Luminous intensity. */
    public static final Dimension LUMINOSITY = new Dimension(0, 0, 0, 0, 0, 0, 1);

    /** Area. */
    public static final Dimension AREA = LENGTH.pow(2);
    /** Volume. */
    public static final Dimension VOLUME = LENGTH.pow(3);
    /** Velocity. */
    public static final Dimension VELOCITY = LENGTH.over(TIME);
    /** Acceleration. */
    public static final Dimension ACCELERATION = VELOCITY.over(TIME);
    /** Force. */
    public static final Dimension FORCE = MASS.times(ACCELERATION);
    /** Pressure and stress. */
    public static final Dimension PRESSURE = FORCE.over(AREA);
    /** Energy, work and heat. */
    public static final Dimension ENERGY = FORCE.times(LENGTH);
    /** Power. */
    public static final Dimension POWER = ENERGY.over(TIME);
    /** Density. */
    public static final Dimension DENSITY = MASS.over(VOLUME);
    /** Specific heat capacity and specific entropy. */
    public static final Dimension SPECIFIC_HEAT_CAPACITY = ENERGY.over(MASS).over(TEMPERATURE);
    /** Specific energy, such as latent heat or specific enthalpy. */
    public static final Dimension SPECIFIC_ENERGY = ENERGY.over(MASS);
    /** Thermal conductivity. */
    public static final Dimension THERMAL_CONDUCTIVITY = POWER.over(LENGTH).over(TEMPERATURE);
    /** Heat flux density. */
    public static final Dimension HEAT_FLUX = POWER.over(AREA);
    /** Molar mass. */
    public static final Dimension MOLAR_MASS = MASS.over(AMOUNT);
    /** Electric charge. */
    public static final Dimension CHARGE = CURRENT.times(TIME);
    /** Electric potential. */
    public static final Dimension VOLTAGE = POWER.over(CURRENT);
    /** Electric resistance. */
    public static final Dimension RESISTANCE = VOLTAGE.over(CURRENT);

    /**
     * Returns the dimension of a product of quantities with this dimension and {@code other}.
     *
     * @param other the other factor's dimension
     * @return the product dimension
     */
    public Dimension times(Dimension other) {
        return new Dimension(
                mass + other.mass,
                length + other.length,
                time + other.time,
                temperature + other.temperature,
                amount + other.amount,
                current + other.current,
                luminosity + other.luminosity);
    }

    /**
     * Returns the dimension of a quotient of a quantity with this dimension by one with {@code other}.
     *
     * @param other the divisor's dimension
     * @return the quotient dimension
     */
    public Dimension over(Dimension other) {
        return times(other.pow(-1));
    }

    /**
     * Returns this dimension raised to an integer power.
     *
     * @param exponent the power
     * @return the resulting dimension
     */
    public Dimension pow(int exponent) {
        return new Dimension(
                mass * exponent,
                length * exponent,
                time * exponent,
                temperature * exponent,
                amount * exponent,
                current * exponent,
                luminosity * exponent);
    }

    /**
     * Tells whether this dimension has no base-quantity exponents.
     *
     * @return {@code true} for ratios, fractions and other pure numbers
     */
    public boolean isDimensionless() {
        return equals(NONE);
    }

    /**
     * Formats this dimension with SI base-unit symbols, for example {@code kg·m²·s⁻²}.
     *
     * @return the formatted dimension, or {@code 1} when dimensionless
     */
    @Override
    public String toString() {
        StringBuilder out = new StringBuilder();
        append(out, "kg", mass);
        append(out, "m", length);
        append(out, "s", time);
        append(out, "K", temperature);
        append(out, "mol", amount);
        append(out, "A", current);
        append(out, "cd", luminosity);
        return out.isEmpty() ? "1" : out.toString();
    }

    private static void append(StringBuilder out, String symbol, int exponent) {
        if (exponent == 0) {
            return;
        }
        if (!out.isEmpty()) {
            out.append('·');
        }
        out.append(symbol);
        if (exponent != 1) {
            out.append(superscript(exponent));
        }
    }

    private static String superscript(int value) {
        String digits = Integer.toString(value);
        StringBuilder out = new StringBuilder(digits.length());
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            out.append(switch (c) {
                case '-' -> '⁻';
                case '0' -> '⁰';
                case '1' -> '¹';
                case '2' -> '²';
                case '3' -> '³';
                case '4' -> '⁴';
                case '5' -> '⁵';
                case '6' -> '⁶';
                case '7' -> '⁷';
                case '8' -> '⁸';
                case '9' -> '⁹';
                default -> c;
            });
        }
        return out.toString();
    }
}
