package io.github.osir933.anchor.core.matter;

import static io.github.osir933.anchor.core.matter.PropertyCurve.constant;
import static io.github.osir933.anchor.core.matter.PropertyCurve.table;

import io.github.osir933.anchor.core.matter.Source.DataQuality;
import java.util.List;

/**
 * The built-in starter materials.
 *
 * <p>These values are typical handbook values ({@link DataQuality#HANDBOOK_TYPICAL}) or, where no single
 * good measurement exists, labelled estimates ({@link DataQuality#ESTIMATED}). Every value is checked line
 * by line against its primary source before the first public release; the full material database then
 * moves to data packs.
 *
 * <p>All values are at standard atmospheric pressure. Solids keep their 300 K density at all temperatures
 * for now; thermal expansion arrives with the structural model.
 */
public final class MaterialLibrary {

    /** Incropera's heat transfer textbook, appendix tables A.1 to A.6. */
    public static final Source INCROPERA = new Source("incropera-7",
            "T. L. Bergman, A. S. Lavine, F. P. Incropera and D. P. DeWitt, Fundamentals of Heat and Mass "
                    + "Transfer, 7th ed., Wiley (2011), Appendix A",
            DataQuality.HANDBOOK_TYPICAL);
    /** The CRC Handbook. */
    public static final Source CRC = new Source("crc-97",
            "W. M. Haynes (ed.), CRC Handbook of Chemistry and Physics, 97th ed., CRC Press (2016)",
            DataQuality.HANDBOOK_TYPICAL);
    /** The NIST-JANAF thermochemical tables. */
    public static final Source JANAF = new Source("nist-janaf",
            "M. W. Chase Jr., NIST-JANAF Thermochemical Tables, 4th ed., J. Phys. Chem. Ref. Data, "
                    + "Monograph 9 (1998)",
            DataQuality.HANDBOOK_TYPICAL);
    /** IAPWS formulations for water and ice. */
    public static final Source IAPWS = new Source("iapws",
            "IAPWS R6-95 (2018) for ordinary water substance and IAPWS R10-06 (2009) for ice Ih",
            DataQuality.HANDBOOK_TYPICAL);
    /** Typical surface emissivities. */
    public static final Source EMISSIVITY = new Source("emissivity-typical",
            "Typical total hemispherical emissivities of oxidised or rough surfaces, after Incropera "
                    + "(7th ed.) Table A.11; the real value depends strongly on surface finish",
            DataQuality.ESTIMATED);
    /** Engineering estimates for silicate melts and rock melting. */
    public static final Source ROCK_ESTIMATE = new Source("rock-melt-estimate",
            "Engineering estimate from typical silicate rock and melt properties; rocks melt over a range "
                    + "of temperatures, modelled here as a single transition",
            DataQuality.ESTIMATED);
    /** Typical mineral or oxide composition used for a natural or manufactured mixture. */
    public static final Source COMPOSITION_ESTIMATE = new Source("composition-typical",
            "Typical modal or oxide composition; natural materials vary from place to place",
            DataQuality.ESTIMATED);

    /** Water substance: ice Ih, liquid water and steam. */
    public static final Material WATER = Material.builder("anchor:water", "Water")
            .composition(Composition.pure(SpeciesCatalog.WATER))
            .region(new PhaseRegion(Phase.SOLID, "ice Ih", 150.0, 273.15,
                    table(INCROPERA, 200.0, 1600.0, 253.15, 1945.0, 273.15, 2040.0),
                    table(INCROPERA, 253.15, 2.03, 273.15, 1.88),
                    constant(920.0, INCROPERA),
                    constant(0.97, EMISSIVITY)))
            .transition(new PhaseTransition("melting", 273.15, 333.7e3, INCROPERA))
            .region(new PhaseRegion(Phase.LIQUID, "liquid water", 273.15, 373.124,
                    table(INCROPERA, 273.15, 4217.0, 280.0, 4198.0, 300.0, 4179.0, 320.0, 4180.0, 340.0, 4188.0,
                            360.0, 4203.0, 373.15, 4217.0),
                    table(INCROPERA, 273.15, 0.569, 280.0, 0.582, 300.0, 0.613, 320.0, 0.640, 340.0, 0.660,
                            360.0, 0.674, 373.15, 0.680),
                    table(INCROPERA, 273.15, 1000.0, 300.0, 997.0, 320.0, 989.0, 340.0, 979.0, 360.0, 967.0,
                            373.15, 958.0),
                    constant(0.96, EMISSIVITY)))
            .transition(new PhaseTransition("boiling", 373.124, 2257e3, IAPWS))
            .region(new PhaseRegion(Phase.GAS, "steam", 373.124, 1500.0,
                    table(INCROPERA, 380.0, 2060.0, 400.0, 2014.0, 450.0, 1980.0, 500.0, 1985.0, 600.0, 2026.0,
                            700.0, 2085.0, 800.0, 2152.0),
                    table(INCROPERA, 380.0, 0.0246, 400.0, 0.0261, 450.0, 0.0299, 500.0, 0.0339, 600.0, 0.0422,
                            700.0, 0.0505, 800.0, 0.0549),
                    table(INCROPERA, 380.0, 0.5863, 400.0, 0.5542, 450.0, 0.4902, 500.0, 0.4405, 600.0, 0.3652,
                            700.0, 0.3140, 800.0, 0.2739),
                    constant(0.0, EMISSIVITY)))
            .notes("Steam properties are at 1 atm. A gas has no surface emissivity; radiation inside gases "
                    + "needs a participating-media model, not yet included.")
            .build();

    /** Pure iron, with its alpha, gamma and delta crystal structures. */
    public static final Material IRON = Material.builder("anchor:iron", "Iron")
            .composition(Composition.pure(SpeciesCatalog.IRON))
            .region(new PhaseRegion(Phase.SOLID, "alpha iron (bcc)", 100.0, 1185.15,
                    table(INCROPERA, 200.0, 384.0, 300.0, 447.0, 400.0, 490.0, 600.0, 574.0, 800.0, 680.0,
                            1000.0, 975.0, 1043.0, 1300.0, 1100.0, 790.0, 1185.15, 700.0),
                    table(INCROPERA, 200.0, 94.0, 300.0, 80.2, 400.0, 69.5, 600.0, 54.7, 800.0, 43.3, 1000.0, 32.8,
                            1185.15, 28.0),
                    constant(7870.0, INCROPERA),
                    constant(0.70, EMISSIVITY)))
            .transition(new PhaseTransition("alpha to gamma", 1185.15, 16.1e3, JANAF))
            .region(new PhaseRegion(Phase.SOLID, "gamma iron (fcc)", 1185.15, 1667.15,
                    table(INCROPERA, 1185.15, 606.0, 1200.0, 609.0, 1500.0, 654.0, 1667.15, 680.0),
                    table(INCROPERA, 1185.15, 28.2, 1200.0, 28.3, 1500.0, 32.1, 1667.15, 33.4),
                    constant(7870.0, INCROPERA),
                    constant(0.70, EMISSIVITY)))
            .transition(new PhaseTransition("gamma to delta", 1667.15, 15.0e3, JANAF))
            .region(new PhaseRegion(Phase.SOLID, "delta iron (bcc)", 1667.15, 1811.0,
                    table(JANAF, 1667.15, 730.0, 1811.0, 760.0),
                    constant(34.0, INCROPERA),
                    constant(7870.0, INCROPERA),
                    constant(0.70, EMISSIVITY)))
            .transition(new PhaseTransition("melting", 1811.0, 247.3e3, CRC))
            .region(new PhaseRegion(Phase.LIQUID, "liquid iron", 1811.0, 3134.0,
                    constant(824.0, JANAF),
                    constant(40.0, CRC),
                    constant(7015.0, CRC),
                    constant(0.40, EMISSIVITY)))
            .notes("The Curie point near 1043 K appears as a peak in specific heat, approximated by a few "
                    + "points. Emissivity assumes an oxidised surface. Boiling at 3134 K is outside the range.")
            .build();

    /** Pure copper. */
    public static final Material COPPER = Material.builder("anchor:copper", "Copper")
            .composition(Composition.pure(SpeciesCatalog.COPPER))
            .region(new PhaseRegion(Phase.SOLID, "copper (fcc)", 100.0, 1357.77,
                    table(INCROPERA, 100.0, 252.0, 200.0, 356.0, 300.0, 385.0, 400.0, 397.0, 600.0, 417.0,
                            800.0, 433.0, 1000.0, 451.0, 1200.0, 480.0),
                    table(INCROPERA, 100.0, 482.0, 200.0, 413.0, 300.0, 401.0, 400.0, 393.0, 600.0, 379.0,
                            800.0, 366.0, 1000.0, 352.0, 1200.0, 339.0),
                    constant(8933.0, INCROPERA),
                    constant(0.60, EMISSIVITY)))
            .transition(new PhaseTransition("melting", 1357.77, 208.7e3, CRC))
            .region(new PhaseRegion(Phase.LIQUID, "liquid copper", 1357.77, 2835.0,
                    constant(495.0, JANAF),
                    constant(165.0, CRC),
                    constant(8020.0, CRC),
                    constant(0.15, EMISSIVITY)))
            .notes("Emissivity assumes an oxidised surface; polished copper is near 0.03.")
            .build();

    /** Pure aluminium. */
    public static final Material ALUMINIUM = Material.builder("anchor:aluminium", "Aluminium")
            .composition(Composition.pure(SpeciesCatalog.ALUMINIUM))
            .region(new PhaseRegion(Phase.SOLID, "aluminium (fcc)", 100.0, 933.47,
                    table(INCROPERA, 100.0, 482.0, 200.0, 798.0, 300.0, 903.0, 400.0, 949.0, 600.0, 1033.0,
                            800.0, 1146.0),
                    table(INCROPERA, 100.0, 302.0, 200.0, 237.0, 300.0, 237.0, 400.0, 240.0, 600.0, 231.0,
                            800.0, 218.0),
                    constant(2702.0, INCROPERA),
                    constant(0.10, EMISSIVITY)))
            .transition(new PhaseTransition("melting", 933.47, 396.9e3, CRC))
            .region(new PhaseRegion(Phase.LIQUID, "liquid aluminium", 933.47, 2792.0,
                    constant(1177.0, JANAF),
                    constant(91.0, CRC),
                    constant(2375.0, CRC),
                    constant(0.10, EMISSIVITY)))
            .build();

    /** Pure gold. */
    public static final Material GOLD = Material.builder("anchor:gold", "Gold")
            .composition(Composition.pure(SpeciesCatalog.GOLD))
            .region(new PhaseRegion(Phase.SOLID, "gold (fcc)", 100.0, 1337.33,
                    table(INCROPERA, 100.0, 109.0, 200.0, 124.0, 300.0, 129.0, 400.0, 131.0, 600.0, 135.0,
                            800.0, 140.0, 1000.0, 145.0, 1200.0, 155.0),
                    table(INCROPERA, 100.0, 327.0, 200.0, 323.0, 300.0, 317.0, 400.0, 311.0, 600.0, 298.0,
                            800.0, 284.0, 1000.0, 270.0, 1200.0, 255.0),
                    constant(19300.0, INCROPERA),
                    constant(0.03, EMISSIVITY)))
            .transition(new PhaseTransition("melting", 1337.33, 63.7e3, CRC))
            .region(new PhaseRegion(Phase.LIQUID, "liquid gold", 1337.33, 3129.0,
                    constant(149.0, JANAF),
                    constant(105.0, CRC),
                    constant(17310.0, CRC),
                    constant(0.05, EMISSIVITY)))
            .build();

    /** Granite, the default rock behind vanilla stone. */
    public static final Material GRANITE = Material.builder("anchor:granite", "Granite")
            .composition(Composition.builder()
                    .add(SpeciesCatalog.SILICA, 0.30)
                    .add(SpeciesCatalog.ORTHOCLASE, 0.35)
                    .add(SpeciesCatalog.ALBITE, 0.30)
                    .add(SpeciesCatalog.MAGNETITE, 0.05)
                    .build())
            .region(new PhaseRegion(Phase.SOLID, "granite", 150.0, 1510.0,
                    constant(775.0, INCROPERA),
                    constant(2.79, INCROPERA),
                    constant(2630.0, INCROPERA),
                    constant(0.85, EMISSIVITY)))
            .transition(new PhaseTransition("melting", 1510.0, 300e3, ROCK_ESTIMATE))
            .region(new PhaseRegion(Phase.LIQUID, "granitic melt", 1510.0, 2500.0,
                    constant(1400.0, ROCK_ESTIMATE),
                    constant(1.5, ROCK_ESTIMATE),
                    constant(2350.0, ROCK_ESTIMATE),
                    constant(0.85, EMISSIVITY)))
            .notes("Composition is a typical granite (" + COMPOSITION_ESTIMATE.key() + "). Specific heat and "
                    + "conductivity are 300 K values held constant; both change with temperature in reality.")
            .build();

    /** Basalt, the rock that Minecraft lava is modelled on when molten. */
    public static final Material BASALT = Material.builder("anchor:basalt", "Basalt")
            .composition(Composition.builder()
                    .add(SpeciesCatalog.ANORTHITE, 0.25)
                    .add(SpeciesCatalog.ALBITE, 0.20)
                    .add(SpeciesCatalog.DIOPSIDE, 0.35)
                    .add(SpeciesCatalog.FORSTERITE, 0.10)
                    .add(SpeciesCatalog.MAGNETITE, 0.10)
                    .build())
            .region(new PhaseRegion(Phase.SOLID, "basalt", 150.0, 1423.0,
                    constant(840.0, ROCK_ESTIMATE),
                    constant(1.7, ROCK_ESTIMATE),
                    constant(2900.0, ROCK_ESTIMATE),
                    constant(0.90, EMISSIVITY)))
            .transition(new PhaseTransition("melting", 1423.0, 400e3, ROCK_ESTIMATE))
            .region(new PhaseRegion(Phase.LIQUID, "basaltic melt (lava)", 1423.0, 2500.0,
                    constant(1500.0, ROCK_ESTIMATE),
                    constant(1.5, ROCK_ESTIMATE),
                    constant(2700.0, ROCK_ESTIMATE),
                    constant(0.95, EMISSIVITY)))
            .notes("Basalt melts between about 1373 K and 1473 K; one transition at 1423 K stands in for "
                    + "that range.")
            .build();

    /** Dry quartz sand, as a loose bulk material. */
    public static final Material SAND = Material.builder("anchor:sand", "Sand")
            .composition(Composition.pure(SpeciesCatalog.SILICA))
            .region(new PhaseRegion(Phase.SOLID, "quartz sand (bulk)", 150.0, 1996.0,
                    constant(800.0, INCROPERA),
                    constant(0.27, INCROPERA),
                    constant(1515.0, INCROPERA),
                    constant(0.90, EMISSIVITY)))
            .transition(new PhaseTransition("melting", 1996.0, 160e3, CRC))
            .region(new PhaseRegion(Phase.LIQUID, "silica melt", 1996.0, 2500.0,
                    constant(1430.0, JANAF),
                    constant(1.5, ROCK_ESTIMATE),
                    constant(2200.0, CRC),
                    constant(0.90, EMISSIVITY)))
            .notes("Bulk density includes about 40 percent air-filled pores; melting closes them, which changes "
                    + "volume and is not yet modelled.")
            .build();

    /** Soda-lime glass, as in windows. */
    public static final Material GLASS = Material.builder("anchor:glass", "Soda-lime glass")
            .composition(Composition.builder()
                    .add(SpeciesCatalog.SILICA, 0.73)
                    .add(SpeciesCatalog.SODIUM_OXIDE, 0.14)
                    .add(SpeciesCatalog.CALCIUM_OXIDE, 0.09)
                    .add(SpeciesCatalog.MAGNESIUM_OXIDE, 0.04)
                    .build())
            .region(new PhaseRegion(Phase.SOLID, "soda-lime glass", 150.0, 1000.0,
                    constant(750.0, INCROPERA),
                    constant(1.4, INCROPERA),
                    constant(2500.0, INCROPERA),
                    constant(0.92, EMISSIVITY)))
            .notes("Glass softens gradually above about 840 K instead of melting at one temperature; states "
                    + "above 1000 K are outside the description until a viscous model exists.")
            .build();

    /** Dry hardwood, such as oak or maple. */
    public static final Material HARDWOOD = Material.builder("anchor:hardwood", "Hardwood")
            .composition(Composition.builder()
                    .add(SpeciesCatalog.CELLULOSE, 0.45)
                    .add(SpeciesCatalog.HEMICELLULOSE, 0.30)
                    .add(SpeciesCatalog.LIGNIN, 0.25)
                    .build())
            .region(new PhaseRegion(Phase.SOLID, "dry hardwood", 150.0, 473.15,
                    constant(1255.0, INCROPERA),
                    constant(0.16, INCROPERA),
                    constant(720.0, INCROPERA),
                    constant(0.90, EMISSIVITY)))
            .notes("Wood starts to decompose (pyrolysis) above about 473 K; burning and charring need the "
                    + "chemistry model, so hotter states are outside this description.")
            .build();

    /** Dry air at one atmosphere. */
    public static final Material AIR = Material.builder("anchor:air", "Air")
            .composition(Composition.builder()
                    .add(SpeciesCatalog.NITROGEN, 0.7552)
                    .add(SpeciesCatalog.OXYGEN, 0.2314)
                    .add(SpeciesCatalog.ARGON, 0.0129)
                    .add(SpeciesCatalog.CARBON_DIOXIDE, 0.0005)
                    .build())
            .region(new PhaseRegion(Phase.GAS, "dry air", 100.0, 2500.0,
                    table(INCROPERA, 250.0, 1006.0, 300.0, 1007.0, 350.0, 1009.0, 400.0, 1014.0, 500.0, 1030.0,
                            600.0, 1051.0, 800.0, 1099.0, 1000.0, 1141.0, 1500.0, 1230.0, 2000.0, 1338.0),
                    table(INCROPERA, 250.0, 0.0223, 300.0, 0.0263, 350.0, 0.0300, 400.0, 0.0338, 500.0, 0.0407,
                            600.0, 0.0469, 800.0, 0.0573, 1000.0, 0.0667, 1500.0, 0.100, 2000.0, 0.137),
                    table(INCROPERA, 250.0, 1.3947, 300.0, 1.1614, 350.0, 0.9950, 400.0, 0.8711, 500.0, 0.6964,
                            600.0, 0.5804, 800.0, 0.4354, 1000.0, 0.3482, 1500.0, 0.2322, 2000.0, 0.1741),
                    constant(0.0, EMISSIVITY)))
            .notes("Properties at 1 atm. Still air conducts heat poorly; most heat leaves surfaces by "
                    + "convection, which needs the fluid model or a convection correlation.")
            .build();

    private static final List<Material> ALL = List.of(
            WATER, IRON, COPPER, ALUMINIUM, GOLD, GRANITE, BASALT, SAND, GLASS, HARDWOOD, AIR);

    private MaterialLibrary() {
    }

    /**
     * Returns every built-in material in a fixed order.
     *
     * @return the materials
     */
    public static List<Material> all() {
        return ALL;
    }
}
