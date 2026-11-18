package net.amaguri.myvoice.net;

import net.amaguri.myvoice.MyVoiceMod;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 契約: 通信の型と定数。司令塔だけが変更する（作業者は触らない）。
 * 音声データは「16bit 符号付き・リトルエンディアン・モノラル PCM」。
 */
public final class VoiceNet {
	private VoiceNet() {}

	/** 録音のサンプルレート（Hz）。全員この値で送る。 */
	public static final int SAMPLE_RATE = 22050;
	/** 録音の最大秒数。 */
	public static final int MAX_SECONDS = 10;
	/** PCM の最大バイト数（= SAMPLE_RATE * 2byte * MAX_SECONDS）。 */
	public static final int MAX_PCM_BYTES = SAMPLE_RATE * 2 * MAX_SECONDS;
	/** 声の高さ（ピッチ）・速さの倍率の範囲。1.0 = そのまま。 */
	public static final float MIN_TUNE = 0.5f;
	public static final float MAX_TUNE = 2.0f;
	public static float clampTune(float v) {
		return Float.isFinite(v) ? Math.max(MIN_TUNE, Math.min(MAX_TUNE, v)) : 1.0f;
	}

	/**
	 * 無音カットの強さ。0 = 切らない、1 = 強く切る。既定 DEFAULT_TRIM。
	 * 元の録音は切らずに保存・配布し、各クライアントで切る（あとから音ごとに変えられる）。
	 */
	public static final float DEFAULT_TRIM = 0.35f;
	public static float clampTrim(float v) {
		return Float.isFinite(v) ? Math.max(0f, Math.min(1f, v)) : DEFAULT_TRIM;
	}

	/** 1 プレイヤーが登録できるクリップ数の上限（サーバー側で数える）。 */
	public static final int MAX_CLIPS_PER_PLAYER = 64;
	private static final int MAX_PACKET = MAX_PCM_BYTES + 4096;

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MyVoiceMod.MOD_ID, path);
	}

	/**
	 * C→S: 録音したクリップを sound に割り当てる（同じ sound があれば上書き）。
	 * followPitch: true = ゲームが鳴らすときのピッチ（ランダムな揺れ・0.8倍など）を声にもかける / false = 声のまま（既定）。
	 */
	public record UploadClipC2S(Identifier sound, int sampleRate, byte[] pcm, boolean followPitch, float pitch, float speed, float trim) implements CustomPacketPayload {
		public static final Type<UploadClipC2S> TYPE = new Type<>(id("upload_clip"));
		public static final StreamCodec<RegistryFriendlyByteBuf, UploadClipC2S> CODEC = CustomPacketPayload.codec(
				(p, b) -> { b.writeIdentifier(p.sound); b.writeVarInt(p.sampleRate); b.writeByteArray(p.pcm); b.writeBoolean(p.followPitch); b.writeFloat(p.pitch); b.writeFloat(p.speed); b.writeFloat(p.trim); },
				b -> new UploadClipC2S(b.readIdentifier(), b.readVarInt(), b.readByteArray(MAX_PCM_BYTES), b.readBoolean(), b.readFloat(), b.readFloat(), b.readFloat()));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/**
	 * C→S: 録音し直さずに声の調整だけ変える（誰でも可。無ければ not_found）。
	 * pitch = 声の高さの倍率、speed = 速さの倍率（どちらも MIN_TUNE..MAX_TUNE、高さと速さは独立）。
	 */
	public record SetTuneC2S(Identifier sound, boolean followPitch, float pitch, float speed, float trim) implements CustomPacketPayload {
		public static final Type<SetTuneC2S> TYPE = new Type<>(id("set_tune"));
		public static final StreamCodec<RegistryFriendlyByteBuf, SetTuneC2S> CODEC = CustomPacketPayload.codec(
				(p, b) -> { b.writeIdentifier(p.sound); b.writeBoolean(p.followPitch); b.writeFloat(p.pitch); b.writeFloat(p.speed); b.writeFloat(p.trim); },
				b -> new SetTuneC2S(b.readIdentifier(), b.readBoolean(), b.readFloat(), b.readFloat(), b.readFloat()));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** C→S: sound の割り当てを消す。 */
	public record ClearClipC2S(Identifier sound) implements CustomPacketPayload {
		public static final Type<ClearClipC2S> TYPE = new Type<>(id("clear_clip"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ClearClipC2S> CODEC = CustomPacketPayload.codec(
				(p, b) -> b.writeIdentifier(p.sound),
				b -> new ClearClipC2S(b.readIdentifier()));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** S→C: sound にクリップが割り当てられた（参加時の同期でも使う）。owner は録音した人の名前。 */
	public record ClipS2C(Identifier sound, String owner, int sampleRate, byte[] pcm, boolean followPitch, float pitch, float speed, float trim) implements CustomPacketPayload {
		public static final Type<ClipS2C> TYPE = new Type<>(id("clip"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ClipS2C> CODEC = CustomPacketPayload.codec(
				(p, b) -> { b.writeIdentifier(p.sound); b.writeUtf(p.owner, 64); b.writeVarInt(p.sampleRate); b.writeByteArray(p.pcm); b.writeBoolean(p.followPitch); b.writeFloat(p.pitch); b.writeFloat(p.speed); b.writeFloat(p.trim); },
				b -> new ClipS2C(b.readIdentifier(), b.readUtf(64), b.readVarInt(), b.readByteArray(MAX_PCM_BYTES), b.readBoolean(), b.readFloat(), b.readFloat(), b.readFloat()));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** S→C: sound の割り当てが消えた。by は消した人の名前。 */
	public record ClipRemovedS2C(Identifier sound, String by) implements CustomPacketPayload {
		public static final Type<ClipRemovedS2C> TYPE = new Type<>(id("clip_removed"));
		public static final StreamCodec<RegistryFriendlyByteBuf, ClipRemovedS2C> CODEC = CustomPacketPayload.codec(
				(p, b) -> { b.writeIdentifier(p.sound); b.writeUtf(p.by, 64); },
				b -> new ClipRemovedS2C(b.readIdentifier(), b.readUtf(64)));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** S→C: 結果メッセージ（翻訳キー + 引数）。アップロード拒否などをクライアントに知らせる。 */
	public record NoticeS2C(String key, String arg) implements CustomPacketPayload {
		public static final Type<NoticeS2C> TYPE = new Type<>(id("notice"));
		public static final StreamCodec<RegistryFriendlyByteBuf, NoticeS2C> CODEC = CustomPacketPayload.codec(
				(p, b) -> { b.writeUtf(p.key, 128); b.writeUtf(p.arg, 256); },
				b -> new NoticeS2C(b.readUtf(128), b.readUtf(256)));
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** MyVoiceMod.onInitialize から一度だけ呼ぶ。 */
	public static void registerTypes() {
		PayloadTypeRegistry.serverboundPlay().registerLarge(UploadClipC2S.TYPE, UploadClipC2S.CODEC, MAX_PACKET);
		PayloadTypeRegistry.serverboundPlay().register(ClearClipC2S.TYPE, ClearClipC2S.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SetTuneC2S.TYPE, SetTuneC2S.CODEC);
		PayloadTypeRegistry.clientboundPlay().registerLarge(ClipS2C.TYPE, ClipS2C.CODEC, MAX_PACKET);
		PayloadTypeRegistry.clientboundPlay().register(ClipRemovedS2C.TYPE, ClipRemovedS2C.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(NoticeS2C.TYPE, NoticeS2C.CODEC);
	}
}
