/**
 * Instruments for experiments: probes that record a reading at a point over time, and ways to look at what they
 * recorded.
 *
 * <p>A {@link io.github.osir933.anchor.core.instrument.ProbeSet} holds a world's probes and their shared clock. Each
 * probe keeps its readings in a {@link io.github.osir933.anchor.core.instrument.TimeSeries}, which holds a recording
 * of any length in fixed memory. {@link io.github.osir933.anchor.core.instrument.ChartImage} draws recordings as a
 * chart the size of a Minecraft map, {@link io.github.osir933.anchor.core.instrument.Sparkline} as one line of text,
 * and {@link io.github.osir933.anchor.core.instrument.TimeSeriesCsv} writes them out for spreadsheets.
 */
package io.github.osir933.anchor.core.instrument;
