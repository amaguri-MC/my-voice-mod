package net.amaguri.myvoice.gametest;

import net.amaguri.myvoice.client.VoiceClientNet;
import net.amaguri.myvoice.client.audio.ClientClips;
import net.amaguri.myvoice.client.audio.ClipPlayer;
import net.amaguri.myvoice.client.catalog.RecentSounds;
import net.amaguri.myvoice.client.catalog.SoundCatalog;
import net.amaguri.myvoice.client.ui.PickerLauncher;
import net.amaguri.myvoice.client.ui.SoundPickerScreen;
import net.amaguri.myvoice.net.VoiceNet;
import net.amaguri.myvoice.server.VoiceServer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.locale.Language;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

import java.util.List;

/** マイク無しで: 送信 → 両側に登録 → 再生が置き換わる → 削除。ログは MYVOICE_TEST。 */
public class MyVoiceClientGameTest implements FabricClientGameTest {
	private static void log(String fmt, Object... args) {
		System.out.println("MYVOICE_TEST " + String.format(fmt, args));
	}

	private static void check(boolean ok, String message) {
		if (!ok) {
			log("FAIL %s", message);
			throw new IllegalStateException("MYVOICE_TEST failed: " + message);
		}
	}

	/** 440Hz・1秒の正弦波（16bit 符号付き LE モノラル）。 */
	private static byte[] sine() {
		int n = VoiceNet.SAMPLE_RATE;
		byte[] pcm = new byte[n * 2];
		for (int i = 0; i < n; i++) {
			short v = (short) Math.round(Math.sin(2 * Math.PI * 440 * i / VoiceNet.SAMPLE_RATE) * 12000);
			pcm[i * 2] = (byte) (v & 0xFF);
			pcm[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
		}
		return pcm;
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		Identifier id = Identifier.fromNamespaceAndPath("minecraft", "ui.button.click");
		try (TestSingleplayerContext sp = context.worldBuilder().create()) {
			var server = sp.getServer();
			sp.getConnection().waitForChunksRender();
			log("world ready");

			// a) アップロード
			byte[] pcm = sine();
			context.runOnClient(mc -> VoiceClientNet.upload(id, pcm, true, 1.0f, 1.0f));
			log("a) uploaded %d bytes to %s", pcm.length, id);

			// b) 両側に登録される
			server.waitFor(s -> VoiceServer.has(id));
			log("b) server has=%s clipCount=%d", server.computeOnServer(s -> VoiceServer.has(id)), server.computeOnServer(s -> VoiceServer.clipCount()));
			context.waitFor(mc -> ClientClips.get(id) != null);
			boolean serverHas = server.computeOnServer(s -> VoiceServer.has(id));
			boolean clientHas = context.computeOnClient(mc -> ClientClips.get(id) != null);
			log("b) server=%s client=%s", serverHas, clientHas);
			check(serverHas, "server does not have the clip");
			check(clientHas, "client does not have the clip");

			// c) 再生が置き換えられる
			int before = ClipPlayer.replacedCount();
			context.runOnClient(mc -> mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f)));
			context.waitTicks(5);
			int after = ClipPlayer.replacedCount();
			log("c) replacedCount %d -> %d", before, after);
			check(after > before, "sound was not replaced (replacedCount " + before + " -> " + after + ")");

			// d) 削除（/myvoice clear 相当）
			context.runOnClient(mc -> ClientPlayNetworking.send(new VoiceNet.ClearClipC2S(id)));
			server.waitFor(s -> !VoiceServer.has(id));
			context.waitFor(mc -> ClientClips.get(id) == null);
			boolean serverGone = !server.computeOnServer(s -> VoiceServer.has(id));
			boolean clientGone = context.computeOnClient(mc -> ClientClips.get(id) == null);
			log("d) cleared server=%s client=%s", serverGone, clientGone);
			check(serverGone, "server still has the clip");
			check(clientGone, "client still has the clip");

			// e) followPitch=false でアップ（連投制限を避けて待つ）
			context.waitTicks(30);
			context.runOnClient(mc -> VoiceClientNet.upload(id, pcm, false, 1.0f, 1.0f));
			server.waitFor(s -> VoiceServer.has(id));
			context.waitFor(mc -> ClientClips.get(id) != null && !ClientClips.get(id).followPitch());
			boolean eOk = context.computeOnClient(mc -> ClientClips.get(id) != null && !ClientClips.get(id).followPitch());
			log("e) followPitch=false client=%s", eOk);
			check(eOk, "followPitch should be false after upload(false)");

			// f) setTune(followPitch=true, 1, 1)
			context.waitTicks(30);
			context.runOnClient(mc -> VoiceClientNet.setTune(id, true, 1.0f, 1.0f));
			context.waitFor(mc -> ClientClips.get(id) != null && ClientClips.get(id).followPitch());
			boolean fOk = context.computeOnClient(mc -> ClientClips.get(id) != null && ClientClips.get(id).followPitch());
			log("f) setTune(followPitch=true) client=%s", fOk);
			check(fOk, "followPitch should be true after setTune(true)");

			// g) pitch=1.5, speed=0.75 でアップ → 長さは元の約 1/0.75 = 1.33 倍
			float orig = (pcm.length / 2) / (float) VoiceNet.SAMPLE_RATE;
			context.waitTicks(30);
			context.runOnClient(mc -> VoiceClientNet.upload(id, pcm, true, 1.5f, 0.75f));
			context.waitFor(mc -> ClientClips.get(id) != null && ClientClips.get(id).speed() == 0.75f && ClientClips.ready(id));
			float gSec = context.computeOnClient(mc -> ClientClips.get(id).seconds());
			float gRatio = gSec / orig;
			log("g) upload(pitch=1.5, speed=0.75) seconds=%.3f orig=%.3f ratio=%.3f", gSec, orig, gRatio);
			check(Math.abs(gRatio - 1.0f / 0.75f) < 0.05f, "seconds should be about 1.33x the original, ratio=" + gRatio);
			boolean gServer = server.computeOnServer(s -> VoiceServer.has(id));
			check(gServer, "server should have the tuned clip");

			// h) setTune(1,1) で元の長さに戻る
			context.waitTicks(30);
			context.runOnClient(mc -> VoiceClientNet.setTune(id, true, 1.0f, 1.0f));
			context.waitFor(mc -> ClientClips.get(id) != null && ClientClips.get(id).speed() == 1.0f && ClientClips.ready(id));
			float hSec = context.computeOnClient(mc -> ClientClips.get(id).seconds());
			log("h) setTune(1.0, 1.0) seconds=%.3f orig=%.3f", hSec, orig);
			check(Math.abs(hSec - orig) < 0.01f, "seconds should return to the original, got " + hSec);

			// h2) ピッチ追従 on → すぐ off（予約のまとめ）→ 3秒待って followPitch=false に落ち着く
			context.waitTicks(40);
			context.runOnClient(mc -> VoiceClientNet.queueTune(id, false, 1.0f, 1.0f));
			context.waitFor(mc -> ClientClips.get(id) != null && !ClientClips.get(id).followPitch());
			context.waitTicks(40);
			context.runOnClient(mc -> VoiceClientNet.queueTune(id, true, 1.0f, 1.0f));
			context.waitTicks(3);
			context.runOnClient(mc -> VoiceClientNet.queueTune(id, false, 1.0f, 1.0f));
			context.waitTicks(60);
			boolean h2 = context.computeOnClient(mc -> ClientClips.get(id) != null && !ClientClips.get(id).followPitch());
			log("h2) on -> immediately off, after 3s followPitch=false client=%s", h2);
			check(h2, "followPitch should settle to false after quick on/off");
			context.waitTicks(40);

			// ---- 音選び画面（W6） ----
			pickerTest(context, id);

			log("all passed");
		}
	}

	private static final List<String> NAME_IDS = List.of(
			"entity.cow.ambient", "block.stone.break", "entity.player.hurt", "ui.button.click",
			"block.wooden_door.open", "entity.generic.explode", "item.bucket.fill", "ambient.cave",
			"entity.chicken.step", "block.grass.step");

	private static Identifier mc(String path) {
		return Identifier.fromNamespaceAndPath("minecraft", path);
	}

	private static void playUi(ClientGameTestContext context, String path) {
		context.runOnClient(m -> m.getSoundManager().play(
				SimpleSoundInstance.forUI(SoundEvent.createVariableRangeEvent(mc(path)), 1f)));
	}

	private static void shot(ClientGameTestContext context, String name) {
		context.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix());
		log("screenshot %s.png", name);
	}

	private static Long heardAt(ClientGameTestContext context, Identifier sound) {
		return context.computeOnClient(m -> {
			for (RecentSounds.Heard h : RecentSounds.recent()) {
				if (h.sound().equals(sound)) return h.atMillis();
			}
			return null;
		});
	}

	private void pickerTest(ClientGameTestContext context, Identifier clipId) {
		// 言語を ja_jp に（画面の翻訳と名前の組み立てを日本語で確かめる）
		String probe = "myvoice.picker.title";
		String before = context.computeOnClient(m -> Language.getInstance().getOrDefault(probe, "?"));
		context.runOnClient(m -> {
			m.getLanguageManager().setSelected("ja_jp");
			m.options.languageCode = "ja_jp";
			m.reloadResourcePacks();
		});
		context.waitFor(m -> m.gui.overlay() == null
				&& "声を割り当てる音を選ぶ".equals(Language.getInstance().getOrDefault(probe, "?")), 1200);
		log("language switched: title '%s' -> '%s'", before,
				context.computeOnClient(m -> Language.getInstance().getOrDefault(probe, "?")));

		// 名前の組み立て（10件）
		for (String path : NAME_IDS) {
			String name = context.computeOnClient(m -> SoundCatalog.entry(mc(path)).name().getString());
			log("name %s id=%s", name, mc(path));
			check(name != null && !name.isBlank(), "empty name for " + path);
		}

		// 全サウンドの名前に英字が残っていないこと（TNT は除く）
		List<String> ascii = context.computeOnClient(m -> {
			List<String> out = new java.util.ArrayList<>();
			for (SoundCatalog.Entry e : SoundCatalog.list(null, "")) {
				String n = e.name().getString();
				if (n.replace("TNT", "").chars().anyMatch(c -> (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))) {
					out.add("MYVOICE_TEST ascii " + n + " id=" + e.sound());
				}
			}
			return out;
		});
		int total = context.computeOnClient(m -> SoundCatalog.list(null, "").size());
		ascii.forEach(System.out::println);
		List<String> unmapped = context.computeOnClient(m -> SoundCatalog.unmappedWords());
		log("unmapped words: %s", unmapped);
		log("ascii names: %d of %d sounds", ascii.size(), total);
		check(ascii.isEmpty(), "sound names still containing ASCII letters: " + ascii.size());

		// playOriginal は置換せず、さっき鳴った音にも入れない（clipId は声が割り当て済み）
		check(context.computeOnClient(m -> ClientClips.get(clipId) != null), "clip should be assigned for bypass test");
		playUi(context, clipId.getPath());
		context.waitTicks(5);
		Long heardBefore = heardAt(context, clipId);
		int replacedBefore = ClipPlayer.replacedCount();
		context.runOnClient(m -> ClipPlayer.playOriginal(clipId));
		context.waitTicks(10);
		int replacedAfter = ClipPlayer.replacedCount();
		Long heardAfter = heardAt(context, clipId);
		log("pg) playOriginal replacedCount %d -> %d, recent atMillis %s -> %s", replacedBefore, replacedAfter, heardBefore, heardAfter);
		check(replacedAfter == replacedBefore, "playOriginal must not be replaced");
		check(heardBefore != null && heardBefore.equals(heardAfter), "playOriginal must not be recorded in RecentSounds");

		// 画面を開く前に、ウシ・ドア・ボタンを鳴らす
		context.getInput().resizeWindow(1280, 720);
		context.waitTicks(5);
		playUi(context, "entity.cow.ambient");
		context.waitTicks(3);
		playUi(context, "block.wooden_door.open");
		context.waitTicks(3);
		playUi(context, "ui.button.click");
		context.waitTicks(10);

		context.runOnClient(m -> PickerLauncher.open());
		context.waitForScreen(SoundPickerScreen.class);
		context.waitTicks(25);
		int recentRows = context.computeOnClient(m -> SoundCatalog.list(SoundCatalog.Category.RECENT, "").size());
		log("ph) picker opened, recent rows=%d", recentRows);
		check(recentRows >= 3, "recent list should contain the sounds just played");
		boolean notPaused = context.computeOnClient(m -> !m.gui.screen().isPauseScreen());
		check(notPaused, "picker must not pause the game");
		shot(context, "picker_recent");

		// 生き物分類で検索「ウシ」
		context.runOnClient(m -> ((SoundPickerScreen) m.gui.screen()).openCategory(SoundCatalog.Category.CREATURE));
		context.waitTicks(3);
		context.getInput().typeChars("ウシ");
		context.waitTicks(5);
		String typed = context.computeOnClient(m -> ((SoundPickerScreen) m.gui.screen()).query());
		if (!"ウシ".equals(typed)) {
			log("pi) typeChars did not reach the search box (query='%s'); using setQuery", typed);
			context.runOnClient(m -> ((SoundPickerScreen) m.gui.screen()).setQuery("ウシ"));
			context.waitTicks(5);
		}
		int hits = context.computeOnClient(m -> SoundCatalog.list(SoundCatalog.Category.CREATURE, "ウシ").size());
		log("pi) search ウシ in creatures: %d hits (typed query='%s')", hits, typed);
		check(hits > 0, "search ウシ should find cow sounds");
		shot(context, "picker_search");

		// ui.button.click を選んで右列の詳細
		context.runOnClient(m -> {
			SoundPickerScreen sp = (SoundPickerScreen) m.gui.screen();
			sp.setQuery("");
			sp.openCategory(SoundCatalog.Category.UI);
			sp.choose(clipId);
		});
		context.waitTicks(10);
		Identifier chosen = context.computeOnClient(m -> ((SoundPickerScreen) m.gui.screen()).selectedSound());
		log("pj) selected %s", chosen);
		check(clipId.equals(chosen), "ui.button.click should be selected");
		// 画面のバーにその値が出る状態で撮る: 声の調整を 高さ1.5・速さ0.75 にして同期を待つ
		context.waitTicks(30);
		context.runOnClient(m -> VoiceClientNet.setTune(clipId, true, 1.5f, 0.75f));
		context.waitFor(m -> ClientClips.get(clipId) != null && ClientClips.get(clipId).pitch() == 1.5f && ClientClips.get(clipId).speed() == 0.75f && ClientClips.ready(clipId));
		context.waitTicks(30);
		log("pj) tuned %s to pitch=1.5 speed=0.75 for the screenshot", clipId);
		shot(context, "picker_detail");

		context.runOnClient(m -> m.gui.screen().onClose());
		context.waitTicks(5);
	}
}
