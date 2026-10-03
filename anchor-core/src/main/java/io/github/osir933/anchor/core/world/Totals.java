package io.github.osir933.anchor.core.world;

import io.github.osir933.anchor.core.matter.Element;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * The conserved totals of a world, or of a change to it.
 *
 * @param mass the total mass in kilograms
 * @param enthalpy the total enthalpy in joules
 * @param absoluteEnthalpy the sum of the cells' absolute enthalpies, the scale for judging rounding
 * @param elementMasses the mass of each chemical element, in kilograms
 */
public record Totals(double mass, double enthalpy, double absoluteEnthalpy, Map<Element, Double> elementMasses) {

    /** Nothing at all. */
    public static final Totals ZERO = new Totals(0, 0, 0, new EnumMap<>(Element.class));

    /**
     * Copies the element masses.
     *
     * @param mass the mass
     * @param enthalpy the enthalpy
     * @param absoluteEnthalpy the absolute enthalpy scale
     * @param elementMasses the element masses
     */
    public Totals {
        EnumMap<Element, Double> copy = new EnumMap<>(Element.class);
        copy.putAll(elementMasses);
        elementMasses = Collections.unmodifiableMap(copy);
    }

    /**
     * Returns the mass of one element.
     *
     * @param element the element
     * @return its mass in kilograms, zero if absent
     */
    public double elementMass(Element element) {
        return elementMasses.getOrDefault(element, 0.0);
    }
}
