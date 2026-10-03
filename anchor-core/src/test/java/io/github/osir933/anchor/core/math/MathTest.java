package io.github.osir933.anchor.core.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MathTest {

    @Test
    void compensatedSumsKeepSmallTerms() {
        assertEquals(2.0, CompensatedSum.of(1e16, 1.0, 1.0, -1e16));
        double naive = 0;
        CompensatedSum sum = new CompensatedSum();
        for (int i = 0; i < 1_000_000; i++) {
            naive += 0.1;
            sum.add(0.1);
        }
        assertEquals(100_000.0, sum.value(), 1e-9);
        assertNotEquals(100_000.0, naive);
        assertEquals(0.0, sum.reset().value());
    }

    @Test
    void randomStreamsAreReproducibleAndKeyed() {
        DeterministicRandom a = DeterministicRandom.forKeys(42, 1, 2, 3);
        DeterministicRandom b = DeterministicRandom.forKeys(42, 1, 2, 3);
        DeterministicRandom c = DeterministicRandom.forKeys(42, 3, 2, 1);
        long first = a.nextLong();
        assertEquals(first, b.nextLong());
        assertNotEquals(first, c.nextLong());
    }

    @Test
    void randomDoublesAreUniformInTheUnitInterval() {
        DeterministicRandom r = new DeterministicRandom(7);
        int[] bins = new int[10];
        for (int i = 0; i < 100_000; i++) {
            double d = r.nextDouble();
            assertTrue(d >= 0 && d < 1);
            bins[(int) (d * 10)]++;
        }
        for (int count : bins) {
            assertEquals(10_000, count, 400);
        }
        for (int i = 0; i < 10_000; i++) {
            int n = r.nextInt(7);
            assertTrue(n >= 0 && n < 7);
        }
    }

    @Test
    void hashingMixesEveryKey() {
        assertNotEquals(Hashing.combine(1, 2, 3), Hashing.combine(1, 3, 2));
        assertNotEquals(Hashing.combine(1, 2), Hashing.combine(2, 2));
        assertEquals(Hashing.combine(9, 8, 7), Hashing.combine(9, 8, 7));
    }
}
