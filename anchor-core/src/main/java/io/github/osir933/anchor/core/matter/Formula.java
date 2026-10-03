package io.github.osir933.anchor.core.matter;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * A chemical formula: how many atoms of each element one formula unit contains.
 *
 * <p>The parser accepts element symbols with optional counts, nested parentheses and brackets, decimal
 * counts for non-stoichiometric compounds, and hydrate dots, for example {@code Fe2O3},
 * {@code Ca(OH)2}, {@code CuSO4·5H2O} and {@code Fe0.95O}.
 */
public final class Formula {

    private final String text;
    private final Map<Element, Double> counts;

    private Formula(String text, Map<Element, Double> counts) {
        this.text = text;
        this.counts = Collections.unmodifiableMap(counts);
    }

    /**
     * Parses a formula.
     *
     * @param text the formula text
     * @return the parsed formula
     * @throws IllegalArgumentException if the text is not a valid formula
     */
    public static Formula parse(String text) {
        Objects.requireNonNull(text, "text");
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("empty formula");
        }
        Parser parser = new Parser(trimmed);
        EnumMap<Element, Double> counts = parser.parseFormula();
        return new Formula(trimmed, counts);
    }

    /**
     * Returns the atom counts per element, in atomic-number order.
     *
     * @return an unmodifiable map from element to atoms per formula unit
     */
    public Map<Element, Double> counts() {
        return counts;
    }

    /**
     * Returns the number of atoms of an element in one formula unit.
     *
     * @param element the element
     * @return the count, zero if absent
     */
    public double count(Element element) {
        return counts.getOrDefault(element, 0.0);
    }

    /**
     * Returns the molar mass of one formula unit.
     *
     * @return the molar mass in kg/mol
     */
    public double molarMassKgPerMol() {
        double grams = 0.0;
        for (Map.Entry<Element, Double> e : counts.entrySet()) {
            grams += e.getKey().atomicWeight() * e.getValue();
        }
        return grams * 1e-3;
    }

    /**
     * Returns the mass fraction of each element in this compound.
     *
     * @return an unmodifiable map from element to mass fraction; the fractions sum to one
     */
    public Map<Element, Double> elementMassFractions() {
        double total = molarMassKgPerMol();
        EnumMap<Element, Double> fractions = new EnumMap<>(Element.class);
        for (Map.Entry<Element, Double> e : counts.entrySet()) {
            fractions.put(e.getKey(), e.getKey().molarMassKgPerMol() * e.getValue() / total);
        }
        return Collections.unmodifiableMap(fractions);
    }

    /**
     * Returns the formula as written.
     *
     * @return the original text
     */
    public String text() {
        return text;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Formula other && counts.equals(other.counts);
    }

    @Override
    public int hashCode() {
        return counts.hashCode();
    }

    @Override
    public String toString() {
        return text;
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        EnumMap<Element, Double> parseFormula() {
            EnumMap<Element, Double> total = parseSequence();
            while (pos < s.length() && isHydrateDot(s.charAt(pos))) {
                pos++;
                double multiplier = parseNumber(1.0);
                EnumMap<Element, Double> part = parseSequence();
                if (part.isEmpty()) {
                    throw error("expected a formula after the dot");
                }
                addScaled(total, part, multiplier);
            }
            if (pos != s.length()) {
                throw error("unexpected character '" + s.charAt(pos) + "'");
            }
            if (total.isEmpty()) {
                throw error("no elements");
            }
            return total;
        }

        private EnumMap<Element, Double> parseSequence() {
            EnumMap<Element, Double> counts = new EnumMap<>(Element.class);
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '(' || c == '[') {
                    char close = c == '(' ? ')' : ']';
                    pos++;
                    EnumMap<Element, Double> inner = parseSequence();
                    if (pos >= s.length() || s.charAt(pos) != close) {
                        throw error("missing '" + close + "'");
                    }
                    pos++;
                    if (inner.isEmpty()) {
                        throw error("empty group");
                    }
                    addScaled(counts, inner, parseNumber(1.0));
                } else if (Character.isUpperCase(c)) {
                    Element element = parseElement();
                    double n = parseNumber(1.0);
                    counts.merge(element, n, Double::sum);
                } else {
                    break;
                }
            }
            return counts;
        }

        private Element parseElement() {
            int start = pos;
            pos++;
            while (pos < s.length() && Character.isLowerCase(s.charAt(pos))) {
                pos++;
            }
            String symbol = s.substring(start, pos);
            return Element.bySymbol(symbol).orElseThrow(() -> error("unknown element '" + symbol + "'"));
        }

        private double parseNumber(double defaultValue) {
            int start = pos;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) {
                pos++;
            }
            if (start == pos) {
                return defaultValue;
            }
            String number = s.substring(start, pos);
            double value;
            try {
                value = Double.parseDouble(number);
            } catch (NumberFormatException e) {
                throw error("bad number '" + number + "'");
            }
            if (!(value > 0) || !Double.isFinite(value)) {
                throw error("count must be positive: " + number);
            }
            return value;
        }

        private static boolean isHydrateDot(char c) {
            return c == '·' || c == '*' || c == '•';
        }

        private static void addScaled(EnumMap<Element, Double> into, Map<Element, Double> part, double factor) {
            for (Map.Entry<Element, Double> e : part.entrySet()) {
                into.merge(e.getKey(), e.getValue() * factor, Double::sum);
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException("invalid formula '" + s + "' at position " + pos + ": " + message);
        }
    }
}
