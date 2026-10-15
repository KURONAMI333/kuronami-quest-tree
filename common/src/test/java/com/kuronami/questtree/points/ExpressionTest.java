package com.kuronami.questtree.points;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExpressionTest {

    private static double ev(String s, Map<String, Double> vars) {
        return Expression.parse(s).eval(vars);
    }

    @Test
    void curvesFromRealPacks() {
        // FTB Skies 2 / Evolution mining curve
        assertEquals(Math.pow(4, 1.5) * 100, ev("level^1.5 * 100", Map.of("level", 4.0)), 1e-9);
        assertEquals(250, ev("min(dropped_xp, 5) * 50", Map.of("dropped_xp", 9.0)), 1e-9);
    }

    @Test
    void precedenceAndAssociativity() {
        assertEquals(7, ev("1 + 2 * 3", Map.of()), 1e-9);
        assertEquals(Math.pow(2, 9), ev("2 ^ 3 ^ 2", Map.of()), 1e-9); // right associative
        assertEquals(-4, ev("-2 ^ 2", Map.of()), 1e-9);              // unary binds looser than ^ as in Pufferfish (prec 7 < 8)
        assertEquals(1, ev("1 + 1 = 2 & 3 > 2", Map.of()), 1e-9);
        assertEquals(0, ev("1 <> 1 | 2 < 1", Map.of()), 1e-9);
    }

    @Test
    void booleansAndFunctions() {
        assertEquals(1, ev("stone_like & !silk_touch", Map.of("stone_like", 1.0, "silk_touch", 0.0)), 1e-9);
        assertEquals(0, ev("stone_like & !silk_touch", Map.of("stone_like", 1.0, "silk_touch", 1.0)), 1e-9);
        assertEquals(5, ev("clamp(9, 0, 5)", Map.of()), 1e-9);
        assertEquals(2.5, ev("mix(0, 10, 0.25)", Map.of()), 1e-9);
        assertEquals(1, ev("mod(-3, 2)", Map.of()), 1e-9);
        assertEquals(Math.PI * 2, ev("tau", Map.of()), 1e-9);
        assertEquals(0, ev("unknown_var", Map.of()), 1e-9);
    }

    @Test
    void syntaxErrorsAreReported() {
        assertThrows(IllegalArgumentException.class, () -> Expression.parse("1 +"));
        assertThrows(IllegalArgumentException.class, () -> Expression.parse("min(1, 2"));
        assertThrows(IllegalArgumentException.class, () -> Expression.parse("nosuch(1)"));
    }
}
