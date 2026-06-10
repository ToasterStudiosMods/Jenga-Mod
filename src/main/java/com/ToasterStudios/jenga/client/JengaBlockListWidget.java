package com.ToasterStudios.jenga.client;

import com.ToasterStudios.jenga.config.JengaConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.screen.narration.NarrationPart;
import net.minecraft.client.gui.widget.EntryListWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.function.Consumer;

/**
 * Scrollable list of configured Jenga blocks. Clicking an entry removes that
 * block. Backed by vanilla {@link EntryListWidget}, so mousewheel + scrollbar
 * work for free.
 */
@Environment(EnvType.CLIENT)
public class JengaBlockListWidget extends EntryListWidget<JengaBlockListWidget.BlockEntry> {

    private final Consumer<Identifier> onRemove;

    public JengaBlockListWidget(MinecraftClient client, int width, int height, int y, int itemHeight,
                                Consumer<Identifier> onRemove) {
        super(client, width, height, y, itemHeight);
        this.onRemove = onRemove;
        rebuild();
    }

    /** Replace all entries from the current config. Cheap; call after add/remove. */
    public void rebuild() {
        this.clearEntries();
        for (Identifier id : JengaConfig.getIdentifiers()) {
            this.addEntry(new BlockEntry(id));
        }
    }

    @Override
    public int getRowWidth() {
        return Math.min(this.width - 40, 280);
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        // Minimal narration — screen reader announces the screen title; entries
        // themselves don't need per-row narration for this list.
        builder.put(NarrationPart.TITLE, Text.translatable("screen.jengamod.blocks.title"));
    }

    @Environment(EnvType.CLIENT)
    public class BlockEntry extends EntryListWidget.Entry<BlockEntry> {

        private final Identifier id;

        BlockEntry(Identifier id) {
            this.id = id;
        }

        @Override
        public void render(DrawContext ctx, int index, int y, int x, int entryWidth, int entryHeight,
                           int mouseX, int mouseY, boolean hovered, float tickDelta) {
            // Subtle hover highlight so the user sees the row is interactive.
            if (hovered) {
                ctx.fill(x + 2, y, x + entryWidth - 2, y + entryHeight - 2, 0x40FFFFFF);
            }
            MinecraftClient mc = MinecraftClient.getInstance();
            int textY = y + (entryHeight - mc.textRenderer.fontHeight) / 2;
            ctx.drawTextWithShadow(mc.textRenderer, Text.literal(id.toString()),
                x + 6, textY, 0xFFFFFFFF);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0) {
                onRemove.accept(id);
                return true;
            }
            return false;
        }
    }
}
