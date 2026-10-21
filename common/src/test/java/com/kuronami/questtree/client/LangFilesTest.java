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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final Pattern TRANSLATABLE = Pattern.compile(
            "Component\\.translatable(?:WithFallback)?\\(\\s*\"([^\"]*)\"(\\s*\\+)?");
    private static final Pattern PREFIX_DECL = Pattern.compile("static final String (\\w*PREFIX) = \"([^\"]*)\"");
    private static final Pattern PREFIX_USE = Pattern.compile("(\\w*PREFIX)\\s*\\+\\s*\"([^\"]*)\"(\\s*\\+)?");

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

    /**
     * Every key the code asks for must be in en_us: a key built from a doubled prefix (2026-10-22, the restore screen
     * read {@code editor.editor.option.none}) is silently drawn as its own key. Literal keys and
     * {@code PREFIX + "suffix"} are read from the sources; keys that still need a variable are left to their own tests.
     */
    @Test
    void everyKeyUsedInCodeIsInEnglish() throws Exception {
        List<Path> sources = javaSources();
        if (sources.isEmpty()) {
            // resource-only test run (for example from a jar): the sources are not on the disk
            return;
        }
        Map<String, String> prefixes = new HashMap<>();
        for (Path file : sources) {
            Matcher m = PREFIX_DECL.matcher(read(file));
            while (m.find()) {
                prefixes.put(m.group(1), m.group(2));
            }
        }
        assertTrue(prefixes.containsKey("PREFIX"), "EditorScreen.PREFIX was not found in the sources");
        JsonObject en = read("en_us");
        List<String> missing = new ArrayList<>();
        int used = 0;
        for (Path file : sources) {
            String text = read(file);
            Matcher literal = TRANSLATABLE.matcher(text);
            while (literal.find()) {
                if (literal.group(2) != null) {
                    continue; // the key goes on with a variable
                }
                used++;
                String key = literal.group(1);
                if (!en.has(key)) {
                    missing.add(file + ":" + lineOf(text, literal.start()) + " " + key);
                }
            }
            Matcher built = PREFIX_USE.matcher(text);
            while (built.find()) {
                // a suffix that ends with a dot is itself a prefix for a variable: only the code tests cover those
                if (built.group(3) != null || built.group(2).endsWith(".")) {
                    continue;
                }
                String start = prefixes.get(built.group(1));
                if (start == null) {
                    continue;
                }
                used++;
                String key = start + built.group(2);
                if (!en.has(key)) {
                    missing.add(file + ":" + lineOf(text, built.start()) + " " + key);
                }
            }
        }
        assertTrue(used > 50, "the source scan found almost no keys: " + used);
        assertTrue(missing.isEmpty(), "keys used in code but missing from en_us.json:\n" + String.join("\n", missing));
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** The java sources of all three modules, from the module directory or the repository root. */
    private static List<Path> javaSources() throws IOException {
        Set<Path> roots = new LinkedHashSet<>();
        for (String base : List.of(".", "..")) {
            for (String module : List.of("common", "fabric", "neoforge")) {
                Path dir = Path.of(base, module, "src/main/java");
                if (Files.isDirectory(dir)) {
                    roots.add(dir.toAbsolutePath().normalize());
                }
            }
            Path direct = Path.of(base, "src/main/java");
            if (Files.isDirectory(direct)) {
                roots.add(direct.toAbsolutePath().normalize());
            }
        }
        List<Path> java = new ArrayList<>();
        for (Path root : roots) {
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(p -> p.toString().endsWith(".java")).sorted().forEach(java::add);
            }
        }
        return java;
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
