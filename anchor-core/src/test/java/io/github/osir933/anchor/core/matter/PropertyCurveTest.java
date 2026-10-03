package io.github.osir933.anchor.core.matter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PropertyCurveTest {

    private static final PropertyCurve HUMP = PropertyCurve.table(MaterialLibrary.PROPERTY_ESTIMATE,
            100.0, 1.0, 200.0, 5.0, 300.0, 2.0);

    @Test
    void interpolatesBetweenPointsAndHoldsTheEnds() {
        assertEquals(3.0, HUMP.at(150.0), 1e-12);
        assertEquals(1.0, HUMP.at(50.0));
        assertEquals(2.0, HUMP.at(400.0));
    }

    @Test
    void extremesIncludeThePointsBetweenTheLimits() {
        assertEquals(5.0, HUMP.max(150.0, 250.0), "the peak lies between the limits");
        assertEquals(5.0, HUMP.max(250.0, 150.0), "in either order");
        assertEquals(3.0, HUMP.min(150.0, 250.0), 1e-12);
        assertEquals(4.2, HUMP.max(120.0, 180.0), 1e-12, "a rising stretch peaks at its upper limit");
        assertEquals(1.0, HUMP.min(0.0, 500.0), "beyond the table the ends hold");
        assertEquals(3.0, HUMP.max(150.0, 150.0), 1e-12);
        PropertyCurve flat = PropertyCurve.constant(7.0, MaterialLibrary.PROPERTY_ESTIMATE);
        assertEquals(7.0, flat.max(10.0, 1000.0));
        assertEquals(7.0, flat.min(10.0, 1000.0));
    }
}
