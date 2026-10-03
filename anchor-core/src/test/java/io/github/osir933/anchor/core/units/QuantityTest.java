package io.github.osir933.anchor.core.units;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class QuantityTest {

    @Test
    void temperaturesConvertWithOffsets() {
        assertEquals(273.15, Units.CELSIUS.toSi(0.0), 1e-12);
        assertEquals(373.15, Quantity.of(212, Units.FAHRENHEIT).in(Units.KELVIN), 1e-9);
        assertEquals(-40.0, Quantity.of(-40, Units.CELSIUS).in(Units.FAHRENHEIT), 1e-9);
    }

    @Test
    void dimensionsMultiplyAndDivide() {
        Quantity force = Quantity.of(10, Units.KILOGRAM).times(Quantity.of(9.81, Units.METRE)
                .over(Quantity.of(1, Units.SECOND).times(Quantity.of(1, Units.SECOND))));
        assertEquals(Dimension.FORCE, force.dimension());
        assertEquals(98.1, force.in(Units.NEWTON), 1e-12);
        assertEquals("kg·m·s⁻²", Dimension.FORCE.toString());
        assertEquals(Dimension.ENERGY, Dimension.FORCE.times(Dimension.LENGTH));
    }

    @Test
    void addingDifferentDimensionsFails() {
        assertThrows(IllegalArgumentException.class,
                () -> Quantity.of(1, Units.METRE).plus(Quantity.of(1, Units.SECOND)));
    }

    @Test
    void formattingIsLocaleFree() {
        assertEquals("1538 °C", Quantity.of(1811.15, Units.KELVIN).format(Units.CELSIUS, 4));
        assertEquals("101.3 kPa", Quantity.of(1, Units.ATMOSPHERE).format(Units.KILOPASCAL, 4));
        assertEquals("0 J", Quantity.of(0, Units.JOULE).format(Units.JOULE, 3));
    }

    @Test
    void constantsAreTheSi2019Values() {
        assertEquals(1.380649e-23, PhysicalConstants.BOLTZMANN);
        assertEquals(6.02214076e23, PhysicalConstants.AVOGADRO);
        assertEquals(101325.0, PhysicalConstants.STANDARD_ATMOSPHERE);
    }
}
