package io.github.osir933.anchor.core.matter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.TreeSet;
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
                assertEquals(back, m.temperatureFor(m.specificEnthalpy(t)), m.id() + " at " + t + " K");
            }
        }
    }

    @Test
    void theTemperatureShortcutAgreesWithTheFullStateEverywhere() {
        for (Material m : MaterialLibrary.all()) {
            double lo = m.specificEnthalpy(m.thermal().minTemperatureK() - 50.0);
            double hi = m.specificEnthalpy(m.thermal().maxTemperatureK() + 50.0);
            for (int i = 0; i <= 1000; i++) {
                double h = lo + (hi - lo) * i / 1000.0;
                assertEquals(m.stateFor(h).temperatureK(), m.temperatureFor(h), m.id() + " at " + h + " J/kg");
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
    void theDominantPhaseFollowsTheMassFractions() {
        double solid = WATER.specificEnthalpy(273.15);
        ThermalState mostlyIce = WATER.stateFor(solid + 0.3 * 333.7e3);
        ThermalState mostlyWater = WATER.stateFor(solid + 0.7 * 333.7e3);
        assertEquals(Phase.SOLID, WATER.dominantPhase(mostlyIce));
        assertEquals(Phase.LIQUID, WATER.dominantPhase(mostlyWater));
        assertEquals(Phase.GAS, WATER.dominantPhase(WATER.stateFor(WATER.specificEnthalpy(400.0))));
        assertTrue(WATER.canAppearAs(mostlyIce, Phase.SOLID));
        assertTrue(WATER.canAppearAs(mostlyIce, Phase.LIQUID));
        assertFalse(WATER.canAppearAs(mostlyIce, Phase.GAS));
        assertFalse(WATER.canAppearAs(WATER.stateFor(WATER.specificEnthalpy(300.0)), Phase.SOLID));
    }

    @Test
    void theNearestStateInAPhaseStopsAtItsTransitions() {
        double warm = WATER.specificEnthalpy(300.0);
        assertEquals(warm, WATER.nearestSpecificEnthalpyIn(Phase.LIQUID, warm));

        double coldestSteam = WATER.nearestSpecificEnthalpyIn(Phase.GAS, warm);
        assertEquals(WATER.thermal().regionStartEnthalpy(2), coldestSteam);
        assertEquals(373.124, WATER.stateFor(coldestSteam).temperatureK(), 1e-9);
        assertEquals(Phase.GAS, WATER.dominantPhase(WATER.stateFor(coldestSteam)));

        double warmestIce = WATER.nearestSpecificEnthalpyIn(Phase.SOLID, warm);
        assertEquals(WATER.thermal().regionEndEnthalpy(0), warmestIce);
        assertEquals(273.15, WATER.stateFor(warmestIce).temperatureK(), 1e-9);
        assertEquals(Phase.SOLID, WATER.dominantPhase(WATER.stateFor(warmestIce)));

        double veryCold = WATER.specificEnthalpy(100.0);
        assertEquals(veryCold, WATER.nearestSpecificEnthalpyIn(Phase.SOLID, veryCold), "ice continues below");
        assertTrue(Double.isNaN(MaterialLibrary.GRANITE.nearestSpecificEnthalpyIn(Phase.GAS, warm)));

        Material iron = MaterialLibrary.IRON;
        double gamma = iron.specificEnthalpy(1400.0);
        assertEquals(gamma, iron.nearestSpecificEnthalpyIn(Phase.SOLID, gamma), "allotropes are all solid");
        double molten = iron.specificEnthalpy(2000.0);
        assertEquals(1811.0, iron.stateFor(iron.nearestSpecificEnthalpyIn(Phase.SOLID, molten)).temperatureK(),
                1e-9);
    }

    @Test
    void everyLiquidCanFlow() {
        for (Material m : MaterialLibrary.all()) {
            for (PhaseRegion r : m.thermal().regions()) {
                if (r.phase() != Phase.LIQUID) {
                    assertNull(r.viscosity(), m.id() + " " + r.structure());
                    continue;
                }
                assertNotNull(r.viscosity(), "every liquid has a viscosity: " + m.id());
                assertTrue(m.sources().containsKey(r.viscosity().source().key()), m.id());
                double previous = Double.POSITIVE_INFINITY;
                for (double t = r.fromK(); t <= r.toK(); t += (r.toK() - r.fromK()) / 50) {
                    double viscosity = r.viscosity().at(t);
                    assertTrue(viscosity > 0 && viscosity < previous, "liquids thin as they warm: " + m.id() + " at "
                            + t + " K");
                    previous = viscosity;
                    if (t > 278.0 && t + 1 <= r.toK()) {
                        assertTrue(r.density().at(t + 1) <= r.density().at(t), "and never shrink: " + m.id() + " at "
                                + t + " K");
                    }
                }
            }
        }
    }

    @Test
    void waterIsDensestAtFourDegrees() {
        double densest = WATER.density(WATER.stateFor(WATER.specificEnthalpy(277.15)));
        for (double t : new double[] {273.15, 275.15, 276.15, 278.15, 279.15, 283.15}) {
            assertTrue(WATER.density(WATER.stateFor(WATER.specificEnthalpy(t))) < densest, t + " K");
        }
        ThermalState melting = WATER.stateFor(WATER.specificEnthalpy(273.15) + 0.5 * 333.7e3);
        assertTrue(Double.isNaN(WATER.viscosity(melting)), "half-frozen water has no single viscosity");
        assertEquals(1.08e-3, WATER.viscosity(WATER.stateFor(WATER.specificEnthalpy(290.0))), 1e-12);
    }

    @Test
    void onlyLiquidsTakeAViscosity() {
        PropertyCurve one = PropertyCurve.constant(1.0, MaterialLibrary.PROPERTY_ESTIMATE);
        assertThrows(IllegalArgumentException.class,
                () -> new PhaseRegion(Phase.SOLID, "glassy", 100.0, 200.0, one, one, one, one, one));
    }

    @Test
    void everyMaterialHasAUniqueId() {
        TreeSet<String> ids = new TreeSet<>();
        for (Material m : MaterialLibrary.all()) {
            assertTrue(ids.add(m.id()), "duplicate id " + m.id());
        }
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
