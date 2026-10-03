package io.github.osir933.anchor.core.space;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SectionPosTest {

    @ParameterizedTest
    @CsvSource({"0, 0, 0", "1, 2, 3", "-1, -1, -1", "1875000, 2047, -1875000", "-2097152, -524288, 2097151"})
    void packingRoundTrips(int x, int y, int z) {
        long key = SectionPos.pack(x, y, z);
        assertEquals(x, SectionPos.x(key));
        assertEquals(y, SectionPos.y(key));
        assertEquals(z, SectionPos.z(key));
    }

    @Test
    void packingMatchesMinecraft() {
        // net.minecraft.core.SectionPos.asLong(1, 2, 3)
        assertEquals((1L << 42) | (3L << 20) | 2L, SectionPos.pack(1, 2, 3));
    }

    @Test
    void blockPositionsRoundTripThroughSections() {
        for (GridPos pos : new GridPos[] {new GridPos(0, 0, 0), new GridPos(-1, -64, -1),
                new GridPos(17, 319, -33), new GridPos(-29999984, 100, 29999984)}) {
            assertEquals(pos, GridPos.of(pos.sectionKey(), pos.indexInSection()));
        }
    }

    @Test
    void localIndicesRunXThenZThenY() {
        assertEquals(1, SectionPos.localIndex(1, 0, 0));
        assertEquals(16, SectionPos.localIndex(0, 0, 1));
        assertEquals(256, SectionPos.localIndex(0, 1, 0));
        assertEquals(4095, SectionPos.localIndex(15, 15, 15));
    }
}
