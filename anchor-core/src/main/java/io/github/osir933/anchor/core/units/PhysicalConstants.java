package io.github.osir933.anchor.core.units;

/**
 * Physical constants in coherent SI units.
 *
 * <p>Values marked exact are fixed by the 2019 redefinition of the SI. The Stefan–Boltzmann constant is
 * derived from exact constants and quoted to the precision CODATA 2018 recommends.
 */
public final class PhysicalConstants {

    /** Speed of light in vacuum, m/s (exact). */
    public static final double SPEED_OF_LIGHT = 299_792_458.0;
    /** Planck constant, J·s (exact). */
    public static final double PLANCK = 6.626_070_15e-34;
    /** Elementary charge, C (exact). */
    public static final double ELEMENTARY_CHARGE = 1.602_176_634e-19;
    /** Boltzmann constant, J/K (exact). */
    public static final double BOLTZMANN = 1.380_649e-23;
    /** Avogadro constant, 1/mol (exact). */
    public static final double AVOGADRO = 6.022_140_76e23;
    /** Molar gas constant, J/(mol·K) (exact, the product of the Boltzmann and Avogadro constants). */
    public static final double GAS_CONSTANT = BOLTZMANN * AVOGADRO;
    /** Stefan–Boltzmann constant, W/(m²·K⁴) (CODATA 2018). */
    public static final double STEFAN_BOLTZMANN = 5.670_374_419e-8;
    /** Standard acceleration of gravity, m/s² (exact by definition). */
    public static final double STANDARD_GRAVITY = 9.806_65;
    /** Standard atmosphere, Pa (exact by definition). */
    public static final double STANDARD_ATMOSPHERE = 101_325.0;
    /** The kelvin temperature of 0 °C (exact by definition). */
    public static final double ZERO_CELSIUS = 273.15;
    /** Reference temperature for material data and specific enthalpy, K (25 °C). */
    public static final double STANDARD_TEMPERATURE = 298.15;

    private PhysicalConstants() {
    }
}
