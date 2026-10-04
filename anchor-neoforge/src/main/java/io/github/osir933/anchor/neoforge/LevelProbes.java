package io.github.osir933.anchor.neoforge;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.osir933.anchor.core.instrument.ProbeSet;
import io.github.osir933.anchor.core.instrument.TimeSeries;
import io.github.osir933.anchor.core.space.GridPos;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.LongStream;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;
import org.slf4j.Logger;

/**
 * The probes of one level and the charts drawn from them, saved with the level. Each probe records the temperature at
 * a point every simulation step; see {@link ProbeSet}. Each chart is a map that {@link ProbeCharts} keeps drawing
 * from some of the probes.
 */
final class LevelProbes {

    /** The most probes a level holds. */
    static final int MAX_PROBES = ProbeSet.DEFAULT_LIMIT;

    /** The most charts a level keeps drawing; making another lets go of the oldest, which keeps its last picture. */
    static final int MAX_CHARTS = 16;

    /** The version of the saved layout, written next to the data. */
    static final int FORMAT = 1;

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * A chart: the map it is drawn on, and the numbers of the probes it shows.
     *
     * @param mapId the map's id
     * @param probes the probes' numbers, in the order the chart shows them
     */
    record Chart(int mapId, List<Long> probes) {

        /**
         * Takes an unmodifiable copy of the probes.
         *
         * @param mapId the map's id
         * @param probes the probes' numbers
         */
        Chart {
            probes = List.copyOf(probes);
        }
    }

    private static final Codec<double[]> DOUBLES = Codec.LONG_STREAM.xmap(
            s -> s.mapToDouble(Double::longBitsToDouble).toArray(),
            a -> Arrays.stream(a).mapToLong(Double::doubleToRawLongBits));

    private static final Codec<long[]> LONGS = Codec.LONG_STREAM.xmap(LongStream::toArray, Arrays::stream);

    private static final Codec<GridPos> BLOCK = BlockPos.CODEC.xmap(b -> new GridPos(b.getX(), b.getY(), b.getZ()),
            g -> new BlockPos(g.x(), g.y(), g.z()));

    /** A recording, its doubles kept as their raw bits so they come back exactly. */
    private static final Codec<TimeSeries.State> SERIES = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("capacity").forGetter(TimeSeries.State::capacity),
            Codec.LONG.fieldOf("span").forGetter(TimeSeries.State::span),
            Codec.LONG.fieldOf("samples").forGetter(TimeSeries.State::samples),
            DOUBLES.fieldOf("start").forGetter(TimeSeries.State::start),
            DOUBLES.fieldOf("end").forGetter(TimeSeries.State::end),
            DOUBLES.fieldOf("minimum").forGetter(TimeSeries.State::minimum),
            DOUBLES.fieldOf("maximum").forGetter(TimeSeries.State::maximum),
            DOUBLES.fieldOf("sum").forGetter(TimeSeries.State::sum),
            LONGS.fieldOf("readings").forGetter(TimeSeries.State::readings),
            DOUBLES.fieldOf("recent_time").forGetter(TimeSeries.State::recentTime),
            DOUBLES.fieldOf("recent_value").forGetter(TimeSeries.State::recentValue))
            .apply(i, TimeSeries.State::new));

    private static final Codec<ProbeSet.SavedProbe> PROBE = RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.fieldOf("number").forGetter(ProbeSet.SavedProbe::number),
            Codec.STRING.fieldOf("name").forGetter(ProbeSet.SavedProbe::name),
            BLOCK.fieldOf("block").forGetter(ProbeSet.SavedProbe::block),
            Codec.DOUBLE.fieldOf("x").forGetter(ProbeSet.SavedProbe::x),
            Codec.DOUBLE.fieldOf("y").forGetter(ProbeSet.SavedProbe::y),
            Codec.DOUBLE.fieldOf("z").forGetter(ProbeSet.SavedProbe::z),
            SERIES.fieldOf("series").forGetter(ProbeSet.SavedProbe::series))
            .apply(i, ProbeSet.SavedProbe::new));

    private static final Codec<ProbeSet.State> PROBES = RecordCodecBuilder.create(i -> i.group(
            Codec.DOUBLE.fieldOf("clock").forGetter(ProbeSet.State::clockS),
            Codec.LONG.fieldOf("next_number").forGetter(ProbeSet.State::nextNumber),
            PROBE.listOf().fieldOf("probes").forGetter(ProbeSet.State::probes))
            .apply(i, ProbeSet.State::new));

    private static final Codec<Chart> CHART = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("map").forGetter(Chart::mapId),
            Codec.LONG.listOf().fieldOf("probes").forGetter(Chart::probes))
            .apply(i, Chart::new));

    /** Everything saved for a level. */
    private record Stored(ProbeSet.State probes, List<Chart> charts) {
    }

    /** Reads and writes a level's probes and charts. */
    static final Codec<LevelProbes> CODEC = RecordCodecBuilder.<Stored>create(i -> i.group(
            PROBES.fieldOf("probes").forGetter(Stored::probes),
            CHART.listOf().fieldOf("charts").forGetter(Stored::charts))
            .apply(i, Stored::new))
            .xmap(LevelProbes::fromStored, p -> new Stored(p.probes.state(), p.charts));

    /**
     * Saves a level's probes with the level: nothing while it has none, otherwise the layout's version and the
     * probes and charts. Probes saved in a layout this version does not read are dropped.
     */
    static final IAttachmentSerializer<LevelProbes> SERIALIZER = new IAttachmentSerializer<>() {
        @Override
        public LevelProbes read(IAttachmentHolder holder, ValueInput input) {
            int format = input.getIntOr("format", 0);
            if (format != FORMAT) {
                LOGGER.warn("Anchor: dropping probes saved in layout {}; this version reads layout {}", format,
                        FORMAT);
                return new LevelProbes();
            }
            return input.read("data", CODEC).orElseGet(() -> {
                LOGGER.warn("Anchor: dropping probes that could not be read");
                return new LevelProbes();
            });
        }

        @Override
        public boolean write(LevelProbes probes, ValueOutput output) {
            if (probes.isEmpty()) {
                return false;
            }
            output.putInt("format", FORMAT);
            output.store("data", CODEC, probes);
            return true;
        }
    };

    private final ProbeSet probes;
    private final List<Chart> charts = new ArrayList<>();
    private int stepsSinceDrawn;

    /** Creates a level's probes with none yet. */
    LevelProbes() {
        this(new ProbeSet(MAX_PROBES));
    }

    private LevelProbes(ProbeSet probes) {
        this.probes = probes;
    }

    private static LevelProbes fromStored(Stored stored) {
        ProbeSet set;
        try {
            set = ProbeSet.restore(stored.probes(), MAX_PROBES);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Anchor: dropping saved probes that do not fit together: {}", e.getMessage());
            return new LevelProbes();
        }
        LevelProbes probes = new LevelProbes(set);
        for (Chart chart : stored.charts()) {
            if (probes.charts.size() < MAX_CHARTS) {
                probes.charts.add(chart);
            }
        }
        return probes;
    }

    /**
     * Returns the probes.
     *
     * @return the level's probes, which callers may change
     */
    ProbeSet set() {
        return probes;
    }

    /**
     * Returns the charts.
     *
     * @return the charts, oldest first, unmodifiable
     */
    List<Chart> charts() {
        return Collections.unmodifiableList(new ArrayList<>(charts));
    }

    /**
     * Remembers a new chart, letting go of the oldest if there are already {@link #MAX_CHARTS}.
     *
     * @param chart the chart
     */
    void addChart(Chart chart) {
        charts.removeIf(c -> c.mapId() == chart.mapId());
        while (charts.size() >= MAX_CHARTS) {
            charts.remove(0);
        }
        charts.add(chart);
    }

    /**
     * Forgets a chart, as when its map is gone.
     *
     * @param mapId the map's id
     */
    void removeChart(int mapId) {
        charts.removeIf(c -> c.mapId() == mapId);
    }

    /**
     * Counts a simulation step and tells whether the charts are due to be drawn again.
     *
     * @param everySteps how many steps apart they are drawn
     * @return {@code true} once every {@code everySteps} steps
     */
    boolean chartsDue(int everySteps) {
        if (++stepsSinceDrawn < everySteps) {
            return false;
        }
        stepsSinceDrawn = 0;
        return true;
    }

    /**
     * Tells whether there is nothing to save.
     *
     * @return {@code true} if the level has no probes and no charts
     */
    boolean isEmpty() {
        return probes.size() == 0 && charts.isEmpty();
    }
}
