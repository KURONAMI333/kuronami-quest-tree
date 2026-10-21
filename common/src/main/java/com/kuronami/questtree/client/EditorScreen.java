package com.kuronami.questtree.client;

import com.google.gson.JsonObject;
import com.kuronami.questtree.client.editor.ContentSchema;
import com.kuronami.questtree.client.editor.EditorDoc;
import com.kuronami.questtree.client.editor.FieldSpec;
import com.kuronami.questtree.client.editor.ValidationPaths;
import com.kuronami.questtree.network.EditorReplyPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The frame every editing screen shares (2026-10-21, phase 3): a tall panel, a tick to save and a cross to leave, and
 * one scrolling {@link EditForm}. A screen only says which JSON object it edits and what to do on save; the field rows,
 * pickers, validator sentences and paging live here so no screen invents its own.
 * <p>
 * Text is labels and values only: no line explains what a field means, and a refusal is the validator's own sentence
 * under the row it named.
 */
abstract class EditorScreen extends Screen {

    protected static final String PREFIX = "screen.kuronami_quest_tree.editor.";
    private static final int PANEL_MAX = 460;
    private static final int HEADER = 26;

    protected final Screen parent;
    protected final EditForm form = new EditForm();
    protected int px;
    protected int py;
    protected int pw;
    protected int ph;
    private int bodyTop;
    private int bodyBottom;
    private Component status;
    private boolean statusError;
    /** The working copy as it stood when a refusal was shown; see {@link #cancel()}. */
    private JsonObject refusedContent;
    /** One save at a time: the tick, Escape and the server's own tick must not mint two writes for one screen. */
    protected boolean sending;

    EditorScreen(Screen parent, Component title) {
        super(title);
        this.parent = parent;
    }

    /** Fills the form for the current JSON; called on open and after a row changes the shape of the data. */
    protected abstract void build();

    /** Save and leave, or leave when there is nothing to save. */
    protected void requestClose() {
        closeNow();
    }

    /** The cross and Escape: the same as a save for a form, a plain leave for one that creates on a button. */
    protected void cancel() {
        if (sending) {
            return;
        }
        if (statusError) {
            // a refusal is on screen: press leave again to throw that form away, but a form the author changed since
            // the refusal is saved instead of being lost with it
            if (EditorDoc.sameContent(refusedContent, content())) {
                closeNow();
            } else {
                clearStatus();
                requestClose();
            }
            return;
        }
        requestClose();
    }

    protected final void closeNow() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    protected final void setStatus(Component text, boolean error) {
        status = text;
        statusError = error;
    }

    protected final void clearStatus() {
        status = null;
        statusError = false;
    }

    /** The working copy this screen writes; a refusal's snapshot is compared with it on the next leave attempt. */
    protected JsonObject content() {
        return null;
    }

    @Override
    protected void init() {
        pw = Math.min(width - 16, PANEL_MAX);
        ph = height - 16;
        px = (width - pw) / 2;
        py = 8;
        bodyTop = py + HEADER + 4;
        bodyBottom = py + ph - 16;
        form.layout(px + 8, bodyTop, pw - 16, bodyBottom - bodyTop);
        build();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        g.fill(0, 0, width, height, Ui.GROUND);
        Ui.panel(g, px, py, pw, ph, Ui.PANEL);
        Ui.outline(g, px, py, pw, ph, Ui.R2, Ui.HAIRLINE);
        Ui.text(g, font, font.plainSubstrByWidth(title.getString(), pw - 70), px + 10, py + 9, Ui.BRIGHT);
        int cross = px + pw - 10 - 18;
        int tick = cross - 20;
        renderHeaderButton(g, tick, py + 4, "tick", tickHit(mouseX, mouseY, true));
        renderHeaderButton(g, cross, py + 4, "cross", tickHit(mouseX, mouseY, false));
        form.render(g, font, mouseX, mouseY);
        if (status != null) {
            Ui.text(g, font, font.plainSubstrByWidth(status.getString(), pw - 20), px + 10, py + ph - 12,
                    statusError ? Ui.TEXT_ERROR : Ui.MUTED);
        }
    }

    private boolean tickHit(double mx, double my, boolean tick) {
        int cross = px + pw - 10 - 18;
        int x = tick ? cross - 20 : cross;
        return mx >= x && mx < x + 18 && my >= py + 4 && my < py + 22;
    }

    private void renderHeaderButton(GuiGraphics g, int x, int y, String icon, boolean over) {
        Ui.surface(g, x, y, 18, 18, Ui.R1, over ? Ui.OVERLAY_3 : Ui.OVERLAY_2);
        Tiles.icon(g, icon, x + 4, y + 4, 10, Ui.TEXT);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            if (tickHit(mx, my, true)) {
                ProgressFeedback.click();
                requestClose();
                return true;
            }
            if (tickHit(mx, my, false)) {
                ProgressFeedback.click();
                cancel();
                return true;
            }
        }
        return form.click(mx, my, button) || super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        return form.scrolled(mx, my, dy) || super.mouseScrolled(mx, my, dx, dy);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            cancel();
            return true;
        }
        return form.key(key, scan, mods) || super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        return form.chars(c, mods) || super.charTyped(c, mods);
    }

    @Override
    public void onClose() {
        requestClose();
    }

    // ------------------------------------------------------------------ rows

    /** One row per field, in schema order; a multiline field gets a paragraph box instead of a single line. */
    protected List<EditForm.Line> fieldRows(List<FieldSpec> fields, JsonObject root) {
        List<EditForm.Line> lines = new ArrayList<>();
        for (FieldSpec spec : fields) {
            Component label = Component.translatable(spec.label());
            if (spec.kind() == FieldSpec.Kind.MULTILINE) {
                lines.add(new EditForm.TextAreaLine(font, root, spec.path(), label, path -> onEdited(path)));
                continue;
            }
            boolean table = isTableField(spec);
            lines.add(new EditForm.FieldLine(font, spec, root, label, () -> onEdited(spec.path()),
                    spec.picker() || table ? () -> openPicker(spec, root) : null,
                    null, null, EditorScreen::optionName, path -> onEdited(path)));
        }
        return lines;
    }

    /** A shape or a row changed; screens that care rebuild, the rest only redraw. */
    protected void onEdited(String path) {
    }

    private static boolean isTableField(FieldSpec spec) {
        return spec.kind() == FieldSpec.Kind.TEXT && spec.path().endsWith("table");
    }

    protected static Component optionName(String id) {
        if (id == null || id.isEmpty()) {
            return Component.translatable(PREFIX + "option.unset");
        }
        return Component.translatableWithFallback(PREFIX + "option." + id, id);
    }

    protected final void openPicker(FieldSpec spec, JsonObject root) {
        List<EditPickers.Option> options = isTableField(spec) ? tableOptions() : EditPickers.options(spec.kind());
        if (minecraft != null) {
            minecraft.setScreen(new EditPickerScreen(this, Component.translatable(spec.label()), options, true, id -> {
                EditorDoc.write(root, spec, id);
                form.refresh();
                onEdited(spec.path());
            }));
        }
    }

    private static List<EditPickers.Option> tableOptions() {
        List<EditPickers.Option> out = new ArrayList<>();
        for (String id : ContentSchema.tableIds(ClientProgress.rawTables())) {
            out.add(new EditPickers.Option(id, Component.literal(id), ItemStack.EMPTY));
        }
        return out;
    }

    // ------------------------------------------------------------------ replies

    /**
     * Show the validator's sentences under their rows, and the first line in the panel's status. A stale refusal is
     * not a value the author can fix here: the server is already sending the new bundle, so the screen leaves and the
     * map's notice carries the sentence.
     */
    protected final void showReply(EditorReplyPayload reply, String chapterId, String nodeId) {
        form.clearErrors();
        String first = "";
        boolean stale = false;
        for (String line : reply.lines()) {
            if (line == null || line.isBlank()) {
                continue;
            }
            if (first.isEmpty()) {
                first = line;
            }
            if (line.contains("changed while you were editing") || line.contains("reload it and try again")) {
                stale = true;
            }
            String field = ValidationPaths.fieldFor(line, chapterId, nodeId, form.fieldIds());
            if (field != null) {
                form.setError(field, ValidationPaths.sentenceOf(line));
            }
        }
        if (stale) {
            setStatus(Component.literal(first), true);
            closeNow();
            return;
        }
        if (!first.isEmpty()) {
            JsonObject copy = content();
            refusedContent = copy == null ? null : copy.deepCopy();
        }
        setStatus(first.isEmpty() ? null : Component.literal(first), true);
    }
}
