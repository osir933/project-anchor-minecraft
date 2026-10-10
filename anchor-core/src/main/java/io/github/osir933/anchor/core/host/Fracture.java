package io.github.osir933.anchor.core.host;

import io.github.osir933.anchor.core.space.GridPos;
import java.util.Objects;

/**
 * A built block that thermal stress cracked through in a step, and what the host should show now. Its joints with
 * every neighbour have cracked too, so it holds only by pressing and friction, and the structures around it wait to
 * be checked again.
 *
 * @param pos the block
 * @param hostBlock the id of the host block to show instead, from {@link BlockAppearance#fractured}, or {@code null}
 *     to go on showing the block as it is
 * @param temperatureK the block's temperature, in kelvin
 * @param load how far its most loaded part was stressed, as a fraction of the strength it was checked against
 * @param tension whether that part was pulled, as one colder than the rest of the block is; otherwise it was pressed
 */
public record Fracture(GridPos pos, String hostBlock, double temperatureK, double load, boolean tension) {

    /**
     * Validates the fracture.
     *
     * @param pos the block
     * @param hostBlock the block to show instead, or {@code null}
     * @param temperatureK the temperature
     * @param load the load
     * @param tension whether the most loaded part was pulled
     */
    public Fracture {
        Objects.requireNonNull(pos, "pos");
    }
}
