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

/**
 * Works out whether a structure stands under its own weight, and what breaks if it does not.
 *
 * <p>The frame's free blocks carry their weight to the ground through their joints. The analysis solves for how
 * far each block moves under that weight, linear and elastic, then checks every joint: an intact joint against
 * the strength of the materials on each side at their temperatures, a cracked or granular one against what
 * pressing and friction can hold. If something is overloaded, the worst joint gives way: an intact joint cracks,
 * a cracked one lets go. Loads then find other paths, so the analysis solves again, until nothing more breaks.
 * Blocks left with no path to the ground fall.
 *
 * <p>Failure is judged at the faces where blocks meet, on each block's side, because that is where the
 * structure's cross-section is narrowest and where a crack can form, and because a straight run of blocks that is
 * overloaded inside a block is overloaded at the face next to it too: the bending moment changes little over half
 * a block. The ground is hemmed in by the earth around and below it, so pressing does not crush it, but a joint
 * to it can still crack on its side: stone cannot hang from soil.
 *
 * <p>What the analysis leaves out, so far: a ductile joint that yields gives way at once, where a real steel
 * frame would keep its full plastic moment there and hand on the rest, so redundant metal frames fall somewhat
 * early; cracked joints do not wedge into arches; slender columns do not buckle; and deflections are taken to be
 * small. Where they turn out large the result says so.
 */
public final class StructuralAnalysis {

    /** Deflections beyond this many metres make small-deflection theory doubtful. */
    static final double LARGE_DEFLECTION_M = 0.1;

    /** Rotations beyond this many radians make small-deflection theory doubtful. */
    static final double LARGE_ROTATION = 0.1;

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
     */
    public record Settings(double gravity, int maxRounds, double together, double stiffnessFloor) {

        /**
         * Validates the settings.
         *
         * @param gravity the gravity
         * @param maxRounds the round limit
         * @param together the tolerance for giving way together
         * @param stiffnessFloor the stiffness floor
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
         * together, and materials keep at least one ten-thousandth of their stiffness.
         *
         * @return the settings
         */
        public static Settings defaults() {
            return new Settings(PhysicalConstants.STANDARD_GRAVITY, 64, 0.02, 1e-4);
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
     * @param displacement how far its centre moved under load, x, y and z in metres; zeros if it fell
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
     */
    public record BondResult(GridPos pos, int axis, Frame.Joint state, boolean holds, double load, Mode mode) {
    }

    /**
     * A joint that cracked in this analysis.
     *
     * @param pos the block on the joint's negative side
     * @param axis the axis the joint runs along
     * @param mode how it gave way
     * @param load its load when it gave way, as a fraction of what it could take
     */
    public record Crack(GridPos pos, int axis, Mode mode, double load) {
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
     * @param notes where the result is less certain than usual, in plain words
     */
    public record Result(List<BlockResult> blocks, List<BondResult> bonds, List<Crack> cracks, List<GridPos> falling,
            int rounds, boolean settled, List<String> notes) {

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
        private final Mode[] mode;
        private final boolean[] alive;
        private final double[] displacement;
        private final double tolerance;
        private final List<Crack> cracks = new ArrayList<>();
        private final List<String> notes = new ArrayList<>();

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
            mode = new Mode[m];
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
            alive = new boolean[n];
            Arrays.fill(alive, true);
            displacement = new double[BlockCholesky.B * n];
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
                    settled = true;
                    break;
                }
                // A joint that only cracked is as stiff as before, so the last solution still stands; one
                // that let go changes the frame, which must then be solved again.
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
                    Arrays.fill(displacement, BlockCholesky.B * i, BlockCholesky.B * i + BlockCholesky.B, 0);
                }
            }
        }

        /** Solves for the displacements of the blocks still standing; false if the equations are singular. */
        private boolean solve() {
            int n = free.size();
            int[] number = new int[n];
            int count = 0;
            for (int i = 0; i < n; i++) {
                number[i] = alive[i] ? count++ : -1;
            }
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
                int[] map = dofMap(bonds.get(e).axis());
                double[] k = stiffness[e];
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
            int[] xs = new int[count];
            int[] ys = new int[count];
            int[] zs = new int[count];
            double[] rhs = new double[BlockCholesky.B * count];
            for (int i = 0; i < n; i++) {
                if (number[i] < 0) {
                    continue;
                }
                GridPos p = free.get(i).pos();
                xs[number[i]] = p.x();
                ys[number[i]] = p.y();
                zs[number[i]] = p.z();
                rhs[BlockCholesky.B * number[i] + 1] = -free.get(i).massKg() * settings.gravity();
            }
            BlockCholesky factor;
            try {
                factor = BlockCholesky.factor(new BlockCholesky.Matrix(diagonal, pairs, off), xs, ys, zs);
            } catch (BlockCholesky.SingularException e) {
                return false;
            }
            factor.solve(rhs);
            for (int i = 0; i < n; i++) {
                if (number[i] >= 0) {
                    System.arraycopy(rhs, BlockCholesky.B * number[i], displacement, BlockCholesky.B * i,
                            BlockCholesky.B);
                }
            }
            return true;
        }

        /**
         * Checks every joint that still holds against what it can take; returns the worst load.
         */
        private double evaluate() {
            double worst = 0;
            double[] forces = new double[BeamElement.DOFS];
            Mode[] limit = new Mode[1];
            for (int e = 0; e < bonds.size(); e++) {
                if (!active(e)) {
                    continue;
                }
                endForces(e, forces);
                double value = check(e, forces, limit);
                load[e] = value;
                mode[e] = limit[0];
                worst = Math.max(worst, value);
            }
            return worst;
        }

        /** Computes the forces a joint's ends need, in its own axes: f = K d. */
        private void endForces(int e, double[] forces) {
            int[] map = dofMap(bonds.get(e).axis());
            double[] d = new double[BeamElement.DOFS];
            if (endI[e] >= 0) {
                for (int l = 0; l < 6; l++) {
                    d[l] = displacement[BlockCholesky.B * endI[e] + map[l]];
                }
            }
            if (endJ[e] >= 0) {
                for (int l = 0; l < 6; l++) {
                    d[6 + l] = displacement[BlockCholesky.B * endJ[e] + map[l]];
                }
            }
            double[] k = stiffness[e];
            for (int r = 0; r < BeamElement.DOFS; r++) {
                double sum = 0;
                for (int c = 0; c < BeamElement.DOFS; c++) {
                    sum += k[r * BeamElement.DOFS + c] * d[c];
                }
                forces[r] = sum;
            }
        }

        /**
         * Returns how loaded a joint is, from its end forces, and what limits it. The joint is judged at the face
         * where its blocks meet, on each block's side: that is where a crack can form, and in a straight run the
         * bending moment changes little over the half block to the centre, while at a block where runs meet,
         * the centre is no single section at all.
         */
        private double check(int e, double[] f, Mode[] limit) {
            Contact c = bonds.get(e).contact();
            // Stress resultants on the section's positive face: at end i, at end j, or halfway, where the blocks
            // of a free joint meet. Forces are the same all along; moments change linearly.
            double[] face = switch (kind[e]) {
                case FREE -> new double[] {f[6], f[7], f[8], f[9], (f[10] - f[4]) * 0.5, (f[11] - f[5]) * 0.5};
                case GROUND_BELOW -> new double[] {-f[0], -f[1], -f[2], -f[3], -f[4], -f[5]};
                default -> new double[] {f[6], f[7], f[8], f[9], f[10], f[11]};
            };
            Solid a = endI[e] >= 0 ? solids[endI[e]] : groundSolid(bonds.get(e).pos());
            Solid b = endJ[e] >= 0 ? solids[endJ[e]] : groundSolid(bonds.get(e).other());
            if (contact[e] || state[e] == Frame.Joint.CRACKED) {
                double friction = Math.min(friction(a), friction(b));
                double crush = Math.min(compression(a), compression(b));
                return contactLoad(c, friction, crush, face, limit);
            }
            double worst = strength(a, c, face, limit);
            Mode[] m = new Mode[1];
            double v = strength(b, c, face, m);
            if (v > worst) {
                worst = v;
                limit[0] = m[0];
            }
            return worst;
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
            double worst = 0;
            limit[0] = Mode.TIPPING;
            double tip = Math.max(ratio(my, press * c.halfT()), ratio(mz, press * c.halfS()));
            if (tip > worst) {
                worst = tip;
                limit[0] = Mode.TIPPING;
            }
            double slide = Math.max(ratio(shear, friction * press), ratio(twist, friction * press * c.frictionRadius()));
            if (slide > worst) {
                worst = slide;
                limit[0] = Mode.SLIDING;
            }
            // The most pressed corner, with the pressure spread linearly as if the whole patch bore.
            double edge = 0;
            for (int k = 0; k < c.corners(); k++) {
                double sigma = r[0] / c.area() + r[4] * c.cornerT(k) / c.inertiaT() - r[5] * c.cornerS(k) / c.inertiaS();
                edge = Math.max(edge, -sigma);
            }
            double crushing = ratio(edge, crush);
            if (crushing > worst) {
                worst = crushing;
                limit[0] = Mode.CRUSHING;
            }
            return worst;
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
         * Lets every joint loaded to at least the threshold give way: intact ones crack, cracked ones let go.
         * Returns whether any let go.
         */
        private boolean giveWay(double threshold) {
            boolean opened = false;
            for (int e = 0; e < bonds.size(); e++) {
                if (!active(e) || load[e] < threshold || load[e] <= 1.0) {
                    continue;
                }
                boolean asContact = contact[e] || state[e] == Frame.Joint.CRACKED;
                if (asContact) {
                    open[e] = true;
                    opened = true;
                } else {
                    state[e] = Frame.Joint.CRACKED;
                    Frame.Bond b = bonds.get(e);
                    cracks.add(new Crack(b.pos(), b.axis(), mode[e], load[e]));
                }
            }
            return opened;
        }

        private Result result(int rounds, boolean settled, List<GridPos> falling) {
            int n = free.size();
            double[] nodeLoad = new double[n];
            List<BondResult> bondResults = new ArrayList<>(bonds.size());
            for (int e = 0; e < bonds.size(); e++) {
                Frame.Bond b = bonds.get(e);
                boolean holds = active(e);
                bondResults.add(new BondResult(b.pos(), b.axis(), state[e], holds, load[e], mode[e]));
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
                double[] move = {displacement[o], displacement[o + 1], displacement[o + 2]};
                double[] turn = {displacement[o + 3], displacement[o + 4], displacement[o + 5]};
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
                    List.copyOf(cracks), List.copyOf(falling), rounds, settled, List.copyOf(notes));
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
