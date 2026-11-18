package net.amaguri.myvoice.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.amaguri.myvoice.client.audio.ClipPlayer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;

/** SoundManager declares play(SoundInstance) -> SoundEngine$PlayResult (verified in apidump). */
@Mixin(SoundManager.class)
public abstract class SoundManagerMixin {
	@Inject(method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;",
			at = @At("HEAD"), cancellable = true)
	private void myvoice$replace(SoundInstance instance, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
		if (ClipPlayer.tryReplace(instance)) {
			cir.setReturnValue(SoundEngine.PlayResult.STARTED);
		}
	}
}
