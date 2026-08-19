package com.blockradar.structure;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

public final class StructureManager {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("blockradar/structures");

	private static List<StructureTemplate> cache;

	private StructureManager() {
	}

	public static List<StructureTemplate> loadAll() {
		if (cache != null) return cache;

		List<StructureTemplate> result = new ArrayList<>();
		try {
			Files.createDirectories(DIR);
			try (Stream<Path> files = Files.list(DIR)) {
				for (Path path : files.filter(p -> p.toString().endsWith(".json")).toList()) {
					try {
						String json = Files.readString(path, StandardCharsets.UTF_8);
						StructureTemplate template = GSON.fromJson(json, StructureTemplate.class);
						if (template != null && template.blocks != null && !template.blocks.isEmpty()) {
							String anchorBefore = template.blocks.get(0).blockId;
							template.chooseAnchor();
							template.computeBounds();
							result.add(template);

							// If the anchor changed (e.g. this template was captured before
							// anchor auto-selection existed and started with a common block
							// like stone), persist the fix so this doesn't get redone - and
							// isn't silently lost - every single load.
							if (!template.blocks.get(0).blockId.equals(anchorBefore)) {
								Files.writeString(path, GSON.toJson(template), StandardCharsets.UTF_8);
							}
						}
					} catch (IOException | RuntimeException e) {
						System.err.println("[blockradar] Failed to load structure " + path + ": " + e);
					}
				}
			}
		} catch (IOException e) {
			System.err.println("[blockradar] Failed to list structures dir: " + e);
		}

		cache = result;
		return cache;
	}

	public static List<StructureTemplate> getEnabled() {
		return loadAll().stream().filter(t -> t.enabled).toList();
	}

	public static void save(StructureTemplate template) {
		template.chooseAnchor();
		template.computeBounds();
		try {
			Files.createDirectories(DIR);
			Path path = DIR.resolve(sanitizeFileName(template.name) + ".json");
			Files.writeString(path, GSON.toJson(template), StandardCharsets.UTF_8);
		} catch (IOException e) {
			System.err.println("[blockradar] Failed to save structure " + template.name + ": " + e);
		}
		cache = null; // force reload next time loadAll()/getEnabled() is called
	}

	public static void delete(StructureTemplate template) {
		try {
			Files.deleteIfExists(DIR.resolve(sanitizeFileName(template.name) + ".json"));
		} catch (IOException e) {
			System.err.println("[blockradar] Failed to delete structure " + template.name + ": " + e);
		}
		cache = null;
	}

	private static String sanitizeFileName(String name) {
		return name.trim().replaceAll("[^a-zA-Z0-9_-]", "_");
	}
}
