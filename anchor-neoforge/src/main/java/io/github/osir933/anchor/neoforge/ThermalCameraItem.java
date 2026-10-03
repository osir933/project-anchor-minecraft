package io.github.osir933.anchor.neoforge;

import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * Shows where heat is. Held in either hand, it shows the temperatures of what the player looks at; see
 * {@link ThermalCamera}. Using it switches between the surfaces in view and the air, and using it while sneaking
 * locks the scale so that images can be compared over time.
 */
final class ThermalCameraItem extends Item {

    /**
     * Creates the item.
     *
     * @param properties the item's properties
     */
    ThermalCameraItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer) {
            ThermalCamera.use(serverPlayer);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getPlayer() instanceof ServerPlayer player) {
            ThermalCamera.use(player);
        }
        return InteractionResult.SUCCESS;
    }

    // Mojang marks this deprecated as tooltips move to data components, but it is still how an item adds lines of
    // its own, and a game test checks that they show.
    @Override
    @SuppressWarnings("deprecation")
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display,
            Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(Component.translatableWithFallback("item.anchor.thermal_camera.hold",
                "Hold it to see temperatures").withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.translatableWithFallback("item.anchor.thermal_camera.use",
                "Use it to switch between surfaces and air").withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.translatableWithFallback("item.anchor.thermal_camera.sneak",
                "Sneak and use it to lock the scale").withStyle(ChatFormatting.GRAY));
    }
}
