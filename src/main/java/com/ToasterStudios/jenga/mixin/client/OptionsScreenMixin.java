package com.ToasterStudios.jenga.mixin.client;

import com.ToasterStudios.jenga.client.JengaSettingsScreen;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a "Jenga Blocks" button to the vanilla Options screen. Positioned at the
 * bottom-left, just left of the standard "Done" button row, so it doesn't
 * disturb vanilla layout.
 *
 * <p>The mixin extends {@link Screen} declaratively (with a no-op constructor
 * that is never invoked) so we can call {@code addDrawableChild} and read
 * {@code width}/{@code height} as if we were inside {@code OptionsScreen}.
 */
@Environment(EnvType.CLIENT)
@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin extends Screen {

    /** Required for the compiler — never actually called; runtime instance is OptionsScreen's. */
    protected OptionsScreenMixin(Text title) { super(title); }

    @Inject(method = "init", at = @At("TAIL"))
    private void jengamod$addJengaBlocksButton(CallbackInfo ci) {
        // Bottom-left, 100px wide, 20 tall. Don't overlap the centered Done button
        // (which sits roughly at width/2 - 100 .. width/2 + 100).
        int x = this.width / 2 - 215;
        int y = this.height - 27;

        // Clamp to screen on tiny windows so we don't render off-screen.
        if (x < 5) x = 5;

        ButtonWidget btn = ButtonWidget.builder(
            Text.translatable("options.jengamod.jenga"),
            button -> {
                MinecraftClient mc = MinecraftClient.getInstance();
                mc.setScreen(new JengaSettingsScreen((Screen) (Object) this));
            }
        ).dimensions(x, y, 100, 20).build();

        this.addDrawableChild(btn);
    }
}
