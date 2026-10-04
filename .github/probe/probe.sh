#!/usr/bin/env bash
# Prints public API signatures of the game classes the integration uses. Signatures only, no code.
set -uo pipefail

GAME=""
NEO=""
while IFS= read -r j; do
  listing=$(unzip -l "$j" 2>/dev/null)
  if [[ -z "$GAME" ]] && grep -q 'net/minecraft/server/level/ServerLevel.class' <<<"$listing"; then GAME="$j"; fi
  if [[ -z "$NEO" ]] && grep -q 'net/neoforged/neoforge/attachment/AttachmentType.class' <<<"$listing"; then NEO="$j"; fi
done < <(find anchor-neoforge/build "$HOME/.gradle/caches" -name '*.jar' 2>/dev/null | grep -iE 'neoforge|minecraft' )
FML=""
while IFS= read -r j; do
  if unzip -l "$j" 2>/dev/null | grep -q 'net/neoforged/fml/ModContainer.class'; then FML="$j"; break; fi
done < <(find "$HOME/.gradle/caches" -name '*.jar' 2>/dev/null | grep -iE 'fml|loader')
echo "GAME=$GAME NEO=$NEO FML=$FML"
CP="$GAME:$NEO:$FML"

clean() {
  grep -v '^Compiled from' | sed -E -e 's/^  //' -e 's/(public|abstract|final|native|synchronized|default) //g' \
    -e 's/java\.lang\.//g' -e 's/java\.util\.function\.//g' -e 's/java\.util\.//g'
}
p() { echo "=== $1"; javap -cp "$CP" -public "$1" 2>&1 | clean; }
pf() { local cls="$1"; local re="$2"; echo "=== $cls ~ $re"; javap -cp "$CP" -public "$cls" 2>&1 | clean | grep -E "$re|^(class|interface|enum|record)|Error"; }

echo "### round 20: laboratory: create-world screen, level generator, chat events, blocks for experiments"
CLS=net.minecraft.client.gui.screens.worldselection
pf $CLS.CreateWorldScreen 'getUiState|class|static|openFresh'
p $CLS.WorldCreationUiState
p "$CLS.WorldCreationUiState\$SelectedGameMode"
p "$CLS.WorldCreationUiState\$WorldTypeEntry"
p 'net.neoforged.neoforge.client.event.ScreenEvent$Init$Post'
pf 'net.neoforged.neoforge.client.event.ScreenEvent$Init' 'class|getScreen|addListener|getListenersList'
pf net.neoforged.neoforge.client.event.ScreenEvent 'class|getScreen'
pf net.neoforged.neoforge.client.event.RegisterPresetEditorsEvent 'class|register'
pf net.minecraft.server.level.ServerChunkCache 'getGenerator|class'
pf net.minecraft.world.level.chunk.ChunkGenerator 'getBiomeSource|class'
pf net.minecraft.world.level.biome.BiomeSource 'possibleBiomes|class'
pf net.minecraft.world.level.LevelReader 'getBiome|interface'
pf net.minecraft.server.MinecraftServer 'isSingleplayer|isDedicatedServer|getPlayerList|registryAccess|getCommands|isSingleplayerOwner|getWorldPath|isPublished'
pf net.minecraft.server.players.PlayerList 'class|setAllowCommandsForAllPlayers|isAllowCommands|sendPlayerPermissionLevel|isOp'
pf net.minecraft.world.level.storage.WorldData 'interface|isAllowCommands|getAllowCommands|setAllowCommands|isAllowCheats'
pf net.minecraft.world.level.storage.PrimaryLevelData 'class|isAllowCommands|setAllowCommands|getAllowCommands'
pf net.minecraft.world.item.ItemStack 'ItemStack\(|class'
pf net.neoforged.neoforge.event.entity.player.PlayerEvent 'class|getEntity'
pf net.neoforged.neoforge.event.server.ServerLifecycleEvent 'class|getServer'
p net.minecraft.network.chat.ClickEvent
pf 'net.minecraft.network.chat.ClickEvent$SuggestCommand' 'class|record|SuggestCommand\('
pf 'net.minecraft.network.chat.ClickEvent$RunCommand' 'class|record|RunCommand\('
pf 'net.minecraft.network.chat.HoverEvent$ShowText' 'class|record|ShowText\('
pf net.minecraft.network.chat.Style 'class|withClickEvent|withHoverEvent|withUnderlined|EMPTY'
pf net.minecraft.network.chat.MutableComponent 'class|withStyle|setStyle|append'
pf net.minecraft.world.entity.Entity 'class|getDirection\(|getYRot\(|blockPosition\(|getMotionDirection'
pf net.minecraft.core.Direction 'class|getClockWise\(|getCounterClockWise\(|getStepX|getStepZ|getOpposite\(|getUnitVec3i|fromYRot|getAxis\(|toYRot'
pf net.minecraft.world.level.levelgen.presets.WorldPresets 'class|static'
pf net.minecraft.world.level.block.Blocks 'class|WOOL|CONCRETE|COPPER_BLOCK|IRON_BLOCK| GLASS;|OAK_PLANKS|GRANITE;|MAGMA_BLOCK|ICE;|PACKED_ICE|BLUE_ICE|SNOW_BLOCK|WATER;|LAVA;|STONE;|SMOOTH_STONE;|GOLD_BLOCK|QUARTZ_BLOCK|OBSIDIAN|BRICKS;|TERRACOTTA|AIR;|BARRIER|POLISHED_ANDESITE|STONE_BRICKS'
pf net.minecraft.world.level.block.ColorCollection 'class|white\(|lightGray\(|black\(|get\(|record'
pf net.minecraft.world.item.Items 'class|FILLED_MAP|MAP;|ITEM_FRAME'
pf net.minecraft.server.level.ServerPlayer 'class|sendSystemMessage|displayClientMessage|getRespawnConfig|getRespawnPosition'
pf net.minecraft.world.level.levelgen.WorldDimensions 'class|get\(|dimensions|overworld'
pf net.minecraft.world.level.dimension.LevelStem 'class|generator\(|type\(|OVERWORLD'
unzip -p "$GAME" assets/minecraft/lang/en_us.json 2>/dev/null | grep -oE '"block\.minecraft\.(light_gray_concrete|white_concrete|copper_block|iron_block|oak_planks|white_wool|magma_block)"' | head -20
DATAJAR=""
while IFS= read -r j; do
  if unzip -l "$j" 2>/dev/null | grep -q 'data/minecraft/worldgen/world_preset/normal.json'; then DATAJAR="$j"; break; fi
done < <(find anchor-neoforge/build "$HOME/.gradle/caches" -name '*.jar' 2>/dev/null)
unzip -p "$DATAJAR" assets/minecraft/lang/en_us.json 2>/dev/null | grep -oE '"block\.minecraft\.(light_gray_concrete|white_concrete|copper_block|iron_block|oak_planks|white_wool|magma_block)"' | head -20
python3 .github/probe/json_shape.py "$DATAJAR" data/minecraft/dimension_type/overworld.json data/minecraft/worldgen/world_preset/single_biome_surface.json
