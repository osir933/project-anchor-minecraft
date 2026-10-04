package io.github.osir933.anchor.neoforge;

import io.github.osir933.anchor.core.instrument.ProbeSet;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Ready-made experiments, each built with one command in front of whoever asks, on a bench of smooth stone:
 * <ul>
 *   <li>{@code cooling}: a block of iron at 1227 °C cools in still air, glowing less and less;</li>
 *   <li>{@code conduction}: rods of copper, iron, stone and brick stand on lava, and the heat climbs them at very
 *   different speeds;</li>
 *   <li>{@code melting}: ice and stone at -10 °C warm side by side on hot plates, and the ice stops at 0 °C while it
 *   melts;</li>
 *   <li>{@code insulation}: three blocks of iron at 150 °C cool, one bare, one wrapped in glass and one wrapped in
 *   wool.</li>
 * </ul>
 *
 * <p>Building one clears its space, puts its blocks in place, sets the temperatures it starts from, puts probes where
 * its results show and saves it all as a snapshot, so that it can be run again from the start. Each was sized with
 * the simulation itself to show its result within minutes, a few of them faster with heat sped up; the numbers in
 * their descriptions are what the simulation gives in a laboratory's air at 20 °C.
 *
 * <p>An experiment is only built where its space is clear: air or plants above the bench, and nothing with contents,
 * such as a chest, where the bench goes. Where it pours lava into its bench, the ground under the bench must be solid
 * to hold it. Heat must already run in all of its space.
 */
final class Experiments {

    /** How far ahead of the player an experiment's space begins, in blocks, so that they stand clear of it. */
    static final int AHEAD = 2;

    /** Each experiment is saved as the snapshot of this name followed by its own when built, to be run again. */
    static final String SNAPSHOT_PREFIX = "experiment-";

    /** What every experiment stands on: a bench of smooth stone, which Anchor takes for granite. */
    private static final BlockState BENCH = Blocks.SMOOTH_STONE.defaultBlockState();

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState LAVA = Blocks.LAVA.defaultBlockState();
    private static final BlockState IRON = Blocks.IRON_BLOCK.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState BRICKS = Blocks.BRICKS.defaultBlockState();
    private static final BlockState GLASS = Blocks.GLASS.defaultBlockState();
    private static final BlockState ICE = Blocks.PACKED_ICE.defaultBlockState();
    private static final BlockState WOOL = Blocks.WOOL.white().defaultBlockState();
    /** Waxed, so that it stays bright copper in worlds where copper weathers. */
    private static final BlockState COPPER = Blocks.COPPER_BLOCK.waxed().unaffected().defaultBlockState();

    /** The temperature the cooling iron starts at: 1500 K, a bright orange glow. */
    static final double COOLING_START_K = 1500.0;

    /** The temperature the ice and stone of the melting experiment start at: -10 °C, as in a freezer. */
    static final double MELTING_START_K = 263.15;

    /** The temperature the irons of the insulation experiment start at: 150 °C, which wool can take. */
    static final double INSULATION_START_K = 423.15;

    /**
     * A ready-made experiment. Its space runs from {@code left} blocks left of its middle to {@code right} blocks right
     * of it, {@code depth} blocks ahead and {@code height} blocks up, on a bench one block deep that it builds itself.
     *
     * @param name its name in commands
     * @param title its title, in English
     * @param summary what it shows, in English
     * @param watch what to look for while it runs, in English
     * @param left how far its space reaches to the left of its middle, in blocks
     * @param right how far its space reaches to the right of its middle, in blocks
     * @param depth how far its space reaches ahead, in blocks
     * @param height how tall its space is above the bench, in blocks
     * @param speed how many times faster than normal heat had best run to show the result in a few minutes; 1 if
     *     it shows soon enough as it is
     * @param design what it builds and how it starts
     */
    record Experiment(String name, String title, String summary, String watch, int left, int right, int depth,
            int height, int speed, Consumer<Plan> design) {

        /**
         * Returns the experiment's title, translated where a translation exists.
         *
         * @return the title
         */
        Component titleText() {
            return Component.translatableWithFallback("experiment.anchor." + name, title);
        }

        /**
         * Returns what the experiment shows, translated where a translation exists.
         *
         * @return the summary
         */
        Component summaryText() {
            return Component.translatableWithFallback("experiment.anchor." + name + ".summary", summary);
        }

        /**
         * Returns what to look for while the experiment runs, translated where a translation exists.
         *
         * @return the advice
         */
        Component watchText() {
            return Component.translatableWithFallback("experiment.anchor." + name + ".watch", watch);
        }

        /**
         * Returns the name of the snapshot the experiment is saved as when built.
         *
         * @return the snapshot's name
         */
        String snapshot() {
            return SNAPSHOT_PREFIX + name;
        }

        /**
         * Returns the plan of what the experiment builds.
         *
         * @return a fresh plan
         */
        Plan plan() {
            Plan plan = new Plan();
            design.accept(plan);
            return plan;
        }
    }

    /** A block of iron glowing at 1500 K, on the bench, with a probe on its top and one in its middle. */
    static final Experiment COOLING = new Experiment("cooling", "Glowing iron cools",
            "A block of iron at 1227 °C cools in still air. Its glow fades from orange through dull red to nothing in "
                    + "about five minutes.",
            "Its top cools faster than its middle: the surface radiates heat and warms the air around it, while "
                    + "the inside must first conduct its heat out to the surface. Hold the thermal camera to watch it "
                    + "cool.",
            1, 1, 3, 2, 1, Experiments::cooling);

    /** Four rods three blocks tall, each standing on a block of lava set into the bench, with a probe at its top. */
    static final Experiment CONDUCTION = new Experiment("conduction", "Conduction race",
            "Rods of copper, iron, stone and brick stand on lava. Copper carries the heat to its top first, iron "
                    + "follows, and stone and brick barely warm at the top.",
            "The probes at the tops of the rods record the race. Hold the thermal camera to see the heat climb "
                    + "each rod.",
            4, 4, 3, 4, 5, Experiments::conduction);

    /** Ice and stone at -10 °C in glass cups on copper plates over lava, with a probe in the middle of each. */
    static final Experiment MELTING = new Experiment("melting", "Melting takes heat",
            "Ice and stone at -10 °C warm side by side on copper hot plates over lava. The stone warms on past 0 °C; "
                    + "the ice stops there until it has melted.",
            "Ice and stone take in heat much alike, so their lines run together until the ice reaches 0 °C. Then "
                    + "the heat goes into melting it rather than warming it: that heat is the latent heat of fusion.",
            3, 3, 3, 3, 10, Experiments::melting);

    /** Three blocks of iron at 150 °C: one held in the air, one inside glass and one inside wool. */
    static final Experiment INSULATION = new Experiment("insulation", "Insulation",
            "Three blocks of iron at 150 °C: one bare, one wrapped in glass and one wrapped in wool. The bare one "
                    + "cools fastest, and the wool keeps its iron hot for hours.",
            "Wool is mostly trapped air and conducts heat almost 30 times worse than glass. The bare iron is held "
                    + "in the air, so that it touches nothing.",
            6, 6, 3, 4, 1, Experiments::insulation);

    /** Every experiment, in the order they are listed. */
    static final List<Experiment> ALL = List.of(COOLING, CONDUCTION, MELTING, INSULATION);

    private Experiments() {
    }

    /**
     * Returns the names of the experiments.
     *
     * @return the names, in the order they are listed
     */
    static List<String> names() {
        List<String> names = new ArrayList<>();
        for (Experiment e : ALL) {
            names.add(e.name());
        }
        return names;
    }

    /**
     * Finds an experiment by name.
     *
     * @param name the name, in any case
     * @return the experiment, or empty if there is none of that name
     */
    static Optional<Experiment> find(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (Experiment e : ALL) {
            if (e.name().equals(lower)) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    private static void cooling(Plan plan) {
        plan.block(0, 0, 1, IRON);
        plan.temperature(0, 0, 1, COOLING_START_K);
        plan.probe("top", 0, 0, 1, 1.0);
        plan.probe("middle", 0, 0, 1, 0.5);
    }

    private static void conduction(Plan plan) {
        String[] names = {"copper", "iron", "stone", "brick"};
        BlockState[] rods = {COPPER, IRON, STONE, BRICKS};
        for (int r = 0; r < rods.length; r++) {
            int right = 2 * r - 3;
            plan.block(right, -1, 1, LAVA);
            for (int up = 0; up < 3; up++) {
                plan.block(right, up, 1, rods[r]);
            }
            plan.probe(names[r], right, 2, 1, 0.5);
        }
    }

    private static void melting(Plan plan) {
        for (int middle : new int[] {-2, 2}) {
            plan.block(middle, -1, 1, LAVA);
            for (int right = middle - 1; right <= middle + 1; right++) {
                for (int ahead = 0; ahead <= 2; ahead++) {
                    if (right != middle || ahead != 1) {
                        plan.block(right, 0, ahead, BRICKS);
                        plan.block(right, 1, ahead, GLASS);
                    }
                }
            }
            plan.block(middle, 0, 1, COPPER);
        }
        plan.block(-2, 1, 1, ICE);
        plan.block(2, 1, 1, STONE);
        plan.temperature(-2, 1, 1, MELTING_START_K);
        plan.temperature(2, 1, 1, MELTING_START_K);
        plan.probe("ice", -2, 1, 1, 0.5);
        plan.probe("stone", 2, 1, 1, 0.5);
    }

    private static void insulation(Plan plan) {
        plan.box(-1, 0, 0, 1, 2, 2, GLASS);
        plan.box(4, 0, 0, 6, 2, 2, WOOL);
        String[] names = {"bare", "glass", "wool"};
        int[] middles = {-5, 0, 5};
        for (int i = 0; i < middles.length; i++) {
            plan.block(middles[i], 1, 1, IRON);
            plan.temperature(middles[i], 1, 1, INSULATION_START_K);
            plan.probe(names[i], middles[i], 1, 1, 0.5);
        }
    }

    /**
     * Where an experiment is built: the middle of the near edge of its space, at the height of the bench's top, and the
     * way ahead. Its own right, up and ahead turn with the way ahead, so it is built the same whichever way the player
     * faces.
     *
     * @param origin the block at the middle of the near edge of the experiment's space, just above the bench
     * @param forward the way ahead, along the ground
     */
    record Frame(BlockPos origin, Direction forward) {

        /**
         * Checks the way ahead.
         *
         * @param origin the origin
         * @param forward the way ahead
         * @throws IllegalArgumentException if the way ahead is up or down
         */
        Frame {
            if (forward.getStepY() != 0) {
                throw new IllegalArgumentException("an experiment is built along the ground, not " + forward);
            }
        }

        /**
         * Returns the frame of an experiment built in front of someone: {@link #AHEAD} blocks ahead of where they
         * stand, at the height of their feet, the way they face.
         *
         * @param position where they are
         * @param yaw which way they face, in degrees as the game measures it
         * @return the frame
         */
        static Frame inFrontOf(Vec3 position, float yaw) {
            Direction forward = Direction.fromYRot(yaw);
            return new Frame(BlockPos.containing(position).relative(forward, AHEAD), forward);
        }

        /**
         * Returns the block at a place in the frame.
         *
         * @param right how far to the right of the origin
         * @param up how far above it
         * @param ahead how far ahead of it
         * @return the block
         */
        BlockPos at(int right, int up, int ahead) {
            Direction side = forward.getClockWise();
            return origin.offset(side.getStepX() * right + forward.getStepX() * ahead, up,
                    side.getStepZ() * right + forward.getStepZ() * ahead);
        }

        /**
         * Returns the lowest corner of an experiment's space, its bench included.
         *
         * @param experiment the experiment
         * @return the corner
         */
        BlockPos min(Experiment experiment) {
            return BlockPos.min(near(experiment), far(experiment));
        }

        /**
         * Returns the highest corner of an experiment's space.
         *
         * @param experiment the experiment
         * @return the corner
         */
        BlockPos max(Experiment experiment) {
            return BlockPos.max(near(experiment), far(experiment));
        }

        private BlockPos near(Experiment experiment) {
            return at(-experiment.left(), -1, 0);
        }

        private BlockPos far(Experiment experiment) {
            return at(experiment.right(), experiment.height() - 1, experiment.depth() - 1);
        }
    }

    /** What an experiment puts where and how it starts, in its own frame: blocks right, up and ahead of its origin. */
    static final class Plan {
        private final Map<Spot, BlockState> blocks = new LinkedHashMap<>();
        private final Map<Spot, Double> temperatures = new LinkedHashMap<>();
        private final List<ProbeSpot> probes = new ArrayList<>();

        private Plan() {
        }

        /**
         * Puts a block somewhere, in place of anything planned there before. Liquids go only into the bench, which
         * holds them in at the sides, with solid ground under it.
         *
         * @throws IllegalArgumentException if a liquid would go above the bench
         */
        void block(int right, int up, int ahead, BlockState state) {
            if (!state.getFluidState().isEmpty() && up != -1) {
                throw new IllegalArgumentException("liquids go into the bench, not " + up + " blocks above it");
            }
            blocks.put(new Spot(right, up, ahead), state);
        }

        /** Fills a box with one block, corners included. */
        void box(int right0, int up0, int ahead0, int right1, int up1, int ahead1, BlockState state) {
            for (int right = right0; right <= right1; right++) {
                for (int up = up0; up <= up1; up++) {
                    for (int ahead = ahead0; ahead <= ahead1; ahead++) {
                        block(right, up, ahead, state);
                    }
                }
            }
        }

        /** Starts a block at a temperature, in kelvin, keeping its matter. */
        void temperature(int right, int up, int ahead, double kelvin) {
            temperatures.put(new Spot(right, up, ahead), kelvin);
        }

        /** Puts a probe in the middle of a block, at a height within it: 0.5 for its middle and 1 for its top. */
        void probe(String name, int right, int up, int ahead, double height) {
            probes.add(new ProbeSpot(name, new Spot(right, up, ahead), height));
        }

        /**
         * Returns the names the experiment gives its probes, in its order; probes are given another name if one is
         * already taken.
         *
         * @return the names
         */
        List<String> probeNames() {
            List<String> names = new ArrayList<>();
            for (ProbeSpot p : probes) {
                names.add(p.name());
            }
            return names;
        }

        /**
         * Returns how many blocks the experiment starts at a temperature of their own.
         *
         * @return the count
         */
        int startingTemperatures() {
            return temperatures.size();
        }
    }

    /** A block in an experiment's frame. */
    private record Spot(int right, int up, int ahead) {
    }

    /** A probe an experiment puts in place: its name, its block and the height within the block it measures. */
    private record ProbeSpot(String name, Spot block, double height) {
    }

    /**
     * An experiment as built.
     *
     * @param experiment the experiment
     * @param min the lowest corner of its space, bench included
     * @param max the highest corner of its space
     * @param probes the probes it put in place, in its order
     * @param snapshot the snapshot it was saved as, or {@code null} if it could not be saved
     * @param problems what did not work out, such as probes that could not be added; empty if all went well
     */
    record Built(Experiment experiment, BlockPos min, BlockPos max, List<ProbeSet.Probe> probes, String snapshot,
            List<Component> problems) {
    }

    /**
     * What came of building an experiment: what was built, or why nothing was.
     *
     * @param built what was built, or {@code null}
     * @param refusal why nothing was built, or {@code null}
     */
    record Outcome(Built built, Component refusal) {

        private static Outcome refused(Component why) {
            return new Outcome(null, why);
        }
    }

    /**
     * Builds an experiment, starts it, puts its probes in place and saves it as a snapshot.
     *
     * @param level the level
     * @param heat the level's heat
     * @param experiment the experiment
     * @param frame where to build it
     * @return what was built, or why nothing was
     */
    static Outcome build(ServerLevel level, LevelHeat heat, Experiment experiment, Frame frame) {
        BlockPos min = frame.min(experiment);
        BlockPos max = frame.max(experiment);
        if (min.getY() < level.getMinY() || max.getY() >= level.getMinY() + level.getHeight()) {
            return Outcome.refused(Component.translatableWithFallback("message.anchor.experiment.outside",
                    "There is no room for %s between the bottom and the top of the world here.",
                    experiment.titleText()));
        }
        if (!heat.simulates(min, max)) {
            return Outcome.refused(Component.translatableWithFallback("message.anchor.experiment.not_simulated",
                    "Heat does not run in all of the space %s needs yet: wait a moment after arriving and try again.",
                    experiment.titleText()));
        }
        Optional<BlockPos> obstacle = obstacle(level, min, max);
        if (obstacle.isPresent()) {
            BlockPos at = obstacle.get();
            return Outcome.refused(Component.translatableWithFallback("message.anchor.experiment.blocked",
                    "%s needs a clear space %s blocks wide, %s deep and %s tall in front of you, and %s is in the way "
                            + "at %s.", experiment.titleText(), experiment.left() + experiment.right() + 1,
                    experiment.depth(), experiment.height(), level.getBlockState(at).getBlock().getName(),
                    at.getX() + " " + at.getY() + " " + at.getZ()));
        }
        Plan plan = experiment.plan();
        Optional<Map.Entry<Spot, BlockState>> unheld = unheld(level, plan, frame);
        if (unheld.isPresent()) {
            Spot s = unheld.get().getKey();
            BlockPos at = frame.at(s.right(), s.up() - 1, s.ahead());
            return Outcome.refused(Component.translatableWithFallback("message.anchor.experiment.unheld",
                    "%s needs solid ground under its bench to hold the %s in it, and there is none at %s.",
                    experiment.titleText(), unheld.get().getValue().getBlock().getName(),
                    at.getX() + " " + at.getY() + " " + at.getZ()));
        }
        place(level, plan, frame, min, max);
        List<Component> problems = new ArrayList<>();
        for (Map.Entry<Spot, Double> start : plan.temperatures.entrySet()) {
            Spot s = start.getKey();
            if (!heat.setTemperature(frame.at(s.right(), s.up(), s.ahead()), start.getValue())) {
                problems.add(Component.translatableWithFallback("message.anchor.experiment.not_started",
                        "A block of it could not be given its starting temperature."));
            }
        }
        List<ProbeSet.Probe> probes = addProbes(level, plan, frame, problems);
        String snapshot = experiment.snapshot();
        try {
            if (Snapshots.save(level, heat, snapshot, min, max).isEmpty()) {
                snapshot = null;
            }
        } catch (IOException e) {
            snapshot = null;
        }
        if (snapshot == null) {
            problems.add(Component.translatableWithFallback("message.anchor.experiment.not_saved",
                    "It could not be saved as a snapshot, so it cannot be run again with /anchor snapshot restore."));
        }
        return new Outcome(new Built(experiment, min, max, List.copyOf(probes), snapshot, List.copyOf(problems)), null);
    }

    /**
     * Finds the first thing in the way of an experiment: above its bench, anything but air and plants; where its bench
     * goes, anything with contents of its own, such as a chest or a furnace.
     */
    private static Optional<BlockPos> obstacle(ServerLevel level, BlockPos min, BlockPos max) {
        for (int y = min.getY(); y <= max.getY(); y++) {
            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    boolean clear = y == min.getY() ? !state.hasBlockEntity()
                            : state.isAir() || state.canBeReplaced() && state.getFluidState().isEmpty();
                    if (!clear) {
                        return Optional.of(pos);
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** Finds the first liquid in an experiment's bench that the ground under the bench would not hold. */
    private static Optional<Map.Entry<Spot, BlockState>> unheld(ServerLevel level, Plan plan, Frame frame) {
        for (Map.Entry<Spot, BlockState> block : plan.blocks.entrySet()) {
            Spot s = block.getKey();
            if (!block.getValue().getFluidState().isEmpty()) {
                BlockPos below = frame.at(s.right(), s.up() - 1, s.ahead());
                if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
                    return Optional.of(block);
                }
            }
        }
        return Optional.empty();
    }

    /** Clears an experiment's space from the top down, lays its bench and puts its blocks in, liquids last. */
    private static void place(ServerLevel level, Plan plan, Frame frame, BlockPos min, BlockPos max) {
        for (int y = max.getY(); y >= min.getY(); y--) {
            for (int x = min.getX(); x <= max.getX(); x++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    level.setBlock(new BlockPos(x, y, z), y == min.getY() ? BENCH : AIR, Block.UPDATE_ALL);
                }
            }
        }
        for (boolean liquids : new boolean[] {false, true}) {
            for (Map.Entry<Spot, BlockState> block : plan.blocks.entrySet()) {
                BlockState state = block.getValue();
                if (state.getFluidState().isEmpty() != liquids) {
                    Spot s = block.getKey();
                    level.setBlock(frame.at(s.right(), s.up(), s.ahead()), state, Block.UPDATE_ALL);
                }
            }
        }
    }

    /** Puts an experiment's probes in place, each under its own name or, if that is taken, the first free one after. */
    private static List<ProbeSet.Probe> addProbes(ServerLevel level, Plan plan, Frame frame,
            List<Component> problems) {
        ProbeSet set = ProbeCommands.probes(level).set();
        List<ProbeSet.Probe> added = new ArrayList<>();
        for (ProbeSpot p : plan.probes) {
            String name = p.name();
            for (int n = 2; set.get(name).isPresent(); n++) {
                name = p.name() + n;
            }
            BlockPos pos = frame.at(p.block().right(), p.block().up(), p.block().ahead());
            Vec3 at = new Vec3(pos.getX() + 0.5, pos.getY() + p.height(), pos.getZ() + 0.5);
            ProbeCommands.Outcome outcome = ProbeCommands.add(level, pos, at, name);
            Optional<ProbeSet.Probe> probe = set.get(name);
            if (outcome.done() && probe.isPresent()) {
                added.add(probe.get());
            } else {
                problems.add(outcome.message());
            }
        }
        return added;
    }
}
