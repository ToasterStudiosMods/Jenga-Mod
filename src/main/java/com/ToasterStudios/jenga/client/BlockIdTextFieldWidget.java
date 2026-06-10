package com.ToasterStudios.jenga.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/**
 * Text field that auto-converts space to underscore as the user types — a
 * tiny convenience for entering block IDs ({@code stripped oak log} →
 * {@code stripped_oak_log}). Pasted text is unchanged; only character input
 * is intercepted.
 */
@Environment(EnvType.CLIENT)
public class BlockIdTextFieldWidget extends TextFieldWidget {

    public BlockIdTextFieldWidget(TextRenderer textRenderer, int x, int y, int width, int height, Text message) {
        super(textRenderer, x, y, width, height, message);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (chr == ' ') chr = '_';
        return super.charTyped(chr, modifiers);
    }
}
