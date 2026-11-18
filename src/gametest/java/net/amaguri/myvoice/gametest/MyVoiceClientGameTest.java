package net.amaguri.myvoice.gametest;

import net.amaguri.myvoice.client.VoiceClientNet;
import net.amaguri.myvoice.client.audio.ClientClips;
import net.amaguri.myvoice.client.audio.ClipPlayer;
import net.amaguri.myvoice.net.VoiceNet;
import net.amaguri.myvoice.server.VoiceServer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;

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
			context.runOnClient(mc -> VoiceClientNet.upload(id, pcm));
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

			log("all passed");
		}
	}
}
