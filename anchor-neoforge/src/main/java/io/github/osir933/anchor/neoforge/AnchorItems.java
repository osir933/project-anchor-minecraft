package io.github.osir933.anchor.neoforge;

import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Anchor's items. */
final class AnchorItems {

    static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(AnchorMod.MOD_ID);

    /** Reads the temperature of blocks and air. */
    static final DeferredItem<ThermometerItem> THERMOMETER = ITEMS.registerItem("thermometer", ThermometerItem::new,
            properties -> properties.stacksTo(1));

    private AnchorItems() {
    }

    /**
     * Registers the items and puts them in the creative inventory.
     *
     * @param modEventBus the mod's event bus
     */
    static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        modEventBus.addListener(AnchorItems::addToCreativeTabs);
    }

    private static void addToCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(THERMOMETER.get());
        }
    }
}
