package io.github.osir933.anchor.core.physics.thermal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.osir933.anchor.core.physics.thermal.Incandescence.Glow;
import io.github.osir933.anchor.core.physics.thermal.Incandescence.Tristimulus;
import org.junit.jupiter.api.Test;

class IncandescenceTest {

    @Test
    void platinumPointHasTheOldCandelaLuminance() {
        // From 1948 to 1979 the candela was 1/600 000 m² of a black body at the freezing point of platinum.
        assertEquals(6.0e5, Incandescence.blackBodyLuminance(2042.0), 6.0e3);
    }

    @Test
    void lightHasTheChromaticityOfStandardIlluminants() {
        // CIE standard illuminant A is a black body at 2856 K.
        Tristimulus a = Incandescence.tristimulus(2856.0);
        assertEquals(0.44757, a.chromaticityX(), 0.002);
        assertEquals(0.40745, a.chromaticityY(), 0.002);
        // The Planckian locus near daylight, at 6504 K.
        Tristimulus daylight = Incandescence.tristimulus(6504.0);
        assertEquals(0.3135, daylight.chromaticityX(), 0.002);
        assertEquals(0.3236, daylight.chromaticityY(), 0.002);
    }

    @Test
    void planckLawFollowsWienDisplacement() {
        double kelvin = 1500.0;
        double peak = 2.897771955e-3 / kelvin;
        double atPeak = Incandescence.spectralRadiance(peak, kelvin);
        assertTrue(atPeak > Incandescence.spectralRadiance(peak * 0.99, kelvin));
        assertTrue(atPeak > Incandescence.spectralRadiance(peak * 1.01, kelvin));
        assertEquals(0.0, Incandescence.spectralRadiance(600e-9, 0.0));
        assertEquals(0.0, Incandescence.spectralRadiance(600e-9, -5.0));
    }

    @Test
    void nothingGlowsBelowTheDraperPoint() {
        assertSame(Incandescence.NONE, Incandescence.glow(300.0, 1.0));
        assertSame(Incandescence.NONE, Incandescence.glow(700.0, 1.0));
        assertSame(Incandescence.NONE, Incandescence.glow(0.0, 1.0));
        assertSame(Incandescence.NONE, Incandescence.glow(Double.NaN, 1.0));
        assertFalse(Incandescence.NONE.visible());
    }

    @Test
    void glowGrowsFromDullRedToFullBrightness() {
        Glow dull = Incandescence.glow(800.0, 1.0);
        assertTrue(dull.visible());
        assertTrue(dull.level() < 0.1, () -> "800 K glows at " + dull.level());
        assertEquals(1.0, Incandescence.glow(1600.0, 1.0).level());
        assertEquals(1.0, Incandescence.glow(1.0e6, 1.0).level());
        double previous = 0.0;
        for (double kelvin = 500.0; kelvin <= 3000.0; kelvin += 7.0) {
            double level = Incandescence.glow(kelvin, 1.0).level();
            assertTrue(level >= previous, "the glow dims at " + kelvin + " K");
            previous = level;
        }
    }

    @Test
    void coloursRunFromRedThroughOrangeToWhite() {
        Glow red = Incandescence.glow(1000.0, 1.0);
        assertEquals(1.0, red.red(), 1e-9);
        assertTrue(red.green() < 0.3, () -> "green at 1000 K: " + red.green());
        assertEquals(0.0, red.blue(), 1e-9);

        Glow warm = Incandescence.glow(3000.0, 1.0);
        assertEquals(1.0, warm.red(), 1e-9);
        assertEquals(0.725, warm.green(), 0.02);
        assertEquals(0.43, warm.blue(), 0.03);

        Glow white = Incandescence.glow(6500.0, 1.0);
        assertTrue(white.red() > 0.95 && white.green() > 0.95 && white.blue() > 0.95, white::toString);
    }

    @Test
    void emissivityDimsTheGlowButKeepsItsColour() {
        Glow black = Incandescence.glow(1000.0, 1.0);
        Glow grey = Incandescence.glow(1000.0, 0.3);
        double span = Incandescence.LOG_LUMINANCE_LEVEL_ONE - Incandescence.LOG_LUMINANCE_LEVEL_ZERO;
        assertEquals(black.level() + StrictMath.log10(0.3) / span, grey.level(), 1e-12);
        assertEquals(black.red(), grey.red());
        assertEquals(black.green(), grey.green());
        assertEquals(black.blue(), grey.blue());
        assertSame(Incandescence.NONE, Incandescence.glow(1000.0, 0.0));
        assertEquals(black, Incandescence.glow(1000.0, 1.5));
    }

    @Test
    void tableAgreesWithTheFullSum() {
        for (double kelvin = 790.0; kelvin <= 3000.0; kelvin += 37.0) {
            double exact = Incandescence.level(Incandescence.blackBodyLuminance(kelvin));
            assertEquals(exact, Incandescence.glow(kelvin, 1.0).level(), 0.005, "at " + kelvin + " K");
        }
    }

    @Test
    void levelFollowsTheLogarithmOfLuminance() {
        assertEquals(0.0, Incandescence.level(0.0));
        assertEquals(0.0, Incandescence.level(StrictMath.pow(10.0, Incandescence.LOG_LUMINANCE_LEVEL_ZERO)));
        assertEquals(1.0, Incandescence.level(StrictMath.pow(10.0, Incandescence.LOG_LUMINANCE_LEVEL_ONE)));
        double middle = (Incandescence.LOG_LUMINANCE_LEVEL_ZERO + Incandescence.LOG_LUMINANCE_LEVEL_ONE) / 2;
        assertEquals(0.5, Incandescence.level(StrictMath.pow(10.0, middle)), 1e-12);
        assertEquals(1.0, Incandescence.level(1.0e9));
    }

    @Test
    void glowsFromKIsWhereTheGlowBegins() {
        for (double emissivity : new double[] {1.0, 0.5, 0.1, 0.02}) {
            double from = Incandescence.glowsFromK(emissivity);
            assertFalse(Incandescence.glow(from, emissivity).visible(), "glows at " + from + " K");
            assertTrue(Incandescence.glow(from + Incandescence.TABLE_STEP_K, emissivity).visible(),
                    "dark at " + (from + Incandescence.TABLE_STEP_K) + " K");
        }
        assertEquals(775.0, Incandescence.glowsFromK(1.0), 10.0);
        assertTrue(Incandescence.glowsFromK(0.1) > Incandescence.glowsFromK(1.0));
        assertEquals(Double.POSITIVE_INFINITY, Incandescence.glowsFromK(0.0));
    }

    @Test
    void argbPacksTheLevelAsAlpha() {
        assertEquals(0x40FF8000, new Glow(1.0, 0.5, 0.0, 0.25).argb());
        assertEquals(0xFFFF0080, new Glow(2.0, -1.0, 0.5, 1.0).argb());
        assertEquals(0, Incandescence.NONE.argb());
    }
}
