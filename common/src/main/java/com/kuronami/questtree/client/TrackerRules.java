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
        return card(lineH, textWidths, materialCounts, null);
    }

    /**
     * As {@link #card(int, int[], int[])}; {@code countShown[i]} false means row {@code i} has only its title line
     * (see {@link #questCountShown}), which is as tall as the icon. Null means every row shows its count line.
     */
    public static Size card(int lineH, int[] textWidths, int[] materialCounts, boolean[] countShown) {
        int matH = lineH + 2;
        int widest = 0;
        int height = 0;
        for (int i = 0; i < textWidths.length; i++) {
            widest = Math.max(widest, ICON + GAP + Math.max(0, textWidths[i]));
            int mats = i < materialCounts.length ? Math.max(0, materialCounts[i]) : 0;
            if (i > 0) {
                height += 2;
            }
            height += rowHeight(lineH, countShown == null || i >= countShown.length || countShown[i]) + matH * mats;
        }
        return new Size(widest + 2 * PAD, height + 2 * PAD);
    }

    /** A row's own height: title and count line, or just the title (never shorter than the icon). */
    public static int rowHeight(int lineH, boolean countShown) {
        return countShown ? lineH * 2 : Math.max(lineH, ICON);
    }

    /**
     * Whether a quest's own "have/need" line is drawn. A quest with one task that is also listed as an item line
     * ("(icon) 0/1") with the same text would say the number twice, so only the item line stays.
     *
     * @param taskCount     how many tasks the quest has
     * @param questLine     the quest's count line
     * @param materialTexts the texts of the item lines drawn under the quest
     */
    public static boolean questCountShown(int taskCount, String questLine, java.util.List<String> materialTexts) {
        return !(taskCount == 1 && materialTexts.size() == 1 && materialTexts.get(0).equals(questLine));
    }
}
