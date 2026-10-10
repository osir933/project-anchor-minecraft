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

echo "### round 25: falling blocks, sounds, particles, growth events, shapes, game test helpers"
p net.minecraft.world.entity.item.FallingBlockEntity
pf net.minecraft.world.level.Level 'destroyBlock|playSound|levelEvent|getGameTime|removeBlock|destroyBlockProgress'
pf net.minecraft.server.level.ServerLevel 'sendParticles|playSound|addFreshEntity|getEntities|destroyBlock|destroyBlockProgress|getGameTime'
pf net.minecraft.world.level.block.SoundType 'getBreakSound|getVolume|getPitch|getHitSound|getFallSound'
pf net.minecraft.world.level.block.state.BlockBehaviour\$BlockStateBase 'getSoundType|canSurvive|getCollisionShape|isCollisionShapeFullBlock|hasBlockEntity|getDestroySpeed|getPistonPushReaction|isAir'
p net.minecraft.core.particles.BlockParticleOption
pf net.minecraft.core.particles.ParticleTypes 'BLOCK|FALLING_DUST|DUST_PLUME|POOF'
pf net.minecraft.world.level.block.state.properties.BlockStateProperties 'DOUBLE_BLOCK_HALF|BED_PART'
unzip -l "$NEO" | grep -oE 'net/neoforged/neoforge/event/level/[A-Za-z$]*\.class|net/neoforged/neoforge/event/level/block/[A-Za-z$]*\.class' | sort -u
for c in $(unzip -l "$NEO" | grep -oE 'net/neoforged/neoforge/event/level/(block/)?[A-Za-z]*(Grow|Fluid|Place)[A-Za-z$]*\.class' | sed 's/\.class$//; s#/#.#g' | sort -u); do p "$c"; done
p net.neoforged.neoforge.event.level.BlockEvent\$FluidPlaceBlockEvent
pf net.minecraft.world.phys.shapes.VoxelShape 'forAllBoxes|toAabbs|isEmpty|bounds'
pf net.minecraft.world.entity.EntityType 'FALLING_BLOCK'
pf net.minecraft.gametest.framework.GameTestHelper 'assertBlockPresent|assertBlockNotPresent|assertEntityPresent|getEntities|succeedWhen|runAfterDelay|setBlock|destroyBlock|onEachTick|succeedIf|runAtTickTime|assertionException|getBlockState|killAllEntities|absolutePos|getTick'
pf net.minecraft.sounds.SoundSource 'BLOCKS'
pf net.minecraft.world.level.block.LevelEvent 'PARTICLES_DESTROY_BLOCK|DRIPSTONE'
pf net.minecraft.world.entity.Entity 'discard|isRemoved|blockPosition|getDeltaMovement'
pf net.minecraft.world.level.block.Block 'getId|stateById|dropResources'
pf net.minecraft.world.level.block.FallingBlock 'isFree'
pf net.minecraft.commands.arguments.coordinates.BlockPosArgument 'blockPos|getLoadedBlockPos|getBlockPos'
