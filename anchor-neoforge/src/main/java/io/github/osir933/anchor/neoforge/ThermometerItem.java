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
import net.minecraft.world.phys.Vec3;

/**
 * Measures temperature. Used on a block it reads that block where it touches it: the cell there, for a block refined
 * into smaller cells, and the top of a block open to the sky, which the sun warms and a clear night chills faster
 * than the block as a whole. Used in the air it reads the air around the player's head. The reading appears above
 * the hotbar.
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
            player.sendOverlayMessage(reading(level, context.getClickedPos(), context.getClickLocation()));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
            Vec3 eye = player.getEyePosition();
            serverPlayer.sendOverlayMessage(reading(serverLevel, BlockPos.containing(eye), eye));
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * Reads the temperature of a block where the thermometer touches it.
     *
     * @param level the level
     * @param pos the block
     * @param at where the thermometer touches it
     * @return the reading
     */
    static Component reading(ServerLevel level, BlockPos pos, Vec3 at) {
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
        double kelvin = heat.get().temperatureAt(pos, at);
        if (Double.isNaN(kelvin)) {
            return Component.translatableWithFallback("message.anchor.thermometer.empty", "Nothing here to measure");
        }
        return Component.translatableWithFallback("message.anchor.thermometer.reading", "%s: %s",
                level.getBlockState(pos).getBlock().getName(), HeatText.celsius(kelvin));
    }
}
