package io.github.osir933.anchor.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.osir933.anchor.core.physics.thermal.Sky;
import org.junit.jupiter.api.Test;

class ClimateTest {

    @Test
    void snowFallsWhereWaterFreezes() {
        assertEquals(273.15, Climate.kelvin(Climate.FREEZING_BIOME_TEMPERATURE, 64), 1e-9);
    }

    @Test
    void biomesGetFamiliarTemperatures() {
        assertEquals(14.95, Climate.kelvin(0.8, 64) - 273.15, 1e-9, "plains are temperate");
        assertEquals(42.55, Climate.kelvin(2.0, 64) - 273.15, 1e-9, "deserts are hot");
        assertEquals(-3.45, Climate.kelvin(0.0, 64) - 273.15, 1e-9, "snowy plains freeze");
    }

    @Test
    void itGetsColderHigherUpAsMinecraftsSnowLineDoes() {
        assertEquals(Climate.kelvin(0.8, 80), Climate.kelvin(0.8, -40), 1e-12, "no change below y = 80");
        // 0.8 - 0.15 = 0.65 of biome temperature lies between plains and freezing: 520 blocks above y = 80.
        assertEquals(273.15, Climate.kelvin(0.8, 600), 1e-9);
    }

    @Test
    void extremesAreLimited() {
        assertEquals(273.15 + Climate.WARMEST_C, Climate.kelvin(10.0, 64), 1e-9);
        assertEquals(273.15 + Climate.COLDEST_C, Climate.kelvin(-5.0, 64), 1e-9);
    }

    @Test
    void wetterBiomesHaveMoisterAir() {
        assertEquals(0.2, Climate.relativeHumidity(0.0), 1e-12, "deserts are dry");
        assertEquals(0.46, Climate.relativeHumidity(0.4), 1e-12, "plains");
        assertEquals(0.785, Climate.relativeHumidity(0.9), 1e-12, "jungles are humid");
        assertEquals(1.0, Climate.relativeHumidity(5.0), 1e-12, "a data pack cannot make the air hold more");
        assertEquals(0.0, Climate.relativeHumidity(-1.0), 1e-12);
    }

    @Test
    void theSunRisesInTheEastAndSetsInTheWest() {
        assertEquals(1.0, Climate.sky(0.0, 0.0, 0.0).sunY(), 1e-12, "overhead at noon");
        Sky sunrise = Climate.sky(270.0, 0.0, 0.0);
        assertEquals(1.0, sunrise.sunX(), 1e-12, "east is +x");
        assertEquals(0.0, sunrise.sunY(), 1e-12);
        assertEquals(-1.0, Climate.sky(90.0, 0.0, 0.0).sunX(), 1e-12, "west is -x");
        assertTrue(Climate.sky(180.0, 0.0, 0.0).sunY() < -0.99, "below the ground at midnight");
        assertEquals(Math.sqrt(0.5), Climate.sky(-45.0, 0.0, 0.0).sunY(), 1e-12, "halfway up the morning sky");
    }

    @Test
    void rainCloudsTheSky() {
        assertEquals(0.0, Climate.sky(0.0, 0.0, 0.0).cloudCover(), "clear weather is a cloudless sky");
        Sky storm = Climate.sky(0.0, 1.0, 0.7);
        assertEquals(1.0, storm.cloudCover());
        assertEquals(1.0, storm.precipitation());
        assertEquals(0.7, storm.storm(), 1e-12);
        Sky odd = Climate.sky(0.0, Double.NaN, 1.5);
        assertEquals(0.0, odd.cloudCover(), "an odd weather value is taken as none");
        assertEquals(1.0, odd.storm());
    }
}
