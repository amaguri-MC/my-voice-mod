package net.amaguri.myvoice.client.ui;

import net.amaguri.myvoice.client.VoiceClientNet;
import net.amaguri.myvoice.client.audio.MicRecorder;
import net.amaguri.myvoice.net.VoiceNet;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * 録音の流れ（3,2,1 のカウント → 録音 → アップロード）。/myvoice record と音選び画面と HUD が共有する。
 * クライアントスレッドからのみ呼ぶ。
 */
public final class RecordFlow {
	private RecordFlow() {}

	/** 結果の通知先。コマンドはチャット、音選び画面は右列に出す。 */
	public interface Listener {
		/** マイクが無い・無音だった等。 */
		void error(Component message);

		/** アップロードを送った。 */
		default void sent(Identifier sound) {}
	}

	public enum StopResult { COUNTDOWN_CANCELLED, STOPPING, NOT_RECORDING }

	public static final int DEFAULT_SECONDS = 3;
	private static final int TICKS_PER_SECOND = 20;
	private static final int COUNTDOWN_TICKS = 3 * TICKS_PER_SECOND;

	/** カウントダウンの残り tick（0 なら動いていない）。 */
	private static int countdownTicks = 0;
	private static Identifier target = null;
	private static int seconds = DEFAULT_SECONDS;
	private static boolean followPitch = false;
	private static float tunePitch = 1.0f;
	private static float tuneSpeed = 1.0f;
	private static float tuneTrim = VoiceNet.DEFAULT_TRIM;
	private static Listener listener = null;
	/** 録音中か（MicRecorder.start 〜 onDone/onError）。 */
	private static boolean recording = false;
	private static int recordSeconds = DEFAULT_SECONDS;
	private static int recordTicks = 0;

	/** カウントダウン中・録音中のどちらか。 */
	public static boolean busy() {
		return countdownTicks > 0 || recording || MicRecorder.isRecording();
	}

	/** カウントダウン中なら 3,2,1、そうでなければ 0。 */
	public static int countdownNumber() {
		return countdownTicks <= 0 ? 0 : (countdownTicks + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND;
	}

	public static boolean isRecording() {
		return recording;
	}

	public static float remainingSeconds() {
		return Math.max(0f, recordSeconds - recordTicks / (float) TICKS_PER_SECOND);
	}

	/** いま録ろうとしている（録っている）音。動いていなければ null。 */
	public static Identifier target() {
		return busy() ? target : null;
	}

	/** サーバーにも My Voice が入っているか。 */
	public static boolean canSend() {
		Minecraft mc = Minecraft.getInstance();
		return mc.getConnection() != null && ClientPlayNetworking.canSend(VoiceNet.UploadClipC2S.TYPE);
	}

	/**
	 * 3 秒のカウントダウンのあと録音して送る。busy のときは何もせず false。
	 * canSend の確認は呼ぶ側でする（メッセージの出し方が違うため）。
	 */
	public static boolean begin(Identifier sound, int secs, boolean follow, Listener l) {
		return begin(sound, secs, follow, 1.0f, 1.0f, VoiceNet.DEFAULT_TRIM, l);
	}

	/** 高さ・速さ（0.5〜2.0）と無音カット（0〜1）つき。録音のアップロードにそのまま渡す。 */
	public static boolean begin(Identifier sound, int secs, boolean follow, float pitch, float speed, float trim, Listener l) {
		if (sound == null || busy()) return false;
		target = sound;
		tunePitch = VoiceNet.clampTune(pitch);
		tuneSpeed = VoiceNet.clampTune(speed);
		tuneTrim = VoiceNet.clampTrim(trim);
		seconds = Math.max(1, Math.min(VoiceNet.MAX_SECONDS, secs));
		followPitch = follow;
		listener = l;
		countdownTicks = COUNTDOWN_TICKS;
		return true;
	}

	/** /myvoice stop と画面の［止める］。 */
	public static StopResult stopEarly() {
		if (countdownTicks > 0) {
			countdownTicks = 0;
			target = null;
			return StopResult.COUNTDOWN_CANCELLED;
		}
		if (recording || MicRecorder.isRecording()) {
			MicRecorder.stop();
			return StopResult.STOPPING;
		}
		return StopResult.NOT_RECORDING;
	}

	/** 切断時など。 */
	public static void reset() {
		countdownTicks = 0;
		target = null;
		if (recording || MicRecorder.isRecording()) {
			MicRecorder.stop();
		}
		recording = false;
		recordTicks = 0;
	}

	/** 毎 tick（END_CLIENT_TICK）。 */
	public static void tick() {
		if (countdownTicks > 0) {
			countdownTicks--;
			if (countdownTicks == 0 && target != null) {
				startRecording(target, seconds, followPitch, tunePitch, tuneSpeed, tuneTrim, listener);
			}
		} else if (recording) {
			recordTicks++;
		}
	}

	private static void startRecording(Identifier sound, int secs, boolean follow, float pitch, float speed, float trim, Listener l) {
		recording = true;
		recordSeconds = secs;
		recordTicks = 0;
		MicRecorder.start(secs, pcm -> {
			recording = false;
			if (pcm == null || pcm.length < 2) {
				fail(l, Component.translatable("myvoice.error.mic_failed"));
				return;
			}
			VoiceClientNet.upload(sound, pcm, follow, pitch, speed, trim);
			if (l != null) l.sent(sound);
		}, errorKey -> {
			recording = false;
			fail(l, Component.translatable(errorKey));
		});
	}

	private static void fail(Listener l, Component message) {
		if (l != null) l.error(message);
	}
}
