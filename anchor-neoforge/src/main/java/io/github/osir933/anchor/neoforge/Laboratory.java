package io.github.osir933.anchor.neoforge;

import com.mojang.logging.LogUtils;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.clock.ClockTimeMarkers;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

/**
 * The Laboratory, a world type for experiments, listed as Anchor Laboratory beside Minecraft's own world types. Its
 * Overworld is a flat floor of light grey concrete, its top at y = -1, over stone, all of it in one biome, the
 * laboratory, whose air is at 20 °C and 50 % relative humidity, where it never rains and nothing spawns.
 *
 * <p>Experiments there run in steady surroundings. Heat follows no sun and no night sky in a laboratory (see
 * {@link LevelHeat}), so whatever is not heated or cooled settles at the air's temperature, day and night alike.
 * When a laboratory world is first loaded, the time of day is held at noon and the weather at clear; mobs, phantoms,
 * patrols and wandering traders stop spawning; and random ticks stop, so that crops, grass, copper and Minecraft's own
 * melting of ice and snow leave blocks alone and only the simulation changes them. These are ordinary game rules,
 * which operators can change back with {@code /gamerule}. Each player is given a thermometer and a thermal camera the
 * first time they join.
 *
 * <p>Choosing the Laboratory on the Create New World screen switches the new world to Creative mode with commands
 * allowed, which players can change back before creating it; see {@link LaboratoryScreen}.
 *
 * <p>A level counts as a laboratory if every biome its generator can place is the laboratory: the Overworld of this
 * world type, or a world of the Single Biome type made of it.
 */
final class Laboratory {

    /** The laboratory's biome. */
    static final ResourceKey<Biome> BIOME = ResourceKey.create(Registries.BIOME, AnchorMod.id("laboratory"));

    /** The Laboratory world type. */
    static final ResourceKey<WorldPreset> PRESET = ResourceKey.create(Registries.WORLD_PRESET,
            AnchorMod.id("laboratory"));

    /**
     * The version of the setup a laboratory world is given when first loaded. A world set up by an earlier version
     * is given what was added since.
     */
    static final int SETUP_VERSION = 1;

    /**
     * The version of the instruments each player is given. A player given an earlier set is given what was added
     * since.
     */
    static final int KIT_VERSION = 1;

    private static final Logger LOGGER = LogUtils.getLogger();

    private Laboratory() {
    }

    /**
     * Registers the listeners.
     *
     * @param bus NeoForge's game event bus
     */
    static void register(IEventBus bus) {
        bus.addListener(Laboratory::serverStarted);
        bus.addListener(Laboratory::playerLoggedIn);
    }

    /**
     * Tells whether a level is a laboratory: whether every biome its generator can place is the laboratory.
     *
     * @param level the level
     * @return {@code true} for a laboratory
     */
    static boolean is(ServerLevel level) {
        Set<Holder<Biome>> biomes = level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes();
        if (biomes.isEmpty()) {
            return false;
        }
        for (Holder<Biome> biome : biomes) {
            if (!biome.is(BIOME)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Tells whether a server's world is a laboratory: whether its Overworld is one.
     *
     * @param server the server
     * @return {@code true} for a laboratory world
     */
    static boolean is(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld != null && is(overworld);
    }

    private static void serverStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        if (!is(server)) {
            return;
        }
        ServerLevel overworld = server.overworld();
        int version = overworld.getData(AnchorAttachments.LABORATORY);
        if (version < SETUP_VERSION) {
            setUp(server);
            overworld.setData(AnchorAttachments.LABORATORY, SETUP_VERSION);
            LOGGER.info("Anchor set up this laboratory world: the time is held at noon and the weather at clear, "
                    + "and mobs, phantoms, patrols, wandering traders and random ticks are stopped");
        }
    }

    /**
     * Gives a laboratory world its steady conditions: the time held at noon and the weather at clear, nothing
     * spawning and no random ticks.
     *
     * @param server the server
     */
    static void setUp(MinecraftServer server) {
        GameRules rules = server.getGameRules();
        rules.set(GameRules.ADVANCE_TIME, false, server);
        rules.set(GameRules.ADVANCE_WEATHER, false, server);
        rules.set(GameRules.SPAWN_MOBS, false, server);
        rules.set(GameRules.SPAWN_PHANTOMS, false, server);
        rules.set(GameRules.SPAWN_PATROLS, false, server);
        rules.set(GameRules.SPAWN_WANDERING_TRADERS, false, server);
        rules.set(GameRules.RANDOM_TICK_SPEED, 0, server);
        ServerLevel overworld = server.overworld();
        Optional<Holder<WorldClock>> clock = overworld.dimensionType().defaultClock();
        if (clock.isEmpty() || server.clockManager().moveToTimeMarker(clock.get(), ClockTimeMarkers.NOON)
                == ServerClockManager.MoveResult.NO_TIME_MARKER_FOUND) {
            LOGGER.warn("Anchor could not set the laboratory's time to noon: its clock has no noon");
        }
        overworld.resetWeatherCycle();
    }

    private static void playerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !is(player.level().getServer())) {
            return;
        }
        int given = player.getData(AnchorAttachments.LABORATORY_KIT);
        if (given >= KIT_VERSION) {
            return;
        }
        player.setData(AnchorAttachments.LABORATORY_KIT, KIT_VERSION);
        AnchorItems.give(player, new ItemStack(AnchorItems.THERMOMETER.get()));
        AnchorItems.give(player, new ItemStack(AnchorItems.THERMAL_CAMERA.get()));
        for (Component line : welcome(player.level(), player.blockPosition())) {
            player.sendSystemMessage(line);
        }
    }

    /**
     * Returns the lines that welcome a player to a laboratory, naming the air's temperature and humidity where
     * they are.
     *
     * @param level the level
     * @param pos where the player is
     * @return the lines
     */
    static Component[] welcome(ServerLevel level, BlockPos pos) {
        Biome biome = level.getBiome(pos).value();
        String air = HeatText.celsius(Climate.kelvin(biome.getBaseTemperature(), pos.getY()));
        String humidity = String.format(Locale.ROOT, "%.0f %%",
                100.0 * Climate.relativeHumidity(biome.getModifiedClimateSettings().downfall()));
        return new Component[] {
            Component.translatableWithFallback("message.anchor.laboratory.welcome", "Welcome to the Anchor Laboratory")
                    .withStyle(ChatFormatting.GOLD),
            Component.translatableWithFallback("message.anchor.laboratory.air", "The air here stays at %s and %s "
                    + "humidity, the sun gives no heat, and nothing spawns or grows, so only your experiments change "
                    + "what you build.", air, humidity),
            Component.translatableWithFallback("message.anchor.laboratory.instruments", "Use the thermometer on a "
                    + "block to read it, or sneak and use it to record it; hold the thermal camera to see heat."),
            Component.translatableWithFallback("message.anchor.laboratory.commands", "%s lists ready-made "
                    + "experiments to build, %s pauses, steps and speeds up heat, and %s saves your own experiments so "
                    + "that you can run them again.", ChatLinks.run("/anchor experiment list"),
                    ChatLinks.type("/anchor time"), ChatLinks.type("/anchor snapshot"))
        };
    }
}
