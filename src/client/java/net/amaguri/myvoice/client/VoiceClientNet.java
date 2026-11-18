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
	/** 自分が setTune を送った音と時刻（返ってきた通知を出さない。断られたら取り消す）。 */
	private static Identifier pendingSelfTune = null;
	private static long pendingSelfTuneAt = 0L;
	private static final long PENDING_TTL_NANOS = 5_000_000_000L;

	private record Tune(boolean followPitch, float pitch, float speed) {}
	/** 音ごとの「最後の値だけ」の送信予約。 */
	private static final java.util.Map<Identifier, Tune> PENDING = new java.util.LinkedHashMap<>();
	/** サーバーの連投制限（1秒）に余裕を足した間隔。upload / setTune の最終送信時刻から数える。 */
	private static final long MIN_GAP_NANOS = 1_100_000_000L;
	private static long lastSendAt = System.nanoTime() - MIN_GAP_NANOS;

	public static void init() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> joinedAt = System.nanoTime());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			PENDING.clear();
			pendingSelfTune = null;
		});
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (PENDING.isEmpty()) return;
			if (!canSend()) { PENDING.clear(); return; }
			if (System.nanoTime() - lastSendAt < MIN_GAP_NANOS) return;
			var it = PENDING.entrySet().iterator();
			var en = it.next();
			it.remove();
			Tune t = en.getValue();
			setTune(en.getKey(), t.followPitch(), t.pitch(), t.speed());
		});

		ClientPlayNetworking.registerGlobalReceiver(VoiceNet.ClipS2C.TYPE, (payload, context) -> {
			Minecraft mc = context.client();
			ClientClips.ClipInfo old = ClientClips.get(payload.sound());
			boolean samePcm = ClientClips.samePcm(payload.sound(), payload.pcm());
			ClientClips.put(payload.sound(), payload.owner(), payload.sampleRate(), payload.pcm(),
					payload.followPitch(), payload.pitch(), payload.speed());
			ClientClips.ClipInfo now = ClientClips.get(payload.sound());
			boolean tuneOnly = old != null && samePcm && old.owner().equals(payload.owner())
					&& old.sampleRate() == payload.sampleRate()
					&& (old.followPitch() != now.followPitch() || old.pitch() != now.pitch() || old.speed() != now.speed());
			if (tuneOnly) {
				// 録音は同じで調整だけ変わった。自分の操作は出さない
				boolean self = pendingSelfTune != null && pendingSelfTune.equals(payload.sound())
						&& System.nanoTime() - pendingSelfTuneAt < PENDING_TTL_NANOS;
				if (self) pendingSelfTune = null;
				else if (!isQuiet()) chat(mc, Component.translatable("myvoice.chat.tuned", SoundCatalog.displayName(payload.sound())));
				return;
			}
			if (isQuiet()) return;
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
					pendingSelfTune = null; // 断られた: 返事待ちの自分の調整は無くなった
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

	/** 録音したクリップをサーバーへ送る。MOD の無いサーバーでは送らずに知らせる。pitch/speed は 0.5〜2.0（1 = そのまま）。 */
	public static void upload(Identifier sound, byte[] pcm, boolean followPitch, float pitch, float speed) {
		if (!canSend()) {
			notifyNeedServer();
			return;
		}
		PENDING.remove(sound); // 録音の送信が最新の値を持っている
		lastSendAt = System.nanoTime();
		ClientPlayNetworking.send(new VoiceNet.UploadClipC2S(sound, VoiceNet.SAMPLE_RATE, pcm, followPitch,
				VoiceNet.clampTune(pitch), VoiceNet.clampTune(speed)));
	}

	/** 録音し直さずに声の調整（ピッチ追従・高さ・速さ）だけ変える。 */
	public static void setTune(Identifier sound, boolean followPitch, float pitch, float speed) {
		if (!canSend()) {
			notifyNeedServer();
			return;
		}
		float p = VoiceNet.clampTune(pitch);
		float sp = VoiceNet.clampTune(speed);
		ClientClips.ClipInfo cur = ClientClips.get(sound);
		if (cur != null && cur.followPitch() == followPitch && cur.pitch() == p && cur.speed() == sp) return; // サーバーも無処理
		PENDING.remove(sound);
		lastSendAt = System.nanoTime();
		pendingSelfTune = sound;
		pendingSelfTuneAt = System.nanoTime();
		ClientPlayNetworking.send(new VoiceNet.SetTuneC2S(sound, followPitch, p, sp));
	}

	/** 調整の送信を予約する（音ごとに最後の値だけ。最終送信から 1.1 秒あけて tick で送る）。 */
	public static void queueTune(Identifier sound, boolean followPitch, float pitch, float speed) {
		if (sound == null) return;
		PENDING.remove(sound); // 後勝ち（順番も最新に）
		PENDING.put(sound, new Tune(followPitch, VoiceNet.clampTune(pitch), VoiceNet.clampTune(speed)));
	}

	/** その音の送信予約があるか（画面が表示を ClientClips に合わせてよいかの判定用）。 */
	public static boolean hasPendingTune(Identifier sound) {
		return PENDING.containsKey(sound);
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
