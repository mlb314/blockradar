package com.blockradar.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import com.blockradar.BlockRadar;
import com.blockradar.config.ColorUtil;
import com.blockradar.config.HighlightEntry;

/**
 * Add or edit a single highlight rule: which block, and what color.
 * Color can be set either by typing a hex code (#RRGGBB) or by dragging the
 * R / G / B / Alpha sliders - both stay in sync with each other and with the
 * live preview swatch.
 */
public class EditHighlightScreen extends Screen {
	private final ConfigScreen parent;
	private final HighlightEntry editing; // null => creating a new entry

	private EditBox blockIdBox;
	private EditBox hexBox;
	private ColorSlider rSlider, gSlider, bSlider, aSlider;

	private int color;
	private boolean syncing = false;

	public EditHighlightScreen(ConfigScreen parent, HighlightEntry editing) {
		super(Component.literal(editing == null ? "Add Highlighted Block" : "Edit Highlighted Block"));
		this.parent = parent;
		this.editing = editing;
		this.color = editing != null ? editing.color : 0xA000FFFF;
	}

	@Override
	protected void init() {
		int left = 20;
		int top = 30;

		blockIdBox = new EditBox(this.font, left, top, 260, 20, Component.literal("Block ID"));
		blockIdBox.setMaxLength(128);
		blockIdBox.setValue(editing != null ? editing.blockId : "minecraft:diamond_ore");
		this.addRenderableWidget(blockIdBox);

		top += 34;
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
		this.addRenderableWidget(Button.builder(Component.literal("Save"), btn -> save())
				.bounds(left, bottom, 90, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Cancel"), btn -> this.minecraft.setScreen(parent))
				.bounds(left + 100, bottom, 90, 20).build());
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

	private void save() {
		String id = blockIdBox.getValue().trim();
		if (id.isEmpty()) {
			this.minecraft.setScreen(parent);
			return;
		}
		if (!id.contains(":")) {
			id = "minecraft:" + id;
		}

		if (editing != null) {
			editing.blockId = id;
			editing.color = color;
		} else {
			BlockRadar.CONFIG.highlights.add(new HighlightEntry(id, color));
		}
		BlockRadar.CONFIG.save();
		this.minecraft.setScreen(parent);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);

		graphics.text(this.font, this.title.getString(), 20, 12, 0xFFFFFFFF, true);
		graphics.text(this.font, "Block ID (e.g. minecraft:diamond_ore)", blockIdBox.getX(), blockIdBox.getY() - 10, 0xFFAAAAAA, false);
		graphics.text(this.font, "Hex color", hexBox.getX(), hexBox.getY() - 10, 0xFFAAAAAA, false);

		// Live preview swatch next to the hex box.
		int swatchX = hexBox.getX() + hexBox.getWidth() + 12;
		int swatchY = hexBox.getY();
		graphics.fill(swatchX, swatchY, swatchX + 40, swatchY + 20, 0xFF000000 | (color & 0x00FFFFFF));
		graphics.fill(swatchX + 2, swatchY + 2, swatchX + 38, swatchY + 18, color);
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(parent);
	}
}
