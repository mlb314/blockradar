package com.blockradar.config;

/**
 * One "block to highlight" rule.
 * <p>
 * blockId is stored as a plain string (e.g. "minecraft:diamond_ore") so it round-trips
 * cleanly through JSON and so the config file stays readable/editable by hand.
 * color is packed 0xAARRGGBB.
 */
public class HighlightEntry {
	public String blockId;
	public int color;

	// Needed for Gson.
	public HighlightEntry() {
		this.blockId = "minecraft:diamond_ore";
		this.color = 0x8000FFFF; // 50% alpha cyan
	}

	public HighlightEntry(String blockId, int color) {
		this.blockId = blockId;
		this.color = color;
	}

	public float red() {
		return ((color >> 16) & 0xFF) / 255f;
	}

	public float green() {
		return ((color >> 8) & 0xFF) / 255f;
	}

	public float blue() {
		return (color & 0xFF) / 255f;
	}

	public float alpha() {
		return ((color >> 24) & 0xFF) / 255f;
	}
}
