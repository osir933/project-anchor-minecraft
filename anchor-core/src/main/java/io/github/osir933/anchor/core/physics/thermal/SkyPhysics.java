package io.github.osir933.anchor.core.physics.thermal;

import io.github.osir933.anchor.core.units.PhysicalConstants;

/**
 * The sunlight and the sky's own thermal radiation that reach the ground, and the water vapour in the air, from
 * published empirical formulas. The air near the ground is taken to be at standard atmospheric pressure.
 *
 * <ul>
 *   <li>Direct sunlight through a clear sky follows Meinel and Meinel's fit, {@code 1353 × 0.7^(m^0.678)} W/m²,
 *   with {@code m} the air mass of Kasten and Young (1989); the clear sky adds a tenth of that as diffuse light
 *   on a level surface.</li>
 *   <li>Clouds dim it as Kasten and Czeplak (1980) found, by {@code 1 − 0.75·N^3.4} for a cloud cover
 *   {@code N}; thunderclouds, which they did not separate, are taken to let through 60 % less again.</li>
 *   <li>The clear sky radiates as a grey body at the air's temperature with Brutsaert's (1975) emissivity
 *   {@code 1.24·(e/T)^(1/7)}, for vapour pressure {@code e} in hPa; clouds, which radiate almost as black
 *   bodies, raise it as Unsworth and Monteith (1975) found, to {@code (1 − 0.84·N)·ε + 0.84·N}.</li>
 *   <li>Saturation vapour pressure over water and over ice follows Alduchov and Eskridge's (1996) Magnus
 *   formulas.</li>
 * </ul>
 */
public final class SkyPhysics {

    /** The solar constant of Meinel and Meinel's fit for direct sunlight, in W/m². */
    public static final double MEINEL_SOLAR_CONSTANT = 1353.0;

    /** The diffuse light a clear sky adds on a level surface, as a share of the direct beam. */
    public static final double CLEAR_SKY_DIFFUSE_SHARE = 0.1;

    /** How much less sunlight thunderclouds let through than ordinary overcast, as a fraction. */
    public static final double STORM_DIMMING = 0.6;

    /** The specific gas constant of dry air, in J/(kg·K). */
    public static final double DRY_AIR_GAS_CONSTANT = 287.05;

    /** The specific heat of air at constant pressure, in J/(kg·K). */
    public static final double AIR_SPECIFIC_HEAT = 1005.0;

    /** The ratio of the molar masses of water and dry air. */
    public static final double MOLAR_MASS_RATIO = 0.622;

    /** The latent heat of sublimation of ice, in J/kg. */
    public static final double SUBLIMATION_HEAT = 2.834e6;

    private static final double SIGMA = PhysicalConstants.STEFAN_BOLTZMANN;
    private static final double ZERO_CELSIUS = PhysicalConstants.ZERO_CELSIUS;

    private SkyPhysics() {
    }

    /**
     * Returns the relative mass of air sunlight crosses to reach the ground, after Kasten and Young (1989): 1 with
     * the sun overhead, about 38 at the horizon.
     *
     * @param sunHeightSine the sine of the sun's height above the horizon
     * @return the air mass, or positive infinity if the sun is not above the horizon
     */
    public static double airMass(double sunHeightSine) {
        if (!(sunHeightSine > 0)) {
            return Double.POSITIVE_INFINITY;
        }
        double zenithDegrees = StrictMath.toDegrees(StrictMath.acos(Math.min(1.0, sunHeightSine)));
        return 1.0 / (sunHeightSine + 0.50572 * StrictMath.pow(96.07995 - zenithDegrees, -1.6364));
    }

    /**
     * Returns the direct sunlight through a clear sky on a surface facing the sun, after Meinel and Meinel.
     *
     * @param sunHeightSine the sine of the sun's height above the horizon
     * @return the irradiance in W/m², zero at night
     */
    public static double clearSkyBeam(double sunHeightSine) {
        double mass = airMass(sunHeightSine);
        if (mass == Double.POSITIVE_INFINITY) {
            return 0.0;
        }
        return MEINEL_SOLAR_CONSTANT * StrictMath.pow(0.7, StrictMath.pow(mass, 0.678));
    }

    /**
     * Returns the fraction of clear-sky sunlight that clouds let through, after Kasten and Czeplak, dimmed further
     * in a storm.
     *
     * @param cloudCover the cloud cover, from 0 to 1
     * @param storm the storminess, from 0 to 1
     * @return the fraction
     */
    public static double cloudTransmission(double cloudCover, double storm) {
        return (1.0 - 0.75 * StrictMath.pow(cloudCover, 3.4)) * (1.0 - STORM_DIMMING * storm);
    }

    /**
     * Returns the sunlight, direct and diffuse, that falls on a level surface under an open sky.
     *
     * @param sky the sky
     * @return the irradiance in W/m², zero at night
     */
    public static double sunlightOnLevelGround(Sky sky) {
        double beam = clearSkyBeam(sky.sunY());
        if (beam == 0) {
            return 0.0;
        }
        double clear = beam * (sky.sunY() + CLEAR_SKY_DIFFUSE_SHARE);
        return clear * cloudTransmission(sky.cloudCover(), sky.storm());
    }

    /**
     * Returns the emissivity of the sky as seen from the ground, after Brutsaert for a clear sky and Unsworth and
     * Monteith for cloud.
     *
     * @param airK the temperature of the air near the ground, in kelvin
     * @param vapourPressurePa the pressure of water vapour in that air, in pascals
     * @param cloudCover the cloud cover, from 0 to 1
     * @return the emissivity, at most 1
     */
    public static double skyEmissivity(double airK, double vapourPressurePa, double cloudCover) {
        double clear = 1.24 * StrictMath.pow(Math.max(0.0, vapourPressurePa) / 100.0 / airK, 1.0 / 7.0);
        return Math.min(1.0, (1.0 - 0.84 * cloudCover) * Math.min(1.0, clear) + 0.84 * cloudCover);
    }

    /**
     * Returns the thermal radiation the sky sends down onto level ground.
     *
     * @param airK the temperature of the air near the ground, in kelvin
     * @param vapourPressurePa the pressure of water vapour in that air, in pascals
     * @param cloudCover the cloud cover, from 0 to 1
     * @return the irradiance in W/m²
     */
    public static double skyRadiation(double airK, double vapourPressurePa, double cloudCover) {
        return skyEmissivity(airK, vapourPressurePa, cloudCover) * SIGMA * (airK * airK) * (airK * airK);
    }

    /**
     * Returns the temperature of a black sky that would send down the same thermal radiation.
     *
     * @param airK the temperature of the air near the ground, in kelvin
     * @param vapourPressurePa the pressure of water vapour in that air, in pascals
     * @param cloudCover the cloud cover, from 0 to 1
     * @return the temperature in kelvin
     */
    public static double skyTemperature(double airK, double vapourPressurePa, double cloudCover) {
        return airK * Math.sqrt(Math.sqrt(skyEmissivity(airK, vapourPressurePa, cloudCover)));
    }

    /**
     * Returns the saturation vapour pressure over liquid water, after Alduchov and Eskridge. Temperatures are held
     * between -80 °C and 100 °C, past which the fit no longer applies.
     *
     * @param temperatureK the temperature in kelvin
     * @return the pressure in pascals
     */
    public static double saturationOverWater(double temperatureK) {
        double c = Math.max(-80.0, Math.min(100.0, temperatureK - ZERO_CELSIUS));
        return 610.94 * StrictMath.exp(17.625 * c / (c + 243.04));
    }

    /**
     * Returns how fast the saturation vapour pressure over liquid water rises with temperature.
     *
     * @param temperatureK the temperature in kelvin
     * @return the slope in pascals per kelvin, zero where the temperature is held
     */
    public static double saturationOverWaterSlope(double temperatureK) {
        double c = temperatureK - ZERO_CELSIUS;
        if (c < -80.0 || c > 100.0) {
            return 0.0;
        }
        double d = c + 243.04;
        return saturationOverWater(temperatureK) * 17.625 * 243.04 / (d * d);
    }

    /**
     * Returns the saturation vapour pressure over ice, after Alduchov and Eskridge. Temperatures are held between
     * -80 °C and 0 °C.
     *
     * @param temperatureK the temperature in kelvin
     * @return the pressure in pascals
     */
    public static double saturationOverIce(double temperatureK) {
        double c = Math.max(-80.0, Math.min(0.0, temperatureK - ZERO_CELSIUS));
        return 611.21 * StrictMath.exp(22.587 * c / (c + 273.86));
    }

    /**
     * Returns how fast the saturation vapour pressure over ice rises with temperature.
     *
     * @param temperatureK the temperature in kelvin
     * @return the slope in pascals per kelvin, zero where the temperature is held
     */
    public static double saturationOverIceSlope(double temperatureK) {
        double c = temperatureK - ZERO_CELSIUS;
        if (c < -80.0 || c > 0.0) {
            return 0.0;
        }
        double d = c + 273.86;
        return saturationOverIce(temperatureK) * 22.587 * 273.86 / (d * d);
    }

    /**
     * Returns the latent heat of vaporisation of water, which falls as it warms (Rogers and Yau, A Short Course
     * in Cloud Physics, 3rd ed., 1989).
     *
     * @param temperatureK the temperature in kelvin
     * @return the latent heat in J/kg
     */
    public static double vaporisationHeat(double temperatureK) {
        return 2.501e6 - 2370.0 * (temperatureK - ZERO_CELSIUS);
    }

    /**
     * Returns the density of air at standard atmospheric pressure, treating it as dry.
     *
     * @param temperatureK the temperature in kelvin
     * @return the density in kg/m³
     */
    public static double airDensity(double temperatureK) {
        return PhysicalConstants.STANDARD_ATMOSPHERE / (DRY_AIR_GAS_CONSTANT * temperatureK);
    }
}
