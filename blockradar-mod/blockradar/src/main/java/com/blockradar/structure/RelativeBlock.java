package com.blockradar.structure;

/** One captured block within a structure template, relative to the template's anchor block. */
public class RelativeBlock {
	public int dx, dy, dz;
	public String blockId;

	// Needed for Gson.
	public RelativeBlock() {
	}

	public RelativeBlock(int dx, int dy, int dz, String blockId) {
		this.dx = dx;
		this.dy = dy;
		this.dz = dz;
		this.blockId = blockId;
	}
}
