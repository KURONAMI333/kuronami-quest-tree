package com.kuronami.questtree.client.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Chapter-image aspect arithmetic (2026-10-22, p6-editor). */
class DecorationGeometryTest {

    @Test
    void aWideTextureKeepsItsRatioWhenTheHeightStays() {
        assertEquals(4.0, DecorationGeometry.widthFor(2, 64, 32), 0.001);
        assertEquals(1.0, DecorationGeometry.heightFor(2, 64, 32), 0.001);
    }

    @Test
    void aSquareTextureKeepsTheSideTheButtonDidNotChange() {
        assertEquals(2.0, DecorationGeometry.widthFor(2, 16, 16), 0.001);
        assertEquals(2.0, DecorationGeometry.heightFor(2, 16, 16), 0.001);
    }

    @Test
    void aTextureWithNoUsableSizeIsLeftAlone() {
        assertEquals(2.5, DecorationGeometry.widthFor(2.5, 0, 0), 0.001);
        assertEquals(2.5, DecorationGeometry.heightFor(2.5, 0, 0), 0.001);
        assertEquals(2.5, DecorationGeometry.widthFor(2.5, -1, 8), 0.001);
    }

    @Test
    void theResultCarriesTwoDecimalsSoTheFileStaysReadable() {
        assertEquals(1.33, DecorationGeometry.widthFor(1, 4, 3), 0.0001);
        assertEquals(0.67, DecorationGeometry.heightFor(1, 3, 2), 0.0001);
    }
}
