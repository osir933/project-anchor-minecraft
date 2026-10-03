package io.github.osir933.anchor.neoforge;

import io.github.osir933.anchor.core.host.HostedWorld;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
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
            new Case("torch_warms_the_air", 800, AnchorGameTests::torchWarmsTheAir));

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
