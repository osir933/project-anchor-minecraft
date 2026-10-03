package io.github.osir933.anchor.core.world;

import io.github.osir933.anchor.core.math.CompensatedSum;
import io.github.osir933.anchor.core.matter.Element;
import io.github.osir933.anchor.core.space.SectionPos;
import java.util.EnumMap;
import java.util.Map;

/** Adds up cell states into {@link Totals}, in the order they are given. */
final class TotalsBuilder {

    private static final Element[] ELEMENTS = Element.values();

    private final PhysicalWorld world;
    private final CompensatedSum mass = new CompensatedSum();
    private final CompensatedSum enthalpy = new CompensatedSum();
    private final CompensatedSum absoluteEnthalpy = new CompensatedSum();
    private CompensatedSum[] byMaterial;

    TotalsBuilder(PhysicalWorld world) {
        this.world = world;
        this.byMaterial = new CompensatedSum[world.materials().size()];
    }

    /** Adds {@code count} copies of a state, with a sign of +1 for matter added and -1 for matter removed. */
    TotalsBuilder add(CellState state, double count) {
        double m = state.mass() * count;
        double h = state.enthalpy() * count;
        mass.add(m);
        enthalpy.add(h);
        absoluteEnthalpy.add(Math.abs(h));
        int material = state.material();
        if (material >= byMaterial.length) {
            CompensatedSum[] grown = new CompensatedSum[world.materials().size()];
            System.arraycopy(byMaterial, 0, grown, 0, byMaterial.length);
            byMaterial = grown;
        }
        if (byMaterial[material] == null) {
            byMaterial[material] = new CompensatedSum();
        }
        byMaterial[material].add(m);
        return this;
    }

    TotalsBuilder addSection(Section section, double sign) {
        if (section.isUniform()) {
            return add(section.blockState(0), sign * SectionPos.BLOCKS);
        }
        section.visitLeafStates(state -> add(state, sign));
        return this;
    }

    Totals build() {
        Map<Element, CompensatedSum> elements = new EnumMap<>(Element.class);
        for (int material = 0; material < byMaterial.length; material++) {
            if (byMaterial[material] == null) {
                continue;
            }
            double m = byMaterial[material].value();
            double[] fractions = world.elementFractions(material);
            for (int e = 0; e < fractions.length; e++) {
                if (fractions[e] != 0) {
                    elements.computeIfAbsent(ELEMENTS[e], k -> new CompensatedSum()).add(m * fractions[e]);
                }
            }
        }
        Map<Element, Double> elementMasses = new EnumMap<>(Element.class);
        for (Map.Entry<Element, CompensatedSum> e : elements.entrySet()) {
            elementMasses.put(e.getKey(), e.getValue().value());
        }
        return new Totals(mass.value(), enthalpy.value(), absoluteEnthalpy.value(), elementMasses);
    }
}
