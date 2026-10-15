package com.kuronami.questtree.client;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Minimal handling of FTB Quests description markup for the read-only view: {@code &x} colour codes become section
 * codes, {@code \n} and page breaks become line breaks, and {@code {image:...}} tags are left out for now.
 */
final class FtbText {

    private static final Pattern COLOR = Pattern.compile("(?<!\\\\)&([0-9a-fk-or])");
    private static final Pattern IMAGE = Pattern.compile("\\{image:[^}]*}");

    private FtbText() {
    }

    static String legacyColors(String s) {
        return COLOR.matcher(s).replaceAll("§$1").replace("\\&", "&");
    }

    static List<String> descriptionLines(String raw) {
        List<String> out = new ArrayList<>();
        for (String line : raw.split("\n")) {
            String l = IMAGE.matcher(line).replaceAll("").replace("{@pagebreak}", "");
            for (String part : l.split("\\\\n")) {
                out.add(legacyColors(part));
            }
        }
        while (!out.isEmpty() && out.get(out.size() - 1).isBlank()) {
            out.remove(out.size() - 1);
        }
        return out;
    }
}
