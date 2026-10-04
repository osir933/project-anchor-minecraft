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

echo "### round 21: time, weather and clocks; copper; command results"
pf net.minecraft.server.level.ServerLevel 'class|[Tt]ime|[Cc]lock|[Ww]eather|[Rr]ain|[Tt]hunder'
pf net.minecraft.world.level.Level 'class|[Tt]ime\(|[Cc]lock|[Rr]ain|[Tt]hunder'
pf net.minecraft.server.MinecraftServer 'class|[Cc]lock|[Tt]ime\('
p net.minecraft.world.clock.WorldClock
for c in $(unzip -l "$GAME" | grep -oE 'net/minecraft/world/clock/[A-Za-z$]+\.class' | sed 's/\.class//; s#/#.#g' | sort -u | head -20); do echo "--- $c"; done
for c in $(unzip -l "$GAME" | grep -oE 'net/minecraft/world/clock/[A-Za-z]+\.class' | sed 's/\.class//; s#/#.#g' | sort -u | head -8); do p "$c"; done
pf net.minecraft.server.commands.TimeCommand 'class|static'
pf net.minecraft.server.commands.WeatherCommand 'class|static'
pf net.minecraft.world.level.storage.ServerLevelData 'interface|[Ww]eather|[Rr]ain|[Tt]hunder|[Tt]ime|[Cc]lear'
p net.minecraft.world.level.block.WeatheringCopperCollection
pf net.minecraft.commands.Commands 'class|performPrefixedCommand|performCommand'
pf net.minecraft.commands.CommandSourceStack 'class|withCallback|withSuppressedOutput|withPermission|withPosition'
DATAJAR=""
while IFS= read -r j; do
  if unzip -l "$j" 2>/dev/null | grep -q 'data/minecraft/worldgen/world_preset/normal.json'; then DATAJAR="$j"; break; fi
done < <(find anchor-neoforge/build "$HOME/.gradle/caches" -name '*.jar' 2>/dev/null)
unzip -l "$DATAJAR" | grep -oE 'data/minecraft/(timeline|world_clock|tags/timeline)/[a-z_/]+\.json' | head -20
python3 .github/probe/json_shape.py "$DATAJAR" data/minecraft/tags/timeline/in_overworld.json data/minecraft/world_clock/overworld.json data/minecraft/timeline/day.json
