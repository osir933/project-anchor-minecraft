package io.github.osir933.anchor.core.matter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MaterialTest {

    private static final Material WATER = MaterialLibrary.WATER;

    @Test
    void enthalpyIsZeroAtTheReferenceTemperature() {
        assertEquals(0.0, WATER.specificEnthalpy(298.15), 1e-9);
    }

    @Test
    void meltingIceAbsorbsTheLatentHeat() {
        double solid = WATER.specificEnthalpy(273.15);
        double liquid = WATER.thermal().specificEnthalpy(new ThermalState(273.15, 0, 1.0, false));
        assertEquals(333.7e3, liquid - solid, 1e-6);
        ThermalState halfway = WATER.stateFor(solid + 0.5 * 333.7e3);
        assertTrue(halfway.inTransition());
        assertEquals(273.15, halfway.temperatureK());
        assertEquals(0.5, halfway.transitionFraction(), 1e-12);
        double[] phases = WATER.phaseFractions(halfway);
        assertEquals(0.5, phases[Phase.SOLID.ordinal()], 1e-12);
        assertEquals(0.5, phases[Phase.LIQUID.ordinal()], 1e-12);
    }

    @Test
    void temperatureAndEnthalpyRoundTripInEveryMaterial() {
        for (Material m : MaterialLibrary.all()) {
            for (int i = 1; i < 50; i++) {
                double t = m.thermal().minTemperatureK()
                        + (m.thermal().maxTemperatureK() - m.thermal().minTemperatureK()) * i / 50.0;
                double back = m.stateFor(m.specificEnthalpy(t)).temperatureK();
                assertEquals(t, back, 1e-9 * t, m.id() + " at " + t + " K");
            }
        }
    }

    @Test
    void stateOutsideTheDataIsFlaggedAsExtrapolated() {
        double hot = WATER.thermal().maxTemperatureK() + 500;
        ThermalState state = WATER.stateFor(WATER.specificEnthalpy(hot));
        assertTrue(state.extrapolated());
        assertEquals(hot, state.temperatureK(), 1e-6);
        assertFalse(WATER.isDescribedAt(hot));
        assertFalse(WATER.stateFor(0.0).extrapolated());
    }

    @Test
    void ironGoesThroughItsAllotropes() {
        Material iron = MaterialLibrary.IRON;
        assertEquals(1811.0, iron.meltingPointK());
        assertTrue(iron.thermal().regions().get(iron.stateFor(iron.specificEnthalpy(1000)).region())
                .structure().contains("alpha"));
        assertTrue(iron.thermal().regions().get(iron.stateFor(iron.specificEnthalpy(1400)).region())
                .structure().contains("gamma"));
    }

    @Test
    void densitiesMatchHandbookValues() {
        assertEquals(997.0, WATER.density(WATER.stateFor(0.0)), 1.0);
        assertEquals(7870.0, MaterialLibrary.IRON.referenceDensity(), 20.0);
        assertEquals(1.18, MaterialLibrary.AIR.referenceDensity(), 0.02);
    }

    @Test
    void everyMaterialCitesItsSources() {
        for (Material m : MaterialLibrary.all()) {
            assertFalse(m.sources().isEmpty(), m.id());
            double sum = m.composition().elementMassFractions().values().stream().mapToDouble(Double::doubleValue)
                    .sum();
            assertEquals(1.0, sum, 1e-9, m.id());
        }
    }
}
