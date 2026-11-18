package net.amaguri.myvoice.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.amaguri.myvoice.client.audio.ClientClips;
import net.amaguri.myvoice.client.audio.ClipPlayer;
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

/** /myvoice record|stop|play|clear|list（クライアントコマンド）。 */
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
			ctx.getSource().sendError(Component.translatable("myvoice.cmd.play_none", sound.toString()));
			return 0;
		}
		VoiceClientNet.setPitchMode(sound, follow);
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
		RecordFlow.begin(sound, seconds, followPitch, CHAT_LISTENER);
		src.sendFeedback(Component.translatable("myvoice.cmd.record_start", sound.toString(), seconds));
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
