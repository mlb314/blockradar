package com.blockradar;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.KeyBinding;
import net.minecraft.world.level.ClientLevel;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.util.InputUtil;
import net.minecraft.resources.Identifier;

import com.blockradar.config.BlockRadarConfig;
import com.blockradar.gui.ConfigScreen;
import com.blockradar.render.BoxRenderer;

public class BlockRadar implements ClientModInitializer {
	public static final String MOD_ID = "blockradar";

	public static BlockRadarConfig CONFIG;

	public static final KeyBinding.Category CATEGORY = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
	public static KeyBinding openMenuKey;

	private int tickCounter = 0;

	@Override
	public void onInitializeClient() {
		CONFIG = BlockRadarConfig.load();

		openMenuKey = new KeyBinding(
				"key.blockradar.open_menu",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_UNKNOWN, // unbound by default - set it in Controls once in-game
				CATEGORY
		);
		KeyBindingHelper.registerKeyBinding(openMenuKey);

		BoxRenderer.init();

		ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
	}

	private void onClientTick(Minecraft client) {
		while (openMenuKey.wasPressed()) {
			if (client.screen == null) {
				client.setScreen(new ConfigScreen(null));
			}
		}

		if (client.level == null || client.player == null) return;

		tickCounter++;
		int interval = Math.max(1, CONFIG.rescanIntervalTicks);
		if (tickCounter >= interval) {
			tickCounter = 0;
			ClientLevel level = client.level;
			BoxRenderer.getInstance().rescan(level, client.player.position(), CONFIG);
		}
	}
}
