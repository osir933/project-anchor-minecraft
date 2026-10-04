package io.github.osir933.anchor.core.instrument;

import java.util.Locale;

/**
 * A tiny font for writing on charts: capital letters, digits and a few signs, each three pixels wide and five high,
 * a pixel apart. Small letters are written as capitals, and characters the font lacks as a question mark.
 */
final class PixelFont {

    /** How high a character is, in pixels. */
    static final int HEIGHT = 5;

    /** How far apart characters start, in pixels: three for the character and one between. */
    static final int ADVANCE = 4;

    private static final int WIDTH = 3;

    /** Each character's pixels, row by row from the top, three bits a row with the leftmost highest. */
    private static final int[] GLYPHS = new int[128];

    private static final int DEGREE = glyph("##.", "##.", "...", "...", "...");

    static {
        define(' ', "...", "...", "...", "...", "...");
        define('0', "###", "#.#", "#.#", "#.#", "###");
        define('1', ".#.", "##.", ".#.", ".#.", "###");
        define('2', "###", "..#", "###", "#..", "###");
        define('3', "###", "..#", ".##", "..#", "###");
        define('4', "#.#", "#.#", "###", "..#", "..#");
        define('5', "###", "#..", "###", "..#", "###");
        define('6', "###", "#..", "###", "#.#", "###");
        define('7', "###", "..#", "..#", ".#.", ".#.");
        define('8', "###", "#.#", "###", "#.#", "###");
        define('9', "###", "#.#", "###", "..#", "###");
        define('A', ".#.", "#.#", "###", "#.#", "#.#");
        define('B', "##.", "#.#", "##.", "#.#", "##.");
        define('C', ".##", "#..", "#..", "#..", ".##");
        define('D', "##.", "#.#", "#.#", "#.#", "##.");
        define('E', "###", "#..", "##.", "#..", "###");
        define('F', "###", "#..", "##.", "#..", "#..");
        define('G', ".##", "#..", "#.#", "#.#", ".##");
        define('H', "#.#", "#.#", "###", "#.#", "#.#");
        define('I', "###", ".#.", ".#.", ".#.", "###");
        define('J', "..#", "..#", "..#", "#.#", ".#.");
        define('K', "#.#", "#.#", "##.", "#.#", "#.#");
        define('L', "#..", "#..", "#..", "#..", "###");
        define('M', "#.#", "###", "###", "#.#", "#.#");
        define('N', "##.", "#.#", "#.#", "#.#", "#.#");
        define('O', ".#.", "#.#", "#.#", "#.#", ".#.");
        define('P', "##.", "#.#", "##.", "#..", "#..");
        define('Q', ".#.", "#.#", "#.#", "##.", ".##");
        define('R', "##.", "#.#", "##.", "#.#", "#.#");
        define('S', ".##", "#..", ".#.", "..#", "##.");
        define('T', "###", ".#.", ".#.", ".#.", ".#.");
        define('U', "#.#", "#.#", "#.#", "#.#", "###");
        define('V', "#.#", "#.#", "#.#", "#.#", ".#.");
        define('W', "#.#", "#.#", "###", "###", "#.#");
        define('X', "#.#", "#.#", ".#.", "#.#", "#.#");
        define('Y', "#.#", "#.#", ".#.", ".#.", ".#.");
        define('Z', "###", "..#", ".#.", "#..", "###");
        define('.', "...", "...", "...", "...", ".#.");
        define(',', "...", "...", "...", ".#.", "#..");
        define('-', "...", "...", "###", "...", "...");
        define('+', "...", ".#.", "###", ".#.", "...");
        define(':', "...", ".#.", "...", ".#.", "...");
        define('/', "..#", "..#", ".#.", "#..", "#..");
        define('_', "...", "...", "...", "...", "###");
        define('%', "#.#", "..#", ".#.", "#..", "#.#");
        define('?', "##.", "..#", ".#.", "...", ".#.");
        define('(', ".#.", "#..", "#..", "#..", ".#.");
        define(')', ".#.", "..#", "..#", "..#", ".#.");
    }

    private PixelFont() {
    }

    private static void define(char c, String... rows) {
        GLYPHS[c] = glyph(rows);
    }

    private static int glyph(String... rows) {
        int bits = 0;
        for (String row : rows) {
            for (int i = 0; i < WIDTH; i++) {
                bits = bits << 1 | (row.charAt(i) == '#' ? 1 : 0);
            }
        }
        return bits;
    }

    /**
     * Returns how wide a text is when written.
     *
     * @param text the text
     * @return the width in pixels, from the first character's left edge to the last one's right
     */
    static int width(String text) {
        return text.isEmpty() ? 0 : text.length() * ADVANCE - 1;
    }

    /**
     * Writes a text.
     *
     * @param plot sets one pixel: x, then y
     * @param text the text
     * @param x where its left edge goes
     * @param y where its top goes
     */
    static void write(PixelSink plot, String text, int x, int y) {
        String upper = text.toUpperCase(Locale.ROOT);
        for (int k = 0; k < upper.length(); k++) {
            int bits = bits(upper.charAt(k));
            for (int row = 0; row < HEIGHT; row++) {
                for (int col = 0; col < WIDTH; col++) {
                    int bit = (HEIGHT - 1 - row) * WIDTH + (WIDTH - 1 - col);
                    if ((bits >> bit & 1) != 0) {
                        plot.set(x + k * ADVANCE + col, y + row);
                    }
                }
            }
        }
    }

    private static int bits(char c) {
        if (c == '°') {
            return DEGREE;
        }
        if (c < GLYPHS.length && (GLYPHS[c] != 0 || c == ' ')) {
            return GLYPHS[c];
        }
        return GLYPHS['?'];
    }

    /** Somewhere to write pixels. */
    @FunctionalInterface
    interface PixelSink {
        /**
         * Sets a pixel.
         *
         * @param x its column
         * @param y its row
         */
        void set(int x, int y);
    }
}
