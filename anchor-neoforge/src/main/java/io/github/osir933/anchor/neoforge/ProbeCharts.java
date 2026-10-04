package io.github.osir933.anchor.neoforge;

import io.github.osir933.anchor.core.instrument.ChartImage;
import io.github.osir933.anchor.core.instrument.ProbeSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

/**
 * Charts of probes drawn on maps. A chart is an ordinary locked map whose picture Anchor draws from what its probes
 * recorded, and draws again as they record more, so it can be held, copied or hung on a wall in an item frame like any
 * map. See {@link ChartImage} for what a chart shows.
 */
final class ProbeCharts {

    /** Charts are drawn again at most every this many game ticks, once a second, while the simulation steps. */
    static final int DRAW_EVERY_TICKS = 20;

    /**
     * Where the chart maps say they are centred, far beyond where anyone builds, so that the game never marks an
     * item frame holding one on its picture.
     */
    private static final double FAR_AWAY = 29_000_000.0;

    /** The map colour of each ink, by the ink's ordinal. */
    private static final byte[] PALETTE = palette();

    private ProbeCharts() {
    }

    private static byte[] palette() {
        byte[] p = new byte[ChartImage.Ink.values().length];
        p[ChartImage.Ink.PAPER.ordinal()] = MapColor.SNOW.getPackedId(MapColor.Brightness.HIGH);
        p[ChartImage.Ink.GRID.ordinal()] = MapColor.WOOL.getPackedId(MapColor.Brightness.HIGH);
        p[ChartImage.Ink.AXIS.ordinal()] = MapColor.COLOR_GRAY.getPackedId(MapColor.Brightness.NORMAL);
        p[ChartImage.Ink.TEXT.ordinal()] = MapColor.COLOR_BLACK.getPackedId(MapColor.Brightness.HIGH);
        p[ChartImage.Ink.LINE_1.ordinal()] = MapColor.COLOR_RED.getPackedId(MapColor.Brightness.HIGH);
        p[ChartImage.Ink.LINE_2.ordinal()] = MapColor.COLOR_BLUE.getPackedId(MapColor.Brightness.HIGH);
        p[ChartImage.Ink.LINE_3.ordinal()] = MapColor.COLOR_GREEN.getPackedId(MapColor.Brightness.HIGH);
        p[ChartImage.Ink.LINE_4.ordinal()] = MapColor.COLOR_ORANGE.getPackedId(MapColor.Brightness.HIGH);
        p[ChartImage.Ink.BAND_1.ordinal()] = MapColor.COLOR_PINK.getPackedId(MapColor.Brightness.HIGH);
        p[ChartImage.Ink.BAND_2.ordinal()] = MapColor.COLOR_LIGHT_BLUE.getPackedId(MapColor.Brightness.HIGH);
        p[ChartImage.Ink.BAND_3.ordinal()] = MapColor.COLOR_LIGHT_GREEN.getPackedId(MapColor.Brightness.HIGH);
        p[ChartImage.Ink.BAND_4.ordinal()] = MapColor.COLOR_YELLOW.getPackedId(MapColor.Brightness.HIGH);
        return p;
    }

    /**
     * Returns the map colour a chart draws an ink in.
     *
     * @param ink the ink
     * @return the packed map colour
     */
    static byte colour(ChartImage.Ink ink) {
        return PALETTE[ink.ordinal()];
    }

    /**
     * Makes a new chart of some probes and returns the map it is drawn on.
     *
     * @param level the level the probes are in
     * @param probes the level's probes
     * @param shown the probes to show, as many as {@link ChartImage#MAX_TRACES}
     * @return a filled map showing the chart
     */
    static ItemStack create(ServerLevel level, LevelProbes probes, List<ProbeSet.Probe> shown) {
        MapId id = level.getFreeMapId();
        MapItemSavedData data = MapItemSavedData.createFresh(FAR_AWAY, FAR_AWAY, (byte) 0, false, false,
                level.dimension()).locked();
        level.setMapData(id, data);
        List<Long> numbers = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (ProbeSet.Probe p : shown.subList(0, Math.min(ChartImage.MAX_TRACES, shown.size()))) {
            numbers.add(p.number());
            names.add(p.name());
        }
        LevelProbes.Chart chart = new LevelProbes.Chart(id.id(), numbers);
        probes.addChart(chart);
        draw(data, image(probes.set(), chart));
        ItemStack stack = new ItemStack(Items.FILLED_MAP);
        stack.set(DataComponents.MAP_ID, id);
        stack.set(DataComponents.ITEM_NAME, Component.translatableWithFallback("item.anchor.chart", "Chart of %s",
                String.join(", ", names)));
        return stack;
    }

    /**
     * Draws every chart of a level again from what its probes have recorded. A chart whose map is gone is forgotten.
     *
     * @param level the level
     * @param probes its probes
     */
    static void drawAll(ServerLevel level, LevelProbes probes) {
        for (LevelProbes.Chart chart : probes.charts()) {
            MapItemSavedData data = level.getMapData(new MapId(chart.mapId()));
            if (data == null) {
                probes.removeChart(chart.mapId());
            } else {
                draw(data, image(probes.set(), chart));
            }
        }
    }

    /** Draws a chart's picture from the probes it shows that are still there. */
    static ChartImage image(ProbeSet set, LevelProbes.Chart chart) {
        List<ChartImage.Trace> traces = new ArrayList<>();
        for (long number : chart.probes()) {
            Optional<ProbeSet.Probe> p = set.byNumber(number);
            p.ifPresent(probe -> traces.add(new ChartImage.Trace(probe.name(), probe.series())));
        }
        return ChartImage.draw(traces, HeatText::toCelsius, "°C");
    }

    /** Puts a picture on a map, changing only the pixels that differ, so that players are sent only those. */
    private static void draw(MapItemSavedData data, ChartImage image) {
        byte[] pixels = image.pixels();
        for (int y = 0; y < ChartImage.SIZE; y++) {
            for (int x = 0; x < ChartImage.SIZE; x++) {
                data.updateColor(x, y, PALETTE[pixels[y * ChartImage.SIZE + x]]);
            }
        }
    }
}
