package io.github.osir933.anchor.core.instrument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.osir933.anchor.core.space.GridPos;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProbeSetTest {

    private static final GridPos BLOCK = new GridPos(10, 64, -3);

    @Test
    void probesWithoutANameAreNumbered() {
        ProbeSet set = new ProbeSet();
        ProbeSet.Probe first = set.add(null, BLOCK, 10.5, 64.5, -2.5);
        ProbeSet.Probe named = set.add("Furnace", BLOCK.offset(1, 0, 0), 11.5, 64.5, -2.5);
        ProbeSet.Probe third = set.add(null, BLOCK.offset(2, 0, 0), 12.5, 64.5, -2.5);
        assertEquals("p1", first.name());
        assertEquals(1, first.number());
        assertEquals("furnace", named.name(), "names are in lower case");
        assertEquals(2, named.number());
        assertEquals("p3", third.name(), "numbering skips the numbers named probes took");
        assertEquals(3, third.number());
        assertEquals(List.of(first, named, third), set.probes());
        assertEquals(named, set.get("FURNACE").orElseThrow());
        assertEquals(third, set.byNumber(3).orElseThrow());
        assertEquals(first, set.in(BLOCK).orElseThrow());
        assertTrue(set.in(BLOCK.offset(0, 1, 0)).isEmpty());
        set.add("p4", BLOCK, 10.5, 64.5, -2.5);
        assertEquals("p5", set.nextName(), "a name someone chose is not handed out again");
        set.remove("p1");
        assertEquals("p5", set.nextName(), "nor is the name of a removed probe");
    }

    @Test
    void namesAreLettersDigitsAndDashes() {
        assertTrue(ProbeSet.validName("north-wall_2"));
        assertTrue(ProbeSet.validName("ABC"), "capitals become small letters");
        assertFalse(ProbeSet.validName(""));
        assertFalse(ProbeSet.validName("a b"));
        assertFalse(ProbeSet.validName("../x"));
        assertFalse(ProbeSet.validName("a".repeat(ProbeSet.MAX_NAME_LENGTH + 1)));
        ProbeSet set = new ProbeSet();
        set.add("a", BLOCK, 10, 64, -3);
        assertThrows(IllegalArgumentException.class, () -> set.add("A", BLOCK, 10, 64, -3), "taken");
        assertThrows(IllegalArgumentException.class, () -> set.add("a.b", BLOCK, 10, 64, -3));
        assertThrows(IllegalArgumentException.class, () -> set.add("b", BLOCK, Double.NaN, 64, -3));
        assertTrue(set.rename("a", "Kiln"));
        assertEquals("kiln", set.probes().get(0).name());
        assertTrue(set.rename("kiln", "kiln"), "a probe may keep its own name");
        set.add("b", BLOCK, 10, 64, -3);
        assertFalse(set.rename("b", "kiln"), "taken");
        assertFalse(set.rename("b", "no way"));
        assertFalse(set.rename("missing", "c"));
    }

    @Test
    void aPointOnABlocksFaceIsMovedIntoTheBlock() {
        ProbeSet set = new ProbeSet();
        ProbeSet.Probe top = set.add(null, BLOCK, 10.25, 65.0, -3.0);
        assertEquals(10.25, top.x());
        assertEquals(Math.nextDown(65.0), top.y(), "the top face reads the block's top, not the block above");
        assertEquals(-3.0, top.z());
        ProbeSet.Probe far = set.add(null, BLOCK, 3.0, 70.0, -10.0);
        assertEquals(10.0, far.x());
        assertEquals(Math.nextDown(65.0), far.y());
        assertEquals(-3.0, far.z());
    }

    @Test
    void theSetIsFullAtItsLimit() {
        ProbeSet set = new ProbeSet(2);
        set.add(null, BLOCK, 10, 64, -3);
        set.add(null, BLOCK, 10, 64, -3);
        assertFalse(set.canAdd());
        assertThrows(IllegalArgumentException.class, () -> set.add(null, BLOCK, 10, 64, -3));
        set.remove("p2");
        assertTrue(set.canAdd());
        set.clear();
        assertEquals(0, set.size());
        assertThrows(IllegalArgumentException.class, () -> new ProbeSet(0));
    }

    @Test
    void everyProbeRecordsOnTheSharedClock() {
        ProbeSet set = new ProbeSet();
        ProbeSet.Probe a = set.add(null, BLOCK, 10, 64, -3);
        set.record(14.4, p -> 300.0);
        ProbeSet.Probe b = set.add(null, BLOCK.offset(0, 1, 0), 10, 65, -3);
        set.record(14.4, p -> p == a ? 301.0 : Double.NaN);
        set.record(14.4, p -> p == a ? Double.POSITIVE_INFINITY : 280.0);
        assertEquals(3 * 14.4, set.clock(), 1e-12);
        assertEquals(3, a.series().samples());
        assertEquals(14.4, a.series().firstTime(), 1e-12);
        assertEquals(2, b.series().samples(), "a probe added later starts with the next reading");
        assertEquals(28.8, b.series().firstTime(), 1e-12);
        assertEquals(set.clock(), b.series().lastTime(), 1e-12);
        assertTrue(Double.isNaN(a.series().last()), "an infinite reading counts as missing");
        assertEquals(300.5, a.series().mean(), 1e-12);
        assertEquals(280.0, b.series().last());
        assertThrows(IllegalArgumentException.class, () -> set.record(0.0, p -> 1.0));
        assertThrows(IllegalArgumentException.class, () -> set.record(Double.NaN, p -> 1.0));
    }

    @Test
    void comesBackAsItWasSaved() {
        ProbeSet set = new ProbeSet();
        set.add(null, BLOCK, 10.5, 64.25, -2.5);
        set.add("kiln", BLOCK.offset(4, 0, 0), 14.5, 64.5, -2.5);
        for (int i = 0; i < 50; i++) {
            double t = i;
            set.record(14.4, p -> p.number() == 1 ? 290 + t : 1000 - t);
        }
        set.remove("p1");
        set.add(null, BLOCK, 10, 64, -3);
        ProbeSet.State state = set.state();
        ProbeSet back = ProbeSet.restore(state, 8);
        assertEquals(state, back.state());
        assertEquals(8, back.limit());
        assertEquals("p4", back.nextName(), "numbering carries on");
        assertEquals(set.clock(), back.clock());
        assertEquals(set.get("kiln").orElseThrow().series().state(), back.get("kiln").orElseThrow().series().state());
        ProbeSet small = ProbeSet.restore(state, 1);
        assertEquals(2, small.size(), "probes beyond a lower limit are kept");
        assertFalse(small.canAdd());
    }

    @Test
    void refusesASavedSetThatCannotBe() {
        ProbeSet set = new ProbeSet();
        set.add("a", BLOCK, 10, 64, -3);
        set.record(1.0, p -> 1.0);
        ProbeSet.State good = set.state();
        ProbeSet.SavedProbe p = good.probes().get(0);
        ProbeSet.SavedProbe twin = new ProbeSet.SavedProbe(2, "A", p.block(), p.x(), p.y(), p.z(), p.series());
        assertThrows(IllegalArgumentException.class, () -> ProbeSet.restore(new ProbeSet.State(good.clockS(), 3,
                List.of(p, twin)), 8), "two probes called a");
        ProbeSet.SavedProbe outside = new ProbeSet.SavedProbe(1, "a", p.block(), 11.0, p.y(), p.z(), p.series());
        assertThrows(IllegalArgumentException.class, () -> ProbeSet.restore(new ProbeSet.State(good.clockS(), 3,
                List.of(outside)), 8), "measures outside its block");
        assertThrows(IllegalArgumentException.class, () -> ProbeSet.restore(new ProbeSet.State(0.5, 3,
                good.probes()), 8), "recorded after the clock");
        assertThrows(IllegalArgumentException.class, () -> ProbeSet.restore(new ProbeSet.State(-1, 3,
                List.of()), 8));
    }
}
