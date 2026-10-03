package io.github.osir933.anchor.core.world;

import java.util.Objects;

/**
 * The fixed parameters of a world.
 *
 * @param seed the world seed; every random choice the engine makes is keyed from it
 * @param ambientMaterial the id of the material that fills space nobody has described, or
 *     {@link MaterialRegistry#VACUUM_ID}
 * @param ambientTemperatureK the temperature of that ambient matter, in kelvin
 * @param maxLeaves the most refined cells the world may hold at once, across all blocks
 */
public record WorldSettings(long seed, String ambientMaterial, double ambientTemperatureK, int maxLeaves) {

    /** About a million leaves: roughly 250 blocks refined to Minecraft-pixel size. */
    public static final int DEFAULT_MAX_LEAVES = 1 << 20;

    /**
     * Validates the settings.
     *
     * @param seed the seed
     * @param ambientMaterial the ambient material id
     * @param ambientTemperatureK the ambient temperature
     * @param maxLeaves the leaf budget
     */
    public WorldSettings {
        Objects.requireNonNull(ambientMaterial, "ambientMaterial");
        if (!(ambientTemperatureK > 0) || !Double.isFinite(ambientTemperatureK)) {
            throw new IllegalArgumentException("ambient temperature must be positive: " + ambientTemperatureK);
        }
        if (maxLeaves < 0) {
            throw new IllegalArgumentException("leaf budget must not be negative: " + maxLeaves);
        }
    }

    /**
     * Returns settings for a world filled with air at 20 °C.
     *
     * @param seed the world seed
     * @return the settings
     */
    public static WorldSettings airAt20C(long seed) {
        return new WorldSettings(seed, "anchor:air", 293.15, DEFAULT_MAX_LEAVES);
    }

    /**
     * Returns settings for an empty world: vacuum wherever nothing was placed.
     *
     * @param seed the world seed
     * @return the settings
     */
    public static WorldSettings vacuum(long seed) {
        return new WorldSettings(seed, MaterialRegistry.VACUUM_ID, 2.725, DEFAULT_MAX_LEAVES);
    }
}
