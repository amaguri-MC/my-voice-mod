package net.amaguri.myvoice.client.audio;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.resources.Identifier;

/** サーバーから受け取った声のクリップ置き場。クライアントスレッドからのみ呼ぶ。 */
public final class ClientClips {
	private ClientClips() {}

	public record ClipInfo(Identifier sound, String owner, int sampleRate, float seconds) {}

	/** PCM と（再生時に作る）AL バッファ。 */
	static final class Entry {
		final ClipInfo info;
		final byte[] pcm;
		int alBuffer; // 0 = 未作成

		Entry(ClipInfo info, byte[] pcm) {
			this.info = info;
			this.pcm = pcm;
		}
	}

	private static final Map<Identifier, Entry> CLIPS = new LinkedHashMap<>();

	public static void put(Identifier sound, String owner, int sampleRate, byte[] pcm) {
		if (sound == null || pcm == null || sampleRate <= 0) return;
		remove(sound); // 上書き: 古いソースとバッファを先に片付ける
		byte[] copy = pcm.clone();
		float seconds = (copy.length / 2) / (float) sampleRate;
		CLIPS.put(sound, new Entry(new ClipInfo(sound, owner == null ? "" : owner, sampleRate, seconds), copy));
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

	static Entry entry(Identifier sound) {
		return sound == null ? null : CLIPS.get(sound);
	}

	/** 再生中のソースを先に止めてから AL バッファを解放する。 */
	private static void release(Entry e) {
		ClipPlayer.stopSourcesOf(e);
		ClipPlayer.deleteBuffer(e);
	}
}
