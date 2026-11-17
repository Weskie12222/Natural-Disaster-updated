package com.naturaldisasters.disaster;

import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;

public enum Severity {
	MINOR("Minor", 1, ChatFormatting.YELLOW),
	MODERATE("Moderate", 2, ChatFormatting.GOLD),
	STRONG("Strong", 3, ChatFormatting.RED),
	EXTREME("Extreme", 4, ChatFormatting.DARK_RED);

	public final String label;
	public final int level;
	public final ChatFormatting color;

	Severity(String label, int level, ChatFormatting color) {
		this.label = label;
		this.level = level;
		this.color = color;
	}

	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}

	public static Severity parse(String s) {
		for (Severity v : values()) {
			if (v.id().equals(s.toLowerCase(Locale.ROOT))) {
				return v;
			}
		}
		return null;
	}

	/** One step weaker (used for aftershocks). */
	public Severity lower() {
		return this == MINOR ? MINOR : values()[ordinal() - 1];
	}

	/**
	 * Natural-spawn weights (minor/moderate/strong/extreme), shifted by world difficulty:
	 * easy 55/30/12/3, normal 40/30/20/10, hard 20/30/30/20.
	 */
	public static Severity random(RandomSource random, Difficulty difficulty) {
		int[] w = switch (difficulty) {
			case PEACEFUL, EASY -> new int[] {55, 85, 97};
			case NORMAL -> new int[] {40, 70, 90};
			case HARD -> new int[] {20, 50, 80};
		};
		int roll = random.nextInt(100);
		if (roll < w[0]) {
			return MINOR;
		}
		if (roll < w[1]) {
			return MODERATE;
		}
		if (roll < w[2]) {
			return STRONG;
		}
		return EXTREME;
	}
}
