package com.blockradar.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;

import com.blockradar.BlockRadar;
import com.blockradar.config.ColorUtil;
import com.blockradar.structure.RelativeBlock;
import com.blockradar.structure.StructureManager;
import com.blockradar.structure.StructureTemplate;

/**
 * Captures a new structure from BlockRadar.selectedCorner1/2 (set via the two "select corner"
 * keybindings while looking at blocks in-game), OR edits an existing template's name/threshold/
 * color. Pass an existing StructureTemplate to edit it - if corners are ALSO currently selected
 * when you hit Save, the block list is re-captured from that selection too; otherwise the
 * existing captured blocks are kept as-is and only the metadata changes.
 */
public class CaptureStructureScreen extends Screen {
	private final Screen parent;
	private final StructureTemplate editing; // null when capturing a brand new structure

	private EditBox nameBox;
	private EditBox thresholdBox;
	private EditBox hexBox;
	private ColorSlider rSlider, gSlider, bSlider, aSlider;

	private int color = 0xA0FF00FF;
	private boolean syncing = false;

	public CaptureStructureScreen(Screen parent) {
		this(parent, null);
	}

	public CaptureStructureScreen(Screen parent, StructureTemplate editing) {
		super(Component.literal(editing == null ? "Capture Structure" : "Edit Structure"));
		this.parent = parent;
		this.editing = editing;
		if (editing != null) {
			this.color = editing.color;
		}
	}

	@Override
	protected void init() {
		int left = 20;
		int top = 30;

		nameBox = new EditBox(this.font, left, top, 200, 20, Component.literal("Name"));
		nameBox.setMaxLength(48);
		nameBox.setValue(editing != null ? editing.name : "my_structure");
		this.addRenderableWidget(nameBox);

		top += 30;
		thresholdBox = new EditBox(this.font, left, top, 60, 20, Component.literal("Threshold"));
		thresholdBox.setMaxLength(3);
		thresholdBox.setValue(Integer.toString(editing != null ? editing.matchThresholdPercent : 100));
		this.addRenderableWidget(thresholdBox);

		top += 30;
		hexBox = new EditBox(this.font, left, top, 120, 20, Component.literal("Hex"));
		hexBox.setMaxLength(9);
		hexBox.setValue(ColorUtil.toHexRgb(color));
		hexBox.setResponder(this::onHexTyped);
		this.addRenderableWidget(hexBox);

		top += 30;
		rSlider = new ColorSlider(left, top, 200, 20, "R", ColorUtil.channel(color, 'r'), v -> onSliderChanged('r', v));
		this.addRenderableWidget(rSlider);
		top += 24;
		gSlider = new ColorSlider(left, top, 200, 20, "G", ColorUtil.channel(color, 'g'), v -> onSliderChanged('g', v));
		this.addRenderableWidget(gSlider);
		top += 24;
		bSlider = new ColorSlider(left, top, 200, 20, "B", ColorUtil.channel(color, 'b'), v -> onSliderChanged('b', v));
		this.addRenderableWidget(bSlider);
		top += 24;
		aSlider = new ColorSlider(left, top, 200, 20, "Alpha", ColorUtil.channel(color, 'a'), v -> onSliderChanged('a', v));
		this.addRenderableWidget(aSlider);

		int bottom = this.height - 28;
		this.addRenderableWidget(Button.builder(Component.literal(editing == null ? "Capture" : "Save"), btn -> capture())
				.bounds(left, bottom, 100, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Cancel"), btn -> this.minecraft.setScreen(parent))
				.bounds(left + 110, bottom, 90, 20).build());
	}

	private void onHexTyped(String text) {
		if (syncing) return;
		color = ColorUtil.parseHex(text, color);
		syncing = true;
		rSlider.setValueSilently(ColorUtil.channel(color, 'r'));
		gSlider.setValueSilently(ColorUtil.channel(color, 'g'));
		bSlider.setValueSilently(ColorUtil.channel(color, 'b'));
		aSlider.setValueSilently(ColorUtil.channel(color, 'a'));
		syncing = false;
	}

	private void onSliderChanged(char channel, int value) {
		if (syncing) return;
		color = ColorUtil.withChannel(color, channel, value);
		syncing = true;
		hexBox.setValue(ColorUtil.toHexRgb(color));
		syncing = false;
	}

	private void capture() {
		BlockPos c1 = BlockRadar.selectedCorner1;
		BlockPos c2 = BlockRadar.selectedCorner2;
		boolean cornersSelected = c1 != null && c2 != null && this.minecraft.level != null;

		List<RelativeBlock> blocks;

		if (cornersSelected) {
			blocks = captureBlocksFromSelection(c1, c2);
			if (blocks.isEmpty()) {
				this.minecraft.gui.setOverlayMessage(Component.literal(
						"Block Radar: no non-air blocks found in that selection"), false);
				this.minecraft.setScreen(parent);
				return;
			}
		} else if (editing != null) {
			// No new selection made - keep the structure's existing captured blocks and only
			// change name/threshold/color.
			blocks = editing.blocks;
		} else {
			this.minecraft.gui.setOverlayMessage(Component.literal(
					"Block Radar: select both corners first (look at a block, press the corner-1 and corner-2 keys)"), false);
			this.minecraft.setScreen(parent);
			return;
		}

		// Anchor (blocks.get(0)) should ideally be a distinctive/uncommon block so the scanner's
		// anchor-filter stays cheap - if the first captured block happens to be something very
		// common (stone, dirt), consider re-ordering the saved JSON by hand afterward.
		String name = nameBox.getValue().trim().isEmpty() ? "structure" : nameBox.getValue().trim();
		int threshold = Math.max(1, Math.min(100, parseOr(thresholdBox, 100)));

		if (editing != null && !editing.name.equals(name)) {
			// Renaming saves under a new file - remove the old one so it isn't left behind.
			StructureManager.delete(editing);
		}

		StructureTemplate template = new StructureTemplate(name, blocks, threshold, color);
		template.enabled = editing == null || editing.enabled;
		StructureManager.save(template);

		this.minecraft.gui.setOverlayMessage(Component.literal(
				(editing == null ? "Block Radar: captured '" : "Block Radar: updated '") + name + "' (" + blocks.size() + " blocks)"), false);

		this.minecraft.setScreen(parent);
	}

	private List<RelativeBlock> captureBlocksFromSelection(BlockPos c1, BlockPos c2) {
		int minX = Math.min(c1.getX(), c2.getX());
		int maxX = Math.max(c1.getX(), c2.getX());
		int minY = Math.min(c1.getY(), c2.getY());
		int maxY = Math.max(c1.getY(), c2.getY());
		int minZ = Math.min(c1.getZ(), c2.getZ());
		int maxZ = Math.max(c1.getZ(), c2.getZ());

		List<RelativeBlock> blocks = new ArrayList<>();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

		for (int x = minX; x <= maxX; x++) {
			for (int y = minY; y <= maxY; y++) {
				for (int z = minZ; z <= maxZ; z++) {
					cursor.set(x, y, z);
					BlockState state = this.minecraft.level.getBlockState(cursor);
					if (state.isAir()) continue;

					String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
					blocks.add(new RelativeBlock(x - minX, y - minY, z - minZ, id));
				}
			}
		}

		return blocks;
	}

	private static int parseOr(EditBox box, int fallback) {
		try {
			return Integer.parseInt(box.getValue().trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);

		graphics.text(this.font, this.title.getString(), 20, 12, 0xFFFFFFFF, true);
		graphics.text(this.font, "Name", nameBox.getX(), nameBox.getY() - 10, 0xFFAAAAAA, false);
		graphics.text(this.font, "Match threshold % (100 = exact, lower tolerates damage)",
				thresholdBox.getX(), thresholdBox.getY() - 10, 0xFFAAAAAA, false);
		graphics.text(this.font, "Hex color", hexBox.getX(), hexBox.getY() - 10, 0xFFAAAAAA, false);

		int swatchX = hexBox.getX() + hexBox.getWidth() + 12;
		int swatchY = hexBox.getY();
		graphics.fill(swatchX, swatchY, swatchX + 40, swatchY + 20, 0xFF000000 | (color & 0x00FFFFFF));
		graphics.fill(swatchX + 2, swatchY + 2, swatchX + 38, swatchY + 18, color);

		String status;
		if (BlockRadar.selectedCorner1 == null || BlockRadar.selectedCorner2 == null) {
			status = editing == null
					? "Corners not fully selected yet - look at blocks and use the corner keybinds"
					: "No new selection - Save will keep the existing " + editing.blocks.size() + " captured blocks";
		} else {
			status = "Corners: " + BlockRadar.selectedCorner1.toShortString() + " to " + BlockRadar.selectedCorner2.toShortString()
					+ (editing != null ? " (Save will RE-CAPTURE from this selection)" : "");
		}
		graphics.text(this.font, status, 20, this.height - 46, 0xFFAAAAAA, false);
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(parent);
	}
}
