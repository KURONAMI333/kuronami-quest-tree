package com.kuronami.questtree.progress;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parser checks for FTB Filter System smart filter expressions (no game needed). */
class ItemFilterTest {

    private static ItemFilter.Node only(String expr) {
        ItemFilter f = ItemFilter.parse(expr);
        assertTrue(f.isValid(), () -> expr + ": " + f.error());
        assertEquals(1, f.root().children().size(), expr);
        return f.root().children().get(0);
    }

    @Test
    void itemTagWithPrefix() {
        ItemFilter.Node n = only("ftbfiltersystem:item_tag(integrateddynamics:menril_logs)");
        assertEquals(ItemFilter.Node.Kind.ITEM_TAG, n.kind());
        assertEquals("item_tag", n.function());
        assertEquals("integrateddynamics:menril_logs", n.argument());
        assertTrue(ItemFilter.parse("ftbfiltersystem:item_tag(integrateddynamics:menril_logs)").unsupportedFunctions().isEmpty());
    }

    @Test
    void prefixIsOptionalAtEveryLevel() {
        String a = ItemFilter.parse("ftbfiltersystem:or(ftbfiltersystem:item(a:b)mod(c))").toString();
        String b = ItemFilter.parse("or(item(a:b)ftbfiltersystem:mod(c))").toString();
        assertEquals("or(item(a:b)mod(c))", a);
        assertEquals(a, b);
    }

    @Test
    void orOfConcatenatedItems() {
        ItemFilter.Node n = only("or(item(integratedtunnels:part_interface_energy)item(integratedtunnels:part_interface_item)item(integratedtunnels:part_interface_fluid))");
        assertEquals(ItemFilter.Node.Kind.OR, n.kind());
        assertEquals(3, n.children().size());
        assertEquals(List.of("integratedtunnels:part_interface_energy", "integratedtunnels:part_interface_item", "integratedtunnels:part_interface_fluid"),
                n.children().stream().map(ItemFilter.Node::argument).toList());
        n.children().forEach(c -> assertEquals(ItemFilter.Node.Kind.ITEM, c.kind()));
    }

    @Test
    void itemOrFuzzyComponent() {
        ItemFilter.Node n = only("or(item(mysticalagriculture:entro_seeds)component(fuzzy:{\"productivebees:bee_type\":\"productivebees:entro\"}))");
        assertEquals(2, n.children().size());
        ItemFilter.Node c = n.children().get(1);
        assertEquals(ItemFilter.Node.Kind.COMPONENT, c.kind());
        assertFalse(c.strict());
        assertEquals("{\"productivebees:bee_type\":\"productivebees:entro\"}", c.data());
        assertTrue(only("component(strict:{a:1})").strict());
        // no prefix means fuzzy, as in FTB Filter System
        ItemFilter.Node plain = only("component({a:1})");
        assertFalse(plain.strict());
        assertEquals("{a:1}", plain.data());
    }

    @Test
    void nestedAndInsideOr() {
        ItemFilter.Node n = only("or(and(item_tag(curios:charm)mod(apotheosis)))");
        assertEquals(ItemFilter.Node.Kind.OR, n.kind());
        ItemFilter.Node and = n.children().get(0);
        assertEquals(ItemFilter.Node.Kind.AND, and.kind());
        assertEquals(List.of("item_tag", "mod"), and.children().stream().map(ItemFilter.Node::function).toList());
        assertEquals("apotheosis", and.children().get(1).argument());
    }

    @Test
    void blockWithEmptyArgument() {
        ItemFilter.Node n = only("or(block())");
        assertEquals(ItemFilter.Node.Kind.BLOCK, n.children().get(0).kind());
        assertEquals("", n.children().get(0).argument());
    }

    @Test
    void topLevelListIsAnImplicitAnd() {
        ItemFilter f = ItemFilter.parse("item_tag(c:ingots) mod(minecraft)");
        assertTrue(f.isValid());
        assertEquals(ItemFilter.Node.Kind.AND, f.root().kind());
        assertEquals("root", f.root().function());
        assertEquals(2, f.root().children().size());
    }

    @Test
    void whitespaceAroundTermsAndArguments() {
        assertEquals("or(item(a:b)item(c:d))", ItemFilter.parse("  or( item( a:b )  item(c:d) ) ").toString());
    }

    @Test
    void unknownFunctionsParseAndAreReported() {
        ItemFilter f = ItemFilter.parse("or(item(a:b)frobnicate(x)othermod:thing(y)custom(ev/extra))");
        assertTrue(f.isValid());
        assertEquals(Set.of("frobnicate", "othermod:thing", "custom"), f.unsupportedFunctions());
        ItemFilter.Node or = f.root().children().get(0);
        assertEquals(ItemFilter.Node.Kind.UNKNOWN, or.children().get(1).kind());
        assertEquals("x", or.children().get(1).argument());
        assertEquals(ItemFilter.Node.Kind.CUSTOM, or.children().get(3).kind());
    }

    @Test
    void comparisons() {
        ItemFilter.Node n = only("stack_size(>=16)");
        assertEquals(">=", n.op());
        assertEquals(16, n.value());
        ItemFilter.Node d = only("durability(<50%)");
        assertEquals("<", d.op());
        assertEquals(50, d.value());
        assertTrue(d.percent());
        assertEquals(ItemFilter.Node.Kind.FOOD_VALUE, only("food_value(=4)").kind());
        assertEquals(ItemFilter.Node.Kind.MAX_STACK_SIZE, only("max_stack_size(!=64)").kind());
        // percentages are only meaningful for durability
        assertFalse(ItemFilter.parse("stack_size(<50%)").isValid());
        assertFalse(ItemFilter.parse("stack_size(abc)").isValid());
        assertFalse(ItemFilter.parse("stack_size(~3)").isValid());
    }

    @Test
    void expressionWrapsASubFilter() {
        ItemFilter.Node n = only("expression(or(item(a:b)item(c:d))/My Group)");
        assertEquals(ItemFilter.Node.Kind.EXPRESSION, n.kind());
        assertEquals("My Group", n.data());
        assertEquals("or(item(a:b)item(c:d))", n.children().get(0).childrenString());
    }

    @Test
    void backslashEscapesParentheses() {
        ItemFilter.Node n = only("mod(a\\)b)");
        assertEquals("a)b", n.argument());
    }

    @Test
    void notTakesAtMostOneChild() {
        assertTrue(ItemFilter.parse("not(item(a:b))").isValid());
        assertTrue(ItemFilter.parse("not()").isValid());
        assertFalse(ItemFilter.parse("not(item(a:b)item(c:d))").isValid());
    }

    @Test
    void malformedInputNeverThrows() {
        for (String bad : new String[]{"item(minecraft:stone", "(x)", "item", ")(", "item(a:b))", "Item(a:b)",
                "item(Bad Id)", "item()", "or(item(a:b)", "@#!(x)"}) {
            ItemFilter f = assertDoesNotThrow(() -> ItemFilter.parse(bad));
            assertFalse(f.isValid(), bad);
            assertNull(f.root(), bad);
            assertTrue(f.error() != null && !f.error().isEmpty(), bad);
        }
        assertFalse(ItemFilter.parse(null).isValid());
    }

    @Test
    void deepNestingIsRejectedNotOverflowed() {
        String deep = "or(".repeat(500) + "item(a:b)" + ")".repeat(500);
        ItemFilter f = assertDoesNotThrow(() -> ItemFilter.parse(deep));
        assertFalse(f.isValid());
    }

    @Test
    void emptyExpressionIsAnEmptyAnd() {
        ItemFilter f = ItemFilter.parse("");
        assertTrue(f.isValid());
        assertTrue(f.root().children().isEmpty());
    }

    @Test
    void parsedFiltersAreCached() {
        assertSame(ItemFilter.parse("item(minecraft:stone)"), ItemFilter.parse("item(minecraft:stone)"));
    }

    @Test
    void recognisesSmartFilterItems() {
        JsonObject item = JsonParser.parseString("{\"id\":\"ftbfiltersystem:smart_filter\",\"components\":"
                + "{\"ftbfiltersystem:filter\":\"ftbfiltersystem:item_tag(integrateddynamics:menril_logs)\"}}").getAsJsonObject();
        assertTrue(ItemFilter.isFilterItem(item));
        assertEquals("ftbfiltersystem:item_tag(integrateddynamics:menril_logs)", ItemFilter.filterExpression(item));
        JsonObject plain = JsonParser.parseString("{\"id\":\"minecraft:stone\",\"count\":1}").getAsJsonObject();
        assertFalse(ItemFilter.isFilterItem(plain));
        assertNull(ItemFilter.filterExpression(plain));
        JsonObject noExpr = JsonParser.parseString("{\"id\":\"ftbfiltersystem:smart_filter\"}").getAsJsonObject();
        assertFalse(ItemFilter.isFilterItem(noExpr));
        assertFalse(ItemFilter.isFilterItem(null));
    }
}
