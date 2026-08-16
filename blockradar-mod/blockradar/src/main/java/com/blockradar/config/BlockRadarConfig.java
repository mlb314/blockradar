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
 * xMin/xMax and zMin/zMax are offsets FROM THE PLAYER, e.g. xMin=-16, xMax=16
 * scans 16 blocks to either side on X. yRadius does the same on the vertical axis
 * (not asked for explicitly, but needed since the world is 3D - highlighting only
 * matters for blocks you could actually reach/see).
 */
public class BlockRadarConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("blockradar.json");

	public boolean enabled = true;
	public boolean seeThroughWalls = true;

	public int xMin = -16;
	public int xMax = 16;
	public int zMin = -16;
	public int zMax = 16;
	public int yRadius = 16;

	// How often (in client ticks) the world is re-scanned for matching blocks.
	// 20 ticks = 1 real second. Lower = more responsive, higher = cheaper.
	public int rescanIntervalTicks = 10;

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
