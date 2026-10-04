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

echo "### round 24: copper blocks, block flags, chat run command, game test helpers"
p net.minecraft.world.level.block.WeatheringCopperCollection
for c in $(unzip -l "$GAME" | grep -oE 'net/minecraft/world/level/block/WeatheringCopperCollection[^ ]*\.class|net/minecraft/world/level/block/WeatheringCopper\$[^ ]*\.class' | sed 's/\.class$//; s#/#.#g' | sort -u); do p "$c"; done
for c in $(unzip -l "$GAME" | grep -oE 'net/minecraft/world/level/block/[A-Za-z]*ByState[^ ]*\.class|net/minecraft/util/[A-Za-z]*ByState[^ ]*\.class' | sed 's/\.class$//; s#/#.#g' | sort -u); do p "$c"; done
unzip -l "$GAME" | grep -oE 'net/minecraft/[a-z/]*ByState[^ ]*\.class' | sort -u | head
pf net.minecraft.world.level.block.Blocks 'COPPER_BLOCK|BRICKS|SMOOTH_STONE|PACKED_ICE|WAXED'
pf net.minecraft.network.chat.ClickEvent 'RunCommand|SuggestCommand'
pf net.minecraft.world.entity.Entity 'getDirection|getYRot|blockPosition'
pf net.minecraft.core.Direction 'getClockWise|getCounterClockWise|getStepX|getStepZ|fromYRot|getNearest|Plane'
pf net.minecraft.gametest.framework.GameTestHelper 'makeMockPlayer|absolutePos|getLevel|relativePos|getTestRotation|getBounds|testInfo'
pf net.minecraft.world.level.block.Block 'UPDATE_'
pf net.minecraft.world.level.block.state.BlockBehaviour\$BlockStateBase 'canBeReplaced|hasBlockEntity|isAir|getFluidState'
