package com.kuronami.questtree.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.kuronami.questtree.client.editor.EditorDoc;
import com.kuronami.questtree.client.editor.FieldSpec;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The editor's scrolling list of rows (2026-10-21, phase 3). Every editing screen is a list of {@link Line}s over one
 * working JSON object: a row reads and writes its own path, so the screen never mirrors values into widgets and back.
 * <p>
 * Rows are drawn with the same surfaces as the map's chrome ({@code Ui}), so a resource pack that restyles the masks
 * restyles the editor too. There is no explanatory text: a row is a label and a control, and a refused value shows
 * the validator's own sentence under its row.
 */
final class EditForm {

    static final int ROW = 18;
    private static final int LABEL_FRACTION = 38;

    /**
     * Boxes are built 40 wide, so a value set before layout scrolls to its tail; show the start until focused.
     * <p>
     * The cursor moves with the highlight: a box left at cursor-end with its highlight at 0 draws the whole value as
     * a selection, so every form read as though its text was picked out in blue (2026-10-25).
     */
    private static void fitBox(EditBox box, int width) {
        box.setWidth(width);
        if (!box.isFocused()) {
            box.setHighlightPos(0);
            box.setCursorPosition(0);
        }
    }

    /** A row of the form. Positions are handed in at draw time and remembered for the clicks that follow. */
    interface Line {
        int height();

        void render(GuiGraphics g, Font font, int x, int y, int w, int mouseX, int mouseY);

        default boolean click(double mx, double my, int button) {
            return false;
        }

        default boolean key(int key, int scan, int mods) {
            return false;
        }

        default boolean chars(char c, int mods) {
            return false;
        }

        default void blur() {
        }

        /** The row id the validator's path maps to, or null for a row that is not a field. */
        default String fieldId() {
            return null;
        }

        default void setError(String sentence) {
        }

        /** Re-reads the value from the JSON, for a picker that wrote a field while the row was already built. */
        default void refresh() {
        }
    }

    abstract static class Base implements Line {
        int ax;
        int ay;
        int aw;

        void place(int x, int y, int w) {
            ax = x;
            ay = y;
            aw = w;
        }

        boolean inside(double mx, double my, int x, int y, int w, int h) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private List<Line> lines = new ArrayList<>();
    private Line focus;
    private double scroll;
    private int vx;
    private int vy;
    private int vw;
    private int vh;

    void set(List<Line> next) {
        lines = next;
        focus = null;
    }

    void layout(int x, int y, int w, int h) {
        vx = x;
        vy = y;
        vw = w;
        vh = h;
    }

    int contentHeight() {
        int sum = 0;
        for (Line l : lines) {
            sum += l.height();
        }
        return sum;
    }

    private double maxScroll() {
        return Math.max(0, contentHeight() - vh);
    }

    void scrollToEnd() {
        scroll = maxScroll();
    }

    void render(GuiGraphics g, Font font, int mouseX, int mouseY) {
        scroll = Mth.clamp(scroll, 0, maxScroll());
        boolean bar = maxScroll() > 0;
        int w = vw - (bar ? 6 : 0);
        g.enableScissor(vx, vy, vx + vw, vy + vh);
        int y = vy - (int) scroll;
        for (Line l : lines) {
            int h = l.height();
            if (y + h > vy && y < vy + vh) {
                l.render(g, font, vx, y, w, mouseX, mouseY);
            }
            y += h;
        }
        g.disableScissor();
        if (bar) {
            double ratio = (double) vh / contentHeight();
            int thumb = Math.max(12, (int) (vh * ratio));
            int ty = vy + (int) ((vh - thumb) * (scroll / maxScroll()));
            boolean over = mouseX >= vx + vw - 5 && mouseX < vx + vw && mouseY >= vy && mouseY < vy + vh;
            Ui.surface(g, vx + vw - 4, ty, 3, thumb, Ui.CORNER, over ? Ui.SCROLLBAR_HOVER : Ui.SCROLLBAR);
        }
    }

    boolean click(double mx, double my, int button) {
        if (mx < vx || mx >= vx + vw || my < vy || my >= vy + vh) {
            return false;
        }
        int y = vy - (int) scroll;
        for (Line l : lines) {
            int h = l.height();
            if (my >= y && my < y + h) {
                if (focus != null && focus != l) {
                    focus.blur();
                }
                focus = l;
                return l.click(mx, my, button) || true;
            }
            y += h;
        }
        if (focus != null) {
            focus.blur();
            focus = null;
        }
        return true;
    }

    boolean scrolled(double mx, double my, double amount) {
        if (mx < vx || mx >= vx + vw || my < vy || my >= vy + vh) {
            return false;
        }
        scroll = Mth.clamp(scroll - amount * 24, 0, maxScroll());
        return true;
    }

    boolean key(int key, int scan, int mods) {
        if (focus != null && focus.key(key, scan, mods)) {
            return true;
        }
        if (key == GLFW.GLFW_KEY_TAB) {
            moveFocus(Screen_hasShift(mods) ? -1 : 1);
            return true;
        }
        return false;
    }

    private static boolean Screen_hasShift(int mods) {
        return (mods & GLFW.GLFW_MOD_SHIFT) != 0;
    }

    /** Tab moves between the rows that take typing; a row says so by accepting a click at its own position. */
    private void moveFocus(int step) {
        List<Line> typing = new ArrayList<>();
        for (Line l : lines) {
            if (l instanceof FieldLine f && f.typing()) {
                typing.add(l);
            }
        }
        if (typing.isEmpty()) {
            return;
        }
        int at = typing.indexOf(focus);
        int next = Math.floorMod(at + step, typing.size());
        if (focus != null) {
            focus.blur();
        }
        focus = typing.get(next);
        ((FieldLine) focus).focusBox();
        // bring the row into view
        int y = 0;
        for (Line l : lines) {
            if (l == focus) {
                break;
            }
            y += l.height();
        }
        if (y < scroll) {
            scroll = y;
        } else if (y + focus.height() > scroll + vh) {
            scroll = y + focus.height() - vh;
        }
    }

    boolean chars(char c, int mods) {
        return focus != null && focus.chars(c, mods);
    }

    /** Drops every validator sentence from the rows, before a new reply's are placed. */
    void clearErrors() {
        lines.forEach(l -> l.setError(null));
    }

    /** Puts one validator sentence under the row with this id. */
    void setError(String fieldId, String sentence) {
        for (Line line : lines) {
            if (fieldId != null && fieldId.equals(line.fieldId())) {
                line.setError(sentence);
            }
        }
    }

    /** The row ids the validator's paths can be matched against. */
    List<String> fieldIds() {
        List<String> out = new ArrayList<>();
        for (Line line : lines) {
            if (line.fieldId() != null) {
                out.add(line.fieldId());
            }
        }
        return out;
    }

    /** Re-reads every editable value, for a picker that wrote into the JSON behind the form. */
    void refresh() {
        lines.forEach(Line::refresh);
    }

    // ------------------------------------------------------------------ rows

    /** A label, a control and, under it, the sentence the validator gave for this field. */
    static final class FieldLine extends Base {
        private final FieldSpec spec;
        private final JsonObject root;
        private final Runnable changed;
        private final Component label;
        private final EditBox box;
        private final Runnable pick;
        private final Component extraLabel;
        private final Runnable extra;
        private final java.util.function.Function<String, Component> optionName;
        private String error;
        /** The box holds text the numeric field cannot write: say so instead of leaving the JSON silently behind. */
        private boolean invalidNumber;
        private boolean boxed;

        FieldLine(Font font, FieldSpec spec, JsonObject root, Component label, Runnable changed, Runnable pick,
                  Component extraLabel, Runnable extra, java.util.function.Function<String, Component> optionName,
                  Consumer<String> edited) {
            this.spec = spec;
            this.root = root;
            this.changed = changed;
            this.label = label;
            this.pick = pick;
            this.extraLabel = extraLabel;
            this.extra = extra;
            this.optionName = optionName;
            boolean text = switch (spec.kind()) {
                case BOOL, FLAG_OBJECT, ENUM -> false;
                default -> true;
            };
            if (text) {
                box = new EditBox(font, 0, 0, 40, 12, label);
                box.setBordered(false);
                box.setMaxLength(8192);
                box.setTextColor(Ui.TEXT);
                box.setValue(spec.kind() == FieldSpec.Kind.IDS ? EditorDoc.showIds(root, spec.path())
                        : spec.kind() == FieldSpec.Kind.JSON ? EditorDoc.showJson(root, spec.path())
                        : EditorDoc.show(root, spec));
                box.setResponder(v -> {
                    if (spec.kind() == FieldSpec.Kind.IDS) {
                        EditorDoc.writeIds(root, spec.path(), v);
                    } else if (spec.kind() == FieldSpec.Kind.JSON) {
                        EditorDoc.writeJson(root, spec.path(), v);
                    } else {
                        EditorDoc.write(root, spec, v);
                    }
                    invalidNumber = (spec.kind() == FieldSpec.Kind.INT || spec.kind() == FieldSpec.Kind.DOUBLE)
                            && !v.isBlank() && !numberParses(v.trim(), spec.kind() == FieldSpec.Kind.INT);
                    edited.accept(spec.path());
                });
                boxed = true;
            } else {
                box = null;
            }
        }

        boolean typing() {
            return box != null;
        }

        void focusBox() {
            if (box != null) {
                box.setFocused(true);
                // the resting box holds its cursor at the start; a row reached with Tab types at the end, as before
                box.moveCursorToEnd(false);
            }
        }

        @Override
        public String fieldId() {
            return spec.path();
        }

        @Override
        public void setError(String sentence) {
            error = sentence;
        }

        @Override
        public void refresh() {
            if (box != null && !box.isFocused()) {
                box.setValue(spec.kind() == FieldSpec.Kind.IDS ? EditorDoc.showIds(root, spec.path())
                        : spec.kind() == FieldSpec.Kind.JSON ? EditorDoc.showJson(root, spec.path())
                        : EditorDoc.show(root, spec));
            }
        }

        /** The validator's sentence for this row, or the one word for text the numeric field cannot write. */
        private String message() {
            if (error != null) {
                return error;
            }
            return invalidNumber
                    ? Component.translatable("screen.kuronami_quest_tree.editor.field.invalid").getString() : null;
        }

        /** The number the box holds is written only when it parses, the same way {@link EditorDoc#setNumber} reads it. */
        private static boolean numberParses(String value, boolean integer) {
            try {
                if (integer) {
                    Long.parseLong(value);
                } else if (!Double.isFinite(Double.parseDouble(value))) {
                    return false;
                }
                return true;
            } catch (NumberFormatException ex) {
                return false;
            }
        }

        @Override
        public int height() {
            return ROW + (message() != null ? 11 : 0);
        }

        private int labelW() {
            return aw * LABEL_FRACTION / 100;
        }

        @Override
        public void render(GuiGraphics g, Font font, int x, int y, int w, int mouseX, int mouseY) {
            place(x, y, w);
            String message = message();
            Ui.text(g, font, font.plainSubstrByWidth(label.getString(), Ui.textRoom(labelW() - 4)), x + 4, y + (ROW - 8) / 2,
                    message != null ? Ui.TEXT_ERROR : Ui.MUTED);
            int cx = x + labelW();
            int cw = w - labelW() - 4;
            switch (spec.kind()) {
                case BOOL, FLAG_OBJECT -> renderToggle(g, cx, y, mouseX, mouseY);
                case ENUM -> renderChoice(g, font, cx, y, cw, mouseX, mouseY);
                default -> renderBox(g, font, cx, y, cw, mouseX, mouseY);
            }
            if (message != null) {
                Ui.text(g, font, font.plainSubstrByWidth(message, Ui.textRoom(w - 8)), x + 4, y + ROW, Ui.TEXT_ERROR);
            }
        }

        private boolean on() {
            return Boolean.parseBoolean(EditorDoc.show(root, spec));
        }

        private void renderToggle(GuiGraphics g, int cx, int y, int mx, int my) {
            boolean on = on();
            boolean over = inside(mx, my, cx, y + 3, 26, 12);
            Ui.surface(g, cx, y + 3, 26, 12, Ui.CORNER, on ? Ui.ACCENT : over ? Ui.OVERLAY_3 : Ui.OVERLAY_2);
            Ui.surface(g, on ? cx + 15 : cx + 3, y + 5, 8, 8, Ui.CORNER, Ui.BRIGHT);
        }

        private Component optionText(String id) {
            return optionName.apply(id);
        }

        private void renderChoice(GuiGraphics g, Font font, int cx, int y, int cw, int mx, int my) {
            boolean over = inside(mx, my, cx, y + 1, cw, ROW - 2);
            Ui.surface(g, cx, y + 1, cw, ROW - 2, Ui.R1, over ? Ui.OVERLAY_2 : Ui.OVERLAY_1);
            Component name = optionText(EditorDoc.text(root, spec.path()));
            Ui.text(g, font, font.plainSubstrByWidth(name.getString(), Ui.textRoom(cw - 8)), cx + 4, y + (ROW - 8) / 2, Ui.TEXT);
        }

        private int boxLeft(int cx) {
            return cx + (spec.kind() == FieldSpec.Kind.ITEM ? 16 : 0);
        }

        private int buttons() {
            int n = 0;
            if (spec.picker() || pick != null) {
                n++;
            }
            if (extra != null) {
                n++;
            }
            return n;
        }

        private void renderBox(GuiGraphics g, Font font, int cx, int y, int cw, int mx, int my) {
            int right = cx + cw;
            int bw = buttons() * 18;
            int fieldW = cw - bw;
            boolean focused = box.isFocused();
            Ui.surface(g, cx, y + 1, fieldW, ROW - 2, Ui.R1, Ui.OVERLAY_1);
            if (focused) {
                Ui.outline(g, cx, y + 1, fieldW, ROW - 2, Ui.R1, Ui.FOCUS);
            }
            if (spec.kind() == FieldSpec.Kind.ITEM) {
                ItemStack stack = EditPickers.stackOf(EditorDoc.text(root, spec.path()));
                if (!stack.isEmpty()) {
                    g.pose().pushPose();
                    g.pose().translate(cx + 3, y + 3, 0);
                    g.pose().scale(0.75f, 0.75f, 1);
                    g.renderItem(stack, 0, 0);
                    g.pose().popPose();
                }
            }
            int bx = boxLeft(cx) + 4;
            box.setX(bx);
            box.setY(y + 3);
            fitBox(box, Math.max(8, cx + fieldW - bx - 4));
            box.render(g, mx, my, 0);
            int bxr = right - bw;
            if (spec.picker() || pick != null) {
                renderButton(g, bxr, y, mx, my, "search");
                bxr += 18;
            }
            if (extra != null) {
                renderTextButton(g, font, bxr, y, mx, my, extraLabel);
            }
        }

        private void renderButton(GuiGraphics g, int bx, int y, int mx, int my, String icon) {
            boolean over = inside(mx, my, bx + 2, y + 1, 16, ROW - 2);
            Ui.surface(g, bx + 2, y + 1, 16, ROW - 2, Ui.R1, over ? Ui.OVERLAY_3 : Ui.OVERLAY_2);
            Tiles.icon(g, icon, bx + 5, y + 4, 10, Ui.TEXT);
        }

        private void renderTextButton(GuiGraphics g, Font font, int bx, int y, int mx, int my, Component text) {
            boolean over = inside(mx, my, bx + 2, y + 1, 16, ROW - 2);
            Ui.surface(g, bx + 2, y + 1, 16, ROW - 2, Ui.R1, over ? Ui.OVERLAY_3 : Ui.OVERLAY_2);
            Ui.text(g, font, text, bx + 10 - Ui.textWidth(font, text) / 2, y + (ROW - 8) / 2, Ui.TEXT);
        }

        @Override
        public boolean click(double mx, double my, int button) {
            int cx = ax + labelW();
            int cw = aw - labelW() - 4;
            int y = ay;
            switch (spec.kind()) {
                case BOOL, FLAG_OBJECT -> {
                    if (inside(mx, my, cx, y + 3, 26, 12)) {
                        EditorDoc.write(root, spec, String.valueOf(!on()));
                        ProgressFeedback.click();
                        changed.run();
                    }
                    return true;
                }
                case ENUM -> {
                    if (inside(mx, my, cx, y + 1, cw, ROW - 2)) {
                        cycle(button == 1 ? -1 : 1);
                        ProgressFeedback.click();
                        changed.run();
                    }
                    return true;
                }
                default -> {
                    int bw = buttons() * 18;
                    int fieldW = cw - bw;
                    if (inside(mx, my, cx, y + 1, fieldW, ROW - 2)) {
                        box.setFocused(true);
                        box.mouseClicked(mx, my, button);
                        return true;
                    }
                    box.setFocused(false);
                    int bx = cx + fieldW;
                    if (spec.picker() || pick != null) {
                        if (inside(mx, my, bx, y + 1, 18, ROW - 2)) {
                            ProgressFeedback.click();
                            if (pick != null) {
                                pick.run();
                            }
                        }
                        bx += 18;
                    }
                    if (extra != null && inside(mx, my, bx, y + 1, 18, ROW - 2)) {
                        ProgressFeedback.click();
                        extra.run();
                    }
                    return true;
                }
            }
        }

        private void cycle(int step) {
            List<String> options = spec.options();
            String now = EditorDoc.text(root, spec.path());
            int at = Math.max(0, options.indexOf(now));
            String next = options.get(Math.floorMod(at + step, options.size()));
            EditorDoc.write(root, spec, next);
        }

        @Override
        public boolean key(int key, int scan, int mods) {
            return box != null && box.isFocused() && box.keyPressed(key, scan, mods);
        }

        @Override
        public boolean chars(char c, int mods) {
            return box != null && box.isFocused() && box.charTyped(c, mods);
        }

        @Override
        public void blur() {
            if (box != null) {
                box.setFocused(false);
            }
        }
    }

    /** A paragraph per box, joined with line breaks into one string: the description's shape in the data. */
    static final class TextAreaLine extends Base {
        private final Font font;
        private final JsonObject root;
        private final String path;
        private final Component label;
        private final List<EditBox> boxes = new ArrayList<>();
        private final Consumer<String> edited;
        private int focusAt = -1;
        private String error;

        TextAreaLine(Font font, JsonObject root, String path, Component label, Consumer<String> edited) {
            this.font = font;
            this.root = root;
            this.path = path;
            this.label = label;
            this.edited = edited;
            String all = readText(root, path);
            for (String part : all.split("\n", -1)) {
                boxes.add(makeBox(part));
            }
        }

        /** A string, or the pages of an array joined by line breaks (the client reads both). */
        static String readText(JsonObject root, String path) {
            JsonElement e = EditorDoc.get(root, path);
            if (e == null) {
                return "";
            }
            if (e.isJsonPrimitive()) {
                return e.getAsString();
            }
            if (e.isJsonArray()) {
                StringBuilder joined = new StringBuilder();
                for (JsonElement page : (JsonArray) e) {
                    if (page.isJsonPrimitive()) {
                        if (!joined.isEmpty()) {
                            joined.append('\n');
                        }
                        joined.append(page.getAsString());
                    }
                }
                return joined.toString();
            }
            return "";
        }

        private EditBox makeBox(String text) {
            EditBox box = new EditBox(font, 0, 0, 40, 12, label);
            box.setBordered(false);
            box.setMaxLength(2048);
            box.setTextColor(Ui.TEXT);
            box.setValue(text);
            box.setResponder(v -> write());
            return box;
        }

        private void write() {
            List<String> parts = new ArrayList<>();
            for (EditBox b : boxes) {
                parts.add(b.getValue());
            }
            String joined = String.join("\n", parts);
            // untouched text is not rewritten: an array of pages stays an array until the author edits it
            if (!joined.equals(readText(root, path))) {
                EditorDoc.setString(root, path, joined.isBlank() ? "" : stripTrailing(joined));
            }
            edited.accept(path);
        }

        private static String stripTrailing(String s) {
            int end = s.length();
            while (end > 0 && s.charAt(end - 1) == '\n') {
                end--;
            }
            return s.substring(0, end);
        }

        @Override
        public String fieldId() {
            return path;
        }

        @Override
        public void setError(String sentence) {
            error = sentence;
        }

        @Override
        public int height() {
            return ROW * boxes.size() + 2 + (error != null ? 11 : 0);
        }

        @Override
        public void render(GuiGraphics g, Font f, int x, int y, int w, int mouseX, int mouseY) {
            place(x, y, w);
            int lw = w * LABEL_FRACTION / 100;
            Ui.text(g, f, f.plainSubstrByWidth(label.getString(), Ui.textRoom(lw - 4)), x + 4, y + (ROW - 8) / 2,
                    error != null ? Ui.TEXT_ERROR : Ui.MUTED);
            int cw = w - lw - 4;
            for (int i = 0; i < boxes.size(); i++) {
                EditBox b = boxes.get(i);
                int ry = y + i * ROW;
                int fieldW = cw - (boxes.size() > 1 ? 18 : 0);
                Ui.surface(g, x + lw, ry + 1, fieldW, ROW - 2, Ui.R1, Ui.OVERLAY_1);
                if (b.isFocused()) {
                    Ui.outline(g, x + lw, ry + 1, fieldW, ROW - 2, Ui.R1, Ui.FOCUS);
                }
                b.setX(x + lw + 4);
                b.setY(ry + 3);
                fitBox(b, Math.max(8, fieldW - 8));
                b.render(g, mouseX, mouseY, 0);
                if (boxes.size() > 1) {
                    boolean over = inside(mouseX, mouseY, x + lw + fieldW, ry + 1, 18, ROW - 2);
                    Ui.surface(g, x + lw + fieldW + 2, ry + 1, 16, ROW - 2, Ui.R1, over ? Ui.OVERLAY_3 : Ui.OVERLAY_2);
                    Tiles.icon(g, "cross", x + lw + fieldW + 5, ry + 4, 10, Ui.TEXT);
                }
            }
            if (error != null) {
                Ui.text(g, f, f.plainSubstrByWidth(error, Ui.textRoom(w - 8)), x + 4, y + ROW * boxes.size() + 1, Ui.TEXT_ERROR);
            }
        }

        @Override
        public boolean click(double mx, double my, int button) {
            int lw = aw * LABEL_FRACTION / 100;
            int cw = aw - lw - 4;
            int fieldW = cw - (boxes.size() > 1 ? 18 : 0);
            for (EditBox b : boxes) {
                b.setFocused(false);
            }
            for (int i = 0; i < boxes.size(); i++) {
                int ry = ay + i * ROW;
                if (inside(mx, my, ax + lw, ry + 1, fieldW, ROW - 2)) {
                    boxes.get(i).setFocused(true);
                    boxes.get(i).mouseClicked(mx, my, button);
                    focusAt = i;
                    return true;
                }
                if (boxes.size() > 1 && inside(mx, my, ax + lw + fieldW, ry + 1, 18, ROW - 2)) {
                    boxes.remove(i);
                    focusAt = -1;
                    write();
                    return true;
                }
            }
            return true;
        }

        @Override
        public boolean key(int key, int scan, int mods) {
            if (focusAt < 0 || focusAt >= boxes.size()) {
                return false;
            }
            EditBox current = boxes.get(focusAt);
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                // the text after the cursor moves to a new paragraph under this one
                String value = current.getValue();
                int cursor = Mth.clamp(current.getCursorPosition(), 0, value.length());
                current.setValue(value.substring(0, cursor));
                current.setFocused(false);
                EditBox next = makeBox(value.substring(cursor));
                boxes.add(focusAt + 1, next);
                focusAt++;
                next.setFocused(true);
                next.moveCursorToStart(false);
                write();
                return true;
            }
            if (key == GLFW.GLFW_KEY_BACKSPACE && current.getCursorPosition() == 0 && focusAt > 0
                    && current.getHighlighted().isEmpty()) {
                EditBox before = boxes.get(focusAt - 1);
                int join = before.getValue().length();
                before.setValue(before.getValue() + current.getValue());
                boxes.remove(focusAt);
                current.setFocused(false);
                focusAt--;
                before.setFocused(true);
                before.setCursorPosition(join);
                before.setHighlightPos(join);
                write();
                return true;
            }
            return current.isFocused() && current.keyPressed(key, scan, mods);
        }

        @Override
        public boolean chars(char c, int mods) {
            return focusAt >= 0 && focusAt < boxes.size() && boxes.get(focusAt).isFocused()
                    && boxes.get(focusAt).charTyped(c, mods);
        }

        @Override
        public void blur() {
            boxes.forEach(b -> b.setFocused(false));
            focusAt = -1;
        }
    }

    /** One button on a card's header. */
    record Action(String icon, String glyph, Runnable run, boolean enabled) {
    }

    /** The band above one task, reward, entry or decoration: its name (a click changes the type) and its buttons. */
    static final class CardLine extends Base {
        private final Component title;
        private final Runnable open;
        private final List<Action> actions;
        private final String fieldId;
        private String error;

        CardLine(Component title, Runnable open, List<Action> actions) {
            this(title, open, actions, null);
        }

        /** A card can stand for the object at {@code fieldId}, so the validator's sentence gets a row to sit under. */
        CardLine(Component title, Runnable open, List<Action> actions, String fieldId) {
            this.title = title;
            this.open = open;
            this.actions = actions;
            this.fieldId = fieldId;
        }

        @Override
        public String fieldId() {
            return fieldId;
        }

        @Override
        public void setError(String sentence) {
            error = sentence;
        }

        @Override
        public int height() {
            return ROW + 6 + (error != null ? 11 : 0);
        }

        @Override
        public void render(GuiGraphics g, Font font, int x, int y, int w, int mouseX, int mouseY) {
            place(x, y, w);
            Ui.surface(g, x, y + 6, w, ROW, Ui.R1, Ui.OVERLAY_2);
            boolean over = open != null && inside(mouseX, mouseY, x, y + 6, w - actions.size() * 18, ROW);
            Ui.text(g, font, font.plainSubstrByWidth(title.getString(), Ui.textRoom(w - actions.size() * 18 - 12)), x + 6,
                    y + 6 + (ROW - 8) / 2, error != null ? Ui.TEXT_ERROR : over ? Ui.BRIGHT : Ui.TEXT);
            int bx = x + w - actions.size() * 18;
            for (Action a : actions) {
                boolean hit = a.enabled() && inside(mouseX, mouseY, bx, y + 6, 18, ROW);
                if (hit) {
                    Ui.surface(g, bx + 1, y + 7, 16, ROW - 2, Ui.R1, Ui.OVERLAY_3);
                }
                int tone = a.enabled() ? Ui.TEXT : Ui.DIM;
                if (a.icon() != null) {
                    Tiles.icon(g, a.icon(), bx + 4, y + 6 + 4, 10, tone);
                } else {
                    Ui.text(g, font, a.glyph(), bx + 9 - Ui.textWidth(font, a.glyph()) / 2, y + 6 + (ROW - 8) / 2, tone);
                }
                bx += 18;
            }
            if (error != null) {
                Ui.text(g, font, font.plainSubstrByWidth(error, Ui.textRoom(w - 8)), x + 6, y + 6 + ROW, Ui.TEXT_ERROR);
            }
        }

        @Override
        public boolean click(double mx, double my, int button) {
            int bx = ax + aw - actions.size() * 18;
            if (my < ay + 6 || my >= ay + 6 + ROW) {
                return true;
            }
            for (Action a : actions) {
                if (inside(mx, my, bx, ay + 6, 18, ROW)) {
                    if (a.enabled()) {
                        ProgressFeedback.click();
                        a.run().run();
                    }
                    return true;
                }
                bx += 18;
            }
            if (open != null) {
                ProgressFeedback.click();
                open.run();
            }
            return true;
        }
    }

    /** A full-width ghost button with a plus: add a task, a reward, an entry, a picture. */
    static final class AddLine extends Base {
        private final Component label;
        private final Runnable run;

        AddLine(Component label, Runnable run) {
            this.label = label;
            this.run = run;
        }

        @Override
        public int height() {
            return ROW + 8;
        }

        @Override
        public void render(GuiGraphics g, Font font, int x, int y, int w, int mouseX, int mouseY) {
            place(x, y, w);
            boolean over = inside(mouseX, mouseY, x, y + 4, w, ROW);
            Ui.surface(g, x, y + 4, w, ROW, Ui.R1, over ? Ui.OVERLAY_2 : Ui.OVERLAY_1);
            Tiles.icon(g, "plus", x + w / 2 - Ui.textWidth(font, label) / 2 - 14, y + 4 + 4, 10, Ui.TEXT);
            Ui.text(g, font, label, x + w / 2 - Ui.textWidth(font, label) / 2, y + 4 + (ROW - 8) / 2, Ui.TEXT);
        }

        @Override
        public boolean click(double mx, double my, int button) {
            if (inside(mx, my, ax, ay + 4, aw, ROW)) {
                ProgressFeedback.click();
                run.run();
            }
            return true;
        }
    }

    /** A quiet divider with a name, between groups of rows. */
    static final class HeadLine extends Base {
        private final Component label;

        HeadLine(Component label) {
            this.label = label;
        }

        @Override
        public int height() {
            return 16;
        }

        @Override
        public void render(GuiGraphics g, Font font, int x, int y, int w, int mouseX, int mouseY) {
            place(x, y, w);
            Ui.text(g, font, label, x + 4, y + 6, Ui.DIM);
            g.fill(x + 8 + Ui.textWidth(font, label), y + 10, x + w, y + 11, Ui.DIVIDER);
        }
    }
}
