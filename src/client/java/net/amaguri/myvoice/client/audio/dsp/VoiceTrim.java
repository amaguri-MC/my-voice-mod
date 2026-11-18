package net.amaguri.myvoice.client.audio.dsp;

/**
 * 録音の前後の無音カット（MC 非依存・純Java）。元の PCM は変えず、残す範囲（Cut）を求めて切り出す。
 * 判定は 10ms 窓の RMS。しきい値 = max(最大窓RMS × 10^(-dB/20), 雑音RMS × 係数)。
 *  dB: trim 0.01 → -60、0.35 → -40、1 → -18（連続・折れ線）。
 *  雑音係数: trim &lt; 0.15 なら使わない。それ以上は 1.0 → 2.0 まで。
 *  余白: 前 120ms、後ろ 350ms（trim 0.35 以上で 150ms まで縮む）。
 */
public final class VoiceTrim {
	private VoiceTrim() {}

	/** 残す範囲 [fromSample, toSample)。空なら from == to。 */
	public record Cut(int fromSample, int toSample) {}

	private static final int WIN_MS = 10;
	private static final int HEAD_MARGIN_MS = 120;
	private static final int TAIL_MARGIN_MS = 350;
	private static final int TAIL_MARGIN_MIN_MS = 150;
	private static final int FADE_IN_MS = 5;
	private static final int FADE_OUT_MS = 40;

	/** 10ms 窓ごとの RMS。画面のバーを動かしながら findCut を何度も呼ぶ用に、呼び出し側でキャッシュする。 */
	public static double[] windowRms(byte[] pcm, int sampleRate) {
		int n = pcm == null ? 0 : pcm.length / 2;
		int win = windowSize(sampleRate);
		int windows = (n + win - 1) / win;
		double[] rms = new double[windows];
		for (int w = 0; w < windows; w++) {
			int from = w * win, to = Math.min(n, from + win);
			double sum = 0;
			for (int i = from; i < to; i++) {
				double v = sample(pcm, i);
				sum += v * v;
			}
			rms[w] = Math.sqrt(sum / Math.max(1, to - from));
		}
		return rms;
	}

	public static Cut findCut(byte[] pcm, int sampleRate, float trim) {
		int n = pcm == null ? 0 : pcm.length / 2;
		if (n == 0 || !(trim > 0f)) return new Cut(0, n);
		return findCut(windowRms(pcm, sampleRate), n, sampleRate, trim);
	}

	/** キャッシュ済みの窓 RMS から範囲を求める（しきい値判定だけ）。 */
	public static Cut findCut(double[] rms, int totalSamples, int sampleRate, float trim) {
		int n = totalSamples;
		if (n <= 0) return new Cut(0, 0);
		if (!(trim > 0f) || rms == null || rms.length == 0) return new Cut(0, n);
		float t = Math.min(1f, trim);
		int win = windowSize(sampleRate);

		double maxRms = 0;
		for (double v : rms) maxRms = Math.max(maxRms, v);
		if (maxRms < 1.0) return new Cut(0, n); // 無音: 切らない

		double threshold = maxRms * Math.pow(10.0, -thresholdDb(t) / 20.0);
		if (t >= 0.15f) {
			double[] sorted = rms.clone();
			java.util.Arrays.sort(sorted);
			double noise = sorted[Math.min(sorted.length - 1, sorted.length / 10)]; // 静かな方から 10%
			double factor = 1.0 + (t - 0.15) / 0.85;
			threshold = Math.max(threshold, noise * factor);
		}
		threshold = Math.min(threshold, maxRms * 0.25); // 雑音が大きくても声は残す

		int firstW = -1, lastW = -1;
		for (int w = 0; w < rms.length; w++) {
			if (rms[w] >= threshold) {
				if (firstW < 0) firstW = w;
				lastW = w;
			}
		}
		if (firstW < 0) return new Cut(0, n);
		double tailMs = t <= 0.35f ? TAIL_MARGIN_MS
				: TAIL_MARGIN_MS - (TAIL_MARGIN_MS - TAIL_MARGIN_MIN_MS) * (t - 0.35) / 0.65;
		int from = Math.max(0, firstW * win - (int) ((long) sampleRate * HEAD_MARGIN_MS / 1000));
		int to = Math.min(n, (lastW + 1) * win + (int) (sampleRate * tailMs / 1000.0));
		if (to <= from) return new Cut(0, n);
		return new Cut(from, to);
	}

	/** cut の範囲を切り出し、端に短いフェードをかける。正規化はしない。範囲外は丸める。 */
	public static byte[] apply(byte[] pcm, int sampleRate, Cut cut) {
		int n = pcm == null ? 0 : pcm.length / 2;
		int from = cut == null ? 0 : Math.max(0, Math.min(n, cut.fromSample()));
		int to = cut == null ? n : Math.max(from, Math.min(n, cut.toSample()));
		int len = to - from;
		if (len <= 0) return new byte[0];
		int fadeIn = Math.min(len / 2, sampleRate * FADE_IN_MS / 1000);
		int fadeOut = Math.min(len / 2, sampleRate * FADE_OUT_MS / 1000);
		byte[] out = new byte[len * 2];
		for (int i = 0; i < len; i++) {
			double g = 1.0;
			if (i < fadeIn) g *= (double) i / fadeIn;
			int fromEnd = len - 1 - i;
			if (fromEnd < fadeOut) g *= (double) fromEnd / fadeOut;
			int v = (int) Math.round(sample(pcm, from + i) * g);
			v = Math.max(-32768, Math.min(32767, v));
			out[i * 2] = (byte) v;
			out[i * 2 + 1] = (byte) (v >> 8);
		}
		return out;
	}

	/** trim → しきい値（最大窓RMS からの下がり dB、正の値）。0.01→60、0.35→40、1→18。 */
	private static double thresholdDb(float t) {
		if (t <= 0.01f) return 60.0;
		if (t <= 0.35f) return 60.0 - 20.0 * (t - 0.01) / 0.34;
		return 40.0 - 22.0 * (t - 0.35) / 0.65;
	}

	private static int windowSize(int sampleRate) {
		return Math.max(1, sampleRate * WIN_MS / 1000);
	}

	private static int sample(byte[] b, int idx) {
		return (short) ((b[idx * 2] & 0xFF) | (b[idx * 2 + 1] << 8));
	}
}
