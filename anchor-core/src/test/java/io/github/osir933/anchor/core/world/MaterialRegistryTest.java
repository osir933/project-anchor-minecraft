package io.github.osir933.anchor.core.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.osir933.anchor.core.matter.Composition;
import io.github.osir933.anchor.core.matter.Material;
import io.github.osir933.anchor.core.matter.MaterialLibrary;
import io.github.osir933.anchor.core.matter.Phase;
import io.github.osir933.anchor.core.matter.PhaseRegion;
import io.github.osir933.anchor.core.matter.PropertyCurve;
import io.github.osir933.anchor.core.matter.SpeciesCatalog;
import org.junit.jupiter.api.Test;

class MaterialRegistryTest {

    @Test
    void vacuumIsIndexZero() {
        MaterialRegistry registry = new MaterialRegistry();
        assertEquals(MaterialRegistry.VACUUM_ID, registry.id(MaterialRegistry.VACUUM));
        assertEquals(1, registry.size());
        assertThrows(IllegalArgumentException.class, () -> registry.get(MaterialRegistry.VACUUM));
    }

    @Test
    void indicesFollowRegistrationOrder() {
        MaterialRegistry registry = MaterialRegistry.withLibrary();
        assertEquals(MaterialLibrary.all().size() + 1, registry.size());
        for (int i = 0; i < MaterialLibrary.all().size(); i++) {
            Material m = MaterialLibrary.all().get(i);
            assertEquals(i + 1, registry.indexOf(m));
            assertEquals(i + 1, registry.register(m));
            assertEquals(m.id(), registry.palette().get(i + 1));
        }
        assertEquals(-1, registry.indexOf("anchor:unobtainium"));
    }

    @Test
    void twoMaterialsCannotShareAnId() {
        MaterialRegistry registry = MaterialRegistry.withLibrary();
        Material impostor = Material.builder("anchor:iron", "Fake iron")
                .composition(Composition.pure(SpeciesCatalog.IRON))
                .region(new PhaseRegion(Phase.SOLID, "solid", 1.0, 2000.0,
                        PropertyCurve.constant(450, MaterialLibrary.CRC),
                        PropertyCurve.constant(80, MaterialLibrary.CRC),
                        PropertyCurve.constant(7874, MaterialLibrary.CRC),
                        PropertyCurve.constant(0.3, MaterialLibrary.CRC)))
                .build();
        assertThrows(IllegalArgumentException.class, () -> registry.register(impostor));
        assertThrows(IllegalArgumentException.class, () -> registry.indexOf(impostor));
    }
}
