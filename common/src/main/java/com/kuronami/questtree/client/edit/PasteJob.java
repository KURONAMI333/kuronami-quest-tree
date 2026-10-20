package com.kuronami.questtree.client.edit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One paste in flight (2026-10-20, phase 2): waits for the server's minted ids, then says which links to send.
 * <p>
 * Every create is answered separately and out of the UI's hands. A refused create is simply left out of the links;
 * the rest of the paste still lands, because the nodes are independent until their links are sent. Pure, so the
 * ordering rules are tested without a client.
 */
public final class PasteJob {

    private final EditorClipboard clipboard;
    private final Map<String, String> created = new LinkedHashMap<>();
    private final Set<String> answered = new LinkedHashSet<>();
    private final Set<String> refused = new LinkedHashSet<>();

    public PasteJob(EditorClipboard clipboard) {
        this.clipboard = clipboard;
    }

    public EditorClipboard clipboard() {
        return clipboard;
    }

    public List<EditorClipboard.Entry> entries() {
        return clipboard.entries();
    }

    /** The server accepted one node and minted its id. */
    public void created(String oldId, String newId) {
        if (newId == null || newId.isEmpty()) {
            refused.add(oldId);
        } else {
            created.put(oldId, newId);
        }
        answered.add(oldId);
    }

    /** The server refused one node; the paste goes on without it. */
    public void refused(String oldId) {
        refused.add(oldId);
        answered.add(oldId);
    }

    /** Every create has an answer, so links (if any) can be sent now. */
    public boolean complete() {
        return answered.size() >= clipboard.entries().size();
    }

    public int refusedCount() {
        return refused.size();
    }

    /** For each created node, the new ids of the copied nodes it excludes; sent as an update once all ids exist. */
    public Map<String, List<String>> resolvedExclusives() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (EditorClipboard.Link l : clipboard.internalExclusives()) {
            String from = created.get(l.from());
            String to = created.get(l.to());
            if (from != null && to != null) {
                out.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
            }
        }
        return out;
    }

    /** The links whose both ends were created, rewritten to the minted ids. */
    public List<EditorClipboard.Link> resolvedLinks() {
        List<EditorClipboard.Link> out = new ArrayList<>();
        for (EditorClipboard.Link l : clipboard.internalLinks()) {
            String from = created.get(l.from());
            String to = created.get(l.to());
            if (from != null && to != null) {
                out.add(new EditorClipboard.Link(from, to));
            }
        }
        return out;
    }
}
