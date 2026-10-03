package io.github.osir933.anchor.neoforge;

import io.github.osir933.anchor.core.host.HostedWorld;
import io.github.osir933.anchor.core.host.SectionSnapshot;
import io.github.osir933.anchor.core.world.Provenance;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Checks that run on a real server with the mod loaded: {@code ./gradlew :anchor-neoforge:runGameTestServer},
 * which CI runs too. Each test builds a small scene on a stone floor, keeps it simulated, and waits for the
 * simulation to do what physics says it should.
 */
final class AnchorGameTests {

    /** A test: its name, how many game ticks it may take, and what it does. */
    private record Case(String name, int maxTicks, Consumer<GameTestHelper> body) {
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
            new Case("saved_heat_keeps_its_numbers", 20, AnchorGameTests::savedHeatKeepsItsNumbers));

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
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(AnchorMod.id("heat"));
        for (Case c : CASES) {
            ResourceKey<Consumer<GameTestHelper>> function = ResourceKey.create(Registries.TEST_FUNCTION,
                    AnchorMod.id(c.name()));
            event.registerTest(AnchorMod.id(c.name()), new FunctionGameTestInstance(function,
                    new TestData<>(environment, AnchorMod.id("empty"), c.maxTicks(), 0, true)));
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
            Component reading = ThermometerItem.reading(helper.getLevel(), above);
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
                new double[] {7874.0, 1.2041, 0.0}, new double[] {1.0e9 / 3, -12_345.678_9, 0.0}));
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

    private static LevelHeat heat(GameTestHelper helper) {
        return HeatEvents.of(helper.getLevel()).orElseThrow(
                () -> helper.assertionException(Component.literal("heat has not started in the test level")));
    }
}
