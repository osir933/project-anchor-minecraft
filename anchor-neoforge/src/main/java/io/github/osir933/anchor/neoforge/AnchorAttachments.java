package io.github.osir933.anchor.neoforge;

import java.util.function.Supplier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** The data Anchor attaches to Minecraft's objects and saves with them. */
final class AnchorAttachments {

    private static final DeferredRegister<AttachmentType<?>> TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, AnchorMod.MOD_ID);

    /** The heat saved with a chunk; see {@link ChunkHeat}. */
    static final Supplier<AttachmentType<ChunkHeat>> CHUNK_HEAT = TYPES.register("chunk_heat",
            () -> AttachmentType.builder(() -> new ChunkHeat()).serialize(ChunkHeat.SERIALIZER).build());

    /** The probes of a level and the charts drawn from them, saved with the level; see {@link LevelProbes}. */
    static final Supplier<AttachmentType<LevelProbes>> PROBES = TYPES.register("probes",
            () -> AttachmentType.builder(() -> new LevelProbes()).serialize(LevelProbes.SERIALIZER).build());

    /** Whether heat in a level is paused and how fast it runs, saved with the level; see {@link LevelPace}. */
    static final Supplier<AttachmentType<LevelPace>> PACE = TYPES.register("pace",
            () -> AttachmentType.builder(() -> new LevelPace()).serialize(LevelPace.SERIALIZER).build());

    private AnchorAttachments() {
    }

    /**
     * Registers the attachment types.
     *
     * @param modEventBus the mod's event bus
     */
    static void register(IEventBus modEventBus) {
        TYPES.register(modEventBus);
    }
}
