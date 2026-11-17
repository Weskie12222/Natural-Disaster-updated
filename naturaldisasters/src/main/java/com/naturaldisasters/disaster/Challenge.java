package com.naturaldisasters.disaster;

import java.util.Locale;

/** Difficulty presets. Configurable in config/naturaldisasters.json or with /disaster difficulty. */
public enum Challenge {
	//     id        damage frequency warning severityBias
	EASY("easy", 0.5f, 1.6f, 1.4f, -1),
	NORMAL("normal", 1.0f, 1.0f, 1.0f, 0),
	HARD("hard", 1.5f, 0.7f, 0.7f, 1),
	BRUTAL("brutal", 2.2f, 0.4f, 0.45f, 2);

	public final String id;
	/** Multiplier on direct damage dealt by disasters. */
	public final float damage;
	/** Multiplier on the time between natural disasters (lower = more often). */
	public final float frequency;
	/** Multiplier on warning lead time (lower = less time to react). */
	public final float warning;
	/** Shifts natural severity upward (positive) or downward (negative). */
	public final int severityBias;

	Challenge(String id, float damage, float frequency, float warning, int severityBias) {
		this.id = id;
		this.damage = damage;
		this.frequency = frequency;
		this.warning = warning;
		this.severityBias = severityBias;
	}

	public static Challenge parse(String s) {
		if (s != null) {
			for (Challenge c : values()) {
				if (c.id.equals(s.toLowerCase(Locale.ROOT))) {
					return c;
				}
			}
		}
		return NORMAL;
	}
}
