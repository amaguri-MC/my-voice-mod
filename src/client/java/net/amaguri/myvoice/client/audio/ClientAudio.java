package net.amaguri.myvoice.client.audio;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/** クライアント音声の初期化: 毎tickの後始末と、切断時の全停止・全消去。 */
public final class ClientAudio {
	private ClientAudio() {}

	private static boolean initialized;

	public static void init() {
		if (initialized) return;
		initialized = true;
		ClientTickEvents.END_CLIENT_TICK.register(client -> ClipPlayer.tick());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			ClipPlayer.stopAll();
			ClientClips.clear();
		});
	}
}
