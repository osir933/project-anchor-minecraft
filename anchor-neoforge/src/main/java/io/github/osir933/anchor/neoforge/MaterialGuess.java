package io.github.osir933.anchor.neoforge;

import io.github.osir933.anchor.core.matter.Phase;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Guesses what a block is made of from the words in its registry name, for blocks that neither Anchor's
 * material data map nor its built-in rules describe. Most vanilla and modded blocks say what they are made of:
 * {@code deepslate_iron_ore} is rock, {@code spruce_planks} is wood and {@code aluminum_block} is metal.
 *
 * <p>Rules are tried in order and the first whose words appear in the name wins, so more specific materials
 * come before general ones: {@code sandstone} before {@code sand}, and ores before the metals they contain.
 */
final class MaterialGuess {

    /**
     * A guessed material.
     *
     * @param material the material id
     * @param phase the phase the block is shown in, or {@code null} if it looks the same in any phase
     */
    record Guess(String material, Phase phase) {
    }

    /** A material and the words that suggest it. */
    private record Rule(Guess guess, Set<String> words) {

        Rule(String material, String... words) {
            this(new Guess(material, null), Set.of(words));
        }

        boolean matches(List<String> name) {
            for (String word : name) {
                if (words.contains(word)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final Guess GRANITE = new Guess("anchor:granite", null);
    private static final Guess SLATE = new Guess("anchor:slate", null);
    private static final Guess NETHERRACK = new Guess("anchor:netherrack", null);
    private static final Guess SAND = new Guess("anchor:sand", null);
    private static final Guess HARDWOOD = new Guess("anchor:hardwood", null);
    private static final Guess SOFTWOOD = new Guess("anchor:softwood", null);

    private static final Set<String> SOFTWOODS = Set.of("spruce", "pine", "fir", "crimson", "warped", "bamboo");

    private static final Set<String> WOODEN = Set.of("planks", "log", "logs", "wood", "stem", "hyphae", "bamboo",
            "chest", "barrel", "bookshelf", "lectern", "composter", "beehive", "crafting", "table", "sign");

    private static final List<Rule> RULES = List.of(
            new Rule("anchor:copper", "copper"),
            new Rule("anchor:iron", "iron", "steel", "anvil", "netherite", "cauldron", "hopper"),
            new Rule("anchor:gold", "gold", "golden"),
            new Rule("anchor:aluminium", "aluminum", "aluminium"),
            new Rule("anchor:diamond", "diamond"),
            new Rule("anchor:coal", "coal"),
            new Rule("anchor:quartzite", "quartz", "quartzite", "amethyst"),
            new Rule("anchor:obsidian", "obsidian"),
            new Rule("anchor:basalt", "basalt", "blackstone", "magma"),
            new Rule("anchor:netherrack", "netherrack", "nylium"),
            new Rule("anchor:tuff", "tuff"),
            new Rule("anchor:slate", "deepslate", "slate"),
            new Rule("anchor:marble", "calcite", "marble"),
            new Rule("anchor:limestone", "dripstone", "limestone", "bone"),
            new Rule("anchor:sandstone", "sandstone"),
            new Rule("anchor:concrete", "concrete"),
            new Rule("anchor:brick", "brick", "bricks", "terracotta"),
            new Rule("anchor:granite", "granite", "diorite", "andesite", "stone", "cobblestone", "cobbled",
                    "bedrock"),
            new Rule("anchor:glass", "glass"),
            new Rule("anchor:clay", "clay"),
            new Rule("anchor:gravel", "gravel"),
            new Rule("anchor:sand", "sand"),
            new Rule("anchor:soil", "dirt", "grass", "podzol", "mycelium", "farmland", "path", "mud", "soil",
                    "rooted"),
            new Rule("anchor:wool", "wool", "carpet", "bed"),
            new Rule("anchor:foliage", "leaves", "hay", "moss", "vine", "vines", "wart", "mushroom", "kelp",
                    "melon", "pumpkin", "cactus", "sponge", "shroomlight"));

    private MaterialGuess() {
    }

    /**
     * Guesses a block's material from the path of its registry name.
     *
     * @param path the path, such as {@code deepslate_iron_ore}
     * @return the guess, or empty if no word in the name suggests a material
     */
    static Optional<Guess> fromName(String path) {
        List<String> words = List.of(path.split("_"));
        if (words.contains("ore")) {
            // An ore is mostly the rock it sits in.
            if (words.contains("deepslate")) {
                return Optional.of(SLATE);
            }
            return Optional.of(words.contains("nether") ? NETHERRACK : GRANITE);
        }
        if (words.contains("concrete") && words.contains("powder")) {
            return Optional.of(SAND);
        }
        if (words.contains("stone") && (words.contains("brick") || words.contains("bricks"))) {
            return Optional.of(GRANITE);
        }
        if (words.contains("snow")) {
            return Optional.of(new Guess("anchor:snow", Phase.SOLID));
        }
        if (words.contains("ice")) {
            return Optional.of(new Guess("anchor:water", Phase.SOLID));
        }
        for (Rule rule : RULES) {
            if (rule.matches(words)) {
                return Optional.of(rule.guess());
            }
        }
        for (String word : words) {
            if (WOODEN.contains(word)) {
                return Optional.of(isSoftwood(words) ? SOFTWOOD : HARDWOOD);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the wood a wooden block is most likely made of.
     *
     * @param path the path of the block's registry name
     * @return softwood for conifers, nether fungi and bamboo, hardwood otherwise
     */
    static Guess wood(String path) {
        return isSoftwood(List.of(path.split("_"))) ? SOFTWOOD : HARDWOOD;
    }

    private static boolean isSoftwood(List<String> words) {
        for (String word : words) {
            if (SOFTWOODS.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
