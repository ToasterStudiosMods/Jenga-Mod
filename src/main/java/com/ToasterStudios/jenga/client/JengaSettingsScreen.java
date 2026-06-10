package com.ToasterStudios.jenga.client;

import com.ToasterStudios.jenga.config.JengaConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

import java.util.Locale;

/**
 * Top-level Jenga settings screen, reached from the vanilla Options screen.
 * Hosts the four behavioural toggles + the Jenga Blocks sub-button.
 */
@Environment(EnvType.CLIENT)
public class JengaSettingsScreen extends Screen {

    private static final int ROW_HEIGHT = 24;
    private static final int BUTTON_WIDTH = 200;
    private static final int BUTTON_HEIGHT = 20;

    private final Screen parent;

    public JengaSettingsScreen(Screen parent) {
        super(Text.translatable("screen.jengamod.settings.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        // Row count is dynamic: the Wobble Time slider is hidden when Ignore
        // Wobbling is on. Always 8 toggles/sliders + 1 Jenga Blocks button.
        int rowCount = 8 + (JengaConfig.isIgnoreWobbling() ? 0 : 1);
        int firstRowY = Math.max(36, this.height / 2 - (rowCount * ROW_HEIGHT) / 2);
        int rowY = firstRowY;

        // Row — Turn Enforcement
        addToggle(centerX, rowY,
            this::buildTurnEnforcementText,
            JengaConfig::isTurnEnforcementEnabled,
            JengaConfig::setTurnEnforcementEnabled);
        rowY += ROW_HEIGHT;

        // Row — Lazy Drag
        addToggle(centerX, rowY,
            this::buildLazyDragText,
            JengaConfig::isLazyDragEnabled,
            JengaConfig::setLazyDragEnabled);
        rowY += ROW_HEIGHT;

        // Row — Top 3 Layers Protected
        addToggle(centerX, rowY,
            this::buildTopLayersText,
            JengaConfig::isTopLayersProtectionEnabled,
            JengaConfig::setTopLayersProtectionEnabled);
        rowY += ROW_HEIGHT;

        // Row — Fall Detection ON/OFF
        addToggle(centerX, rowY,
            this::buildFallDetectionText,
            JengaConfig::isFallDetectionEnabled,
            JengaConfig::setFallDetectionEnabled);
        rowY += ROW_HEIGHT;

        // Row — Fall Sensitivity slider
        addFallSensitivitySlider(centerX, rowY);
        rowY += ROW_HEIGHT;

        // Row — Placement Sensitivity slider
        addPlacementSensitivitySlider(centerX, rowY);
        rowY += ROW_HEIGHT;

        // Row — Ignore Wobbling. Setter triggers clearAndInit so the slot
        // for the Wobble Time slider appears/disappears.
        addToggle(centerX, rowY,
            this::buildIgnoreWobblingText,
            JengaConfig::isIgnoreWobbling,
            v -> {
                JengaConfig.setIgnoreWobbling(v);
                this.clearAndInit();
            });
        rowY += ROW_HEIGHT;

        // Row — Wobble Time (only when wobbling matters)
        if (!JengaConfig.isIgnoreWobbling()) {
            addWobbleTimeSlider(centerX, rowY);
            rowY += ROW_HEIGHT;
        }

        // Row — Jenga Blocks (sub-screen)
        this.addDrawableChild(ButtonWidget.builder(
            Text.translatable("options.jengamod.jenga_blocks"),
            btn -> this.client.setScreen(new JengaBlocksScreen(this))
        ).dimensions(centerX - BUTTON_WIDTH / 2, rowY,
                     BUTTON_WIDTH, BUTTON_HEIGHT).build());

        // Done — return to Options screen.
        this.addDrawableChild(ButtonWidget.builder(
            Text.translatable("screen.jengamod.blocks.done"),
            btn -> this.client.setScreen(this.parent)
        ).dimensions(centerX - BUTTON_WIDTH / 2, this.height - 28,
                     BUTTON_WIDTH, BUTTON_HEIGHT).build());
    }

    private void addToggle(int centerX, int y,
                           java.util.function.Supplier<Text> labelBuilder,
                           java.util.function.BooleanSupplier getter,
                           java.util.function.Consumer<Boolean> setter) {
        ButtonWidget btn = ButtonWidget.builder(
            labelBuilder.get(),
            b -> {
                setter.accept(!getter.getAsBoolean());
                b.setMessage(labelBuilder.get());
            }
        ).dimensions(centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT).build();
        this.addDrawableChild(btn);
    }

    private void addFallSensitivitySlider(int centerX, int y) {
        addSlider(centerX, y,
            JengaConfig.FALL_SENSITIVITY_MIN,
            JengaConfig.FALL_SENSITIVITY_MAX,
            JengaConfig.getFallSensitivity(),
            "screen.jengamod.settings.fall_sensitivity.label",
            JengaConfig::setFallSensitivity);
    }

    private void addPlacementSensitivitySlider(int centerX, int y) {
        addSlider(centerX, y,
            JengaConfig.PLACEMENT_SENSITIVITY_MIN,
            JengaConfig.PLACEMENT_SENSITIVITY_MAX,
            JengaConfig.getPlacementSensitivity(),
            "screen.jengamod.settings.placement_sensitivity.label",
            JengaConfig::setPlacementSensitivity);
    }

    private void addWobbleTimeSlider(int centerX, int y) {
        addSlider(centerX, y,
            JengaConfig.WOBBLE_TIME_MIN,
            JengaConfig.WOBBLE_TIME_MAX,
            JengaConfig.getWobbleTime(),
            "screen.jengamod.settings.wobble_time.label",
            JengaConfig::setWobbleTime);
    }

    /** Generic slider: maps slider value [0,1] linearly into [min,max] and
     *  updates the bound config setter live. */
    private void addSlider(int centerX, int y,
                           double min, double max, double initial,
                           String labelKey,
                           java.util.function.DoubleConsumer setter) {
        final double range = max - min;
        double normalized = (initial - min) / range;

        SliderWidget slider = new SliderWidget(
            centerX - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, BUTTON_HEIGHT,
            buildSliderLabel(labelKey, initial), normalized
        ) {
            @Override
            protected void updateMessage() {
                this.setMessage(buildSliderLabel(labelKey, toReal(this.value)));
            }

            @Override
            protected void applyValue() {
                setter.accept(toReal(this.value));
            }

            private double toReal(double v) { return min + v * range; }
        };
        this.addDrawableChild(slider);
    }

    private static Text buildSliderLabel(String key, double value) {
        return Text.translatable(key, String.format(Locale.ROOT, "%.1f", value));
    }

    private Text buildTurnEnforcementText() {
        return wrap("turn_enforcement", JengaConfig.isTurnEnforcementEnabled());
    }

    private Text buildLazyDragText() {
        return wrap("lazy_drag", JengaConfig.isLazyDragEnabled());
    }

    private Text buildTopLayersText() {
        return wrap("top_layers", JengaConfig.isTopLayersProtectionEnabled());
    }

    private Text buildFallDetectionText() {
        return wrap("fall_detection", JengaConfig.isFallDetectionEnabled());
    }

    private Text buildIgnoreWobblingText() {
        return wrap("ignore_wobbling", JengaConfig.isIgnoreWobbling());
    }

    private static Text wrap(String key, boolean enabled) {
        String stateKey = enabled
            ? "screen.jengamod.settings." + key + ".on"
            : "screen.jengamod.settings." + key + ".off";
        return Text.translatable("screen.jengamod.settings." + key + ".label",
            Text.translatable(stateKey));
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(
            this.textRenderer, this.title, this.width / 2, 20, 0xFFFFFF);
    }

    @Override
    public void close() {
        if (this.client != null) this.client.setScreen(this.parent);
    }
}
