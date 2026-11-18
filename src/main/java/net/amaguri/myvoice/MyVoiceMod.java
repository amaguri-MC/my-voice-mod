package net.amaguri.myvoice;

import net.amaguri.myvoice.net.VoiceNet;
import net.amaguri.myvoice.server.VoiceServer;
import net.fabricmc.api.ModInitializer;

public class MyVoiceMod implements ModInitializer {
	public static final String MOD_ID = "myvoice";
	@Override
	public void onInitialize() {
		VoiceNet.registerTypes();
		VoiceServer.init();
	}
}
