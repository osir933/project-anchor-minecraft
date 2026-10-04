package io.github.osir933.anchor.core.host;

/**
 * Decides when a hosted world takes its steps on the host's clock, so that the simulation can be paused, stepped
 * by hand, run faster or slower than the host, or sent ahead by a stretch of simulated time. On each tick of its
 * clock the host calls {@link #beginTick()}, takes steps for as long as {@link #wantsStep(double)} says, telling
 * {@link #stepped(double)} how long each took, and ends with {@link #endTick()}.
 *
 * <p>At normal speed the world takes one step every {@code ticksPerStep} host ticks, whatever the steps cost. A
 * speed is kept in hundredths of normal, and what it is owed is counted in whole numbers, so every speed keeps an
 * exact rhythm however long it runs: at a quarter of normal speed a step comes every {@code 4 * ticksPerStep}
 * ticks, never one tick early or late. Running faster than normal never makes the world slower than normal: the
 * steps normal speed would take are always taken, and the steps beyond them only while they fit into a budget of
 * time per tick, judged from what recent steps cost. Steps a speed is owed that do not fit are forgiven at the end
 * of the tick, all but one, so a host that cannot keep up runs as fast as it can instead of falling ever further
 * behind.
 *
 * <p>Steps asked for by hand, to step a paused world or to send it ahead, are never forgiven: they are taken
 * whether the world is paused or not, at least one a tick and more while they fit into the budget, until they are
 * done. While they wait, the speed earns nothing, so sending a running world ahead does not leave it owing steps.
 *
 * <p>How many steps beyond the normal ones fit into a tick depends on how fast the host's machine is; the steps
 * themselves, and what they compute, are the same everywhere.
 */
public final class Pacer {

    /** Normal speed, in hundredths. */
    public static final int NORMAL_SPEED = 100;

    /** The slowest speed, in hundredths: one hundredth of normal. */
    public static final int MIN_SPEED = 1;

    /** The fastest speed, in hundredths: a thousand times normal. */
    public static final int MAX_SPEED = 100_000;

    /** The most steps taken in one host tick, however cheap they are. */
    public static final int MAX_STEPS_PER_TICK = 256;

    /** The most steps that can wait to be taken by hand. */
    public static final long MAX_REQUESTED = 10_000_000L;

    /** How many recent host ticks the speed actually reached is measured over. */
    public static final int WINDOW_TICKS = 200;

    private final int ticksPerStep;
    private final long stepCost;
    private final double budgetMillis;
    private final int[] recent = new int[WINDOW_TICKS];
    private boolean paused;
    private int speed = NORMAL_SPEED;
    /** What the speed has earned toward its next step, in hundredths of a host tick. */
    private long credit;
    /** What normal speed has earned toward its next step: steps it pays for are taken whatever they cost. */
    private long free;
    private long requested;
    private long requestTotal;
    private int stepsThisTick;
    private long ticks;
    private long steps;
    private long recentSteps;
    private long lastForgiven = Long.MIN_VALUE;
    private double millisPerStep;

    /**
     * Creates a pacer for a world running at normal speed, which takes its first step on the first tick.
     *
     * @param ticksPerStep how many host ticks apart steps come at normal speed, at least 1
     * @param budgetMillis how long the steps beyond the normal ones may take in one host tick, in milliseconds
     */
    public Pacer(int ticksPerStep, double budgetMillis) {
        if (ticksPerStep < 1) {
            throw new IllegalArgumentException("steps must come at least one tick apart, not " + ticksPerStep);
        }
        if (!(budgetMillis >= 0.0) || !Double.isFinite(budgetMillis)) {
            throw new IllegalArgumentException("the budget must be a finite time of at least 0, not " + budgetMillis);
        }
        this.ticksPerStep = ticksPerStep;
        this.stepCost = (long) ticksPerStep * NORMAL_SPEED;
        this.budgetMillis = budgetMillis;
        this.credit = stepCost - NORMAL_SPEED;
        this.free = stepCost - NORMAL_SPEED;
    }

    /**
     * Converts a speed given as a multiple of normal speed into hundredths, rounding to the nearest.
     *
     * @param factor the multiple, such as {@code 0.5} for half of normal speed
     * @return the speed in hundredths
     * @throws IllegalArgumentException if it rounds to a speed slower than {@link #MIN_SPEED} or faster than
     *     {@link #MAX_SPEED}
     */
    public static int speedOf(double factor) {
        double hundredths = Math.rint(factor * NORMAL_SPEED);
        if (!(hundredths >= MIN_SPEED && hundredths <= MAX_SPEED)) {
            throw new IllegalArgumentException("a speed must be from " + (double) MIN_SPEED / NORMAL_SPEED + " to "
                    + MAX_SPEED / NORMAL_SPEED + " times normal, not " + factor);
        }
        return (int) hundredths;
    }

    /**
     * Converts a length of simulated time into whole steps, rounding up so that the world goes at least that far; a
     * time a whole number of steps long, to within rounding, takes exactly that many.
     *
     * @param seconds the time in seconds, more than 0
     * @param stepSeconds simulated seconds per step, more than 0
     * @return the steps, at least 1
     */
    public static long stepsFor(double seconds, double stepSeconds) {
        if (!(seconds > 0.0) || !(stepSeconds > 0.0) || !Double.isFinite(seconds) || !Double.isFinite(stepSeconds)) {
            throw new IllegalArgumentException("times must be finite and above 0, not " + seconds + " and "
                    + stepSeconds);
        }
        return Math.max(1L, (long) Math.ceil(seconds / stepSeconds * (1.0 - 1e-12)));
    }

    /**
     * Returns how many host ticks apart steps come at normal speed.
     *
     * @return the ticks per step
     */
    public int ticksPerStep() {
        return ticksPerStep;
    }

    /**
     * Returns the speed.
     *
     * @return the speed in hundredths of normal
     */
    public int speed() {
        return speed;
    }

    /**
     * Sets the speed. What the old speed earned toward the next step is kept.
     *
     * @param hundredths the speed in hundredths of normal, from {@link #MIN_SPEED} to {@link #MAX_SPEED}
     */
    public void setSpeed(int hundredths) {
        if (hundredths < MIN_SPEED || hundredths > MAX_SPEED) {
            throw new IllegalArgumentException("a speed must be from " + MIN_SPEED + " to " + MAX_SPEED
                    + " hundredths of normal, not " + hundredths);
        }
        speed = hundredths;
    }

    /**
     * Tells whether the world is paused.
     *
     * @return {@code true} if it takes only the steps asked for by hand
     */
    public boolean paused() {
        return paused;
    }

    /**
     * Pauses the world, dropping any steps still waiting to be taken by hand, so that nothing moves until steps are
     * asked for again or the world is resumed.
     */
    public void pause() {
        paused = true;
        cancel();
    }

    /** Lets the world run at its speed again. It picks up where it stopped, owing no steps for the pause. */
    public void resume() {
        paused = false;
    }

    /**
     * Asks for steps to be taken as soon as the budget allows, paused or not. They add to any still waiting.
     *
     * @param count how many, at least 1
     * @throws IllegalArgumentException if that would leave more than {@link #MAX_REQUESTED} waiting
     */
    public void request(long count) {
        if (count < 1 || count > MAX_REQUESTED - requested) {
            throw new IllegalArgumentException("at most " + MAX_REQUESTED + " steps can wait, and " + requested
                    + " already do; cannot ask for " + count + " more");
        }
        requested += count;
        requestTotal += count;
    }

    /**
     * Drops the steps still waiting to be taken by hand.
     *
     * @return how many were dropped
     */
    public long cancel() {
        long dropped = requested;
        requested = 0;
        requestTotal = 0;
        return dropped;
    }

    /**
     * Returns how many steps asked for by hand are still waiting.
     *
     * @return the count
     */
    public long requested() {
        return requested;
    }

    /**
     * Returns how many steps were asked for since none were last waiting, taken or not.
     *
     * @return the count, or 0 if none are waiting
     */
    public long requestTotal() {
        return requestTotal;
    }

    /** Starts a host tick: the speed and normal speed earn their share of a step, unless the world is paused. */
    public void beginTick() {
        stepsThisTick = 0;
        if (!paused) {
            free += NORMAL_SPEED;
            if (requested == 0) {
                credit += speed;
            }
        }
    }

    /**
     * Tells whether to take another step in this tick.
     *
     * @param millisSpent how long the steps already taken in this tick took, in milliseconds
     * @return {@code true} if a step is owed and either normal speed pays for it, it is the tick's first step asked
     *     for by hand, or one more step like the recent ones fits into the budget
     */
    public boolean wantsStep(double millisSpent) {
        if (stepsThisTick >= MAX_STEPS_PER_TICK) {
            return false;
        }
        if (requested > 0) {
            return stepsThisTick == 0 || fits(millisSpent);
        }
        if (paused || credit < stepCost) {
            return false;
        }
        return free >= stepCost || fits(millisSpent);
    }

    private boolean fits(double millisSpent) {
        return millisSpent + millisPerStep <= budgetMillis;
    }

    /**
     * Records that a step was taken.
     *
     * @param millis how long it took, in milliseconds
     */
    public void stepped(double millis) {
        if (!(millis >= 0.0) || !Double.isFinite(millis)) {
            throw new IllegalArgumentException("a step takes a finite time of at least 0, not " + millis);
        }
        millisPerStep = steps == 0 ? millis : 0.9 * millisPerStep + 0.1 * millis;
        steps++;
        stepsThisTick++;
        if (requested > 0) {
            if (--requested == 0) {
                requestTotal = 0;
            }
        } else {
            credit -= stepCost;
            free = Math.max(0, free - stepCost);
        }
    }

    /** Ends a host tick, forgiving all but one of the steps the speed is owed that did not fit into it. */
    public void endTick() {
        if (credit > stepCost) {
            credit = stepCost;
            lastForgiven = ticks;
        }
        free = Math.min(free, stepCost);
        int slot = (int) (ticks % WINDOW_TICKS);
        recentSteps += stepsThisTick - recent[slot];
        recent[slot] = stepsThisTick;
        ticks++;
    }

    /**
     * Returns how many steps have been taken.
     *
     * @return the count
     */
    public long steps() {
        return steps;
    }

    /**
     * Returns how many steps were taken in the current or last host tick.
     *
     * @return the count
     */
    public int stepsThisTick() {
        return stepsThisTick;
    }

    /**
     * Returns what recent steps took, on average.
     *
     * @return milliseconds per step, or 0 before the first step
     */
    public double millisPerStep() {
        return millisPerStep;
    }

    /**
     * Returns the speed the world actually ran at over the last {@link #WINDOW_TICKS} host ticks, or since the
     * first tick if that was fewer, steps taken by hand included.
     *
     * @return the speed as a multiple of normal, or 0 before the first tick
     */
    public double achievedSpeed() {
        long window = Math.min(ticks, WINDOW_TICKS);
        return window == 0 ? 0.0 : (double) recentSteps * ticksPerStep / window;
    }

    /**
     * Tells whether the world has kept up with its speed over the last {@link #WINDOW_TICKS} host ticks.
     *
     * @return {@code false} if steps the speed was owed had to be forgiven in that time
     */
    public boolean keepingUp() {
        return lastForgiven == Long.MIN_VALUE || ticks - lastForgiven > WINDOW_TICKS;
    }

    /**
     * Returns the pacer's state for display.
     *
     * @return the status
     */
    public Status status() {
        return new Status(paused, speed, requested, requestTotal, achievedSpeed(), keepingUp(), steps,
                millisPerStep);
    }

    /**
     * Where a pacer stands.
     *
     * @param paused whether the world is paused
     * @param speed the speed in hundredths of normal
     * @param requested how many steps asked for by hand are still waiting
     * @param requestTotal how many were asked for since none were last waiting
     * @param achievedSpeed the speed actually reached lately, as a multiple of normal
     * @param keepingUp whether the world kept up with its speed lately
     * @param steps how many steps have been taken
     * @param millisPerStep what recent steps took on average, in milliseconds
     */
    public record Status(boolean paused, int speed, long requested, long requestTotal, double achievedSpeed,
            boolean keepingUp, long steps, double millisPerStep) {
    }

    @Override
    public String toString() {
        return "Pacer[speed=" + speed + "/100, " + (paused ? "paused" : "running") + ", requested=" + requested
                + ", steps=" + steps + "]";
    }
}
