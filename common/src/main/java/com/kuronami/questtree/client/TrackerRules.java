package com.kuronami.questtree.client;

/** Small tracker decisions and metrics, kept out of the HUD so they can be checked without a game. */
public final class TrackerRules {

    /** Jade's tooltip box: 4px padding, a 16px icon, 3px after it, and room for 110px of text. */
    public static final int PAD = 4;
    public static final int ICON = 16;
    public static final int GAP = 3;
    public static final int TEXT_W = 110;

    private TrackerRules() {
    }

    /** Auto-pin belongs to the "all" tracker; "pinned only" must stay what its name says. */
    public static boolean autoPin(boolean autoPin, boolean modeAll) {
        return autoPin && modeAll;
    }

    /** Jade's own text pitch is 10px at 100%; our text scale applies on top of it. */
    public static int lineH(float textScale) {
        // 1.2f sits just above 1.2, so nudge before rounding up (as TextFit does)
        return Math.max(10, (int) Math.ceil(10 * (double) textScale - 1e-5));
    }

    /** The tracker card's size before the player's tracker scale. */
    public record Size(int width, int height) {
    }

    /**
     * The card around the tracker's rows: each row is a 16px icon plus a 3px gap and the row's widest text, followed
     * by one line per item still needed. Two rows sit 2px apart, and the card has {@link #PAD} on every side. A row
     * always has at least one line, so a card for rows is never empty.
     */
    public static Size card(int lineH, int[] textWidths, int[] materialCounts) {
        int rowH = lineH * 2;
        int matH = lineH + 2;
        int widest = 0;
        int height = 0;
        for (int i = 0; i < textWidths.length; i++) {
            widest = Math.max(widest, ICON + GAP + Math.max(0, textWidths[i]));
            int mats = i < materialCounts.length ? Math.max(0, materialCounts[i]) : 0;
            if (i > 0) {
                height += 2;
            }
            height += rowH + matH * mats;
        }
        return new Size(widest + 2 * PAD, height + 2 * PAD);
    }
}
