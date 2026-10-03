package io.github.osir933.anchor.core.model;

import io.github.osir933.anchor.core.world.ConservationLedger;
import java.util.List;
import java.util.Objects;

/**
 * What happened in one tick of the scheduler.
 *
 * @param tick the tick that was simulated
 * @param runs one entry per registered model, in the order they were considered
 * @param audit the conservation audit, or {@code null} if this tick was not audited
 * @param validityIssues issues the models reported after running
 * @param budgetBalance the work units left over, negative when a starved model ran on credit
 */
public record TickReport(long tick, List<ModelRun> runs, ConservationLedger.Audit audit,
        List<ValidityIssue> validityIssues, long budgetBalance) {

    /**
     * Copies the lists.
     *
     * @param tick the tick
     * @param runs the runs
     * @param audit the audit, may be null
     * @param validityIssues the issues
     * @param budgetBalance the balance
     */
    public TickReport {
        runs = List.copyOf(Objects.requireNonNull(runs, "runs"));
        validityIssues = List.copyOf(Objects.requireNonNull(validityIssues, "validityIssues"));
    }

    /**
     * Tells whether the tick was audited and balanced.
     *
     * @return {@code false} if an audit found a discrepancy
     */
    public boolean conserved() {
        return audit == null || audit.balanced();
    }

    /**
     * What one model did in a tick.
     *
     * @param modelId the model
     * @param status whether it ran
     * @param dt the simulated time it covered, or would have covered, in seconds
     * @param cost its estimated cost in work units
     */
    public record ModelRun(String modelId, Status status, double dt, long cost) {
    }

    /** Whether a model ran. */
    public enum Status {
        /** It ran within the budget. */
        RAN,
        /** It had waited too long and ran on credit, pushing the budget below zero. */
        RAN_ON_CREDIT,
        /** It did not fit in the budget; its time carries over to a later tick. */
        DEFERRED
    }
}
