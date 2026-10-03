package io.github.osir933.anchor.neoforge;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * The NeoForge entry point. This side of Anchor stays thin: it shows the core's physical world through
 * Minecraft and turns player actions into physical inputs, while every physical rule lives in the core.
 */
@Mod(AnchorMod.MOD_ID)
public final class AnchorMod {

    /** The mod id, also the namespace of Anchor's materials and models. */
    public static final String MOD_ID = "anchor";

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Called by NeoForge when the mod is constructed.
     *
     * @param modEventBus the mod's event bus
     * @param modContainer the container describing this mod
     */
    public AnchorMod(IEventBus modEventBus, ModContainer modContainer) {
        NeoForge.EVENT_BUS.addListener(AnchorCommands::register);
        LOGGER.info("Anchor {} loaded", modContainer.getModInfo().getVersion());
    }
}
