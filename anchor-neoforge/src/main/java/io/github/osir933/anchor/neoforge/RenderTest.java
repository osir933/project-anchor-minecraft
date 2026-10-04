package io.github.osir933.anchor.neoforge;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.slf4j.Logger;

/**
 * Checks in a real game client that hot blocks are drawn glowing. It runs only when the game is started with
 * {@code -Danchor.renderTest=true}, as CI does under a virtual display: it creates a flat world, builds a dark room
 * with two iron blocks in it, heats one to {@value #WARM_K} K and the other to {@value #HOT_K} K, photographs them,
 * cools them and photographs them again, then closes the game. CI compares the two pictures.
 *
 * <p>Each step logs a line starting with {@value #TAG}; the last says whether every step happened.
 */
final class RenderTest {

    /** The system property that switches the test on. */
    static final String PROPERTY = "anchor.renderTest";

    private static final String TAG = "ANCHOR RENDER TEST";
    private static final Logger LOGGER = LogUtils.getLogger();

    /** The iron block on the left as the camera sees it, heated less. */
    private static final BlockPos WARM = new BlockPos(-1, 101, -4);
    /** The iron block on the right, heated more. */
    private static final BlockPos HOT = new BlockPos(1, 101, -4);
    private static final double WARM_K = 1100.0;
    private static final double HOT_K = 1600.0;
    private static final double COOL_K = 300.0;

    /** Game ticks to wait once the glow has arrived or gone before a picture, so that drawn frames show it. */
    private static final int SETTLE_TICKS = 40;
    /** Game ticks between requests to the server while it is not ready. */
    private static final int RETRY_TICKS = 20;
    /** Game ticks a step may take before the test gives up. */
    private static final int STEP_TICKS = 1200;
    /** Game ticks the game may take to start and show its first menu. */
    private static final int START_TICKS = 6000;

    private enum Step {
        START, LOAD_WORLD, BUILD_ROOM, HEAT, SHOOT_HOT, COOL, SHOOT_COLD, DONE
    }

    private static Step step = Step.START;
    private static int stepTicks;
    private static int settledTicks;
    private static boolean menuShown;
    private static boolean shotTaken;
    private static int glowingWhenHot = -1;
    private static int glowingWhenCold = -1;
    /** Set by the server thread when it has done what the current step asked of it. */
    private static volatile boolean serverDone;
    /** Set while the server has a request from the test to run, so that only one is ever waiting. */
    private static volatile boolean serverBusy;
    /** Set when the current picture has been saved. */
    private static volatile boolean shotSaved;

    private RenderTest() {
    }

    /**
     * Registers the listeners that drive the test.
     *
     * @param bus NeoForge's game event bus
     */
    static void register(IEventBus bus) {
        bus.addListener(RenderTest::screenOpening);
        bus.addListener(RenderTest::tick);
        LOGGER.info("{}: waiting for the game to start", TAG);
    }

    private static void screenOpening(ScreenEvent.Opening event) {
        Screen screen = event.getNewScreen();
        if (screen instanceof TitleScreen || screen instanceof AccessibilityOnboardingScreen) {
            menuShown = true;
        }
    }

    private static void tick(ClientTickEvent.Post event) {
        if (step == Step.DONE) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        stepTicks++;
        if (stepTicks > (step == Step.START ? START_TICKS : STEP_TICKS)) {
            finish(minecraft, "gave up waiting at step " + step);
            return;
        }
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (step.compareTo(Step.BUILD_ROOM) >= 0 && (server == null || minecraft.level == null)) {
            finish(minecraft, "the world closed at step " + step);
            return;
        }
        switch (step) {
            case START -> {
                if (menuShown) {
                    next(Step.LOAD_WORLD);
                    createWorld(minecraft);
                }
            }
            case LOAD_WORLD -> {
                if (minecraft.level != null && minecraft.player != null && server != null) {
                    next(Step.BUILD_ROOM);
                    server.execute(() -> buildRoom(server));
                }
            }
            case BUILD_ROOM -> {
                if (serverDone) {
                    next(Step.HEAT);
                }
            }
            case HEAT -> {
                if (!serverDone) {
                    askServer(server, WARM_K, HOT_K);
                } else if (settled(GlowClient.glowingBlocks() >= 2)) {
                    glowingWhenHot = GlowClient.glowingBlocks();
                    next(Step.SHOOT_HOT);
                }
            }
            case SHOOT_HOT -> {
                if (shoot(minecraft, "hot.png")) {
                    next(Step.COOL);
                }
            }
            case COOL -> {
                if (!serverDone) {
                    askServer(server, COOL_K, COOL_K);
                } else if (settled(GlowClient.glowingBlocks() == 0)) {
                    glowingWhenCold = GlowClient.glowingBlocks();
                    next(Step.SHOOT_COLD);
                }
            }
            case SHOOT_COLD -> {
                if (shoot(minecraft, "cold.png")) {
                    finish(minecraft, null);
                }
            }
            default -> throw new IllegalStateException("unexpected step " + step);
        }
    }

    private static void next(Step following) {
        LOGGER.info("{}: {} done after {} ticks; now {}", TAG, step, stepTicks, following);
        step = following;
        stepTicks = 0;
        settledTicks = 0;
        serverDone = false;
        shotTaken = false;
        shotSaved = false;
    }

    /** Tells whether a condition has held for {@value #SETTLE_TICKS} ticks in a row. */
    private static boolean settled(boolean condition) {
        settledTicks = condition ? settledTicks + 1 : 0;
        return settledTicks >= SETTLE_TICKS;
    }

    /** Creates and opens a flat world in spectator mode, so that the player floats where they are put. */
    private static void createWorld(Minecraft minecraft) {
        minecraft.options.pauseOnLostFocus = false;
        minecraft.options.onboardAccessibility = false;
        minecraft.options.tutorialStep = TutorialSteps.NONE;
        LevelSettings settings = new LevelSettings("Anchor render test", GameType.SPECTATOR,
                new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true,
                WorldDataConfiguration.DEFAULT);
        minecraft.createWorldOpenFlows().createFreshLevel("anchor-render-test-" + System.currentTimeMillis(),
                settings, new WorldOptions(20261004L, false, false),
                registries -> registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value()
                        .createWorldDimensions(),
                new TitleScreen());
    }

    /**
     * Builds a closed room of black concrete high above the ground, with two iron blocks in it, and puts the player
     * in it facing them. Runs on the server thread.
     */
    private static void buildRoom(MinecraftServer server) {
        run(server, "time set midnight");
        run(server, "fill -6 96 -6 6 106 6 minecraft:black_concrete hollow");
        run(server, "setblock " + WARM.getX() + " " + WARM.getY() + " " + WARM.getZ() + " minecraft:iron_block");
        run(server, "setblock " + HOT.getX() + " " + HOT.getY() + " " + HOT.getZ() + " minecraft:iron_block");
        run(server, "tp @a 0.5 100 0.5 180 0");
        serverDone = true;
    }

    private static void run(MinecraftServer server, String command) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),
                command);
    }

    /** Asks the server, every {@value #RETRY_TICKS} ticks until it manages, to set the two blocks' temperatures. */
    private static void askServer(IntegratedServer server, double warmK, double hotK) {
        if (!serverBusy && stepTicks % RETRY_TICKS == 1) {
            serverBusy = true;
            server.execute(() -> {
                try {
                    setTemperatures(server, warmK, hotK);
                } finally {
                    serverBusy = false;
                }
            });
        }
    }

    private static void setTemperatures(MinecraftServer server, double warmK, double hotK) {
        Optional<LevelHeat> heat = HeatEvents.of(server.overworld());
        if (heat.isEmpty()) {
            LOGGER.info("{}: heat has not started yet", TAG);
            return;
        }
        boolean warm = heat.get().setTemperature(WARM, warmK);
        boolean hot = heat.get().setTemperature(HOT, hotK);
        LOGGER.info("{}: set {} K: {}, {} K: {}; the server sees {} and {} glowing blocks there", TAG, warmK, warm,
                hotK, hot, heat.get().glowingBlocks(WARM).size(), heat.get().glowingBlocks(HOT).size());
        serverDone = warm && hot;
    }

    /** Takes a picture of what the game shows and tells whether it has been saved. */
    private static boolean shoot(Minecraft minecraft, String name) {
        if (!shotTaken) {
            shotTaken = true;
            Path file = minecraft.gameDirectory.toPath().resolve("anchor-render-test").resolve(name);
            Screenshot.takeScreenshot(minecraft.gameRenderer.mainRenderTarget(), image -> save(image, file));
        }
        return shotSaved;
    }

    private static void save(NativeImage image, Path file) {
        try (image) {
            Files.createDirectories(file.getParent());
            image.writeToFile(file);
            LOGGER.info("{}: saved {}x{} picture {}", TAG, image.getWidth(), image.getHeight(), file);
            shotSaved = true;
        } catch (IOException e) {
            LOGGER.error("{}: could not save {}", TAG, file, e);
        }
    }

    private static void finish(Minecraft minecraft, String failure) {
        step = Step.DONE;
        if (failure == null) {
            LOGGER.info("{} PASSED its steps: {} glowing blocks when hot, {} when cooled", TAG, glowingWhenHot,
                    glowingWhenCold);
        } else {
            LOGGER.error("{} FAILED: {}; {} glowing blocks when hot, {} when cooled", TAG, failure, glowingWhenHot,
                    glowingWhenCold);
        }
        minecraft.stop();
    }
}
