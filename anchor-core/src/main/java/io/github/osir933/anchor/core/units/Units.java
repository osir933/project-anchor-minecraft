package io.github.osir933.anchor.core.units;

/**
 * The units Anchor uses for input and display. Simulation code always works in coherent SI units;
 * these definitions only convert at the edges, for example in the inspector or in data files.
 */
public final class Units {

    // Base and length units.
    /** Kilogram. */
    public static final Unit KILOGRAM = Unit.of("kg", "kilogram", Dimension.MASS, 1.0);
    /** Gram. */
    public static final Unit GRAM = Unit.of("g", "gram", Dimension.MASS, 1e-3);
    /** Tonne (metric ton). */
    public static final Unit TONNE = Unit.of("t", "tonne", Dimension.MASS, 1e3);
    /** Metre, the edge length of one Minecraft block. */
    public static final Unit METRE = Unit.of("m", "metre", Dimension.LENGTH, 1.0);
    /** Centimetre. */
    public static final Unit CENTIMETRE = Unit.of("cm", "centimetre", Dimension.LENGTH, 1e-2);
    /** Millimetre. */
    public static final Unit MILLIMETRE = Unit.of("mm", "millimetre", Dimension.LENGTH, 1e-3);
    /** Second. */
    public static final Unit SECOND = Unit.of("s", "second", Dimension.TIME, 1.0);
    /** Minute. */
    public static final Unit MINUTE = Unit.of("min", "minute", Dimension.TIME, 60.0);
    /** Hour. */
    public static final Unit HOUR = Unit.of("h", "hour", Dimension.TIME, 3600.0);
    /** Mole. */
    public static final Unit MOLE = Unit.of("mol", "mole", Dimension.AMOUNT, 1.0);
    /** Ampere. */
    public static final Unit AMPERE = Unit.of("A", "ampere", Dimension.CURRENT, 1.0);

    // Temperature.
    /** Kelvin, the SI unit of temperature. */
    public static final Unit KELVIN = Unit.of("K", "kelvin", Dimension.TEMPERATURE, 1.0);
    /** Degree Celsius; a reading of 0 °C is 273.15 K. */
    public static final Unit CELSIUS = new Unit("°C", "degree Celsius", Dimension.TEMPERATURE, 1.0,
            PhysicalConstants.ZERO_CELSIUS);
    /** Degree Fahrenheit; a reading of 32 °F is 273.15 K. */
    public static final Unit FAHRENHEIT = new Unit("°F", "degree Fahrenheit", Dimension.TEMPERATURE, 5.0 / 9.0,
            459.67 * 5.0 / 9.0);

    // Mechanics.
    /** Square metre. */
    public static final Unit SQUARE_METRE = Unit.of("m²", "square metre", Dimension.AREA, 1.0);
    /** Cubic metre, the volume of one Minecraft block. */
    public static final Unit CUBIC_METRE = Unit.of("m³", "cubic metre", Dimension.VOLUME, 1.0);
    /** Litre. */
    public static final Unit LITRE = Unit.of("L", "litre", Dimension.VOLUME, 1e-3);
    /** Metre per second. */
    public static final Unit METRE_PER_SECOND = Unit.of("m/s", "metre per second", Dimension.VELOCITY, 1.0);
    /** Newton. */
    public static final Unit NEWTON = Unit.of("N", "newton", Dimension.FORCE, 1.0);
    /** Pascal. */
    public static final Unit PASCAL = Unit.of("Pa", "pascal", Dimension.PRESSURE, 1.0);
    /** Kilopascal. */
    public static final Unit KILOPASCAL = Unit.of("kPa", "kilopascal", Dimension.PRESSURE, 1e3);
    /** Megapascal, the usual unit of material strength. */
    public static final Unit MEGAPASCAL = Unit.of("MPa", "megapascal", Dimension.PRESSURE, 1e6);
    /** Gigapascal, the usual unit of stiffness. */
    public static final Unit GIGAPASCAL = Unit.of("GPa", "gigapascal", Dimension.PRESSURE, 1e9);
    /** Bar. */
    public static final Unit BAR = Unit.of("bar", "bar", Dimension.PRESSURE, 1e5);
    /** Standard atmosphere. */
    public static final Unit ATMOSPHERE = Unit.of("atm", "standard atmosphere", Dimension.PRESSURE,
            PhysicalConstants.STANDARD_ATMOSPHERE);

    // Energy and power.
    /** Joule. */
    public static final Unit JOULE = Unit.of("J", "joule", Dimension.ENERGY, 1.0);
    /** Kilojoule. */
    public static final Unit KILOJOULE = Unit.of("kJ", "kilojoule", Dimension.ENERGY, 1e3);
    /** Megajoule. */
    public static final Unit MEGAJOULE = Unit.of("MJ", "megajoule", Dimension.ENERGY, 1e6);
    /** Kilowatt-hour. */
    public static final Unit KILOWATT_HOUR = Unit.of("kWh", "kilowatt-hour", Dimension.ENERGY, 3.6e6);
    /** Watt. */
    public static final Unit WATT = Unit.of("W", "watt", Dimension.POWER, 1.0);
    /** Kilowatt. */
    public static final Unit KILOWATT = Unit.of("kW", "kilowatt", Dimension.POWER, 1e3);

    // Material properties.
    /** Kilogram per cubic metre. */
    public static final Unit KILOGRAM_PER_CUBIC_METRE = Unit.of("kg/m³", "kilogram per cubic metre",
            Dimension.DENSITY, 1.0);
    /** Joule per kilogram kelvin. */
    public static final Unit JOULE_PER_KILOGRAM_KELVIN = Unit.of("J/(kg·K)", "joule per kilogram kelvin",
            Dimension.SPECIFIC_HEAT_CAPACITY, 1.0);
    /** Kilojoule per kilogram kelvin. */
    public static final Unit KILOJOULE_PER_KILOGRAM_KELVIN = Unit.of("kJ/(kg·K)", "kilojoule per kilogram kelvin",
            Dimension.SPECIFIC_HEAT_CAPACITY, 1e3);
    /** Joule per kilogram. */
    public static final Unit JOULE_PER_KILOGRAM = Unit.of("J/kg", "joule per kilogram",
            Dimension.SPECIFIC_ENERGY, 1.0);
    /** Kilojoule per kilogram. */
    public static final Unit KILOJOULE_PER_KILOGRAM = Unit.of("kJ/kg", "kilojoule per kilogram",
            Dimension.SPECIFIC_ENERGY, 1e3);
    /** Watt per metre kelvin. */
    public static final Unit WATT_PER_METRE_KELVIN = Unit.of("W/(m·K)", "watt per metre kelvin",
            Dimension.THERMAL_CONDUCTIVITY, 1.0);
    /** Watt per square metre. */
    public static final Unit WATT_PER_SQUARE_METRE = Unit.of("W/m²", "watt per square metre",
            Dimension.HEAT_FLUX, 1.0);
    /** Gram per mole, the conventional unit of molar mass. */
    public static final Unit GRAM_PER_MOLE = Unit.of("g/mol", "gram per mole", Dimension.MOLAR_MASS, 1e-3);
    /** Kilogram per mole, the coherent SI unit of molar mass. */
    public static final Unit KILOGRAM_PER_MOLE = Unit.of("kg/mol", "kilogram per mole", Dimension.MOLAR_MASS, 1.0);

    // Electricity.
    /** Coulomb. */
    public static final Unit COULOMB = Unit.of("C", "coulomb", Dimension.CHARGE, 1.0);
    /** Volt. */
    public static final Unit VOLT = Unit.of("V", "volt", Dimension.VOLTAGE, 1.0);
    /** Ohm. */
    public static final Unit OHM = Unit.of("Ω", "ohm", Dimension.RESISTANCE, 1.0);

    /** A pure number. */
    public static final Unit ONE = Unit.of("", "one", Dimension.NONE, 1.0);
    /** Percent. */
    public static final Unit PERCENT = Unit.of("%", "percent", Dimension.NONE, 1e-2);

    private Units() {
    }
}
