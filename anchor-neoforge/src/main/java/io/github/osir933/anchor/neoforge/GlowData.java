package io.github.osir933.anchor.neoforge;

import io.github.osir933.anchor.core.host.GlowingBlock;
import io.github.osir933.anchor.core.physics.thermal.Incandescence;
import io.github.osir933.anchor.core.space.Direction;
import io.github.osir933.anchor.core.space.SectionPos;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The glowing blocks of one section as the server sends them to players and the client draws them. Temperatures
 * travel rounded to whole kelvin, and a face as hot all over travels as one temperature.
 *
 * <p>The format, big-endian: the number of blocks as an int; then for each block its index in the section as a
 * short, its emissivity in 255ths as a byte, a byte with a bit for each face it shows and a byte with a bit for each
 * face sent spot by spot, both in {@link Direction} order; then for each face it shows, in that order, either
 * {@value GlowingBlock#SAMPLES} temperatures or one, each an unsigned short in kelvin, 0 for none.
 */
final class GlowData {

    /** The data of a section in which nothing glows. */
    static final byte[] NONE = new byte[0];

    private static final Direction[] FACES = Direction.values();

    /** The most bytes a section's data can take: every block, every face spot by spot. */
    static final int MAX_BYTES = 4 + SectionPos.BLOCKS * (5 + FACES.length * GlowingBlock.SAMPLES * 2);

    private static final int HOTTEST_SENT_K = 0xFFFF;

    private GlowData() {
    }

    /**
     * A glowing block as it arrives.
     *
     * @param index its index in its section
     * @param emissivity the emissivity of its surface, to within 1/255
     * @param faces a bit for each face it shows, {@code 1 << face.ordinal()}
     * @param temperatures the temperatures of its faces in K, laid out as in {@link GlowingBlock}; {@link Double#NaN}
     *     where there are none
     */
    record Block(int index, double emissivity, int faces, double[] temperatures) {

        /**
         * Works out the colour of every spot of the block's faces.
         *
         * @return for each spot, laid out as in {@link GlowingBlock}, its colour and glow as by
         *     {@link Incandescence.Glow#argb()}; 0 where it does not glow
         */
        int[] colours() {
            int[] colours = new int[temperatures.length];
            for (int i = 0; i < colours.length; i++) {
                Incandescence.Glow glow = Incandescence.glow(temperatures[i], emissivity);
                colours[i] = glow.visible() ? glow.argb() : 0;
            }
            return colours;
        }

        /**
         * Tells whether every spot of a face has the same colour, so that the face can be drawn whole.
         *
         * @param colours the colours from {@link #colours()}
         * @param face the face
         * @return {@code true} if all its spots match
         */
        static boolean uniform(int[] colours, Direction face) {
            int first = colours[GlowingBlock.sample(face, 0, 0)];
            for (int s = 1; s < GlowingBlock.SAMPLES; s++) {
                if (colours[face.ordinal() * GlowingBlock.SAMPLES + s] != first) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Packs the glowing blocks of a section.
     *
     * @param blocks the blocks, as {@link io.github.osir933.anchor.core.host.HostedWorld#glowingBlocks} lists them
     * @return the data; {@link #NONE} if there are no blocks
     */
    static byte[] encode(List<GlowingBlock> blocks) {
        if (blocks.isEmpty()) {
            return NONE;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(blocks.size());
            int[] kelvin = new int[GlowingBlock.SAMPLES];
            for (GlowingBlock block : blocks) {
                double[] temperatures = block.temperatures();
                int detailed = 0;
                for (Direction face : FACES) {
                    if (block.shows(face) && !even(temperatures, face, kelvin)) {
                        detailed |= 1 << face.ordinal();
                    }
                }
                out.writeShort(block.index());
                out.writeByte((int) Math.round(Math.max(0.0, Math.min(1.0, block.emissivity())) * 255.0));
                out.writeByte(block.faces());
                out.writeByte(detailed);
                for (Direction face : FACES) {
                    if (!block.shows(face)) {
                        continue;
                    }
                    int spots = (detailed & 1 << face.ordinal()) != 0 ? GlowingBlock.SAMPLES : 1;
                    for (int s = 0; s < spots; s++) {
                        out.writeShort(kelvin(temperatures[face.ordinal() * GlowingBlock.SAMPLES + s]));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /**
     * Unpacks the glowing blocks of a section.
     *
     * @param data the data from {@link #encode}
     * @return the blocks; empty for {@link #NONE}
     * @throws IllegalArgumentException if the data is damaged
     */
    static List<Block> decode(byte[] data) {
        if (data.length == 0) {
            return List.of();
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int count = in.readInt();
            if (count < 0 || count > SectionPos.BLOCKS) {
                throw new IllegalArgumentException("not a number of blocks: " + count);
            }
            List<Block> blocks = new ArrayList<>(count);
            for (int b = 0; b < count; b++) {
                int index = in.readUnsignedShort();
                double emissivity = in.readUnsignedByte() / 255.0;
                int faces = in.readUnsignedByte();
                int detailed = in.readUnsignedByte();
                if (index >= SectionPos.BLOCKS || faces >= 1 << FACES.length || (detailed & ~faces) != 0) {
                    throw new IllegalArgumentException("damaged block " + b + ": index " + index + ", faces " + faces
                            + ", detailed " + detailed);
                }
                double[] temperatures = new double[FACES.length * GlowingBlock.SAMPLES];
                Arrays.fill(temperatures, Double.NaN);
                for (Direction face : FACES) {
                    if ((faces & 1 << face.ordinal()) == 0) {
                        continue;
                    }
                    int start = face.ordinal() * GlowingBlock.SAMPLES;
                    if ((detailed & 1 << face.ordinal()) != 0) {
                        for (int s = 0; s < GlowingBlock.SAMPLES; s++) {
                            temperatures[start + s] = temperature(in.readUnsignedShort());
                        }
                    } else {
                        Arrays.fill(temperatures, start, start + GlowingBlock.SAMPLES,
                                temperature(in.readUnsignedShort()));
                    }
                }
                blocks.add(new Block(index, emissivity, faces, temperatures));
            }
            if (in.available() > 0) {
                throw new IllegalArgumentException(in.available() + " bytes too many");
            }
            return blocks;
        } catch (IOException e) {
            throw new IllegalArgumentException("the data ends too soon", e);
        }
    }

    /** Rounds a face's temperatures as they will be sent, and tells whether they all round alike. */
    private static boolean even(double[] temperatures, Direction face, int[] kelvin) {
        int start = face.ordinal() * GlowingBlock.SAMPLES;
        boolean even = true;
        for (int s = 0; s < GlowingBlock.SAMPLES; s++) {
            kelvin[s] = kelvin(temperatures[start + s]);
            even &= kelvin[s] == kelvin[0];
        }
        return even;
    }

    private static int kelvin(double temperature) {
        if (!(temperature > 0)) {
            return 0;
        }
        return (int) Math.min(HOTTEST_SENT_K, Math.max(1, Math.round(temperature)));
    }

    private static double temperature(int kelvin) {
        return kelvin == 0 ? Double.NaN : kelvin;
    }
}
