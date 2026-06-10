package com.ToasterStudios.jenga.client;

import com.ToasterStudios.jenga.config.JengaConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Screen for editing the list of blocks the Jenga builder picks from.
 * Reached from the vanilla Options screen via {@code OptionsScreenMixin}.
 *
 * <h3>Layout</h3>
 * <pre>
 *  Title:      "Jenga Blocks"
 *  Notes:      modded-not-supported, multiplayer caveat
 *  Input:      [text field]  [Add]
 *  Suggestions: dropdown overlay below the input (when typing matches)
 *  Status:     last error / success message
 *  Hint:       "Click an entry to remove it."
 *  List:       scrollable
 *  Reset / Done: bottom buttons
 * </pre>
 */
@Environment(EnvType.CLIENT)
public class JengaBlocksScreen extends Screen {

    /** Cap on suggestion rows displayed. Vanilla command bar shows ~10; we fit fewer. */
    private static final int MAX_SUGGESTIONS = 7;

    /** Suggestion row height in pixels (font height ≈ 9 + padding). */
    private static final int SUGG_ROW_HEIGHT = 11;

    private final Screen parent;

    private BlockIdTextFieldWidget input;
    private JengaBlockListWidget list;
    private Text statusMessage = Text.empty();

    /** Active suggestions, top match first. Empty when no input or no matches. */
    private final List<Identifier> suggestions = new ArrayList<>();
    private int selectedSuggestion = 0;

    public JengaBlocksScreen(Screen parent) {
        super(Text.translatable("screen.jengamod.blocks.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;

        // ── Input row ────────────────────────────────────────────────────
        this.input = new BlockIdTextFieldWidget(
            this.textRenderer,
            centerX - 110, 60, 165, 20,
            Text.literal(""));
        this.input.setMaxLength(120);
        this.input.setPlaceholder(Text.translatable("screen.jengamod.blocks.placeholder"));
        this.input.setChangedListener(this::onInputChanged);
        this.addDrawableChild(this.input);

        this.addDrawableChild(ButtonWidget.builder(
            Text.translatable("screen.jengamod.blocks.add"),
            btn -> tryAdd()
        ).dimensions(centerX + 60, 60, 50, 20).build());

        // ── Scrollable list of configured blocks ─────────────────────────
        int listTop = 110;
        int listBottom = this.height - 32;
        int listHeight = Math.max(40, listBottom - listTop);

        this.list = new JengaBlockListWidget(
            this.client,
            this.width,
            listHeight,
            listTop,
            22,
            this::onRemove
        );
        this.addDrawableChild(this.list);

        // ── Reset to Default + Done (bottom row) ─────────────────────────
        this.addDrawableChild(ButtonWidget.builder(
            Text.translatable("screen.jengamod.blocks.reset"),
            btn -> {
                JengaConfig.resetToDefaults();
                this.statusMessage = Text.translatable("screen.jengamod.blocks.reset_done")
                    .formatted(Formatting.GRAY);
                this.list.rebuild();
            }
        ).dimensions(centerX - 155, this.height - 28, 100, 20).build());

        this.addDrawableChild(ButtonWidget.builder(
            Text.translatable("screen.jengamod.blocks.done"),
            btn -> this.client.setScreen(this.parent)
        ).dimensions(centerX - 50, this.height - 28, 100, 20).build());

        setInitialFocus(this.input);

        // Fresh init → no suggestions yet.
        this.suggestions.clear();
        this.selectedSuggestion = 0;
    }

    // ── Add / remove handlers ────────────────────────────────────────────

    private void tryAdd() {
        if (this.input == null) return;

        String raw = this.input.getText().trim();
        if (raw.isEmpty()) {
            setError("screen.jengamod.blocks.error_empty");
            return;
        }

        String full = raw.contains(":") ? raw : "minecraft:" + raw;

        Identifier id = Identifier.tryParse(full);
        if (id == null) {
            setError("screen.jengamod.blocks.error_invalid_id");
            return;
        }
        if (!JengaConfig.isValidBlock(id)) {
            setError("screen.jengamod.blocks.error_not_a_block");
            return;
        }
        if (!JengaConfig.isFullBlock(id)) {
            setError("screen.jengamod.blocks.error_not_full_block");
            return;
        }
        if (JengaConfig.contains(id)) {
            setError("screen.jengamod.blocks.error_already_added");
            return;
        }

        boolean added = JengaConfig.addBlock(id);
        if (!added) {
            setError("screen.jengamod.blocks.error_add_failed");
            return;
        }

        this.input.setText("");
        this.suggestions.clear();
        this.statusMessage = Text.translatable("screen.jengamod.blocks.added", id.toString())
            .formatted(Formatting.GREEN);
        this.list.rebuild();
    }

    private void onRemove(Identifier id) {
        JengaConfig.removeBlock(id);
        this.statusMessage = Text.translatable("screen.jengamod.blocks.removed", id.toString())
            .formatted(Formatting.GRAY);
        this.list.rebuild();
    }

    private void setError(String translationKey) {
        this.statusMessage = Text.translatable(translationKey).formatted(Formatting.RED);
    }

    // ── Suggestion logic ─────────────────────────────────────────────────

    private void onInputChanged(String newText) {
        recomputeSuggestions(newText);
    }

    private void recomputeSuggestions(String raw) {
        this.suggestions.clear();
        this.selectedSuggestion = 0;

        if (raw == null) return;
        String query = raw.trim().toLowerCase();
        if (query.isEmpty()) return;

        // Two-bucket scan: prefix matches first (after-namespace), then substring matches.
        List<Identifier> prefixHits = new ArrayList<>();
        List<Identifier> substringHits = new ArrayList<>();

        for (Identifier id : Registries.BLOCK.getIds()) {
            // Hide non-full blocks from suggestions entirely so the user
            // doesn't see them as options.
            if (!JengaConfig.isFullBlock(id)) continue;

            String full = id.toString().toLowerCase();
            String path = id.getPath().toLowerCase();

            if (full.equals(query)) {
                // Exact match → top of list, only one
                this.suggestions.clear();
                this.suggestions.add(id);
                return;
            }

            if (path.startsWith(query) || full.startsWith(query)) {
                prefixHits.add(id);
            } else if (full.contains(query)) {
                substringHits.add(id);
            }
        }

        Comparator<Identifier> alpha = Comparator.comparing(Identifier::toString);
        prefixHits.sort(alpha);
        substringHits.sort(alpha);

        for (Identifier id : prefixHits) {
            if (this.suggestions.size() >= MAX_SUGGESTIONS) return;
            this.suggestions.add(id);
        }
        for (Identifier id : substringHits) {
            if (this.suggestions.size() >= MAX_SUGGESTIONS) return;
            this.suggestions.add(id);
        }
    }

    private void cycleSuggestion(int direction) {
        if (this.suggestions.isEmpty()) return;
        int n = this.suggestions.size();
        this.selectedSuggestion = ((this.selectedSuggestion + direction) % n + n) % n;
    }

    private void applySelectedSuggestion() {
        if (this.suggestions.isEmpty()) return;
        Identifier id = this.suggestions.get(this.selectedSuggestion);
        applySuggestion(id);
    }

    private void applySuggestion(Identifier id) {
        if (this.input == null) return;
        String text = id.toString();
        this.input.setText(text);
        // Move cursor to end so the user can keep typing if they want.
        this.input.setCursor(text.length(), false);
        // Hide the dropdown after applying — the field text now exactly matches
        // a real block; further matches happen as the user keeps typing.
        this.suggestions.clear();
        this.selectedSuggestion = 0;
    }

    private boolean suggestionsVisible() {
        return !this.suggestions.isEmpty()
            && this.input != null
            && this.input.isFocused();
    }

    // ── Suggestion rendering ─────────────────────────────────────────────

    private int suggestionBoxWidth() {
        // Match the list-row width so the popup fully covers the list rows
        // behind it. The list widget renders rows up to 280px (mirrored here
        // in JengaBlockListWidget#getRowWidth), so we use the same cap.
        return Math.min(this.width - 40, 280);
    }
    private int suggestionBoxX() {
        // Centered on screen, like the list rows. Decoupled from the input
        // field's narrower X so the popup can cover what's behind it.
        return (this.width - suggestionBoxWidth()) / 2;
    }
    private int suggestionBoxY() { return this.input != null ? this.input.getY() + 20 : 0; }
    private int suggestionBoxHeight() { return this.suggestions.size() * SUGG_ROW_HEIGHT; }

    private void renderSuggestions(DrawContext ctx) {
        if (!suggestionsVisible()) return;

        int x = suggestionBoxX();
        int y = suggestionBoxY();
        int w = suggestionBoxWidth();
        int h = suggestionBoxHeight();

        // Push the matrix Z forward so the popup draws on top of every other
        // widget, including EntryListWidget which renders its rows at a
        // higher Z than plain fill() calls. Without this, list rows can
        // appear "in front of" the popup even though we render after them in
        // call order.
        ctx.getMatrices().push();
        ctx.getMatrices().translate(0f, 0f, 400f);

        // Solid black background.
        ctx.fill(x, y, x + w, y + h, 0xFF000000);

        // 1px border so it reads as a discrete popup.
        int border = 0xFF555555;
        ctx.fill(x,         y,         x + w,     y + 1,     border); // top
        ctx.fill(x,         y + h - 1, x + w,     y + h,     border); // bottom
        ctx.fill(x,         y,         x + 1,     y + h,     border); // left
        ctx.fill(x + w - 1, y,         x + w,     y + h,     border); // right

        for (int i = 0; i < this.suggestions.size(); i++) {
            Identifier id = this.suggestions.get(i);
            int rowY = y + i * SUGG_ROW_HEIGHT;
            boolean selected = i == this.selectedSuggestion;
            int color = selected ? 0xFFFFFF55 /* yellow */ : 0xFFCCCCCC /* light grey */;

            if (selected) {
                ctx.fill(x + 1, rowY, x + w - 1, rowY + SUGG_ROW_HEIGHT, 0x66FFFFFF);
            }

            ctx.drawTextWithShadow(this.textRenderer, Text.literal(id.toString()),
                x + 4, rowY + 1, color);
        }

        ctx.getMatrices().pop();
    }

    // ── Render ───────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);

        int centerX = this.width / 2;

        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, centerX, 15, 0xFFFFFF);

        ctx.drawCenteredTextWithShadow(this.textRenderer,
            Text.translatable("screen.jengamod.blocks.modded_warning")
                .formatted(Formatting.GRAY),
            centerX, 32, 0xAAAAAA);
        ctx.drawCenteredTextWithShadow(this.textRenderer,
            Text.translatable("screen.jengamod.blocks.multiplayer_warning")
                .formatted(Formatting.DARK_GRAY),
            centerX, 44, 0x888888);

        if (this.statusMessage != null && !this.statusMessage.getString().isEmpty()) {
            ctx.drawCenteredTextWithShadow(this.textRenderer, this.statusMessage, centerX, 86, 0xFFFFFF);
        }

        ctx.drawCenteredTextWithShadow(this.textRenderer,
            Text.translatable("screen.jengamod.blocks.click_to_remove")
                .formatted(Formatting.GRAY),
            centerX, 100, 0xAAAAAA);

        // Suggestions LAST so they overlay the list/widgets when typing.
        renderSuggestions(ctx);
    }

    // ── Input event interception ─────────────────────────────────────────

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // While the input is focused with suggestions showing, intercept nav keys.
        if (suggestionsVisible()) {
            // GLFW_KEY_DOWN = 264, GLFW_KEY_UP = 265, GLFW_KEY_TAB = 258
            if (keyCode == 264) { cycleSuggestion( 1); return true; }
            if (keyCode == 265) { cycleSuggestion(-1); return true; }
            if (keyCode == 258) { applySelectedSuggestion(); return true; }
        }

        if (keyCode == 257 /* GLFW_KEY_ENTER */ || keyCode == 335 /* GLFW_KEY_KP_ENTER */) {
            if (this.input != null && this.input.isFocused()) {
                tryAdd();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // If the user clicks on the suggestion overlay, autofill that row instead
        // of letting the click fall through to the list widget below it.
        if (suggestionsVisible() && button == 0) {
            int x = suggestionBoxX();
            int y = suggestionBoxY();
            int w = suggestionBoxWidth();
            int h = suggestionBoxHeight();

            if (mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h) {
                int row = (int) ((mouseY - y) / SUGG_ROW_HEIGHT);
                if (row >= 0 && row < this.suggestions.size()) {
                    applySuggestion(this.suggestions.get(row));
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(this.parent);
    }
}
