package com.blockradar.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Persisted, user-editable settings.
 * <p>
 * xMin/xMax, zMin/zMax and yMin/yMax are FIXED WORLD-SPACE BLOCK COORDINATES (not relative
 * to the player) - only blocks inside this box are ever scanned or highlighted, no matter
 * where you're standing. Set these to bound a build site, a mining area, etc.
 */
public class BlockRadarConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("blockradar.json");

	public boolean enabled = true;
	public boolean seeThroughWalls = true;

	// Fixed world-space block coordinates (NOT relative to the player) - only blocks inside
	// this box are ever scanned, regardless of where you're standing.
	public int xMin = -100;
	public int xMax = 100;
	public int zMin = -100;
	public int zMax = 100;
	public int yMin = -64;
	public int yMax = 100;

	// How often (in client ticks) the world is re-scanned for matching blocks.
	// 20 ticks = 1 real second. Lower = more responsive, higher = cheaper.
	public int rescanIntervalTicks = 10;

	// If a received chat/system message CONTAINS this text (case-insensitive), it's treated
	// as "you just connected to a (possibly different) server/world". The mod uses the FULL
	// message text as a key: a never-seen key wipes the scan cache (everything is new again),
	// a previously-seen key restores that server's cache (already-known chunks stay known).
	// Leave blank to disable this behavior entirely.
	public String serverChangeChatTrigger = "Sending to server";

	public List<HighlightEntry> highlights = new ArrayList<>();

	public static BlockRadarConfig load() {
		if (Files.exists(PATH)) {
			try {
				String json = Files.readString(PATH, StandardCharsets.UTF_8);
				BlockRadarConfig cfg = GSON.fromJson(json, BlockRadarConfig.class);
				if (cfg != null) {
					if (cfg.highlights == null) cfg.highlights = new ArrayList<>();
					return cfg;
				}
			} catch (IOException | RuntimeException e) {
				System.err.println("[blockradar] Failed to load config, using defaults: " + e);
			}
		}

		BlockRadarConfig cfg = new BlockRadarConfig();
		// A couple of sane starter entries so the mod visibly does something on first launch.
		cfg.highlights.add(new HighlightEntry("minecraft:diamond_ore", 0xA000E5FF));
		cfg.highlights.add(new HighlightEntry("minecraft:ancient_debris", 0xA0FF8000));
		cfg.save();
		return cfg;
	}

	public void save() {
		try {
			Files.createDirectories(PATH.getParent());
			Files.writeString(PATH, GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException e) {
			System.err.println("[blockradar] Failed to save config: " + e);
		}
	}
}
