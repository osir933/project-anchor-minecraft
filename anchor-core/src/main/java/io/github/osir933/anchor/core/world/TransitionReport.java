package io.github.osir933.anchor.core.world;

import io.github.osir933.anchor.core.space.CellId;
import java.util.Objects;

/**
 * What happened when the world was asked to refine or coarsen a cell.
 *
 * @param kind whether cells were to be split or merged
 * @param cell the cell the request named
 * @param outcome whether the change was made
 * @param leafDelta the change in the number of leaves
 * @param reason why the request was refused or changed nothing; empty when applied
 */
public record TransitionReport(Kind kind, CellId cell, Outcome outcome, int leafDelta, String reason) {

    /** The two directions of a change of resolution. */
    public enum Kind {
        /** Splitting cells into smaller ones. */
        REFINE,
        /** Merging cells into a larger one. */
        COARSEN
    }

    /** Whether the change was made. */
    public enum Outcome {
        /** The change was made. */
        APPLIED,
        /** The world was already in the requested shape. */
        UNCHANGED,
        /** The change would have broken a rule, so it was not made. */
        REFUSED
    }

    /**
     * Validates the report.
     *
     * @param kind the kind
     * @param cell the cell
     * @param outcome the outcome
     * @param leafDelta the change in leaves
     * @param reason the reason
     */
    public TransitionReport {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(cell, "cell");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(reason, "reason");
    }

    static TransitionReport applied(Kind kind, CellId cell, int leafDelta) {
        return new TransitionReport(kind, cell, Outcome.APPLIED, leafDelta, "");
    }

    static TransitionReport unchanged(Kind kind, CellId cell, String reason) {
        return new TransitionReport(kind, cell, Outcome.UNCHANGED, 0, reason);
    }

    static TransitionReport refused(Kind kind, CellId cell, String reason) {
        return new TransitionReport(kind, cell, Outcome.REFUSED, 0, reason);
    }

    /**
     * Tells whether the change was made.
     *
     * @return {@code true} for {@link Outcome#APPLIED}
     */
    public boolean applied() {
        return outcome == Outcome.APPLIED;
    }
}
