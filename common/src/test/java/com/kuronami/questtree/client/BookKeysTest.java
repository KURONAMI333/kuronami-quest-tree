package com.kuronami.questtree.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The duplicate action is a rebindable {@link BookKeys} mapping, not a raw key code (2026-10-23, R6 L5). Read from the
 * sources: the common test runtime has no Minecraft classes to load a mapping with, and the point is that the code
 * asks the mapping rather than the letter.
 */
class BookKeysTest {

    @Test
    void duplicateIsRegisteredAndTheScreenAsksTheMapping() throws IOException {
        String keys = source("common/src/main/java/com/kuronami/questtree/client/BookKeys.java");
        assertTrue(keys.contains("DUPLICATE = key(\"editor_duplicate\", GLFW.GLFW_KEY_D)"),
                "DUPLICATE must be registered with D as its default");

        String screen = source("common/src/main/java/com/kuronami/questtree/client/ProgressScreen.java");
        assertTrue(screen.contains("BookKeys.ctrl(BookKeys.DUPLICATE, key, scan)"),
                "the screen must ask the mapping");
        assertFalse(screen.contains("org.lwjgl.glfw.GLFW.GLFW_KEY_D)"),
                "no raw D is left in the duplicate path");
    }

    private static String source(String relative) throws IOException {
        for (String base : List.of(".", "..")) {
            Path path = Path.of(base, relative);
            if (Files.isRegularFile(path)) {
                return Files.readString(path, StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("source not found: " + relative);
    }
}
