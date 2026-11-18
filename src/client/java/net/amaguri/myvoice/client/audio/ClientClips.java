package net.amaguri.myvoice.client.audio;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.amaguri.myvoice.client.audio.dsp.VoiceDsp;
import net.amaguri.myvoice.client.audio.dsp.VoiceTrim;
import net.amaguri.myvoice.client.audio.dsp.VoiceVolume;
import net.amaguri.myvoice.net.VoiceNet;
import net.minecraft.resources.Identifier;

/** サーバーから受け取った声のクリップ置き場。クライアントスレッドからのみ呼ぶ。 */
public final class ClientClips {
	private ClientClips() {}

	public record ClipInfo(Identifier sound, String owner, int sampleRate, float seconds, boolean followPitch, float pitch, float speed, float trim, float volume) {}

	/** PCM と（再生時に作る）AL バッファ。 */
	static final class Entry {
		final ClipInfo info;
		byte[] pcm; // AL バッファの元になる加工後 PCM（音量まで）。null = 加工中（まだ鳴らさない）
		byte[] dsp; // trim → DSP までの PCM（音量を掛ける前）。音量だけ変わるときの作り直し用キャッシュ。null = 未完了
		final byte[] rawPcm; // 元の録音（無加工）
		final double[] rms; // 元 PCM の 10ms 窓 RMS（put 時に1回。cutFraction 用。null = 作っていない）
		float[] envCache; // envelope の直近の結果
		int envBuckets;
		int alBuffer; // 0 = 未作成
		long alContext; // alBuffer を作った AL コンテキスト（サウンドエンジン再起動で変わる）

		Entry(ClipInfo info, byte[] rawPcm, byte[] pcm) {
			this(info, rawPcm, pcm, null);
		}

		Entry(ClipInfo info, byte[] rawPcm, byte[] pcm, double[] rms) {
			this.info = info;
			this.rawPcm = rawPcm;
			this.pcm = pcm;
			this.rms = rms;
		}
	}

	private static final Map<Identifier, Entry> CLIPS = new LinkedHashMap<>();

	/**
	 * 加工順は 元の録音 → VoiceTrim(trim) → VoiceDsp(pitch, speed) → VoiceVolume(volume)。どれも別スレッド。
	 * 全部が素通し（trim=0, pitch=speed=1, volume=1）なら元のまま即座に鳴らせる。
	 * 音量だけが変わったとき（録音・trim・pitch・speed が同じ）は、前の DSP 後の PCM を使い回して音量だけ掛け直す。
	 */
	public static void put(Identifier sound, String owner, int sampleRate, byte[] pcm, boolean followPitch, float pitch, float speed, float trim, float volume) {
		if (sound == null || pcm == null || sampleRate <= 0) return;
		pitch = VoiceNet.clampTune(pitch);
		speed = VoiceNet.clampTune(speed);
		trim = VoiceNet.clampTrim(trim);
		volume = VoiceNet.clampVolume(volume);
		Entry old = CLIPS.get(sound);
		byte[] cachedDsp = null;
		if (old != null && old.dsp != null && old.info.sampleRate() == sampleRate && old.info.pitch() == pitch
				&& old.info.speed() == speed && old.info.trim() == trim && java.util.Arrays.equals(old.rawPcm, pcm)) {
			cachedDsp = old.dsp;
		}
		remove(sound); // 上書き: 古いソースとバッファを先に片付ける（値が変わったら AL バッファも作り直しになる）
		byte[] raw = cachedDsp != null && old != null ? old.rawPcm : pcm.clone();
		boolean plain = pitch == 1.0f && speed == 1.0f && trim == 0f;
		double[] rms = old != null && cachedDsp != null ? old.rms : VoiceTrim.windowRms(raw, sampleRate); // 1回だけ
		int total = raw.length / 2;
		VoiceTrim.Cut cut = VoiceTrim.findCut(rms, total, sampleRate, trim);
		float seconds = (cut.toSample() - cut.fromSample()) / (float) sampleRate / speed; // 長さは先に決める（加工は別スレッド）
		byte[] dspNow = cachedDsp != null ? cachedDsp : plain ? raw : null;
		byte[] pcmNow = dspNow == null ? null : volume == 1.0f ? dspNow : null;
		Entry e = new Entry(new ClipInfo(sound, owner == null ? "" : owner, sampleRate, seconds, followPitch, pitch, speed, trim, volume), raw, pcmNow, rms);
		e.dsp = dspNow;
		CLIPS.put(sound, e);
		if (pcmNow == null) {
			tuneAsync(raw, sampleRate, pitch, speed, trim, volume, dspNow, (dsp, fin) -> {
				// 世代確認: 上書き・削除されていたら捨てる（put のたびに新しい Entry）
				if (CLIPS.get(sound) != e) return;
				ClipPlayer.stopSourcesOf(e);
				ClipPlayer.deleteBuffer(e);
				e.dsp = dsp;
				e.pcm = fin;
			});
		}
	}

	/** 元の録音の波形: buckets 個の区間ごとのピーク 0..1。声が無ければ null。画面用（結果は直近の1つをキャッシュ）。 */
	public static float[] envelope(Identifier sound, int buckets) {
		Entry e = entry(sound);
		if (e == null || buckets <= 0) return null;
		if (e.envCache != null && e.envBuckets == buckets) return e.envCache.clone();
		int n = e.rawPcm.length / 2;
		float[] out = new float[buckets];
		for (int b = 0; b < buckets; b++) {
			int from = (int) ((long) n * b / buckets);
			int to = Math.max(from + 1, (int) ((long) n * (b + 1) / buckets));
			int peak = 0;
			for (int i = from; i < to && i < n; i++) {
				int v = Math.abs((short) ((e.rawPcm[i * 2] & 0xFF) | (e.rawPcm[i * 2 + 1] << 8)));
				if (v > peak) peak = v;
			}
			out[b] = Math.min(1f, peak / 32768f);
		}
		e.envCache = out;
		e.envBuckets = buckets;
		return out.clone();
	}

	/** その trim で残る範囲の {先頭, 末尾}（0..1）。キャッシュ済みの窓 RMS でしきい値判定だけするので軽い。声が無ければ null。 */
	public static float[] cutFraction(Identifier sound, float trim) {
		Entry e = entry(sound);
		if (e == null) return null;
		int n = e.rawPcm.length / 2;
		if (n == 0) return new float[] {0f, 1f};
		VoiceTrim.Cut c = VoiceTrim.findCut(e.rms, n, e.info.sampleRate(), VoiceNet.clampTrim(trim));
		return new float[] {c.fromSample() / (float) n, c.toSample() / (float) n};
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

	/**
	 * 単一スレッドで加工（trim → DSP → 音量）し、結果をクライアントスレッドで渡す。
	 * cachedDsp があれば trim・DSP は飛ばして音量だけ掛ける。onMain には (DSP 後, 音量後) を渡す。
	 */
	static void tuneAsync(byte[] raw, int sampleRate, float pitch, float speed, float trim, float volume, byte[] cachedDsp,
			java.util.function.BiConsumer<byte[], byte[]> onMain) {
		DSP.execute(() -> {
			byte[] dsp = cachedDsp != null ? cachedDsp : tune(raw, sampleRate, pitch, speed, trim);
			byte[] fin;
			try {
				fin = volume == 1.0f ? dsp : VoiceVolume.apply(dsp, volume);
			} catch (Throwable t) {
				fin = dsp;
			}
			byte[] finalPcm = fin;
			net.minecraft.client.Minecraft.getInstance().execute(() -> onMain.accept(dsp, finalPcm));
		});
	}

	/** trim → DSP の加工。何も要らなければ元のまま。失敗したら元の PCM で鳴らす。 */
	static byte[] tune(byte[] raw, int sampleRate, float pitch, float speed, float trim) {
		byte[] cur = raw;
		try {
			if (trim > 0f) {
				byte[] cutPcm = VoiceTrim.apply(raw, sampleRate, VoiceTrim.findCut(raw, sampleRate, trim));
				if (cutPcm.length >= 2) cur = cutPcm;
			}
		} catch (Throwable t) {
			cur = raw;
		}
		if (pitch == 1.0f && speed == 1.0f) return cur;
		try {
			byte[] out = VoiceDsp.process(cur, sampleRate, pitch, speed);
			return out == null || out.length < 2 ? cur : out;
		} catch (Throwable t) {
			return cur;
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
