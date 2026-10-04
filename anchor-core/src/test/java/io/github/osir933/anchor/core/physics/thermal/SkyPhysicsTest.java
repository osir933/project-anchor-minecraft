package io.github.osir933.anchor.core.physics.thermal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.osir933.anchor.core.units.PhysicalConstants;
import org.junit.jupiter.api.Test;

class SkyPhysicsTest {

    @Test
    void airMassMatchesKastenAndYoung() {
        assertEquals(1.0, SkyPhysics.airMass(1.0), 1e-3, "the sun overhead");
        assertEquals(2.0, SkyPhysics.airMass(0.5), 0.01, "close to the secant at 60 degrees from overhead");
        assertEquals(37.92, SkyPhysics.airMass(1e-12), 0.05, "and finite at the horizon");
        assertEquals(Double.POSITIVE_INFINITY, SkyPhysics.airMass(0.0));
        assertEquals(Double.POSITIVE_INFINITY, SkyPhysics.airMass(-0.3));
    }

    @Test
    void clearSkySunlightMatchesMeinel() {
        assertEquals(947.0, SkyPhysics.clearSkyBeam(1.0), 1.0, "1353 x 0.7 W/m2 with the sun overhead");
        double am15 = 1353.0 * StrictMath.pow(0.7, StrictMath.pow(1.5, 0.678));
        assertEquals(846.0, am15, 1.0);
        assertEquals(am15, SkyPhysics.clearSkyBeam(1.0 / 1.5), 2.0, "air mass 1.5");
        assertEquals(0.0, SkyPhysics.clearSkyBeam(-0.1), "nothing at night");
        assertEquals(1042.0, SkyPhysics.sunlightOnLevelGround(Sky.clear(90.0)), 2.0, "a clear noon, about 1 kW/m2");
        double lowSun = SkyPhysics.sunlightOnLevelGround(Sky.clear(10.0));
        assertTrue(lowSun > 50 && lowSun < 150, "a low sun gives little: " + lowSun);
        assertEquals(0.0, SkyPhysics.sunlightOnLevelGround(Sky.clear(-5.0)));
    }

    @Test
    void cloudsDimTheSunAsKastenAndCzeplakFound() {
        assertEquals(1.0, SkyPhysics.cloudTransmission(0.0, 0.0));
        assertEquals(0.25, SkyPhysics.cloudTransmission(1.0, 0.0), 1e-12, "overcast lets through a quarter");
        assertEquals(1.0 - 0.75 * StrictMath.pow(0.5, 3.4), SkyPhysics.cloudTransmission(0.5, 0.0), 1e-12);
        assertEquals(0.1, SkyPhysics.cloudTransmission(1.0, 1.0), 1e-12, "thunderclouds a tenth");
        Sky overcast = Sky.clear(90.0).withWeather(1.0, 1.0, 0.0);
        assertEquals(0.25 * SkyPhysics.sunlightOnLevelGround(Sky.clear(90.0)),
                SkyPhysics.sunlightOnLevelGround(overcast), 1e-9);
    }

    @Test
    void theClearSkyIsColderThanTheAirAndCloudsWarmIt() {
        // Brutsaert: 1.24 (10 hPa / 288.15 K)^(1/7).
        assertEquals(0.767, SkyPhysics.skyEmissivity(288.15, 1000.0, 0.0), 1e-3);
        double clear = SkyPhysics.skyTemperature(288.15, 1000.0, 0.0);
        assertEquals(269.7, clear, 0.3, "a clear sky 18 K colder than mild, fairly dry air");
        assertEquals(0.16 * 0.767 + 0.84, SkyPhysics.skyEmissivity(288.15, 1000.0, 1.0), 1e-3);
        assertTrue(SkyPhysics.skyTemperature(288.15, 1000.0, 1.0) > 285.0, "overcast is nearly as warm as the air");
        assertTrue(SkyPhysics.skyEmissivity(288.15, 300.0, 0.0) < SkyPhysics.skyEmissivity(288.15, 1500.0, 0.0),
                "dry air lets more heat escape");
        assertEquals(0.767 * PhysicalConstants.STEFAN_BOLTZMANN * StrictMath.pow(288.15, 4),
                SkyPhysics.skyRadiation(288.15, 1000.0, 0.0), 0.5);
    }

    @Test
    void saturationVapourPressureMatchesTheTables() {
        assertEquals(611.2, SkyPhysics.saturationOverWater(273.15), 0.5);
        assertEquals(2339.0, SkyPhysics.saturationOverWater(293.15), 10.0);
        assertEquals(7384.0, SkyPhysics.saturationOverWater(313.15), 40.0);
        assertEquals(101_325.0, SkyPhysics.saturationOverWater(373.15), 3000.0, "boiling at one atmosphere");
        assertEquals(259.9, SkyPhysics.saturationOverIce(263.15), 0.5);
        assertEquals(103.3, SkyPhysics.saturationOverIce(253.15), 0.5);
        assertTrue(SkyPhysics.saturationOverIce(263.15) < SkyPhysics.saturationOverWater(263.15),
                "ice holds its vapour tighter than supercooled water");
        for (double t = 230.0; t < 370.0; t += 7.3) {
            double slope = (SkyPhysics.saturationOverWater(t + 1e-3) - SkyPhysics.saturationOverWater(t - 1e-3)) / 2e-3;
            assertEquals(slope, SkyPhysics.saturationOverWaterSlope(t), 1e-4 * slope + 1e-9, t + " K");
            if (t < 272.0) {
                double ice = (SkyPhysics.saturationOverIce(t + 1e-3) - SkyPhysics.saturationOverIce(t - 1e-3)) / 2e-3;
                assertEquals(ice, SkyPhysics.saturationOverIceSlope(t), 1e-4 * ice + 1e-9, t + " K");
            }
        }
    }

    @Test
    void airAndWaterPropertiesMatchStandardValues() {
        assertEquals(1.225, SkyPhysics.airDensity(288.15), 1e-3, "the standard atmosphere at sea level");
        assertEquals(2.501e6, SkyPhysics.vaporisationHeat(273.15), 1.0);
        assertEquals(2.257e6, SkyPhysics.vaporisationHeat(373.15), 1e4);
    }

    @Test
    void theSkyNormalisesTheSunAndChecksTheWeather() {
        Sky sky = new Sky(0.0, 2.0, 0.0, 0.2, 0.0, 0.0);
        assertEquals(1.0, sky.sunY(), 1e-15);
        assertTrue(sky.sunUp());
        assertEquals(-1.0, Sky.clear(270.0).sunY(), 1e-12);
        assertEquals(1.0, Sky.clear(0.0).sunX(), 1e-12, "the sun rises in the east, along +x");
        assertThrows(IllegalArgumentException.class, () -> new Sky(0, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Sky(0, 1, 0, 1.5, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Sky(0, 1, 0, 0, Double.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> new Sky(0, 1, 0, 0, 0, -0.1));
    }
}
