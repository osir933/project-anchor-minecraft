package io.github.osir933.anchor.core.world;

import java.util.Objects;

/**
 * Something that happened to the world, kept for the inspector and the history view. Events describe
 * changes; they are not themselves part of the physical state.
 *
 * @param tick the tick at which it happened
 * @param kind what kind of change it was
 * @param subject what it happened to, such as a block or cell
 * @param detail a human-readable description
 */
public record WorldEvent(long tick, Kind kind, String subject, String detail) {

    /** The kinds of event. */
    public enum Kind {
        /** Matter placed, removed or replaced from outside the simulation. */
        EDIT,
        /** A section entered the simulated region, filled from what was known about it. */
        SECTION_ADDED,
        /** A section left the simulated region. */
        SECTION_REMOVED,
        /** Cells were subdivided. */
        REFINE,
        /** Cells were merged. */
        COARSEN,
        /** The world was rewound to a snapshot. */
        RESTORE,
        /** A conservation audit found an unexplained gain or loss. */
        CONSERVATION
    }

    /**
     * Validates the event.
     *
     * @param tick the tick
     * @param kind the kind
     * @param subject the subject
     * @param detail the description
     */
    public WorldEvent {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(detail, "detail");
    }
}
