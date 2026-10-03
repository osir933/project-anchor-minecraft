package io.github.osir933.anchor.core.math;

/**
 * A reproducible pseudo-random number generator (SplitMix64).
 *
 * <p>Anchor never shares one generator between threads or between unrelated processes. Instead each
 * random decision derives its own stream from stable keys with {@link #forKeys(long, long...)}, so results
 * do not depend on thread scheduling or on how many other random numbers were drawn elsewhere.
 */
public final class DeterministicRandom {

    private long state;

    /**
     * Creates a generator with the given seed.
     *
     * @param seed the seed; equal seeds give equal sequences
     */
    public DeterministicRandom(long seed) {
        this.state = seed;
    }

    /**
     * Creates an independent stream for a decision identified by stable keys.
     *
     * @param seed the world or experiment seed
     * @param keys identifiers of the decision, such as an entity id, a tick and a purpose code
     * @return a fresh generator for that decision
     */
    public static DeterministicRandom forKeys(long seed, long... keys) {
        return new DeterministicRandom(Hashing.combine(seed, keys));
    }

    /**
     * Returns the next 64 random bits.
     *
     * @return a uniformly distributed long
     */
    public long nextLong() {
        state += Hashing.GOLDEN_GAMMA;
        return Hashing.mix64(state);
    }

    /**
     * Returns a uniformly distributed double in [0, 1) with 53 random bits.
     *
     * @return the next double
     */
    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    /**
     * Returns a uniformly distributed integer in [0, bound) without modulo bias (Lemire's method).
     *
     * @param bound the exclusive upper bound, positive
     * @return the next integer
     */
    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive: " + bound);
        }
        long random = nextLong() >>> 32;
        long product = random * bound;
        long low = product & 0xFFFFFFFFL;
        if (low < bound) {
            long threshold = (0x1_0000_0000L - bound) % bound;
            while (low < threshold) {
                random = nextLong() >>> 32;
                product = random * bound;
                low = product & 0xFFFFFFFFL;
            }
        }
        return (int) (product >>> 32);
    }

    /**
     * Returns the current internal state, for saving.
     *
     * @return the state
     */
    public long state() {
        return state;
    }
}
