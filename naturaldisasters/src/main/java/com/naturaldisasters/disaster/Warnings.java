package com.naturaldisasters.disaster;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Chat / action-bar warnings sent to players near a disaster. */
public final class Warnings {
	private Warnings() {
	}

	/**
	 * The forecast message. Sent once per player and disaster, to players who can know about it:
	 * carrying the right detector, standing within earshot of a wailing siren, or everyone
	 * when {@code /disaster warnings everyone} is set.
	 * @param via why this player is being warned, shown in the message
	 */
	public static void forecastTo(Disaster d, ServerPlayer p, String via) {
		double dist = p.position().distanceTo(d.position());
		boolean siren = d.triggersSirens();
		Component head = Component.literal("⚠ " + d.describe().toUpperCase() + (siren ? " WARNING" : " FORECAST"))
				.withStyle(d.severity.color, ChatFormatting.BOLD);
		Component detail = Component.literal("  " + (int) dist + " blocks " + DisasterUtil.compass(p.position(), d.position())
				+ " - impact in " + d.secondsUntilImpact() + "s  (" + via + ")")
				.withStyle(ChatFormatting.GRAY);
		p.sendSystemMessage(head);
		p.sendSystemMessage(detail);
		p.sendSystemMessage(advice(d));
	}

	public static void broadcastImpact(Disaster d, String text) {
		for (ServerPlayer p : d.level.players()) {
			if (p.position().distanceTo(d.position()) <= d.warningRadius()) {
				p.sendSystemMessage(Component.literal(text).withStyle(d.severity.color, ChatFormatting.BOLD), true);
			}
		}
	}

	public static void broadcastEnd(Disaster d) {
		for (ServerPlayer p : d.level.players()) {
			if (p.position().distanceTo(d.position()) <= d.warningRadius()) {
				p.sendSystemMessage(Component.literal(d.type.label + " has passed. Stay alert for aftershocks, fires and debris.")
						.withStyle(ChatFormatting.GREEN), true);
			}
		}
	}

	/** Survival advice, adapted to the environment. */
	private static Component advice(Disaster d) {
		String text = switch (d.type) {
			case EARTHQUAKE -> "Get into the open, away from buildings and glass. Underground, ceilings can collapse. Expect aftershocks and fires.";
			case FISSURE -> "The ground will split. Move away from the marked area and do not stand near cave openings.";
			case TORNADO -> "Take shelter underground with a roof over you - being in the open or in a house is not safe.";
			case HURRICANE -> "Get indoors under a solid roof. Wind only hurts under open sky.";
			case TSUNAMI -> "Move inland and to high ground immediately - above the wave height.";
			case FLOOD -> "Move to high ground. Water fills valleys and low terrain first.";
		};
		String extra = switch (d.env) {
			case ARID -> d.type == DisasterType.HURRICANE || d.type == DisasterType.TORNADO
					? " Sand blinds and slows you - find cover early." : "";
			case SNOWY -> d.type == DisasterType.HURRICANE || d.type == DisasterType.TORNADO || d.type == DisasterType.FLOOD
					? " It is freezing: stay near a light source or indoors." : d.type == DisasterType.EARTHQUAKE
					? " Avalanche risk on snowy slopes." : "";
			case MOUNTAIN -> d.type == DisasterType.EARTHQUAKE || d.type == DisasterType.HURRICANE
					? " Landslide risk on steep slopes." : d.type == DisasterType.FLOOD ? " Flash flood: it rises fast." : "";
			case WET -> d.type == DisasterType.FLOOD ? " Monsoon floods stay for a long time." : "";
			default -> "";
		};
		return Component.literal("  " + text + extra).withStyle(ChatFormatting.YELLOW);
	}

	/** Status line shown by detectors. */
	public static Component line(Disaster d, Vec3 viewer) {
		double dist = viewer.distanceTo(d.position());
		String when = d.isActive() ? "ACTIVE NOW" : "impact in " + d.secondsUntilImpact() + "s";
		return Component.literal(d.describe() + " - " + (int) dist + " blocks " + DisasterUtil.compass(viewer, d.position())
				+ " - " + when).withStyle(d.severity.color);
	}
}
