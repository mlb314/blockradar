package com.blockradar.config;

public final class ColorUtil {
	private ColorUtil() {
	}

	/** Parses "#RRGGBB", "RRGGBB", "#AARRGGBB" or "AARRGGBB" into a packed 0xAARRGGBB int. */
	public static int parseHex(String input, int fallback) {
		if (input == null) return fallback;
		String s = input.trim();
		if (s.startsWith("#")) s = s.substring(1);
		if (s.isEmpty()) return fallback;

		try {
			if (s.length() == 6) {
				int rgb = Integer.parseInt(s, 16);
				int alpha = fallback >>> 24;
				if (alpha == 0) alpha = 0xA0; // don't silently produce an invisible highlight
				return (alpha << 24) | (rgb & 0xFFFFFF);
			} else if (s.length() == 8) {
				return (int) Long.parseLong(s, 16);
			}
		} catch (NumberFormatException ignored) {
			// fall through to fallback
		}
		return fallback;
	}

	/** Formats the RGB portion only, as "#RRGGBB", for display in the hex text field. */
	public static String toHexRgb(int argb) {
		return String.format("#%06X", argb & 0xFFFFFF);
	}

	public static int withChannel(int argb, char channel, int value) {
		value = Math.max(0, Math.min(255, value));
		return switch (channel) {
			case 'a' -> (value << 24) | (argb & 0x00FFFFFF);
			case 'r' -> (argb & 0xFF00FFFF) | (value << 16);
			case 'g' -> (argb & 0xFFFF00FF) | (value << 8);
			case 'b' -> (argb & 0xFFFFFF00) | value;
			default -> argb;
		};
	}

	public static int channel(int argb, char channel) {
		return switch (channel) {
			case 'a' -> (argb >>> 24) & 0xFF;
			case 'r' -> (argb >> 16) & 0xFF;
			case 'g' -> (argb >> 8) & 0xFF;
			case 'b' -> argb & 0xFF;
			default -> 0;
		};
	}
}
