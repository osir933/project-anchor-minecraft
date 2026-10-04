package io.github.osir933.anchor.core.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PacerTest {

    /** Runs one host tick in which every step takes {@code millis}, and returns how many steps were taken. */
    private static int tick(Pacer pacer, double millis) {
        pacer.beginTick();
        double spent = 0.0;
        int steps = 0;
        while (pacer.wantsStep(spent)) {
            pacer.stepped(millis);
            spent += millis;
            steps++;
        }
        pacer.endTick();
        return steps;
    }

    /** Runs host ticks and returns the ticks, counted from 0, on which steps were taken, once per step. */
    private static List<Integer> run(Pacer pacer, int ticks, double millis) {
        List<Integer> at = new ArrayList<>();
        for (int t = 0; t < ticks; t++) {
            int n = tick(pacer, millis);
            for (int i = 0; i < n; i++) {
                at.add(t);
            }
        }
        return at;
    }

    @Test
    void normalSpeedStepsEveryFewTicksWhateverTheStepsCost() {
        for (double millis : new double[] {0.1, 500.0}) {
            Pacer pacer = new Pacer(4, 20.0);
            List<Integer> at = run(pacer, 400, millis);
            assertEquals(100, at.size(), "steps taking " + millis + " ms");
            for (int i = 0; i < at.size(); i++) {
                assertEquals(4 * i, at.get(i), "the first step comes on the first tick, then one every 4 ticks");
            }
            assertTrue(pacer.keepingUp());
            assertEquals(1.0, pacer.achievedSpeed(), 1e-12);
        }
    }

    @Test
    void slowSpeedsKeepAnExactRhythm() {
        Pacer quarter = new Pacer(4, 20.0);
        quarter.setSpeed(Pacer.speedOf(0.25));
        List<Integer> at = run(quarter, 1600, 1.0);
        assertEquals(100, at.size());
        for (int i = 1; i < at.size(); i++) {
            assertEquals(16, at.get(i) - at.get(i - 1), "a quarter of normal speed steps every 16 ticks");
        }

        Pacer odd = new Pacer(4, 20.0);
        odd.setSpeed(33);
        long taken = 0;
        for (int t = 1; t <= 20_000; t++) {
            taken += tick(odd, 1.0);
            assertEquals((300 + 33L * t) / 400, taken, "after " + t + " ticks at 0.33 of normal speed");
        }
    }

    @Test
    void fastSpeedsTakeExtraStepsWhileTheyFitTheBudget() {
        Pacer pacer = new Pacer(4, 20.0);
        pacer.setSpeed(Pacer.speedOf(10));
        List<Integer> at = run(pacer, 400, 1.0);
        assertEquals((300 + 1000 * 400) / 400, at.size(), "ten times normal speed takes 2.5 steps a tick");
        assertTrue(pacer.keepingUp());
        assertEquals(10.0, pacer.achievedSpeed(), 0.03);
    }

    @Test
    void theBudgetLimitsExtraStepsAndWhatDoesNotFitIsForgiven() {
        Pacer pacer = new Pacer(4, 20.0);
        pacer.setSpeed(Pacer.speedOf(100));
        for (int t = 0; t < 300; t++) {
            assertEquals(4, tick(pacer, 5.0), "four steps of 5 ms fit into 20 ms, on tick " + t);
        }
        assertFalse(pacer.keepingUp());
        assertEquals(16.0, pacer.achievedSpeed(), 1e-12);
        pacer.setSpeed(Pacer.NORMAL_SPEED);
        assertTrue(tick(pacer, 5.0) <= 1, "what the fast speed could not take is not owed later");
    }

    @Test
    void runningFasterNeverRunsSlowerThanNormal() {
        Pacer pacer = new Pacer(4, 20.0);
        pacer.setSpeed(Pacer.speedOf(10));
        List<Integer> at = run(pacer, 400, 100.0);
        assertEquals(100, at.size(), "steps too slow for the budget still come at normal speed");
        assertFalse(pacer.keepingUp());
        assertEquals(1.0, pacer.achievedSpeed(), 1e-12);
    }

    @Test
    void aPausedWorldTakesOnlyTheStepsAskedFor() {
        Pacer pacer = new Pacer(4, 20.0);
        pacer.pause();
        assertTrue(run(pacer, 100, 1.0).isEmpty(), "paused, nothing moves");
        pacer.request(3);
        assertEquals(3, pacer.requestTotal());
        assertEquals(3, tick(pacer, 1.0), "three quick steps fit into one tick");
        assertEquals(0, pacer.requested());
        assertEquals(0, pacer.requestTotal(), "nothing is waiting once they are done");
        assertTrue(run(pacer, 100, 1.0).isEmpty(), "and the world stays paused");

        pacer.request(5);
        for (int t = 0; t < 5; t++) {
            assertEquals(1, tick(pacer, 30.0), "slow steps asked for still come one a tick");
            assertEquals(t < 4 ? 5 : 0, pacer.requestTotal(), "the total asked for stays while they run");
        }
        assertEquals(0, pacer.requested());
        assertTrue(run(pacer, 100, 1.0).isEmpty());
    }

    @Test
    void pausingDropsTheStepsStillWaiting() {
        Pacer pacer = new Pacer(4, 20.0);
        pacer.request(500);
        assertEquals(1, tick(pacer, 50.0));
        pacer.pause();
        assertEquals(0, pacer.requested());
        assertTrue(run(pacer, 50, 1.0).isEmpty());
        pacer.request(2);
        assertEquals(2, pacer.cancel(), "cancelling says how many were dropped");
        assertTrue(run(pacer, 50, 1.0).isEmpty());
    }

    @Test
    void sendingARunningWorldAheadLeavesItOwingNothing() {
        Pacer pacer = new Pacer(4, 20.0);
        run(pacer, 7, 1.0);
        pacer.request(1000);
        int ticks = 0;
        while (pacer.requested() > 0) {
            tick(pacer, 1.0);
            ticks++;
        }
        assertEquals(50, ticks, "twenty steps of 1 ms fit into each tick");
        List<Integer> after = run(pacer, 40, 1.0);
        assertEquals(10, after.size(), "then it steps at normal speed again");
        for (int i = 1; i < after.size(); i++) {
            assertEquals(4, after.get(i) - after.get(i - 1));
        }
    }

    @Test
    void resumingOwesNothingForThePause() {
        Pacer pacer = new Pacer(4, 20.0);
        pacer.setSpeed(Pacer.speedOf(3));
        run(pacer, 10, 1.0);
        pacer.pause();
        run(pacer, 1000, 1.0);
        pacer.resume();
        List<Integer> at = run(pacer, 400, 1.0);
        assertEquals(300, at.size(), "three times normal speed, and no burst of steps after the pause");
        for (int t = 0; t < 4; t++) {
            int tick = t;
            assertTrue(at.stream().filter(s -> s == tick).count() <= 1, "tick " + t);
        }
    }

    @Test
    void stepsPerTickAreCapped() {
        Pacer pacer = new Pacer(4, 20.0);
        pacer.pause();
        pacer.request(1000);
        assertEquals(Pacer.MAX_STEPS_PER_TICK, tick(pacer, 0.0));
        assertEquals(Pacer.MAX_STEPS_PER_TICK, tick(pacer, 0.0));
        assertEquals(Pacer.MAX_STEPS_PER_TICK, tick(pacer, 0.0));
        assertEquals(1000 - 3 * Pacer.MAX_STEPS_PER_TICK, tick(pacer, 0.0));
        assertEquals(1000, pacer.steps());
    }

    @Test
    void speedsAreHundredthsOfNormal() {
        assertEquals(100, Pacer.speedOf(1.0));
        assertEquals(50, Pacer.speedOf(0.5));
        assertEquals(1, Pacer.speedOf(0.01));
        assertEquals(100_000, Pacer.speedOf(1000.0));
        assertEquals(333, Pacer.speedOf(3.333));
        for (double bad : new double[] {0.004, 0.0, -1.0, 1000.01, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> Pacer.speedOf(bad), "speed " + bad);
        }
    }

    @Test
    void lengthsOfTimeBecomeWholeSteps() {
        assertEquals(2500, Pacer.stepsFor(36_000.0, 14.4), "ten hours of steps of 14.4 s");
        assertEquals(6000, Pacer.stepsFor(86_400.0, 14.4));
        assertEquals(3, Pacer.stepsFor(30.0, 14.4), "rounded up, so the world goes at least that far");
        assertEquals(1, Pacer.stepsFor(1.0, 14.4));
        assertEquals(1, Pacer.stepsFor(14.4, 14.4));
        assertEquals(2, Pacer.stepsFor(28.8, 14.4));
        assertThrows(IllegalArgumentException.class, () -> Pacer.stepsFor(0.0, 14.4));
        assertThrows(IllegalArgumentException.class, () -> Pacer.stepsFor(10.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> Pacer.stepsFor(Double.POSITIVE_INFINITY, 14.4));
    }

    @Test
    void badArgumentsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Pacer(0, 20.0));
        assertThrows(IllegalArgumentException.class, () -> new Pacer(4, -1.0));
        assertThrows(IllegalArgumentException.class, () -> new Pacer(4, Double.NaN));
        Pacer pacer = new Pacer(4, 20.0);
        assertThrows(IllegalArgumentException.class, () -> pacer.setSpeed(0));
        assertThrows(IllegalArgumentException.class, () -> pacer.setSpeed(Pacer.MAX_SPEED + 1));
        assertThrows(IllegalArgumentException.class, () -> pacer.request(0));
        pacer.request(Pacer.MAX_REQUESTED);
        assertThrows(IllegalArgumentException.class, () -> pacer.request(1), "no more than the most can wait");
        assertThrows(IllegalArgumentException.class, () -> pacer.stepped(-1.0));
        assertThrows(IllegalArgumentException.class, () -> pacer.stepped(Double.NaN));
    }

    @Test
    void theStatusSaysWhereThePacerStands() {
        Pacer pacer = new Pacer(4, 20.0);
        pacer.setSpeed(250);
        run(pacer, 40, 2.0);
        pacer.pause();
        pacer.request(7);
        Pacer.Status status = pacer.status();
        assertTrue(status.paused());
        assertEquals(250, status.speed());
        assertEquals(7, status.requested());
        assertEquals(7, status.requestTotal());
        assertEquals(2.0, status.millisPerStep(), 1e-12);
        assertEquals(pacer.steps(), status.steps());
        assertTrue(status.keepingUp());
    }
}
