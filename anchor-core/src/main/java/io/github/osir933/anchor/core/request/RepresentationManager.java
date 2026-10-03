package io.github.osir933.anchor.core.request;

import io.github.osir933.anchor.core.model.Domain;
import io.github.osir933.anchor.core.space.CellId;
import io.github.osir933.anchor.core.space.GridPos;
import io.github.osir933.anchor.core.world.CoarseningRule;
import io.github.osir933.anchor.core.world.PhysicalWorld;
import io.github.osir933.anchor.core.world.RefinedBlock;
import io.github.osir933.anchor.core.world.Section;
import io.github.osir933.anchor.core.world.TransitionReport;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Decides where the world is refined. It refines cells that active requests ask for and tries to coarsen
 * refined blocks no request needs any more; coarsening only succeeds where the world's
 * {@link CoarseningRule} finds nothing that would be lost, so detail that changed an outcome stays.
 *
 * <p>The manager holds no state besides the requests, and decides from the world and the tick alone, so a
 * replay from a snapshot makes the same decisions. Each update performs at most a fixed number of
 * refinements and coarsening attempts; demand left over is met on later ticks.
 */
public final class RepresentationManager {

    private final TreeMap<String, SimulationRequest> requests = new TreeMap<>();
    private final CoarseningRule rule;
    private final int maxOperationsPerUpdate;

    /**
     * Creates a manager.
     *
     * @param rule how uniform cells must be to merge
     * @param maxOperationsPerUpdate the most refinements plus coarsening attempts per update
     */
    public RepresentationManager(CoarseningRule rule, int maxOperationsPerUpdate) {
        this.rule = Objects.requireNonNull(rule, "rule");
        if (maxOperationsPerUpdate < 1) {
            throw new IllegalArgumentException("must allow at least one operation per update");
        }
        this.maxOperationsPerUpdate = maxOperationsPerUpdate;
    }

    /**
     * Adds a request, replacing any request with the same id.
     *
     * @param request the request
     */
    public void submit(SimulationRequest request) {
        requests.put(request.id(), request);
    }

    /**
     * Removes a request.
     *
     * @param id the request's id
     * @return {@code true} if there was such a request
     */
    public boolean withdraw(String id) {
        return requests.remove(id) != null;
    }

    /**
     * Returns every request, ordered by id.
     *
     * @return an unmodifiable view
     */
    public Collection<SimulationRequest> requests() {
        return Collections.unmodifiableCollection(requests.values());
    }

    /**
     * Returns the requests whose time window is open at a tick, ordered by id.
     *
     * @param tick the tick
     * @return the active requests
     */
    public List<SimulationRequest> active(long tick) {
        List<SimulationRequest> active = new ArrayList<>();
        for (SimulationRequest r : requests.values()) {
            if (r.when().contains(tick)) {
                active.add(r);
            }
        }
        return active;
    }

    /**
     * Returns the domains some active request asks for.
     *
     * @param tick the tick
     * @return the domains
     */
    public Set<Domain> activeDomains(long tick) {
        EnumSet<Domain> domains = EnumSet.noneOf(Domain.class);
        for (SimulationRequest r : active(tick)) {
            domains.addAll(r.what());
        }
        return Collections.unmodifiableSet(domains);
    }

    /**
     * Brings the world's resolution closer to what the active requests ask for.
     *
     * @param world the world
     * @return what was done
     */
    public Report update(PhysicalWorld world) {
        List<SimulationRequest> active = active(world.tick());
        Counter counter = new Counter(maxOperationsPerUpdate);
        for (SimulationRequest r : active) {
            if (r.how().level() == 0) {
                continue;
            }
            Region.Box box = r.where().bounds();
            for (int x = box.min().x(); x <= box.max().x() && counter.hasBudget(); x++) {
                for (int y = box.min().y(); y <= box.max().y() && counter.hasBudget(); y++) {
                    for (int z = box.min().z(); z <= box.max().z() && counter.hasBudget(); z++) {
                        refineWithin(world, CellId.of(new GridPos(x, y, z)), r.where(), r.how().level(), counter);
                    }
                }
            }
            if (!counter.hasBudget()) {
                counter.unmet = true;
                break;
            }
        }
        coarsenUnneeded(world, active, counter);
        return new Report(counter.refined, counter.coarsened, counter.coarseningRefused, counter.refinementRefused,
                !counter.unmet);
    }

    private static void refineWithin(PhysicalWorld world, CellId cell, Region region, int level, Counter counter) {
        if (!counter.hasBudget() || !region.intersects(cell)) {
            return;
        }
        if (cell.level() == level) {
            CellId covering = world.leafCovering(cell);
            if (covering != null && covering.level() < level) {
                TransitionReport report = world.refine(cell);
                counter.operations++;
                if (report.applied()) {
                    counter.refined++;
                } else if (report.outcome() == TransitionReport.Outcome.REFUSED) {
                    counter.refinementRefused++;
                    counter.unmet = true;
                }
            }
            return;
        }
        for (int octant = 0; octant < 8; octant++) {
            refineWithin(world, cell.child(octant), region, level, counter);
        }
    }

    private void coarsenUnneeded(PhysicalWorld world, List<SimulationRequest> active, Counter counter) {
        List<GridPos> candidates = new ArrayList<>();
        for (Section s : world.sections()) {
            for (RefinedBlock block : s.refinedBlocks()) {
                if (!demanded(block.pos(), active)) {
                    candidates.add(block.pos());
                }
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        // Start at a different candidate each tick so that blocks which refuse to merge cannot keep the
        // others from being tried, without the manager having to remember anything.
        int start = (int) Long.remainderUnsigned(world.tick(), candidates.size());
        for (int i = 0; i < candidates.size() && counter.hasBudget(); i++) {
            GridPos pos = candidates.get((start + i) % candidates.size());
            TransitionReport report = world.coarsen(CellId.of(pos), rule);
            counter.operations++;
            if (report.applied()) {
                counter.coarsened++;
            } else if (report.outcome() == TransitionReport.Outcome.REFUSED) {
                counter.coarseningRefused++;
            }
        }
    }

    private static boolean demanded(GridPos pos, List<SimulationRequest> active) {
        CellId block = CellId.of(pos);
        for (SimulationRequest r : active) {
            if (r.how().level() > 0 && r.where().intersects(block)) {
                return true;
            }
        }
        return false;
    }

    private static final class Counter {
        private final int budget;
        private int operations;
        private int refined;
        private int coarsened;
        private int coarseningRefused;
        private int refinementRefused;
        private boolean unmet;

        private Counter(int budget) {
            this.budget = budget;
        }

        private boolean hasBudget() {
            return operations < budget;
        }
    }

    /**
     * What an update did.
     *
     * @param refined cells refined
     * @param coarsened blocks merged back into whole blocks
     * @param coarseningRefused blocks that could not merge because their detail matters
     * @param refinementRefused refinements the world refused, usually for lack of leaf budget
     * @param demandMet whether every active request is now fully met
     */
    public record Report(int refined, int coarsened, int coarseningRefused, int refinementRefused,
            boolean demandMet) {
    }
}
