package io.github.osir933.anchor.core.world;

/**
 * Where a cell's current state came from. The inspector shows this so that players and researchers can
 * tell a simulated result from an assumption.
 */
public enum Provenance {
    /** Set directly: world generation, a placed block, or an experiment's initial conditions. */
    INITIAL,
    /** Filled in with the world's ambient default because nothing more specific was known. */
    AMBIENT,
    /** Produced by a physics model stepping the cell forward. */
    SIMULATED,
    /**
     * Inferred during refinement because the coarser state held no detail at this scale; the parent is
     * assumed to have been uniform inside.
     */
    RECONSTRUCTED,
    /** Aggregated from finer cells that were uniform enough to merge without changing any outcome. */
    COARSENED
}
