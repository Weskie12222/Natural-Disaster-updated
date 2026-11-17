package com.naturaldisasters.registry;

import com.naturaldisasters.NaturalDisasters;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

/**
 * Custom sound events.
 * <p>
 * The siren is an 80 second recording cut into 16 consecutive 5 second mono pieces (siren_00 ... siren_15,
 * with tiny crossfades). {@link com.naturaldisasters.block.SirenBlock} plays them back to back so the siren
 * wails continuously. Range: the server sends them to players within {@link #SIREN_RANGE} blocks and the
 * client attenuation distance in sounds.json is 300 blocks.
 */
public final class ModSounds {
	public static final int SIREN_SEGMENTS = 16;
	public static final float SIREN_RANGE = 320f;

	public static final SoundEvent[] SIREN = new SoundEvent[SIREN_SEGMENTS];

	static {
		for (int i = 0; i < SIREN_SEGMENTS; i++) {
			SIREN[i] = register(String.format("siren_%02d", i), SIREN_RANGE);
		}
	}

	public static final SoundEvent RUMBLE = register("rumble", 128f);
	public static final SoundEvent ROAR = register("roar", 160f);
	public static final SoundEvent WAVE = register("wave", 160f);

	private static SoundEvent register(String name, float range) {
		Identifier id = NaturalDisasters.id(name);
		return Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createFixedRangeEvent(id, range));
	}

	public static void initialize() {
	}

	private ModSounds() {
	}
}
