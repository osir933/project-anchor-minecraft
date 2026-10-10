package io.github.osir933.anchor.neoforge;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.BlockGrowFeatureEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Connects each server level's {@link LevelHeat} to the game's events. */
final class HeatEvents {

    private static final Map<ServerLevel, LevelHeat> LEVELS = new IdentityHashMap<>();

    private HeatEvents() {
    }

    /**
     * Registers the listeners.
     *
     * @param bus NeoForge's game event bus
     */
    static void register(IEventBus bus) {
        bus.addListener(HeatEvents::onLevelTick);
        bus.addListener(HeatEvents::onBlockChanged);
        bus.addListener(HeatEvents::onFeatureGrowing);
        bus.addListener(HeatEvents::onCropGrown);
        bus.addListener(HeatEvents::onFluidPlacing);
        bus.addListener(HeatEvents::onChunkUnload);
        bus.addListener(HeatEvents::onLevelSave);
        bus.addListener(HeatEvents::onLevelUnload);
        bus.addListener(HeatEvents::onServerStopping);
        bus.addListener(HeatEvents::onServerStopped);
        bus.addListener(HeatEvents::onTagsUpdated);
        bus.addListener(HeatEvents::onPlayerChangedDimension);
        bus.addListener(HeatEvents::onPlayerLoggedOut);
    }

    /**
     * Returns the heat simulation of a level, if heat runs there.
     *
     * @param level the level
     * @return its heat, or empty if heat is switched off or has not started yet
     */
    static Optional<LevelHeat> of(ServerLevel level) {
        return Optional.ofNullable(LEVELS.get(level));
    }

    private static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            LevelHeat heat = LEVELS.get(level);
            if (heat == null) {
                if (!AnchorConfig.get(AnchorConfig.HEAT_ENABLED)) {
                    return;
                }
                heat = new LevelHeat(level);
                LEVELS.put(level, heat);
            }
            // Frozen with /tick freeze, the level still ticks but nothing in it moves; heat waits with it.
            heat.tick(level.tickRateManager().runsNormally());
        }
    }

    private static void onBlockChanged(BlockEvent.NeighborNotifyEvent event) {
        if (event.getLevel() instanceof ServerLevel level) {
            LevelHeat heat = LEVELS.get(level);
            if (heat != null) {
                heat.blockChanged(event.getPos());
            }
        }
    }

    /** A tree, a huge mushroom or another feature grows right after this event; what it puts in is natural. */
    private static void onFeatureGrowing(BlockGrowFeatureEvent event) {
        if (event.getLevel() instanceof ServerLevel level && !event.isCanceled()) {
            LevelHeat heat = LEVELS.get(level);
            if (heat != null) {
                heat.featureGrowing(event.getPos());
            }
        }
    }

    /** A crop, a cactus, sugar cane or bamboo has just grown; what it grew is natural. */
    private static void onCropGrown(CropGrowEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            LevelHeat heat = LEVELS.get(level);
            if (heat != null) {
                heat.grownAround(event.getPos());
            }
        }
    }

    /** Lava meeting water is about to put stone, cobblestone, obsidian or basalt in; that block is natural. */
    private static void onFluidPlacing(BlockEvent.FluidPlaceBlockEvent event) {
        if (event.getLevel() instanceof ServerLevel level && !event.isCanceled()) {
            LevelHeat heat = LEVELS.get(level);
            if (heat != null) {
                heat.grown(event.getPos());
            }
        }
    }

    /** A chunk is unloaded before it is saved, so its sections go into it in time for that save. */
    private static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            LevelHeat heat = LEVELS.get(level);
            if (heat != null) {
                heat.chunkUnloaded(event.getChunk());
            }
        }
    }

    private static void onLevelSave(LevelEvent.Save event) {
        if (event.getLevel() instanceof ServerLevel level) {
            LevelHeat heat = LEVELS.get(level);
            if (heat != null) {
                heat.save();
            }
        }
    }

    private static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            LevelHeat heat = LEVELS.remove(level);
            if (heat != null) {
                heat.close();
            }
        }
    }

    /** The server stops before it saves for the last time, so everything simulated goes into its chunks first. */
    private static void onServerStopping(ServerStoppingEvent event) {
        for (LevelHeat heat : LEVELS.values()) {
            heat.save();
        }
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        LEVELS.clear();
    }

    private static void onTagsUpdated(TagsUpdatedEvent event) {
        for (LevelHeat heat : LEVELS.values()) {
            heat.appearancesChanged();
        }
    }

    /** A player's client forgets the glowing blocks it was told about when it leaves a level; so does the server. */
    private static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        forget(event.getEntity());
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        forget(event.getEntity());
    }

    private static void forget(Object entity) {
        if (entity instanceof ServerPlayer player) {
            for (LevelHeat heat : LEVELS.values()) {
                heat.forgetPlayer(player);
            }
        }
    }
}
