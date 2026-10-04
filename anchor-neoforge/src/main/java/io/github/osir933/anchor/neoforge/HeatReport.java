package io.github.osir933.anchor.neoforge;

import io.github.osir933.anchor.core.host.HostedWorld;
import io.github.osir933.anchor.core.host.Pacer;

/**
 * How a level's heat simulation is doing.
 *
 * @param world the simulation's own summary
 * @param stepSeconds simulated seconds per step
 * @param lastStepMillis how long the last step took on the server, in milliseconds
 * @param averageStepMillis a running average of that, in milliseconds
 * @param shownPhaseChanges how many blocks were changed to show melting, freezing or boiling
 * @param restoredBlocks how many blocks took back the state saved with their chunk
 * @param restoredSections how many sections those blocks were in
 * @param failure why the simulation stopped, or {@code null} while it runs
 * @param pace how the simulation is paced: paused or not, its speed and the steps asked for by hand
 * @param ticksPerStep how many game ticks apart steps come at normal speed
 * @param laboratory whether the level is a laboratory, with no sun or sky
 */
record HeatReport(HostedWorld.Status world, double stepSeconds, double lastStepMillis, double averageStepMillis,
        long shownPhaseChanges, long restoredBlocks, int restoredSections, String failure, Pacer.Status pace,
        int ticksPerStep, boolean laboratory) {
}
