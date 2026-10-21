package com.kuronami.questtree.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The description markup the details sheet draws, including the three link tokens and FTB Quests' image token. */
class FtbTextTest {

    private static List<FtbText.Token> tokens(String raw) {
        return FtbText.descriptionLines(raw).stream().flatMap(l -> l.tokens().stream()).toList();
    }

    @Test
    void colorsAndLineBreaks() {
        assertEquals("§aGreen", FtbText.legacyColors("&aGreen"));
        assertEquals("A & B", FtbText.legacyColors("A \\& B"));
        List<FtbText.Line> lines = FtbText.descriptionLines("one\\ntwo\nthree");
        assertEquals(3, lines.size());
        assertEquals("one", lines.get(0).tokens().get(0).text());
        assertEquals("three", lines.get(2).tokens().get(0).text());
    }

    @Test
    void imageKeepsItsSizeAndUnknownPropertiesDoNotCostIt() {
        FtbText.Token image = tokens("before {image:test:textures/gui/pic.png width:150 height:90 align:center} after").stream()
                .filter(t -> t.kind() == FtbText.Token.Kind.IMAGE).findFirst().orElseThrow();
        assertEquals("test:textures/gui/pic.png", image.target());
        assertEquals(150, image.width());
        assertEquals(90, image.height());
    }

    @Test
    void anImageTargetMustBeANamespacedTexturePath() {
        assertTrue(tokens("before {image:test:textures/gui/pic.png width:20} after").stream()
                .anyMatch(t -> t.kind() == FtbText.Token.Kind.IMAGE));
        for (String bad : List.of(
                "{image:https://example.com/pic.png}",
                "{image:test:\\pic.png}",
                "{image:/test:pic.png}",
                "{image:Test:pic.png}",
                "{image:test:}")) {
            assertTrue(tokens("x " + bad + " y").stream().noneMatch(t -> t.kind() == FtbText.Token.Kind.IMAGE),
                    "not an image: " + bad);
        }
    }

    @Test
    void linksCarryTargetAndLabel() {
        List<FtbText.Token> t = tokens("{open_url:https://example.com docs}{quest:two/iron The Iron Age}{recipe:minecraft:iron_ingot Iron}");
        assertEquals(FtbText.Token.Kind.OPEN_URL, t.get(0).kind());
        assertEquals("https://example.com", t.get(0).target());
        assertEquals("docs", t.get(0).text());
        assertEquals(FtbText.Token.Kind.QUEST, t.get(1).kind());
        assertEquals("two/iron", t.get(1).target());
        assertEquals("The Iron Age", t.get(1).text());
        assertEquals(FtbText.Token.Kind.RECIPE, t.get(2).kind());
        assertEquals("minecraft:iron_ingot", t.get(2).target());
    }

    @Test
    void pageBreakIsAParagraphAndTrailingBlankLinesGo() {
        List<FtbText.Line> lines = FtbText.descriptionLines("first {@pagebreak} second\\n\\n");
        assertEquals(3, lines.size());
        assertTrue(lines.get(1).blank());
        assertEquals(" second", lines.get(2).tokens().get(0).text());
    }

    @Test
    void unknownBracesStayProse() {
        assertEquals("use {unknown:thing} here", tokens("use {unknown:thing} here").get(0).text());
    }

    @Test
    void jsonLinesAreResolvedOneByOne() {
        String raw = "plain &aone\n{\"text\":\"json\"}\n[{\"text\":\"array\"}]\nlast";
        String out = FtbText.resolveJsonLines(raw, line ->
                line.startsWith("{\"text\"") || line.startsWith("[{") ? "<component>" : null);
        assertEquals("plain &aone\n<component>\n<component>\nlast", out);
    }

    @Test
    void aLineThatIsOnlyPartlyJsonStaysAsWritten() {
        String raw = "before {\"text\":\"json\"} after";
        assertEquals(raw, FtbText.resolveJsonLines(raw, line -> "<component>"));
    }

    @Test
    void bracketedPlainTextIsNotAnArray() {
        String raw = "[Optional]\n[{\"text\":\"array\"}]\nplain [brackets]";
        String out = FtbText.resolveJsonLines(raw, line -> line.startsWith("[{") ? "<component>" : null);
        assertEquals("[Optional]\n<component>\nplain [brackets]", out);
    }

    @Test
    void aComponentFormattedOverLinesIsResolvedWhole() {
        String array = "[\n  {\"text\": \"one\"},\n  {\"text\": \"two\"}\n]";
        assertEquals("<component>", FtbText.resolveJsonLines(array,
                text -> text.startsWith("[") && text.indexOf('\n') >= 0 ? "<component>" : null));
        String object = "{\n  \"text\": \"one\",\n  \"color\": \"red\"\n}";
        assertEquals("<component>", FtbText.resolveJsonLines(object,
                text -> text.startsWith("{") && text.indexOf('\n') >= 0 ? "<component>" : null));
    }

    @Test
    void wrappingKeepsTheColourAndBreaksLongWords() {
        assertEquals(List.of("§aone ", "§atwo ", "§athree"),
                FtbText.wrap("§aone two three", 5, 5, FtbTextTest::visibleWidth));
        assertEquals(List.of("§labcd", "§lefgh", "§lij"),
                FtbText.wrap("§labcdefghij", 4, 4, FtbTextTest::visibleWidth));
    }

    @Test
    void onlyAWordThatFitsAWholeLineMovesDown() {
        assertTrue(FtbText.movesFirstWordDown(8, "abcd x", 10, FtbTextTest::visibleWidth),
                "a short word that does not fit the rest of the line moves down");
        assertFalse(FtbText.movesFirstWordDown(8, "abcdefghijkl ", 10, FtbTextTest::visibleWidth),
                "a word wider than a whole line is broken where it stands");
        assertFalse(FtbText.movesFirstWordDown(0, "abcdefghijkl ", 10, FtbTextTest::visibleWidth),
                "nothing moves at the start of a line");
        assertFalse(FtbText.movesFirstWordDown(2, "ab cd", 10, FtbTextTest::visibleWidth),
                "a word that fits the rest of the line stays");
    }

    private static int visibleWidth(String s) {
        return s.replaceAll("§.", "").length();
    }
}
