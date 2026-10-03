package io.github.osir933.anchor.neoforge;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent;

/** The data maps Anchor reads from data packs. */
final class AnchorDataMaps {

    /**
     * What blocks are made of, read from {@code data/anchor/data_maps/block/materials.json} in every data pack.
     * Blocks it does not list are described by Anchor's built-in rules; see {@link MaterialEntry}.
     */
    static final DataMapType<Block, MaterialEntry> MATERIALS = DataMapType.builder(AnchorMod.id("materials"),
            Registries.BLOCK, MaterialEntry.CODEC).build();

    private AnchorDataMaps() {
    }

    static void register(RegisterDataMapTypesEvent event) {
        event.register(MATERIALS);
    }
}
