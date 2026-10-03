package io.github.osir933.anchor.neoforge;

/**
 * The temperature of a place's surroundings, from its biome and height. Blocks start at this temperature when
 * they are first simulated, and air returns to it.
 *
 * <p>Minecraft describes climate with a biome temperature that has no unit: snow falls below 0.15, plains
 * sit at 0.8 and deserts at 2.0, and it drops by 0.05 for every 40 blocks above y = 80. Anchor maps 0.15 to
 * the freezing point of water, so snow, ice and the simulated freezing point agree, and every unit to 23
 * degrees: plains come out at about 15 °C, snowy tundra just below freezing and deserts at about 42 °C.
 * The result is limited to between -30 °C and 45 °C.
 */
final class Climate {

    /** The biome temperature below which Minecraft lets snow fall, taken as the freezing point of water. */
    static final double FREEZING_BIOME_TEMPERATURE = 0.15;

    /** Degrees Celsius per unit of biome temperature. */
    static final double CELSIUS_PER_UNIT = 23.0;

    /** The height above which biome temperature drops. */
    static final int COOLING_START_Y = 80;

    /** How much biome temperature drops per block above {@link #COOLING_START_Y}, as in Minecraft. */
    static final double COOLING_PER_BLOCK = 0.05 / 40.0;

    /** The coldest surroundings, in degrees Celsius. */
    static final double COLDEST_C = -30.0;

    /** The warmest surroundings, in degrees Celsius. */
    static final double WARMEST_C = 45.0;

    private static final double ZERO_CELSIUS_K = 273.15;

    private Climate() {
    }

    /**
     * Returns the temperature of a place's surroundings.
     *
     * @param biomeTemperature the biome's base temperature, as Minecraft gives it
     * @param y the height of the place
     * @return the temperature in kelvin
     */
    static double kelvin(double biomeTemperature, int y) {
        double t = biomeTemperature - Math.max(0, y - COOLING_START_Y) * COOLING_PER_BLOCK;
        double celsius = CELSIUS_PER_UNIT * (t - FREEZING_BIOME_TEMPERATURE);
        return ZERO_CELSIUS_K + Math.max(COLDEST_C, Math.min(WARMEST_C, celsius));
    }
}
