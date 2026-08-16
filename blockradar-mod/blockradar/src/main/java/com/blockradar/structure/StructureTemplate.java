package com.blockradar.structure;

import java.util.List;

/**
 * A capturable, savable "shape" to scan for - e.g. the parts of a monument that can't be
 * destroyed. blocks.get(0) is always the anchor: the scanner only attempts a full match at a
 * position when it finds a block matching the anchor's type there, which keeps scanning cheap.
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
		computeBounds();
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
