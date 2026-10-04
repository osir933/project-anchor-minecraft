package io.github.osir933.anchor.neoforge;

import java.lang.ref.WeakReference;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Makes choosing the Laboratory on the Create New World screen switch the new world to Creative mode with commands
 * allowed, as the laboratory's time control, snapshots and probes need operators' commands. It switches only when the
 * world type changes to the Laboratory, so a player can still choose another game mode for it afterwards; a Hardcore
 * world stays Hardcore.
 */
final class LaboratoryScreen {

    /** The screen state already followed, so that a screen laid out again is not followed twice. */
    private static WeakReference<WorldCreationUiState> followed = new WeakReference<>(null);

    private LaboratoryScreen() {
    }

    /**
     * Registers the listener.
     *
     * @param bus NeoForge's game event bus
     */
    static void register(IEventBus bus) {
        bus.addListener(LaboratoryScreen::screenReady);
    }

    private static void screenReady(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof CreateWorldScreen screen)) {
            return;
        }
        WorldCreationUiState state = screen.getUiState();
        if (followed.get() == state) {
            return;
        }
        followed = new WeakReference<>(state);
        boolean[] wasLaboratory = {isLaboratory(state)};
        state.addListener(changed -> {
            boolean laboratory = isLaboratory(changed);
            boolean chosen = laboratory && !wasLaboratory[0];
            wasLaboratory[0] = laboratory;
            if (chosen && !changed.isHardcore()) {
                changed.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
                changed.setAllowCommands(true);
            }
        });
    }

    private static boolean isLaboratory(WorldCreationUiState state) {
        WorldCreationUiState.WorldTypeEntry type = state.getWorldType();
        return type != null && type.preset() != null && type.preset().is(Laboratory.PRESET);
    }
}
