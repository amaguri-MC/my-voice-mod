package net.amaguri.myvoice.client.audio;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.amaguri.myvoice.client.audio.dsp.VoiceDsp;
import net.amaguri.myvoice.net.VoiceNet;
import net.minecraft.resources.Identifier;

/** サーバーから受け取った声のクリップ置き場。クライアントスレッドからのみ呼ぶ。 */
public final class ClientClips {
	private ClientClips() {}

	public record ClipInfo(Identifier sound, String owner, int sampleRate, float seconds, boolean followPitch, float pitch, float speed) {}

	/** PCM と（再生時に作る）AL バッファ。 */
	static final class Entry {
		final ClipInfo info;
		byte[] pcm; // AL バッファの元になる加工後 PCM。null = 加工中（まだ鳴らさない）
		final byte[] rawPcm; // 元の録音（無加工）
		int alBuffer; // 0 = 未作成
		long alContext; // alBuffer を作った AL コンテキスト（サウンドエンジン再起動で変わる）

		Entry(ClipInfo info, byte[] rawPcm, byte[] pcm) {
			this.info = info;
			this.rawPcm = rawPcm;
			this.pcm = pcm;
		}
	}

	private static final Map<Identifier, Entry> CLIPS = new LinkedHashMap<>();

	/** pitch/speed は元の録音への倍率（1,1 以外なら VoiceDsp で加工した PCM を AL バッファにする）。 */
	public static void put(Identifier sound, String owner, int sampleRate, byte[] pcm, boolean followPitch, float pitch, float speed) {
		if (sound == null || pcm == null || sampleRate <= 0) return;
		remove(sound); // 上書き: 古いソースとバッファを先に片付ける（値が変わったら AL バッファも作り直しになる）
		pitch = VoiceNet.clampTune(pitch);
		speed = VoiceNet.clampTune(speed);
		byte[] raw = pcm.clone();
		boolean plain = pitch == 1.0f && speed == 1.0f;
		float seconds = (raw.length / 2) / (float) sampleRate / speed; // 長さは先に決める（加工は別スレッド）
		Entry e = new Entry(new ClipInfo(sound, owner == null ? "" : owner, sampleRate, seconds, followPitch, pitch, speed), raw, plain ? raw : null);
		CLIPS.put(sound, e);
		if (!plain) {
			tuneAsync(raw, sampleRate, pitch, speed, tuned -> {
				// 世代確認: 上書き・削除されていたら捨てる（put のたびに新しい Entry）
				if (CLIPS.get(sound) != e) return;
				ClipPlayer.stopSourcesOf(e);
				ClipPlayer.deleteBuffer(e);
				e.pcm = tuned;
			});
		}
	}

	/** 加工が終わって鳴らせる状態か（テスト用）。 */
	public static boolean ready(Identifier sound) {
		Entry e = CLIPS.get(sound);
		return e != null && e.pcm != null;
	}

	private static final java.util.concurrent.ExecutorService DSP = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "myvoice-dsp");
		t.setDaemon(true);
		return t;
	});

	/** 単一スレッドで加工し、結果をクライアントスレッドで渡す。 */
	static void tuneAsync(byte[] raw, int sampleRate, float pitch, float speed, java.util.function.Consumer<byte[]> onMain) {
		DSP.execute(() -> {
			byte[] out = tune(raw, sampleRate, pitch, speed);
			net.minecraft.client.Minecraft.getInstance().execute(() -> onMain.accept(out));
		});
	}

	/** 加工。1,1 なら元のまま。DSP が失敗したら元の PCM で鳴らす。 */
	static byte[] tune(byte[] raw, int sampleRate, float pitch, float speed) {
		if (pitch == 1.0f && speed == 1.0f) return raw;
		try {
			byte[] out = VoiceDsp.process(raw, sampleRate, pitch, speed);
			return out == null || out.length < 2 ? raw : out;
		} catch (Throwable t) {
			return raw;
		}
	}

	public static void remove(Identifier sound) {
		Entry e = CLIPS.remove(sound);
		if (e != null) release(e);
	}

	public static void clear() {
		ClipPlayer.stopAll();
		List<Entry> all = new ArrayList<>(CLIPS.values());
		CLIPS.clear();
		for (Entry e : all) release(e);
	}

	public static ClipInfo get(Identifier sound) {
		Entry e = CLIPS.get(sound);
		return e == null ? null : e.info;
	}

	public static Collection<ClipInfo> all() {
		List<ClipInfo> list = new ArrayList<>(CLIPS.size());
		for (Entry e : CLIPS.values()) list.add(e.info);
		return list;
	}

	/** 同じ録音（PCM が一致）か。ピッチだけの変更の判定用。 */
	public static boolean samePcm(Identifier sound, byte[] pcm) {
		Entry e = entry(sound);
		return e != null && pcm != null && java.util.Arrays.equals(e.rawPcm, pcm);
	}

	static Entry entry(Identifier sound) {
		return sound == null ? null : CLIPS.get(sound);
	}

	/** 再生中のソースを先に止めてから AL バッファを解放する。 */
	private static void release(Entry e) {
		ClipPlayer.stopSourcesOf(e);
		ClipPlayer.deleteBuffer(e);
	}
}
