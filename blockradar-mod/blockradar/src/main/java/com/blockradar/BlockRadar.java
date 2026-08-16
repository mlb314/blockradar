package com.blockradar;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.multiplayer.ClientLevel;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import com.blockradar.config.BlockRadarConfig;
import com.blockradar.gui.ConfigScreen;
import com.blockradar.render.BoxRenderer;

public class BlockRadar implements ClientModInitializer {
	public static final String MOD_ID = "blockradar";

	public static BlockRadarConfig CONFIG;

	public static final KeyMapping.Category CATEGORY =
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
	public static KeyMapping openMenuKey;

	private int tickCounter = 0;

	@Override
	public void onInitializeClient() {
		CONFIG = BlockRadarConfig.load();

		openMenuKey = new KeyMapping(
				"key.blockradar.open_menu",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_UNKNOWN, // unbound by default - set it in Controls once in-game
				CATEGORY
		);
		KeyMappingHelper.registerKeyMapping(openMenuKey);

		BoxRenderer.init();

		ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
	}

	private void onClientTick(Minecraft client) {
		while (openMenuKey.consumeClick()) {
			if (client.screen == null) {
				client.setScreen(new ConfigScreen(null));
			}
		}

		if (client.level == null) return;

		tickCounter++;
		int interval = Math.max(1, CONFIG.rescanIntervalTicks);
		if (tickCounter >= interval) {
			tickCounter = 0;
			ClientLevel level = client.level;
			BoxRenderer.getInstance().rescan(level, CONFIG);
		}
	}
}
