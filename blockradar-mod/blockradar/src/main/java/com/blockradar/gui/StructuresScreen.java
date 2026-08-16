package com.blockradar.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import com.blockradar.BlockRadar;
import com.blockradar.structure.StructureManager;
import com.blockradar.structure.StructureTemplate;

/**
 * Lists saved structure templates and lets you capture a new one from your current
 * corner-1/corner-2 selection (see BlockRadar's select-corner keybindings).
 */
public class StructuresScreen extends Screen {
	private final Screen parent;
	private final List<AbstractWidget> rowWidgets = new ArrayList<>();

	private static final int LEFT = 20;

	public StructuresScreen(Screen parent) {
		super(Component.literal("Block Radar - Structures"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		rebuildList();

		int bottom = this.height - 28;
		this.addRenderableWidget(Button.builder(Component.literal("+ Capture New From Selection"), btn ->
				this.minecraft.setScreen(new CaptureStructureScreen(this))
		).bounds(LEFT, bottom, 200, 20).build());

		this.addRenderableWidget(Button.builder(Component.literal("Back"), btn ->
				this.minecraft.setScreen(parent)
		).bounds(LEFT + 210, bottom, 80, 20).build());
	}

	private void rebuildList() {
		for (AbstractWidget w : rowWidgets) this.removeWidget(w);
		rowWidgets.clear();

		List<StructureTemplate> templates = StructureManager.loadAll();
		int rowY = 60;

		for (int i = 0; i < templates.size(); i++) {
			StructureTemplate template = templates.get(i);
			int y = rowY + i * 24;

			Checkbox toggle = Checkbox.builder(Component.literal(template.name), this.font)
					.pos(LEFT, y)
					.selected(template.enabled)
					.onValueChange((cb, value) -> {
						template.enabled = value;
						StructureManager.save(template);
					})
					.build();

			Button delete = Button.builder(Component.literal("Delete"), btn -> {
				StructureManager.delete(template);
				rebuildList();
			}).bounds(LEFT + 250, y, 60, 20).build();

			this.addRenderableWidget(toggle);
			this.addRenderableWidget(delete);
			rowWidgets.add(toggle);
			rowWidgets.add(delete);
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
		super.extractRenderState(graphics, mouseX, mouseY, delta);

		graphics.text(this.font, this.title.getString(), LEFT, 12, 0xFFFFFFFF, true);
		graphics.text(this.font, "Blocks/structures still change from mining/griefing - use the match", LEFT, 30, 0xFFAAAAAA, false);
		graphics.text(this.font, "% threshold when capturing to tolerate some damage.", LEFT, 40, 0xFFAAAAAA, false);

		if (StructureManager.loadAll().isEmpty()) {
			graphics.text(this.font, "(no saved structures yet)", LEFT, 60, 0xFF888888, false);
		}
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(parent);
	}
}
