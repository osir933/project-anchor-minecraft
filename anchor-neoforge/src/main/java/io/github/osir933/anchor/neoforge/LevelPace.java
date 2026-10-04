package io.github.osir933.anchor.neoforge;

import io.github.osir933.anchor.core.host.Pacer;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;

/**
 * Whether heat in a level is paused and how fast it runs, saved with the level, so that an experiment left paused
 * is still paused when the world is loaded again. Steps asked for by hand are not saved: a world loaded again does
 * not go on sending itself ahead. See {@link Pacer} for what the speed means.
 */
final class LevelPace {

    /**
     * Saves a level's pace with the level, nothing while it runs at normal speed. A speed saved outside the range
     * this version allows is brought into it.
     */
    static final IAttachmentSerializer<LevelPace> SERIALIZER = new IAttachmentSerializer<>() {
        @Override
        public LevelPace read(IAttachmentHolder holder, ValueInput input) {
            LevelPace pace = new LevelPace();
            pace.set(input.getIntOr("paused", 0) != 0,
                    Math.clamp(input.getIntOr("speed", Pacer.NORMAL_SPEED), Pacer.MIN_SPEED, Pacer.MAX_SPEED));
            return pace;
        }

        @Override
        public boolean write(LevelPace pace, ValueOutput output) {
            if (pace.isNormal()) {
                return false;
            }
            output.putInt("paused", pace.paused ? 1 : 0);
            output.putInt("speed", pace.speed);
            return true;
        }
    };

    private boolean paused;
    private int speed = Pacer.NORMAL_SPEED;

    /** Creates the pace of a level running at normal speed. */
    LevelPace() {
    }

    /**
     * Tells whether heat is paused.
     *
     * @return {@code true} if paused
     */
    boolean paused() {
        return paused;
    }

    /**
     * Returns how fast heat runs.
     *
     * @return the speed in hundredths of normal
     */
    int speed() {
        return speed;
    }

    /**
     * Remembers the pace.
     *
     * @param paused whether heat is paused
     * @param speed the speed in hundredths of normal, from {@link Pacer#MIN_SPEED} to {@link Pacer#MAX_SPEED}
     */
    void set(boolean paused, int speed) {
        if (speed < Pacer.MIN_SPEED || speed > Pacer.MAX_SPEED) {
            throw new IllegalArgumentException("no such speed: " + speed);
        }
        this.paused = paused;
        this.speed = speed;
    }

    /**
     * Tells whether there is nothing to save.
     *
     * @return {@code true} if heat runs at normal speed, unpaused
     */
    boolean isNormal() {
        return !paused && speed == Pacer.NORMAL_SPEED;
    }
}
