package com.kuronami.questtree.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every shipped interface language file must parse, cover every en_us key in the same order, and keep that key's
 * format placeholders exactly: a swapped {@code %s} would put the wrong value into the sentence.
 */
class LangFilesTest {

    private static final String RESOURCE_DIR = "/assets/kuronami_quest_tree/lang/";
    private static final String SOURCE_DIR = "src/main/resources/assets/kuronami_quest_tree/lang";
    private static final String COMMON_SOURCE_DIR = "common/" + SOURCE_DIR;
    private static final Pattern PLACEHOLDER = Pattern.compile("%(\\d+\\$)?[sd]");

    @Test
    void everyLocaleCoversEnglishWithTheSamePlaceholders() throws Exception {
        JsonObject en = read("en_us");
        assertTrue(!en.isEmpty(), "en_us.json is empty");
        for (String locale : locales()) {
            JsonObject lang = read(locale);
            assertEquals(new ArrayList<>(en.keySet()), new ArrayList<>(lang.keySet()),
                    locale + " must have every en_us key, in the same order");
            for (Map.Entry<String, JsonElement> e : en.entrySet()) {
                assertEquals(placeholders(e.getValue().getAsString()),
                        placeholders(lang.get(e.getKey()).getAsString()),
                        locale + " placeholders differ for " + e.getKey());
            }
        }
    }

    /** Every file in the source folder, so a new locale cannot be added without being covered here. */
    private static List<String> locales() throws IOException {
        for (String dir : List.of(SOURCE_DIR, COMMON_SOURCE_DIR)) {
            Path path = Path.of(dir);
            if (!Files.isDirectory(path)) {
                continue;
            }
            try (Stream<Path> files = Files.list(path)) {
                List<String> found = new ArrayList<>();
                files.filter(p -> p.getFileName().toString().endsWith(".json"))
                        .forEach(p -> found.add(p.getFileName().toString().replaceFirst("\\.json$", "")));
                found.sort(String::compareTo);
                assertTrue(found.contains("en_us"), dir + " has no en_us.json");
                return found;
            }
        }
        // resource-only test run (for example from a jar): the shipped set
        return List.of("en_us", "ja_jp", "de_de", "fr_fr", "es_es", "zh_cn", "ru_ru", "pt_br", "ko_kr");
    }

    private static JsonObject read(String locale) throws IOException {
        try (InputStream in = LangFilesTest.class.getResourceAsStream(RESOURCE_DIR + locale + ".json")) {
            assertNotNull(in, "missing language file: " + locale);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static List<String> placeholders(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }
}
