package io.github.osir933.anchor.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ThermalScaleTest {

    private static double brightness(int rgb) {
        return 0.2126 * (rgb >> 16 & 0xFF) + 0.7152 * (rgb >> 8 & 0xFF) + 0.0722 * (rgb & 0xFF);
    }

    @Test
    void colorsRunFromDarkVioletToNearWhite() {
        assertEquals(0x14003C, ThermalScale.color(0.0));
        assertEquals(0xFFFFE6, ThermalScale.color(1.0));
        assertEquals(0xE6321E, ThermalScale.color(0.55), "red sits a little above the middle");
    }

    @Test
    void hotterIsAlwaysBrighter() {
        double previous = -1.0;
        for (int i = 0; i <= 100; i++) {
            double now = brightness(ThermalScale.color(i / 100.0));
            assertTrue(now > previous, "colour " + i + "% is no brighter than the one before");
            previous = now;
        }
    }

    @Test
    void placesOffTheScaleTakeTheNearestEnd() {
        assertEquals(ThermalScale.color(0.0), ThermalScale.color(-3.0));
        assertEquals(ThermalScale.color(1.0), ThermalScale.color(7.0));
        assertEquals(ThermalScale.color(0.0), ThermalScale.color(Double.NaN));
    }

    @Test
    void theFirstImageSetsTheSpan() {
        ThermalScale scale = new ThermalScale();
        assertFalse(scale.ready());
        scale.follow(290.0, 600.0);
        assertTrue(scale.ready());
        assertEquals(290.0, scale.low());
        assertEquals(600.0, scale.high());
        assertEquals(ThermalScale.color(1.0), scale.colorOf(600.0));
        assertEquals(ThermalScale.color(0.5), scale.colorOf(445.0));
    }

    @Test
    void aViewAtOneTemperatureStillSpansTheNarrowestRange() {
        ThermalScale scale = new ThermalScale();
        scale.follow(290.0, 290.0);
        assertEquals(289.0, scale.low());
        assertEquals(291.0, scale.high());
        assertEquals(ThermalScale.color(0.5), scale.colorOf(290.0));
    }

    @Test
    void theSpanWidensAtOnceAndNarrowsSlowly() {
        ThermalScale scale = new ThermalScale();
        scale.follow(290.0, 300.0);
        scale.follow(280.0, 400.0);
        assertEquals(280.0, scale.low(), "a colder thing in view widens the span at once");
        assertEquals(400.0, scale.high(), "so does a hotter one");
        scale.follow(290.0, 300.0);
        assertEquals(282.5, scale.low(), 1e-9, "the span narrows a quarter of the way per image");
        assertEquals(375.0, scale.high(), 1e-9);
        for (int i = 0; i < 100; i++) {
            scale.follow(290.0, 300.0);
        }
        assertEquals(290.0, scale.low(), 1e-6, "and in the end fits the view");
        assertEquals(300.0, scale.high(), 1e-6);
    }

    @Test
    void imagesWithNothingInThemLeaveTheScaleAlone() {
        ThermalScale scale = new ThermalScale();
        scale.follow(Double.NaN, Double.NaN);
        assertFalse(scale.ready());
        scale.follow(290.0, 300.0);
        scale.follow(Double.NaN, Double.NaN);
        assertEquals(290.0, scale.low());
        assertEquals(300.0, scale.high());
    }

    @Test
    void aLockedScaleKeepsItsSpan() {
        ThermalScale scale = new ThermalScale();
        scale.lock(true);
        assertFalse(scale.locked(), "there is no span to keep yet");
        scale.follow(290.0, 300.0);
        scale.lock(true);
        assertTrue(scale.locked());
        scale.follow(200.0, 900.0);
        scale.reset();
        assertEquals(290.0, scale.low());
        assertEquals(300.0, scale.high());
        scale.lock(false);
        scale.follow(200.0, 900.0);
        assertEquals(200.0, scale.low());
        assertEquals(900.0, scale.high());
        scale.reset();
        assertFalse(scale.ready(), "a free scale forgets its span when reset");
    }
}
