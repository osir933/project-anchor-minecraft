package io.github.osir933.anchor.core.model;

import io.github.osir933.anchor.core.math.DeterministicRandom;
import io.github.osir933.anchor.core.world.PhysicalWorld;
import java.util.Objects;
import java.util.SortedSet;

/**
 * Everything a model needs for one step.
 *
 * @param world the world to advance
 * @param dt how much simulated time this step covers, in seconds
 * @param random random numbers keyed by world seed, tick and model, identical on every machine
 * @param scope where each domain is simulated this tick
 */
public record StepContext(PhysicalWorld world, double dt, DeterministicRandom random, SimulationScope scope) {

    /**
     * Validates the context.
     *
     * @param world the world
     * @param dt the time step
     * @param random the random stream
     * @param scope the scope
     */
    public StepContext {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(random, "random");
        Objects.requireNonNull(scope, "scope");
        if (!(dt > 0) || !Double.isFinite(dt)) {
            throw new IllegalArgumentException("time step must be positive: " + dt);
        }
    }

    /**
     * Returns the sections where a domain is simulated.
     *
     * @param domain the domain
     * @return section keys in ascending order
     */
    public SortedSet<Long> sections(Domain domain) {
        return scope.sections(world, domain);
    }
}
