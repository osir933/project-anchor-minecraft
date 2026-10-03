package io.github.osir933.anchor.core.request;

import io.github.osir933.anchor.core.model.Domain;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * A request to simulate something in detail: WHAT phenomena, WHERE, WHEN and HOW finely. Players and
 * researchers place requests with tools; the engine refines the world to meet them within its budget and
 * lets the detail go once no request needs it and nothing would be lost.
 *
 * @param id a unique id, which also orders requests
 * @param what the branches of physics of interest
 * @param where the region
 * @param when the ticks during which the request applies
 * @param how the resolution wanted
 * @param requester who placed the request, for the inspector
 */
public record SimulationRequest(String id, Set<Domain> what, Region where, TimeWindow when, Fidelity how,
        String requester) {

    /**
     * Validates the request and freezes its domain set.
     *
     * @param id the id
     * @param what the domains
     * @param where the region
     * @param when the time window
     * @param how the fidelity
     * @param requester the requester
     */
    public SimulationRequest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(what, "what");
        Objects.requireNonNull(where, "where");
        Objects.requireNonNull(when, "when");
        Objects.requireNonNull(how, "how");
        Objects.requireNonNull(requester, "requester");
        EnumSet<Domain> domains = EnumSet.noneOf(Domain.class);
        domains.addAll(what);
        what = Collections.unmodifiableSet(domains);
    }
}
