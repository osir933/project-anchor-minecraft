package io.github.osir933.anchor.core.matter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FormulaTest {

    @ParameterizedTest
    @CsvSource({
        "H2O, 18.015",
        "'Ca(OH)2', 74.092",
        "CuSO4·5H2O, 249.677",
        "CuSO4*5H2O, 249.677",
        "Fe0.95O, 69.05175",
        "'K4[Fe(CN)6]', 368.345"
    })
    void molarMassesMatchStandardAtomicWeights(String formula, double gramsPerMole) {
        assertEquals(gramsPerMole / 1000, Formula.parse(formula).molarMassKgPerMol(), 1e-9);
    }

    @Test
    void countsAreKeptPerElement() {
        Formula f = Formula.parse("CH3COOH");
        assertEquals(2.0, f.count(Element.C));
        assertEquals(4.0, f.count(Element.H));
        assertEquals(2.0, f.count(Element.O));
        assertEquals(0.0, f.count(Element.N));
    }

    @Test
    void massFractionsSumToOne() {
        double sum = Formula.parse("CaAl2Si2O8").elementMassFractions().values().stream()
                .mapToDouble(Double::doubleValue).sum();
        assertEquals(1.0, sum, 1e-12);
    }

    @Test
    void invalidFormulasAreRejected() {
        for (String bad : new String[] {"", "h2o", "H2O)", "(H2O", "Xx2", "H-1"}) {
            assertThrows(IllegalArgumentException.class, () -> Formula.parse(bad), bad);
        }
    }
}
