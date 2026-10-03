package io.github.osir933.anchor.core.world;

import io.github.osir933.anchor.core.math.CompensatedSum;
import io.github.osir933.anchor.core.matter.Element;
import io.github.osir933.anchor.core.matter.Material;
import io.github.osir933.anchor.core.matter.ThermalState;
import io.github.osir933.anchor.core.space.CellId;
import io.github.osir933.anchor.core.space.GridPos;
import io.github.osir933.anchor.core.space.SectionPos;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The physical world: every section of matter the engine simulates, with its conservation ledger and
 * event log.
 *
 * <p>There are two ways to change it. <em>Edits</em> ({@link #setBlock}, {@link #fill}, adding and removing
 * sections) come from outside the simulation, such as a player or world generation; the world declares
 * their effect on the totals in the ledger itself. <em>Model writes</em> ({@link #writeLeaf}) come from
 * physics models and are never declared, so a model that creates or destroys anything fails the next
 * {@link #audit()}.
 *
 * <p>Everything is visited in a fixed order: sections by key, blocks by index, cells depth first. Given the
 * same inputs, two worlds end in bit-identical states, which {@link #stateHash()} makes easy to check.
 */
public final class PhysicalWorld {

    private static final Element[] ELEMENTS = Element.values();

    private final WorldSettings settings;
    private final MaterialRegistry materials;
    private final CellState ambient;
    private final TreeMap<Long, Section> sections = new TreeMap<>();
    private final ConservationLedger ledger = new ConservationLedger();
    private final EventLog events = new EventLog(EventLog.DEFAULT_CAPACITY);
    private double[][] elementFractions = new double[0][];
    private long tick;
    private int leafCount;

    /**
     * Creates an empty world. Space that holds no section is filled with the ambient material.
     *
     * @param settings the world's settings
     * @param materials the materials the world may use; the ambient material must be registered
     */
    public PhysicalWorld(WorldSettings settings, MaterialRegistry materials) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.materials = Objects.requireNonNull(materials, "materials");
        int ambientIndex = materials.indexOf(settings.ambientMaterial());
        if (ambientIndex < 0) {
            throw new IllegalArgumentException("the ambient material " + settings.ambientMaterial()
                    + " is not registered");
        }
        this.ambient = ambientIndex == MaterialRegistry.VACUUM
                ? CellState.vacuum(Provenance.AMBIENT)
                : fullBlock(ambientIndex, settings.ambientTemperatureK(), Provenance.AMBIENT);
    }

    // ---- reading ----

    /**
     * Returns the settings.
     *
     * @return the settings
     */
    public WorldSettings settings() {
        return settings;
    }

    /**
     * Returns the material registry.
     *
     * @return the registry
     */
    public MaterialRegistry materials() {
        return materials;
    }

    /**
     * Returns the current tick.
     *
     * @return the number of ticks simulated so far
     */
    public long tick() {
        return tick;
    }

    /**
     * Returns the state that fills a block of space nobody has described.
     *
     * @return a copy of the ambient block state
     */
    public CellState ambientBlock() {
        return ambient.copy();
    }

    /**
     * Returns the number of refined cells across the world.
     *
     * @return the leaf count, which {@link WorldSettings#maxLeaves()} caps
     */
    public int leafCount() {
        return leafCount;
    }

    /**
     * Returns the number of sections.
     *
     * @return the section count
     */
    public int sectionCount() {
        return sections.size();
    }

    /**
     * Returns a section.
     *
     * @param key the packed section position
     * @return the section, or {@code null} if it is not part of the world
     */
    public Section section(long key) {
        return sections.get(key);
    }

    /**
     * Returns the sections in key order.
     *
     * @return an unmodifiable view
     */
    public Collection<Section> sections() {
        return Collections.unmodifiableCollection(sections.values());
    }

    /**
     * Returns a block's state.
     *
     * @param pos the block
     * @return a copy of its state, the aggregate of a refined block, or the ambient state where there is
     *     no section
     */
    public CellState readBlock(GridPos pos) {
        Section s = sections.get(pos.sectionKey());
        return s == null ? ambient.copy() : s.blockState(pos.indexInSection());
    }

    /**
     * Tells whether a block is subdivided.
     *
     * @param pos the block
     * @return {@code true} if the block has smaller cells
     */
    public boolean isRefined(GridPos pos) {
        Section s = sections.get(pos.sectionKey());
        return s != null && s.isRefined(pos.indexInSection());
    }

    /**
     * Returns a refined block's octree.
     *
     * @param pos the block
     * @return the octree, or {@code null} if the block is not refined
     */
    public RefinedBlock refinedBlock(GridPos pos) {
        Section s = sections.get(pos.sectionKey());
        return s == null ? null : s.refinedBlock(pos.indexInSection());
    }

    /**
     * Returns the leaf that contains a cell.
     *
     * @param cell any cell
     * @return the cell itself if it is a leaf, the coarser leaf around it, or {@code null} if the cell is
     *     subdivided into smaller leaves
     */
    public CellId leafCovering(CellId cell) {
        RefinedBlock block = refinedBlock(cell.block());
        return block == null ? CellId.of(cell.block()) : block.leafCovering(cell);
    }

    /**
     * Returns a leaf's state.
     *
     * @param leaf a leaf, such as one returned by {@link #leafCovering}
     * @return a copy of its state
     * @throws IllegalArgumentException if the cell is not a leaf
     */
    public CellState readLeaf(CellId leaf) {
        RefinedBlock block = refinedBlock(leaf.block());
        if (block == null) {
            if (leaf.level() != 0) {
                throw new IllegalArgumentException(leaf + " is not a leaf: its block is not refined");
            }
            return readBlock(leaf.block());
        }
        return block.liveLeaf(leaf).copy();
    }

    /**
     * Visits every leaf of every section in the engine's order.
     *
     * @param visitor receives each leaf and a view of its state that is only valid during the call
     */
    public void forEachLeaf(LeafVisitor visitor) {
        for (Section s : sections.values()) {
            s.forEachLeaf(visitor);
        }
    }

    /**
     * Adds up the world's mass, enthalpy and chemical elements.
     *
     * @return the totals
     */
    public Totals totals() {
        TotalsBuilder builder = new TotalsBuilder(this);
        for (Section s : sections.values()) {
            builder.addSection(s, 1);
        }
        return builder.build();
    }

    /**
     * Returns the full-block state of a material at a temperature: one cubic metre at the density the
     * material has at that temperature.
     *
     * @param material a registered material
     * @param temperatureK the temperature in kelvin
     * @return a new state with provenance {@link Provenance#INITIAL}
     */
    public CellState fullBlock(Material material, double temperatureK) {
        return fullBlock(materials.indexOf(material), temperatureK, Provenance.INITIAL);
    }

    // ---- edits from outside the simulation ----

    /**
     * Replaces a block, removing any refinement, and declares the change in the ledger.
     *
     * @param pos the block
     * @param state the new state
     */
    public void setBlock(GridPos pos, CellState state) {
        checkMaterial(state.material());
        Section s = sectionForEdit(pos.sectionKey());
        int index = pos.indexInSection();
        TotalsBuilder change = new TotalsBuilder(this);
        subtractBlock(change, s, index);
        change.add(state, 1);
        int before = s.leafCount();
        s.setBlock(index, state);
        leafCount += s.leafCount() - before;
        ledger.recordExchange(change.build());
        events.add(new WorldEvent(tick, WorldEvent.Kind.EDIT, describe(pos),
                "set to " + describe(state)));
    }

    /**
     * Fills a block with a material at a temperature.
     *
     * @param pos the block
     * @param material a registered material
     * @param temperatureK the temperature in kelvin
     */
    public void placeMaterial(GridPos pos, Material material, double temperatureK) {
        setBlock(pos, fullBlock(material, temperatureK));
    }

    /**
     * Empties a block.
     *
     * @param pos the block
     */
    public void clearBlock(GridPos pos) {
        setBlock(pos, CellState.vacuum(Provenance.INITIAL));
    }

    /**
     * Fills a box of blocks with a material at a temperature, removing any refinement inside it.
     *
     * @param from one corner of the box, inclusive
     * @param to the opposite corner, inclusive
     * @param material a registered material
     * @param temperatureK the temperature in kelvin
     */
    public void fill(GridPos from, GridPos to, Material material, double temperatureK) {
        CellState state = fullBlock(material, temperatureK);
        int x0 = Math.min(from.x(), to.x());
        int y0 = Math.min(from.y(), to.y());
        int z0 = Math.min(from.z(), to.z());
        int x1 = Math.max(from.x(), to.x());
        int y1 = Math.max(from.y(), to.y());
        int z1 = Math.max(from.z(), to.z());
        TotalsBuilder change = new TotalsBuilder(this);
        long blocks = 0;
        for (int sx = x0 >> 4; sx <= x1 >> 4; sx++) {
            for (int sy = y0 >> 4; sy <= y1 >> 4; sy++) {
                for (int sz = z0 >> 4; sz <= z1 >> 4; sz++) {
                    long key = SectionPos.pack(sx, sy, sz);
                    Section s = sectionForEdit(key);
                    int before = s.leafCount();
                    int bx = sx << 4;
                    int by = sy << 4;
                    int bz = sz << 4;
                    if (x0 <= bx && bx + 15 <= x1 && y0 <= by && by + 15 <= y1 && z0 <= bz && bz + 15 <= z1) {
                        change.addSection(s, -1);
                        s.fill(state);
                        change.add(state, SectionPos.BLOCKS);
                        blocks += SectionPos.BLOCKS;
                    } else {
                        for (int y = Math.max(y0, by); y <= Math.min(y1, by + 15); y++) {
                            for (int z = Math.max(z0, bz); z <= Math.min(z1, bz + 15); z++) {
                                for (int x = Math.max(x0, bx); x <= Math.min(x1, bx + 15); x++) {
                                    int index = SectionPos.localIndex(x & 15, y & 15, z & 15);
                                    subtractBlock(change, s, index);
                                    s.setBlock(index, state);
                                    change.add(state, 1);
                                    blocks++;
                                }
                            }
                        }
                    }
                    leafCount += s.leafCount() - before;
                }
            }
        }
        ledger.recordExchange(change.build());
        events.add(new WorldEvent(tick, WorldEvent.Kind.EDIT,
                describe(new GridPos(x0, y0, z0)) + " to " + describe(new GridPos(x1, y1, z1)),
                "filled " + blocks + " blocks with " + describe(state)));
    }

    /**
     * Brings a section into the world, filled with the ambient state, and declares the matter it adds.
     *
     * @param key the packed section position
     * @return the new section, or the existing one if it was already there
     */
    public Section addSection(long key) {
        return sectionForEdit(key);
    }

    /**
     * Takes a section out of the world and declares the matter that leaves with it.
     *
     * @param key the packed section position
     * @return {@code true} if there was a section to remove
     */
    public boolean removeSection(long key) {
        Section s = sections.remove(key);
        if (s == null) {
            return false;
        }
        leafCount -= s.leafCount();
        ledger.recordExchange(new TotalsBuilder(this).addSection(s, -1).build());
        events.add(new WorldEvent(tick, WorldEvent.Kind.SECTION_REMOVED, SectionPos.toString(key), "removed"));
        return true;
    }

    // ---- writes from physics models ----

    /**
     * Replaces a leaf's state. Used by physics models; the change is not declared, so whatever the model
     * moves must add up.
     *
     * @param leaf an existing leaf in a loaded section
     * @param state the new state
     */
    public void writeLeaf(CellId leaf, CellState state) {
        checkMaterial(state.material());
        Section s = sections.get(leaf.block().sectionKey());
        if (s == null) {
            throw new IllegalStateException("no section holds " + leaf.block());
        }
        int index = leaf.block().indexInSection();
        RefinedBlock block = s.refinedBlock(index);
        if (block == null) {
            if (leaf.level() != 0) {
                throw new IllegalArgumentException(leaf + " is not a leaf: its block is not refined");
            }
            s.setBlock(index, state);
        } else {
            block.writeLeaf(leaf, state);
            s.invalidateTotals();
        }
    }

    /**
     * Declares matter or energy crossing the world's boundary because of a physics model, such as heat
     * radiated to the sky.
     *
     * @param change what entered (positive) or left (negative)
     * @param reason a short description for the event log, or {@code null} to log nothing
     */
    public void recordExchange(Totals change, String reason) {
        ledger.recordExchange(change);
        if (reason != null) {
            events.add(new WorldEvent(tick, WorldEvent.Kind.EDIT, "boundary", reason));
        }
    }

    // ---- resolution ----

    /**
     * Subdivides cells until a leaf exists at the target cell's size and position. The new cells assume
     * that the matter they came from was uniform; their provenance says so.
     *
     * @param target the cell that should become a leaf
     * @return what happened
     */
    public TransitionReport refine(CellId target) {
        TransitionReport.Kind kind = TransitionReport.Kind.REFINE;
        if (target.level() == 0) {
            return TransitionReport.unchanged(kind, target, "a whole block needs no refinement");
        }
        GridPos pos = target.block();
        int index = pos.indexInSection();
        Section s = sections.get(pos.sectionKey());
        RefinedBlock existing = s == null ? null : s.refinedBlock(index);
        int needed = existing == null ? 7 * target.level() + 1 : existing.leavesToRefine(target);
        if (needed == 0) {
            return TransitionReport.unchanged(kind, target, "already refined to this size");
        }
        if ((long) leafCount + needed > settings.maxLeaves()) {
            return TransitionReport.refused(kind, target, "the world's leaf budget of " + settings.maxLeaves()
                    + " cells would be exceeded");
        }
        if (s == null) {
            s = sectionForEdit(pos.sectionKey());
        }
        int before = s.leafCount();
        RefinedBlock block = s.refinedBlockForUpdate(index);
        int added = block.refineTo(target);
        s.afterTreeChange(index, added);
        int delta = s.leafCount() - before;
        leafCount += delta;
        events.add(new WorldEvent(tick, WorldEvent.Kind.REFINE, describe(target),
                "refined to " + CellId.edgeLength(target.level()) + " m cells"));
        return TransitionReport.applied(kind, target, delta);
    }

    /**
     * Merges the leaves under a cell into one, if the rule finds nothing that would be lost.
     *
     * @param node the cell that should become a leaf
     * @param rule how uniform the leaves must be
     * @return what happened, with the reason for a refusal
     */
    public TransitionReport coarsen(CellId node, CoarseningRule rule) {
        TransitionReport.Kind kind = TransitionReport.Kind.COARSEN;
        GridPos pos = node.block();
        int index = pos.indexInSection();
        Section s = sections.get(pos.sectionKey());
        RefinedBlock block = s == null ? null : s.refinedBlock(index);
        if (block == null) {
            return TransitionReport.unchanged(kind, node, "the block is not refined");
        }
        CellId covering = block.leafCovering(node);
        if (covering != null) {
            return TransitionReport.unchanged(kind, node, covering.equals(node)
                    ? "the cell is already a leaf" : "the cell lies inside a coarser leaf");
        }
        List<CellState> states = new ArrayList<>();
        List<CellId> leaves = block.leavesUnder(node, states);
        String objection = objection(leaves, states, rule);
        if (objection != null) {
            return TransitionReport.refused(kind, node, objection);
        }
        double[] sums = block.subtreeTotals(node);
        CellState first = states.get(0);
        CellState merged = new CellState(first.material(), sums[0], sums[0] > 0 ? sums[1] : 0.0, first.owner(),
                Provenance.COARSENED);
        int before = s.leafCount();
        int removed = block.collapse(node, merged);
        s.afterTreeChange(index, -removed);
        int delta = s.leafCount() - before;
        leafCount += delta;
        events.add(new WorldEvent(tick, WorldEvent.Kind.COARSEN, describe(node),
                "merged " + leaves.size() + " cells"));
        return TransitionReport.applied(kind, node, delta);
    }

    /** Returns why the leaves may not merge, or {@code null} if they may. */
    private String objection(List<CellId> leaves, List<CellState> states, CoarseningRule rule) {
        CellState first = states.get(0);
        for (CellState state : states) {
            if (state.material() != first.material()) {
                return "the cells hold different materials (" + materials.id(first.material()) + " and "
                        + materials.id(state.material()) + ")";
            }
            if (state.owner() != first.owner()) {
                return "the cells belong to different objects (" + first.owner() + " and " + state.owner() + ")";
            }
        }
        if (first.material() == MaterialRegistry.VACUUM) {
            return null;
        }
        double minDensity = Double.POSITIVE_INFINITY;
        double maxDensity = Double.NEGATIVE_INFINITY;
        CompensatedSum mass = new CompensatedSum();
        CompensatedSum volume = new CompensatedSum();
        for (int i = 0; i < states.size(); i++) {
            double v = leaves.get(i).volume();
            double d = states.get(i).mass() / v;
            minDensity = Math.min(minDensity, d);
            maxDensity = Math.max(maxDensity, d);
            mass.add(states.get(i).mass());
            volume.add(v);
        }
        double meanDensity = mass.value() / volume.value();
        if (maxDensity - minDensity > rule.maxRelativeDensitySpread() * meanDensity) {
            return "the density varies from " + minDensity + " to " + maxDensity + " kg/m³";
        }
        if (mass.value() == 0) {
            return null;
        }
        Material material = materials.get(first.material());
        ThermalState reference = null;
        double minT = Double.POSITIVE_INFINITY;
        double maxT = Double.NEGATIVE_INFINITY;
        double minF = Double.POSITIVE_INFINITY;
        double maxF = Double.NEGATIVE_INFINITY;
        for (CellState state : states) {
            ThermalState t = material.stateFor(state.specificEnthalpy());
            if (reference == null) {
                reference = t;
            } else if (t.region() != reference.region()) {
                return "the cells are in different phases ("
                        + material.thermal().regions().get(reference.region()).structure() + " and "
                        + material.thermal().regions().get(t.region()).structure() + ")";
            }
            minT = Math.min(minT, t.temperatureK());
            maxT = Math.max(maxT, t.temperatureK());
            minF = Math.min(minF, t.transitionFraction());
            maxF = Math.max(maxF, t.transitionFraction());
        }
        if (maxT - minT > rule.maxTemperatureSpreadK()) {
            return "the temperature varies by " + (maxT - minT) + " K";
        }
        if (maxF - minF > rule.maxTransitionFractionSpread()) {
            return "the cells are at different stages of a phase change";
        }
        return null;
    }

    // ---- time, accounting and history ----

    /** Moves the world's clock forward one tick. The scheduler calls this after the models have run. */
    public void advanceTick() {
        tick++;
    }

    /**
     * Returns the conservation ledger.
     *
     * @return the ledger
     */
    public ConservationLedger ledger() {
        return ledger;
    }

    /**
     * Returns the event log.
     *
     * @return the log
     */
    public EventLog events() {
        return events;
    }

    /**
     * Audits conservation since the last rebase and logs any discrepancy.
     *
     * @return the audit
     */
    public ConservationLedger.Audit audit() {
        ConservationLedger.Audit audit = ledger.audit(totals());
        for (ConservationLedger.Discrepancy d : audit.discrepancies()) {
            events.add(new WorldEvent(tick, WorldEvent.Kind.CONSERVATION, d.quantity(), d.toString()));
        }
        return audit;
    }

    /**
     * Captures the world's physical state.
     *
     * @return a snapshot that can be restored any number of times
     */
    public WorldSnapshot snapshot() {
        TreeMap<Long, Section> copies = new TreeMap<>();
        for (Map.Entry<Long, Section> e : sections.entrySet()) {
            copies.put(e.getKey(), e.getValue().copy());
        }
        return new WorldSnapshot(settings, materials.palette(), tick, copies, leafCount,
                StateHash.of(tick, materials.palette(), settings, copies.values()));
    }

    /**
     * Rewinds the world to a snapshot. This is a deliberate jump in time, not a physical process, so the
     * ledger starts a new accounting period and the event log records the jump.
     *
     * @param snapshot a snapshot of this world, or of one with the same settings and materials
     */
    public void restore(WorldSnapshot snapshot) {
        if (!snapshot.settings().equals(settings)) {
            throw new IllegalArgumentException("the snapshot was taken with different world settings");
        }
        List<String> palette = materials.palette();
        if (snapshot.palette().size() > palette.size()
                || !palette.subList(0, snapshot.palette().size()).equals(snapshot.palette())) {
            throw new IllegalArgumentException("the snapshot uses materials this world does not have");
        }
        long from = tick;
        sections.clear();
        for (Map.Entry<Long, Section> e : snapshot.sections().entrySet()) {
            sections.put(e.getKey(), e.getValue().copy());
        }
        tick = snapshot.tick();
        leafCount = snapshot.leafCount();
        ledger.rebase(totals());
        events.add(new WorldEvent(tick, WorldEvent.Kind.RESTORE, "world",
                "rewound from tick " + from + " to tick " + snapshot.tick()));
    }

    /**
     * Returns a SHA-256 hash of the world's physical state: tick, settings, materials and every cell.
     * Two worlds with the same hash are in bit-identical states.
     *
     * @return the hash as 64 hexadecimal digits
     */
    public String stateHash() {
        return StateHash.of(tick, materials.palette(), settings, sections.values());
    }

    // ---- internals ----

    /** Returns the mass fraction of each element, indexed by ordinal, in the material at an index. */
    double[] elementFractions(int material) {
        if (material >= elementFractions.length) {
            double[][] grown = new double[materials.size()][];
            System.arraycopy(elementFractions, 0, grown, 0, elementFractions.length);
            for (int i = elementFractions.length; i < grown.length; i++) {
                grown[i] = new double[ELEMENTS.length];
                if (i != MaterialRegistry.VACUUM) {
                    for (Map.Entry<Element, Double> e
                            : materials.get(i).composition().elementMassFractions().entrySet()) {
                        grown[i][e.getKey().ordinal()] = e.getValue();
                    }
                }
            }
            elementFractions = grown;
        }
        return elementFractions[material];
    }

    private CellState fullBlock(int material, double temperatureK, Provenance provenance) {
        Material m = materials.get(material);
        double h = m.specificEnthalpy(temperatureK);
        double mass = m.density(m.stateFor(h)); // a block is one cubic metre
        return new CellState(material, mass, mass * h, 0L, provenance);
    }

    private Section sectionForEdit(long key) {
        Section s = sections.get(key);
        if (s == null) {
            s = new Section(key, ambient);
            sections.put(key, s);
            ledger.recordExchange(new TotalsBuilder(this).add(ambient, SectionPos.BLOCKS).build());
            events.add(new WorldEvent(tick, WorldEvent.Kind.SECTION_ADDED, SectionPos.toString(key),
                    "added, filled with " + describe(ambient)));
        }
        return s;
    }

    private static void subtractBlock(TotalsBuilder change, Section s, int index) {
        RefinedBlock block = s.refinedBlock(index);
        if (block == null) {
            change.add(s.blockState(index), -1);
        } else {
            block.visitLive((cell, state) -> change.add(state, -1));
        }
    }

    private void checkMaterial(int material) {
        if (!materials.contains(material)) {
            throw new IllegalArgumentException("no material has index " + material);
        }
    }

    private String describe(CellState state) {
        if (state.material() == MaterialRegistry.VACUUM) {
            return "vacuum";
        }
        Material m = materials.get(state.material());
        return m.name() + " (" + state.mass() + " kg, " + m.stateFor(state.specificEnthalpy()).temperatureK()
                + " K)";
    }

    private static String describe(GridPos pos) {
        return pos.x() + " " + pos.y() + " " + pos.z();
    }

    private static String describe(CellId cell) {
        return describe(cell.block()) + (cell.level() == 0 ? ""
                : " level " + cell.level() + " [" + cell.subX() + " " + cell.subY() + " " + cell.subZ() + "]");
    }
}
