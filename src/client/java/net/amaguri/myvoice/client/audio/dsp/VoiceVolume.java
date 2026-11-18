package net.amaguri.myvoice.client.audio.dsp;

/**
 * 音量（純Java・MC 非依存）。1 以下は単純な倍率。1 を超える分は先読みのソフトリミッタ
 * （ピークに合わせてゲインをなめらかに下げる）で割れないようにする。波形そのものを折らないので歪みが少ない。
 */
public final class VoiceVolume {
	private VoiceVolume() {}

	/** リミッタの天井（満尺比）。 */
	private static final double CEILING = 0.97;
	/** 先読み・なだらかにする幅（サンプル。22050Hz で約 5ms ずつ）。 */
	private static final int WINDOW = 110;

	/**
	 * 16bit LE モノラル PCM に音量を掛けた新しい配列を返す。volume=1 は入力のコピー、0 は全部 0。
	 * volume>1 のとき、天井を超える部分だけゲインを下げる（出力は 32767 を超えない）。
	 */
	public static byte[] apply(byte[] pcm, float volume) {
		if (pcm == null) return new byte[0];
		int n = pcm.length / 2;
		byte[] out = new byte[n * 2];
		if (!(volume > 0f)) return out; // 0・負・NaN は無音
		if (volume == 1.0f) {
			System.arraycopy(pcm, 0, out, 0, out.length);
			return out;
		}
		double[] x = new double[n];
		boolean over = false;
		for (int i = 0; i < n; i++) {
			x[i] = (short) ((pcm[2 * i] & 0xFF) | (pcm[2 * i + 1] << 8)) / 32768.0 * volume;
			if (Math.abs(x[i]) > CEILING) over = true;
		}
		if (over) limit(x);
		for (int i = 0; i < n; i++) {
			long v = Math.round(x[i] * 32768.0);
			if (v > 32767) v = 32767;
			else if (v < -32768) v = -32768;
			out[2 * i] = (byte) v;
			out[2 * i + 1] = (byte) (v >> 8);
		}
		return out;
	}

	/** 各サンプルが天井に収まる最大ゲイン → 前後 WINDOW の最小 → 前後 WINDOW の平均（平均は必ず元の最大ゲイン以下）。 */
	private static void limit(double[] x) {
		int n = x.length;
		double[] g = new double[n];
		for (int i = 0; i < n; i++) {
			double a = Math.abs(x[i]);
			g[i] = a > CEILING ? CEILING / a : 1.0;
		}
		double[] m = new double[n]; // 窓内の最小（単調キューで O(n)）
		int[] dq = new int[n];
		int head = 0, tail = 0;
		int next = 0; // 次に入れる添字
		for (int i = 0; i < n; i++) {
			int hi = Math.min(n - 1, i + WINDOW);
			while (next <= hi) {
				while (tail > head && g[dq[tail - 1]] >= g[next]) tail--;
				dq[tail++] = next++;
			}
			while (dq[head] < i - WINDOW) head++;
			m[i] = g[dq[head]];
		}
		double[] prefix = new double[n + 1];
		for (int i = 0; i < n; i++) prefix[i + 1] = prefix[i] + m[i];
		for (int i = 0; i < n; i++) {
			int lo = Math.max(0, i - WINDOW), hi = Math.min(n - 1, i + WINDOW);
			double avg = (prefix[hi + 1] - prefix[lo]) / (hi - lo + 1);
			x[i] *= avg;
		}
	}
}
