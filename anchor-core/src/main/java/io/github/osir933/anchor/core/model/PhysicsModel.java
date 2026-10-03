package io.github.osir933.anchor.core.model;

import io.github.osir933.anchor.core.world.PhysicalWorld;
import java.util.List;

/**
 * A physical law, or an approximation of one, that advances the world in time.
 *
 * <p>Models move matter and energy around with {@link PhysicalWorld#writeLeaf}; they never declare
 * internal transfers, so the conservation audit after each step checks them. Anything a model sends across
 * the world's boundary, such as heat radiated to the sky, it declares with
 * {@link PhysicalWorld#recordExchange}.
 *
 * <p>A model must be deterministic: its result may depend only on the world, the time step and the random
 * stream it is given, and it must visit cells in the engine's order.
 */
public interface PhysicsModel {

    /**
     * Returns the model's namespaced id, such as {@code anchor:conduction}.
     *
     * @return the id
     */
    String id();

    /**
     * Returns the branch of physics the model belongs to.
     *
     * @return the domain
     */
    Domain domain();

    /**
     * Describes the model's assumptions and limits in a sentence or two, for the inspector.
     *
     * @return the description
     */
    String assumptions();

    /**
     * Returns where in the tick the model runs; lower runs first, ties are broken by id.
     *
     * @return the priority, zero by default
     */
    default int priority() {
        return 0;
    }

    /**
     * Estimates the work a step would take, in abstract units. The scheduler budgets with these estimates,
     * never with measured time, so a slow computer runs the same simulation more slowly rather than a
     * different one. The estimate must depend only on the context.
     *
     * @param context the world, time step and scope the step would use
     * @return the estimated cost, at least zero
     */
    long estimateCost(StepContext context);

    /**
     * Advances the world.
     *
     * @param context the world, time step and random stream
     */
    void step(StepContext context);

    /**
     * Reports where the model is being used outside its assumptions.
     *
     * @param world the world after the step
     * @return the issues, empty when the model is valid everywhere it ran
     */
    default List<ValidityIssue> checkValidity(PhysicalWorld world) {
        return List.of();
    }
}
