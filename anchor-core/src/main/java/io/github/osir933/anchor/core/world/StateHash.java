package io.github.osir933.anchor.core.world;

import io.github.osir933.anchor.core.space.SectionPos;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;

/**
 * Hashes a world's physical state so that two runs can be compared bit for bit.
 *
 * <p>The hash covers what the world is, not how it happens to be stored: blocks are run-length encoded from
 * their values, so a section held as one value and the same section held as a full array hash alike.
 * Events and the ledger are history, not state, and are left out.
 */
final class StateHash {

    private static final String FORMAT = "anchor-state-v1";

    private StateHash() {
    }

    static String of(long tick, List<String> palette, WorldSettings settings, Collection<Section> sections) {
        Digest d = new Digest();
        d.string(FORMAT);
        d.putLong(tick);
        d.putLong(settings.seed());
        d.string(settings.ambientMaterial());
        d.putDouble(settings.ambientTemperatureK());
        d.putInt(settings.maxLeaves());
        d.putInt(palette.size());
        for (String id : palette) {
            d.string(id);
        }
        d.putInt(sections.size());
        for (Section s : sections) {
            d.putLong(s.key());
            int i = 0;
            while (i < SectionPos.BLOCKS) {
                RefinedBlock block = s.refinedBlock(i);
                if (block != null) {
                    d.putByte(1);
                    d.putInt(i);
                    d.putInt(block.leafCount());
                    block.visitLive((cell, state) -> {
                        d.putInt(cell.level());
                        d.putInt(cell.mortonCode());
                        d.state(state);
                    });
                    i++;
                    continue;
                }
                int run = 1;
                while (i + run < SectionPos.BLOCKS && !s.isRefined(i + run) && s.sameBlockState(i, i + run)) {
                    run++;
                }
                d.putByte(0);
                d.putInt(run);
                d.state(s.blockState(i));
                i += run;
            }
        }
        return HexFormat.of().formatHex(d.sha.digest());
    }

    /** Feeds primitive values into SHA-256 in big-endian order. */
    private static final class Digest {
        private final MessageDigest sha;
        private final byte[] buffer = new byte[8];

        private Digest() {
            try {
                sha = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("every Java runtime must provide SHA-256", e);
            }
        }

        private void putByte(int value) {
            sha.update((byte) value);
        }

        private void putInt(int value) {
            for (int i = 0; i < 4; i++) {
                buffer[i] = (byte) (value >>> (24 - 8 * i));
            }
            sha.update(buffer, 0, 4);
        }

        private void putLong(long value) {
            for (int i = 0; i < 8; i++) {
                buffer[i] = (byte) (value >>> (56 - 8 * i));
            }
            sha.update(buffer, 0, 8);
        }

        private void putDouble(double value) {
            putLong(Double.doubleToRawLongBits(value));
        }

        private void string(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            putInt(bytes.length);
            sha.update(bytes);
        }

        private void state(CellState state) {
            putInt(state.material());
            putDouble(state.mass());
            putDouble(state.enthalpy());
            putLong(state.owner());
            putByte(state.provenance().ordinal());
        }
    }
}
