package net.amaguri.myvoice.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.amaguri.myvoice.MyVoiceMod;
import net.amaguri.myvoice.net.VoiceNet;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** サーバー側: クリップの検証・保存・配布。クライアント専用クラスは使わない。 */
public final class VoiceServer {
	private static final Logger LOGGER = LoggerFactory.getLogger(MyVoiceMod.MOD_ID + "/server");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final long MIN_INTERVAL_NANOS = 1_000_000_000L;

	private record Clip(Identifier sound, String owner, UUID ownerId, int sampleRate, byte[] pcm, boolean followPitch) {}

	private static final Map<Identifier, Clip> CLIPS = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> LAST_UPLOAD = new ConcurrentHashMap<>();
	private static volatile Path dir;

	private VoiceServer() {}

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(VoiceServer::load);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			CLIPS.clear();
			LAST_UPLOAD.clear();
			dir = null;
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.getPlayer();
			if (!ServerPlayNetworking.canSend(player, VoiceNet.ClipS2C.TYPE)) return;
			for (Clip c : CLIPS.values()) {
				ServerPlayNetworking.send(player, toPacket(c));
			}
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				LAST_UPLOAD.remove(handler.getPlayer().getUUID()));
		ServerPlayNetworking.registerGlobalReceiver(VoiceNet.UploadClipC2S.TYPE, (payload, ctx) ->
				ctx.server().execute(() -> onUpload(ctx.server(), ctx.player(), payload)));
		ServerPlayNetworking.registerGlobalReceiver(VoiceNet.ClearClipC2S.TYPE, (payload, ctx) ->
				ctx.server().execute(() -> onClear(ctx.server(), ctx.player(), payload)));
		ServerPlayNetworking.registerGlobalReceiver(VoiceNet.SetPitchModeC2S.TYPE, (payload, ctx) ->
				ctx.server().execute(() -> onSetPitchMode(ctx.server(), ctx.player(), payload)));
	}

	/** テスト用。 */
	public static boolean has(Identifier sound) {
		return sound != null && CLIPS.containsKey(sound);
	}

	/** テスト用。 */
	public static int clipCount() {
		return CLIPS.size();
	}

	// ---- 受信 ----

	private static void onUpload(MinecraftServer server, ServerPlayer player, VoiceNet.UploadClipC2S p) {
		try {
			byte[] pcm = p.pcm();
			String reason = null;
			if (p.sampleRate() != VoiceNet.SAMPLE_RATE) reason = "bad_rate";
			else if (pcm == null || pcm.length < 2 || pcm.length > VoiceNet.MAX_PCM_BYTES || (pcm.length & 1) != 0) reason = "bad_length";
			UUID id = player.getUUID();
			if (reason == null) {
				long now = System.nanoTime();
				Long last = LAST_UPLOAD.get(id);
				if (last != null && now - last < MIN_INTERVAL_NANOS) reason = "too_fast";
				else {
					Clip existing = CLIPS.get(p.sound());
					boolean replacingOwn = existing != null && id.equals(existing.ownerId());
					if (!replacingOwn && countOwned(id) >= VoiceNet.MAX_CLIPS_PER_PLAYER) reason = "too_many";
				}
			}
			if (reason != null) {
				ServerPlayNetworking.send(player, new VoiceNet.NoticeS2C("myvoice.notice.rejected", reason));
				return;
			}
			LAST_UPLOAD.put(id, System.nanoTime());
			Clip clip = new Clip(p.sound(), player.getPlainTextName(), id, p.sampleRate(), pcm.clone(), p.followPitch());
			CLIPS.put(clip.sound(), clip); // 上書きは置換
			save(server, clip);
			broadcast(server, clip);
			ServerPlayNetworking.send(player, new VoiceNet.NoticeS2C("myvoice.notice.saved", p.sound().toString()));
		} catch (Exception e) {
			LOGGER.warn("Failed to handle upload from {}", player.getPlainTextName(), e);
		}
	}

	private static void onSetPitchMode(MinecraftServer server, ServerPlayer player, VoiceNet.SetPitchModeC2S p) {
		try {
			Clip existing = CLIPS.get(p.sound());
			if (existing == null) {
				ServerPlayNetworking.send(player, new VoiceNet.NoticeS2C("myvoice.notice.not_found", p.sound().toString()));
				return;
			}
			UUID id = player.getUUID();
			long now = System.nanoTime();
			Long last = LAST_UPLOAD.get(id);
			if (last != null && now - last < MIN_INTERVAL_NANOS) {
				ServerPlayNetworking.send(player, new VoiceNet.NoticeS2C("myvoice.notice.rejected", "too_fast"));
				return;
			}
			LAST_UPLOAD.put(id, now);
			Clip clip = new Clip(existing.sound(), existing.owner(), existing.ownerId(), existing.sampleRate(), existing.pcm(), p.followPitch());
			CLIPS.put(clip.sound(), clip);
			writeIndex();
			broadcast(server, clip);
		} catch (Exception e) {
			LOGGER.warn("Failed to handle pitch mode from {}", player.getPlainTextName(), e);
		}
	}

	private static void broadcast(MinecraftServer server, Clip clip) {
		VoiceNet.ClipS2C packet = toPacket(clip);
		for (ServerPlayer other : server.getPlayerList().getPlayers()) {
			if (ServerPlayNetworking.canSend(other, VoiceNet.ClipS2C.TYPE)) {
				ServerPlayNetworking.send(other, packet);
			}
		}
	}

	private static void onClear(MinecraftServer server, ServerPlayer player, VoiceNet.ClearClipC2S p) {
		try {
			Clip removed = CLIPS.remove(p.sound());
			if (removed == null) {
				ServerPlayNetworking.send(player, new VoiceNet.NoticeS2C("myvoice.notice.not_found", p.sound().toString()));
				return;
			}
			deleteFile(removed);
			writeIndex();
			VoiceNet.ClipRemovedS2C packet = new VoiceNet.ClipRemovedS2C(p.sound(), player.getPlainTextName());
			for (ServerPlayer other : server.getPlayerList().getPlayers()) {
				if (ServerPlayNetworking.canSend(other, VoiceNet.ClipRemovedS2C.TYPE)) {
					ServerPlayNetworking.send(other, packet);
				}
			}
		} catch (Exception e) {
			LOGGER.warn("Failed to handle clear from {}", player.getPlainTextName(), e);
		}
	}

	private static int countOwned(UUID id) {
		int n = 0;
		for (Clip c : CLIPS.values()) if (id.equals(c.ownerId())) n++;
		return n;
	}

	private static VoiceNet.ClipS2C toPacket(Clip c) {
		return new VoiceNet.ClipS2C(c.sound(), c.owner(), c.sampleRate(), c.pcm(), c.followPitch());
	}

	// ---- 保存・読込 ----

	private static String fileName(Identifier sound) {
		String s = sound.toString();
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			char ch = s.charAt(i);
			boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_' || ch == '-' || ch == '.';
			sb.append(ok ? ch : '_');
		}
		// 変換で衝突しないようにハッシュを付ける
		return sb + "_" + Integer.toHexString(s.hashCode()) + ".pcm";
	}

	private static void load(MinecraftServer server) {
		CLIPS.clear();
		try {
			dir = server.getWorldPath(LevelResource.ROOT).resolve("myvoice");
			Files.createDirectories(dir);
		} catch (Exception e) {
			LOGGER.warn("Cannot prepare myvoice directory", e);
			return;
		}
		Path index = dir.resolve("index.json");
		if (!Files.isRegularFile(index)) return;
		try (Reader r = Files.newBufferedReader(index, StandardCharsets.UTF_8)) {
			JsonElement root = JsonParser.parseReader(r);
			if (!root.isJsonObject() || !root.getAsJsonObject().has("clips")) return;
			for (JsonElement el : root.getAsJsonObject().getAsJsonArray("clips")) {
				try {
					JsonObject o = el.getAsJsonObject();
					Identifier sound = Identifier.tryParse(o.get("sound").getAsString());
					if (sound == null) continue;
					String owner = o.get("owner").getAsString();
					UUID ownerId = o.has("ownerId") ? UUID.fromString(o.get("ownerId").getAsString()) : new UUID(0L, 0L);
					int rate = o.get("sampleRate").getAsInt();
					boolean followPitch = o.has("followPitch") && o.get("followPitch").isJsonPrimitive() && o.get("followPitch").getAsBoolean();
					byte[] pcm = Files.readAllBytes(dir.resolve(fileName(sound)));
					if (rate != VoiceNet.SAMPLE_RATE || pcm.length < 2 || pcm.length > VoiceNet.MAX_PCM_BYTES || (pcm.length & 1) != 0) {
						LOGGER.warn("Skipping invalid clip {}", sound);
						continue;
					}
					CLIPS.put(sound, new Clip(sound, owner, ownerId, rate, pcm, followPitch));
				} catch (Exception e) {
					LOGGER.warn("Failed to load a clip entry: {}", e.toString());
				}
			}
			LOGGER.info("Loaded {} voice clips", CLIPS.size());
		} catch (Exception e) {
			LOGGER.warn("Failed to read index.json", e);
		}
	}

	private static void save(MinecraftServer server, Clip clip) {
		try {
			if (dir == null) dir = server.getWorldPath(LevelResource.ROOT).resolve("myvoice");
			Files.createDirectories(dir);
			Path target = dir.resolve(fileName(clip.sound()));
			Path tmp = dir.resolve(target.getFileName() + ".tmp");
			Files.write(tmp, clip.pcm());
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
			writeIndex();
		} catch (Exception e) {
			LOGGER.warn("Failed to save clip {}", clip.sound(), e);
		}
	}

	private static void deleteFile(Clip clip) {
		try {
			if (dir != null) Files.deleteIfExists(dir.resolve(fileName(clip.sound())));
		} catch (Exception e) {
			LOGGER.warn("Failed to delete clip file {}", clip.sound(), e);
		}
	}

	private static void writeIndex() {
		Path d = dir;
		if (d == null) return;
		try {
			JsonArray arr = new JsonArray();
			for (Clip c : CLIPS.values()) {
				JsonObject o = new JsonObject();
				o.addProperty("sound", c.sound().toString());
				o.addProperty("owner", c.owner());
				o.addProperty("ownerId", c.ownerId().toString());
				o.addProperty("sampleRate", c.sampleRate());
				o.addProperty("followPitch", c.followPitch());
				arr.add(o);
			}
			JsonObject root = new JsonObject();
			root.add("clips", arr);
			Path tmp = d.resolve("index.json.tmp");
			try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(root, w);
			}
			Files.move(tmp, d.resolve("index.json"), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Failed to write index.json", e);
		}
	}
}
