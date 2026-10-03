package io.github.osir933.anchor.core.matter;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The chemical elements from hydrogen to uranium with their standard atomic weights.
 *
 * <p>Atomic weights are the IUPAC CIAAW abridged standard atomic weights (2021), in grams per mole. For
 * elements without stable isotopes the mass number of the longest-lived or most common isotope is used,
 * and {@link #hasStableIsotopes()} returns {@code false}.
 */
public enum Element {
    H(1, "Hydrogen", 1.0080),
    He(2, "Helium", 4.0026),
    Li(3, "Lithium", 6.94),
    Be(4, "Beryllium", 9.0122),
    B(5, "Boron", 10.81),
    C(6, "Carbon", 12.011),
    N(7, "Nitrogen", 14.007),
    O(8, "Oxygen", 15.999),
    F(9, "Fluorine", 18.998),
    Ne(10, "Neon", 20.180),
    Na(11, "Sodium", 22.990),
    Mg(12, "Magnesium", 24.305),
    Al(13, "Aluminium", 26.982),
    Si(14, "Silicon", 28.085),
    P(15, "Phosphorus", 30.974),
    S(16, "Sulfur", 32.06),
    Cl(17, "Chlorine", 35.45),
    Ar(18, "Argon", 39.95),
    K(19, "Potassium", 39.098),
    Ca(20, "Calcium", 40.078),
    Sc(21, "Scandium", 44.956),
    Ti(22, "Titanium", 47.867),
    V(23, "Vanadium", 50.942),
    Cr(24, "Chromium", 51.996),
    Mn(25, "Manganese", 54.938),
    Fe(26, "Iron", 55.845),
    Co(27, "Cobalt", 58.933),
    Ni(28, "Nickel", 58.693),
    Cu(29, "Copper", 63.546),
    Zn(30, "Zinc", 65.38),
    Ga(31, "Gallium", 69.723),
    Ge(32, "Germanium", 72.630),
    As(33, "Arsenic", 74.922),
    Se(34, "Selenium", 78.971),
    Br(35, "Bromine", 79.904),
    Kr(36, "Krypton", 83.798),
    Rb(37, "Rubidium", 85.468),
    Sr(38, "Strontium", 87.62),
    Y(39, "Yttrium", 88.906),
    Zr(40, "Zirconium", 91.224),
    Nb(41, "Niobium", 92.906),
    Mo(42, "Molybdenum", 95.95),
    Tc(43, "Technetium", 98, false),
    Ru(44, "Ruthenium", 101.07),
    Rh(45, "Rhodium", 102.91),
    Pd(46, "Palladium", 106.42),
    Ag(47, "Silver", 107.87),
    Cd(48, "Cadmium", 112.41),
    In(49, "Indium", 114.82),
    Sn(50, "Tin", 118.71),
    Sb(51, "Antimony", 121.76),
    Te(52, "Tellurium", 127.60),
    I(53, "Iodine", 126.90),
    Xe(54, "Xenon", 131.29),
    Cs(55, "Caesium", 132.91),
    Ba(56, "Barium", 137.33),
    La(57, "Lanthanum", 138.91),
    Ce(58, "Cerium", 140.12),
    Pr(59, "Praseodymium", 140.91),
    Nd(60, "Neodymium", 144.24),
    Pm(61, "Promethium", 145, false),
    Sm(62, "Samarium", 150.36),
    Eu(63, "Europium", 151.96),
    Gd(64, "Gadolinium", 157.25),
    Tb(65, "Terbium", 158.93),
    Dy(66, "Dysprosium", 162.50),
    Ho(67, "Holmium", 164.93),
    Er(68, "Erbium", 167.26),
    Tm(69, "Thulium", 168.93),
    Yb(70, "Ytterbium", 173.05),
    Lu(71, "Lutetium", 174.97),
    Hf(72, "Hafnium", 178.49),
    Ta(73, "Tantalum", 180.95),
    W(74, "Tungsten", 183.84),
    Re(75, "Rhenium", 186.21),
    Os(76, "Osmium", 190.23),
    Ir(77, "Iridium", 192.22),
    Pt(78, "Platinum", 195.08),
    Au(79, "Gold", 196.97),
    Hg(80, "Mercury", 200.59),
    Tl(81, "Thallium", 204.38),
    Pb(82, "Lead", 207.2),
    Bi(83, "Bismuth", 208.98),
    Po(84, "Polonium", 209, false),
    At(85, "Astatine", 210, false),
    Rn(86, "Radon", 222, false),
    Fr(87, "Francium", 223, false),
    Ra(88, "Radium", 226, false),
    Ac(89, "Actinium", 227, false),
    Th(90, "Thorium", 232.04),
    Pa(91, "Protactinium", 231.04),
    U(92, "Uranium", 238.03);

    private static final Map<String, Element> BY_SYMBOL = new TreeMap<>();

    static {
        for (Element e : values()) {
            BY_SYMBOL.put(e.name(), e);
        }
    }

    private final int atomicNumber;
    private final String displayName;
    private final double atomicWeight;
    private final boolean stableIsotopes;

    Element(int atomicNumber, String displayName, double atomicWeight) {
        this(atomicNumber, displayName, atomicWeight, true);
    }

    Element(int atomicNumber, String displayName, double atomicWeight, boolean stableIsotopes) {
        this.atomicNumber = atomicNumber;
        this.displayName = displayName;
        this.atomicWeight = atomicWeight;
        this.stableIsotopes = stableIsotopes;
    }

    /**
     * Looks up an element by its symbol, which is case-sensitive ({@code "Co"} is cobalt, {@code "CO"} is not
     * a symbol).
     *
     * @param symbol the element symbol
     * @return the element, if the symbol is known
     */
    public static Optional<Element> bySymbol(String symbol) {
        return Optional.ofNullable(BY_SYMBOL.get(symbol));
    }

    /**
     * Returns the chemical symbol.
     *
     * @return the symbol, such as {@code Fe}
     */
    public String symbol() {
        return name();
    }

    /**
     * Returns the atomic number.
     *
     * @return the number of protons
     */
    public int atomicNumber() {
        return atomicNumber;
    }

    /**
     * Returns the English element name.
     *
     * @return the name, such as {@code Iron}
     */
    public String displayName() {
        return displayName;
    }

    /**
     * Returns the standard atomic weight in grams per mole.
     *
     * @return the atomic weight in g/mol
     */
    public double atomicWeight() {
        return atomicWeight;
    }

    /**
     * Returns the molar mass in the coherent SI unit.
     *
     * @return the molar mass in kg/mol
     */
    public double molarMassKgPerMol() {
        return atomicWeight * 1e-3;
    }

    /**
     * Tells whether the element has stable isotopes and therefore a true standard atomic weight.
     *
     * @return {@code false} for radioactive-only elements such as technetium
     */
    public boolean hasStableIsotopes() {
        return stableIsotopes;
    }
}
