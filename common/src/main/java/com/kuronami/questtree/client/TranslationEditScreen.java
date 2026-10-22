package com.kuronami.questtree.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kuronami.questtree.client.editor.FieldSpec;
import com.kuronami.questtree.client.editor.TranslationKeys;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One locale's text for this pack (2026-10-22, p6-editor). FTB Quests keeps its translations in a sidecar next to the
 * quest files; KQT has a writable {@code lang/<locale>.json} overlay, so this screen edits that file and never the mod's
 * own lang files. An empty box means "no translation": the key is dropped on save and the English text is used, which
 * is also what an absent key in the overlay means.
 */
final class TranslationEditScreen extends EditorScreen {

    private final String locale;
    private final JsonObject root;
    private final List<String> keys;
    private final long revision;

    TranslationEditScreen(Screen parent, String locale) {
        super(parent, Component.translatable(PREFIX + "translation.title"));
        this.locale = locale;
        this.revision = ClientProgress.langRevision(locale);
        this.keys = TranslationKeys.collect(ClientProgress.rawChapters(), ClientProgress.rawTables(),
                ClientProgress.langs());
        JsonObject all = ClientProgress.langs();
        JsonElement mine = all.get(locale);
        this.root = mine != null && mine.isJsonObject() ? mine.getAsJsonObject().deepCopy() : new JsonObject();
    }

    @Override
    protected void build() {
        List<EditForm.Line> lines = new ArrayList<>();
        lines.add(new EditForm.HeadLine(Component.literal(locale)));
        if (keys.isEmpty()) {
            lines.add(new EditForm.CardLine(Component.translatable(PREFIX + "translation.empty"), null, List.of()));
        }
        for (String key : keys) {
            boolean missing = !root.has(key) || text(key).isEmpty();
            FieldSpec spec = FieldSpec.text("entries[" + key + "]", "");
            lines.add(new EditForm.FieldLine(font, spec, root,
                    Component.literal((missing ? "* " : "") + key),
                    () -> { }, null,
                    Component.literal("×"), () -> remove(key),
                    EditorScreen::optionName, path -> { }));
        }
        lines.add(new EditForm.CardLine(Component.translatable(PREFIX + "translation.save"), this::requestClose,
                List.of()));
        form.set(lines);
    }

    private String text(String key) {
        try {
            return root.has(key) && root.get(key).isJsonPrimitive() ? root.get(key).getAsString() : "";
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private void remove(String key) {
        root.remove(key);
        build();
    }

    @Override
    protected JsonObject content() {
        return root;
    }

    @Override
    protected void requestClose() {
        if (sending) {
            return;
        }
        JsonObject entries = new JsonObject();
        for (Map.Entry<String, JsonElement> e : new TreeMap<>(asMap(root)).entrySet()) {
            if (e.getValue().isJsonPrimitive() && !e.getValue().getAsString().isBlank()) {
                entries.add(e.getKey(), e.getValue());
            }
        }
        JsonObject args = new JsonObject();
        args.addProperty("locale", locale);
        args.add("entries", entries);
        sending = true;
        EditorSession.send(locale, "update_lang", args, revision, reply -> {
            if (reply.ok()) {
                closeNow();
            } else {
                sending = false;
                showReply(reply, locale, null);
            }
        });
    }

    private static Map<String, JsonElement> asMap(JsonObject object) {
        Map<String, JsonElement> out = new TreeMap<>();
        for (Map.Entry<String, JsonElement> e : object.entrySet()) {
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }
}
