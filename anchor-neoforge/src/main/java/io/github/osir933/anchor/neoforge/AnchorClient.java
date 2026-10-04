package io.github.osir933.anchor.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.common.NeoForge;

/** The client's entry point, which NeoForge constructs only in the game's client: it draws what the server sends. */
@Mod(value = AnchorMod.MOD_ID, dist = Dist.CLIENT)
public final class AnchorClient {

    /**
     * Called by NeoForge when the mod is constructed on a client.
     *
     * @param modEventBus the mod's event bus
     * @param modContainer the container describing this mod
     */
    public AnchorClient(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(AnchorClient::registerPayloadHandlers);
        GlowClient.register(NeoForge.EVENT_BUS);
        if (Boolean.getBoolean(RenderTest.PROPERTY)) {
            RenderTest.register(NeoForge.EVENT_BUS);
        }
    }

    private static void registerPayloadHandlers(RegisterClientPayloadHandlersEvent event) {
        event.register(GlowPayload.TYPE, GlowClient::receive);
    }
}
