package net.amaguri.myvoice.client;

import net.amaguri.myvoice.client.audio.ClientClips;
import net.amaguri.myvoice.net.VoiceNet;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** クライアント側の通信: S2C の受信とアップロード送信。 */
public final class VoiceClientNet {
	private VoiceClientNet() {}

	/** 参加直後の一括同期ではチャットを出さないための猶予（ナノ秒）。 */
	private static final long QUIET_AFTER_JOIN_NANOS = 3_000_000_000L;
	private static long joinedAt = 0L;

	public static void init() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> joinedAt = System.nanoTime());

		ClientPlayNetworking.registerGlobalReceiver(VoiceNet.ClipS2C.TYPE, (payload, context) -> {
			Minecraft mc = context.client();
			ClientClips.put(payload.sound(), payload.owner(), payload.sampleRate(), payload.pcm());
			// 自分が録音した分は notice("saved") が知らせるので重ねて出さない
			if (!isQuiet() && !payload.owner().equals(selfName(mc))) {
				chat(mc, Component.translatable("myvoice.chat.clip_added", payload.owner(), payload.sound().toString()));
			}
		});

		ClientPlayNetworking.registerGlobalReceiver(VoiceNet.ClipRemovedS2C.TYPE, (payload, context) -> {
			Minecraft mc = context.client();
			ClientClips.remove(payload.sound());
			if (!isQuiet()) {
				chat(mc, Component.translatable("myvoice.chat.clip_removed", payload.by(), payload.sound().toString()));
			}
		});

		ClientPlayNetworking.registerGlobalReceiver(VoiceNet.NoticeS2C.TYPE, (payload, context) -> {
			// 翻訳キーは自分の MOD のものだけ受け付ける
			if (payload.key().startsWith("myvoice.")) {
				chat(context.client(), Component.translatable(payload.key(), payload.arg()));
			}
		});
	}

	/** 録音したクリップをサーバーへ送る。MOD の無いサーバーでは送らずに知らせる。 */
	public static void upload(Identifier sound, byte[] pcm) {
		if (!canSend()) {
			notifyNeedServer();
			return;
		}
		ClientPlayNetworking.send(new VoiceNet.UploadClipC2S(sound, VoiceNet.SAMPLE_RATE, pcm));
	}

	/** サーバーにも My Voice が入っているか。 */
	static boolean canSend() {
		Minecraft mc = Minecraft.getInstance();
		return mc.getConnection() != null && ClientPlayNetworking.canSend(VoiceNet.UploadClipC2S.TYPE);
	}

	static void notifyNeedServer() {
		chat(Minecraft.getInstance(), Component.translatable("myvoice.cmd.need_server"));
	}

	static void chat(Minecraft mc, Component message) {
		if (mc.player != null) {
			mc.player.sendSystemMessage(message);
		}
	}

	private static boolean isQuiet() {
		return System.nanoTime() - joinedAt < QUIET_AFTER_JOIN_NANOS;
	}

	private static String selfName(Minecraft mc) {
		return mc.player == null ? "" : mc.player.getGameProfile().name();
	}
}
