package io.github.osir933.anchor.core.physics.structure;

import io.github.osir933.anchor.core.matter.Mechanics;
import io.github.osir933.anchor.core.matter.Mechanics.Failure;
import io.github.osir933.anchor.core.space.Direction;
import io.github.osir933.anchor.core.space.GridPos;
import io.github.osir933.anchor.core.units.PhysicalConstants;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.function.IntFunction;

/**
 * Works out whether a structure stands under its own weight and the strain of heat, and what breaks if it does
 * not.
 *
 * <p>The frame's free blocks carry their weight to the ground through their joints. Heat stretches their matter
 * too, and where the frame or the ground stops a block growing or bending, its joints are loaded. The analysis
 * solves for how far each block moves, linear and elastic, then checks every joint: an intact joint against the
 * strength of the materials on each side at their temperatures, a cracked or granular one against what pressing
 * and friction can hold. If something is overloaded, the worst joint gives way: an intact joint cracks, a cracked
 * one pivots on its edge or lets go. Loads then find other paths, so the analysis solves again, until nothing more
 * breaks. Blocks left with no path to the ground fall.
 *
 * <p>The strain of heat is a matter of hairlines: it moves blocks by fractions of a millimetre, and lets go once
 * they have moved that far. So it cracks brittle matter, which breaks before it moves, but not matter that yields:
 * a ductile joint that heat strains past its strength gives a little and so lets the strain go, without losing any
 * of its strength, so ductile joints are checked against the weight they carry alone. A crack lets the strain go
 * too: a cracked joint rocks rather than bends, and where heat alone would make it slip, rock or open, it does so
 * by a hairline and holds as well as it does without heat. That is why a structure cracked by heat can still
 * stand, and why heat that presses a cracked span together can hold it up. Heat that crushes the edge of a joint
 * has nowhere to go.
 *
 * <p>A cracked joint holds only by pressing, so it cannot hold a span up by bending, but the span can still stand
 * as an arch. A cracked joint that bending would tip, or whose edge it would crush, pivots instead, on a hinge just
 * in from the edge it presses, where that edge can bear the force through it, and goes on carrying that force. So a
 * span cracked at its ends sags until it pushes on them, the ground pushes back, and the span stands on them as an
 * arch, as a masonry arch does, or a cracked floor held on all sides. A joint pivots only where the blocks beyond it
 * are held by more than it, which would otherwise only swing about the hinge, and of neighbouring joints that would
 * start to pivot together only the most loaded does, since two hinges side by side can leave the blocks between
 * them free to swing. A hinge moves inward as the force through it grows, a few times at most, and its joint lets
 * go once the force acts beyond its patch: so an arch too flat for its span sags through its rise and falls, and one
 * too short for its rise slides off its ends unless friction holds them. Loose grains have no edge to pivot on, and a
 * joint the frame broke by buckling lets go, since a hinge would only let it bow further.
 *
 * <p>Slender structures bow under what presses them, and bowing loads them more. Forces along the joints soften
 * the frame where they press and stiffen it where they pull, so under some multiple of its loads, its critical load
 * factor, it stops resisting some way of bowing and buckles. A structure that could carry ten times its loads or
 * more bows too little to matter and is judged straight. One that could carry fewer is taken to start bowed in the
 * shape it buckles into, by a five-hundredth of the length over which it buckles, as no column is quite straight,
 * and is solved with the softening in: the nearer its loads come to the critical ones, the more it bows, and the
 * bow bends its joints, which are checked as before. One that cannot carry its loads at all buckles, and the joints
 * it bends most give way. Heat that presses a structure can buckle it too, unless a side of the joint it presses
 * through yields: then bowing a little lets the push go, as yielding lets its strain go.
 *
 * <p>Failure is judged at the faces where blocks meet, on each block's side, because that is where the
 * structure's cross-section is narrowest and where a crack can form, and because a straight run of blocks that is
 * overloaded inside a block is overloaded at the face next to it too: the bending moment changes little over half
 * a block. The ground is hemmed in by the earth around and below it, so pressing does not crush it, but a joint
 * to it can still crack on its side: stone cannot hang from soil.
 *
 * <p>What the analysis leaves out, so far: a ductile joint that yields gives way at once, where a real steel
 * frame would keep its full plastic moment there and hand on the rest, so redundant metal frames fall somewhat
 * early; only forces along the joints soften the frame, so a beam bent about its stiff side does not twist aside;
 * the ground does not give, however soft, so it holds an arch's ends however hard they push; and deflections are
 * taken to be small, bowing included. Where they turn out large the result says so.
 */
public final class StructuralAnalysis {

    /** Deflections beyond this many metres make small-deflection theory doubtful. */
    static final double LARGE_DEFLECTION_M = 0.1;

    /** Rotations beyond this many radians make small-deflection theory doubtful. */
    static final double LARGE_ROTATION = 0.1;

    /**
     * A structure that could carry at least this many times its loads before it buckles bows too little under them
     * to matter, so first-order theory judges it, as Eurocode 3 allows (EN 1993-1-1 5.2.1); one that could carry
     * fewer is judged in second-order theory.
     */
    static final double SECOND_ORDER_BELOW = 10.0;

    /**
     * No structure is perfectly straight nor perfectly loaded, so a structure judged in second-order theory is taken
     * to start bowed, in the shape it buckles into, by one part in this many of the length over which it buckles,
     * about what building codes take for masonry and timber.
     */
    static final double IMPERFECTION = 500.0;

    /**
     * A hinge keeps this fraction of the stiffness with which its joint resisted turning about it, so that a frame its
     * hinges leave free to move still solves: such a hinge turns so far that the force through it leaves the patch,
     * and the joint lets go.
     */
    static final double HINGE_SOFTNESS = 1e-6;

    /**
     * A hinge is put where the edge it pivots on can take this many times the force pressing through it, so that a
     * little more pressing does not move it again at once.
     */
    static final double HINGE_ROOM = 1.05;

    /**
     * No force can press on an edge alone, so a hinge is put no nearer the edge than where the largest part of the
     * patch centred on it is this fraction of the whole patch.
     */
    static final double HINGE_EDGE = 1e-3;

    /** How many times a hinge may move inward as the force through it grows, before its joint gives up. */
    static final int HINGE_MOVES = 4;

    /** How many times a hinge may close again as loads find other paths, before it is left as it is. */
    static final int HINGE_CLOSES = 2;

    /** A hinge that turns back, closing, by more than this many radians closes. */
    static final double HINGE_CLOSING = 1e-12;

    private StructuralAnalysis() {
    }

    /**
     * How the analysis runs.
     *
     * @param gravity the acceleration of gravity in m/s², pointing down the y axis
     * @param maxRounds how many times the analysis may solve and let joints give way before it stops and lets
     *     everything still overloaded give way at once
     * @param together joints within this fraction of the worst one give way in the same round, so a symmetric
     *     structure breaks symmetrically
     * @param stiffnessFloor the smallest fraction of its cold Young's modulus a material keeps, however hot, so
     *     that the equations stay solvable; its strength is not floored
     * @param buckling whether slender structures bow under what presses them and buckle; if not, every structure is
     *     judged in first-order theory
     * @param arching whether a cracked joint that would tip pivots on the edge it presses, still carrying the thrust
     *     an arch needs, rather than letting go
     */
    public record Settings(double gravity, int maxRounds, double together, double stiffnessFloor, boolean buckling,
            boolean arching) {

        /**
         * Validates the settings.
         *
         * @param gravity the gravity
         * @param maxRounds the round limit
         * @param together the tolerance for giving way together
         * @param stiffnessFloor the stiffness floor
         * @param buckling whether structures buckle
         * @param arching whether cracked joints pivot on their edges
         */
        public Settings {
            if (!(gravity >= 0 && Double.isFinite(gravity))) {
                throw new IllegalArgumentException("gravity must be finite and non-negative: " + gravity);
            }
            if (maxRounds < 1) {
                throw new IllegalArgumentException("at least one round is needed: " + maxRounds);
            }
            if (!(together >= 0 && together < 1)) {
                throw new IllegalArgumentException("together must lie in [0, 1): " + together);
            }
            if (!(stiffnessFloor > 0 && stiffnessFloor <= 1)) {
                throw new IllegalArgumentException("stiffness floor must lie in (0, 1]: " + stiffnessFloor);
            }
        }

        /**
         * Returns the default settings: standard gravity, 64 rounds, joints within 2 percent of the worst give way
         * together, materials keep at least one ten-thousandth of their stiffness, structures buckle and cracked
         * joints pivot on their edges.
         *
         * @return the settings
         */
        public static Settings defaults() {
            return new Settings(PhysicalConstants.STANDARD_GRAVITY, 64, 0.02, 1e-4, true, true);
        }

        /**
         * Returns these settings with buckling switched on or off.
         *
         * @param on whether structures buckle
         * @return the settings
         */
        public Settings withBuckling(boolean on) {
            return new Settings(gravity, maxRounds, together, stiffnessFloor, on, arching);
        }

        /**
         * Returns these settings with arching switched on or off.
         *
         * @param on whether cracked joints pivot on their edges
         * @return the settings
         */
        public Settings withArching(boolean on) {
            return new Settings(gravity, maxRounds, together, stiffnessFloor, buckling, on);
        }
    }

    /** How a joint is loaded beyond what it can take, or would be if it gave way. */
    public enum Mode {
        /** A brittle material is pulled apart, usually on the stretched side of a bend. */
        TENSION,
        /** A brittle material is crushed. */
        COMPRESSION,
        /** A brittle material is sheared or twisted apart. */
        SHEAR,
        /** A ductile material yields right across the joint. */
        YIELDING,
        /** A cracked or granular joint is pulled apart. */
        PULLED_APART,
        /** A cracked or granular joint is bent so far that the blocks tip about an edge. */
        TIPPING,
        /** A cracked or granular joint slides or twists further than friction allows. */
        SLIDING,
        /** A cracked or granular joint is pressed harder than the material can take. */
        CRUSHING
    }

    /**
     * How one free block ended up.
     *
     * @param pos where the block is
     * @param fell whether it was left with no path to the ground and falls
     * @param displacement how far its centre moved under load, x, y and z in metres, including how far a slender
     *     structure bows one way from its imperfect start; zeros if it fell
     * @param rotation how far it turned, about x, y and z in radians; zeros if it fell
     * @param load the largest load of any joint it keeps, as a fraction of what that joint can take
     */
    public record BlockResult(GridPos pos, boolean fell, double[] displacement, double[] rotation, double load) {
    }

    /**
     * How one joint ended up.
     *
     * @param pos the block on the joint's negative side
     * @param axis the axis the joint runs along
     * @param state whether it is intact or cracked after the analysis
     * @param holds whether it still carries load: false if it let go, or if a block it joins fell
     * @param load how loaded it was in the last solution that included it, as a fraction of what it can take:
     *     its utilization if intact, how far beyond friction or contact it was pushed if cracked
     * @param mode what limits it
     * @param withoutHeat how loaded the weight it carries would leave it in that solution, without the strain of
     *     heat
     * @param unbowed how loaded it would be in that solution if the structure did not bow under what presses it:
     *     its load in first-order theory, the same as its load unless the structure is slender enough to bow
     * @param hinged whether it is a cracked joint that pivots on the edge it presses, as the joints of an arch do
     * @param force the force along it in that solution, from the weight and the strain of heat, in newtons: positive
     *     where it pulls its blocks together, negative where they press on each other
     */
    public record BondResult(GridPos pos, int axis, Frame.Joint state, boolean holds, double load, Mode mode,
            double withoutHeat, double unbowed, boolean hinged, double force) {
    }

    /**
     * A joint that cracked in this analysis.
     *
     * @param pos the block on the joint's negative side
     * @param axis the axis the joint runs along
     * @param mode how it gave way
     * @param load its load when it gave way, as a fraction of what it could take
     * @param heat whether the strain of heat broke it: the weight it carried alone would have left it whole
     * @param buckled whether the structure bowing under what pressed it broke it: had it stayed straight, the joint
     *     would have held
     */
    public record Crack(GridPos pos, int axis, Mode mode, double load, boolean heat, boolean buckled) {
    }

    /**
     * The outcome of an analysis.
     *
     * @param blocks every free block, in position order
     * @param bonds every joint, in frame order
     * @param cracks the joints that cracked, in the order they gave way
     * @param falling the free blocks left with no path to the ground, in position order
     * @param rounds how many times the analysis solved the equations
     * @param settled whether it found a state where nothing more gives way within the round limit
     * @param buckling how many times its loads what is left standing could carry before it buckles, in the last
     *     solution: the critical load factor; infinite if at least {@link #SECOND_ORDER_BELOW}, which the analysis
     *     does not work out more closely, or if nothing in it is pressed
     * @param notes where the result is less certain than usual, in plain words
     */
    public record Result(List<BlockResult> blocks, List<BondResult> bonds, List<Crack> cracks, List<GridPos> falling,
            int rounds, boolean settled, double buckling, List<String> notes) {

        /**
         * Returns how one block ended up.
         *
         * @param pos where the block is
         * @return its result, or {@code null} if it is not a free block of the frame
         */
        public BlockResult block(GridPos pos) {
            int lo = 0;
            int hi = blocks.size() - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                int c = blocks.get(mid).pos().compareTo(pos);
                if (c < 0) {
                    lo = mid + 1;
                } else if (c > 0) {
                    hi = mid - 1;
                } else {
                    return blocks.get(mid);
                }
            }
            return null;
        }
    }

    /** A material's properties at one block's temperature and melt state. */
    private record Solid(Failure failure, double youngs, double shear, double tension, double compression,
            double friction) {

        static Solid of(Mechanics m, double temperatureK, double solidFraction, double floor) {
            double cold = m.youngsModulus(Mechanics.REFERENCE_K);
            double e = Math.max(m.youngsModulus(temperatureK) * solidFraction, floor * cold);
            double g = e / (2.0 * (1.0 + m.poissonRatio()));
            return new Solid(m.failure(), e, g, m.tensileStrength(temperatureK) * solidFraction,
                    m.compressiveStrength(temperatureK) * solidFraction, m.friction());
        }
    }

    /** How a joint's end sections relate to the frame. */
    private static final int FREE = 0;
    private static final int GROUND_BELOW = 1;
    private static final int GROUND_ABOVE = 2;

    /**
     * Analyses a frame with the default settings.
     *
     * @param frame the frame
     * @return the outcome
     */
    public static Result analyse(Frame frame) {
        return analyse(frame, Settings.defaults());
    }

    /**
     * Analyses a frame.
     *
     * @param frame the frame
     * @param settings how to run
     * @return the outcome
     */
    public static Result analyse(Frame frame, Settings settings) {
        return new Run(frame, settings).run();
    }

    /** One analysis, with its working arrays. */
    private static final class Run {

        private final Settings settings;
        private final List<Frame.Block> free = new ArrayList<>();
        private final TreeMap<GridPos, Integer> nodeAt = new TreeMap<>();
        private final TreeMap<GridPos, Frame.Block> groundAt = new TreeMap<>();
        private final Solid[] solids;
        private final List<Frame.Bond> bonds;
        private final int[] kind;
        private final int[] endI;
        private final int[] endJ;
        private final double[][] stiffness;
        private final boolean[] contact;
        private final Frame.Joint[] state;
        private final boolean[] open;
        private final double[] load;
        private final double[] withoutHeat;
        private final Mode[] mode;
        private final boolean[] alive;
        /** How far each block moves under its weight alone. */
        private final double[] displacement;
        /**
         * For each joint that heat strains, how far heat would move its end j, end i held, if nothing held it, in
         * its own axes; {@code null} for a joint heat leaves alone.
         */
        private final double[][] heatShift;
        /** For each joint that heat strains, the forces its ends need to hold it as it was: K times the shift. */
        private final double[][] heatLoad;
        /** How far each block moves under the strain of heat alone, or {@code null} if heat strains no joint. */
        private final double[] heatDisplacement;
        /** Each joint's load in first-order theory, as if the structure did not bow. */
        private final double[] unbowed;
        /** The force along each joint in the last solution as far as it presses the frame aside, positive pulling. */
        private final double[] axial;
        /** The number of each block in the last solution, or -1 if it fell, and how many were numbered. */
        private int[] number = new int[0];
        private int count;
        private int[] xs;
        private int[] ys;
        private int[] zs;
        /** The critical load factor of the last solution, or infinity if first-order theory judged it. */
        private double buckling = Double.POSITIVE_INFINITY;
        /**
         * In second-order theory, how far each block moves under its weight, under the strain of heat (or
         * {@code null} if heat strains no joint), and as its imperfect start bows; all {@code null} otherwise.
         */
        private double[] bowedWeight;
        private double[] bowedHeat;
        private double[] bow;
        /** The shape the frame buckles into if it cannot carry its loads at all, or {@code null}. */
        private double[] buckled;
        /** If it cannot, the critical load factor of its weight alone, without what heat presses it with. */
        private double coldBuckling = Double.POSITIVE_INFINITY;
        private final double tolerance;
        private final List<Crack> cracks = new ArrayList<>();
        private final List<String> notes = new ArrayList<>();
        /** Each joint's stiffness without a hinge. */
        private final double[][] base;
        /**
         * For a cracked joint that pivots on an edge, a point on its hinge line, y and z in metres from the joint's
         * axis, and the line's direction, y and z; {@code null} for a joint that does not.
         */
        private final double[][] pivot;
        /** How many times each joint's hinge has moved inward, and closed again. */
        private final int[] moves;
        private final int[] closes;
        /** For each joint, the stress resultants on its face in the case that loaded it most, when last checked. */
        private final double[][] governing;
        /** For each cracked joint, whether in that case the force through it acted beyond its patch. */
        private final boolean[] escaped;
        /** The force along each joint in the last solution, from the weight and heat, positive pulling. */
        private final double[] force;
        /**
         * For each joint, whether the frame buckling broke it when last checked: bowing overloads it, and a hinge in
         * it would only let the frame bow further.
         */
        private final boolean[] snapped;

        Run(Frame frame, Settings settings) {
            this.settings = settings;
            double weight = 0;
            for (Frame.Block b : frame.blocks()) {
                if (b.ground()) {
                    groundAt.put(b.pos(), b);
                } else {
                    nodeAt.put(b.pos(), free.size());
                    free.add(b);
                    weight += b.massKg() * settings.gravity();
                }
            }
            // Contacts may carry this much pull before they count as pulled apart: round-off, not load.
            this.tolerance = 1e-9 * weight + 1e-6;
            int n = free.size();
            solids = new Solid[n];
            for (int i = 0; i < n; i++) {
                Frame.Block b = free.get(i);
                solids[i] = Solid.of(b.mechanics(), b.temperatureK(), b.solidFraction(), settings.stiffnessFloor());
            }
            bonds = frame.bonds();
            int m = bonds.size();
            kind = new int[m];
            endI = new int[m];
            endJ = new int[m];
            stiffness = new double[m][];
            contact = new boolean[m];
            state = new Frame.Joint[m];
            open = new boolean[m];
            load = new double[m];
            withoutHeat = new double[m];
            unbowed = new double[m];
            axial = new double[m];
            mode = new Mode[m];
            heatShift = new double[m][];
            heatLoad = new double[m][];
            base = new double[m][];
            pivot = new double[m][];
            moves = new int[m];
            closes = new int[m];
            governing = new double[m][];
            escaped = new boolean[m];
            force = new double[m];
            snapped = new boolean[m];
            for (int e = 0; e < m; e++) {
                Frame.Bond b = bonds.get(e);
                Integer a = nodeAt.get(b.pos());
                Integer c = nodeAt.get(b.other());
                Solid sa = a == null ? null : solids[a];
                Solid sc = c == null ? null : solids[c];
                state[e] = b.state();
                if (a != null && c != null) {
                    kind[e] = FREE;
                    endI[e] = a;
                    endJ[e] = c;
                    stiffness[e] = BeamElement.stiffness(new double[] {0.5, 0.5}, new double[] {sa.youngs(), sc.youngs()},
                            new double[] {sa.shear(), sc.shear()}, b.contact());
                    contact[e] = sa.failure() == Failure.GRANULAR || sc.failure() == Failure.GRANULAR;
                } else if (a == null) {
                    kind[e] = GROUND_BELOW;
                    endI[e] = -1;
                    endJ[e] = c;
                    stiffness[e] = BeamElement.stiffness(new double[] {0.5}, new double[] {sc.youngs()},
                            new double[] {sc.shear()}, b.contact());
                    contact[e] = sc.failure() == Failure.GRANULAR || granular(groundAt.get(b.pos()));
                } else {
                    kind[e] = GROUND_ABOVE;
                    endI[e] = a;
                    endJ[e] = -1;
                    stiffness[e] = BeamElement.stiffness(new double[] {0.5}, new double[] {sa.youngs()},
                            new double[] {sa.shear()}, b.contact());
                    contact[e] = sa.failure() == Failure.GRANULAR || granular(groundAt.get(b.other()));
                }
            }
            System.arraycopy(stiffness, 0, base, 0, m);
            boolean heated = false;
            for (int e = 0; e < m; e++) {
                heatShift[e] = heatShift(e);
                if (heatShift[e] != null) {
                    heatLoad[e] = times(stiffness[e], heatShift[e]);
                    heated = true;
                }
            }
            alive = new boolean[n];
            Arrays.fill(alive, true);
            displacement = new double[BlockCholesky.B * n];
            heatDisplacement = heated ? new double[BlockCholesky.B * n] : null;
        }

        /**
         * Works out how far heat would move a joint's end j relative to its end i, held fixed, if nothing held end
         * j: the strain of each piece's matter stretches the beam, and where it changes across the beam, bends an
         * intact joint. A contact does not bend that way: its blocks rock on it instead, opening a hairline gap on
         * one side, so heat only presses it together or pulls it apart. Returns {@code null} if heat does neither.
         */
        private double[] heatShift(int e) {
            Frame.Bond b = bonds.get(e);
            int axis = b.axis();
            boolean bends = !contact[e] && state[e] == Frame.Joint.INTACT;
            double[] shift = new double[BeamElement.DOFS];
            boolean any = false;
            // Each piece of the beam lies in one block, from s0 to s1 along the beam, with the block's centre at c.
            if (kind[e] == FREE) {
                any |= addHeatShift(shift, free.get(endI[e]).expansion(), axis, 0.0, 0.5, 0.0, 1.0, bends);
                any |= addHeatShift(shift, free.get(endJ[e]).expansion(), axis, 0.5, 1.0, 1.0, 1.0, bends);
            } else if (kind[e] == GROUND_BELOW) {
                any |= addHeatShift(shift, free.get(endJ[e]).expansion(), axis, 0.0, 0.5, 0.5, 0.5, bends);
            } else {
                any |= addHeatShift(shift, free.get(endI[e]).expansion(), axis, 0.0, 0.5, 0.0, 0.5, bends);
            }
            return any ? shift : null;
        }

        /**
         * Adds what one piece of a beam does to the movement of its end j: its matter's strain along the beam,
         * which changes along it as the block's stretch says, stretches it, and, if it bends, the change of strain
         * across the beam bends it, the hotter side longer. Returns whether the piece moved the end at all.
         */
        private static boolean addHeatShift(double[] shift, Frame.Expansion x, int axis, double s0, double s1,
                double centre, double length, boolean bends) {
            double along = x.stretch(axis);
            double acrossY = bends ? x.gradient((axis + 1) % 3) : 0;
            double acrossZ = bends ? x.gradient((axis + 2) % 3) : 0;
            if (x.strain() == 0 && along == 0 && acrossY == 0 && acrossZ == 0) {
                return false;
            }
            double l = s1 - s0;
            shift[6] += x.strain() * l + along * ((s1 - centre) * (s1 - centre) - (s0 - centre) * (s0 - centre)) / 2;
            // Curvatures: matter longer toward +y bends the beam toward -y, turning it the negative way about z;
            // matter longer toward +z bends it toward -z, which turns it the positive way about y.
            double curveZ = -acrossY;
            double curveY = acrossZ;
            double moment = ((length - s0) * (length - s0) - (length - s1) * (length - s1)) / 2;
            shift[7] += curveZ * moment;
            shift[8] -= curveY * moment;
            shift[10] += curveY * l;
            shift[11] += curveZ * l;
            return true;
        }

        private static double[] times(double[] k, double[] v) {
            double[] out = new double[BeamElement.DOFS];
            for (int r = 0; r < BeamElement.DOFS; r++) {
                double sum = 0;
                for (int c = 0; c < BeamElement.DOFS; c++) {
                    sum += k[r * BeamElement.DOFS + c] * v[c];
                }
                out[r] = sum;
            }
            return out;
        }

        private static boolean granular(Frame.Block ground) {
            return ground != null && ground.mechanics() != null && ground.mechanics().failure() == Failure.GRANULAR;
        }

        Result run() {
            int rounds = 0;
            boolean settled = false;
            List<GridPos> falling = new ArrayList<>();
            boolean stale = true;
            while (true) {
                dropUnsupported(falling);
                if (!anyAlive()) {
                    buckling = Double.POSITIVE_INFINITY;
                    settled = true;
                    break;
                }
                // A joint that only cracked is as stiff as before, so the last solution still stands, unless heat
                // bent it; one that let go changes the frame, which must then be solved again.
                if (stale) {
                    if (rounds == settings.maxRounds()) {
                        break;
                    }
                    if (!solve()) {
                        notes.add("The structure could not be solved: part of it can move without bending "
                                + "anything.");
                        break;
                    }
                    rounds++;
                    // A hinge that loads have turned back closes, and the frame is solved again with it shut.
                    if (closeHinges()) {
                        continue;
                    }
                }
                double worst = evaluate();
                if (worst <= 1.0) {
                    settled = true;
                    break;
                }
                double threshold = rounds == settings.maxRounds() ? 1.0
                        : Math.max(1.0, worst * (1.0 - settings.together()));
                stale = giveWay(threshold);
            }
            if (!settled) {
                notes.add("Joints were still giving way after " + rounds + (rounds == 1 ? " solution" : " solutions")
                        + ", so the last let every overloaded joint go at once and what was left was not checked again.");
            }
            falling.sort(null);
            return result(rounds, settled, falling);
        }

        private boolean anyAlive() {
            for (boolean a : alive) {
                if (a) {
                    return true;
                }
            }
            return false;
        }

        private boolean active(int e) {
            if (open[e]) {
                return false;
            }
            return (endI[e] < 0 || alive[endI[e]]) && (endJ[e] < 0 || alive[endJ[e]]);
        }

        /** Lets every block with no path of holding joints to the ground fall. */
        private void dropUnsupported(List<GridPos> falling) {
            int n = free.size();
            boolean[] supported = new boolean[n];
            List<List<Integer>> neighbours = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                neighbours.add(new ArrayList<>());
            }
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            for (int e = 0; e < bonds.size(); e++) {
                if (!active(e)) {
                    continue;
                }
                if (kind[e] == FREE) {
                    neighbours.get(endI[e]).add(endJ[e]);
                    neighbours.get(endJ[e]).add(endI[e]);
                } else {
                    int node = kind[e] == GROUND_BELOW ? endJ[e] : endI[e];
                    if (!supported[node]) {
                        supported[node] = true;
                        queue.add(node);
                    }
                }
            }
            while (!queue.isEmpty()) {
                int i = queue.poll();
                for (int k : neighbours.get(i)) {
                    if (!supported[k]) {
                        supported[k] = true;
                        queue.add(k);
                    }
                }
            }
            for (int i = 0; i < n; i++) {
                if (alive[i] && !supported[i]) {
                    alive[i] = false;
                    falling.add(free.get(i).pos());
                    for (double[] moves : new double[][] {displacement, heatDisplacement, bowedWeight, bowedHeat, bow,
                        buckled}) {
                        if (moves != null) {
                            Arrays.fill(moves, BlockCholesky.B * i, BlockCholesky.B * i + BlockCholesky.B, 0);
                        }
                    }
                }
            }
        }

        /** Solves for the displacements of the blocks still standing; false if the equations are singular. */
        private boolean solve() {
            int n = free.size();
            number = new int[n];
            count = 0;
            for (int i = 0; i < n; i++) {
                number[i] = alive[i] ? count++ : -1;
            }
            xs = new int[count];
            ys = new int[count];
            zs = new int[count];
            double[] weight = new double[BlockCholesky.B * count];
            for (int i = 0; i < n; i++) {
                if (number[i] < 0) {
                    continue;
                }
                GridPos p = free.get(i).pos();
                xs[number[i]] = p.x();
                ys[number[i]] = p.y();
                zs[number[i]] = p.z();
                weight[BlockCholesky.B * number[i] + 1] = -free.get(i).massKg() * settings.gravity();
            }
            // The strain of heat loads the blocks as the forces that would hold each joint as it was.
            double[] heat = heatDisplacement == null ? null : new double[BlockCholesky.B * count];
            if (heat != null) {
                for (int e = 0; e < bonds.size(); e++) {
                    if (heatLoad[e] == null || !active(e)) {
                        continue;
                    }
                    int[] map = dofMap(bonds.get(e).axis());
                    for (int l = 0; l < 6; l++) {
                        if (endI[e] >= 0) {
                            heat[BlockCholesky.B * number[endI[e]] + map[l]] += heatLoad[e][l];
                        }
                        if (endJ[e] >= 0) {
                            heat[BlockCholesky.B * number[endJ[e]] + map[l]] += heatLoad[e][6 + l];
                        }
                    }
                }
            }
            BlockCholesky factor;
            try {
                factor = BlockCholesky.factor(assemble(e -> stiffness[e]), xs, ys, zs);
            } catch (BlockCholesky.SingularException e) {
                return false;
            }
            toNodes(solved(factor, weight), displacement);
            if (heat != null) {
                toNodes(solved(factor, heat), heatDisplacement);
            }
            buckling = Double.POSITIVE_INFINITY;
            bowedWeight = null;
            bowedHeat = null;
            bow = null;
            buckled = null;
            if (settings.buckling()) {
                stability(factor, weight, heat);
            }
            return true;
        }

        /**
         * Assembles a matrix for the blocks still standing from one 12 by 12 matrix per joint, in its own axes.
         *
         * @param matrixOf each joint's matrix, or {@code null} to leave the joint out
         */
        private BlockCholesky.Matrix assemble(IntFunction<double[]> matrixOf) {
            double[][] diagonal = new double[count][];
            for (int k = 0; k < count; k++) {
                diagonal[k] = new double[BlockCholesky.B * BlockCholesky.B];
            }
            List<int[]> pairList = new ArrayList<>();
            List<double[]> offList = new ArrayList<>();
            for (int e = 0; e < bonds.size(); e++) {
                if (!active(e)) {
                    continue;
                }
                double[] k = matrixOf.apply(e);
                if (k == null) {
                    continue;
                }
                int[] map = dofMap(bonds.get(e).axis());
                if (kind[e] == FREE) {
                    int a = number[endI[e]];
                    int b = number[endJ[e]];
                    addBlock(diagonal[a], k, 0, 0, map);
                    addBlock(diagonal[b], k, 6, 6, map);
                    double[] off = new double[BlockCholesky.B * BlockCholesky.B];
                    addBlock(off, k, 0, 6, map);
                    pairList.add(new int[] {a, b});
                    offList.add(off);
                } else if (kind[e] == GROUND_BELOW) {
                    addBlock(diagonal[number[endJ[e]]], k, 6, 6, map);
                } else {
                    addBlock(diagonal[number[endI[e]]], k, 0, 0, map);
                }
            }
            int[] pairs = new int[2 * pairList.size()];
            double[][] off = new double[pairList.size()][];
            for (int p = 0; p < pairList.size(); p++) {
                int a = pairList.get(p)[0];
                int b = pairList.get(p)[1];
                // The matrix wants the smaller node first; the stored block then belongs to (a, b) or its transpose.
                if (a < b) {
                    pairs[2 * p] = a;
                    pairs[2 * p + 1] = b;
                    off[p] = offList.get(p);
                } else {
                    pairs[2 * p] = b;
                    pairs[2 * p + 1] = a;
                    off[p] = transpose(offList.get(p));
                }
            }
            return new BlockCholesky.Matrix(diagonal, pairs, off);
        }

        /** Returns the solution of the factorized equations for one right-hand side, which is left as it is. */
        private static double[] solved(BlockCholesky factor, double[] rhs) {
            double[] x = rhs.clone();
            factor.solve(x);
            return x;
        }

        /** Copies movements of the numbered blocks to every block, zero for those that fell. */
        private void toNodes(double[] numbered, double[] target) {
            for (int i = 0; i < number.length; i++) {
                if (number[i] >= 0) {
                    System.arraycopy(numbered, BlockCholesky.B * number[i], target, BlockCholesky.B * i,
                            BlockCholesky.B);
                } else {
                    Arrays.fill(target, BlockCholesky.B * i, BlockCholesky.B * i + BlockCholesky.B, 0);
                }
            }
        }

        private double[] toNodes(double[] numbered) {
            double[] target = new double[BlockCholesky.B * free.size()];
            toNodes(numbered, target);
            return target;
        }

        /**
         * Works out how near the frame is to buckling under what presses it. If near enough to matter, it solves the
         * frame again in second-order theory, the stiffness lowered by what presses it and the frame bowed a little
         * to start with, as no real structure is straight; if the frame cannot carry its loads at all, it keeps the
         * shape the frame buckles into, for {@link #buckle} to break it by.
         *
         * <p>Heat that presses matter which yields makes it give a little and so lets the push go, as it lets go
         * its strain, so it cannot buckle a joint with a ductile side; brittle matter and cracked joints keep it.
         */
        private void stability(BlockCholesky factor, double[] weight, double[] heat) {
            double[] w = new double[BeamElement.DOFS];
            double[] t = new double[BeamElement.DOFS];
            double[][] geometric = new double[bonds.size()][];
            double[] cold = new double[bonds.size()];
            boolean pressed = false;
            boolean heated = false;
            for (int e = 0; e < bonds.size(); e++) {
                axial[e] = 0;
                if (!active(e)) {
                    continue;
                }
                elastic(e, displacement, w);
                double n = w[6];
                cold[e] = n;
                if (heatDisplacement != null && !yielding(e)) {
                    elastic(e, heatDisplacement, t);
                    double pressing = t[6] - (heatLoad[e] == null ? 0 : heatLoad[e][6]);
                    n += pressing;
                    heated |= pressing != 0;
                }
                axial[e] = n;
                if (n != 0) {
                    geometric[e] = geometric(e, n);
                }
                pressed |= n < -tolerance;
            }
            if (!pressed) {
                return;
            }
            BlockCholesky.Matrix kg = assemble(e -> geometric[e]);
            Buckling.Mode found = Buckling.critical(factor, kg, BlockCholesky.B * count, SECOND_ORDER_BELOW);
            if (!(found.factor() <= SECOND_ORDER_BELOW)) {
                return;
            }
            buckling = found.factor();
            double[] shape = toNodes(found.shape());
            if (buckling > 1) {
                BlockCholesky bowed = null;
                try {
                    bowed = BlockCholesky.factor(assemble(e -> geometric[e] == null ? stiffness[e]
                            : sum(stiffness[e], geometric[e])), xs, ys, zs);
                } catch (BlockCholesky.SingularException ex) {
                    // The estimate came out a little high: the frame cannot carry its loads after all.
                }
                if (bowed != null) {
                    double[] start = found.shape().clone();
                    double size = imperfection(shape);
                    for (int i = 0; i < start.length; i++) {
                        start[i] *= size;
                    }
                    // The frame's loads, tilted with its imperfect start, push it as K_G times that start would.
                    double[] push = BlockCholesky.multiply(kg, start);
                    for (int i = 0; i < push.length; i++) {
                        push[i] = -push[i];
                    }
                    bowedWeight = toNodes(solved(bowed, weight));
                    bowedHeat = heat == null ? null : toNodes(solved(bowed, heat));
                    bow = toNodes(solved(bowed, push));
                    return;
                }
            }
            buckled = shape;
            coldBuckling = buckling;
            if (heated) {
                double[][] weightOnly = new double[bonds.size()][];
                for (int e = 0; e < bonds.size(); e++) {
                    if (active(e) && cold[e] != 0) {
                        weightOnly[e] = geometric(e, cold[e]);
                    }
                }
                coldBuckling = Buckling.critical(factor, assemble(e -> weightOnly[e]), BlockCholesky.B * count, 1)
                        .factor();
            }
        }

        /** Returns a joint's geometric stiffness under a force along it, with its hinge if it pivots on an edge. */
        private double[] geometric(int e, double axial) {
            double length = kind[e] == FREE ? 1.0 : 0.5;
            double[] kg = BeamElement.geometric(length, axial, bonds.get(e).contact());
            if (pivot[e] == null) {
                return kg;
            }
            return BeamElement.releasedGeometric(kg, base[e], mode(e), HINGE_SOFTNESS, length,
                    kind[e] == GROUND_BELOW ? 0.0 : 0.5, axial, pivot[e][2], pivot[e][3]);
        }

        /** Returns a joint's stiffness plus its geometric stiffness. */
        private static double[] sum(double[] a, double[] b) {
            double[] c = new double[a.length];
            for (int i = 0; i < a.length; i++) {
                c[i] = a[i] + b[i];
            }
            return c;
        }

        /** Returns whether either side of a joint yields rather than breaks. */
        private boolean yielding(int e) {
            return yields(endI[e] >= 0 ? solids[endI[e]] : groundSolid(bonds.get(e).pos()))
                    || yields(endJ[e] >= 0 ? solids[endJ[e]] : groundSolid(bonds.get(e).other()));
        }

        /** Returns the Young's modulus of the softer of the blocks a joint's beam runs through. */
        private double softer(int e) {
            if (kind[e] == FREE) {
                return Math.min(solids[endI[e]].youngs(), solids[endJ[e]].youngs());
            }
            return solids[kind[e] == GROUND_BELOW ? endJ[e] : endI[e]].youngs();
        }

        /**
         * Returns how far the frame starts bowed, as a multiple of its buckling shape. As Eurocode 3 takes it (EN
         * 1993-1-1 5.3.2(11)), the shape is scaled so that where it bends a pressed joint most, it bends it as much
         * as a bow of one {@link #IMPERFECTION}th of its length bends a pinned column that buckles under the force
         * that buckles that joint.
         */
        private double imperfection(double[] shape) {
            double[] f = new double[BeamElement.DOFS];
            double sharpest = 0;
            double moment = 0;
            double force = 0;
            for (int e = 0; e < bonds.size(); e++) {
                if (!active(e) || !(axial[e] < -tolerance)) {
                    continue;
                }
                elastic(e, shape, f);
                double[] r = face(e, f);
                Contact c = bonds.get(e).contact();
                double youngs = softer(e);
                double bendY = r[4] / (youngs * c.inertiaT());
                double bendZ = r[5] / (youngs * c.inertiaS());
                double bend = Math.sqrt(bendY * bendY + bendZ * bendZ);
                if (bend > sharpest) {
                    sharpest = bend;
                    moment = Math.sqrt(r[4] * r[4] + r[5] * r[5]);
                    force = -axial[e];
                }
            }
            if (!(sharpest > 0)) {
                // The shape bends nothing pressed, which should not happen: bow it by that part of the frame's size.
                return extent() / IMPERFECTION;
            }
            double critical = buckling * force;
            double length = Math.PI * Math.sqrt(moment / sharpest / critical);
            return length / IMPERFECTION * critical / moment;
        }

        /** Returns the frame's largest size along an axis, in metres, counting the blocks still standing. */
        private double extent() {
            int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
            int[] hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            for (int i = 0; i < free.size(); i++) {
                if (alive[i]) {
                    GridPos p = free.get(i).pos();
                    int[] at = {p.x(), p.y(), p.z()};
                    for (int a = 0; a < 3; a++) {
                        lo[a] = Math.min(lo[a], at[a]);
                        hi[a] = Math.max(hi[a], at[a]);
                    }
                }
            }
            return Math.max(hi[0] - lo[0], Math.max(hi[1] - lo[1], hi[2] - lo[2])) + 1.0;
        }

        /**
         * Checks every joint that still holds against what it can take; returns the worst load. In second-order
         * theory a joint is judged with the frame bowed from its imperfect start either way, whichever loads it
         * more; if the frame cannot carry its loads at all, {@link #buckle} decides what breaks.
         */
        private double evaluate() {
            double worst = 0;
            double[] weight = new double[BeamElement.DOFS];
            double[] total = new double[BeamElement.DOFS];
            double[] bowedW = new double[BeamElement.DOFS];
            double[] bowedT = new double[BeamElement.DOFS];
            double[] start = new double[BeamElement.DOFS];
            double[] w = new double[BeamElement.DOFS];
            double[] t = new double[BeamElement.DOFS];
            Mode[] limit = new Mode[1];
            Mode[] other = new Mode[1];
            double[] seen = new double[7];
            double[] most = new double[7];
            for (int e = 0; e < bonds.size(); e++) {
                if (!active(e)) {
                    continue;
                }
                double[] f = forces(e, displacement, heatDisplacement, weight, total);
                force[e] = face(e, f)[0];
                double value = check(e, f, weight, limit, most);
                unbowed[e] = value;
                double cold = f == weight ? value : check(e, weight, weight, other, null);
                if (bowedWeight != null) {
                    double[] g = forces(e, bowedWeight, bowedHeat, bowedW, bowedT);
                    elastic(e, bow, start);
                    value = 0;
                    cold = 0;
                    for (int sign = 1; sign >= -1; sign -= 2) {
                        for (int r = 0; r < BeamElement.DOFS; r++) {
                            w[r] = bowedW[r] + sign * start[r];
                            t[r] = g[r] + sign * start[r];
                        }
                        double[] both = g == bowedW ? w : t;
                        double v = check(e, both, w, other, seen);
                        if (v > value || sign == 1) {
                            value = v;
                            limit[0] = other[0];
                            System.arraycopy(seen, 0, most, 0, most.length);
                        }
                        cold = Math.max(cold, both == w ? v : check(e, w, w, other, null));
                    }
                }
                load[e] = value;
                mode[e] = limit[0];
                withoutHeat[e] = cold;
                governing[e] = Arrays.copyOf(most, 6);
                escaped[e] = most[6] != 0;
                snapped[e] = false;
                worst = Math.max(worst, value);
            }
            if (buckled != null && worst <= 1.0) {
                worst = buckle(worst);
            }
            return worst;
        }

        /**
         * Computes the forces a joint's ends need for the given movements of the blocks, in its own axes: f = K d
         * for the weight into {@code weight}, and with the strain of heat f = K (d + d') - K s into {@code total},
         * where d' is how far heat moves the blocks and s how far it would move the joint's ends if nothing held
         * them. Returns {@code total}, or {@code weight} itself if heat strains nothing.
         */
        private double[] forces(int e, double[] moves, double[] heatMoves, double[] weight, double[] total) {
            elastic(e, moves, weight);
            if (heatMoves == null) {
                return weight;
            }
            elastic(e, heatMoves, total);
            for (int r = 0; r < BeamElement.DOFS; r++) {
                total[r] += weight[r] - (heatLoad[e] == null ? 0 : heatLoad[e][r]);
            }
            return total;
        }

        /** Computes K d for a joint: the forces its ends need for the given movements of the blocks, in its axes. */
        private void elastic(int e, double[] moves, double[] forces) {
            double[] d = new double[BeamElement.DOFS];
            nodal(e, moves, d);
            double[] k = stiffness[e];
            for (int r = 0; r < BeamElement.DOFS; r++) {
                double sum = 0;
                for (int c = 0; c < BeamElement.DOFS; c++) {
                    sum += k[r * BeamElement.DOFS + c] * d[c];
                }
                forces[r] = sum;
            }
        }

        /** Copies the movements of a joint's blocks into its own axes, zero at an end on the ground. */
        private void nodal(int e, double[] moves, double[] d) {
            int[] map = dofMap(bonds.get(e).axis());
            for (int l = 0; l < 6; l++) {
                d[l] = endI[e] >= 0 ? moves[BlockCholesky.B * endI[e] + map[l]] : 0;
                d[6 + l] = endJ[e] >= 0 ? moves[BlockCholesky.B * endJ[e] + map[l]] : 0;
            }
        }

        /**
         * Breaks a frame that cannot carry its loads: it bows further and further into the shape it buckles into,
         * one way or the other, until a joint breaks. The joints that break first, and those within the together
         * tolerance of them, are judged loaded by as many times what buckles the frame as it carries. Returns that,
         * or the worst load as it was if bowing breaks nothing.
         */
        private double buckle(double worst) {
            int m = bonds.size();
            double[][] weight = new double[m][];
            double[][] total = new double[m][];
            double[][] shape = new double[m][];
            for (int e = 0; e < m; e++) {
                if (active(e)) {
                    weight[e] = new double[BeamElement.DOFS];
                    total[e] = forces(e, displacement, heatDisplacement, weight[e], new double[BeamElement.DOFS]);
                    shape[e] = new double[BeamElement.DOFS];
                    elastic(e, buckled, shape[e]);
                }
            }
            double far = Double.POSITIVE_INFINITY;
            int way = 1;
            for (int sign = 1; sign >= -1; sign -= 2) {
                double a = firstBreak(sign, weight, total, shape);
                if (a < far) {
                    far = a;
                    way = sign;
                }
            }
            if (far == Double.POSITIVE_INFINITY) {
                notes.add("It is too slender to carry its loads, but bowing breaks none of its joints, so it was left "
                        + "standing.");
                return worst;
            }
            double[] at = new double[m];
            Mode[] modes = new Mode[m];
            Mode[] limit = new Mode[1];
            double peak = 0;
            for (int e = 0; e < m; e++) {
                if (active(e)) {
                    at[e] = loadAt(e, way * far, weight, total, shape, limit);
                    modes[e] = limit[0];
                    peak = Math.max(peak, at[e]);
                }
            }
            double over = 1 / buckling;
            for (int e = 0; e < m; e++) {
                if (active(e) && at[e] >= peak * (1 - settings.together())) {
                    load[e] = over;
                    mode[e] = modes[e];
                    snapped[e] = true;
                    // Weight alone breaks it too unless what heat presses the frame with is what buckles it.
                    if (!(coldBuckling > 1)) {
                        withoutHeat[e] = over;
                    }
                }
            }
            return over;
        }

        /**
         * Returns how far the frame bows into its buckling shape, one way, before a joint breaks, in metres of the
         * shape's largest movement; infinity if a bow of kilometres breaks none.
         */
        private double firstBreak(int sign, double[][] weight, double[][] total, double[][] shape) {
            double lo = 0;
            double hi = 1e-6;
            while (peak(sign * hi, weight, total, shape) < 1.0) {
                lo = hi;
                hi *= 2;
                if (hi > 1e4) {
                    return Double.POSITIVE_INFINITY;
                }
            }
            for (int i = 0; i < 40; i++) {
                double mid = 0.5 * (lo + hi);
                if (peak(sign * mid, weight, total, shape) < 1.0) {
                    lo = mid;
                } else {
                    hi = mid;
                }
            }
            return hi;
        }

        /** Returns the largest load of any joint with the frame bowed by a into its buckling shape. */
        private double peak(double a, double[][] weight, double[][] total, double[][] shape) {
            Mode[] limit = new Mode[1];
            double peak = 0;
            for (int e = 0; e < bonds.size(); e++) {
                if (active(e)) {
                    peak = Math.max(peak, loadAt(e, a, weight, total, shape, limit));
                }
            }
            return peak;
        }

        /** Returns a joint's load with the frame bowed by a into its buckling shape. */
        private double loadAt(int e, double a, double[][] weight, double[][] total, double[][] shape, Mode[] limit) {
            double[] w = new double[BeamElement.DOFS];
            for (int r = 0; r < BeamElement.DOFS; r++) {
                w[r] = weight[e][r] + a * shape[e][r];
            }
            if (total[e] == weight[e]) {
                return check(e, w, w, limit, null);
            }
            double[] f = new double[BeamElement.DOFS];
            for (int r = 0; r < BeamElement.DOFS; r++) {
                f[r] = total[e][r] + a * shape[e][r];
            }
            return check(e, f, w, limit, null);
        }

        /**
         * Returns how loaded a joint is, from its end forces, and what limits it. The joint is judged at the face
         * where its blocks meet, on each block's side: that is where a crack can form, and in a straight run the
         * bending moment changes little over the half block to the centre, while at a block where runs meet,
         * the centre is no single section at all.
         *
         * <p>Heat moves blocks by hairlines, not by lengths, so what it does to a joint depends on whether that
         * movement lets its strain go. Brittle matter cracks before it moves, so it takes the forces heat adds.
         * Matter that yields lets the strain of heat go, so its side takes only the forces of the weight. A contact
         * that heat alone would make slip, rock or open moves that hairline and lets the strain go too, so it holds
         * if it holds with heat or without, and heat that presses it together lends it friction; heat that presses
         * hard enough to crush its edge lets nothing go.
         *
         * @param f the forces at the joint's ends, heat included
         * @param weight the forces at its ends from the weight alone
         * @param seen if not {@code null}, receives the stress resultants on the joint's face, heat included, and
         *     then 1 if the force through a cracked joint acts beyond its patch, 0 if not
         */
        private double check(int e, double[] f, double[] weight, Mode[] limit, double[] seen) {
            Contact c = bonds.get(e).contact();
            double[] face = face(e, f);
            if (seen != null) {
                System.arraycopy(face, 0, seen, 0, 6);
                seen[6] = 0;
            }
            Solid a = endI[e] >= 0 ? solids[endI[e]] : groundSolid(bonds.get(e).pos());
            Solid b = endJ[e] >= 0 ? solids[endJ[e]] : groundSolid(bonds.get(e).other());
            if (contact[e] || state[e] == Frame.Joint.CRACKED) {
                double friction = Math.min(friction(a), friction(b));
                double crush = Math.min(compression(a), compression(b));
                double[] carried = f == weight ? face : face(e, weight);
                if (settings.arching()) {
                    return bearing(e, c, friction, crush, face, carried, limit, seen);
                }
                double holding = holding(c, friction, face, limit);
                if (f != weight) {
                    Mode[] m = new Mode[1];
                    double cold = holding(c, friction, carried, m);
                    if (cold < holding) {
                        holding = cold;
                        limit[0] = m[0];
                    }
                }
                if (limit[0] == Mode.PULLED_APART) {
                    return holding;
                }
                double crushing = crushing(c, crush, face);
                if (crushing > holding) {
                    limit[0] = Mode.CRUSHING;
                    return crushing;
                }
                return holding;
            }
            double[] carried = f == weight ? face : face(e, weight);
            double worst = strength(a, c, yields(a) ? carried : face, limit);
            Mode[] m = new Mode[1];
            double v = strength(b, c, yields(b) ? carried : face, m);
            if (v > worst) {
                worst = v;
                limit[0] = m[0];
            }
            return worst;
        }

        /**
         * Returns the stress resultants on a joint's section, on its positive face: at end i, at end j, or halfway,
         * where the blocks of a free joint meet. Forces are the same all along; moments change linearly.
         */
        private double[] face(int e, double[] f) {
            return switch (kind[e]) {
                case FREE -> new double[] {f[6], f[7], f[8], f[9], (f[10] - f[4]) * 0.5, (f[11] - f[5]) * 0.5};
                case GROUND_BELOW -> new double[] {-f[0], -f[1], -f[2], -f[3], -f[4], -f[5]};
                default -> new double[] {f[6], f[7], f[8], f[9], f[10], f[11]};
            };
        }

        private static boolean yields(Solid s) {
            return s != null && s.failure() == Failure.DUCTILE;
        }

        /**
         * Returns the ground's material where a joint meets it. The earth around and below a block of ground
         * hems it in, so pressing does not crush it; it can still be pulled apart or sheared off.
         */
        private Solid groundSolid(GridPos pos) {
            Frame.Block g = groundAt.get(pos);
            if (g == null || g.mechanics() == null) {
                return null;
            }
            Solid s = Solid.of(g.mechanics(), g.temperatureK(), g.solidFraction(), settings.stiffnessFloor());
            return new Solid(s.failure(), s.youngs(), s.shear(), s.tension(), Double.POSITIVE_INFINITY, s.friction());
        }

        private static double friction(Solid s) {
            return s == null ? Double.POSITIVE_INFINITY : s.friction();
        }

        private static double compression(Solid s) {
            return s == null ? Double.POSITIVE_INFINITY : s.compression();
        }

        /** Returns how loaded a section of an intact joint is for one material; zero for unbreakable ground. */
        private double strength(Solid s, Contact c, double[] r, Mode[] limit) {
            if (s == null) {
                limit[0] = Mode.TENSION;
                return 0;
            }
            return switch (s.failure()) {
                case DUCTILE -> ductile(s, c, r, limit);
                case BRITTLE -> brittle(s, c, r, limit);
                case GRANULAR -> {
                    // Granular joints are always contacts; reaching here would be a bug, so judge it as one.
                    yield contactLoad(c, s.friction(), s.compression(), r, limit);
                }
            };
        }

        /** Rankine's criterion: the largest principal stress against tensile strength, the smallest against crushing. */
        private static double brittle(Solid s, Contact c, double[] r, Mode[] limit) {
            double area = c.area();
            double n = r[0];
            double my = r[4];
            double mz = r[5];
            double iy = c.inertiaT();
            double iz = c.inertiaS();
            double worst = 0;
            limit[0] = Mode.TENSION;
            for (int k = 0; k < c.corners(); k++) {
                double y = c.cornerS(k);
                double z = c.cornerT(k);
                double sigma = n / area + my * z / iy - mz * y / iz;
                double u = sigma > 0 ? ratio(sigma, s.tension()) : ratio(-sigma, s.compression());
                if (u > worst) {
                    worst = u;
                    limit[0] = sigma > 0 ? Mode.TENSION : Mode.COMPRESSION;
                }
            }
            double sigma0 = n / area;
            double tau = c.peakShearStress(length(r[1], r[2]), r[3]);
            double radius = Math.sqrt(sigma0 * sigma0 * 0.25 + tau * tau);
            double first = sigma0 * 0.5 + radius;
            double third = sigma0 * 0.5 - radius;
            double u = Math.max(ratio(Math.max(first, 0), s.tension()), ratio(Math.max(-third, 0), s.compression()));
            if (u > worst) {
                worst = u;
                limit[0] = Mode.SHEAR;
            }
            return worst;
        }

        /**
         * A whole section yields when (N/Np)² + |My|/Mpy + |Mz|/Mpz reaches one, exact for a rectangle in one plane
         * and safe in two; shear above half its plastic capacity takes away bending strength as Eurocode 3 does.
         */
        private static double ductile(Solid s, Contact c, double[] r, Mode[] limit) {
            double fy = s.tension();
            double area = c.area();
            double n = ratio(Math.abs(r[0]), fy * area);
            double my = ratio(Math.abs(r[4]), fy * c.plasticModulusT());
            double mz = ratio(Math.abs(r[5]), fy * c.plasticModulusS());
            double shearYield = fy / Math.sqrt(3.0);
            double shear = ratio(length(r[1], r[2]), shearYield * area)
                    + ratio(Math.abs(r[3]), shearYield * c.plasticTorque());
            double bending = n * n + my + mz;
            if (shear > 0.5) {
                double rho = (2 * shear - 1) * (2 * shear - 1);
                bending = rho >= 1 ? Double.POSITIVE_INFINITY : bending / (1 - rho);
            }
            limit[0] = shear > bending ? Mode.SHEAR : Mode.YIELDING;
            return Math.max(bending, shear);
        }

        /**
         * A contact holds while it is pressed together, the resultant stays within the patch, the shear and twist
         * stay within friction, and the most pressed edge is not crushed.
         */
        private double contactLoad(Contact c, double friction, double crush, double[] r, Mode[] limit) {
            double holding = holding(c, friction, r, limit);
            if (limit[0] == Mode.PULLED_APART) {
                return holding;
            }
            double crushing = crushing(c, crush, r);
            if (crushing > holding) {
                limit[0] = Mode.CRUSHING;
                return crushing;
            }
            return holding;
        }

        /**
         * Returns how near a contact is to opening, rocking or slipping: pulled apart at all, the resultant beyond the
         * patch, or the shear and twist beyond friction.
         */
        private double holding(Contact c, double friction, double[] r, Mode[] limit) {
            double n = r[0];
            if (n > tolerance) {
                limit[0] = Mode.PULLED_APART;
                return 1.0 + n / tolerance;
            }
            // Forces and moments within the tolerance are round-off: a contact that carries nothing holds.
            double press = significant(-n);
            double shear = significant(length(r[1], r[2]));
            double twist = significant(Math.abs(r[3]));
            double my = significant(Math.abs(r[4]));
            double mz = significant(Math.abs(r[5]));
            double tip = Math.max(ratio(my, press * c.halfT()), ratio(mz, press * c.halfS()));
            double slide = Math.max(ratio(shear, friction * press), ratio(twist, friction * press * c.frictionRadius()));
            limit[0] = slide > tip ? Mode.SLIDING : Mode.TIPPING;
            return Math.max(tip, slide);
        }

        /** Returns how near the most pressed corner of a contact is to crushing, the pressure spread linearly. */
        private static double crushing(Contact c, double crush, double[] r) {
            double edge = 0;
            for (int k = 0; k < c.corners(); k++) {
                double sigma = r[0] / c.area() + r[4] * c.cornerT(k) / c.inertiaT() - r[5] * c.cornerS(k) / c.inertiaS();
                edge = Math.max(edge, -sigma);
            }
            return ratio(edge, crush);
        }

        /**
         * Judges a cracked or granular joint as the joints of an arch are judged. It holds while its blocks press
         * together, friction holds the shear and twist, and the pressing force acts where the patch can bear it:
         * spread evenly over the largest part of the patch centred where it acts, as masonry codes take a joint (EN
         * 1996-1-1 6.1.2.2), which shrinks to nothing at the edge, it must not crush the matter. How loaded the
         * joint is in that is how far out the force acts, over how far out it could act at this pressing: past 1, the
         * edge it nears crushes, or, with the force beyond the patch, the blocks tip. A joint pressed past what even
         * its whole patch can bear is crushed right through. A joint loaded beyond what it can take pivots on its edge
         * if it can, which {@link #giveWay} decides.
         *
         * <p>As for any contact, heat that alone would make it open, tip or slip moves it a hairline and lets the
         * strain go, so it holds if it holds with heat or without; but heat that presses it harder still presses it,
         * where its force acts, and crushing has nowhere to go.
         *
         * @param r the stress resultants on its face, heat included
         * @param cold those of the weight alone, or {@code r} itself if heat strains nothing
         * @param seen if not {@code null}, receives the stress resultants it is judged by: its force, pressing as
         *     hard as with heat, where it acts in the case that holds better; then 1 if that is beyond the patch
         */
        private double bearing(int e, Contact c, double friction, double crush, double[] r, double[] cold,
                Mode[] limit, double[] seen) {
            double[] chosen = rigid(e, c, friction, r);
            double[] face = r;
            if (cold != r) {
                double[] without = rigid(e, c, friction, cold);
                if (worst(without) < worst(chosen)) {
                    chosen = without;
                    face = cold;
                }
            }
            if (chosen[0] > 0) {
                // Pulled, but bent so hard that an edge would still press: it rocks open on that edge, unless it
                // already pivots there and the pull is on its hinge.
                limit[0] = pivot[e] == null && rocks(c, face) ? Mode.TIPPING : Mode.PULLED_APART;
                return chosen[0];
            }
            double acting = significant(-face[0]);
            double press = Math.max(acting, significant(-r[0]));
            double my = moment(face[4]);
            double mz = moment(face[5]);
            double y = acting > 0 ? mz / acting : 0;
            double z = acting > 0 ? -my / acting : 0;
            boolean beyond = chosen[1] > 1;
            if (seen != null) {
                seen[0] = -press;
                System.arraycopy(face, 1, seen, 1, 3);
                seen[4] = acting > 0 ? -z * press : face[4];
                seen[5] = acting > 0 ? y * press : face[5];
                seen[6] = beyond ? 1 : 0;
            }
            double outright = ratio(press, crush * c.effectiveArea(c.centroidY(), c.centroidZ()));
            if (outright > 1) {
                limit[0] = chosen[2] > outright ? Mode.SLIDING : Mode.CRUSHING;
                return Math.max(outright, chosen[2]);
            }
            double out;
            if (acting > 0) {
                double fraction = c.bearable(y, z, press / crush);
                out = fraction > 0 ? 1 / fraction : Double.POSITIVE_INFINITY;
            } else {
                out = my != 0 || mz != 0 ? Double.POSITIVE_INFINITY : 0;
            }
            if (beyond) {
                limit[0] = Mode.TIPPING;
            } else if (out >= chosen[2] && out >= outright) {
                // Short of the patch's edge, the force may still act where the edge cannot bear it: then it crushes.
                double crushing = acting > 0 ? ratio(press, crush * c.effectiveArea(y, z)) : 0;
                limit[0] = crushing > chosen[1] ? Mode.CRUSHING : Mode.TIPPING;
            } else {
                limit[0] = chosen[2] >= outright ? Mode.SLIDING : Mode.CRUSHING;
            }
            return Math.max(Math.max(out, chosen[2]), outright);
        }

        /** Returns whether some corner of a contact would be pressed, the stress spread linearly over it. */
        private static boolean rocks(Contact c, double[] r) {
            for (int k = 0; k < c.corners(); k++) {
                if (r[0] / c.area() + r[4] * c.cornerT(k) / c.inertiaT() - r[5] * c.cornerS(k) / c.inertiaS() < 0) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Returns how near a contact is to opening, tipping and slipping under one set of stress resultants: how far
         * it is pulled apart, zero if it is pressed; how far toward the edge of its patch the pressing force acts, 1 at
         * the edge; and how far shear and twist go beyond what friction holds. A hinge twists against friction only
         * along its line.
         */
        private double[] rigid(int e, Contact c, double friction, double[] r) {
            double n = r[0];
            if (n > tolerance) {
                return new double[] {1.0 + n / tolerance, 0, 0};
            }
            double press = significant(-n);
            double shear = significant(length(r[1], r[2]));
            double twist = significant(Math.abs(r[3]));
            double my = moment(r[4]);
            double mz = moment(r[5]);
            double tip;
            if (press > 0) {
                tip = c.reach(mz / press, -my / press);
            } else {
                tip = my != 0 || mz != 0 ? Double.POSITIVE_INFINITY : 0;
            }
            double radius = pivot[e] == null ? c.frictionRadius()
                    : c.chord(pivot[e][0], pivot[e][1], pivot[e][2], pivot[e][3]) / 4;
            double slide = Math.max(ratio(shear, friction * press), ratio(twist, friction * press * radius));
            return new double[] {0, tip, slide};
        }

        /** Returns the worst of how near a contact is to opening, tipping and slipping, from {@link #rigid}. */
        private static double worst(double[] rigid) {
            return rigid[0] > 0 ? rigid[0] : Math.max(rigid[1], rigid[2]);
        }

        /** Returns a moment, keeping its sign, or zero if it is within the tolerance. */
        private double moment(double value) {
            return Math.abs(value) > tolerance ? value : 0;
        }

        /** Returns a force in newtons, or a moment in newton metres, or zero if it is within the tolerance. */
        private double significant(double value) {
            return value > tolerance ? value : 0;
        }

        /** Returns the length of a vector in a plane. */
        private static double length(double a, double b) {
            return Math.sqrt(a * a + b * b);
        }

        /** Returns demand over capacity, infinite when there is demand and no capacity. */
        private static double ratio(double demand, double capacity) {
            if (demand <= 0) {
                return 0;
            }
            if (!(capacity > 0)) {
                return Double.POSITIVE_INFINITY;
            }
            return demand / capacity;
        }

        /**
         * Lets every joint loaded to at least the threshold give way: intact ones crack, cracked ones pivot on the
         * edge they press if they tip and can, and let go otherwise. Returns whether the frame must be solved again:
         * a joint let go or pivots, or one that cracked was bent by heat, which it no longer is.
         *
         * <p>Of a run of neighbouring joints that would start to pivot, joints that share blocks, only the most loaded
         * does, and the rest wait for the next solution: a block that starts to pivot eases the joints beside it, and
         * two hinges at once could leave the blocks between them free to swing, as no one hinge would.
         */
        private boolean giveWay(double threshold) {
            boolean changed = false;
            boolean[] bridge = settings.arching() ? bridges() : null;
            List<Integer> over = new ArrayList<>();
            for (int e = 0; e < bonds.size(); e++) {
                if (active(e) && load[e] >= threshold && load[e] > 1.0) {
                    over.add(e);
                }
            }
            double[][] at = new double[bonds.size()][];
            int[] run = new int[free.size()];
            for (int i = 0; i < run.length; i++) {
                run[i] = i;
            }
            for (int e : over) {
                if (contact[e] || state[e] == Frame.Joint.CRACKED) {
                    at[e] = pivotFor(e, bridge);
                    if (at[e] != null && pivot[e] == null && endI[e] >= 0 && endJ[e] >= 0) {
                        run[find(run, endI[e])] = find(run, endJ[e]);
                    }
                }
            }
            // The most loaded joint of each run starts to pivot, the first of them if several are loaded alike.
            int[] starts = new int[free.size()];
            Arrays.fill(starts, -1);
            for (int e : over) {
                if (at[e] != null && pivot[e] == null) {
                    int first = find(run, endI[e] >= 0 ? endI[e] : endJ[e]);
                    if (starts[first] < 0 || load[e] > load[starts[first]]) {
                        starts[first] = e;
                    }
                }
            }
            for (int e : over) {
                if (contact[e] || state[e] == Frame.Joint.CRACKED) {
                    if (at[e] == null) {
                        open[e] = true;
                    } else if (pivot[e] != null) {
                        moves[e]++;
                        hinge(e, at[e]);
                    } else if (starts[find(run, endI[e] >= 0 ? endI[e] : endJ[e])] == e) {
                        hinge(e, at[e]);
                    }
                    changed = true;
                } else {
                    state[e] = Frame.Joint.CRACKED;
                    Frame.Bond b = bonds.get(e);
                    cracks.add(new Crack(b.pos(), b.axis(), mode[e], load[e], withoutHeat[e] <= 1.0,
                            unbowed[e] <= 1.0));
                    if (heatShift[e] != null) {
                        double[] shift = heatShift(e);
                        if (!Arrays.equals(shift, heatShift[e])) {
                            heatShift[e] = shift;
                            heatLoad[e] = shift == null ? null : times(stiffness[e], shift);
                            changed = true;
                        }
                    }
                }
            }
            return changed;
        }

        /** Returns the first block of a block's run, halving the path there as it goes. */
        private static int find(int[] run, int block) {
            int b = block;
            while (run[b] != b) {
                run[b] = run[run[b]];
                b = run[b];
            }
            return b;
        }

        /**
         * Returns where an overloaded cracked joint would pivot on the edge it presses, if arches are on and it can,
         * as for {@link #hinge}: a point on the hinge line and its direction. It must tip or crush its edge, not open,
         * slip or crush right through; neither side may be granular, since loose grains have no edge to pivot on; the
         * blocks beyond it must be held by more than this joint, or they would only swing about the hinge; and the
         * frame buckling must not be what overloads it, since a hinge would only let the frame bow further. A joint
         * that already pivots moves its hinge inward as the force through it grows, up to {@link #HINGE_MOVES} times,
         * but lets go once that force acts beyond its patch, which means the blocks it joins can swing about their
         * hinges. Returns {@code null} if the joint cannot pivot, and so lets go.
         */
        private double[] pivotFor(int e, boolean[] bridge) {
            if (bridge == null || contact[e] || state[e] != Frame.Joint.CRACKED || snapped[e]
                    || (mode[e] != Mode.TIPPING && mode[e] != Mode.CRUSHING)) {
                return null;
            }
            if (pivot[e] == null ? bridge[e] : escaped[e] || moves[e] >= HINGE_MOVES) {
                return null;
            }
            return placeHinge(e);
        }

        /**
         * Works out where a cracked joint pivots: on the way from the patch's centroid toward where the force through
         * it acts, in the case that loaded it most, at the point where the largest part of the patch centred there
         * can take the force at the matter's crushing strength, with some room to spare. A joint pressed by nothing
         * at all pivots next to the edge its moment turns it toward. The hinge line runs across the direction in which
         * that part shrinks fastest, so along the edge where the force nears one edge, and it turns so that it opens
         * away from the centroid. A joint that already pivots keeps its line's direction and moves the line inward,
         * to where the part of the patch centred beside the force can take it: a force that wanders along the line
         * asks for a deeper hinge, as the part around it shrinks, but turning the line after it would only send it
         * wandering farther. Returns a point on the line, y and z in metres from the joint's axis, and its direction,
         * or {@code null} if the patch could not bear the force anywhere.
         */
        private double[] placeHinge(int e) {
            double[] r = governing[e];
            Contact c = bonds.get(e).contact();
            double crush = Math.min(compression(endI[e] >= 0 ? solids[endI[e]] : groundSolid(bonds.get(e).pos())),
                    compression(endJ[e] >= 0 ? solids[endJ[e]] : groundSolid(bonds.get(e).other())));
            double press = significant(-r[0]);
            double my = moment(r[4]);
            double mz = moment(r[5]);
            double cy = c.centroidY();
            double cz = c.centroidZ();
            double need = Math.max(press * HINGE_ROOM / crush, HINGE_EDGE * c.area());
            if (pivot[e] != null && press > 0) {
                // The force acts on the line, give or take how little the hinge still resists turning; from beside it
                // the line moves straight in, the way it opens away from.
                double[] line = pivot[e];
                double ny = -line[3];
                double nz = line[2];
                double off = (mz / press - line[0]) * ny + (-my / press - line[1]) * nz;
                double qy = mz / press - off * ny;
                double qz = -my / press - off * nz;
                double in = c.inward(qy, qz, ny, nz, need);
                return Double.isNaN(in) ? null : new double[] {qy + in * ny, qz + in * nz, line[2], line[3]};
            }
            // A point the force acts toward: where it acts, or for a joint pressed by nothing, a metre from the
            // centroid the way its moment turns it, beyond any patch.
            double py;
            double pz;
            if (press > 0) {
                py = mz / press;
                pz = -my / press;
            } else {
                double turn = length(my, mz);
                if (turn == 0) {
                    return null;
                }
                py = cy + mz / turn;
                pz = cz - my / turn;
            }
            double fraction = c.bearable(py, pz, need);
            if (!(fraction > 0 && fraction < Double.POSITIVE_INFINITY)) {
                return null;
            }
            double hy = cy + fraction * (py - cy);
            double hz = cz + fraction * (pz - cz);
            double[] grow = c.effectiveAreaGradient(hy, hz);
            double gy = grow[0];
            double gz = grow[1];
            double steep = length(gy, gz);
            if (!(steep > 0)) {
                gy = cy - py;
                gz = cz - pz;
                steep = length(gy, gz);
            }
            double ay = -gz / steep;
            double az = gy / steep;
            // Turning the far side of the joint the positive way about the hinge must lift the centroid's side away.
            if (ay * (cz - hz) - az * (cy - hy) < 0) {
                ay = -ay;
                az = -az;
            }
            return new double[] {hy, hz, ay, az};
        }

        /** Puts a hinge in a cracked joint, from {@link #placeHinge}: it turns freely about the line given. */
        private void hinge(int e, double[] at) {
            pivot[e] = at;
            stiffness[e] = BeamElement.released(base[e], mode(e), HINGE_SOFTNESS);
            heatLoad[e] = heatShift[e] == null ? null : times(stiffness[e], heatShift[e]);
        }

        /** Shuts a joint's hinge: it bends as stiffly as before it pivoted. */
        private void unhinge(int e) {
            pivot[e] = null;
            stiffness[e] = base[e];
            heatLoad[e] = heatShift[e] == null ? null : times(stiffness[e], heatShift[e]);
        }

        /** Returns how a hinged joint's ends move as its hinge turns, in the joint's axes. */
        private double[] mode(int e) {
            double[] p = pivot[e];
            return BeamElement.hingeMode(kind[e] == FREE ? 1.0 : 0.5, kind[e] == GROUND_BELOW ? 0.0 : 0.5, p[0], p[1],
                    p[2], p[3]);
        }

        /**
         * Shuts every hinge that the last solution turned back, closing: loads that found another path have pressed
         * the joint flat on its patch again. Each hinge closes at most {@link #HINGE_CLOSES} times, so a joint the
         * loads cannot settle on stops changing. Returns whether any closed.
         */
        private boolean closeHinges() {
            boolean changed = false;
            double[] d = new double[BeamElement.DOFS];
            double[] h = new double[BeamElement.DOFS];
            for (int e = 0; e < bonds.size(); e++) {
                if (pivot[e] == null || !active(e) || closes[e] >= HINGE_CLOSES) {
                    continue;
                }
                nodal(e, displacement, d);
                if (heatDisplacement != null) {
                    nodal(e, heatDisplacement, h);
                    for (int r = 0; r < BeamElement.DOFS; r++) {
                        d[r] += h[r] - (heatShift[e] == null ? 0 : heatShift[e][r]);
                    }
                }
                if (BeamElement.hingeTurn(base[e], mode(e), HINGE_SOFTNESS, d) < -HINGE_CLOSING) {
                    unhinge(e);
                    closes[e]++;
                    changed = true;
                }
            }
            return changed;
        }

        /**
         * Finds the joints that alone hold some blocks to the ground: those whose loss would cut blocks off, the
         * bridges of the graph of blocks and holding joints, the ground taken as one block. Tarjan's method, without
         * recursion, so that a long chain of blocks cannot overflow the stack.
         */
        private boolean[] bridges() {
            int n = free.size();
            int ground = n;
            int m = bonds.size();
            int[] head = new int[n + 1];
            Arrays.fill(head, -1);
            int[] next = new int[2 * m];
            int[] to = new int[2 * m];
            int[] via = new int[2 * m];
            int edges = 0;
            for (int e = 0; e < m; e++) {
                if (!active(e)) {
                    continue;
                }
                int u = endI[e] >= 0 ? endI[e] : ground;
                int v = endJ[e] >= 0 ? endJ[e] : ground;
                to[edges] = v;
                via[edges] = e;
                next[edges] = head[u];
                head[u] = edges++;
                to[edges] = u;
                via[edges] = e;
                next[edges] = head[v];
                head[v] = edges++;
            }
            boolean[] bridge = new boolean[m];
            int[] found = new int[n + 1];
            Arrays.fill(found, -1);
            int[] low = new int[n + 1];
            int[] stack = new int[n + 1];
            int[] entered = new int[n + 1];
            int[] cursor = new int[n + 1];
            int time = 0;
            int depth = 0;
            stack[depth] = ground;
            entered[depth] = -1;
            cursor[depth] = head[ground];
            found[ground] = time;
            low[ground] = time++;
            depth++;
            while (depth > 0) {
                int top = depth - 1;
                int u = stack[top];
                int edge = cursor[top];
                if (edge >= 0) {
                    cursor[top] = next[edge];
                    if (via[edge] == entered[top]) {
                        continue;
                    }
                    int v = to[edge];
                    if (found[v] < 0) {
                        found[v] = time;
                        low[v] = time++;
                        stack[depth] = v;
                        entered[depth] = via[edge];
                        cursor[depth] = head[v];
                        depth++;
                    } else {
                        low[u] = Math.min(low[u], found[v]);
                    }
                } else {
                    depth--;
                    if (depth > 0) {
                        int parent = stack[depth - 1];
                        low[parent] = Math.min(low[parent], low[u]);
                        if (low[u] > found[parent]) {
                            bridge[entered[depth]] = true;
                        }
                    }
                }
            }
            return bridge;
        }

        /**
         * Returns how far one degree of freedom moved under weight and heat together, and as the frame bows from its
         * imperfect start if it is slender enough to.
         */
        private double moved(int dof) {
            if (bowedWeight != null) {
                return bowedWeight[dof] + bow[dof] + (bowedHeat == null ? 0 : bowedHeat[dof]);
            }
            return heatDisplacement == null ? displacement[dof] : displacement[dof] + heatDisplacement[dof];
        }

        private Result result(int rounds, boolean settled, List<GridPos> falling) {
            int n = free.size();
            double[] nodeLoad = new double[n];
            List<BondResult> bondResults = new ArrayList<>(bonds.size());
            for (int e = 0; e < bonds.size(); e++) {
                Frame.Bond b = bonds.get(e);
                boolean holds = active(e);
                bondResults.add(new BondResult(b.pos(), b.axis(), state[e], holds, load[e], mode[e], withoutHeat[e],
                        unbowed[e], holds && pivot[e] != null, force[e]));
                if (holds) {
                    if (endI[e] >= 0) {
                        nodeLoad[endI[e]] = Math.max(nodeLoad[endI[e]], load[e]);
                    }
                    if (endJ[e] >= 0) {
                        nodeLoad[endJ[e]] = Math.max(nodeLoad[endJ[e]], load[e]);
                    }
                }
            }
            List<BlockResult> blockResults = new ArrayList<>(n);
            double largestMove = 0;
            double largestTurn = 0;
            for (int i = 0; i < n; i++) {
                int o = BlockCholesky.B * i;
                double[] move = {moved(o), moved(o + 1), moved(o + 2)};
                double[] turn = {moved(o + 3), moved(o + 4), moved(o + 5)};
                if (alive[i]) {
                    largestMove = Math.max(largestMove, Math.sqrt(move[0] * move[0] + move[1] * move[1]
                            + move[2] * move[2]));
                    largestTurn = Math.max(largestTurn, Math.sqrt(turn[0] * turn[0] + turn[1] * turn[1]
                            + turn[2] * turn[2]));
                }
                blockResults.add(new BlockResult(free.get(i).pos(), !alive[i], move, turn, alive[i] ? nodeLoad[i] : 0));
            }
            if (largestMove > LARGE_DEFLECTION_M || largestTurn > LARGE_ROTATION) {
                notes.add(String.format(Locale.ROOT, "Parts deflect by up to %.2f m and turn by up to "
                        + "%.2f rad; real material would sag visibly, and small-deflection theory may misjudge it.",
                        largestMove, largestTurn));
            }
            return new Result(Collections.unmodifiableList(blockResults), Collections.unmodifiableList(bondResults),
                    List.copyOf(cracks), List.copyOf(falling), rounds, settled, buckling, List.copyOf(notes));
        }
    }

    /**
     * Returns, for a joint along an axis, the global degree of freedom of each local one: local x is the joint's
     * axis and local y and z follow it in cyclic order, so the local frame is right-handed.
     */
    static int[] dofMap(int axis) {
        int y = (axis + 1) % 3;
        int z = (axis + 2) % 3;
        return new int[] {axis, y, z, 3 + axis, 3 + y, 3 + z};
    }

    /** Adds the 6 by 6 part of a joint's matrix at (row, col) to a global block, mapping local to global DOFs. */
    private static void addBlock(double[] target, double[] k, int row, int col, int[] map) {
        for (int p = 0; p < 6; p++) {
            for (int q = 0; q < 6; q++) {
                target[map[p] * 6 + map[q]] += k[(row + p) * BeamElement.DOFS + col + q];
            }
        }
    }

    private static double[] transpose(double[] m) {
        double[] t = new double[36];
        for (int a = 0; a < 6; a++) {
            for (int c = 0; c < 6; c++) {
                t[c * 6 + a] = m[a * 6 + c];
            }
        }
        return t;
    }

    /**
     * Returns the direction a joint along an axis points from its negative block to its positive one.
     *
     * @param axis the axis
     * @return east, up or south
     */
    public static Direction direction(int axis) {
        return Direction.POSITIVE.get(axis);
    }
}
