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
	/** 送信済みで ClipS2C の返事待ちの調整（音 → 値と送信時刻）。自分の ClipS2C が届くか 3 秒で解除。 */
	private record InFlight(Tune tune, long at) {}
	private static final java.util.Map<Identifier, InFlight> IN_FLIGHT = new java.util.HashMap<>();
	private static final long IN_FLIGHT_TTL_NANOS = 3_000_000_000L;

	public record Tune(boolean followPitch, float pitch, float speed, float trim, float volume) {}
	/** 音ごとの「最後の値だけ」の送信予約。 */
	private static final java.util.Map<Identifier, Tune> PENDING = new java.util.LinkedHashMap<>();
	/** サーバーの連投制限（1秒）に余裕を足した間隔。upload / setTune の最終送信時刻から数える。 */
	private static final long MIN_GAP_NANOS = 1_300_000_000L;
	private static long lastSendAt = System.nanoTime() - MIN_GAP_NANOS;

	public static void init() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> joinedAt = System.nanoTime());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			PENDING.clear();
			IN_FLIGHT.clear();
		});
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			expireInFlight();
			if (PENDING.isEmpty()) return;
			if (!canSend()) { PENDING.clear(); return; }
			if (System.nanoTime() - lastSendAt < MIN_GAP_NANOS) return;
			var it = PENDING.entrySet().iterator();
			var en = it.next();
			it.remove();
			Tune t = en.getValue();
			setTune(en.getKey(), t.followPitch(), t.pitch(), t.speed(), t.trim(), t.volume());
		});

		ClientPlayNetworking.registerGlobalReceiver(VoiceNet.ClipS2C.TYPE, (payload, context) -> {
			Minecraft mc = context.client();
			ClientClips.ClipInfo old = ClientClips.get(payload.sound());
			boolean samePcm = ClientClips.samePcm(payload.sound(), payload.pcm());
			ClientClips.put(payload.sound(), payload.owner(), payload.sampleRate(), payload.pcm(),
					payload.followPitch(), payload.pitch(), payload.speed(), payload.trim(), payload.volume());
			ClientClips.ClipInfo now = ClientClips.get(payload.sound());
			InFlight fl = IN_FLIGHT.get(payload.sound());
			boolean mine = fl != null && fl.tune().equals(new Tune(now.followPitch(), now.pitch(), now.speed(), now.trim(), now.volume()));
			boolean tuneOnly = old != null && samePcm && old.owner().equals(payload.owner())
					&& old.sampleRate() == payload.sampleRate()
					&& (old.followPitch() != now.followPitch() || old.pitch() != now.pitch() || old.speed() != now.speed() || old.trim() != now.trim() || old.volume() != now.volume());
			if (tuneOnly) {
				// 録音は同じで調整だけ変わった。自分の操作は出さない
				if (mine) IN_FLIGHT.remove(payload.sound());
				else if (fl != null) return; // 自分の前の送信の返事（最新の返事待ちは残す）
				else if (!isQuiet()) chat(mc, Component.translatable("myvoice.chat.tuned", SoundCatalog.displayName(payload.sound())));
				return;
			}
			if (mine) IN_FLIGHT.remove(payload.sound());
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
				if (payload.key().equals("myvoice.notice.rejected") && "too_fast".equals(payload.arg())) {
					// 連投制限: 返事待ちの調整を予約に戻して再送する（新しい予約があればそちらが勝つ）
					for (var en : IN_FLIGHT.entrySet()) PENDING.putIfAbsent(en.getKey(), en.getValue().tune());
					IN_FLIGHT.clear();
					lastSendAt = System.nanoTime();
				} else if (payload.key().equals("myvoice.notice.rejected")) {
					IN_FLIGHT.clear(); // 断られた: 返事待ちは無くなった
				} else if (payload.key().equals("myvoice.notice.not_found")) {
					IN_FLIGHT.remove(Identifier.tryParse(payload.arg()));
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

	/** 録音したクリップをサーバーへ送る。MOD の無いサーバーでは送らずに知らせる。pitch/speed は 0.5〜2.0（1 = そのまま）、trim は 0〜1（0 = 切らない）、volume は 0〜2（1 = そのまま、0 = 消音）。 */
	public static void upload(Identifier sound, byte[] pcm, boolean followPitch, float pitch, float speed, float trim, float volume) {
		if (!canSend()) {
			notifyNeedServer();
			return;
		}
		PENDING.remove(sound); // 録音の送信が最新の値を持っている
		lastSendAt = System.nanoTime();
		ClientPlayNetworking.send(new VoiceNet.UploadClipC2S(sound, VoiceNet.SAMPLE_RATE, pcm, followPitch,
				VoiceNet.clampTune(pitch), VoiceNet.clampTune(speed), VoiceNet.clampTrim(trim), VoiceNet.clampVolume(volume)));
	}

	/** 録音し直さずに声の調整（ピッチ追従・高さ・速さ）だけ変える。 */
	public static void setTune(Identifier sound, boolean followPitch, float pitch, float speed, float trim, float volume) {
		if (!canSend()) {
			notifyNeedServer();
			return;
		}
		float p = VoiceNet.clampTune(pitch);
		float sp = VoiceNet.clampTune(speed);
		float tr = VoiceNet.clampTrim(trim);
		float vo = VoiceNet.clampVolume(volume);
		ClientClips.ClipInfo cur = ClientClips.get(sound);
		if (cur != null && cur.followPitch() == followPitch && cur.pitch() == p && cur.speed() == sp && cur.trim() == tr && cur.volume() == vo) return; // サーバーも無処理
		PENDING.remove(sound);
		lastSendAt = System.nanoTime();
		IN_FLIGHT.put(sound, new InFlight(new Tune(followPitch, p, sp, tr, vo), lastSendAt));
		ClientPlayNetworking.send(new VoiceNet.SetTuneC2S(sound, followPitch, p, sp, tr, vo));
	}

	/** 調整の送信を予約する（音ごとに最後の値だけ。最終送信から 1.3 秒あけて tick で送る）。 */
	public static void queueTune(Identifier sound, boolean followPitch, float pitch, float speed, float trim, float volume) {
		if (sound == null) return;
		PENDING.remove(sound); // 後勝ち（順番も最新に）
		PENDING.put(sound, new Tune(followPitch, VoiceNet.clampTune(pitch), VoiceNet.clampTune(speed), VoiceNet.clampTrim(trim), VoiceNet.clampVolume(volume)));
	}

	/** その音の送信予約があるか（画面が表示を ClientClips に合わせてよいかの判定用）。 */
	public static boolean hasPendingTune(Identifier sound) {
		return pendingTune(sound) != null;
	}

	/** その音の「まだ反映されていない最新の値」: 予約 → 返事待ち → null。コマンドと画面はこれを基準にする。 */
	public static Tune pendingTune(Identifier sound) {
		Tune t = PENDING.get(sound);
		if (t != null) return t;
		expireInFlight();
		InFlight f = IN_FLIGHT.get(sound);
		return f == null ? null : f.tune();
	}

	private static void expireInFlight() {
		if (IN_FLIGHT.isEmpty()) return;
		long now = System.nanoTime();
		IN_FLIGHT.values().removeIf(f -> now - f.at() >= IN_FLIGHT_TTL_NANOS);
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
