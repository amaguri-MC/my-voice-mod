package net.amaguri.myvoice.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.amaguri.myvoice.client.audio.ClientClips;
import net.amaguri.myvoice.client.audio.ClipPlayer;
import net.amaguri.myvoice.client.audio.MicRecorder;
import net.amaguri.myvoice.net.VoiceNet;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** /myvoice record|stop|play|clear|list（クライアントコマンド）。 */
public final class VoiceCommands {
	private VoiceCommands() {}

	private static final int TICKS_PER_SECOND = 20;
	private static final int COUNTDOWN_TICKS = 3 * TICKS_PER_SECOND;
	private static final int DEFAULT_SECONDS = 3;

	/** カウントダウンの残りtick（0なら動いていない）。 */
	private static int countdownTicks = 0;
	private static Identifier pendingSound = null;
	private static int pendingSeconds = DEFAULT_SECONDS;
	/** 録音中か（MicRecorder.start 〜 onDone/onError）。 */
	private static boolean recording = false;
	private static int recordSeconds = DEFAULT_SECONDS;
	private static int recordTicks = 0;

	private static final SuggestionProvider<FabricClientCommandSource> ALL_SOUNDS =
			(ctx, builder) -> SharedSuggestionProvider.suggestResource(BuiltInRegistries.SOUND_EVENT.keySet(), builder);
	private static final SuggestionProvider<FabricClientCommandSource> SOUNDS_AND_ASSIGNED = (ctx, builder) -> {
		Set<Identifier> all = new LinkedHashSet<>();
		for (ClientClips.ClipInfo c : ClientClips.all()) {
			all.add(c.sound());
		}
		all.addAll(BuiltInRegistries.SOUND_EVENT.keySet());
		return SharedSuggestionProvider.suggestResource(all, builder);
	};

	public static void register() {
		RecordHud.init();
		ClientTickEvents.END_CLIENT_TICK.register(mc -> tick());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> reset());
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> {
			LiteralArgumentBuilder<FabricClientCommandSource> root = ClientCommands.literal("myvoice")
					.then(ClientCommands.literal("record")
							.then(ClientCommands.argument("sound", IdentifierArgument.id())
									.suggests(ALL_SOUNDS)
									.executes(ctx -> record(ctx, DEFAULT_SECONDS))
									.then(ClientCommands.argument("seconds", IntegerArgumentType.integer(1, VoiceNet.MAX_SECONDS))
											.executes(ctx -> record(ctx, IntegerArgumentType.getInteger(ctx, "seconds"))))))
					.then(ClientCommands.literal("stop").executes(VoiceCommands::stop))
					.then(ClientCommands.literal("play")
							.then(ClientCommands.argument("sound", IdentifierArgument.id())
									.suggests(SOUNDS_AND_ASSIGNED)
									.executes(VoiceCommands::play)))
					.then(ClientCommands.literal("clear")
							.then(ClientCommands.argument("sound", IdentifierArgument.id())
									.suggests(SOUNDS_AND_ASSIGNED)
									.executes(VoiceCommands::clear)))
					.then(ClientCommands.literal("list").executes(VoiceCommands::list));
			dispatcher.register(root);
		});
	}

	// ---- 状態（HUD から読む） ----

	/** カウントダウン中なら 3,2,1、そうでなければ 0。 */
	static int countdownNumber() {
		return countdownTicks <= 0 ? 0 : (countdownTicks + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND;
	}

	static boolean isRecordingNow() {
		return recording;
	}

	static float remainingSeconds() {
		return Math.max(0f, recordSeconds - recordTicks / (float) TICKS_PER_SECOND);
	}

	private static boolean busy() {
		return countdownTicks > 0 || recording || MicRecorder.isRecording();
	}

	private static void reset() {
		countdownTicks = 0;
		pendingSound = null;
		if (recording || MicRecorder.isRecording()) {
			MicRecorder.stop();
		}
		recording = false;
		recordTicks = 0;
	}

	private static void tick() {
		if (countdownTicks > 0) {
			countdownTicks--;
			if (countdownTicks == 0 && pendingSound != null) {
				startRecording(pendingSound, pendingSeconds);
				pendingSound = null;
			}
		} else if (recording) {
			recordTicks++;
		}
	}

	private static void startRecording(Identifier sound, int seconds) {
		recording = true;
		recordSeconds = seconds;
		recordTicks = 0;
		MicRecorder.start(seconds, pcm -> {
			recording = false;
			if (pcm == null || pcm.length < 2) {
				VoiceClientNet.chat(net.minecraft.client.Minecraft.getInstance(), Component.translatable("myvoice.error.mic_failed"));
				return;
			}
			VoiceClientNet.upload(sound, pcm);
		}, errorKey -> {
			recording = false;
			VoiceClientNet.chat(net.minecraft.client.Minecraft.getInstance(), Component.translatable(errorKey));
		});
	}

	// ---- コマンド ----

	private static int record(CommandContext<FabricClientCommandSource> ctx, int seconds) {
		FabricClientCommandSource src = ctx.getSource();
		if (busy()) {
			src.sendError(Component.translatable("myvoice.cmd.already_recording"));
			return 0;
		}
		if (!VoiceClientNet.canSend()) {
			src.sendError(Component.translatable("myvoice.cmd.need_server"));
			return 0;
		}
		pendingSound = ctx.getArgument("sound", Identifier.class);
		pendingSeconds = seconds;
		countdownTicks = COUNTDOWN_TICKS;
		src.sendFeedback(Component.translatable("myvoice.cmd.record_start", pendingSound.toString(), seconds));
		return 1;
	}

	private static int stop(CommandContext<FabricClientCommandSource> ctx) {
		FabricClientCommandSource src = ctx.getSource();
		if (countdownTicks > 0) {
			countdownTicks = 0;
			pendingSound = null;
			src.sendFeedback(Component.translatable("myvoice.cmd.countdown_cancelled"));
			return 1;
		}
		if (recording || MicRecorder.isRecording()) {
			MicRecorder.stop();
			return 1;
		}
		src.sendError(Component.translatable("myvoice.cmd.not_recording"));
		return 0;
	}

	private static int play(CommandContext<FabricClientCommandSource> ctx) {
		Identifier sound = ctx.getArgument("sound", Identifier.class);
		if (ClientClips.get(sound) == null || !ClipPlayer.preview(sound)) {
			ctx.getSource().sendError(Component.translatable("myvoice.cmd.play_none", sound.toString()));
			return 0;
		}
		return 1;
	}

	private static int clear(CommandContext<FabricClientCommandSource> ctx) {
		if (!VoiceClientNet.canSend()) {
			ctx.getSource().sendError(Component.translatable("myvoice.cmd.need_server"));
			return 0;
		}
		Identifier sound = ctx.getArgument("sound", Identifier.class);
		ClientPlayNetworking.send(new VoiceNet.ClearClipC2S(sound));
		return 1;
	}

	private static int list(CommandContext<FabricClientCommandSource> ctx) {
		FabricClientCommandSource src = ctx.getSource();
		List<ClientClips.ClipInfo> clips = new ArrayList<>(ClientClips.all());
		if (clips.isEmpty()) {
			src.sendFeedback(Component.translatable("myvoice.cmd.list_empty"));
			return 0;
		}
		clips.sort(Comparator.comparing(c -> c.sound().toString()));
		src.sendFeedback(Component.translatable("myvoice.cmd.list_header", clips.size()));
		for (ClientClips.ClipInfo c : clips) {
			src.sendFeedback(Component.translatable("myvoice.cmd.list_entry",
					c.sound().toString(), c.owner(), String.format("%.1f", c.seconds())));
		}
		return clips.size();
	}
}
