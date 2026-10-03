package io.github.osir933.anchor.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
