package net.amaguri.myvoice.client.audio;

import java.io.ByteArrayOutputStream;
import java.util.function.Consumer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.amaguri.myvoice.net.VoiceNet;
import net.minecraft.client.Minecraft;

/** マイク録音（javax.sound.sampled）。別スレッドで録り、結果はクライアントスレッドで返す。 */
public final class MicRecorder {
	private MicRecorder() {}

	private static final Logger LOGGER = LoggerFactory.getLogger("myvoice/mic");
	private static final int[] RATES = {VoiceNet.SAMPLE_RATE, 44100, 48000};
	/** 前後の無音を切るしきい値（16bit 振幅）。約 -40 dBFS。 */
	private static final int SILENCE_THRESHOLD = 330;
	/** 切ったあとに残す余白（ミリ秒）。 */
	private static final int TRIM_MARGIN_MS = 60;
	/** 正規化のピーク目標 = -1 dBFS。 */
	private static final double TARGET_PEAK = 32767.0 * 0.8912509381337456;

	private static volatile boolean recording;
	private static volatile boolean stopRequested;
	private static volatile float level;

	public static boolean isRecording() {
		return recording;
	}

	public static float level() {
		return recording ? level : 0F;
	}

	public static void stop() {
		stopRequested = true;
	}

	public static void start(int seconds, Consumer<byte[]> onDone, Consumer<String> onError) {
		synchronized (MicRecorder.class) {
			if (recording) {
				postError(onError, "myvoice.error.already_recording");
				return;
			}
			recording = true;
			stopRequested = false;
			level = 0F;
		}
		int secs = Math.max(1, Math.min(VoiceNet.MAX_SECONDS, seconds));
		Thread t = new Thread(() -> run(secs, onDone, onError), "myvoice-mic");
		t.setDaemon(true);
		try {
			t.start();
		} catch (Throwable e) {
			recording = false;
			postError(onError, "myvoice.error.mic_failed");
		}
	}

	// ---------------------------------------------------------------

	private static void run(int seconds, Consumer<byte[]> onDone, Consumer<String> onError) {
		TargetDataLine line = null;
		String error = null;
		byte[] result = null;
		try {
			int rate = 0;
			for (int r : RATES) {
				AudioFormat fmt = new AudioFormat(r, 16, 1, true, false);
				DataLine.Info info = new DataLine.Info(TargetDataLine.class, fmt);
				try {
					if (!AudioSystem.isLineSupported(info)) continue;
					TargetDataLine l = (TargetDataLine) AudioSystem.getLine(info);
					l.open(fmt);
					line = l;
					rate = r;
					break;
				} catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
					LOGGER.debug("mic open failed at {} Hz: {}", r, e.toString());
				}
			}
			if (line == null) {
				error = "myvoice.error.no_mic";
			} else {
				byte[] raw = capture(line, rate, seconds);
				byte[] pcm = rate == VoiceNet.SAMPLE_RATE ? raw : resample(raw, rate, VoiceNet.SAMPLE_RATE);
				result = process(pcm);
				if (result == null) error = "myvoice.error.silent";
			}
		} catch (Throwable e) {
			LOGGER.warn("recording failed", e);
			error = "myvoice.error.mic_failed";
		} finally {
			if (line != null) {
				try { line.stop(); } catch (Throwable ignored) { /* ignore */ }
				try { line.close(); } catch (Throwable ignored) { /* ignore */ }
			}
			level = 0F;
			recording = false;
		}
		if (error != null) postError(onError, error);
		else postDone(onDone, result);
	}

	private static byte[] capture(TargetDataLine line, int rate, int seconds) {
		int total = rate * 2 * seconds;
		int chunk = Math.max(2, (rate / 50) * 2); // 約 20ms
		byte[] buf = new byte[chunk];
		ByteArrayOutputStream out = new ByteArrayOutputStream(total);
		line.start();
		while (!stopRequested && out.size() < total) {
			int want = Math.min(chunk, total - out.size()) & ~1;
			if (want <= 0) break;
			int n = line.read(buf, 0, want);
			if (n <= 0) break;
			n &= ~1;
			out.write(buf, 0, n);
			level = peakOf(buf, n) / 32768F;
		}
		return out.toByteArray();
	}

	private static int peakOf(byte[] b, int len) {
		int peak = 0;
		for (int i = 0; i + 1 < len; i += 2) {
			int v = Math.abs((short) ((b[i] & 0xFF) | (b[i + 1] << 8)));
			if (v > peak) peak = v;
		}
		return peak;
	}

	/** 面積平均によるリサンプル（16bit LE モノラル）。 */
	static byte[] resample(byte[] in, int fromRate, int toRate) {
		int inN = in.length / 2;
		if (inN == 0 || fromRate == toRate) return in;
		int outN = (int) ((long) inN * toRate / fromRate);
		byte[] out = new byte[outN * 2];
		double ratio = (double) fromRate / toRate;
		for (int i = 0; i < outN; i++) {
			double a = i * ratio;
			double b = Math.min(inN, a + ratio);
			int ia = (int) a;
			double sum = 0, w = 0;
			for (int k = ia; k < inN && k < b; k++) {
				double lo = Math.max(a, k);
				double hi = Math.min(b, k + 1);
				double wt = hi - lo;
				if (wt <= 0) continue;
				sum += sample(in, k) * wt;
				w += wt;
			}
			int v = (int) Math.round(w > 0 ? sum / w : 0);
			v = Math.max(-32768, Math.min(32767, v));
			out[i * 2] = (byte) v;
			out[i * 2 + 1] = (byte) (v >> 8);
		}
		return out;
	}

	private static int sample(byte[] b, int idx) {
		return (short) ((b[idx * 2] & 0xFF) | (b[idx * 2 + 1] << 8));
	}

	/** 前後の無音を切り、ピークを -1 dBFS に正規化する。全部無音なら null。 */
	static byte[] process(byte[] pcm) {
		int n = pcm.length / 2;
		int first = -1, last = -1;
		int peak = 0;
		for (int i = 0; i < n; i++) {
			int v = Math.abs(sample(pcm, i));
			if (v > peak) peak = v;
			if (v >= SILENCE_THRESHOLD) {
				if (first < 0) first = i;
				last = i;
			}
		}
		if (first < 0) return null;
		int margin = VoiceNet.SAMPLE_RATE * TRIM_MARGIN_MS / 1000;
		int from = Math.max(0, first - margin);
		int to = Math.min(n, last + 1 + margin);
		int len = to - from;
		if (len < 1) return null;
		double gain = TARGET_PEAK / peak;
		byte[] out = new byte[len * 2];
		for (int i = 0; i < len; i++) {
			int v = (int) Math.round(sample(pcm, from + i) * gain);
			v = Math.max(-32768, Math.min(32767, v));
			out[i * 2] = (byte) v;
			out[i * 2 + 1] = (byte) (v >> 8);
		}
		if (out.length > VoiceNet.MAX_PCM_BYTES) {
			byte[] cut = new byte[VoiceNet.MAX_PCM_BYTES];
			System.arraycopy(out, 0, cut, 0, cut.length);
			return cut;
		}
		return out;
	}

	private static void postDone(Consumer<byte[]> onDone, byte[] data) {
		if (onDone == null) return;
		try {
			Minecraft.getInstance().execute(() -> onDone.accept(data));
		} catch (Throwable t) {
			LOGGER.warn("onDone dispatch failed", t);
		}
	}

	private static void postError(Consumer<String> onError, String key) {
		if (onError == null) return;
		try {
			Minecraft.getInstance().execute(() -> onError.accept(key));
		} catch (Throwable t) {
			LOGGER.warn("onError dispatch failed", t);
		}
	}
}
