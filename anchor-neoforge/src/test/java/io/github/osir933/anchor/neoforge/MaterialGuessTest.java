package io.github.osir933.anchor.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.osir933.anchor.core.matter.Phase;
import io.github.osir933.anchor.core.world.MaterialRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class MaterialGuessTest {

    private static String material(String path) {
        return MaterialGuess.fromName(path).orElseThrow(() -> new AssertionError("no guess for " + path))
                .material();
    }

    @Test
    void oresAreTheRockTheySitIn() {
        assertEquals("anchor:granite", material("iron_ore"));
        assertEquals("anchor:slate", material("deepslate_copper_ore"));
        assertEquals("anchor:netherrack", material("nether_gold_ore"));
        assertEquals("anchor:granite", material("tin_ore"), "modded ores too");
    }

    @Test
    void metalBlocksAreTheirMetal() {
        assertEquals("anchor:iron", material("iron_block"));
        assertEquals("anchor:copper", material("weathered_cut_copper_stairs"));
        assertEquals("anchor:gold", material("raw_gold_block"));
        assertEquals("anchor:aluminium", material("aluminum_block"));
        assertEquals("anchor:iron", material("chipped_anvil"));
    }

    @Test
    void specificRocksComeBeforeGeneralOnes() {
        assertEquals("anchor:sandstone", material("cut_red_sandstone"));
        assertEquals("anchor:sand", material("red_sand"));
        assertEquals("anchor:granite", material("mossy_stone_bricks"), "stone bricks are cut stone");
        assertEquals("anchor:brick", material("nether_bricks"));
        assertEquals("anchor:slate", material("deepslate_tiles"));
        assertEquals("anchor:basalt", material("polished_blackstone_bricks"));
        assertEquals("anchor:sand", material("lime_concrete_powder"));
        assertEquals("anchor:concrete", material("lime_concrete"));
        assertEquals("anchor:granite", material("cobblestone"));
        assertEquals("anchor:marble", material("calcite"));
    }

    @Test
    void woodIsHardUnlessItComesFromAConifer() {
        assertEquals("anchor:hardwood", material("oak_planks"));
        assertEquals("anchor:softwood", material("spruce_log"));
        assertEquals("anchor:softwood", material("crimson_hyphae"));
        assertEquals("anchor:hardwood", material("crafting_table"));
        assertEquals("anchor:softwood", MaterialGuess.wood("spruce_fence_gate").material());
    }

    @Test
    void iceAndSnowAreShownSolid() {
        MaterialGuess.Guess ice = MaterialGuess.fromName("ice_bricks").orElseThrow();
        assertEquals("anchor:water", ice.material());
        assertEquals(Phase.SOLID, ice.phase());
        assertEquals(Phase.SOLID, MaterialGuess.fromName("snow_bricks").orElseThrow().phase());
        assertEquals(null, MaterialGuess.fromName("dice_block").map(MaterialGuess.Guess::phase).orElse(null),
                "only whole words count");
    }

    @Test
    void namesThatSayNothingGiveNoGuess() {
        assertTrue(MaterialGuess.fromName("glowstone").isEmpty());
        assertTrue(MaterialGuess.fromName("redstone_block").isEmpty());
        assertTrue(MaterialGuess.fromName("sculk").isEmpty());
    }

    @Test
    void everyGuessIsAKnownMaterial() {
        MaterialRegistry registry = MaterialRegistry.withLibrary();
        for (String path : List.of("iron_ore", "copper_block", "gold_block", "aluminum_block", "diamond_block",
                "coal_block", "quartz_block", "obsidian", "basalt", "netherrack", "tuff", "deepslate", "calcite",
                "dripstone_block", "sandstone", "white_concrete", "bricks", "stone", "glass", "clay", "gravel",
                "sand", "dirt", "white_wool", "oak_leaves", "oak_log", "spruce_log", "snow_block", "packed_ice")) {
            String m = material(path);
            assertTrue(registry.indexOf(m) >= 0, path + " is guessed as " + m + ", which is not registered");
        }
    }
}
