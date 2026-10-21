package com.kuronami.questtree.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
