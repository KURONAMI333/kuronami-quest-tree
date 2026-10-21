package com.kuronami.questtree.client;

/** Row positions in the chapter rail, kept free of drawing so mixed row heights can be checked. */
public final class RailMetrics {

    private RailMetrics() {
    }

    /** Index of the row at {@code y}, or -1 when it falls in the gap below the last row. */
    public static int rowAt(int[] heights, int y) {
        int top = 0;
        for (int i = 0; i < heights.length; i++) {
            if (y >= top && y < top + heights[i]) {
                return i;
            }
            top += heights[i];
        }
        return -1;
    }
}
