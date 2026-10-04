package io.github.osir933.anchor.neoforge;

import com.mojang.serialization.Codec;
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

    /**
     * Which version of its setup a laboratory world's Overworld has been given, 0 for none, saved with the level;
     * see {@link Laboratory}.
     */
    static final Supplier<AttachmentType<Integer>> LABORATORY = TYPES.register("laboratory",
            () -> AttachmentType.builder(() -> 0).serialize(Codec.INT.fieldOf("setup"), version -> version > 0)
                    .build());

    /**
     * Which version of the laboratory's instruments a player has been given, 0 for none, saved with the player and
     * kept when they die; see {@link Laboratory}.
     */
    static final Supplier<AttachmentType<Integer>> LABORATORY_KIT = TYPES.register("laboratory_kit",
            () -> AttachmentType.builder(() -> 0).serialize(Codec.INT.fieldOf("version"), version -> version > 0)
                    .copyOnDeath().build());

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
