package net.amaguri.myvoice.client;

import net.amaguri.myvoice.client.audio.ClientClips;
import net.amaguri.myvoice.client.catalog.SoundCatalog;
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
	/** 自分が setPitchMode を送った音（返ってきた通知を自分用の文言にする）。 */
	private static Identifier pendingSelfPitch = null;

	public static void init() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> joinedAt = System.nanoTime());

		ClientPlayNetworking.registerGlobalReceiver(VoiceNet.ClipS2C.TYPE, (payload, context) -> {
			Minecraft mc = context.client();
			ClientClips.ClipInfo old = ClientClips.get(payload.sound());
			boolean samePcm = ClientClips.samePcm(payload.sound(), payload.pcm());
			ClientClips.put(payload.sound(), payload.owner(), payload.sampleRate(), payload.pcm(), payload.followPitch());
			if (isQuiet()) return;
			if (old != null && samePcm && old.followPitch() != payload.followPitch() && old.owner().equals(payload.owner())
					&& old.sampleRate() == payload.sampleRate() && Math.abs(old.seconds() - (payload.pcm().length / 2) / (float) payload.sampleRate()) < 1e-6f) {
				// ピッチだけの変更（録音は同じ）。自分の操作ならコマンドの反応で足りるので控えめに、1回だけ
				if (pendingSelfPitch != null && pendingSelfPitch.equals(payload.sound())) {
					pendingSelfPitch = null;
					chat(mc, Component.translatable("myvoice.chat.pitch_mode_self", SoundCatalog.displayName(payload.sound()), pitchLabel(payload.followPitch())));
				} else {
					chat(mc, Component.translatable("myvoice.chat.pitch_mode", payload.owner(), SoundCatalog.displayName(payload.sound()), pitchLabel(payload.followPitch())));
				}
				return;
			}
			// 自分が録音した分は notice("saved") が知らせるので重ねて出さない
			if (!payload.owner().equals(selfName(mc))) {
				chat(mc, Component.translatable("myvoice.chat.clip_added", payload.owner(), SoundCatalog.displayName(payload.sound())));
			}
		});

		ClientPlayNetworking.registerGlobalReceiver(VoiceNet.ClipRemovedS2C.TYPE, (payload, context) -> {
			Minecraft mc = context.client();
			ClientClips.remove(payload.sound());
			if (!isQuiet()) {
				chat(mc, Component.translatable("myvoice.chat.clip_removed", payload.by(), SoundCatalog.displayName(payload.sound())));
			}
		});

		ClientPlayNetworking.registerGlobalReceiver(VoiceNet.NoticeS2C.TYPE, (payload, context) -> {
			// 翻訳キーは自分の MOD のものだけ受け付ける
			if (payload.key().startsWith("myvoice.")) {
				if (payload.key().equals("myvoice.notice.rejected") || payload.key().equals("myvoice.notice.not_found")) {
					pendingSelfPitch = null; // 断られた: 返事待ちの自分のピッチ変更は無くなった
				}
				String arg = payload.arg();
				// 音のIDが入る通知は日本語名にする（rejected の arg は理由コードなのでそのまま）
				if (payload.key().equals("myvoice.notice.saved") || payload.key().equals("myvoice.notice.not_found")) {
					arg = SoundCatalog.displayName(arg);
				}
				chat(context.client(), Component.translatable(payload.key(), arg));
			}
		});
	}

	/** 録音したクリップをサーバーへ送る。MOD の無いサーバーでは送らずに知らせる。 */
	public static void upload(Identifier sound, byte[] pcm, boolean followPitch) {
		if (!canSend()) {
			notifyNeedServer();
			return;
		}
		ClientPlayNetworking.send(new VoiceNet.UploadClipC2S(sound, VoiceNet.SAMPLE_RATE, pcm, followPitch));
	}

	/** 録音し直さずにピッチ設定だけ変える。 */
	public static void setPitchMode(Identifier sound, boolean followPitch) {
		if (!canSend()) {
			notifyNeedServer();
			return;
		}
		pendingSelfPitch = sound;
		ClientPlayNetworking.send(new VoiceNet.SetPitchModeC2S(sound, followPitch));
	}

	private static Component pitchLabel(boolean followPitch) {
		return Component.translatable(followPitch ? "myvoice.pitch.game" : "myvoice.pitch.voice");
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
