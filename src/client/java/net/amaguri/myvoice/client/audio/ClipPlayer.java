package net.amaguri.myvoice.client.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.amaguri.myvoice.net.VoiceNet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * 自前の OpenAL ソースでクリップを鳴らす。Minecraft の SoundManager/SoundEngine は通さない
 * （= Mixin に再び入らない）。AL コンテキストは Minecraft のものをそのまま使う。
 *
 * 距離減衰はバニラ（Channel.linearAttenuation / disableAttenuation）と同じ:
 *   LINEAR: AL_DISTANCE_MODEL=LINEAR_DISTANCE_CLAMPED, AL_MAX_DISTANCE=max(volume,1)*attenuationDistance(既定16),
 *           AL_ROLLOFF_FACTOR=1, AL_REFERENCE_DISTANCE=0
 *   NONE  : AL_DISTANCE_MODEL=AL_NONE
 */
public final class ClipPlayer {
	private ClipPlayer() {}

	private static final Logger LOGGER = LoggerFactory.getLogger("myvoice/audio");
	private static final int MAX_SOURCES = 32;
	/** AL_EXT 距離モデル定数（AL11 側の値。apidump には AL11 が無いので数値で持つ）。0xD004 = AL_LINEAR_DISTANCE_CLAMPED */
	private static final int AL_LINEAR_DISTANCE_CLAMPED = 0xD004;
	private static final float DEFAULT_ATTENUATION_DISTANCE = 16.0F;

	private static final class Src {
		final int id;
		final ClientClips.Entry entry;
		final long ctx;

		Src(int id, ClientClips.Entry entry, long ctx) {
			this.id = id;
			this.entry = entry;
			this.ctx = ctx;
		}
	}

	private static final List<Src> SOURCES = new ArrayList<>(); // 古い順
	private static int replaced;
	/** playOriginal の間だけ true。SoundManagerMixin が置換も RecentSounds への記録もしない。 */
	private static boolean bypass;
	private static SoundInstance lastOriginal;
	private static int errorCount;
	/** previewTuned の一時エントリ（ClientClips には入れない）。次の試聴か stopAll で片付ける。 */
	private static ClientClips.Entry previewEntry;
	private static int previewGen;

	// ------------------------------------------------------------ 公開 API

	public static boolean tryReplace(SoundInstance s) {
		try {
			if (s == null) return false;
			Identifier id = s.getIdentifier();
			ClientClips.Entry e = ClientClips.entry(id);
			if (e == null) return false;
			replaced++; // OpenAL が使えなくても数える
			if (e.info.volume() <= 0f) return true; // 音量0: 元の音は止めて、何も鳴らさない
			try {
				playInstance(e, s);
			} catch (Throwable t) {
				logError("tryReplace failed", t);
			}
			return true;
		} catch (Throwable t) {
			logError("tryReplace lookup failed", t);
			return false;
		}
	}

	public static boolean preview(Identifier sound) {
		try {
			ClientClips.Entry e = ClientClips.entry(sound);
			if (e == null) return false;
			if (e.info.volume() <= 0f) return true; // 消音の声: 鳴らさない
			float vol = categoryVolume(SoundSource.MASTER);
			start(e, vol, 1.0F, true, 0, 0, 0, false, 0F);
			return true;
		} catch (Throwable t) {
			logError("preview failed", t);
			return false;
		}
	}

	/**
	 * 送らずに手元だけで、その高さ・速さ・無音カット・音量で試聴する（バーを動かしながら聞く用）。前の試聴は止めてから鳴らす。
	 * 声が無ければ false。followPitch は関係なく AL_PITCH は 1.0（加工は DSP 側）。
	 */
	public static boolean previewTuned(Identifier sound, float pitch, float speed, float trim, float volume) {
		try {
			ClientClips.Entry e = ClientClips.entry(sound);
			if (e == null) return false;
			releasePreview();
			float p = VoiceNet.clampTune(pitch);
			float sp = VoiceNet.clampTune(speed);
			float tr = VoiceNet.clampTrim(trim);
			float vol = VoiceNet.clampVolume(volume);
			int gen = ++previewGen;
			if (vol <= 0f) return true; // 消音: 何も鳴らさない（前の試聴は止めた）
			// 高さ・速さ・無音カットが今の声と同じなら DSP 後の PCM を使い回して音量だけ掛ける
			byte[] cached = e.dsp != null && e.info.pitch() == p && e.info.speed() == sp && e.info.trim() == tr ? e.dsp : null;
			ClientClips.tuneAsync(e.rawPcm, e.info.sampleRate(), p, sp, tr, vol, cached, (dsp, tuned) -> {
				// 古い試聴の結果、または声が差し替わった・消えた場合は捨てる
				if (gen != previewGen || ClientClips.entry(sound) != e) return;
				releasePreview();
				ClientClips.Entry tmp = new ClientClips.Entry(e.info, e.rawPcm, tuned);
				previewEntry = tmp;
				start(tmp, categoryVolume(SoundSource.MASTER), 1.0F, true, 0, 0, 0, false, 0F);
			});
			return true;
		} catch (Throwable t) {
			logError("previewTuned failed", t);
			return false;
		}
	}

	private static void releasePreview() {
		ClientClips.Entry prev = previewEntry;
		previewEntry = null;
		if (prev != null) {
			stopSourcesOf(prev);
			deleteBuffer(prev);
		}
	}

	/** 元の音（置換されないバニラの音）を自分だけに聞かせる。音選び画面の試聴用。 */
	public static void playOriginal(Identifier sound) {
		if (sound == null) return;
		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc == null || mc.getSoundManager() == null) return;
			if (lastOriginal != null) mc.getSoundManager().stop(lastOriginal);
			SimpleSoundInstance inst = SimpleSoundInstance.forUI(SoundEvent.createVariableRangeEvent(sound), 1.0F, 1.0F);
			lastOriginal = inst;
			bypass = true;
			try {
				mc.getSoundManager().play(inst);
			} finally {
				bypass = false;
			}
		} catch (Throwable t) {
			logError("playOriginal failed", t);
		}
	}

	/** playOriginal の再生中か（Mixin 用）。 */
	public static boolean isBypassing() {
		return bypass;
	}

	public static void stopAll() {
		for (Src s : new ArrayList<>(SOURCES)) destroy(s);
		SOURCES.clear();
		previewGen++; // 加工待ちの試聴も捨てる
		releasePreview();
	}

	public static int replacedCount() {
		return replaced;
	}

	// ------------------------------------------------------------ パッケージ内

	/** 毎tick: 終わったソースを回収する。 */
	static void tick() {
		if (SOURCES.isEmpty()) return;
		try {
			Iterator<Src> it = SOURCES.iterator();
			while (it.hasNext()) {
				Src s = it.next();
				boolean playing;
				if (s.ctx != currentContext()) { it.remove(); continue; } // エンジン再起動で消えた ID は触らない
				try {
					playing = AL10.alGetSourcei(s.id, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING;
				} catch (Throwable t) {
					playing = false;
				}
				if (!playing) {
					it.remove();
					destroy(s);
				}
			}
		} catch (Throwable t) {
			logError("tick failed", t);
		}
	}

	/** このクリップを鳴らしているソースをすべて止める。 */
	static void stopSourcesOf(ClientClips.Entry e) {
		Iterator<Src> it = SOURCES.iterator();
		while (it.hasNext()) {
			Src s = it.next();
			if (s.entry == e) {
				it.remove();
				destroy(s);
			}
		}
	}

	static void deleteBuffer(ClientClips.Entry e) {
		if (e.alBuffer == 0) return;
		int buf = e.alBuffer;
		long ctx = e.alContext;
		e.alBuffer = 0;
		e.alContext = 0L;
		try {
			if (!alAvailable() || ctx != currentContext()) return; // 別コンテキストの ID は消さない
			AL10.alGetError();
			AL10.alDeleteBuffers(buf);
			checkError("alDeleteBuffers");
		} catch (Throwable t) {
			logError("deleteBuffer failed", t);
		}
	}

	// ------------------------------------------------------------ 内部

	private static void playInstance(ClientClips.Entry e, SoundInstance s) {
		float instVol = 1.0F;
		float pitch = 1.0F;
		SoundSource category = SoundSource.MASTER;
		float attDist = DEFAULT_ATTENUATION_DISTANCE;
		boolean linear = true;
		boolean relative = false;
		double x = 0, y = 0, z = 0;
		// Mixin は SoundManager.play の HEAD で呼ぶので、まだ resolve されていない（sound が null）
		try { s.resolve(Minecraft.getInstance().getSoundManager()); } catch (Throwable ignored) { /* 既定値で鳴らす */ }
		try { instVol = s.getVolume(); } catch (Throwable t) { instVol = 1.0F; }
		try { pitch = s.getPitch(); } catch (Throwable t) { pitch = 1.0F; }
		try { category = s.getSource(); } catch (Throwable t) { category = SoundSource.MASTER; }
		try { relative = s.isRelative(); } catch (Throwable t) { relative = false; }
		try {
			x = s.getX();
			y = s.getY();
			z = s.getZ();
		} catch (Throwable t) { /* 0,0,0 */ }
		try {
			linear = s.getAttenuation() == SoundInstance.Attenuation.LINEAR;
		} catch (Throwable t) { linear = true; }
		try {
			Sound sound = s.getSound(); // resolve 前は EMPTY_SOUND のことがある
			if (sound != null && sound.getAttenuationDistance() > 0) attDist = sound.getAttenuationDistance();
		} catch (Throwable t) { /* 既定 16 */ }
		if (Float.isNaN(instVol)) instVol = 1.0F;
		if (Float.isNaN(pitch) || pitch <= 0) pitch = 1.0F;

		float vol = clamp(instVol * categoryVolume(category), 0F, 1F);
		pitch = e.info.followPitch() ? clamp(pitch, 0.5F, 2.0F) : 1.0F; // 声のまま: ピッチはかけない（音量・距離はそのまま）
		if (vol <= 0F) return; // バニラも無音は鳴らさない
		float maxDist = Math.max(instVol, 1.0F) * attDist;
		start(e, vol, pitch, relative, x, y, z, linear, maxDist);
	}

	/** 設定音量: カテゴリ × マスター（Options.getFinalSoundSourceVolume）。取れなければ 1.0。 */
	private static float categoryVolume(SoundSource category) {
		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc == null || mc.options == null) return 1.0F;
			float v = mc.options.getFinalSoundSourceVolume(category == null ? SoundSource.MASTER : category);
			return Float.isNaN(v) ? 1.0F : clamp(v, 0F, 1F);
		} catch (Throwable t) {
			return 1.0F;
		}
	}

	private static void start(ClientClips.Entry e, float volume, float pitch, boolean relative,
			double x, double y, double z, boolean linear, float maxDist) {
		if (!alAvailable()) return;
		int buffer = ensureBuffer(e);
		if (buffer == 0) return;

		while (SOURCES.size() >= MAX_SOURCES) destroy(SOURCES.remove(0)); // 古いものを止める

		AL10.alGetError(); // 古いエラーを捨てる
		int src = AL10.alGenSources();
		if (!checkError("alGenSources") || src == 0) {
			if (src != 0) AL10.alDeleteSources(src);
			return;
		}
		try {
			AL10.alSourcei(src, AL10.AL_BUFFER, buffer);
			AL10.alSourcei(src, AL10.AL_LOOPING, AL10.AL_FALSE);
			AL10.alSourcef(src, AL10.AL_GAIN, volume);
			AL10.alSourcef(src, AL10.AL_PITCH, pitch);
			AL10.alSourcei(src, AL10.AL_SOURCE_RELATIVE, relative ? AL10.AL_TRUE : AL10.AL_FALSE);
			AL10.alSource3f(src, AL10.AL_POSITION, (float) x, (float) y, (float) z);
			if (linear) {
				AL10.alSourcei(src, AL10.AL_DISTANCE_MODEL, AL_LINEAR_DISTANCE_CLAMPED);
				AL10.alSourcef(src, AL10.AL_MAX_DISTANCE, maxDist);
				AL10.alSourcef(src, AL10.AL_ROLLOFF_FACTOR, 1.0F);
				AL10.alSourcef(src, AL10.AL_REFERENCE_DISTANCE, 0.0F);
			} else {
				AL10.alSourcei(src, AL10.AL_DISTANCE_MODEL, AL10.AL_NONE);
			}
			AL10.alSourcePlay(src);
			if (!checkError("source setup/play")) {
				AL10.alDeleteSources(src);
				return;
			}
			SOURCES.add(new Src(src, e, currentContext()));
		} catch (Throwable t) {
			try { AL10.alDeleteSources(src); } catch (Throwable ignored) { /* ignore */ }
			logError("start failed", t);
		}
	}

	private static int ensureBuffer(ClientClips.Entry e) {
		long ctx = currentContext();
		if (e.pcm == null) return 0; // 加工中は鳴らさない
		if (e.alBuffer != 0 && e.alContext == ctx) return e.alBuffer;
		e.alBuffer = 0; // 古いコンテキストの ID は捨てる（AL は呼ばない）
		try {
			AL10.alGetError();
			int buf = AL10.alGenBuffers();
			if (!checkError("alGenBuffers") || buf == 0) return 0;
			int len = e.pcm.length & ~1;
			ByteBuffer data = ByteBuffer.allocateDirect(len).order(ByteOrder.LITTLE_ENDIAN);
			data.put(e.pcm, 0, len).flip();
			AL10.alBufferData(buf, AL10.AL_FORMAT_MONO16, data, e.info.sampleRate());
			if (!checkError("alBufferData")) {
				AL10.alDeleteBuffers(buf);
				return 0;
			}
			e.alBuffer = buf;
			e.alContext = ctx;
			return buf;
		} catch (Throwable t) {
			logError("ensureBuffer failed", t);
			return 0;
		}
	}

	private static void destroy(Src s) {
		if (s.ctx != currentContext()) return; // 再起動前の ID は触らない
		try {
			AL10.alSourceStop(s.id);
			AL10.alDeleteSources(s.id);
			AL10.alGetError();
		} catch (Throwable t) {
			logError("destroy source failed", t);
		}
	}

	private static long currentContext() {
		try {
			return ALC10.alcGetCurrentContext();
		} catch (Throwable t) {
			return 0L;
		}
	}

	private static boolean alAvailable() {
		try {
			return ALC10.alcGetCurrentContext() != 0L;
		} catch (Throwable t) {
			return false;
		}
	}

	/** true = エラー無し。エラーがあればログ（最初の数回と、その後は間引く）。 */
	private static boolean checkError(String where) {
		int err = AL10.alGetError();
		if (err == AL10.AL_NO_ERROR) return true;
		errorCount++;
		if (errorCount <= 5 || errorCount % 200 == 0) {
			LOGGER.warn("OpenAL error 0x{} at {} (count={})", Integer.toHexString(err), where, errorCount);
		}
		return false;
	}

	private static void logError(String msg, Throwable t) {
		errorCount++;
		if (errorCount <= 5 || errorCount % 200 == 0) {
			LOGGER.warn("{} (count={})", msg, errorCount, t);
		}
	}

	private static float clamp(float v, float lo, float hi) {
		return v < lo ? lo : Math.min(v, hi);
	}
}
