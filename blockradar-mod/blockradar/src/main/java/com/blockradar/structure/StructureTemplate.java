package com.blockradar.structure;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A capturable, savable "shape" to scan for - e.g. the parts of a monument that can't be
 * destroyed. blocks.get(0) is always the anchor: the scanner only attempts a full match at a
 * position when it finds a block matching the anchor's type there, which keeps scanning cheap
 * - AS LONG AS the anchor is a genuinely uncommon block. If it isn't (e.g. stone, dirt), nearly
 * every block in the scan area becomes a candidate and the full multi-rotation comparison runs
 * constantly, which is slow enough to noticeably stall/freeze the game.
 * <p>
 * To prevent that, {@link #chooseAnchor()} automatically reorders the block list so the LEAST
 * common block type present (excluding a denylist of especially common terrain blocks) becomes
 * the anchor. This runs both right after capturing a new structure and every time an existing
 * template is loaded from disk - so templates captured before this existed get fixed for free
 * the moment this version runs, with no need to re-capture them.
 * <p>
 * matchThresholdPercent lets you tolerate damage: 100 requires every captured block to still be
 * present and correct; lower values (e.g. 70) accept a partially-destroyed structure as a match.
 * Chunks/blocks that aren't loaded when checked don't count against the percentage either way.
 * <p>
 * Templates are saved as human-readable JSON, so you can hand-edit a template file afterwards to
 * delete entries for parts of a structure that are prone to being changed/griefed, if you want
 * more precise control than the percentage threshold gives you.
 */
public class StructureTemplate {
	// Very common terrain/filler blocks that make terrible anchors even if they happen to be
	// the least-frequent block WITHIN a small template - picking one of these as the anchor is
	// what causes the "scan freezes the game" problem, since nearly every position in a normal
	// world matches one of them.
	private static final Set<String> COMMON_BLOCK_IDS = Set.of(
			"minecraft:stone", "minecraft:dirt", "minecraft:grass_block", "minecraft:deepslate",
			"minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:netherrack",
			"minecraft:end_stone", "minecraft:water", "minecraft:lava", "minecraft:sand",
			"minecraft:red_sand", "minecraft:gravel", "minecraft:sandstone", "minecraft:andesite",
			"minecraft:diorite", "minecraft:granite", "minecraft:tuff", "minecraft:basalt",
			"minecraft:snow_block", "minecraft:snow", "minecraft:ice", "minecraft:packed_ice",
			"minecraft:bedrock", "minecraft:air", "minecraft:cave_air", "minecraft:void_air",
			"minecraft:calcite", "minecraft:dripstone_block", "minecraft:mud", "minecraft:clay"
	);

	public String name;
	public List<RelativeBlock> blocks;
	public int matchThresholdPercent = 100;
	public int color = 0xA0FF00FF;
	public boolean enabled = true;

	// Not saved - computed once after loading/capturing, used every scan.
	public transient int minDx, maxDx, minDy, maxDy, minDz, maxDz;

	public StructureTemplate() {
	}

	public StructureTemplate(String name, List<RelativeBlock> blocks, int matchThresholdPercent, int color) {
		this.name = name;
		this.blocks = blocks;
		this.matchThresholdPercent = matchThresholdPercent;
		this.color = color;
		chooseAnchor();
		computeBounds();
	}

	/**
	 * Reorders blocks so the rarest, least-common block type becomes blocks.get(0) (the anchor).
	 * Blocks matching COMMON_BLOCK_IDS are heavily deprioritized even if they'd otherwise be the
	 * least frequent, since being rare WITHIN a small template doesn't mean rare in the world.
	 * Safe to call repeatedly (e.g. on every load) - it's deterministic given the same block list.
	 */
	public void chooseAnchor() {
		if (blocks == null || blocks.size() <= 1) return;

		Map<String, Integer> counts = new HashMap<>();
		for (RelativeBlock b : blocks) {
			counts.merge(b.blockId.toLowerCase(Locale.ROOT), 1, Integer::sum);
		}

		RelativeBlock best = blocks.get(0);
		long bestScore = Long.MAX_VALUE;
		for (RelativeBlock b : blocks) {
			String id = b.blockId.toLowerCase(Locale.ROOT);
			long score = counts.get(id);
			if (COMMON_BLOCK_IDS.contains(id)) {
				score += 1_000_000L; // effectively disqualifies it unless nothing else exists
			}
			if (score < bestScore) {
				bestScore = score;
				best = b;
			}
		}

		if (best != blocks.get(0)) {
			blocks.remove(best);
			blocks.add(0, best);
		}
	}

	public void computeBounds() {
		if (blocks == null || blocks.isEmpty()) {
			minDx = maxDx = minDy = maxDy = minDz = maxDz = 0;
			return;
		}
		minDx = maxDx = blocks.get(0).dx;
		minDy = maxDy = blocks.get(0).dy;
		minDz = maxDz = blocks.get(0).dz;
		for (RelativeBlock b : blocks) {
			minDx = Math.min(minDx, b.dx);
			maxDx = Math.max(maxDx, b.dx);
			minDy = Math.min(minDy, b.dy);
			maxDy = Math.max(maxDy, b.dy);
			minDz = Math.min(minDz, b.dz);
			maxDz = Math.max(maxDz, b.dz);
		}
	}

	public RelativeBlock anchor() {
		return blocks.get(0);
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
