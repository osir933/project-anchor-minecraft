package io.github.osir933.anchor.core.world;

import io.github.osir933.anchor.core.space.CellId;

/** Receives cells one at a time while the world walks its leaves. */
@FunctionalInterface
public interface LeafVisitor {

    /**
     * Visits one leaf.
     *
     * @param cell the leaf
     * @param state its state; a view that is only valid during this call, so use {@link CellState#copy()}
     *     to keep it
     */
    void visit(CellId cell, CellState state);
}
