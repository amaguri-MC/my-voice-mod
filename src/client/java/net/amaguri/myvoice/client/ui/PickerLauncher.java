package net.amaguri.myvoice.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/** 音選び画面の開き方: キー「K」と /myvoice（引数なし）。 */
public final class PickerLauncher {
	private PickerLauncher() {}

	private static KeyMapping openKey;
	/** コマンドからの要求。チャット画面が閉じたあとの tick で開く。 */
	private static boolean pending = false;

	public static void init() {
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("myvoice", "main"));
		openKey = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key.myvoice.open_picker", InputConstants.Type.KEYSYM, InputConstants.KEY_K, category));
		ClientTickEvents.END_CLIENT_TICK.register(PickerLauncher::tick);
	}

	/** /myvoice（引数なし）から。 */
	public static void requestOpen() {
		pending = true;
	}

	/** いま開く（テストなどから）。 */
	public static void open() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		mc.setScreenAndShow(new SoundPickerScreen());
	}

	private static void tick(Minecraft mc) {
		boolean pressed = false;
		while (openKey.consumeClick()) {
			pressed = true;
		}
		if (mc.player == null) {
			pending = false;
			return;
		}
		if (mc.gui.screen() != null) return; // チャットなどが閉じるまで待つ（pending は保つ）
		if (pressed || pending) {
			pending = false;
			open();
		}
	}
}
