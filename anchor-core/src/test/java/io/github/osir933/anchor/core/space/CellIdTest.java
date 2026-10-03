package io.github.osir933.anchor.core.space;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CellIdTest {

    private static final GridPos BLOCK = new GridPos(3, -7, 12);

    @Test
    void childrenAndParentsRoundTrip() {
        CellId root = CellId.of(BLOCK);
        for (int octant = 0; octant < 8; octant++) {
            CellId child = root.child(octant);
            assertEquals(1, child.level());
            assertEquals(octant, child.octant());
            assertEquals(root, child.parent());
            assertTrue(root.contains(child));
            assertFalse(child.contains(root));
        }
    }

    @Test
    void sizesHalveEachLevel() {
        assertEquals(1.0, CellId.edgeLength(0));
        assertEquals(1.0 / 16, CellId.edgeLength(4));
        assertEquals(1.0 / 1024, CellId.edgeLength(CellId.MAX_LEVEL));
        assertEquals(1.0 / 512, new CellId(BLOCK, 3, 0, 0, 0).volume());
    }

    @Test
    void positionsAreInWorldMetres() {
        CellId cell = new CellId(BLOCK, 2, 1, 2, 3);
        assertEquals(3.25, cell.minX());
        assertEquals(-6.5, cell.minY());
        assertEquals(12.75, cell.minZ());
    }

    @Test
    void orderIsDepthFirstByOctant() {
        List<CellId> preOrder = new ArrayList<>();
        walk(CellId.of(BLOCK), 3, preOrder);
        List<CellId> sorted = new ArrayList<>(preOrder);
        Collections.shuffle(sorted, new Random(7));
        sorted.sort(null);
        assertEquals(preOrder, sorted);
    }

    private static void walk(CellId cell, int depth, List<CellId> out) {
        out.add(cell);
        if (cell.level() < depth) {
            for (int octant = 0; octant < 8; octant++) {
                walk(cell.child(octant), depth, out);
            }
        }
    }

    @Test
    void blocksComeBeforeTheirCellsAndAfterEarlierBlocks() {
        CellId a = new CellId(BLOCK, 5, 31, 31, 31);
        CellId b = CellId.of(BLOCK.offset(1, 0, 0));
        assertTrue(a.compareTo(b) < 0);
        assertTrue(CellId.of(BLOCK).compareTo(a) < 0);
    }

    @Test
    void invalidCellsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CellId(BLOCK, CellId.MAX_LEVEL + 1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CellId(BLOCK, 2, 4, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CellId(BLOCK, 1, -1, 0, 0));
        assertThrows(IllegalStateException.class, () -> CellId.of(BLOCK).parent());
        assertThrows(IllegalArgumentException.class, () -> CellId.of(BLOCK).child(8));
    }
}
