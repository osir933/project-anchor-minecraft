package io.github.osir933.anchor.core.host;

import io.github.osir933.anchor.core.matter.Phase;
import io.github.osir933.anchor.core.physics.structure.Shape;
import io.github.osir933.anchor.core.physics.thermal.HeatSourceModel;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * What a kind of block in the host game is made of, as far as physics is concerned: a material, how much of
 * the block it fills, the phase the game shows it in, and anything the game does to it that the engine does
 * not model yet, such as a flame that keeps it hot.
 *
 * <p>A block shown in a phase keeps that look while any of its matter is still in that phase. Once all of it
 * has changed, {@link #becomes} names the block the host should show instead, so ice is replaced by water
 * only when it has melted completely, and water by ice only when it has frozen through.
 *
 * @param material the id of the material, such as {@code anchor:granite}
 * @param fill the fraction of the block's volume the material fills, more than 0 and at most 1; a slab fills
 *     half its block
 * @param phase the phase the host shows the material in, or {@code null} if the host shows it however it is
 * @param temperatureK the temperature the block has when it appears, such as lava's, or {@link Double#NaN}
 *     to take its surroundings' temperature
 * @param source a heat source standing in for a process the engine does not model, such as burning, or
 *     {@code null}
 * @param becomes for each phase the matter can end up in, the id of the host block to show instead; ids the
 *     engine passes back to the host without interpreting them
 * @param albedo the fraction of sunlight the block's top reflects, for a block whose colour its material does not
 *     say, such as dyed wool; {@link Double#NaN} to take its material's. It applies while the block holds its
 *     material in the phase it is shown in.
 * @param shape the solid part of the block, which decides where it touches its neighbours and so how loads pass
 *     between them; a block given none fills its cube from the bottom up to its fill
 * @param frame the id of the material that carries loads in a block whose matter counts as the air or water
 *     around it, as a fence's wood does, its mass that of its shape; {@code null} for a block whose own matter
 *     carries its loads
 * @param immovable whether the block holds still as the ground does wherever it is put, as blocks the game makes
 *     unbreakable do, so that it is never {@linkplain HostedWorld#isBuilt built} and built blocks can hang from it
 */
public record BlockAppearance(String material, double fill, Phase phase, double temperatureK,
        HeatSourceModel.Source source, Map<Phase, String> becomes, double albedo, Shape shape, String frame,
        boolean immovable) {

    /**
     * Validates the appearance and takes an unmodifiable copy of {@code becomes}.
     *
     * @param material the material id
     * @param fill the filled fraction
     * @param phase the shown phase, or {@code null}
     * @param temperatureK the starting temperature, or NaN
     * @param source the heat source, or {@code null}
     * @param becomes the replacement blocks per phase
     * @param albedo the albedo of the block's top, or NaN
     * @param shape the solid part of the block, or {@code null} to fill it from the bottom up to its fill
     * @param frame the material carrying loads in a block whose matter counts as its surroundings, or {@code null}
     * @param immovable whether the block holds still wherever it is put
     */
    public BlockAppearance {
        Objects.requireNonNull(material, "material");
        if (material.isBlank()) {
            throw new IllegalArgumentException("the material id is blank");
        }
        if (!(fill > 0 && fill <= 1)) {
            throw new IllegalArgumentException("fill must be more than 0 and at most 1: " + fill);
        }
        if (!Double.isNaN(temperatureK) && !(temperatureK > 0 && Double.isFinite(temperatureK))) {
            throw new IllegalArgumentException("temperature must be positive or NaN: " + temperatureK);
        }
        if (!Double.isNaN(albedo) && !(albedo >= 0 && albedo <= 1)) {
            throw new IllegalArgumentException("albedo must lie between 0 and 1, or be NaN: " + albedo);
        }
        EnumMap<Phase, String> copy = new EnumMap<>(Phase.class);
        for (Map.Entry<Phase, String> e : Objects.requireNonNull(becomes, "becomes").entrySet()) {
            Phase key = Objects.requireNonNull(e.getKey(), "phase in becomes");
            String block = Objects.requireNonNull(e.getValue(), "block in becomes");
            if (block.isBlank()) {
                throw new IllegalArgumentException("the block shown as " + key + " is blank");
            }
            if (key == phase) {
                throw new IllegalArgumentException("a block shown as " + key + " cannot become another block as "
                        + key);
            }
            copy.put(key, block);
        }
        becomes = Collections.unmodifiableMap(copy);
        if (shape == null) {
            shape = Shape.bottom(fill);
        }
        if (frame != null && frame.isBlank()) {
            throw new IllegalArgumentException("the frame's material id is blank");
        }
    }

    /**
     * Creates an appearance whose colour its material says.
     *
     * @param material the material id
     * @param fill the filled fraction
     * @param phase the shown phase, or {@code null}
     * @param temperatureK the starting temperature, or NaN
     * @param source the heat source, or {@code null}
     * @param becomes the replacement blocks per phase
     */
    public BlockAppearance(String material, double fill, Phase phase, double temperatureK,
            HeatSourceModel.Source source, Map<Phase, String> becomes) {
        this(material, fill, phase, temperatureK, source, becomes, Double.NaN, null, null, false);
    }

    /**
     * Returns a whole block of a material, shown however it is, at its surroundings' temperature.
     *
     * @param material the material id
     * @return the appearance
     */
    public static BlockAppearance of(String material) {
        return new BlockAppearance(material, 1.0, null, Double.NaN, null, new EnumMap<>(Phase.class));
    }

    /**
     * Returns this appearance filling a different fraction of the block.
     *
     * @param newFill the filled fraction
     * @return the new appearance
     */
    public BlockAppearance withFill(double newFill) {
        Shape kept = shape.equals(Shape.bottom(fill)) ? null : shape;
        return new BlockAppearance(material, newFill, phase, temperatureK, source, becomes,
                albedo, kept, frame, immovable);
    }

    /**
     * Returns this appearance shown in a phase.
     *
     * @param newPhase the phase, or {@code null}
     * @return the new appearance
     */
    public BlockAppearance shownAs(Phase newPhase) {
        return new BlockAppearance(material, fill, newPhase, temperatureK, source, becomes,
                albedo, shape, frame, immovable);
    }

    /**
     * Returns this appearance starting at a temperature.
     *
     * @param newTemperatureK the temperature, or NaN for the surroundings'
     * @return the new appearance
     */
    public BlockAppearance startingAt(double newTemperatureK) {
        return new BlockAppearance(material, fill, phase, newTemperatureK, source, becomes,
                albedo, shape, frame, immovable);
    }

    /**
     * Returns this appearance with a heat source.
     *
     * @param newSource the source, or {@code null} for none
     * @return the new appearance
     */
    public BlockAppearance heatedBy(HeatSourceModel.Source newSource) {
        return new BlockAppearance(material, fill, phase, temperatureK, newSource, becomes,
                albedo, shape, frame, immovable);
    }

    /**
     * Returns this appearance with a replacement block for one phase.
     *
     * @param newPhase the phase the matter ends up in
     * @param hostBlock the host block to show then
     * @return the new appearance
     */
    public BlockAppearance becoming(Phase newPhase, String hostBlock) {
        EnumMap<Phase, String> map = new EnumMap<>(Phase.class);
        map.putAll(becomes);
        map.put(newPhase, hostBlock);
        return new BlockAppearance(material, fill, phase, temperatureK, source, map, albedo, shape, frame, immovable);
    }

    /**
     * Returns this appearance without replacement blocks, so the host never changes it.
     *
     * @return the new appearance
     */
    public BlockAppearance withoutReplacements() {
        return new BlockAppearance(material, fill, phase, temperatureK, source, new EnumMap<>(Phase.class), albedo,
                shape, frame, immovable);
    }

    /**
     * Returns this appearance with the albedo of its top given, as for a block dyed a colour its material does not
     * say.
     *
     * @param newAlbedo the fraction of sunlight the top reflects, from 0 to 1, or NaN for the material's
     * @return the new appearance
     */
    public BlockAppearance withAlbedo(double newAlbedo) {
        return new BlockAppearance(material, fill, phase, temperatureK, source, becomes,
                newAlbedo, shape, frame, immovable);
    }

    /**
     * Returns this appearance with the solid part of the block given.
     *
     * @param newShape the shape, or {@code null} to fill the block from the bottom up to its fill
     * @return the new appearance
     */
    public BlockAppearance withShape(Shape newShape) {
        return new BlockAppearance(material, fill, phase, temperatureK, source, becomes,
                albedo, newShape, frame, immovable);
    }

    /**
     * Returns this appearance with a material carrying its loads, for a block whose matter counts as the air or
     * water around it, as a fence's or a pane's does.
     *
     * @param newFrame the material's id, or {@code null} for a block whose own matter carries its loads
     * @return the new appearance
     */
    public BlockAppearance framedIn(String newFrame) {
        return new BlockAppearance(material, fill, phase, temperatureK, source, becomes,
                albedo, shape, newFrame, immovable);
    }

    /**
     * Returns this appearance holding still wherever it is put, as the ground does, for a block the game makes
     * unbreakable.
     *
     * @return the new appearance
     */
    public BlockAppearance asImmovable() {
        return new BlockAppearance(material, fill, phase, temperatureK, source, becomes, albedo, shape, frame, true);
    }

    /**
     * Tells whether the host may need to show this block differently when its matter changes phase.
     *
     * @return {@code true} if the block is shown in a phase and has a replacement for some other phase
     */
    public boolean presentable() {
        return phase != null && !becomes.isEmpty();
    }
}
