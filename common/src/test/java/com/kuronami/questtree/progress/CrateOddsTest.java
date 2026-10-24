package com.kuronami.questtree.progress;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrateOddsTest {

    @Test
    void zeroWeightIsAlwaysGranted() {
        double[] odds = CrateOdds.chances(List.of(0.0, 1.0, 1.0), 0, 1);
        assertEquals(1.0, odds[0], 1e-9);
        assertEquals(0.5, odds[1], 1e-9);
        assertEquals(0.5, odds[2], 1e-9);
    }

    @Test
    void everyWeightZeroMeansNoRollAndEverythingIsGranted() {
        double[] odds = CrateOdds.chances(List.of(0.0, 0.0), 0, 3);
        assertEquals(1.0, odds[0], 1e-9);
        assertEquals(1.0, odds[1], 1e-9);
    }

    @Test
    void severalRollsRaiseTheChanceOfAtLeastOne() {
        double[] odds = CrateOdds.chances(List.of(1.0, 1.0), 0, 3);
        assertEquals(0.875, odds[0], 1e-9);
        assertEquals(0.875, odds[1], 1e-9);
    }

    @Test
    void emptyWeightTakesItsShareOfTheDraw() {
        double[] odds = CrateOdds.chances(List.of(1.0), 3.0, 1);
        assertEquals(0.25, odds[0], 1e-9);
    }

    @Test
    void theChancesCannotExceedOneAndTheSumStaysWithinOne() {
        double[] odds = CrateOdds.chances(List.of(1.0, 2.0), 1.0, 1);
        assertEquals(0.25, odds[0], 1e-9);
        assertEquals(0.5, odds[1], 1e-9);
        assertEquals(0.75, odds[0] + odds[1], 1e-9);
        for (double odd : odds) {
            assertTrue(odd >= 0 && odd <= 1);
        }
    }

    @Test
    void noRollsLeavesOnlyTheAlwaysGrantedEntries() {
        double[] odds = CrateOdds.chances(List.of(0.0, 1.0), 0, 0);
        assertEquals(1.0, odds[0], 1e-9);
        assertEquals(0.0, odds[1], 1e-9);
    }

    @Test
    void percentKeepsOneDecimalAndTheTwoEdges() {
        assertEquals("12.5%", CrateOdds.percent(0.125));
        assertEquals("99.9%", CrateOdds.percent(0.9994));
        assertEquals("100%", CrateOdds.percent(0.9995));
        assertEquals("<0.1%", CrateOdds.percent(0.0004));
        assertEquals("0.1%", CrateOdds.percent(0.001));
        assertEquals("0.0%", CrateOdds.percent(0));
    }
}
