package io.github.osir933.anchor.neoforge;

import io.github.osir933.anchor.core.host.HostedWorld;
import io.github.osir933.anchor.core.instrument.ProbeSet;
import io.github.osir933.anchor.core.space.GridPos;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
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
import net.minecraft.world.phys.Vec3;

/**
 * Measures temperature. Used on a block it reads that block where it touches it: the cell there, for a block refined
 * into smaller cells, and the top of a block open to the sky, which the sun warms and a clear night chills faster
 * than the block as a whole. Used in the air it reads the air around the player's head. The reading appears above
 * the hotbar.
 *
 * <p>Used on a block while sneaking, it leaves a probe there that records the temperature where it touched every
 * simulation step, or takes away the probe already in the block; see {@link ProbeCommands}. Reading a block with a
 * probe also tells how fast the probe finds it warming or cooling, and a reading taken while heat is paused with
 * {@code /anchor time pause} says so.
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
            if (context.isSecondaryUseActive()) {
                player.sendOverlayMessage(ProbeCommands.toggle(level, context.getClickedPos(),
                        context.getClickLocation()).message());
            } else {
                player.sendOverlayMessage(reading(level, context.getClickedPos(), context.getClickLocation()));
            }
        }
        return InteractionResult.SUCCESS;
    }

    // Mojang marks this deprecated as tooltips move to data components, but it is still how an item adds lines of
    // its own, and a game test checks that they show.
    @Override
    @SuppressWarnings("deprecation")
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display,
            Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(Component.translatableWithFallback("item.anchor.thermometer.use",
                "Use it on a block or in the air to read the temperature").withStyle(ChatFormatting.GRAY));
        tooltip.accept(Component.translatableWithFallback("item.anchor.thermometer.sneak",
                "Sneak and use it on a block to record it over time").withStyle(ChatFormatting.GRAY));
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
        MutableComponent reading = Component.translatableWithFallback("message.anchor.thermometer.reading", "%s: %s",
                level.getBlockState(pos).getBlock().getName(), HeatText.celsius(kelvin));
        Optional<ProbeSet.Probe> probe = ProbeCommands.probes(level).set().in(
                new GridPos(pos.getX(), pos.getY(), pos.getZ()));
        if (probe.isPresent()) {
            String rate = HeatText.rate(probe.get().series().recentRate());
            reading.append(rate.isEmpty()
                    ? Component.translatableWithFallback("message.anchor.thermometer.recording", ", recorded as %s",
                            probe.get().name())
                    : Component.translatableWithFallback("message.anchor.thermometer.trend", ", recorded as %s: %s",
                            probe.get().name(), rate));
        }
        if (heat.get().pace().paused()) {
            reading.append(Component.translatableWithFallback("message.anchor.thermometer.paused",
                    " (heat is paused)"));
        }
        return reading;
    }
}
