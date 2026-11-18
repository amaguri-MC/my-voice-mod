package net.amaguri.myvoice.client;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.amaguri.myvoice.client.audio.ClientClips;
import net.amaguri.myvoice.client.audio.ClipPlayer;
import net.amaguri.myvoice.client.catalog.SoundCatalog;
import net.amaguri.myvoice.client.ui.PickerLauncher;
import net.amaguri.myvoice.client.ui.RecordFlow;
import net.amaguri.myvoice.net.VoiceNet;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** /myvoice record|tune|trim|volume|pitch|stop|play|clear|list（クライアントコマンド）。 */
public final class VoiceCommands {
	private VoiceCommands() {}

	private static final int DEFAULT_SECONDS = RecordFlow.DEFAULT_SECONDS;

	private static final SuggestionProvider<FabricClientCommandSource> ALL_SOUNDS =
			(ctx, builder) -> SharedSuggestionProvider.suggestResource(BuiltInRegistries.SOUND_EVENT.keySet(), builder);
	private static final SuggestionProvider<FabricClientCommandSource> ASSIGNED_ONLY = (ctx, builder) -> {
		Set<Identifier> all = new LinkedHashSet<>();
		for (ClientClips.ClipInfo c : ClientClips.all()) {
			all.add(c.sound());
		}
		return SharedSuggestionProvider.suggestResource(all, builder);
	};
	private static final SuggestionProvider<FabricClientCommandSource> PITCH_MODES =
			(ctx, builder) -> SharedSuggestionProvider.suggest(List.of("voice", "game"), builder);
	private static final SuggestionProvider<FabricClientCommandSource> TUNE_VALUES =
			(ctx, builder) -> SharedSuggestionProvider.suggest(List.of("0.5", "0.75", "1", "1.25", "1.5", "2"), builder);
	private static final SuggestionProvider<FabricClientCommandSource> TRIM_VALUES =
			(ctx, builder) -> SharedSuggestionProvider.suggest(List.of("0", "0.2", "0.35", "0.6", "1"), builder);
	private static final SuggestionProvider<FabricClientCommandSource> VOLUME_VALUES =
			(ctx, builder) -> SharedSuggestionProvider.suggest(List.of("0", "50", "100", "150", "200"), builder);
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
		ClientTickEvents.END_CLIENT_TICK.register(mc -> RecordFlow.tick());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> RecordFlow.reset());
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> {
			LiteralArgumentBuilder<FabricClientCommandSource> root = ClientCommands.literal("myvoice")
					.executes(ctx -> {
						PickerLauncher.requestOpen(); // チャット画面が閉じたあとの tick で開く
						return 1;
					})
					.then(ClientCommands.literal("record")
							.then(ClientCommands.argument("sound", IdentifierArgument.id())
									.suggests(ALL_SOUNDS)
									.executes(ctx -> record(ctx, DEFAULT_SECONDS, false))
									.then(ClientCommands.argument("seconds", IntegerArgumentType.integer(1, VoiceNet.MAX_SECONDS))
											.executes(ctx -> record(ctx, IntegerArgumentType.getInteger(ctx, "seconds"), false))
											.then(ClientCommands.argument("pitch", StringArgumentType.word())
													.suggests(PITCH_MODES)
													.executes(ctx -> {
														Boolean f = parseMode(StringArgumentType.getString(ctx, "pitch"));
														if (f == null) {
															ctx.getSource().sendError(Component.translatable("myvoice.cmd.pitch_bad_mode"));
															return 0;
														}
														return record(ctx, IntegerArgumentType.getInteger(ctx, "seconds"), f);
													})))))
					.then(ClientCommands.literal("pitch")
							.then(ClientCommands.argument("sound", IdentifierArgument.id())
									.suggests(ASSIGNED_ONLY)
									.then(ClientCommands.argument("mode", StringArgumentType.word())
											.suggests(PITCH_MODES)
											.executes(VoiceCommands::pitch))))
					.then(ClientCommands.literal("tune")
							.then(ClientCommands.argument("sound", IdentifierArgument.id())
									.suggests(ASSIGNED_ONLY)
									.then(ClientCommands.argument("pitch", FloatArgumentType.floatArg(VoiceNet.MIN_TUNE, VoiceNet.MAX_TUNE))
											.suggests(TUNE_VALUES)
											.then(ClientCommands.argument("speed", FloatArgumentType.floatArg(VoiceNet.MIN_TUNE, VoiceNet.MAX_TUNE))
													.suggests(TUNE_VALUES)
													.executes(VoiceCommands::tune)))))
					.then(ClientCommands.literal("trim")
							.then(ClientCommands.argument("sound", IdentifierArgument.id())
									.suggests(ASSIGNED_ONLY)
									.then(ClientCommands.argument("amount", FloatArgumentType.floatArg(0f, 1f))
											.suggests(TRIM_VALUES)
											.executes(VoiceCommands::trim))))
					.then(ClientCommands.literal("volume")
							.then(ClientCommands.argument("sound", IdentifierArgument.id())
									.suggests(ASSIGNED_ONLY)
									.then(ClientCommands.argument("percent", IntegerArgumentType.integer(0, 200))
											.suggests(VOLUME_VALUES)
											.executes(VoiceCommands::volume))))
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

	private static final RecordFlow.Listener CHAT_LISTENER = message ->
			VoiceClientNet.chat(Minecraft.getInstance(), message);

	// ---- コマンド ----

	private static Boolean parseMode(String s) {
		if ("voice".equals(s)) return Boolean.FALSE;
		if ("game".equals(s)) return Boolean.TRUE;
		return null;
	}

	/** 予約（または返事待ち）があればその値、無ければ ClientClips の値。 */
	private static VoiceClientNet.Tune baseTune(Identifier sound, ClientClips.ClipInfo cur) {
		VoiceClientNet.Tune p = VoiceClientNet.pendingTune(sound);
		return p != null ? p : new VoiceClientNet.Tune(cur.followPitch(), cur.pitch(), cur.speed(), cur.trim(), cur.volume());
	}

	private static int pitch(CommandContext<FabricClientCommandSource> ctx) {
		Boolean follow = parseMode(StringArgumentType.getString(ctx, "mode"));
		if (follow == null) {
			ctx.getSource().sendError(Component.translatable("myvoice.cmd.pitch_bad_mode"));
			return 0;
		}
		if (!VoiceClientNet.canSend()) {
			ctx.getSource().sendError(Component.translatable("myvoice.cmd.need_server"));
			return 0;
		}
		Identifier sound = ctx.getArgument("sound", Identifier.class);
		if (ClientClips.get(sound) == null) {
			ctx.getSource().sendError(Component.translatable("myvoice.cmd.play_none", SoundCatalog.displayName(sound)));
			return 0;
		}
		ClientClips.ClipInfo cur = ClientClips.get(sound);
		VoiceClientNet.Tune base = baseTune(sound, cur);
		if (base.followPitch() == follow) return 1; // もう同じ設定（予約・返事待ちがあればその値で判定）
		VoiceClientNet.queueTune(sound, follow, base.pitch(), base.speed(), base.trim(), base.volume());
		return 1;
	}

	private static int tune(CommandContext<FabricClientCommandSource> ctx) {
		if (!VoiceClientNet.canSend()) {
			ctx.getSource().sendError(Component.translatable("myvoice.cmd.need_server"));
			return 0;
		}
		Identifier sound = ctx.getArgument("sound", Identifier.class);
		ClientClips.ClipInfo cur = ClientClips.get(sound);
		if (cur == null) {
			ctx.getSource().sendError(Component.translatable("myvoice.cmd.play_none", SoundCatalog.displayName(sound)));
			return 0;
		}
		float pitch = FloatArgumentType.getFloat(ctx, "pitch");
		float speed = FloatArgumentType.getFloat(ctx, "speed");
		VoiceClientNet.Tune base = baseTune(sound, cur);
		VoiceClientNet.queueTune(sound, base.followPitch(), pitch, speed, base.trim(), base.volume());
		return 1;
	}

	private static int trim(CommandContext<FabricClientCommandSource> ctx) {
		if (!VoiceClientNet.canSend()) {
			ctx.getSource().sendError(Component.translatable("myvoice.cmd.need_server"));
			return 0;
		}
		Identifier sound = ctx.getArgument("sound", Identifier.class);
		ClientClips.ClipInfo cur = ClientClips.get(sound);
		if (cur == null) {
			ctx.getSource().sendError(Component.translatable("myvoice.cmd.play_none", SoundCatalog.displayName(sound)));
			return 0;
		}
		float amount = FloatArgumentType.getFloat(ctx, "amount");
		VoiceClientNet.Tune base = baseTune(sound, cur);
		VoiceClientNet.queueTune(sound, base.followPitch(), base.pitch(), base.speed(), amount, base.volume());
		return 1;
	}

	private static int volume(CommandContext<FabricClientCommandSource> ctx) {
		if (!VoiceClientNet.canSend()) {
			ctx.getSource().sendError(Component.translatable("myvoice.cmd.need_server"));
			return 0;
		}
		Identifier sound = ctx.getArgument("sound", Identifier.class);
		ClientClips.ClipInfo cur = ClientClips.get(sound);
		if (cur == null) {
			ctx.getSource().sendError(Component.translatable("myvoice.cmd.play_none", SoundCatalog.displayName(sound)));
			return 0;
		}
		float percent = IntegerArgumentType.getInteger(ctx, "percent");
		VoiceClientNet.Tune base = baseTune(sound, cur);
		VoiceClientNet.queueTune(sound, base.followPitch(), base.pitch(), base.speed(), base.trim(), percent / 100f);
		return 1;
	}

	private static int record(CommandContext<FabricClientCommandSource> ctx, int seconds, boolean followPitch) {
		FabricClientCommandSource src = ctx.getSource();
		if (RecordFlow.busy()) {
			src.sendError(Component.translatable("myvoice.cmd.already_recording"));
			return 0;
		}
		if (!VoiceClientNet.canSend()) {
			src.sendError(Component.translatable("myvoice.cmd.need_server"));
			return 0;
		}
		Identifier sound = ctx.getArgument("sound", Identifier.class);
		RecordFlow.begin(sound, seconds, followPitch, 1.0f, 1.0f, VoiceNet.DEFAULT_TRIM, 1.0f, CHAT_LISTENER);
		src.sendFeedback(Component.translatable("myvoice.cmd.record_start", SoundCatalog.displayName(sound), seconds));
		return 1;
	}

	private static int stop(CommandContext<FabricClientCommandSource> ctx) {
		FabricClientCommandSource src = ctx.getSource();
		switch (RecordFlow.stopEarly()) {
			case COUNTDOWN_CANCELLED -> {
				src.sendFeedback(Component.translatable("myvoice.cmd.countdown_cancelled"));
				return 1;
			}
			case STOPPING -> {
				return 1;
			}
			default -> {
				src.sendError(Component.translatable("myvoice.cmd.not_recording"));
				return 0;
			}
		}
	}

	private static int play(CommandContext<FabricClientCommandSource> ctx) {
		Identifier sound = ctx.getArgument("sound", Identifier.class);
		if (ClientClips.get(sound) == null || !ClipPlayer.preview(sound)) {
			ctx.getSource().sendError(Component.translatable("myvoice.cmd.play_none", SoundCatalog.displayName(sound)));
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
					SoundCatalog.displayName(c.sound()), c.owner(), String.format("%.1f", c.seconds())));
		}
		return clips.size();
	}
}
