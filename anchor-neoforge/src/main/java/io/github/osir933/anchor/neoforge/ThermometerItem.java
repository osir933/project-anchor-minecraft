package io.github.osir933.anchor.neoforge;

import io.github.osir933.anchor.core.host.HostedWorld;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * Measures temperature. Used on a block it reads that block; used in the air it reads the air around the
 * player's head. The reading appears above the hotbar.
 */
final class ThermometerItem extends Item {

    /**
     * Creates the item.
     *
     * @param properties the item's properties
     */
    ThermometerItem(Item.Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getLevel() instanceof ServerLevel level && context.getPlayer() instanceof ServerPlayer player) {
            player.sendOverlayMessage(reading(level, context.getClickedPos()));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
            serverPlayer.sendOverlayMessage(reading(serverLevel, BlockPos.containing(player.getEyePosition())));
        }
        return InteractionResult.SUCCESS;
    }

    /** Reads the temperature of a block. */
    static Component reading(ServerLevel level, BlockPos pos) {
        Optional<LevelHeat> heat = HeatEvents.of(level);
        if (heat.isEmpty()) {
            return Component.translatableWithFallback("message.anchor.thermometer.off",
                    "Temperatures are not simulated here");
        }
        Optional<HostedWorld.Inspection> found = heat.get().inspect(pos);
        if (found.isEmpty()) {
            return Component.translatableWithFallback("message.anchor.thermometer.waiting",
                    "No reading yet: this area is still being loaded into the simulation");
        }
        double kelvin = found.get().temperatureK();
        if (Double.isNaN(kelvin)) {
            return Component.translatableWithFallback("message.anchor.thermometer.empty", "Nothing here to measure");
        }
        return Component.translatableWithFallback("message.anchor.thermometer.reading", "%s: %s",
                level.getBlockState(pos).getBlock().getName(), HeatText.celsius(kelvin));
    }
}
