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

echo "### round 19: laboratory preset: data formats, game rules, levels, players, config"
DATAJAR=""
while IFS= read -r j; do
  if unzip -l "$j" 2>/dev/null | grep -q 'data/minecraft/worldgen/world_preset/normal.json'; then DATAJAR="$j"; break; fi
done < <(find anchor-neoforge/build "$HOME/.gradle/caches" -name '*.jar' 2>/dev/null)
echo "DATAJAR=$DATAJAR"
python3 .github/probe/json_shape.py "$DATAJAR" data/minecraft/worldgen/world_preset/normal.json \
  data/minecraft/worldgen/world_preset/flat.json data/minecraft/worldgen/biome/plains.json \
  data/minecraft/worldgen/biome/the_void.json data/minecraft/tags/worldgen/world_preset/normal.json \
  data/minecraft/worldgen/flat_level_generator_preset/classic_flat.json \
  data/minecraft/worldgen/world_preset/ data/minecraft/tags/worldgen/world_preset/
unzip -l "$DATAJAR" | grep -E 'lang/en_us.json' | head -3
unzip -p "$DATAJAR" assets/minecraft/lang/en_us.json 2>/dev/null | grep -oE '"(generator\.minecraft\.[a-z_]+|biome\.minecraft\.(plains|the_void))"' | head -20
pf net.minecraft.world.level.gamerules.GameRules 'static|class|set\(|get\(|getBoolean|getInt'
pf net.minecraft.world.level.gamerules.GameRule 'class|id\(|valueClass|defaultValue|getIdentifier|name'
pf net.minecraft.world.level.gamerules.GameRuleType 'class|enum'
pf net.minecraft.server.level.ServerLevel 'setDayTime|setWeatherParameters|getBiome|getGameRules|getSharedSpawnPos|getRespawnData|class|getLevelData|resetWeatherCycle'
pf net.minecraft.world.level.Level 'getBiome|getSharedSpawnPos|getRespawnData|class|getGameRules|getLevelData'
pf net.minecraft.server.MinecraftServer 'setDefaultGameType|getDefaultGameType|getWorldData|isHardcore|getGameRules|overworld\(|class|getForcedGameType|getWorldGenSettings'
pf net.minecraft.world.level.storage.WorldData 'isHardcore|getGameType|setGameType|interface|getGameRules|setDifficulty'
pf net.minecraft.world.level.storage.ServerLevelData 'setGameType|getGameType|interface|setClearWeatherTime|setDayTime'
pf net.minecraft.server.level.ServerPlayer 'setGameMode|gameMode|class|getInventory'
pf net.minecraft.world.entity.player.Player 'getInventory|class|addItem|isCreative'
pf net.minecraft.world.entity.player.Inventory 'add\(|class|setItem|getSelected|placeItemBackInInventory'
pf net.minecraft.core.Holder 'is\(|interface|unwrapKey'
pf net.minecraft.world.level.biome.Biome 'class|getBaseTemperature|climateSettings|hasPrecipitation'
pf net.minecraft.world.level.levelgen.FlatLevelSource 'class|settings'
pf net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings 'class|getBiome|biome|getLayers'
pf 'net.neoforged.neoforge.common.ModConfigSpec$ConfigValue' 'set\(|save\(|class|get\(|getDefault'
pf net.neoforged.neoforge.common.ModConfigSpec 'save\(|class|isLoaded'
pf 'net.neoforged.neoforge.event.entity.player.PlayerEvent$PlayerLoggedInEvent' 'class|getEntity'
pf net.neoforged.neoforge.event.server.ServerStartedEvent 'class|getServer'
pf 'net.neoforged.neoforge.attachment.AttachmentType$Builder' 'class|copyOnDeath|serialize|build'
pf net.minecraft.world.level.GameType 'CREATIVE|class|enum|getName'
pf net.minecraft.world.level.levelgen.presets.WorldPreset 'class|createWorldDimensions|overworld'
pf net.minecraft.world.level.biome.Biomes 'PLAINS|THE_VOID|class'
pf net.minecraft.core.registries.Registries 'BIOME;|WORLD_PRESET;|FLAT_LEVEL_GENERATOR_PRESET'
pf net.minecraft.world.level.storage.LevelData 'getSpawnPos|getRespawnData|interface|getDayTime'
pf 'net.minecraft.world.level.storage.LevelData$RespawnData' 'pos\(|class|record'
pf net.minecraft.world.level.timers.TimerQueue 'class'
pf net.minecraft.world.clock.WorldClock 'class'
