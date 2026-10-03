package io.github.osir933.anchor.core.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/** The most recent world events, oldest dropped first once the log is full. */
public final class EventLog {

    /** The default number of events kept. */
    public static final int DEFAULT_CAPACITY = 4096;

    private final int capacity;
    private final ArrayDeque<WorldEvent> events = new ArrayDeque<>();
    private long total;

    /**
     * Creates a log.
     *
     * @param capacity how many events to keep
     */
    public EventLog(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.capacity = capacity;
    }

    /**
     * Appends an event.
     *
     * @param event the event
     */
    public void add(WorldEvent event) {
        if (events.size() == capacity) {
            events.removeFirst();
        }
        events.addLast(event);
        total++;
    }

    /**
     * Returns the most recent events, oldest first.
     *
     * @param limit the most events to return
     * @return an unmodifiable list
     */
    public List<WorldEvent> recent(int limit) {
        List<WorldEvent> out = new ArrayList<>(Math.min(limit, events.size()));
        Iterator<WorldEvent> it = events.descendingIterator();
        while (it.hasNext() && out.size() < limit) {
            out.add(it.next());
        }
        Collections.reverse(out);
        return Collections.unmodifiableList(out);
    }

    /**
     * Returns the number of events kept.
     *
     * @return the size of the log
     */
    public int size() {
        return events.size();
    }

    /**
     * Returns the number of events ever added, including those dropped.
     *
     * @return the total
     */
    public long total() {
        return total;
    }
}
