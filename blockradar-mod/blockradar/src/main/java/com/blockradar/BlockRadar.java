package com.blockradar;

import java.util.Locale;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
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
	public static KeyMapping selectCorner1Key;
	public static KeyMapping selectCorner2Key;
	public static KeyMapping resetChunksKey;

	// The structure-capture selection, set by looking at a block and pressing the corner keys.
	// Read by CaptureStructureScreen when the person hits "Capture".
	public static BlockPos selectedCorner1;
	public static BlockPos selectedCorner2;

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
		selectCorner1Key = new KeyMapping(
				"key.blockradar.select_corner_1",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_UNKNOWN,
				CATEGORY
		);
		selectCorner2Key = new KeyMapping(
				"key.blockradar.select_corner_2",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_UNKNOWN,
				CATEGORY
		);
		resetChunksKey = new KeyMapping(
				"key.blockradar.reset_chunks",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_UNKNOWN, // unbound by default
				CATEGORY
		);
		KeyMappingHelper.registerKeyMapping(openMenuKey);
		KeyMappingHelper.registerKeyMapping(selectCorner1Key);
		KeyMappingHelper.registerKeyMapping(selectCorner2Key);
		KeyMappingHelper.registerKeyMapping(resetChunksKey);

		BoxRenderer.init();

		ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);

		// Detects "you just (re)connected to a server" chat/system messages so the scanner can
		// tell a genuinely new server (start fresh) apart from a server it's seen before
		// (restore what was already scanned there). See BlockRadarConfig#serverChangeChatTrigger.
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> handleGameMessage(message));
	}

	private void handleGameMessage(Component message) {
		String trigger = CONFIG.serverChangeChatTrigger;
		if (trigger == null || trigger.isBlank()) return;

		String text = message.getString();
		if (text.toLowerCase(Locale.ROOT).contains(trigger.toLowerCase(Locale.ROOT))) {
			BoxRenderer.getInstance().onServerChanged(text.trim());
		}
	}

	private void onClientTick(Minecraft client) {
		while (openMenuKey.consumeClick()) {
			if (client.screen == null) {
				client.setScreen(new ConfigScreen(null));
			}
		}

		while (selectCorner1Key.consumeClick()) {
			selectedCorner1 = raycastBlockPos(client);
			client.gui.setOverlayMessage(Component.literal(selectedCorner1 != null
					? "Block Radar: corner 1 set to " + selectedCorner1.toShortString()
					: "Block Radar: look at a block to set corner 1"), false);
		}

		while (selectCorner2Key.consumeClick()) {
			selectedCorner2 = raycastBlockPos(client);
			client.gui.setOverlayMessage(Component.literal(selectedCorner2 != null
					? "Block Radar: corner 2 set to " + selectedCorner2.toShortString()
					: "Block Radar: look at a block to set corner 2"), false);
		}

		while (resetChunksKey.consumeClick()) {
			BoxRenderer.getInstance().resetCurrentChunks();
			if (client.player != null) {
				client.player.displayClientMessage(
						Component.literal("§aBlock Radar: chunk cache reset – rescanning…"), true);
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

	private static BlockPos raycastBlockPos(Minecraft client) {
		HitResult hit = client.hitResult;
		if (hit instanceof BlockHitResult blockHit) {
			return blockHit.getBlockPos();
		}
		return null;
	}
}
