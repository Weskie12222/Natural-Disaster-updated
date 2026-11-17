package com.naturaldisasters.disaster;

import java.util.Locale;

public enum DisasterType {
	// name, base warning lead time in seconds, minimum severity that sets off sirens
	EARTHQUAKE("Earthquake", 25, Severity.STRONG),
	FISSURE("Ground Rift", 20, Severity.STRONG),
	TORNADO("Tornado", 60, Severity.STRONG),
	HURRICANE("Hurricane", 120, Severity.STRONG),
	TSUNAMI("Tsunami", 90, Severity.MODERATE),
	FLOOD("Flood", 90, Severity.STRONG);

	public final String label;
	public final int warningSeconds;
	public final Severity sirenThreshold;

	DisasterType(String label, int warningSeconds, Severity sirenThreshold) {
		this.label = label;
		this.warningSeconds = warningSeconds;
		this.sirenThreshold = sirenThreshold;
	}

	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}

	public static DisasterType parse(String s) {
		for (DisasterType t : values()) {
			if (t.id().equals(s.toLowerCase(Locale.ROOT))) {
				return t;
			}
		}
		return null;
	}
}
