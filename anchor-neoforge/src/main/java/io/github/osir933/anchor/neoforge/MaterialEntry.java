package io.github.osir933.anchor.neoforge;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.osir933.anchor.core.matter.Phase;
import io.github.osir933.anchor.core.physics.thermal.HeatSourceModel;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * What a block is made of, as Anchor's {@code anchor:materials} data map says. Data packs use it to describe
 * blocks Anchor would otherwise guess at, or to change its built-in choices:
 *
 * <pre>{@code
 * {
 *   "values": {
 *     "minecraft:torch": { "material": "anchor:air", "source": { "temperature": 1300, "power": 1500 } },
 *     "minecraft:water": {
 *       "material": "anchor:water",
 *       "phase": "liquid",
 *       "becomes": { "solid": "minecraft:ice", "gas": "minecraft:air" }
 *     },
 *     "minecraft:smooth_stone": { "material": "anchor:granite", "fractured": "minecraft:cobblestone" }
 *   }
 * }
 * }</pre>
 *
 * @param material the id of the Anchor material the block is made of
 * @param fill the fraction of the block's space the material fills; left out, it follows from the block's
 *     shape, its snow layers or its fluid level
 * @param phase the phase the block shows its material in, such as liquid for water; matter in it starts in
 *     that phase, and with {@code becomes} the block is replaced when the phase changes
 * @param temperature the temperature the block starts at in kelvin, instead of its surroundings', as for lava
 * @param source a heat source that holds the block at a temperature with up to a given power in watts; blocks
 *     with a {@code lit} property only heat while lit
 * @param becomes the block to show instead once the material has melted, frozen or boiled into another phase
 * @param fractured the block to show instead once thermal stress has cracked a built block through, such as
 *     {@code minecraft:air} for glass that shatters; left out, Anchor picks a cracked form of the block if it knows
 *     one, and otherwise leaves it as it is
 */
record MaterialEntry(String material, Optional<Double> fill, Optional<Phase> phase, Optional<Double> temperature,
        Optional<HeatSourceModel.Source> source, Map<Phase, String> becomes, Optional<String> fractured) {

    /** Reads a phase from its lower-case name. */
    static final Codec<Phase> PHASE_CODEC = Codec.STRING.comapFlatMap(MaterialEntry::phaseNamed,
            phase -> phase.name().toLowerCase(Locale.ROOT));

    /** Reads a heat source. */
    static final Codec<HeatSourceModel.Source> SOURCE_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.doubleRange(1.0, 100_000.0).fieldOf("temperature").forGetter(HeatSourceModel.Source::temperatureK),
            Codec.doubleRange(0.0, 1e9).fieldOf("power").forGetter(HeatSourceModel.Source::powerW))
            .apply(i, HeatSourceModel.Source::new));

    /** Reads an entry. */
    static final Codec<MaterialEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("material").forGetter(MaterialEntry::material),
            Codec.doubleRange(0.0, 1.0).optionalFieldOf("fill").forGetter(MaterialEntry::fill),
            PHASE_CODEC.optionalFieldOf("phase").forGetter(MaterialEntry::phase),
            Codec.doubleRange(1.0, 100_000.0).optionalFieldOf("temperature").forGetter(MaterialEntry::temperature),
            SOURCE_CODEC.optionalFieldOf("source").forGetter(MaterialEntry::source),
            Codec.unboundedMap(PHASE_CODEC, Codec.STRING).optionalFieldOf("becomes", Map.of())
                    .forGetter(MaterialEntry::becomes),
            Codec.STRING.optionalFieldOf("fractured").forGetter(MaterialEntry::fractured))
            .apply(i, MaterialEntry::new));

    /**
     * Copies the replacements.
     *
     * @param material the material
     * @param fill the fill
     * @param phase the shown phase
     * @param temperature the starting temperature
     * @param source the heat source
     * @param becomes the replacements
     * @param fractured the block to show once cracked through
     */
    MaterialEntry {
        becomes = Map.copyOf(becomes);
    }

    private static DataResult<Phase> phaseNamed(String name) {
        for (Phase p : Phase.values()) {
            if (p.name().equalsIgnoreCase(name)) {
                return DataResult.success(p);
            }
        }
        return DataResult.error(() -> "unknown phase '" + name + "'; use solid, liquid or gas");
    }
}
