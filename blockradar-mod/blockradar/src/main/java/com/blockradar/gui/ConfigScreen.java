package com.blockradar.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import com.blockradar.BlockRadar;
import com.blockradar.config.BlockRadarConfig;
import com.blockradar.config.HighlightEntry;

/**
 * The "settings" menu for the mod. Bind a key to blockradar's keybinding
 * (Controls > Key Binds > Block Radar) to open this in-game.
 * <p>
 * X/Z/Y min-max here are FIXED WORLD COORDINATES, not relative to the player - use F3 in-game
 * to read your current coordinates when deciding what to type in.
 */
public class ConfigScreen extends Screen {
	private final Screen parent;
	private final BlockRadarConfig config = BlockRadar.CONFIG;

	private EditBox xMinBox, xMaxBox, zMinBox, zMaxBox, yMinBox, yMaxBox, intervalBox, rescanPercentBox;
	private final List<Button> highlightRowButtons = new ArrayList<>();

	private static final int LEFT = 20;

	public ConfigScreen(Screen parent) {
		super(Component.literal("Block Radar"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int top = 30;

		xMinBox = numberField(LEFT, top, config.xMin);
		xMaxBox = numberField(LEFT + 60, top, config.xMax);
		zMinBox = numberField(LEFT + 140, top, config.zMin);
		zMaxBox = numberField(LEFT + 200, top, config.zMax);

		top += 24;
		yMinBox = numberField(LEFT, top, config.yMin);
		yMaxBox = numberField(LEFT + 60, top, config.yMax);

		top += 24;
		intervalBox = numberField(LEFT, top, config.rescanIntervalTicks);
		rescanPercentBox = numberField(LEFT + 130, top, config.knownChunkRescanPercent);

		top += 24;
		this.addRenderableWidget(Checkbox.builder(Component.literal("Enabled"), this.font)
				.pos(LEFT, top)
				.selected(config.enabled)
				.onValueChange((cb, value) -> config.enabled = value)
				.build());

		this.addRenderableWidget(Checkbox.builder(Component.literal("See through walls"), this.font)
				.pos(LEFT + 90, top)
				.selected(config.seeThroughWalls)
				.onValueChange((cb, value) -> config.seeThroughWalls = value)
				.build());

		rebuildHighlightList();

		int bottom = this.height - 28;
		this.addRenderableWidget(Button.builder(Component.literal("+ Add Block"), btn -> {
			applyFields();
			this.minecraft.setScreen(new EditHighlightScreen(this, null));
		}).bounds(LEFT, bottom, 100, 20).build());

		this.addRenderableWidget(Button.builder(Component.literal("Save & Close"), btn -> {
			applyFields();
			config.save();
			this.minecraft.setScreen(parent);
		}).bounds(LEFT + 110, bottom, 100, 20).build());

		this.addRenderableWidget(Button.builder(Component.literal("Cancel"), btn ->
				this.minecraft.setScreen(parent)
		).bounds(LEFT + 220, bottom, 80, 20).build());
	}

	private EditBox numberField(int x, int y, int value) {
		EditBox box = new EditBox(this.font, x, y, 50, 20, Component.empty());
		box.setValue(Integer.toString(value));
		box.setMaxLength(6);
		this.addRenderableWidget(box);
		return box;
	}

	private void applyFields() {
		config.xMin = parseOr(xMinBox, config.xMin);
		config.xMax = parseOr(xMaxBox, config.xMax);
		config.zMin = parseOr(zMinBox, config.zMin);
		config.zMax = parseOr(zMaxBox, config.zMax);
		config.yMin = parseOr(yMinBox, config.yMin);
		config.yMax = parseOr(yMaxBox, config.yMax);
		config.rescanIntervalTicks = Math.max(1, parseOr(intervalBox, config.rescanIntervalTicks));
		config.knownChunkRescanPercent = Math.max(0, Math.min(100, parseOr(rescanPercentBox, config.knownChunkRescanPercent)));
	}

	private static int parseOr(EditBox box, int fallback) {
		try {
			return Integer.parseInt(box.getValue().trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private void rebuildHighlightList() {
		for (Button b : highlightRowButtons) {
			this.removeWidget(b);
		}
		highlightRowButtons.clear();

		int rowY = 136;
		int maxVisible = Math.max(1, (this.height - 174) / 24);

		for (int i = 0; i < config.highlights.size() && i < maxVisible; i++) {
			HighlightEntry entry = config.highlights.get(i);
			int y = rowY + i * 24;

			Button editBtn = Button.builder(Component.literal(entry.blockId), btn -> {
				applyFields();
				this.minecraft.setScreen(new EditHighlightScreen(this, entry));
			}).bounds(LEFT, y, 220, 20).build();

			Button removeBtn = Button.builder(Component.literal("X"), btn -> {
				config.highlights.remove(entry);
				rebuildHighlightList();
			}).bounds(LEFT + 226, y, 20, 20).build();

			this.addRenderableWidget(editBtn);
			this.addRenderableWidget(removeBtn);
			highlightRowButtons.add(editBtn);
			highlightRowButtons.add(removeBtn);
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);

		graphics.text(this.font, "Block Radar", LEFT, 12, 0xFFFFFFFF, true);

		graphics.text(this.font, "X min", xMinBox.getX(), 20, 0xFFAAAAAA, false);
		graphics.text(this.font, "X max", xMaxBox.getX(), 20, 0xFFAAAAAA, false);
		graphics.text(this.font, "Z min", zMinBox.getX(), 20, 0xFFAAAAAA, false);
		graphics.text(this.font, "Z max", zMaxBox.getX(), 20, 0xFFAAAAAA, false);
		graphics.text(this.font, "Y min", yMinBox.getX(), yMinBox.getY() - 10, 0xFFAAAAAA, false);
		graphics.text(this.font, "Y max", yMaxBox.getX(), yMaxBox.getY() - 10, 0xFFAAAAAA, false);
		graphics.text(this.font, "Rescan (ticks)", intervalBox.getX(), intervalBox.getY() - 10, 0xFFAAAAAA, false);
		graphics.text(this.font, "Known-chunk rescan %", rescanPercentBox.getX(), rescanPercentBox.getY() - 10, 0xFFAAAAAA, false);

		graphics.text(this.font, "Highlighted blocks:", LEFT, 124, 0xFFFFFFFF, false);

		if (config.highlights.isEmpty()) {
			graphics.text(this.font, "(none yet - click + Add Block)", LEFT, 138, 0xFF888888, false);
		}
	}

	@Override
	public void onClose() {
		applyFields();
		config.save();
		this.minecraft.setScreen(parent);
	}
}
