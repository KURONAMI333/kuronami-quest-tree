package com.kuronami.questtree.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Node ids in a chapter's keys ("chapter/node"), so a selection left over from another chapter is never deleted or
 * copied here (2026-10-22). Pure so the prefix rule is tested without a client.
 */
public final class SelectionKeys {

    private SelectionKeys() {
    }

    public static List<String> idsIn(String chapterId, Collection<String> keys) {
        String prefix = chapterId + "/";
        List<String> out = new ArrayList<>();
        for (String key : keys) {
            if (key.startsWith(prefix)) {
                out.add(key.substring(prefix.length()));
            }
        }
        return out;
    }
}
