package io.github.osir933.anchor.core.physics.thermal;

import io.github.osir933.anchor.core.units.PhysicalConstants;

/**
 * The light a hot surface gives off by its temperature alone. From about 525 °C, the Draper point, a body glows
 * dull red in the dark, then brighter, through orange and yellow to white as it grows hotter.
 *
 * <p>Planck's law gives a black body's spectral radiance. Weighted with the CIE 1931 colour matching functions, in
 * the multi-lobe fit of Wyman, Sloan and Shirley (2013), and summed from 360 to 830 nm in 5 nm steps, it gives the
 * tristimulus values X, Y and Z of the light, and 683 lm/W times Y its luminance: 6.0 × 10⁵ cd/m² at the freezing
 * point of platinum, 2042 K, as the candela's old definition had it. A grey body gives off its emissivity times
 * that, in the same colour.
 *
 * <p>For a screen, the colour becomes sRGB (IEC 61966-2-1): clipped to what sRGB can show, scaled so that its
 * brightest channel is 1, and gamma encoded. The luminance becomes a glow level from 0 to 1 on a logarithmic scale,
 * which is how the eye judges brightness: 0 at 10<sup>−2.5</sup> cd/m², where a black body of about 775 K is just
 * seen in the dark, and 1 from 10<sup>4</sup> cd/m², which it reaches at about 1520 K, bright yellow-orange. Both
 * come from a table of black bodies every {@value #TABLE_STEP_K} K up to {@value #TABLE_MAX_K} K, interpolated, so
 * they are cheap enough to work out for every spot of a glowing surface.
 */
public final class Incandescence {

    /** The luminous efficacy that turns a tristimulus value Y in W/(sr·m²) into cd/m². */
    public static final double LUMINOUS_EFFICACY = 683.0;

    /** The base-10 logarithm of the luminance, in cd/m², at which the glow level is 0. */
    public static final double LOG_LUMINANCE_LEVEL_ZERO = -2.5;

    /** The base-10 logarithm of the luminance, in cd/m², from which the glow level is 1. */
    public static final double LOG_LUMINANCE_LEVEL_ONE = 4.0;

    /** The temperature step of the table of black bodies, in K. */
    public static final int TABLE_STEP_K = 10;

    /** The hottest black body in the table, in K; hotter bodies are shown as this one. */
    public static final int TABLE_MAX_K = 12_000;

    /** No glow at all. */
    public static final Glow NONE = new Glow(0.0, 0.0, 0.0, 0.0);

    private static final double FIRST_NM = 360.0;
    private static final double STEP_NM = 5.0;
    private static final int SAMPLES = 95;

    /** The second radiation constant hc/k, in m·K. */
    private static final double C2 = PhysicalConstants.PLANCK * PhysicalConstants.SPEED_OF_LIGHT
            / PhysicalConstants.BOLTZMANN;

    /** Twice hc², the factor of Planck's law for radiance, in W·m²/sr. */
    private static final double C1 = 2.0 * PhysicalConstants.PLANCK * PhysicalConstants.SPEED_OF_LIGHT
            * PhysicalConstants.SPEED_OF_LIGHT;

    private static final double[] X_BAR = new double[SAMPLES];
    private static final double[] Y_BAR = new double[SAMPLES];
    private static final double[] Z_BAR = new double[SAMPLES];

    /** The base-10 logarithm of each tabled black body's luminance in cd/m², or negative infinity for none. */
    private static final double[] LOG_LUMINANCE;
    /** The gamma-encoded sRGB colour of each tabled black body, three channels each. */
    private static final double[] COLOUR;

    static {
        for (int i = 0; i < SAMPLES; i++) {
            double nm = FIRST_NM + i * STEP_NM;
            X_BAR[i] = 1.056 * lobe(nm, 599.8, 37.9, 31.0) + 0.362 * lobe(nm, 442.0, 16.0, 26.7)
                    - 0.065 * lobe(nm, 501.1, 20.4, 26.2);
            Y_BAR[i] = 0.821 * lobe(nm, 568.8, 46.9, 40.5) + 0.286 * lobe(nm, 530.9, 16.3, 31.1);
            Z_BAR[i] = 1.217 * lobe(nm, 437.0, 11.8, 36.0) + 0.681 * lobe(nm, 459.0, 26.0, 13.8);
        }
        int entries = TABLE_MAX_K / TABLE_STEP_K + 1;
        LOG_LUMINANCE = new double[entries];
        COLOUR = new double[3 * entries];
        for (int e = 0; e < entries; e++) {
            Tristimulus t = tristimulus(e * TABLE_STEP_K);
            double luminance = LUMINOUS_EFFICACY * t.y();
            LOG_LUMINANCE[e] = luminance > 0 ? StrictMath.log10(luminance) : Double.NEGATIVE_INFINITY;
            double[] rgb = srgb(t);
            System.arraycopy(rgb, 0, COLOUR, 3 * e, 3);
        }
    }

    private Incandescence() {
    }

    /**
     * The colour of a body's light, as tristimulus values of the CIE 1931 standard observer.
     *
     * @param x the tristimulus value X, in W/(sr·m²)
     * @param y the tristimulus value Y, in W/(sr·m²), which 683 lm/W turns into luminance
     * @param z the tristimulus value Z, in W/(sr·m²)
     */
    public record Tristimulus(double x, double y, double z) {

        /**
         * Returns the chromaticity coordinate x.
         *
         * @return X / (X + Y + Z), or {@link Double#NaN} for no light
         */
        public double chromaticityX() {
            double sum = x + y + z;
            return sum > 0 ? x / sum : Double.NaN;
        }

        /**
         * Returns the chromaticity coordinate y.
         *
         * @return Y / (X + Y + Z), or {@link Double#NaN} for no light
         */
        public double chromaticityY() {
            double sum = x + y + z;
            return sum > 0 ? y / sum : Double.NaN;
        }
    }

    /**
     * How a surface glows, as a screen shows it.
     *
     * @param red the red channel of its colour, gamma-encoded sRGB from 0 to 1
     * @param green the green channel
     * @param blue the blue channel
     * @param level how strongly it glows, from 0 for not at all to 1 for as bright as a screen shows
     */
    public record Glow(double red, double green, double blue, double level) {

        /**
         * Tells whether the glow can be seen at all.
         *
         * @return {@code true} if its level is above 0
         */
        public boolean visible() {
            return level > 0;
        }

        /**
         * Packs the glow into one number: the level as alpha in the top byte, then red, green and blue.
         *
         * @return the packed colour, each part from 0 to 255
         */
        public int argb() {
            return channel(level) << 24 | channel(red) << 16 | channel(green) << 8 | channel(blue);
        }

        private static int channel(double value) {
            return (int) Math.round(Math.max(0.0, Math.min(1.0, value)) * 255.0);
        }
    }

    /**
     * Returns a black body's spectral radiance, by Planck's law.
     *
     * @param wavelengthM the wavelength, in m
     * @param kelvin the temperature, in K
     * @return the radiance per wavelength, in W/(sr·m³); 0 at or below absolute zero
     */
    public static double spectralRadiance(double wavelengthM, double kelvin) {
        if (!(kelvin > 0) || !(wavelengthM > 0)) {
            return 0.0;
        }
        double fifth = wavelengthM * wavelengthM;
        fifth = fifth * fifth * wavelengthM;
        return C1 / fifth / StrictMath.expm1(C2 / (wavelengthM * kelvin));
    }

    /**
     * Returns the colour of a black body's light.
     *
     * @param kelvin the temperature, in K
     * @return its tristimulus values; all 0 at or below absolute zero
     */
    public static Tristimulus tristimulus(double kelvin) {
        double x = 0;
        double y = 0;
        double z = 0;
        for (int i = 0; i < SAMPLES; i++) {
            double radiance = spectralRadiance((FIRST_NM + i * STEP_NM) * 1e-9, kelvin) * STEP_NM * 1e-9;
            x += radiance * X_BAR[i];
            y += radiance * Y_BAR[i];
            z += radiance * Z_BAR[i];
        }
        return new Tristimulus(x, y, z);
    }

    /**
     * Returns a black body's luminance, worked out in full rather than from the table.
     *
     * @param kelvin the temperature, in K
     * @return the luminance, in cd/m²
     */
    public static double blackBodyLuminance(double kelvin) {
        return LUMINOUS_EFFICACY * tristimulus(kelvin).y();
    }

    /**
     * Returns the glow level of a given luminance.
     *
     * @param luminance the luminance, in cd/m²
     * @return the level, from 0 to 1
     */
    public static double level(double luminance) {
        if (!(luminance > 0)) {
            return 0.0;
        }
        return levelOfLog(StrictMath.log10(luminance));
    }

    /**
     * Returns how a surface glows.
     *
     * @param kelvin the surface's temperature, in K
     * @param emissivity its emissivity, from 0 to 1, for the visible light it gives off
     * @return its glow, {@link #NONE} if it gives off no light worth showing
     */
    public static Glow glow(double kelvin, double emissivity) {
        if (!(kelvin > 0) || !(emissivity > 0)) {
            return NONE;
        }
        double at = Math.min(kelvin, TABLE_MAX_K) / TABLE_STEP_K;
        int below = Math.min((int) at, LOG_LUMINANCE.length - 2);
        double share = at - below;
        double lower = LOG_LUMINANCE[below];
        double upper = LOG_LUMINANCE[below + 1];
        if (upper == Double.NEGATIVE_INFINITY) {
            return NONE;
        }
        double log = lower == Double.NEGATIVE_INFINITY ? upper : lower + share * (upper - lower);
        double level = levelOfLog(log + StrictMath.log10(Math.min(1.0, emissivity)));
        if (level <= 0) {
            return NONE;
        }
        int a = 3 * below;
        int b = a + 3;
        return new Glow(COLOUR[a] + share * (COLOUR[b] - COLOUR[a]),
                COLOUR[a + 1] + share * (COLOUR[b + 1] - COLOUR[a + 1]),
                COLOUR[a + 2] + share * (COLOUR[b + 2] - COLOUR[a + 2]), level);
    }

    /**
     * Returns the lowest temperature at which a surface of a given emissivity glows at all, to within the table's
     * step.
     *
     * @param emissivity the emissivity, from 0 to 1
     * @return the temperature in K, or positive infinity if the surface never glows
     */
    public static double glowsFromK(double emissivity) {
        if (!(emissivity > 0)) {
            return Double.POSITIVE_INFINITY;
        }
        double needed = LOG_LUMINANCE_LEVEL_ZERO - StrictMath.log10(Math.min(1.0, emissivity));
        for (int e = 0; e < LOG_LUMINANCE.length; e++) {
            if (LOG_LUMINANCE[e] > needed) {
                return Math.max(0, e - 1) * (double) TABLE_STEP_K;
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    private static double levelOfLog(double log) {
        double level = (log - LOG_LUMINANCE_LEVEL_ZERO) / (LOG_LUMINANCE_LEVEL_ONE - LOG_LUMINANCE_LEVEL_ZERO);
        return Math.max(0.0, Math.min(1.0, level));
    }

    /** Converts tristimulus values to a gamma-encoded sRGB colour whose brightest channel is 1. */
    private static double[] srgb(Tristimulus t) {
        double r = Math.max(0.0, 3.2404542 * t.x() - 1.5371385 * t.y() - 0.4985314 * t.z());
        double g = Math.max(0.0, -0.9692660 * t.x() + 1.8760108 * t.y() + 0.0415560 * t.z());
        double b = Math.max(0.0, 0.0556434 * t.x() - 0.2040259 * t.y() + 1.0572252 * t.z());
        double brightest = Math.max(r, Math.max(g, b));
        if (!(brightest > 0)) {
            return new double[3];
        }
        return new double[] {encode(r / brightest), encode(g / brightest), encode(b / brightest)};
    }

    /** The sRGB transfer function, from linear light to the encoded value. */
    private static double encode(double linear) {
        return linear <= 0.0031308 ? 12.92 * linear : 1.055 * StrictMath.pow(linear, 1.0 / 2.4) - 0.055;
    }

    /** A Gaussian lobe with different widths below and above its centre, as the colour matching fit uses. */
    private static double lobe(double nm, double centre, double widthBelow, double widthAbove) {
        double t = (nm - centre) / (nm < centre ? widthBelow : widthAbove);
        return StrictMath.exp(-0.5 * t * t);
    }
}
