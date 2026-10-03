package io.github.osir933.anchor.core.matter;

/**
 * A state of aggregation. Solid allotropes such as alpha and gamma iron share {@link #SOLID} and are told
 * apart by the structure name of their {@link PhaseRegion}.
 */
public enum Phase {
    /** Rigid matter with a fixed shape. */
    SOLID,
    /** Matter that flows but keeps its volume. */
    LIQUID,
    /** Matter that fills its container. */
    GAS
}
