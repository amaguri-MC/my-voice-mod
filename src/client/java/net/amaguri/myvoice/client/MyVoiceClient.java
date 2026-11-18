package net.amaguri.myvoice.client;

import net.amaguri.myvoice.client.audio.ClientAudio;
import net.amaguri.myvoice.client.ui.PickerLauncher;
import net.fabricmc.api.ClientModInitializer;

public class MyVoiceClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientAudio.init();
		VoiceClientNet.init();
		VoiceCommands.register();
		PickerLauncher.init();
	}
}
