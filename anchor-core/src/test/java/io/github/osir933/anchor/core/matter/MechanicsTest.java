package io.github.osir933.anchor.core.matter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.osir933.anchor.core.matter.Mechanics.Failure;
import org.junit.jupiter.api.Test;

class MechanicsTest {

    private static final double COLD = Mechanics.REFERENCE_K;

    @Test
    void everyMaterialThatCanBeSolidCarriesLoads() {
        for (Material m : MaterialLibrary.all()) {
            boolean solid = m.thermal().regions().stream().anyMatch(r -> r.phase() == Phase.SOLID);
            if (solid) {
                assertNotNull(m.mechanics(), m.id() + " has a solid phase but no mechanics");
            } else {
                assertNull(m.mechanics(), m.id());
            }
        }
        assertNull(MaterialLibrary.AIR.mechanics());
    }

    @Test
    void coldValuesArePhysicallyPlausible() {
        for (Material m : MaterialLibrary.all()) {
            Mechanics k = m.mechanics();
            if (k == null) {
                continue;
            }
            double e = k.youngsModulus(COLD);
            assertTrue(e >= 1e5 && e <= 1.2e12, m.id() + " Young's modulus " + e);
            assertTrue(k.compressiveStrength(COLD) >= k.tensileStrength(COLD), m.id()
                    + " is weaker in compression than in tension");
            // No solid stretches by more than a few percent before it fails.
            assertTrue(k.tensileStrength(COLD) / e < 0.05, m.id() + " fails at too large a strain");
            assertTrue(k.friction() > 0 && k.friction() <= 1.0, m.id() + " friction " + k.friction());
            assertTrue(k.expansion().at(COLD) > 0 && k.expansion().at(COLD) < 1e-4, m.id());
            assertEquals(k.youngsModulus(COLD) / (2 * (1 + k.poissonRatio())), k.shearModulus(COLD), 1e-6);
            for (String key : k.sources().keySet()) {
                assertTrue(m.sources().containsKey(key), m.id() + " does not list the source " + key);
            }
        }
    }

    @Test
    void failureKindsMatchTheMaterials() {
        assertEquals(Failure.DUCTILE, MaterialLibrary.IRON.mechanics().failure());
        assertEquals(Failure.DUCTILE, MaterialLibrary.COPPER.mechanics().failure());
        assertEquals(Failure.BRITTLE, MaterialLibrary.GLASS.mechanics().failure());
        assertEquals(Failure.BRITTLE, MaterialLibrary.GRANITE.mechanics().failure());
        assertEquals(Failure.GRANULAR, MaterialLibrary.SAND.mechanics().failure());
        assertEquals(Failure.GRANULAR, MaterialLibrary.GRAVEL.mechanics().failure());
        assertEquals(0.0, MaterialLibrary.SAND.mechanics().tensileStrength(COLD));
    }

    @Test
    void ironSoftensAsEurocodeThreeSaysSteelDoes() {
        Mechanics iron = MaterialLibrary.IRON.mechanics();
        double yield = iron.tensileStrength(COLD);
        double modulus = iron.youngsModulus(COLD);
        assertEquals(yield, iron.tensileStrength(673.15), 1e-6, "full strength up to 400 °C");
        assertEquals(0.47 * yield, iron.tensileStrength(873.15), 1e-3);
        assertEquals(0.11 * yield, iron.tensileStrength(1073.15), 1e-3);
        assertEquals(0.31 * modulus, iron.youngsModulus(873.15), 1.0);
        assertEquals(0.0, iron.tensileStrength(1500.0), "nothing left at 1200 °C and above");
    }

    @Test
    void concreteAndGlassLoseStrengthInFire() {
        Mechanics concrete = MaterialLibrary.CONCRETE.mechanics();
        assertEquals(0.45 * concrete.compressiveStrength(COLD), concrete.compressiveStrength(873.15), 1e-3);
        assertEquals(0.0, concrete.tensileStrength(873.15));
        Mechanics glass = MaterialLibrary.GLASS.mechanics();
        assertEquals(glass.tensileStrength(COLD), glass.tensileStrength(700.0), 1e-6, "glass is strong below 450 °C");
        assertTrue(glass.tensileStrength(923.15) < 0.05 * glass.tensileStrength(COLD), "and soft by 650 °C");
    }

    @Test
    void thermalStrainIntegratesTheExpansionCoefficient() {
        Mechanics iron = MaterialLibrary.IRON.mechanics();
        double strain = iron.thermalStrain(293.15, 393.15);
        assertTrue(strain > 11.8e-6 * 100 && strain < 13.4e-6 * 100, "iron warmed by 100 K: " + strain);
        assertEquals(-strain, iron.thermalStrain(393.15, 293.15), 1e-18);
        assertEquals(9e-6 * 50, MaterialLibrary.GLASS.mechanics().thermalStrain(300.0, 350.0), 1e-15);
    }

    @Test
    void rejectsImpossibleDescriptions() {
        Source s = new Source("test", "test", Source.DataQuality.ESTIMATED);
        PropertyCurve one = PropertyCurve.constant(1e9, s);
        PropertyCurve zero = PropertyCurve.constant(0.0, s);
        PropertyCurve alpha = PropertyCurve.constant(1e-5, s);
        assertThrows(IllegalArgumentException.class,
                () -> new Mechanics(Failure.BRITTLE, one, 0.5, one, one, 0.5, alpha, Double.NaN, s));
        assertThrows(IllegalArgumentException.class,
                () -> new Mechanics(Failure.BRITTLE, one, 0.3, one, one, -0.1, alpha, Double.NaN, s));
        assertThrows(IllegalArgumentException.class,
                () -> new Mechanics(Failure.GRANULAR, one, 0.3, one, one, 0.5, alpha, Double.NaN, s));
        assertThrows(IllegalArgumentException.class,
                () -> new Mechanics(Failure.BRITTLE, one, 0.3, zero, one, 0.5, alpha, Double.NaN, s));
        assertThrows(IllegalArgumentException.class,
                () -> new Mechanics(Failure.BRITTLE, zero, 0.3, one, one, 0.5, alpha, Double.NaN, s));
        assertThrows(IllegalArgumentException.class,
                () -> new Mechanics(Failure.BRITTLE, one, 0.3, one, one, 0.5, alpha, 0.0, s));
        assertThrows(IllegalArgumentException.class, () -> Material.builder("test:gas", "Gas")
                .composition(MaterialLibrary.AIR.composition())
                .region(MaterialLibrary.AIR.thermal().regions().get(0))
                .mechanics(MaterialLibrary.GLASS.mechanics())
                .build());
    }
}
