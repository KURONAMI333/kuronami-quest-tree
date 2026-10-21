package com.kuronami.questtree.client.editor;

import java.util.List;

/**
 * Turns a validator line into the form row it is about (2026-10-21, phase 3). The validator names a path such as
 * {@code chapters.basics.nodes.pick.tasks[1].item}; the form names its rows {@code tasks[1].item}. A line that does not
 * name a row stays a notice, never a guess.
 */
public final class ValidationPaths {

    private ValidationPaths() {
    }

    /**
     * @param message   one line from {@code EditorReplyPayload.lines()}
     * @param chapterId the chapter or table being edited
     * @param nodeId    the quest node, or null when editing a chapter/table
     * @param fieldIds  row ids in the open form
     * @return the best matching row id, or null
     */
    public static String fieldFor(String message, String chapterId, String nodeId, List<String> fieldIds) {
        if (message == null || fieldIds == null || fieldIds.isEmpty()) {
            return null;
        }
        String path = pathOf(message);
        if (path == null) {
            return null;
        }
        String local = local(chapterId);
        String relative = null;
        if (nodeId != null && !nodeId.isEmpty()) {
            String prefix = "chapters." + local + ".nodes." + nodeId + ".";
            if (path.startsWith(prefix)) {
                relative = path.substring(prefix.length());
            }
        }
        if (relative == null) {
            String chapterPrefix = "chapters." + local + ".";
            String tablePrefix = "reward_tables." + local + ".";
            if (path.startsWith(chapterPrefix)) {
                relative = path.substring(chapterPrefix.length());
            } else if (path.startsWith(tablePrefix)) {
                relative = path.substring(tablePrefix.length());
            }
        }
        if (relative == null) {
            return null;
        }
        String best = null;
        for (String field : fieldIds) {
            if (field == null || field.isEmpty()) {
                continue;
            }
            boolean matches = relative.equals(field) || relative.startsWith(field + ".")
                    || relative.startsWith(field + "[") || field.startsWith(relative + ".");
            if (matches && (best == null || field.length() > best.length())) {
                best = field;
            }
        }
        return best;
    }

    /** The JSON path a validator line names: {@code file:12 chapters.a.nodes.b.tasks[0] - text} gives the middle part. */
    public static String pathOf(String message) {
        if (message == null) {
            return null;
        }
        int split = message.indexOf(" - ");
        if (split < 0) {
            return null;
        }
        String left = message.substring(0, split).trim();
        int space = left.lastIndexOf(' ');
        String path = space < 0 ? left : left.substring(space + 1);
        return path.isEmpty() || path.equals("-") ? null : path;
    }

    /** The sentence after the path, for showing next to a field. */
    public static String sentenceOf(String message) {
        if (message == null) {
            return "";
        }
        int split = message.indexOf(" - ");
        return split < 0 ? message : message.substring(split + 3);
    }

    private static String local(String id) {
        if (id == null) {
            return "";
        }
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }
}
