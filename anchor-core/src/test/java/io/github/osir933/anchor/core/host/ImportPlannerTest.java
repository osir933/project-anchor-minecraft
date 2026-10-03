package io.github.osir933.anchor.core.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.osir933.anchor.core.space.SectionPos;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class ImportPlannerTest {

    private static final long HOME = SectionPos.pack(10, 4, -3);

    @Test
    void importsComeNearestFirstAndAFewAtATime() {
        ImportPlanner planner = new ImportPlanner(1, 1, 1);
        ImportPlanner.Plan plan = planner.plan(List.of(HOME), new TreeSet<>(), key -> true, 4);
        assertEquals(4, plan.imports().size());
        assertEquals(HOME, plan.imports().get(0), "the anchor's own section comes first");
        for (long key : plan.imports().subList(1, 4)) {
            int d = Math.abs(SectionPos.x(key) - 10) + Math.abs(SectionPos.y(key) - 4)
                    + Math.abs(SectionPos.z(key) + 3);
            assertEquals(1, d, "then its face neighbours: " + SectionPos.toString(key));
        }
        assertTrue(plan.removals().isEmpty());
    }

    @Test
    void everythingInRangeIsImportedEventually() {
        ImportPlanner planner = new ImportPlanner(2, 1, 1);
        TreeSet<Long> imported = new TreeSet<>();
        for (int round = 0; round < 100; round++) {
            ImportPlanner.Plan plan = planner.plan(List.of(HOME), imported, key -> true, 7);
            imported.addAll(plan.imports());
        }
        assertEquals(5 * 5 * 3, imported.size());
    }

    @Test
    void unavailableSectionsAreSkippedWithoutUsingTheLimit() {
        ImportPlanner planner = new ImportPlanner(1, 0, 0);
        ImportPlanner.Plan plan = planner.plan(List.of(HOME), new TreeSet<>(), key -> SectionPos.x(key) != 10, 100);
        assertEquals(6, plan.imports().size());
        assertTrue(plan.imports().stream().allMatch(key -> SectionPos.x(key) != 10));
    }

    @Test
    void sectionsAreLetGoOnlyBeyondTheMargin() {
        ImportPlanner planner = new ImportPlanner(2, 1, 1);
        TreeSet<Long> imported = new TreeSet<>();
        imported.add(SectionPos.pack(13, 4, -3));
        imported.add(SectionPos.pack(14, 4, -3));
        imported.add(SectionPos.pack(10, 7, -3));
        imported.add(HOME);
        ImportPlanner.Plan plan = planner.plan(List.of(HOME), imported, key -> false, 10);
        assertEquals(List.of(SectionPos.pack(10, 7, -3), SectionPos.pack(14, 4, -3)).stream().sorted().toList(),
                plan.removals());
        assertTrue(plan.imports().isEmpty());
        assertEquals(List.copyOf(imported), planner.plan(List.of(), imported, key -> true, 10).removals(),
                "with nobody around, everything goes");
    }

    @Test
    void severalAnchorsShareTheirSections() {
        ImportPlanner planner = new ImportPlanner(1, 0, 0);
        long other = SectionPos.pack(12, 4, -3);
        ImportPlanner.Plan plan = planner.plan(List.of(HOME, other), new TreeSet<>(), key -> true, 100);
        assertEquals(3 * 5, plan.imports().size(), "two 3x3 squares overlapping in one column");
        assertEquals(plan.imports().size(), new TreeSet<>(plan.imports()).size());
    }

    @Test
    void negativeRadiiAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ImportPlanner(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ImportPlanner(0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ImportPlanner(0, 0, -1));
    }
}
