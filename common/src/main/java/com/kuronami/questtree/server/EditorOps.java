package com.kuronami.questtree.server;

import com.google.gson.JsonObject;

/**
 * The two editor buttons that are not data edits (2026-10-21, phase 4): export a namespace, and reset one chapter so
 * the author can play it as a player. Both run the commands the pack already has, so there is one implementation of
 * each. The namespace is checked here before it becomes part of a command line.
 */
final class EditorOps {

    private EditorOps() {
    }

    /** The namespace a chapter id belongs to, or null when the id is not {@code <namespace>:<chapter>}. */
    static String namespaceOf(String chapterId) {
        if (!ProgressionEditor.validChapterId(chapterId)) {
            return null;
        }
        String namespace = chapterId.substring(0, chapterId.indexOf(':'));
        // the export command lower-cases what it is given; an uppercase chapter id would then match no file
        return namespace.equals(namespace.toLowerCase(java.util.Locale.ROOT))
                ? ProgressExport.validNamespace(namespace) : null;
    }

    /** {@code /quest_tree export <namespace>}, or null when the namespace is not a resource namespace. */
    static String exportCommand(String namespace) {
        String valid = ProgressExport.validNamespace(namespace);
        return valid == null ? null : "quest_tree export " + valid;
    }

    /** {@code /quest_tree reset @s <chapter>}, or null when the chapter id is not one the commands accept. */
    static String testCommand(String chapterId) {
        return ProgressionEditor.validChapterId(chapterId) ? "quest_tree reset @s " + chapterId : null;
    }

    /** The namespace an export request names: the argument, or the one its chapter belongs to. */
    static String exportNamespace(JsonObject args, String chapterId) {
        String named = args != null && args.has("namespace") && args.get("namespace").isJsonPrimitive()
                && args.getAsJsonPrimitive("namespace").isString() ? args.get("namespace").getAsString() : "";
        return ProgressExport.validNamespace(named) != null ? ProgressExport.validNamespace(named) : namespaceOf(chapterId);
    }
}
