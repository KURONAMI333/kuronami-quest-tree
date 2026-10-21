package com.kuronami.questtree.network;

/**
 * Refusal lines a screen has to recognise rather than only show (2026-10-21, phase 4). The text before the colon is
 * the stable part; what follows it names the thing the author can act on.
 */
public final class EditorReplies {

    /** An export found its pack folder already there; the author can ask for it to be replaced. */
    public static final String EXPORT_FOLDER_EXISTS = "the export folder already exists";

    private EditorReplies() {
    }
}
