package com.blockradar.gui;

import java.util.function.IntConsumer;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/** A 0-255 slider for one color channel. Acts as the "RGB wheel" stand-in - three of
 *  these (R, G, B) plus one for alpha let you dial in any color, and stay in sync
 *  with the hex text field in EditHighlightScreen. */
public class ColorSlider extends AbstractSliderButton {
	private final String label;
	private final IntConsumer onChange;

	public ColorSlider(int x, int y, int width, int height, String label, int initialValue0to255, IntConsumer onChange) {
		super(x, y, width, height, Component.literal(label + ": " + initialValue0to255), clamp01(initialValue0to255 / 255.0));
		this.label = label;
		this.onChange = onChange;
	}

	private static double clamp01(double v) {
		return Math.max(0.0, Math.min(1.0, v));
	}

	public int currentValue() {
		return (int) Math.round(this.value * 255);
	}

	public void setValueSilently(int value0to255) {
		this.value = clamp01(value0to255 / 255.0);
		updateMessage();
	}

	@Override
	protected void updateMessage() {
		this.setMessage(Component.literal(label + ": " + currentValue()));
	}

	@Override
	protected void applyValue() {
		onChange.accept(currentValue());
	}
}
