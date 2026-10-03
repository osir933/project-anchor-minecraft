package io.github.osir933.anchor.core.world;

import io.github.osir933.anchor.core.matter.Material;
import io.github.osir933.anchor.core.matter.MaterialLibrary;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Numbers the materials used in a world. Cells store a small index instead of a reference, which keeps
 * sections compact and simple to save; the registry translates back.
 *
 * <p>Index {@value #VACUUM} is vacuum, the absence of matter. Other indices are handed out in registration
 * order, so every machine that registers the same materials in the same order agrees on them. A saved world
 * stores its palette by id and maps it back on load.
 */
public final class MaterialRegistry {

    /** The index of vacuum, which holds no matter. */
    public static final int VACUUM = 0;

    /** The id that stands for vacuum in saved palettes. */
    public static final String VACUUM_ID = "anchor:vacuum";

    private final List<Material> materials = new ArrayList<>();
    private final Map<String, Integer> indices = new TreeMap<>();

    /** Creates a registry that knows only vacuum. */
    public MaterialRegistry() {
        materials.add(null);
        indices.put(VACUUM_ID, VACUUM);
    }

    /**
     * Creates a registry with every material of the built-in {@link MaterialLibrary}, in library order.
     *
     * @return the registry
     */
    public static MaterialRegistry withLibrary() {
        MaterialRegistry registry = new MaterialRegistry();
        for (Material m : MaterialLibrary.all()) {
            registry.register(m);
        }
        return registry;
    }

    /**
     * Registers a material, or returns its index if this exact material is already registered.
     *
     * @param material the material
     * @return its index
     * @throws IllegalArgumentException if a different material already uses the same id
     */
    public int register(Material material) {
        Objects.requireNonNull(material, "material");
        Integer existing = indices.get(material.id());
        if (existing != null) {
            if (existing == VACUUM || materials.get(existing) != material) {
                throw new IllegalArgumentException("another material is registered as " + material.id());
            }
            return existing;
        }
        int index = materials.size();
        materials.add(material);
        indices.put(material.id(), index);
        return index;
    }

    /**
     * Returns the index of a registered material.
     *
     * @param material the material
     * @return its index
     * @throws IllegalArgumentException if the material is not registered
     */
    public int indexOf(Material material) {
        int index = indexOf(material.id());
        if (index <= VACUUM || materials.get(index) != material) {
            throw new IllegalArgumentException(material + " is not registered");
        }
        return index;
    }

    /**
     * Returns the index registered under an id.
     *
     * @param id the material id, or {@link #VACUUM_ID}
     * @return the index, or {@code -1} if nothing is registered under the id
     */
    public int indexOf(String id) {
        Integer index = indices.get(id);
        return index == null ? -1 : index;
    }

    /**
     * Returns the material at an index.
     *
     * @param index a registered index other than {@link #VACUUM}
     * @return the material
     * @throws IllegalArgumentException for vacuum or an unknown index
     */
    public Material get(int index) {
        checkIndex(index);
        if (index == VACUUM) {
            throw new IllegalArgumentException("vacuum is not a material");
        }
        return materials.get(index);
    }

    /**
     * Returns the id of the material at an index.
     *
     * @param index a registered index
     * @return the material id, or {@link #VACUUM_ID}
     */
    public String id(int index) {
        checkIndex(index);
        return index == VACUUM ? VACUUM_ID : materials.get(index).id();
    }

    /**
     * Tells whether an index is registered.
     *
     * @param index the index
     * @return {@code true} for vacuum and every registered material
     */
    public boolean contains(int index) {
        return index >= 0 && index < materials.size();
    }

    /**
     * Returns the number of indices in use, vacuum included.
     *
     * @return one more than the number of registered materials
     */
    public int size() {
        return materials.size();
    }

    /**
     * Returns the ids of all indices in order, starting with vacuum. Saved worlds store this palette.
     *
     * @return an unmodifiable list where position {@code i} holds the id of index {@code i}
     */
    public List<String> palette() {
        List<String> ids = new ArrayList<>(materials.size());
        for (int i = 0; i < materials.size(); i++) {
            ids.add(id(i));
        }
        return Collections.unmodifiableList(ids);
    }

    private void checkIndex(int index) {
        if (!contains(index)) {
            throw new IllegalArgumentException("no material has index " + index);
        }
    }
}
