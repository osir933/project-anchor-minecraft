package io.github.osir933.anchor.core.math;

/**
 * Small, fast, platform-independent hash functions for deterministic seeding and keys.
 */
public final class Hashing {

    /** The 64-bit golden-ratio increment used by SplitMix64. */
    public static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;

    private Hashing() {
    }

    /**
     * The SplitMix64 finaliser: a bijective mix of all 64 input bits.
     *
     * @param value the input
     * @return a well-mixed output; distinct inputs give distinct outputs
     */
    public static long mix64(long value) {
        long z = value;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /**
     * Combines a seed with a sequence of keys into one well-mixed value. The result depends on the order
     * of the keys.
     *
     * @param seed the starting value, such as a world seed
     * @param keys further values, such as an entity id and a tick number
     * @return the combined hash
     */
    public static long combine(long seed, long... keys) {
        long h = mix64(seed + GOLDEN_GAMMA);
        for (long key : keys) {
            h = mix64(h ^ mix64(key + GOLDEN_GAMMA));
        }
        return h;
    }
}
