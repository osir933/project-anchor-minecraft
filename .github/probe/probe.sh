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

echo "### round 18: world creation details, pipeline registration, screens"
p 'net.minecraft.world.level.LevelSettings$DifficultySettings'
pf net.minecraft.world.level.GameType 'CREATIVE|SPECTATOR|SURVIVAL|enum'
pf net.minecraft.world.Difficulty 'PEACEFUL|enum'
pf 'net.minecraft.core.Holder$Reference' 'value\(|class'
pf net.minecraft.core.Holder 'value\(|interface'
p net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent
p 'net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent$Registrar'
pf net.minecraft.client.gui.screens.AccessibilityOnboardingScreen 'class|AccessibilityOnboardingScreen\('
pf 'net.neoforged.neoforge.client.event.ScreenEvent$Opening' 'class|setNewScreen|getNewScreen|getCurrentScreen'
p 'net.neoforged.neoforge.client.event.RenderFrameEvent$Post'
p net.minecraft.client.DeltaTracker
pf net.minecraft.commands.CommandSourceStack 'withSuppressedOutput|withPermission|withMaximumPermission|class|withPosition|withLevel'
pf net.minecraft.server.level.ServerLevel 'setDayTime|getDayTime|class'
pf net.minecraft.server.level.ServerPlayer 'teleportTo|class|setGameMode|connection'
pf net.minecraft.world.level.block.state.BlockState 'class|getShape|getLightEmission'
pf 'net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase' 'getShape\(|getCollisionShape\(|isAir|getRenderShape|class'
pf net.minecraft.world.phys.shapes.VoxelShape 'toAabbs|bounds|isEmpty|forAllBoxes|class'
pf net.minecraft.world.phys.AABB 'minX|maxX|class'
pf net.minecraft.world.level.block.Block 'stateById|getId|class'
pf com.mojang.blaze3d.platform.NativeImage 'getPixel|format|Format|class'
pf 'com.mojang.blaze3d.platform.NativeImage$Format' 'RGBA|RGB|enum'
pf net.minecraft.client.Options 'hideGui|smoothCamera|bobView|class'
pf net.minecraft.client.gui.Gui 'class|hide'
pf net.minecraft.client.player.LocalPlayer 'class|setYRot|setXRot'
pf net.minecraft.world.entity.Entity 'setYRot|setXRot|getYRot|snapTo|moveTo|absSnapTo|class'
pf net.minecraft.core.SectionPos 'asLong|of\(|class|x\(|y\(|z\(|minBlockX|sectionToBlockCoord|blockToSectionCoord'
pf net.minecraft.world.level.Level 'getLightEmission|getBlockState|class|isOutsideBuildHeight|getMaxY|getMinY'
