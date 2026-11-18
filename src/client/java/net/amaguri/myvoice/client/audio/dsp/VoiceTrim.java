package net.amaguri.myvoice.client.audio.dsp;

/**
 * 録音の前後の無音を切り、ピークを正規化する（MC 非依存）。
 * 語尾の減衰を切らないよう、しきい値はピーク基準（-34dB）と雑音レベル基準の大きい方、
 * 判定は 10ms 窓の RMS、余白は前 120ms・後ろ 350ms、端には短いフェードをかける。
 */
public final class VoiceTrim {
	private VoiceTrim() {}

	public static final double TARGET_PEAK = 32767.0 * 0.8912509381337456; // -1 dBFS
	private static final int HEAD_MARGIN_MS = 120;
	private static final int TAIL_MARGIN_MS = 350;
	private static final int FADE_IN_MS = 5;
	private static final int FADE_OUT_MS = 40;
	private static final int WIN_MS = 10;

	/** pcm16le モノラル。全部無音なら null。maxBytes を超えたら先頭から maxBytes で切る（末尾はフェード）。 */
	public static byte[] process(byte[] pcm, int sampleRate, int maxBytes) {
		int n = pcm.length / 2;
		if (n == 0) return null;
		int peak = 0;
		for (int i = 0; i < n; i++) peak = Math.max(peak, Math.abs(sample(pcm, i)));
		if (peak < 200) return null; // ほぼ無音

		int win = Math.max(1, sampleRate * WIN_MS / 1000);
		int windows = (n + win - 1) / win;
		double[] rms = new double[windows];
		for (int w = 0; w < windows; w++) {
			int from = w * win, to = Math.min(n, from + win);
			double sum = 0;
			for (int i = from; i < to; i++) { double v = sample(pcm, i); sum += v * v; }
			rms[w] = Math.sqrt(sum / Math.max(1, to - from));
		}
		// 雑音レベル = 静かな方から 10% の窓の RMS
		double[] sorted = rms.clone();
		java.util.Arrays.sort(sorted);
		double noise = sorted[Math.min(sorted.length - 1, sorted.length / 10)];
		double maxRms = sorted[sorted.length - 1];
		double threshold = Math.max(maxRms * 0.02, noise * 1.6); // 一番大きい窓の-34dB か 雑音の約+4dB
		threshold = Math.min(threshold, maxRms * 0.25); // 雑音が大きすぎても声は残す

		int firstW = -1, lastW = -1;
		for (int w = 0; w < windows; w++) {
			if (rms[w] >= threshold) {
				if (firstW < 0) firstW = w;
				lastW = w;
			}
		}
		if (firstW < 0) return null;
		int from = Math.max(0, firstW * win - sampleRate * HEAD_MARGIN_MS / 1000);
		int to = Math.min(n, (lastW + 1) * win + sampleRate * TAIL_MARGIN_MS / 1000);
		int len = Math.min(to - from, maxBytes / 2);
		if (len < 1) return null;

		double gain = TARGET_PEAK / peak;
		int fadeIn = Math.min(len / 2, sampleRate * FADE_IN_MS / 1000);
		int fadeOut = Math.min(len / 2, sampleRate * FADE_OUT_MS / 1000);
		byte[] out = new byte[len * 2];
		for (int i = 0; i < len; i++) {
			double g = gain;
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

	private static int sample(byte[] b, int idx) {
		return (short) ((b[idx * 2] & 0xFF) | (b[idx * 2 + 1] << 8));
	}
}
