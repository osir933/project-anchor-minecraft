package io.github.osir933.anchor.core.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.osir933.anchor.core.space.CellId;
import io.github.osir933.anchor.core.space.GridPos;
import org.junit.jupiter.api.Test;

class RegionTest {

    @Test
    void boxesNormaliseTheirCorners() {
        Region.Box box = new Region.Box(new GridPos(5, 1, -2), new GridPos(-1, 3, -4));
        assertEquals(new GridPos(-1, 1, -4), box.min());
        assertEquals(new GridPos(5, 3, -2), box.max());
        assertEquals(7L * 3 * 3, box.blockCount());
    }

    @Test
    void boxesIntersectCellsByVolumeNotByTouch() {
        Region.Box box = Region.Box.of(new GridPos(0, 0, 0));
        assertTrue(box.intersects(CellId.of(new GridPos(0, 0, 0))));
        assertTrue(box.intersects(new CellId(new GridPos(0, 0, 0), 3, 7, 7, 7)));
        assertFalse(box.intersects(CellId.of(new GridPos(1, 0, 0))), "sharing a face is not overlapping");
    }

    @Test
    void spheresMeasureDistanceToTheNearestPointOfACell() {
        Region.Sphere sphere = new Region.Sphere(0.5, 0.5, 0.5, 0.6);
        assertTrue(sphere.intersects(CellId.of(new GridPos(1, 0, 0))));
        assertFalse(sphere.intersects(CellId.of(new GridPos(1, 1, 1))));
        assertEquals(new Region.Box(new GridPos(-1, -1, -1), new GridPos(1, 1, 1)), sphere.bounds());
        assertThrows(IllegalArgumentException.class, () -> new Region.Sphere(0, 0, 0, 0));
    }

    @Test
    void timeWindowsAreHalfOpen() {
        TimeWindow w = TimeWindow.during(10, 5);
        assertFalse(w.contains(9));
        assertTrue(w.contains(10));
        assertTrue(w.contains(14));
        assertFalse(w.contains(15));
        assertTrue(TimeWindow.from(3).contains(Long.MAX_VALUE - 1));
        assertThrows(IllegalArgumentException.class, () -> new TimeWindow(5, 4));
    }
}
