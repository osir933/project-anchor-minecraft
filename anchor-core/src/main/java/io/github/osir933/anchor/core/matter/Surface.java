package io.github.osir933.anchor.core.matter;

import java.util.Objects;

/**
 * How a phase of a material meets sunlight and the open air: how much sunlight its surface reflects, how much
 * gets through it, and how readily water evaporates from it.
 *
 * <p>Sunlight is taken in two bands. Blue-green light, about two fifths of the energy, crosses clear water for
 * tens of metres; red and near-infrared light, the rest, is gone within the first metre. The split follows
 * Paulson and Simpson's two-band fit for clear ocean water, and each material says what fraction of each band
 * crosses one metre of it once inside, past the light its surface reflects: zero for anything opaque, close to
 * one for a window.
 *
 * <p>Evaporation is limited by the air, which carries vapour away, and by the surface, which may hold its water
 * back: open water and ice hold back nothing, the pores of moist soil and the closing stomata of leaves hold back
 * some, and a dry surface such as rock lets nothing evaporate. The surface resistance is in seconds per metre,
 * as in the Penman-Monteith equation.
 *
 * @param albedo the fraction of the sunlight falling on the surface that it reflects, from 0 to 1
 * @param blueGreenTransmittance the fraction of the blue-green sunlight inside the material that crosses one
 *     metre of it
 * @param redInfraredTransmittance the fraction of the red and near-infrared sunlight inside the material that
 *     crosses one metre of it
 * @param evaporationResistance how strongly the surface holds back the water evaporating from it, in s/m: 0 for
 *     open water or ice, {@link Double#POSITIVE_INFINITY} for a surface with no water to give
 * @param source where the values come from
 */
public record Surface(double albedo, double blueGreenTransmittance, double redInfraredTransmittance,
        double evaporationResistance, Source source) {

    /** The share of the energy in sunlight that falls in the blue-green band, which crosses water most easily. */
    public static final double BLUE_GREEN_SHARE = 0.42;

    /** No evaporation: the surface holds no water to give. */
    public static final double DRY = Double.POSITIVE_INFINITY;

    /**
     * Validates the surface.
     *
     * @param albedo the reflected fraction
     * @param blueGreenTransmittance the blue-green transmittance per metre
     * @param redInfraredTransmittance the red and near-infrared transmittance per metre
     * @param evaporationResistance the surface resistance to evaporation
     * @param source the source
     */
    public Surface {
        Objects.requireNonNull(source, "source");
        if (!(albedo >= 0 && albedo <= 1)) {
            throw new IllegalArgumentException("albedo must lie between 0 and 1: " + albedo);
        }
        if (!(blueGreenTransmittance >= 0 && blueGreenTransmittance <= 1)
                || !(redInfraredTransmittance >= 0 && redInfraredTransmittance <= 1)) {
            throw new IllegalArgumentException("transmittances must lie between 0 and 1: " + blueGreenTransmittance
                    + ", " + redInfraredTransmittance);
        }
        if (!(evaporationResistance >= 0)) {
            throw new IllegalArgumentException("evaporation resistance must not be negative: "
                    + evaporationResistance);
        }
    }

    /**
     * Returns a surface that lets no sunlight through and gives off no water.
     *
     * @param albedo the reflected fraction
     * @param source the source
     * @return the surface
     */
    public static Surface opaque(double albedo, Source source) {
        return new Surface(albedo, 0.0, 0.0, DRY, source);
    }

    /**
     * Returns this surface with a different resistance to evaporation.
     *
     * @param resistance the surface resistance in s/m, or {@link #DRY}
     * @return the new surface
     */
    public Surface evaporating(double resistance) {
        return new Surface(albedo, blueGreenTransmittance, redInfraredTransmittance, resistance, source);
    }

    /**
     * Returns this surface with a different albedo, as for a block painted or dyed another colour.
     *
     * @param newAlbedo the reflected fraction
     * @return the new surface
     */
    public Surface withAlbedo(double newAlbedo) {
        return new Surface(newAlbedo, blueGreenTransmittance, redInfraredTransmittance, evaporationResistance,
                source);
    }

    /**
     * Tells whether some sunlight gets through the material.
     *
     * @return {@code true} if either band crosses it
     */
    public boolean translucent() {
        return blueGreenTransmittance > 0 || redInfraredTransmittance > 0;
    }

    /**
     * Tells whether water evaporates from the surface.
     *
     * @return {@code true} if the evaporation resistance is finite
     */
    public boolean wet() {
        return evaporationResistance < DRY;
    }
}
