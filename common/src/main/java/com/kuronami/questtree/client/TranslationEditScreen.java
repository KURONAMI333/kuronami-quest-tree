package com.kuronami.questtree.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kuronami.questtree.client.editor.EditorDoc;
import com.kuronami.questtree.client.editor.FieldSpec;
import com.kuronami.questtree.client.editor.TranslationDraft;
import com.kuronami.questtree.client.editor.TranslationKeys;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * One locale's text for this pack (2026-10-22, p6-editor). FTB Quests keeps its translations in a sidecar next to the
 * quest files; KQT has a writable {@code lang/<locale>.json} overlay, so this screen edits that file and never the mod's
 * own lang files. An empty box means "no translation": the key is dropped on save and the English text is used, which
 * is also what an absent key in the overlay means.
 * <p>
 * The working copy is {@link TranslationDraft}'s wrapper, because a locale key contains dots that a field path would
 * read as levels; each row addresses its value by its position in {@link #keys}.
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
        JsonObject localeObject = mine != null && mine.isJsonObject() ? mine.getAsJsonObject() : new JsonObject();
        this.root = TranslationDraft.wrap(localeObject, keys);
    }

    @Override
    protected void build() {
        List<EditForm.Line> lines = new ArrayList<>();
        lines.add(new EditForm.HeadLine(Component.literal(locale)));
        if (keys.isEmpty()) {
            lines.add(new EditForm.CardLine(Component.translatable(PREFIX + "translation.empty"), null, List.of()));
        }
        for (int i = 0; i < keys.size(); i++) {
            int index = i;
            String key = keys.get(index);
            String path = TranslationDraft.path(index);
            boolean missing = EditorDoc.text(root, path).isEmpty();
            FieldSpec spec = FieldSpec.text(path, "");
            lines.add(new EditForm.FieldLine(font, spec, root,
                    Component.literal((missing ? "* " : "") + key),
                    () -> { }, null,
                    Component.literal("×"), () -> remove(index),
                    EditorScreen::optionName, edited -> { }));
        }
        lines.add(new EditForm.CardLine(Component.translatable(PREFIX + "translation.save"), this::requestClose,
                List.of()));
        form.set(lines);
    }

    private void remove(int index) {
        TranslationDraft.remove(root, index);
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
        JsonObject entries = TranslationDraft.collect(root, keys);
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
}
