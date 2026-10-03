package io.github.osir933.anchor.core.model;

import io.github.osir933.anchor.core.math.DeterministicRandom;
import io.github.osir933.anchor.core.world.PhysicalWorld;
import java.util.Objects;

/**
 * Everything a model needs for one step.
 *
 * @param world the world to advance
 * @param dt how much simulated time this step covers, in seconds
 * @param random random numbers keyed by world seed, tick and model, identical on every machine
 */
public record StepContext(PhysicalWorld world, double dt, DeterministicRandom random) {

    /**
     * Validates the context.
     *
     * @param world the world
     * @param dt the time step
     * @param random the random stream
     */
    public StepContext {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(random, "random");
        if (!(dt > 0) || !Double.isFinite(dt)) {
            throw new IllegalArgumentException("time step must be positive: " + dt);
        }
    }
}
