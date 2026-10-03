package io.github.osir933.anchor.core.matter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CompositionTest {

    @Test
    void theResultDoesNotDependOnInputOrder() {
        List<Species> species = List.of(SpeciesCatalog.NITROGEN, SpeciesCatalog.OXYGEN, SpeciesCatalog.ARGON,
                SpeciesCatalog.CARBON_DIOXIDE);
        double[] fractions = {0.7552, 0.2314, 0.0129, 0.0005};
        Map<Species, Double> forward = new LinkedHashMap<>();
        Map<Species, Double> backward = new LinkedHashMap<>();
        for (int i = 0; i < species.size(); i++) {
            forward.put(species.get(i), fractions[i]);
        }
        for (int i = species.size() - 1; i >= 0; i--) {
            backward.put(species.get(i), fractions[i]);
        }
        Composition a = Composition.ofMassFractions(forward);
        Composition b = Composition.ofMassFractions(backward);
        for (Species s : species) {
            assertEquals(Double.doubleToRawLongBits(a.massFraction(s)), Double.doubleToRawLongBits(b.massFraction(s)));
        }
        assertEquals(a.elementMassFractions(), b.elementMassFractions());
    }

    @Test
    void componentsAreSortedAndNormalised() {
        Composition c = Composition.builder()
                .add(SpeciesCatalog.OXYGEN, 1.0)
                .add(SpeciesCatalog.NITROGEN, 3.0e-7)
                .build();
        assertEquals(SpeciesCatalog.NITROGEN, c.components().get(0).species());
        double sum = c.components().stream().mapToDouble(Composition.Component::massFraction).sum();
        assertEquals(1.0, sum, 1e-15);
    }

    @Test
    void waterIsElevenPercentHydrogenByMass() {
        Composition water = Composition.pure(SpeciesCatalog.WATER);
        assertEquals(2 * 1.008 / 18.015, water.elementMassFractions().get(Element.H), 1e-12);
    }

    @Test
    void fractionsThatDoNotSumToOneAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Composition.builder()
                .add(SpeciesCatalog.OXYGEN, 0.5).add(SpeciesCatalog.NITROGEN, 0.4).build());
        assertThrows(IllegalArgumentException.class, () -> Composition.builder()
                .add(SpeciesCatalog.OXYGEN, 0.5).add(SpeciesCatalog.OXYGEN, 0.5));
    }

    @Test
    void mixingConservesEachSpecies() {
        Composition air = MaterialLibrary.AIR.composition();
        Composition water = Composition.pure(SpeciesCatalog.WATER);
        Composition humid = Composition.mix(air, 99.0, water, 1.0);
        assertEquals(0.01, humid.massFraction(SpeciesCatalog.WATER), 1e-12);
        assertEquals(0.99 * air.massFraction(SpeciesCatalog.NITROGEN), humid.massFraction(SpeciesCatalog.NITROGEN),
                1e-12);
    }
}
