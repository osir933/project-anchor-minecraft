package io.github.osir933.anchor.core.matter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * How a solid carries loads and how it gives way: its stiffness, strength, friction and thermal expansion, the
 * first three as functions of temperature, so that hot steel softens and a fire can bring a beam down.
 *
 * <p>Strengths are positive stresses. What they mean depends on how the material {@linkplain Failure fails}: a
 * brittle material cracks when its largest principal stress reaches its tensile strength, or crushes when its
 * smallest reaches minus its compressive strength; a ductile one gives way when a whole cross-section has
 * yielded, at its yield strength in either direction; a granular one has no strength in tension at all and
 * holds together only by friction under pressure.
 *
 * <p>The values describe the solid as a whole block of it, the same in every direction. Wood, slate and other
 * materials with a grain are stronger along it than across it; for those the values are a stand-in that the
 * material's notes explain.
 *
 * @param failure how the material gives way
 * @param stiffness Young's modulus, in pascals
 * @param poissonRatio Poisson's ratio, from 0 up to but not including 0.5
 * @param tension the tensile strength of a brittle material or the yield strength of a ductile one, in pascals;
 *     zero for a granular material
 * @param compression the compressive strength of a brittle or granular material or the yield strength of a
 *     ductile one, in pascals
 * @param friction the coefficient of static friction of the material on itself, which holds cracked and granular
 *     matter together under pressure
 * @param expansion the linear thermal expansion coefficient, per kelvin
 * @param toughness the fracture toughness K<sub>IC</sub>, in Pa·m<sup>1/2</sup>, or {@link Double#NaN} where
 *     cracks do not decide how the material fails, as in ductile metals
 * @param source where the Poisson ratio, friction and toughness come from; each curve names its own source
 */
public record Mechanics(Failure failure, PropertyCurve stiffness, double poissonRatio, PropertyCurve tension,
        PropertyCurve compression, double friction, PropertyCurve expansion, double toughness, Source source) {

    /** How a solid gives way under load. */
    public enum Failure {
        /** Cracks without warning when a principal stress reaches its tensile or compressive strength. */
        BRITTLE,
        /** Yields and bends; gives way once a whole cross-section has yielded. */
        DUCTILE,
        /** Has no tensile strength; holds together only by friction under pressure, like sand. */
        GRANULAR
    }

    /**
     * Validates the description.
     *
     * @param failure how the material gives way
     * @param stiffness Young's modulus
     * @param poissonRatio Poisson's ratio
     * @param tension the tensile or yield strength
     * @param compression the compressive or yield strength
     * @param friction the friction coefficient
     * @param expansion the expansion coefficient
     * @param toughness the fracture toughness, or NaN
     * @param source the source of the scalar values
     */
    public Mechanics {
        Objects.requireNonNull(failure, "failure");
        Objects.requireNonNull(stiffness, "stiffness");
        Objects.requireNonNull(tension, "tension");
        Objects.requireNonNull(compression, "compression");
        Objects.requireNonNull(expansion, "expansion");
        Objects.requireNonNull(source, "source");
        if (!(poissonRatio >= 0 && poissonRatio < 0.5)) {
            throw new IllegalArgumentException("Poisson's ratio must lie in [0, 0.5): " + poissonRatio);
        }
        if (!(friction >= 0 && Double.isFinite(friction))) {
            throw new IllegalArgumentException("friction must be finite and non-negative: " + friction);
        }
        if (!Double.isNaN(toughness) && !(toughness > 0 && Double.isFinite(toughness))) {
            throw new IllegalArgumentException("toughness must be positive or NaN: " + toughness);
        }
        if (!(stiffness.at(REFERENCE_K) > 0)) {
            throw new IllegalArgumentException("Young's modulus must be positive at 20 °C");
        }
        if (failure == Failure.GRANULAR && tension.at(REFERENCE_K) != 0) {
            throw new IllegalArgumentException("a granular material has no tensile strength");
        }
        if (failure != Failure.GRANULAR && !(tension.at(REFERENCE_K) > 0)) {
            throw new IllegalArgumentException("a " + failure + " material needs a tensile strength at 20 °C");
        }
        if (!(compression.at(REFERENCE_K) > 0)) {
            throw new IllegalArgumentException("the compressive strength must be positive at 20 °C");
        }
    }

    /** The temperature most mechanical data are measured at, 20 °C, in kelvin. */
    public static final double REFERENCE_K = 293.15;

    /**
     * Returns Young's modulus at a temperature.
     *
     * @param temperatureK the temperature in kelvin
     * @return the modulus in pascals, never negative
     */
    public double youngsModulus(double temperatureK) {
        return Math.max(0.0, stiffness.at(temperatureK));
    }

    /**
     * Returns the shear modulus at a temperature, from Young's modulus and Poisson's ratio as for an isotropic
     * solid.
     *
     * @param temperatureK the temperature in kelvin
     * @return the modulus in pascals, never negative
     */
    public double shearModulus(double temperatureK) {
        return youngsModulus(temperatureK) / (2.0 * (1.0 + poissonRatio));
    }

    /**
     * Returns the tensile strength, or the yield strength of a ductile material, at a temperature.
     *
     * @param temperatureK the temperature in kelvin
     * @return the strength in pascals, never negative
     */
    public double tensileStrength(double temperatureK) {
        return Math.max(0.0, tension.at(temperatureK));
    }

    /**
     * Returns the compressive strength, or the yield strength of a ductile material, at a temperature.
     *
     * @param temperatureK the temperature in kelvin
     * @return the strength in pascals, never negative
     */
    public double compressiveStrength(double temperatureK) {
        return Math.max(0.0, compression.at(temperatureK));
    }

    /**
     * Returns how much a free piece of the material lengthens per unit length when it warms from one temperature
     * to another; negative when it cools.
     *
     * @param fromK the starting temperature in kelvin
     * @param toK the final temperature in kelvin
     * @return the thermal strain, dimensionless
     */
    public double thermalStrain(double fromK, double toK) {
        return expansion.integrate(fromK, toK);
    }

    /**
     * Returns every source this description refers to, keyed by source key.
     *
     * @return the sources, in order of first use
     */
    public Map<String, Source> sources() {
        Map<String, Source> sources = new LinkedHashMap<>();
        for (PropertyCurve c : List.of(stiffness, tension, compression, expansion)) {
            sources.putIfAbsent(c.source().key(), c.source());
        }
        sources.putIfAbsent(source.key(), source);
        return sources;
    }
}
