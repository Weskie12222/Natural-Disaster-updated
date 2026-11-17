package com.naturaldisasters.disaster;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Base class for a disaster. Lifecycle:
 * WARNING (forecast; detectors and sirens fire; natural cues like rumbles and rain) -> ACTIVE (effects run)
 * -> LINGER (sirens keep wailing for a bit) -> removed.
 */
public abstract class Disaster {
	/** Ticks the sirens keep going after the disaster itself has ended (10 seconds). */
	private static final int LINGER_TICKS = 200;

	public final DisasterType type;
	public final Severity severity;
	public final ServerLevel level;
	/** Players who already received this disaster's forecast message. */
	public final Set<UUID> warned = new HashSet<>();
	protected final RandomSource random;
	protected Vec3 pos;
	/** Terrain type at the disaster's position. Disasters behave differently per environment. */
	protected Environment env;

	private final int warnTicks;
	private int age;
	private boolean started;
	private boolean ended;

	protected Disaster(DisasterType type, Severity severity, ServerLevel level, Vec3 pos, int warnTicks) {
		this.type = type;
		this.severity = severity;
		this.level = level;
		this.pos = pos;
		this.warnTicks = Math.max(1, warnTicks);
		this.random = level.getRandom();
		this.env = Environment.at(level, pos.x, pos.z);
	}

	/** How many ticks the ACTIVE phase lasts. */
	protected abstract int activeTicks();

	protected abstract void onStart();

	/** Called every tick during the ACTIVE phase. {@code t} is ticks since the impact. */
	protected abstract void tickActive(int t);

	protected abstract void onEnd();

	/** Called every tick during the WARNING phase. {@code age} counts up from 1. Use it for natural cues. */
	protected void tickWarning(int age) {
	}

	/** Removes anything temporary (water, etc.). Also called on server shutdown. */
	public void cleanup() {
	}

	/** @return true once the disaster (including linger time) is completely over and can be removed. */
	public final boolean tick() {
		age++;
		if (age < warnTicks) {
			tickWarning(age);
			return false;
		}
		if (!started) {
			started = true;
			onStart();
		}
		int t = age - warnTicks;
		if (t < activeTicks()) {
			tickActive(t);
			return false;
		}
		if (!ended) {
			ended = true;
			onEnd();
			cleanup();
		}
		return t >= activeTicks() + LINGER_TICKS;
	}

	/** Plays a sound to every player in the warning radius, louder the closer the impact is. */
	protected void cue(SoundEvent sound, float baseVolume, float pitch) {
		double progress = 1.0 - ticksToImpact() / (double) warnTicks;
		float volume = (float) (baseVolume * (0.3 + 0.7 * progress));
		double max = warningRadius();
		for (ServerPlayer p : level.players()) {
			if (p.position().distanceToSqr(pos) < max * max) {
				level.playSound(null, p.getX(), p.getY(), p.getZ(), sound, SoundSource.AMBIENT, volume, pitch);
			}
		}
	}

	public Vec3 position() {
		return pos;
	}

	public boolean warningPhase() {
		return !started;
	}

	public boolean isActive() {
		return started && !ended;
	}

	protected int ticksToImpact() {
		return Math.max(0, warnTicks - age);
	}

	public int secondsUntilImpact() {
		return Math.max(0, (warnTicks - age + 19) / 20);
	}

	/** Radius (blocks) in which players are warned and sirens go off. */
	public double warningRadius() {
		return 160.0 + 96.0 * severity.level;
	}

	/** Radius in which villagers panic once the disaster is actually active. */
	public double dangerRadius() {
		return warningRadius() * 0.5;
	}

	public boolean triggersSirens() {
		return severity.level >= type.sirenThreshold.level && !(ended && age - warnTicks >= activeTicks() + LINGER_TICKS);
	}

	public String describe() {
		return severity.label + " " + type.label;
	}
}
