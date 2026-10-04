package io.github.osir933.anchor.neoforge;

import io.github.osir933.anchor.core.physics.thermal.Sky;

/**
 * The weather around a place: the temperature and humidity of its surroundings, from its biome and height, and
 * the sky over it. Blocks start at the surroundings' temperature when they are first simulated, and air returns to
 * it.
 *
 * <p>Minecraft describes climate with a biome temperature that has no unit: snow falls below 0.15, plains
 * sit at 0.8 and deserts at 2.0, and it drops by 0.05 for every 40 blocks above y = 80. Anchor maps 0.15 to
 * the freezing point of water, so snow, ice and the simulated freezing point agree, and every unit to 23
 * degrees: plains come out at about 15 °C, snowy tundra just below freezing and deserts at about 42 °C.
 * The result is limited to between -30 °C and 45 °C.
 *
 * <p>Minecraft also gives each biome a downfall, from 0 in deserts and savannas to 1 in the wettest biomes, which
 * colours its grass. Anchor reads it as the relative humidity of the air: 20 % in deserts, 46 % in plains, 72 % in
 * forests and 79 % in jungles. It decides how fast water evaporates and how much heat a clear night sky sends
 * back down.
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

    /** The relative humidity of the air in the driest biomes, whose downfall is 0. */
    static final double DRIEST_HUMIDITY = 0.2;

    /** How much the relative humidity rises with each unit of downfall. */
    static final double HUMIDITY_PER_DOWNFALL = 0.65;

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

    /**
     * Returns the relative humidity of a place's air.
     *
     * @param downfall the biome's downfall, as Minecraft gives it
     * @return the relative humidity, from 0 to 1
     */
    static double relativeHumidity(double downfall) {
        return fraction(DRIEST_HUMIDITY + HUMIDITY_PER_DOWNFALL * downfall);
    }

    /**
     * Returns the sky for Minecraft's sun and weather. The sun rises in the east and sets in the west; rain brings
     * an overcast sky and moister air, and thunder storm clouds. Clear weather is a cloudless sky.
     *
     * @param sunAngle the sun's angle in degrees, as Minecraft's {@code sun_angle} environment attribute gives it:
     *     0 at noon, 90 at sunset, 180 at midnight and 270 at sunrise
     * @param rainLevel how hard it rains, from 0 to 1
     * @param thunderLevel how stormy it is, from 0 to 1
     * @return the sky
     */
    static Sky sky(double sunAngle, double rainLevel, double thunderLevel) {
        double rain = fraction(rainLevel);
        return Sky.clear(sunAngle + 90.0).withWeather(rain, rain, fraction(thunderLevel));
    }

    /** Limits a value to between 0 and 1, and makes NaN 0. */
    private static double fraction(double value) {
        return value > 0 ? Math.min(1.0, value) : 0.0;
    }
}
