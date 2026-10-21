package com.kuronami.questtree.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

/**
 * Parses the markup FTB Quests descriptions are written in into lines of drawable tokens. The parser is plain Java, so
 * the client screen and JUnit can both use it without the game.
 * <p>
 * Besides the markup FTB Quests already writes ({@code &x} colours, {@code \n}, {@code {@pagebreak}},
 * {@code {image:path width:150 height:150}}), this understands three link tokens of its own:
 * {@code {open_url:url label}}, {@code {quest:chapter/node label}} and {@code {recipe:namespace:item label}}.
 * A token this parser does not know stays in the text as written, so a pack that uses a newer ftb-quests token loses
 * nothing (2026-10-21).
 */
final class FtbText {

    /** One run of a description line. TEXT carries markup colour codes; links carry their target. */
    record Token(Kind kind, String text, String target, int width, int height) {
        enum Kind {
            TEXT, IMAGE, OPEN_URL, QUEST, RECIPE
        }

        static Token text(String text) {
            return new Token(Kind.TEXT, text, "", 0, 0);
        }

        static Token image(String target, int width, int height) {
            return new Token(Kind.IMAGE, "", target, width, height);
        }

        static Token link(Kind kind, String target, String label) {
            return new Token(kind, label, target, 0, 0);
        }
    }

    /** One source line. A blank line is a paragraph break, not an empty drawing line. */
    record Line(List<Token> tokens) {
        boolean blank() {
            for (Token t : tokens) {
                if (t.kind() == Token.Kind.IMAGE || !t.text().isBlank()) {
                    return false;
                }
            }
            return true;
        }
    }

    static String legacyColors(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length() && s.charAt(i + 1) == '&') {
                out.append('&');
                i++;
            } else if (c == '&' && i + 1 < s.length() && "0123456789abcdefklmnor".indexOf(Character.toLowerCase(s.charAt(i + 1))) >= 0) {
                out.append('\u00a7').append(Character.toLowerCase(s.charAt(i + 1)));
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Turns a JSON text component into the same markup, keeping the clicks a description may carry. */
    static String componentMarkup(Component component) {
        StringBuilder out = new StringBuilder();
        for (Component leaf : component.toFlatList()) {
            String text = leaf.getString();
            if (text.isEmpty()) {
                continue;
            }
            ClickEvent click = leaf.getStyle().getClickEvent();
            if (click != null && click.getAction() == ClickEvent.Action.OPEN_URL) {
                out.append("{open_url:").append(click.getValue()).append(' ').append(text).append('}');
            } else if (click != null && click.getAction() == ClickEvent.Action.CHANGE_PAGE) {
                String target = click.getValue();
                int page = target.lastIndexOf('/');
                if (page > 0 && target.substring(page + 1).chars().allMatch(Character::isDigit)) {
                    target = target.substring(0, page);
                }
                out.append("{quest:").append(target).append(' ').append(text).append('}');
            } else {
                out.append(text.replace("\\&", "&").replace("&", "\\&"));
            }
        }
        return out.toString();
    }

    static List<Line> descriptionLines(String raw) {
        List<Line> out = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        List<Token> line = new ArrayList<>();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\n' || c == '\r') {
                flush(text, line);
                out.add(new Line(List.copyOf(line)));
                line.clear();
                continue;
            }
            if (c == '\\' && i + 1 < raw.length() && raw.charAt(i + 1) == 'n') {
                flush(text, line);
                out.add(new Line(List.copyOf(line)));
                line.clear();
                i++;
                continue;
            }
            if (c == '{') {
                int end = raw.indexOf('}', i + 1);
                if (end > i) {
                    String body = raw.substring(i + 1, end);
                    if (body.equals("@pagebreak")) {
                        // a page break is a paragraph break in this sheet (WP5 gives it pages)
                        flush(text, line);
                        out.add(new Line(List.copyOf(line)));
                        line.clear();
                        out.add(new Line(List.of()));
                        i = end;
                        continue;
                    }
                    Token token = token(body);
                    if (token != null) {
                        flush(text, line);
                        line.add(token);
                        i = end;
                        continue;
                    }
                }
            }
            text.append(c);
        }
        flush(text, line);
        out.add(new Line(List.copyOf(line)));
        while (!out.isEmpty() && out.get(out.size() - 1).blank()) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    private static void flush(StringBuilder text, List<Token> line) {
        if (text.isEmpty()) {
            return;
        }
        line.add(Token.text(legacyColors(text.toString())));
        text.setLength(0);
    }

    /** A brace token, or null when the braces are prose. */
    private static Token token(String body) {
        if (body.startsWith("image:")) {
            String[] parts = body.substring("image:".length()).strip().split("\\s+");
            if (parts.length == 0 || parts[0].isEmpty()) {
                return null;
            }
            int width = 0;
            int height = 0;
            for (int i = 1; i < parts.length; i++) {
                int colon = parts[i].indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String key = parts[i].substring(0, colon).toLowerCase(Locale.ROOT);
                int n = number(parts[i].substring(colon + 1));
                if (key.equals("width")) {
                    width = n;
                } else if (key.equals("height")) {
                    height = n;
                }
            }
            return Token.image(parts[0], width, height);
        }
        if (body.startsWith("open_url:")) {
            return link(Token.Kind.OPEN_URL, "open_url:", body.substring("open_url:".length()));
        }
        if (body.startsWith("quest:")) {
            return link(Token.Kind.QUEST, "quest:", body.substring("quest:".length()));
        }
        if (body.startsWith("recipe:")) {
            return link(Token.Kind.RECIPE, "recipe:", body.substring("recipe:".length()));
        }
        return null;
    }

    /** A link token's body is {@code target} or {@code target label}. */
    private static Token link(Token.Kind kind, String prefix, String rest) {
        String value = rest.strip();
        if (value.isEmpty()) {
            return null;
        }
        int split = value.indexOf(' ');
        String target = split < 0 ? value : value.substring(0, split);
        String label = split < 0 ? target : value.substring(split + 1).strip();
        return Token.link(kind, target, label.isEmpty() ? target : label);
    }

    private static int number(String value) {
        try {
            return Math.max(0, Math.min(1024, Integer.parseInt(value)));
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    private FtbText() {
    }
}
