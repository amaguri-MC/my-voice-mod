package net.amaguri.myvoice.client;

import net.amaguri.myvoice.client.audio.MicRecorder;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** 録音中の表示（画面上部の細い一行と音量バー、カウントダウン中は大きな数字）。 */
public final class RecordHud implements HudElement {
	private static final int WHITE = 0xFFFFFFFF;
	private static final int TRACK = 0x55FFFFFF;
	private static final int FILL = 0xFFE6E6E6;
	private static final int BAR_WIDTH = 80;

	static void init() {
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("myvoice", "record_hud"), new RecordHud());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		Font font = mc.font;
		int w = g.guiWidth();

		int count = VoiceCommands.countdownNumber();
		if (count > 0) {
			float scale = 4f;
			String s = Integer.toString(count);
			float x = w / 2f - font.width(s) * scale / 2f;
			g.pose().pushMatrix();
			g.pose().translate(x, 24f);
			g.pose().scale(scale, scale);
			g.text(font, s, 0, 0, WHITE);
			g.pose().popMatrix();
			g.centeredText(font, Component.translatable("myvoice.hud.get_ready"), w / 2, 12, WHITE);
			return;
		}

		if (VoiceCommands.isRecordingNow()) {
			float remaining = VoiceCommands.remainingSeconds();
			Component text = Component.translatable("myvoice.hud.recording", String.format("%.1f", remaining));
			g.centeredText(font, text, w / 2, 6, WHITE);
			int x0 = w / 2 - BAR_WIDTH / 2;
			int y0 = 6 + font.lineHeight + 3;
			float level = Math.max(0f, Math.min(1f, MicRecorder.level()));
			g.fill(x0, y0, x0 + BAR_WIDTH, y0 + 2, TRACK);
			g.fill(x0, y0, x0 + Math.round(BAR_WIDTH * level), y0 + 2, FILL);
		}
	}
}
