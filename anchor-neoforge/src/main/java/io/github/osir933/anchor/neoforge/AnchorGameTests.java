package io.github.osir933.anchor.neoforge;

import io.github.osir933.anchor.core.host.GlowingBlock;
import io.github.osir933.anchor.core.host.HostedWorld;
import io.github.osir933.anchor.core.host.Pacer;
import io.github.osir933.anchor.core.host.RegionSnapshot;
import io.github.osir933.anchor.core.host.SectionSnapshot;
import io.github.osir933.anchor.core.instrument.ChartImage;
import io.github.osir933.anchor.core.instrument.ProbeSet;
import io.github.osir933.anchor.core.instrument.TimeSeries;
import io.github.osir933.anchor.core.instrument.TimeSeriesCsv;
import io.github.osir933.anchor.core.physics.thermal.Sky;
import io.github.osir933.anchor.core.space.CellId;
import io.github.osir933.anchor.core.space.Direction;
import io.github.osir933.anchor.core.space.GridPos;
import io.github.osir933.anchor.core.world.BlockCopy;
import io.github.osir933.anchor.core.world.Provenance;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Checks that run on a real server with the mod loaded: {@code ./gradlew :anchor-neoforge:runGameTestServer},
 * which CI runs too. Each test builds a small scene on a stone floor, keeps it simulated, and waits for the
 * simulation to do what physics says it should. The game puts a roof of barriers over each test, which keeps the
 * sun off; tests of the sun run in an environment of their own without one, so the game runs them apart from the
 * rest, and hold the sky still. Tests that pause heat or change its speed do so for the whole dimension, so they too
 * run in an environment of their own, and so does each test of a ready-made experiment, which asks heat for the steps
 * it needs to show its result.
 */
final class AnchorGameTests {

    /** The environment most tests run in. */
    private static final String HEAT = "heat";

    /** The environment of tests open to the sky, which hold the sky still. */
    private static final String SUNLIT = "sunlit";

    /** The environment of tests that pace heat in the whole dimension, which the game runs apart from the rest. */
    private static final String CLOCK = "clock";

    /** The structure most tests are built in: a floor of stone, five blocks square. */
    private static final String EMPTY = "empty";

    /**
     * The structure the tests of ready-made experiments are built in: two layers of stone, fifteen blocks wide and five
     * deep, with room for the widest experiment and its bench on top.
     */
    private static final String BENCH = "bench";

    /**
     * A test: its name, how many game ticks it may take, what it does, the environment it runs in and the structure it
     * is built in.
     */
    private record Case(String name, int maxTicks, Consumer<GameTestHelper> body, String environment,
            String structure) {

        Case(String name, int maxTicks, Consumer<GameTestHelper> body) {
            this(name, maxTicks, body, HEAT);
        }

        Case(String name, int maxTicks, Consumer<GameTestHelper> body, String environment) {
            this(name, maxTicks, body, environment, EMPTY);
        }

        /** A test of a ready-made experiment, in an environment of its own on the bench. */
        static Case experiment(String name, int maxTicks, Consumer<GameTestHelper> body) {
            return new Case(name, maxTicks, body, name, BENCH);
        }
    }

    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS =
            DeferredRegister.create(Registries.TEST_FUNCTION, AnchorMod.MOD_ID);

    private static final List<Case> CASES = List.of(
            new Case("ice_melts_when_warmed", 400, AnchorGameTests::iceMeltsWhenWarmed),
            new Case("water_freezes_when_chilled", 400, AnchorGameTests::waterFreezesWhenChilled),
            new Case("water_boils_away", 400, AnchorGameTests::waterBoilsAway),
            new Case("block_placed_this_tick_takes_the_temperature", 400,
                    AnchorGameTests::blockPlacedThisTickTakesTheTemperature),
            new Case("torch_warms_the_air", 800, AnchorGameTests::torchWarmsTheAir),
            new Case("heat_comes_back_when_a_chunk_loads_again", 400,
                    AnchorGameTests::heatComesBackWhenAChunkLoadsAgain),
            new Case("saved_heat_keeps_its_numbers", 20, AnchorGameTests::savedHeatKeepsItsNumbers),
            new Case("thermal_camera_sees_a_hot_block", 200, AnchorGameTests::thermalCameraSeesAHotBlock),
            new Case("thermal_camera_sees_warm_air", 600, AnchorGameTests::thermalCameraSeesWarmAir),
            new Case("thermal_camera_explains_itself", 20, AnchorGameTests::thermalCameraExplainsItself),
            new Case("hot_iron_warms_stone_across_air", 600, AnchorGameTests::hotIronWarmsStoneAcrossAir),
            new Case("stone_beside_lava_is_refined", 200, AnchorGameTests::stoneBesideLavaIsRefined),
            new Case("hot_iron_glows_where_it_shows", 200, AnchorGameTests::hotIronGlowsWhereItShows),
            new Case("black_wool_warms_more_in_the_sun", 400, AnchorGameTests::blackWoolWarmsMoreInTheSun, SUNLIT),
            new Case("probe_records_a_cooling_block", 400, AnchorGameTests::probeRecordsACoolingBlock),
            new Case("thermometer_leaves_and_takes_a_probe", 200, AnchorGameTests::thermometerLeavesAndTakesAProbe),
            new Case("saved_probes_keep_their_numbers", 20, AnchorGameTests::savedProbesKeepTheirNumbers),
            new Case("thermometer_explains_itself", 20, AnchorGameTests::thermometerExplainsItself),
            new Case("paused_heat_holds_and_steps_by_hand", 600, AnchorGameTests::pausedHeatHoldsAndStepsByHand,
                    CLOCK),
            new Case("snapshot_rewinds_melted_ice", 600, AnchorGameTests::snapshotRewindsMeltedIce),
            new Case("snapshot_file_keeps_every_number", 20, AnchorGameTests::snapshotFileKeepsEveryNumber),
            new Case("laboratory_air_is_steady", 20, AnchorGameTests::laboratoryAirIsSteady),
            Case.experiment("experiment_cooling", 1000, AnchorGameTests::experimentCooling),
            Case.experiment("experiment_conduction", 3500, AnchorGameTests::experimentConduction),
            Case.experiment("experiment_melting", 5000, AnchorGameTests::experimentMelting),
            Case.experiment("experiment_insulation", 2000, AnchorGameTests::experimentInsulation),
            new Case("unsupported_block_falls", 400, AnchorGameTests::unsupportedBlockFalls),
            new Case("fence_holds_up_a_block", 400, AnchorGameTests::fenceHoldsUpABlock),
            new Case("pillar_falls_when_its_foot_is_taken", 400, AnchorGameTests::pillarFallsWhenItsFootIsTaken),
            new Case("built_blocks_stay_built_when_a_chunk_loads_again", 400,
                    AnchorGameTests::builtBlocksStayBuiltWhenAChunkLoadsAgain));

    private AnchorGameTests() {
    }

    /**
     * Registers the tests.
     *
     * @param modEventBus the mod's event bus
     */
    static void register(IEventBus modEventBus) {
        for (Case c : CASES) {
            Consumer<GameTestHelper> body = c.body();
            FUNCTIONS.register(c.name(), () -> body);
        }
        FUNCTIONS.register(modEventBus);
        modEventBus.addListener(AnchorGameTests::registerTests);
    }

    private static void registerTests(RegisterGameTestsEvent event) {
        TreeMap<String, Holder<TestEnvironmentDefinition<?>>> environments = new TreeMap<>();
        for (Case c : CASES) {
            Holder<TestEnvironmentDefinition<?>> environment = environments.computeIfAbsent(c.environment(),
                    name -> event.registerEnvironment(AnchorMod.id(name)));
            ResourceKey<Consumer<GameTestHelper>> function = ResourceKey.create(Registries.TEST_FUNCTION,
                    AnchorMod.id(c.name()));
            TestData<Holder<TestEnvironmentDefinition<?>>> data = c.environment().equals(SUNLIT)
                    ? new TestData<>(environment, Level.OVERWORLD, AnchorMod.id(c.structure()), c.maxTicks(), 0, true,
                            Rotation.NONE, false, 1, 1, true, 0)
                    : new TestData<>(environment, AnchorMod.id(c.structure()), c.maxTicks(), 0, true);
            event.registerTest(AnchorMod.id(c.name()), new FunctionGameTestInstance(function, data));
        }
    }

    /** Packed ice warmed above its melting point turns into water. Vanilla never melts packed ice. */
    private static void iceMeltsWhenWarmed(GameTestHelper helper) {
        BlockPos ice = new BlockPos(2, 1, 2);
        helper.setBlock(ice, Blocks.PACKED_ICE);
        setTemperatureThenExpect(helper, ice, 290.0, Blocks.WATER);
    }

    /** Still water chilled below its freezing point turns into ice. */
    private static void waterFreezesWhenChilled(GameTestHelper helper) {
        BlockPos water = pool(helper);
        setTemperatureThenExpect(helper, water, 250.0, Blocks.ICE);
    }

    /** Still water heated past its boiling point turns into steam, which leaves the air behind. */
    private static void waterBoilsAway(GameTestHelper helper) {
        BlockPos water = pool(helper);
        setTemperatureThenExpect(helper, water, 400.0, Blocks.AIR);
    }

    /**
     * A temperature set on a block placed earlier in the same tick goes to that block, not to the air that
     * was there before, even though block changes normally reach the simulation a tick later.
     */
    private static void blockPlacedThisTickTakesTheTemperature(GameTestHelper helper) {
        BlockPos relative = new BlockPos(2, 1, 2);
        BlockPos pos = helper.absolutePos(relative);
        int[] stage = {0};
        helper.succeedWhen(() -> {
            LevelHeat heat = heat(helper);
            if (stage[0] == 0) {
                heat.keepSimulated(pos);
                stage[0] = 1;
            }
            if (stage[0] == 1) {
                if (heat.inspect(pos).isEmpty()) {
                    throw helper.assertionException(Component.literal("waiting for the air to be simulated"));
                }
                helper.setBlock(relative, Blocks.PACKED_ICE);
                helper.assertTrue(heat.setTemperature(pos, 290.0), "the new ice could not be warmed");
                stage[0] = 2;
            }
            helper.assertBlockPresent(Blocks.WATER, relative);
            heat.release(pos);
        });
    }

    /** A torch warms the air above it, and a thermometer can read it. */
    private static void torchWarmsTheAir(GameTestHelper helper) {
        BlockPos torch = new BlockPos(2, 1, 2);
        helper.setBlock(torch, Blocks.TORCH);
        BlockPos above = helper.absolutePos(torch.above());
        boolean[] pinned = {false};
        helper.succeedWhen(() -> {
            LevelHeat heat = heat(helper);
            if (!pinned[0]) {
                heat.keepSimulated(above);
                pinned[0] = true;
            }
            HostedWorld.Inspection air = heat.inspect(above).orElseThrow(
                    () -> helper.assertionException(Component.literal("waiting for the air to be simulated")));
            helper.assertTrue(air.temperatureK() > air.environmentK() + 0.5, "the air above the torch is "
                    + HeatText.temperature(air.temperatureK()) + ", no warmer than its surroundings at "
                    + HeatText.temperature(air.environmentK()));
            Component reading = ThermometerItem.reading(helper.getLevel(), above, Vec3.atCenterOf(above));
            helper.assertTrue(reading.getString().contains("°C"), "the thermometer says: " + reading.getString());
            heat.release(above);
        });
    }

    /**
     * Heat saved with a chunk comes back exactly: a section written into its chunk, let go and brought in again,
     * as unloading and loading the chunk does, holds the same matter and heat as before.
     */
    private static void heatComesBackWhenAChunkLoadsAgain(GameTestHelper helper) {
        BlockPos relative = new BlockPos(2, 1, 2);
        helper.setBlock(relative, Blocks.IRON_BLOCK);
        BlockPos pos = helper.absolutePos(relative);
        int[] stage = {0};
        helper.succeedWhen(() -> {
            LevelHeat heat = heat(helper);
            if (stage[0] == 0) {
                heat.keepSimulated(pos);
                stage[0] = 1;
            }
            if (stage[0] == 1) {
                if (!heat.setTemperature(pos, 600.0)) {
                    throw helper.assertionException(Component.literal("waiting for the iron to be simulated"));
                }
                stage[0] = 2;
            }
            HostedWorld.Inspection air = heat.inspect(pos.above()).orElseThrow(
                    () -> helper.assertionException(Component.literal("waiting for the air to be simulated")));
            if (!(air.temperatureK() > air.environmentK() + 1.0)) {
                throw helper.assertionException(Component.literal("waiting for the iron to warm the air"));
            }
            List<BlockPos> around = List.of(pos, pos.above(), pos.below(), pos.north(), pos.south(), pos.east(),
                    pos.west());
            List<HostedWorld.Inspection> before = new ArrayList<>();
            for (BlockPos p : around) {
                before.add(heat.inspect(p).orElseThrow(
                        () -> helper.assertionException(Component.literal("waiting for " + p + " to be simulated"))));
            }
            long restored = heat.report().restoredBlocks();
            helper.assertTrue(heat.reloadSection(pos), "the iron's section could not be reloaded");
            ChunkHeat saved = helper.getLevel().getChunkAt(pos).getExistingDataOrNull(AnchorAttachments.CHUNK_HEAT);
            helper.assertTrue(saved != null && saved.section(pos.getY() >> 4) != null,
                    "nothing was saved with the chunk");
            for (int k = 0; k < around.size(); k++) {
                BlockPos p = around.get(k);
                HostedWorld.Inspection was = before.get(k);
                HostedWorld.Inspection is = heat.inspect(p).orElseThrow(
                        () -> helper.assertionException(Component.literal(p + " is no longer simulated")));
                helper.assertTrue(was.material().equals(is.material()) && was.massKg() == is.massKg()
                        && was.enthalpyJ() == is.enthalpyJ(), "the block at " + p + " came back at "
                        + HeatText.temperature(is.temperatureK()) + " instead of "
                        + HeatText.temperature(was.temperatureK()));
            }
            helper.assertTrue(heat.report().restoredBlocks() > restored, "no block took back its saved state");
            heat.release(pos);
        });
    }

    /**
     * The format heat is saved in keeps every number exactly, stores blocks and enthalpies as compact arrays,
     * and refuses a section it cannot make sense of instead of guessing.
     */
    private static void savedHeatKeepsItsNumbers(GameTestHelper helper) {
        List<SectionSnapshot.Entry> palette = List.of(
                new SectionSnapshot.Entry("anchor:air", 0L, Provenance.SIMULATED),
                new SectionSnapshot.Entry("anchor:iron", 42L, Provenance.INITIAL));
        ChunkHeat heat = new ChunkHeat();
        heat.put(-4, new SectionSnapshot(palette, new int[] {0, 17, 4095}, new int[] {1, 0, 0},
                new double[] {7874.0, 1.2041, 0.0}, new double[] {1.0e9 / 3, -12_345.678_9, 0.0},
                new int[] {17, 300}, new byte[] {1, 1 | 4}));
        heat.put(5, new SectionSnapshot(palette.subList(0, 1), new int[] {100}, new int[] {0},
                new double[] {1.1}, new double[] {Math.nextUp(-3000.0)}));
        Tag tag = ChunkHeat.CODEC.encodeStart(NbtOps.INSTANCE, heat).getOrThrow();
        helper.assertValueEqual(heat, ChunkHeat.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow(),
                "the heat read back");
        CompoundTag first = ((ListTag) tag).getCompoundOrEmpty(0);
        helper.assertTrue(first.getIntArray("blocks").isPresent() && first.getLongArray("enthalpy").isPresent(),
                "blocks and enthalpies are not saved as arrays: " + first);
        first.remove("palette");
        helper.assertTrue(ChunkHeat.CODEC.parse(NbtOps.INSTANCE, tag).result().isEmpty(),
                "a section without its palette was read");
        helper.succeed();
    }

    /**
     * A thermal camera looking at a hot iron block reads its temperature at the crosshair, shows it in many dots
     * and shows the cold floor too.
     */
    private static void thermalCameraSeesAHotBlock(GameTestHelper helper) {
        BlockPos relative = new BlockPos(2, 1, 4);
        helper.setBlock(relative, Blocks.IRON_BLOCK);
        BlockPos pos = helper.absolutePos(relative);
        // Two blocks in front of the iron, at its height, looking south at it: Minecraft's yaw 0 faces south.
        Vec3 eye = helper.absoluteVec(new Vec3(2.5, 1.5, 1.5));
        boolean[] pinned = {false};
        helper.succeedWhen(() -> {
            LevelHeat heat = heat(helper);
            if (!pinned[0]) {
                heat.keepSimulated(pos);
                pinned[0] = true;
            }
            if (!heat.setTemperature(pos, 600.0)) {
                throw helper.assertionException(Component.literal("waiting for the iron to be simulated"));
            }
            ThermalCamera.Frame frame = ThermalCamera.capture(helper.getLevel(), heat, eye, 0.0f, 0.0f,
                    ThermalCamera.View.SURFACES);
            helper.assertTrue(Math.abs(frame.spotK() - 600.0) < 1.0, "the camera reads "
                    + HeatText.temperature(frame.spotK()) + " at the iron, which is at 600 K");
            long hot = frame.dots().stream().filter(dot -> dot.kelvin() > 599.0).count();
            helper.assertTrue(hot >= 20, "only " + hot + " of " + frame.dots().size() + " dots show the iron");
            helper.assertTrue(frame.hottestK() > 599.0 && frame.coldestK() < 330.0, "the image spans "
                    + HeatText.temperature(frame.coldestK()) + " to " + HeatText.temperature(frame.hottestK())
                    + ", not the floor to the iron");
            heat.release(pos);
        });
    }

    /** In its air view, a thermal camera shows the warm air above a hot iron block and leaves out the rest. */
    private static void thermalCameraSeesWarmAir(GameTestHelper helper) {
        BlockPos relative = new BlockPos(2, 1, 3);
        helper.setBlock(relative, Blocks.IRON_BLOCK);
        BlockPos pos = helper.absolutePos(relative);
        // Level with the air just above the iron, looking south through it.
        Vec3 eye = helper.absoluteVec(new Vec3(2.5, 2.5, 0.5));
        int[] stage = {0};
        helper.succeedWhen(() -> {
            LevelHeat heat = heat(helper);
            if (stage[0] == 0) {
                heat.keepSimulated(pos);
                stage[0] = 1;
            }
            if (stage[0] == 1) {
                if (!heat.setTemperature(pos, 900.0)) {
                    throw helper.assertionException(Component.literal("waiting for the iron to be simulated"));
                }
                stage[0] = 2;
            }
            HostedWorld.Inspection air = heat.inspect(pos.above()).orElseThrow(
                    () -> helper.assertionException(Component.literal("waiting for the air to be simulated")));
            if (!(air.temperatureK() > air.environmentK() + 2.0)) {
                throw helper.assertionException(Component.literal("waiting for the iron to warm the air"));
            }
            ThermalCamera.Frame frame = ThermalCamera.capture(helper.getLevel(), heat, eye, 0.0f, 0.0f,
                    ThermalCamera.View.AIR);
            double warmest = frame.dots().stream().mapToDouble(ThermalCamera.Dot::kelvin).max().orElse(Double.NaN);
            helper.assertTrue(warmest > air.environmentK() + 2.0, "the warmest air the camera shows is "
                    + HeatText.temperature(warmest) + "; the air above the iron is "
                    + HeatText.temperature(air.temperatureK()));
            long ambient = frame.dots().stream()
                    .filter(dot -> Math.abs(dot.kelvin() - air.environmentK()) < 0.1).count();
            helper.assertTrue(ambient == 0, ambient + " dots show air no warmer than the weather");
            heat.release(pos);
        });
    }

    /** The thermal camera's tooltip says how to use it. */
    private static void thermalCameraExplainsItself(GameTestHelper helper) {
        List<Component> lines = new ItemStack(AnchorItems.THERMAL_CAMERA.get()).getTooltipLines(
                Item.TooltipContext.of(helper.getLevel()), null, TooltipFlag.NORMAL);
        for (String key : List.of("hold", "use", "sneak")) {
            String wanted = "item.anchor.thermal_camera." + key;
            helper.assertTrue(lines.stream().anyMatch(line -> line.getContents() instanceof TranslatableContents t
                    && t.getKey().equals(wanted)), "the tooltip has no line " + wanted + ": " + lines);
        }
        helper.succeed();
    }

    /**
     * An iron block held at 1500 K warms a stone block across two blocks of air. Still air carries almost no
     * heat sideways and the warm air rises away from the stone, so the heat that gets there is radiated.
     */
    private static void hotIronWarmsStoneAcrossAir(GameTestHelper helper) {
        BlockPos ironAt = new BlockPos(2, 1, 1);
        BlockPos stoneAt = new BlockPos(2, 1, 4);
        helper.setBlock(ironAt, Blocks.IRON_BLOCK);
        helper.setBlock(stoneAt, Blocks.STONE);
        BlockPos iron = helper.absolutePos(ironAt);
        BlockPos stone = helper.absolutePos(stoneAt);
        boolean[] pinned = {false};
        helper.succeedWhen(() -> {
            LevelHeat heat = heat(helper);
            if (!pinned[0]) {
                heat.keepSimulated(iron);
                heat.keepSimulated(stone);
                pinned[0] = true;
            }
            if (!heat.setTemperature(iron, 1500.0)) {
                throw helper.assertionException(Component.literal("waiting for the iron to be simulated"));
            }
            HostedWorld.Inspection is = heat.inspect(stone).orElseThrow(
                    () -> helper.assertionException(Component.literal("waiting for the stone to be simulated")));
            if (!(is.temperatureK() > is.environmentK() + 3.0)) {
                throw helper.assertionException(Component.literal("the stone is at "
                        + HeatText.temperature(is.temperatureK()) + ", hardly warmer than its surroundings at "
                        + HeatText.temperature(is.environmentK())));
            }
            heat.release(iron);
            heat.release(stone);
        });
    }

    /**
     * The stone walls of a lava pool are refined into smaller cells, so the face against the lava warms ahead of
     * the rest of the stone, and a thermometer touching that face reads it.
     */
    private static void stoneBesideLavaIsRefined(GameTestHelper helper) {
        BlockPos lavaAt = new BlockPos(2, 1, 2);
        for (BlockPos wall : List.of(lavaAt.north(), lavaAt.south(), lavaAt.east(), lavaAt.west())) {
            helper.setBlock(wall, Blocks.STONE);
        }
        helper.setBlock(lavaAt, Blocks.LAVA);
        BlockPos lava = helper.absolutePos(lavaAt);
        BlockPos stone = lava.east();
        boolean[] pinned = {false};
        helper.succeedWhen(() -> {
            LevelHeat heat = heat(helper);
            if (!pinned[0]) {
                heat.keepSimulated(lava);
                pinned[0] = true;
            }
            HostedWorld.Inspection is = heat.inspect(stone).orElseThrow(
                    () -> helper.assertionException(Component.literal("waiting for the stone to be simulated")));
            if (!is.refined() || !(is.hottestK() > is.coolestK())) {
                throw helper.assertionException(Component.literal("waiting for the stone to be refined"));
            }
            // The stone's west face touches the lava.
            Vec3 face = new Vec3(stone.getX(), stone.getY() + 0.5, stone.getZ() + 0.5);
            Vec3 back = face.add(0.99, 0.0, 0.0);
            double faceK = heat.temperatureAt(stone, face);
            double backK = heat.temperatureAt(stone, back);
            helper.assertTrue(faceK > is.temperatureK() && is.temperatureK() > backK, "the stone is at "
                    + HeatText.temperature(is.temperatureK()) + ", its face against the lava at "
                    + HeatText.temperature(faceK) + " and its back at " + HeatText.temperature(backK));
            Component reading = ThermometerItem.reading(helper.getLevel(), stone, face);
            helper.assertTrue(reading.getString().contains(HeatText.celsius(faceK)),
                    "the thermometer touching the face says: " + reading.getString());
            heat.release(lava);
        });
    }

    /**
     * An iron block at 1300 K glows on the faces it shows, with oxidised iron's emissivity, but not on the face the
     * floor hides or the face against a stone block, and not once it is cooled. A lava pool nearby, which the game
     * already draws glowing, is left out.
     */
    private static void hotIronGlowsWhereItShows(GameTestHelper helper) {
        BlockPos ironAt = new BlockPos(1, 1, 1);
        helper.setBlock(ironAt, Blocks.IRON_BLOCK);
        helper.setBlock(ironAt.east(), Blocks.STONE);
        BlockPos lavaAt = new BlockPos(2, 1, 3);
        for (BlockPos wall : List.of(lavaAt.north(), lavaAt.south(), lavaAt.east(), lavaAt.west())) {
            helper.setBlock(wall, Blocks.STONE);
        }
        helper.setBlock(lavaAt, Blocks.LAVA);
        BlockPos iron = helper.absolutePos(ironAt);
        BlockPos lava = helper.absolutePos(lavaAt);
        boolean[] pinned = {false};
        helper.succeedWhen(() -> {
            LevelHeat heat = heat(helper);
            if (!pinned[0]) {
                heat.keepSimulated(iron);
                heat.keepSimulated(lava);
                pinned[0] = true;
            }
            if (!heat.setTemperature(iron, 1300.0) || heat.inspect(lava).isEmpty()) {
                throw helper.assertionException(Component.literal("waiting for the iron and lava to be simulated"));
            }
            GlowingBlock glowing = glowingBlock(heat, iron).orElseThrow(
                    () -> helper.assertionException(Component.literal("the iron at 1300 K does not glow")));
            helper.assertTrue(Math.abs(glowing.emissivity() - 0.70) < 1e-9,
                    "the iron glows with emissivity " + glowing.emissivity());
            int faces = 0b111111 & ~(1 << Direction.DOWN.ordinal()) & ~(1 << Direction.EAST.ordinal());
            helper.assertTrue(glowing.faces() == faces, "the iron glows on faces " + Integer.toBinaryString(
                    glowing.faces()) + ", not on all but its bottom and east faces");
            helper.assertTrue(glowing.hottest() > 1250.0 && glowing.hottest() <= 1300.0 + 1e-6,
                    "the iron's hottest face is at " + HeatText.temperature(glowing.hottest()));
            int[] colours = GlowData.decode(GlowData.encode(List.of(glowing))).get(0).colours();
            int north = colours[GlowingBlock.sample(Direction.NORTH, 1, 1)];
            helper.assertTrue(north >>> 24 > 100 && (north >> 16 & 0xFF) == 255,
                    "the iron's north face is drawn as " + Integer.toHexString(north));
            helper.assertTrue(glowingBlock(heat, lava).isEmpty(), "the lava is listed as glowing as well");

            helper.assertTrue(heat.setTemperature(iron, 300.0), "the iron could not be cooled");
            helper.assertTrue(glowingBlock(heat, iron).isEmpty(), "the iron still glows at 300 K");
            heat.release(iron);
            heat.release(lava);
        });
    }

    private static Optional<GlowingBlock> glowingBlock(LevelHeat heat, BlockPos pos) {
        int index = new GridPos(pos.getX(), pos.getY(), pos.getZ()).indexInSection();
        return heat.glowingBlocks(pos).stream().filter(block -> block.index() == index).findFirst();
    }

    /**
     * In the noon sun, black wool takes in several times the sunlight of white wool beside it, its top grows far
     * hotter, and a thermometer touching its top reads that. The test holds the sky at a cloudless noon, whatever
     * the time and weather in the test world.
     */
    private static void blackWoolWarmsMoreInTheSun(GameTestHelper helper) {
        BlockPos blackAt = new BlockPos(1, 1, 2);
        BlockPos whiteAt = new BlockPos(3, 1, 2);
        helper.setBlock(blackAt, Blocks.WOOL.black());
        helper.setBlock(whiteAt, Blocks.WOOL.white());
        BlockPos black = helper.absolutePos(blackAt);
        BlockPos white = helper.absolutePos(whiteAt);
        boolean[] started = {false};
        helper.succeedWhen(() -> {
            LevelHeat heat = heat(helper);
            if (!started[0]) {
                heat.keepSimulated(black);
                heat.keepSimulated(white);
                heat.holdSky(Sky.clear(90.0));
                started[0] = true;
            }
            HostedWorld.Inspection dark = heat.inspect(black).orElseThrow(
                    () -> helper.assertionException(Component.literal("waiting for the black wool to be simulated")));
            HostedWorld.Inspection pale = heat.inspect(white).orElseThrow(
                    () -> helper.assertionException(Component.literal("waiting for the white wool to be simulated")));
            if (!(dark.sunlightW() > 0 && pale.sunlightW() > 0)) {
                throw helper.assertionException(Component.literal("waiting for the sun to reach the wool; "
                        + heat.report().world().skySurfaces() + " surfaces are open to the sky"));
            }
            helper.assertTrue(dark.sunlightW() > 3 * pale.sunlightW(), "black wool takes in "
                    + HeatText.power(dark.sunlightW()) + " of sunlight and white wool "
                    + HeatText.power(pale.sunlightW()));
            if (!(dark.surfaceK() > pale.surfaceK() + 10.0)) {
                throw helper.assertionException(Component.literal("the top of the black wool is at "
                        + HeatText.temperature(dark.surfaceK()) + " and of the white wool at "
                        + HeatText.temperature(pale.surfaceK())));
            }
            Vec3 top = new Vec3(black.getX() + 0.5, black.getY() + 1.0, black.getZ() + 0.5);
            Component reading = ThermometerItem.reading(helper.getLevel(), black, top);
            helper.assertTrue(reading.getString().contains(HeatText.celsius(dark.surfaceK())),
                    "a thermometer on the black wool's top says: " + reading.getString());
            heat.holdSky(null);
            heat.release(black);
            heat.release(white);
        });
    }

    /**
     * A probe on top of a hot iron block records it cooling, a thermometer on the block names the probe, and a chart
     * of the probe is a locked map with the probe's line drawn on white paper. The probe measures the top, which cools
     * from the first step, rather than the middle, which a refined block keeps hot for a while.
     */
    private static void probeRecordsACoolingBlock(GameTestHelper helper) {
        BlockPos relative = new BlockPos(2, 1, 2);
        helper.setBlock(relative, Blocks.IRON_BLOCK);
        BlockPos pos = helper.absolutePos(relative);
        Vec3 top = new Vec3(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5);
        ServerLevel level = helper.getLevel();
        String name = "cooling_iron";
        int[] stage = {0};
        helper.succeedWhen(() -> {
            LevelHeat heat = heat(helper);
            LevelProbes probes = ProbeCommands.probes(level);
            if (stage[0] == 0) {
                heat.keepSimulated(pos);
                stage[0] = 1;
            }
            if (stage[0] == 1) {
                if (!heat.setTemperature(pos, 700.0)) {
                    throw helper.assertionException(Component.literal("waiting for the iron to be simulated"));
                }
                ProbeCommands.Outcome added = ProbeCommands.add(level, pos, top, name);
                helper.assertTrue(added.done(), "the probe was not added: " + added.message().getString());
                stage[0] = 2;
            }
            ProbeSet.Probe probe = probes.set().get(name).orElseThrow(
                    () -> helper.assertionException(Component.literal("the probe is gone")));
            TimeSeries s = probe.series();
            if (s.samples() < 20) {
                throw helper.assertionException(Component.literal("waiting for readings: " + s.samples()));
            }
            helper.assertTrue(s.bucket(0).mean() > s.last() && s.recentRate() < 0, "the probe saw the iron go from "
                    + HeatText.temperature(s.bucket(0).mean()) + " to " + HeatText.temperature(s.last()) + ", "
                    + HeatText.rate(s.recentRate()));
            Component reading = ThermometerItem.reading(level, pos, top);
            helper.assertTrue(reading.getString().contains("recorded as " + name), "a thermometer on the iron says: "
                    + reading.getString());
            ItemStack chart = ProbeCharts.create(level, probes, List.of(probe));
            MapId id = chart.get(DataComponents.MAP_ID);
            MapItemSavedData data = id == null ? null : level.getMapData(id);
            helper.assertTrue(data != null, "the chart's map was not saved");
            int line = 0;
            int paper = 0;
            for (byte b : data.colors) {
                line += b == ProbeCharts.colour(ChartImage.Ink.LINE_1) ? 1 : 0;
                paper += b == ProbeCharts.colour(ChartImage.Ink.PAPER) ? 1 : 0;
            }
            helper.assertTrue(line > 30 && paper > 8000, "the chart has " + line + " pixels of line and " + paper
                    + " of paper");
            helper.assertTrue(data.locked, "the chart's map is not locked, so the game would draw the land on it");
            String csv = TimeSeriesCsv.write(s, "C", HeatText::toCelsius);
            helper.assertTrue(csv.lines().count() == s.size() + 1, "the CSV has " + csv.lines().count()
                    + " lines for " + s.size() + " stretches of time");
            probes.removeChart(id.id());
            probes.set().remove(name);
            heat.release(pos);
        });
    }

    /**
     * Using a thermometer on a block while sneaking leaves a probe where it touched, and doing it again takes the
     * probe away.
     */
    private static void thermometerLeavesAndTakesAProbe(GameTestHelper helper) {
        BlockPos relative = new BlockPos(2, 1, 2);
        helper.setBlock(relative, Blocks.STONE);
        BlockPos pos = helper.absolutePos(relative);
        GridPos block = new GridPos(pos.getX(), pos.getY(), pos.getZ());
        ServerLevel level = helper.getLevel();
        boolean[] pinned = {false};
        helper.succeedWhen(() -> {
            LevelHeat heat = heat(helper);
            if (!pinned[0]) {
                heat.keepSimulated(pos);
                pinned[0] = true;
            }
            if (heat.inspect(pos).isEmpty()) {
                throw helper.assertionException(Component.literal("waiting for the stone to be simulated"));
            }
            Vec3 top = new Vec3(pos.getX() + 0.25, pos.getY() + 1.0, pos.getZ() + 0.75);
            ProbeCommands.Outcome added = ProbeCommands.toggle(level, pos, top);
            helper.assertTrue(added.done() && key(added.message()).equals("message.anchor.probe.added"),
                    "leaving a probe said: " + added.message().getString());
            ProbeSet.Probe probe = ProbeCommands.probes(level).set().in(block).orElseThrow(
                    () -> helper.assertionException(Component.literal("no probe was left in the stone")));
            helper.assertTrue(probe.x() == pos.getX() + 0.25 && probe.y() == Math.nextDown(pos.getY() + 1.0)
                    && probe.z() == pos.getZ() + 0.75, "the probe measures " + probe.x() + " " + probe.y() + " "
                    + probe.z() + ", not the top of the stone where the thermometer touched");
            ProbeCommands.Outcome removed = ProbeCommands.toggle(level, pos, top);
            helper.assertTrue(removed.done() && key(removed.message()).equals("message.anchor.probe.removed"),
                    "taking the probe said: " + removed.message().getString());
            helper.assertTrue(ProbeCommands.probes(level).set().in(block).isEmpty(), "the probe is still there");
            heat.release(pos);
        });
    }

    /**
     * The format probes are saved in keeps every reading exactly, stores recordings as compact arrays, and drops probes
     * that do not fit together instead of guessing.
     */
    private static void savedProbesKeepTheirNumbers(GameTestHelper helper) {
        LevelProbes probes = new LevelProbes();
        ProbeSet set = probes.set();
        set.add("north", new GridPos(1, 64, 1), 1.5, 64.999, 1.5);
        set.add(null, new GridPos(-7, -3, 12), -6.5, -2.5, 12.5);
        for (int i = 0; i < 700; i++) {
            double t = i;
            set.record(14.4, p -> p.number() == 1 ? 290.0 + StrictMath.sin(t / 30.0)
                    : t % 50 == 0 ? Double.NaN : 1000.0 - t);
        }
        probes.addChart(new LevelProbes.Chart(17, List.of(2L, 1L)));
        Tag tag = LevelProbes.CODEC.encodeStart(NbtOps.INSTANCE, probes).getOrThrow();
        LevelProbes back = LevelProbes.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
        helper.assertValueEqual(probes.set().state(), back.set().state(), "the probes read back");
        helper.assertValueEqual(probes.charts(), back.charts(), "the charts read back");
        CompoundTag first = ((CompoundTag) tag).getCompoundOrEmpty("probes").getListOrEmpty("probes")
                .getCompoundOrEmpty(0);
        helper.assertTrue(first.getCompoundOrEmpty("series").getLongArray("maximum").isPresent(),
                "recordings are not saved as arrays: " + first);
        first.putDouble("x", 100.0);
        helper.assertTrue(LevelProbes.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow().isEmpty(),
                "a probe measuring outside its block was read");
        ((CompoundTag) tag).remove("probes");
        helper.assertTrue(LevelProbes.CODEC.parse(NbtOps.INSTANCE, tag).result().isEmpty(),
                "probes saved without their list were read");
        helper.succeed();
    }

    /** The thermometer's tooltip says how to use it. */
    private static void thermometerExplainsItself(GameTestHelper helper) {
        List<Component> lines = new ItemStack(AnchorItems.THERMOMETER.get()).getTooltipLines(
                Item.TooltipContext.of(helper.getLevel()), null, TooltipFlag.NORMAL);
        for (String wanted : List.of("item.anchor.thermometer.use", "item.anchor.thermometer.sneak")) {
            helper.assertTrue(lines.stream().anyMatch(line -> key(line).equals(wanted)), "the tooltip has no line "
                    + wanted + ": " + lines);
        }
        helper.succeed();
    }

    /**
     * Paused, heat holds every temperature while the game runs on, and a thermometer says it is paused; it then takes
     * exactly the steps asked for by hand and stays paused. Sent ahead, it takes several steps a tick while they fit
     * into the budget, and at twice normal speed it takes twice the steps.
     */
    private static void pausedHeatHoldsAndStepsByHand(GameTestHelper helper) {
        BlockPos relative = new BlockPos(2, 1, 2);
        helper.setBlock(relative, Blocks.IRON_BLOCK);
        BlockPos pos = helper.absolutePos(relative);
        long[] mark = {0L};
        double[] held = {Double.NaN};
        int[] stage = {0};
        int[] ticks = {0};
        long[] least = {38L};
        helper.succeedWhen(() -> {
            LevelHeat heat = heat(helper);
            if (stage[0] == 0) {
                heat.keepSimulated(pos);
                stage[0] = 1;
            }
            if (stage[0] == 1) {
                if (!heat.setTemperature(pos, 700.0)) {
                    throw helper.assertionException(Component.literal("waiting for the iron to be simulated"));
                }
                heat.pause();
                mark[0] = heat.pace().steps();
                held[0] = heat.temperature(pos);
                ticks[0] = 0;
                stage[0] = 2;
            }
            if (stage[0] == 2) {
                helper.assertTrue(heat.pace().steps() == mark[0], "paused heat took "
                        + (heat.pace().steps() - mark[0]) + " steps");
                helper.assertTrue(heat.temperature(pos) == held[0], "paused, the iron went from "
                        + HeatText.temperature(held[0]) + " to " + HeatText.temperature(heat.temperature(pos)));
                if (++ticks[0] < 20) {
                    throw helper.assertionException(Component.literal("watching paused heat"));
                }
                Component reading = ThermometerItem.reading(helper.getLevel(), pos, Vec3.atCenterOf(pos));
                helper.assertTrue(reading.getString().contains("paused"), "a thermometer on the iron says: "
                        + reading.getString());
                heat.request(3);
                stage[0] = 3;
            }
            if (stage[0] == 3) {
                if (heat.pace().requested() > 0) {
                    throw helper.assertionException(Component.literal("waiting for the steps asked for"));
                }
                helper.assertTrue(heat.pace().steps() == mark[0] + 3, "asked for 3 steps, heat took "
                        + (heat.pace().steps() - mark[0]));
                helper.assertTrue(heat.temperature(pos) < held[0], "three steps left the iron at "
                        + HeatText.temperature(heat.temperature(pos)));
                helper.assertTrue(heat.pace().paused(), "heat did not stay paused after the steps asked for");
                heat.resume();
                mark[0] = heat.pace().steps();
                heat.request(60);
                ticks[0] = 0;
                stage[0] = 4;
            }
            if (stage[0] == 4) {
                if (heat.pace().requested() > 0) {
                    ticks[0]++;
                    throw helper.assertionException(Component.literal("sending heat ahead: "
                            + heat.pace().requested() + " steps to go"));
                }
                // At least one step asked for is taken every tick, and more while they fit into the budget.
                double millis = heat.pace().millisPerStep();
                int most = millis < AnchorConfig.get(AnchorConfig.STEP_BUDGET_MILLIS) / 4 ? 30 : 60;
                helper.assertTrue(heat.pace().steps() >= mark[0] + 60 && ticks[0] <= most, "sent 60 steps ahead, "
                        + "heat took " + (heat.pace().steps() - mark[0]) + " steps in " + ticks[0] + " ticks, at "
                        + String.format(Locale.ROOT, "%.2f", millis) + " ms a step");
                heat.setSpeed(Pacer.speedOf(2.0));
                mark[0] = heat.pace().steps();
                ticks[0] = 0;
                stage[0] = 5;
            }
            if (stage[0] == 5) {
                if (ticks[0]++ < 80) {
                    throw helper.assertionException(Component.literal("running at twice normal speed"));
                }
                mark[0] = heat.pace().steps() - mark[0];
                // Steps beyond the normal ones are taken only while they fit into the budget; if they did not, the
                // pacer says it could not keep up, and the normal ones must still have been taken.
                if (!heat.pace().keepingUp()) {
                    least[0] = 19L;
                }
                heat.setSpeed(Pacer.NORMAL_SPEED);
                heat.release(pos);
                stage[0] = 6;
            }
            helper.assertTrue(mark[0] >= least[0] && mark[0] <= 42, "at twice normal speed heat took " + mark[0]
                    + " steps in 80 ticks, not 40" + (least[0] < 38 ? ", and could not keep up" : ""));
        });
    }

    /**
     * A snapshot rewinds an experiment. Packed ice saved cold and then melted comes back as packed ice with exactly the
     * heat it had, the water that spread from it is gone, and the snapshot's file reads back as it was saved.
     */
    private static void snapshotRewindsMeltedIce(GameTestHelper helper) {
        BlockPos iceAt = new BlockPos(2, 1, 2);
        BlockPos besideAt = new BlockPos(1, 1, 2);
        helper.setBlock(iceAt, Blocks.PACKED_ICE);
        BlockPos ice = helper.absolutePos(iceAt);
        BlockPos from = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos to = helper.absolutePos(new BlockPos(3, 2, 3));
        BlockPos min = BlockPos.min(from, to);
        BlockPos max = BlockPos.max(from, to);
        ServerLevel level = helper.getLevel();
        String name = "gametest_ice";
        Snapshots.Saved[] saved = {null};
        HostedWorld.Inspection[] cold = {null};
        int[] stage = {0};
        helper.succeedWhen(() -> {
            LevelHeat heat = heat(helper);
            if (stage[0] == 0) {
                heat.keepSimulated(ice);
                stage[0] = 1;
            }
            if (stage[0] == 1) {
                if (!heat.simulates(min, max) || !heat.setTemperature(ice, 250.0)) {
                    throw helper.assertionException(Component.literal("waiting for the box to be simulated"));
                }
                saved[0] = saveSnapshot(helper, heat, name, min, max);
                cold[0] = heat.inspect(ice).orElseThrow(
                        () -> helper.assertionException(Component.literal("the ice is no longer simulated")));
                helper.assertTrue(heat.setTemperature(ice, 290.0), "the ice could not be warmed");
                stage[0] = 2;
            }
            if (stage[0] == 2) {
                helper.assertBlockPresent(Blocks.WATER, iceAt);
                helper.assertBlockPresent(Blocks.WATER, besideAt);
                Snapshots.Saved read = readSnapshot(helper, Snapshots.file(level, name));
                helper.assertValueEqual(saved[0], read, "the snapshot read back");
                Optional<HostedWorld.Restored> restored = Snapshots.restore(level, heat, read, read.origin());
                helper.assertTrue(restored.isPresent(), "the snapshot was not restored");
                HostedWorld.Restored r = restored.get();
                helper.assertTrue(r.blocks() == 18 && r.changed() >= 2 && r.afresh() == 0, "restoring did " + r);
                stage[0] = 3;
            }
            helper.assertBlockPresent(Blocks.PACKED_ICE, iceAt);
            helper.assertBlockPresent(Blocks.AIR, besideAt);
            HostedWorld.Inspection back = heat.inspect(ice).orElseThrow(
                    () -> helper.assertionException(Component.literal("the ice is no longer simulated")));
            HostedWorld.Inspection was = cold[0];
            helper.assertTrue(back.material().equals(was.material()) && back.massKg() == was.massKg()
                    && back.enthalpyJ() == was.enthalpyJ(), "the ice came back as " + back.material() + " at "
                    + HeatText.temperature(back.temperatureK()) + " instead of " + was.material() + " at "
                    + HeatText.temperature(was.temperatureK()));
            try {
                Files.delete(Snapshots.file(level, name));
            } catch (IOException e) {
                throw helper.assertionException(Component.literal("the snapshot could not be removed: " + e));
            }
            heat.release(ice);
        });
    }

    /**
     * The file a snapshot is kept in keeps every number exactly, those of a block refined into cells included, and a
     * file that cannot be made sense of is refused instead of guessed at.
     */
    private static void snapshotFileKeepsEveryNumber(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<SectionSnapshot.Entry> palette = List.of(
                new SectionSnapshot.Entry("anchor:air", 0L, Provenance.SIMULATED),
                new SectionSnapshot.Entry("anchor:iron", 42L, Provenance.INITIAL));
        // A whole block of air, then a block of iron in eight cells, each holding a little more heat than the last.
        CellId block = CellId.of(new GridPos(0, 0, 0));
        long[] cells = new long[9];
        int[] entries = new int[9];
        double[] mass = new double[9];
        double[] enthalpy = new double[9];
        cells[0] = BlockCopy.WHOLE;
        mass[0] = 1.2041;
        enthalpy[0] = Math.nextUp(-3000.0);
        for (int i = 0; i < 8; i++) {
            cells[1 + i] = BlockCopy.pack(block.child(i));
            entries[1 + i] = 1;
            mass[1 + i] = 7874.0 / 8;
            enthalpy[1 + i] = 1.0e9 / 3 + i * 0.1;
        }
        RegionSnapshot heat = new RegionSnapshot(2, 3, 4, palette, new int[] {0, 13}, cells, entries, mass, enthalpy);
        StructureTemplate template = new StructureTemplate();
        template.fillFromWorld(level, helper.absolutePos(BlockPos.ZERO), new Vec3i(2, 3, 4), false, List.of());
        CompoundTag blocks = NbtUtils.addCurrentDataVersion(template.save(new CompoundTag()));
        Snapshots.Saved saved = new Snapshots.Saved(level.dimension().identifier().toString(),
                new BlockPos(-5, 70, 12), blocks, heat, 1_700_000_000_123L, 86_400L);
        Path file = Snapshots.file(level, "gametest_numbers");
        try {
            Snapshots.write(file, saved);
            helper.assertValueEqual(saved, Snapshots.read(file), "the snapshot read back");
            helper.assertTrue(Snapshots.names(level).contains("gametest_numbers"), "the snapshot is not listed: "
                    + Snapshots.names(level));
            CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            CompoundTag stored = root.getCompoundOrEmpty("heat");
            helper.assertTrue(stored.getLongArray("cells").isPresent() && stored.getLongArray("enthalpy").isPresent(),
                    "cells and enthalpies are not saved as arrays: " + stored);
            stored.putLongArray("cells", Arrays.copyOf(cells, 8));
            NbtIo.writeCompressed(root, file);
            refused(helper, file, "a snapshot with a cell missing");
            stored.putLongArray("cells", cells);
            root.putInt("format", Snapshots.FORMAT + 1);
            NbtIo.writeCompressed(root, file);
            refused(helper, file, "a snapshot in a later layout");
            Files.write(file, new byte[] {1, 2, 3});
            refused(helper, file, "a file of three bytes");
            Files.delete(file);
        } catch (IOException e) {
            throw helper.assertionException(Component.literal("the snapshot could not be written or read: " + e));
        }
        helper.succeed();
    }

    /** Saves a snapshot, failing the test if it is not taken. */
    private static Snapshots.Saved saveSnapshot(GameTestHelper helper, LevelHeat heat, String name, BlockPos min,
            BlockPos max) {
        try {
            return Snapshots.save(helper.getLevel(), heat, name, min, max).orElseThrow(
                    () -> helper.assertionException(Component.literal("the snapshot was not taken")));
        } catch (IOException e) {
            throw helper.assertionException(Component.literal("the snapshot could not be written: " + e));
        }
    }

    /** Reads a snapshot, failing the test if it cannot be read. */
    private static Snapshots.Saved readSnapshot(GameTestHelper helper, Path file) {
        try {
            return Snapshots.read(file);
        } catch (IOException e) {
            throw helper.assertionException(Component.literal("the snapshot could not be read: " + e));
        }
    }

    /** Checks that a damaged snapshot file is refused. */
    private static void refused(GameTestHelper helper, Path file, String what) {
        try {
            Snapshots.read(file);
        } catch (IOException e) {
            return;
        }
        throw helper.assertionException(Component.literal(what + " was read"));
    }

    /** Returns the translation key of a message, or an empty string if it has none. */
    private static String key(Component message) {
        return message.getContents() instanceof TranslatableContents t ? t.getKey() : "";
    }

    /** A stone block put up in the air falls, and lands on the floor. */
    private static void unsupportedBlockFalls(GameTestHelper helper) {
        BlockPos floating = new BlockPos(2, 3, 2);
        BlockPos landing = new BlockPos(2, 1, 2);
        buildThenExpect(helper, heat -> helper.setBlock(floating, Blocks.STONE), heat -> {
            helper.assertBlockPresent(Blocks.AIR, floating);
            helper.assertBlockPresent(Blocks.STONE, landing);
        });
    }

    /**
     * A fence counts as the air around it for heat, but its post holds up a stone put on it, which stays where it is
     * while its structure is checked again and again.
     */
    private static void fenceHoldsUpABlock(GameTestHelper helper) {
        BlockPos fence = new BlockPos(2, 1, 2);
        BlockPos top = new BlockPos(2, 2, 2);
        long[] builtAt = {0};
        buildThenExpect(helper, heat -> {
            helper.setBlock(fence, Blocks.OAK_FENCE);
            helper.setBlock(top, Blocks.STONE);
            builtAt[0] = helper.getTick();
        }, heat -> {
            helper.assertBlockPresent(Blocks.STONE, top);
            LevelStructures.Look look = lookAt(helper, heat, top);
            helper.assertTrue(look.built() && !look.falls() && look.blocks() == 2,
                    "the stone on the fence should be built and stand with it: " + look);
            if (helper.getTick() - builtAt[0] < 40) {
                throw helper.assertionException(Component.literal("waiting to see the stone stay up"));
            }
        });
    }

    /** Taking the foot out of a pillar of three built blocks lets the two above fall into its place. */
    private static void pillarFallsWhenItsFootIsTaken(GameTestHelper helper) {
        BlockPos foot = new BlockPos(2, 1, 2);
        BlockPos middle = foot.above();
        BlockPos top = middle.above();
        boolean[] taken = {false};
        buildThenExpect(helper, heat -> {
            for (BlockPos p : List.of(foot, middle, top)) {
                helper.setBlock(p, Blocks.COBBLESTONE);
            }
        }, heat -> {
            if (!taken[0]) {
                LevelStructures.Look look = lookAt(helper, heat, top);
                helper.assertTrue(look.built() && !look.falls() && look.blocks() == 3,
                        "the pillar should be built and stand: " + look);
                helper.setBlock(foot, Blocks.AIR);
                taken[0] = true;
            }
            helper.assertBlockPresent(Blocks.COBBLESTONE, foot);
            helper.assertBlockPresent(Blocks.COBBLESTONE, middle);
            helper.assertBlockPresent(Blocks.AIR, top);
        });
    }

    /** Which blocks are built is saved with their chunk and comes back with it. */
    private static void builtBlocksStayBuiltWhenAChunkLoadsAgain(GameTestHelper helper) {
        BlockPos placed = new BlockPos(2, 1, 2);
        buildThenExpect(helper, heat -> helper.setBlock(placed, Blocks.STONE), heat -> {
            helper.assertTrue(lookAt(helper, heat, placed).built(), "the placed stone is not built");
            helper.assertTrue(heat.reloadSection(helper.absolutePos(placed)), "its section could not be reloaded");
            helper.assertTrue(lookAt(helper, heat, placed).built(), "the stone came back natural");
            helper.assertTrue(!lookAt(helper, heat, placed.below()).built(), "the floor came back built");
        });
    }

    /**
     * Keeps a test's space simulated and, once the simulation has it, marks its floor natural so that it holds up
     * whatever stands on it, builds on it, and succeeds once the check passes.
     */
    private static void buildThenExpect(GameTestHelper helper, Consumer<LevelHeat> build, Consumer<LevelHeat> check) {
        BlockPos corner = helper.absolutePos(BlockPos.ZERO);
        BlockPos far = helper.absolutePos(new BlockPos(4, 4, 4));
        int[] stage = {0};
        helper.succeedWhen(() -> {
            LevelHeat heat = heat(helper);
            if (stage[0] == 0) {
                heat.keepSimulated(corner);
                stage[0] = 1;
            }
            if (stage[0] == 1) {
                if (!heat.simulates(corner, far)) {
                    throw helper.assertionException(Component.literal("waiting for the test's space to be simulated"));
                }
                heat.setBuilt(corner, helper.absolutePos(new BlockPos(4, 0, 4)), false);
                build.accept(heat);
                stage[0] = 2;
            }
            check.accept(heat);
            heat.release(corner);
        });
    }

    /** Looks at the structure of a block of a test. */
    private static LevelStructures.Look lookAt(GameTestHelper helper, LevelHeat heat, BlockPos relative) {
        return heat.lookAtStructure(helper.absolutePos(relative)).orElseThrow(
                () -> helper.assertionException(Component.literal(relative + " is not simulated")));
    }

    /** Builds a one-block pool of still water in stone and returns where the water is. */
    private static BlockPos pool(GameTestHelper helper) {
        BlockPos water = new BlockPos(2, 1, 2);
        for (BlockPos wall : List.of(water.north(), water.south(), water.east(), water.west())) {
            helper.setBlock(wall, Blocks.STONE);
        }
        helper.setBlock(water, Blocks.WATER);
        return water;
    }

    /**
     * Keeps a block simulated, sets its temperature once the simulation has it, and succeeds when the block
     * has turned into the expected one.
     */
    private static void setTemperatureThenExpect(GameTestHelper helper, BlockPos relative, double kelvin,
            Block expected) {
        BlockPos pos = helper.absolutePos(relative);
        int[] stage = {0};
        helper.succeedWhen(() -> {
            LevelHeat heat = heat(helper);
            if (stage[0] == 0) {
                heat.keepSimulated(pos);
                stage[0] = 1;
            }
            if (stage[0] == 1) {
                if (!heat.setTemperature(pos, kelvin)) {
                    throw helper.assertionException(Component.literal("waiting for the block to be simulated"));
                }
                stage[0] = 2;
            }
            helper.assertBlockPresent(expected, relative);
            heat.release(pos);
        });
    }

    /**
     * The laboratory's biome gives air at 20 °C and 50 % relative humidity, where it never rains, and the Laboratory
     * world type is there to choose. The game test world is not made of that biome, so it is no laboratory and heat
     * there follows the sun as usual.
     */
    private static void laboratoryAirIsSteady(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Biome biome = level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Laboratory.BIOME).value();
        double celsius = Climate.kelvin(biome.getBaseTemperature(), 0) - 273.15;
        double humidity = Climate.relativeHumidity(biome.getModifiedClimateSettings().downfall());
        helper.assertTrue(Math.abs(celsius - 20.0) < 0.01, "the laboratory's air is at " + celsius + " °C");
        helper.assertTrue(Math.abs(humidity - 0.5) < 0.001, "the laboratory's air has a relative humidity of "
                + humidity);
        helper.assertTrue(!biome.hasPrecipitation(), "it rains in the laboratory");
        helper.assertTrue(level.registryAccess().lookupOrThrow(Registries.WORLD_PRESET).get(Laboratory.PRESET)
                .isPresent(), "there is no Laboratory world type");
        helper.assertTrue(!Laboratory.is(level), "the game test world counts as a laboratory");
        helper.assertTrue(heat(helper).hasSky(), "heat in the game test world follows no sun");
        helper.succeed();
    }

    /**
     * The cooling experiment: its iron glows once built, and a minute later at normal speed, 300 steps, its top has
     * cooled below 900 °C while its middle is still far hotter. Restoring its snapshot then brings the iron back at
     * 1500 K, to run it again.
     */
    private static void experimentCooling(GameTestHelper helper) {
        // Restoring is done once: what it left is kept, empty if all went well, as the readings change after it.
        String[] restored = {null};
        runExperiment(helper, Experiments.COOLING, 300, (heat, frame) -> glowingBlock(heat, frame.at(0, 0, 1))
                .isPresent() ? null : "the iron did not glow at 1500 K", (heat, frame, built, readings) -> {
                    if (restored[0] == null) {
                        double top = readings[0];
                        double middle = readings[1];
                        helper.assertTrue(top < 1173.15 && middle > top + 100.0, "after 300 steps the iron's top is "
                                + "at " + HeatText.temperature(top) + " and its middle at "
                                + HeatText.temperature(middle));
                        ServerLevel level = helper.getLevel();
                        Snapshots.Saved saved = readSnapshot(helper, Snapshots.file(level, built.snapshot()));
                        BlockPos iron = frame.at(0, 0, 1);
                        restored[0] = Snapshots.restore(level, heat, saved, saved.origin()).isEmpty()
                                ? "the experiment's snapshot was not restored"
                                : Math.abs(heat.temperature(iron) - Experiments.COOLING_START_K) < 1e-6 ? ""
                                : "restoring the snapshot left the iron at "
                                        + HeatText.temperature(heat.temperature(iron));
                    }
                    if (!restored[0].isEmpty()) {
                        throw helper.assertionException(Component.literal(restored[0]));
                    }
                });
    }

    /**
     * The conduction race: after eight minutes at normal speed, 2400 steps, the top of the copper rod is far warmer
     * than that of the iron rod, the iron's than the stone's, and the stone's than the brick's.
     */
    private static void experimentConduction(GameTestHelper helper) {
        runExperiment(helper, Experiments.CONDUCTION, 2400, (heat, frame) -> null, (heat, frame, built, readings) -> {
            double copper = readings[0];
            double iron = readings[1];
            double stone = readings[2];
            double brick = readings[3];
            helper.assertTrue(copper > iron + 30.0 && iron > stone + 5.0 && stone > brick + 1.5, "after 2400 steps "
                    + "the tops of the rods are at " + HeatText.temperature(copper) + " (copper), "
                    + HeatText.temperature(iron) + " (iron), " + HeatText.temperature(stone) + " (stone) and "
                    + HeatText.temperature(brick) + " (brick)");
        });
    }

    /**
     * Melting takes heat: after nearly twelve minutes at normal speed, 3500 steps, the ice has warmed from -10 °C to
     * 0 °C and stays there, still ice, while the stone beside it has warmed on past 4 °C.
     */
    private static void experimentMelting(GameTestHelper helper) {
        runExperiment(helper, Experiments.MELTING, 3500, (heat, frame) -> null, (heat, frame, built, readings) -> {
            double ice = readings[0];
            double stone = readings[1];
            helper.assertTrue(Math.abs(ice - 273.15) < 0.05 && stone > 277.15, "after 3500 steps the ice is at "
                    + HeatText.temperature(ice) + " and the stone at " + HeatText.temperature(stone));
            helper.assertTrue(helper.getLevel().getBlockState(frame.at(-2, 1, 1)).is(Blocks.PACKED_ICE),
                    "the ice has already melted");
        });
    }

    /**
     * Insulation: after four minutes at normal speed, 1200 steps, the iron in wool has hardly cooled, the iron in glass
     * has cooled less than the bare one, and the bare one most.
     */
    private static void experimentInsulation(GameTestHelper helper) {
        runExperiment(helper, Experiments.INSULATION, 1200, (heat, frame) -> null, (heat, frame, built, readings) -> {
            double bare = readings[0];
            double glass = readings[1];
            double wool = readings[2];
            helper.assertTrue(wool > Experiments.INSULATION_START_K - 5.0 && wool > glass + 15.0
                    && glass > bare + 5.0, "after 1200 steps the irons are at " + HeatText.temperature(bare)
                    + " (bare), " + HeatText.temperature(glass) + " (in glass) and " + HeatText.temperature(wool)
                    + " (in wool)");
        });
    }

    /** What a test of a ready-made experiment checks once it is built: why it failed, or {@code null}. */
    private interface ExperimentStart {
        String check(LevelHeat heat, Experiments.Frame frame);
    }

    /** What a test of a ready-made experiment checks after its steps, given its probes' latest readings in kelvin. */
    private interface ExperimentResult {
        void check(LevelHeat heat, Experiments.Frame frame, Experiments.Built built, double[] readings);
    }

    /**
     * Builds a ready-made experiment on the bench, facing south from the middle of its near edge, checks it as built,
     * asks heat for some steps and checks its probes' readings after them, in its order. It then removes the probes
     * and the snapshot it left. What it checks once built is only judged at the end, so that a failure there is not
     * forgotten while the test waits.
     */
    private static void runExperiment(GameTestHelper helper, Experiments.Experiment experiment, long steps,
            ExperimentStart start, ExperimentResult result) {
        ServerLevel level = helper.getLevel();
        Experiments.Frame frame = new Experiments.Frame(helper.absolutePos(new BlockPos(7, 2, 1)),
                net.minecraft.core.Direction.SOUTH);
        BlockPos min = frame.min(experiment);
        BlockPos max = frame.max(experiment);
        List<BlockPos> sections = new ArrayList<>();
        for (int x = min.getX() >> 4; x <= max.getX() >> 4; x++) {
            for (int y = min.getY() >> 4; y <= max.getY() >> 4; y++) {
                for (int z = min.getZ() >> 4; z <= max.getZ() >> 4; z++) {
                    sections.add(new BlockPos(x << 4, y << 4, z << 4));
                }
            }
        }
        Experiments.Built[] built = {null};
        String[] failure = {null};
        long[] end = {0L};
        int[] stage = {0};
        helper.succeedWhen(() -> {
            LevelHeat heat = heat(helper);
            if (stage[0] == 0) {
                sections.forEach(heat::keepSimulated);
                stage[0] = 1;
            }
            if (stage[0] == 1) {
                if (!heat.simulates(min, max)) {
                    throw helper.assertionException(Component.literal("waiting for the bench to be simulated"));
                }
                Experiments.Outcome outcome = Experiments.build(level, heat, experiment, frame);
                if (outcome.built() == null) {
                    throw helper.assertionException(Component.literal("the experiment was not built: "
                            + outcome.refusal().getString()));
                }
                built[0] = outcome.built();
                failure[0] = start.check(heat, frame);
                end[0] = heat.pace().steps() + steps;
                heat.request(steps);
                stage[0] = 2;
            }
            if (heat.pace().steps() < end[0]) {
                throw helper.assertionException(Component.literal("running the experiment: "
                        + (end[0] - heat.pace().steps()) + " steps to go"));
            }
            Experiments.Built b = built[0];
            if (failure[0] != null) {
                throw helper.assertionException(Component.literal(failure[0]));
            }
            helper.assertTrue(b.problems().isEmpty(), "building the experiment went wrong: "
                    + b.problems().stream().map(Component::getString).toList());
            helper.assertTrue(b.probes().size() == experiment.plan().probeNames().size(), "the experiment has "
                    + b.probes().size() + " probes, not " + experiment.plan().probeNames().size());
            helper.assertValueEqual(experiment.snapshot(), b.snapshot(), "the experiment's snapshot");
            double[] readings = new double[b.probes().size()];
            for (int i = 0; i < readings.length; i++) {
                readings[i] = b.probes().get(i).series().last();
            }
            result.check(heat, frame, b, readings);
            ProbeSet set = ProbeCommands.probes(level).set();
            for (ProbeSet.Probe p : b.probes()) {
                set.remove(p.name());
            }
            try {
                Files.delete(Snapshots.file(level, b.snapshot()));
            } catch (IOException e) {
                throw helper.assertionException(Component.literal("the snapshot could not be removed: " + e));
            }
            sections.forEach(heat::release);
        });
    }

    private static LevelHeat heat(GameTestHelper helper) {
        return HeatEvents.of(helper.getLevel()).orElseThrow(
                () -> helper.assertionException(Component.literal("heat has not started in the test level")));
    }
}
