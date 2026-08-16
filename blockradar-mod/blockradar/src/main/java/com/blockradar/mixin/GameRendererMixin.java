package com.blockradar.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.GameRenderer;

import com.blockradar.render.BoxRenderer;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
	@Inject(method = "close", at = @At("RETURN"))
	private void blockradar$onClose(CallbackInfo ci) {
		if (BoxRenderer.getInstance() != null) {
			BoxRenderer.getInstance().close();
		}
	}
}
