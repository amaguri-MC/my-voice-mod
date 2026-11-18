package net.amaguri.myvoice.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.amaguri.myvoice.client.ui.SoundPickerScreen;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;

/** 音選び画面が開いている間は HUD（チャット・ハート・ホットバー・照準）を描かない。Hud.extractRenderState は Hud 自身の宣言（apidump 確認済み）。 */
@Mixin(Hud.class)
public abstract class HudMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
			at = @At("HEAD"), cancellable = true)
	private void myvoice$hideForPicker(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo ci) {
		if (Minecraft.getInstance().gui.screen() instanceof SoundPickerScreen) {
			ci.cancel();
		}
	}
}
