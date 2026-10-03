package io.github.osir933.anchor.core.model;

import io.github.osir933.anchor.core.math.DeterministicRandom;
import io.github.osir933.anchor.core.world.ConservationLedger;
import io.github.osir933.anchor.core.world.PhysicalWorld;
import io.github.osir933.anchor.core.world.WorldEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Runs the physics models each tick within a budget of work, deterministically.
 *
 * <p>Each tick adds {@code budgetPerTick} work units to a balance, which can save up to
 * {@link #MAX_SAVED_TICKS} ticks' worth. Models are considered by priority, then id. A model runs if its
 * estimated cost fits in the balance; otherwise it is deferred and the time it owes carries over, so when it
 * runs it covers the whole interval. A model deferred for {@code maxDeferredTicks} runs anyway on credit, so
 * nothing starves. Every decision depends on cost estimates computed from the world, never on how long
 * anything actually took, so every machine makes the same decisions.
 */
public final class Scheduler {

    /** How many ticks' budget the balance can save up. */
    public static final int MAX_SAVED_TICKS = 4;

    private static final Comparator<PhysicsModel> ORDER = Comparator.comparingInt(PhysicsModel::priority)
            .thenComparing(PhysicsModel::id);

    private final double tickSeconds;
    private final long budgetPerTick;
    private final int maxDeferredTicks;
    private final int auditInterval;
    private final List<PhysicsModel> models = new ArrayList<>();
    private final Map<String, Double> owedSeconds = new TreeMap<>();
    private final Map<String, Integer> deferredTicks = new TreeMap<>();
    private long balance;

    /**
     * Creates a scheduler.
     *
     * @param tickSeconds simulated seconds per tick; Minecraft runs 20 ticks a second, so 0.05 keeps
     *     simulated time in step with game time
     * @param budgetPerTick work units available each tick
     * @param maxDeferredTicks how long a model may wait before it runs on credit
     * @param auditInterval audit conservation every this many ticks; 1 audits every tick
     */
    public Scheduler(double tickSeconds, long budgetPerTick, int maxDeferredTicks, int auditInterval) {
        if (!(tickSeconds > 0) || !Double.isFinite(tickSeconds)) {
            throw new IllegalArgumentException("tick length must be positive: " + tickSeconds);
        }
        if (budgetPerTick < 0 || maxDeferredTicks < 0 || auditInterval < 1) {
            throw new IllegalArgumentException("budget and deferral limit must be non-negative, audit interval"
                    + " positive");
        }
        this.tickSeconds = tickSeconds;
        this.budgetPerTick = budgetPerTick;
        this.maxDeferredTicks = maxDeferredTicks;
        this.auditInterval = auditInterval;
    }

    /**
     * Adds a model.
     *
     * @param model the model; its id must be unique
     */
    public void register(PhysicsModel model) {
        Objects.requireNonNull(model, "model");
        if (owedSeconds.containsKey(model.id())) {
            throw new IllegalArgumentException("a model with id " + model.id() + " is already registered");
        }
        models.add(model);
        models.sort(ORDER);
        owedSeconds.put(model.id(), 0.0);
        deferredTicks.put(model.id(), 0);
    }

    /**
     * Returns the registered models in the order they run.
     *
     * @return an unmodifiable list
     */
    public List<PhysicsModel> models() {
        return List.copyOf(models);
    }

    /**
     * Returns the simulated time each tick covers.
     *
     * @return seconds per tick
     */
    public double tickSeconds() {
        return tickSeconds;
    }

    /**
     * Simulates one tick: runs the models that fit the budget, advances the world's clock, and audits
     * conservation if this tick is due.
     *
     * @param world the world
     * @return what happened
     */
    public TickReport tick(PhysicalWorld world) {
        balance = Math.min(balance + budgetPerTick, budgetPerTick * MAX_SAVED_TICKS);
        List<TickReport.ModelRun> runs = new ArrayList<>(models.size());
        List<ValidityIssue> issues = new ArrayList<>();
        for (PhysicsModel model : models) {
            String id = model.id();
            double owed = owedSeconds.get(id) + tickSeconds;
            long cost = Math.max(0, model.estimateCost(world, owed));
            boolean starved = deferredTicks.get(id) >= maxDeferredTicks;
            if (cost <= balance || starved) {
                TickReport.Status status = cost <= balance ? TickReport.Status.RAN : TickReport.Status.RAN_ON_CREDIT;
                DeterministicRandom random = DeterministicRandom.forKeys(world.settings().seed(), world.tick(),
                        id.hashCode());
                model.step(new StepContext(world, owed, random));
                balance -= cost;
                owedSeconds.put(id, 0.0);
                deferredTicks.put(id, 0);
                runs.add(new TickReport.ModelRun(id, status, owed, cost));
                issues.addAll(model.checkValidity(world));
            } else {
                owedSeconds.put(id, owed);
                deferredTicks.put(id, deferredTicks.get(id) + 1);
                runs.add(new TickReport.ModelRun(id, TickReport.Status.DEFERRED, owed, cost));
            }
        }
        long simulated = world.tick();
        world.advanceTick();
        ConservationLedger.Audit audit = null;
        if (world.tick() % auditInterval == 0) {
            audit = world.auditAndRebase();
        }
        for (ValidityIssue issue : issues) {
            world.events().add(new WorldEvent(simulated, WorldEvent.Kind.VALIDITY, issue.modelId(),
                    issue.message()));
        }
        return new TickReport(simulated, runs, audit, issues, balance);
    }
}
