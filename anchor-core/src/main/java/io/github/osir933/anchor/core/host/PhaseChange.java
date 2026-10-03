package io.github.osir933.anchor.core.host;

import io.github.osir933.anchor.core.matter.Phase;
import io.github.osir933.anchor.core.space.GridPos;
import java.util.Objects;

/**
 * A block whose matter no longer holds any of the phase the host shows it in, and what the host should show
 * instead. After placing {@code hostBlock}, the host reports it through {@link HostedWorld#reconcile} with
 * {@code temperatureK} as the hint, so matter that leaves with the old block, such as steam, leaves at the
 * temperature it had.
 *
 * @param pos the block
 * @param shown the phase the host shows
 * @param now the phase most of the matter is in now
 * @param hostBlock the id of the host block to show, from {@link BlockAppearance#becomes}
 * @param temperatureK the block's temperature in kelvin
 */
public record PhaseChange(GridPos pos, Phase shown, Phase now, String hostBlock, double temperatureK) {

    /**
     * Validates the change.
     *
     * @param pos the block
     * @param shown the shown phase
     * @param now the current phase
     * @param hostBlock the replacement block
     * @param temperatureK the temperature
     */
    public PhaseChange {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(shown, "shown");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(hostBlock, "hostBlock");
    }
}
