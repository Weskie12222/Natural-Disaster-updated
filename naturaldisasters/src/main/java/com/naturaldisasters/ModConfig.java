package com.naturaldisasters;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.naturaldisasters.disaster.Challenge;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/** Simple JSON config at config/naturaldisasters.json. Edit it, or use /disaster commands. */
public final class ModConfig {
	/** easy | normal | hard | brutal */
	public String difficulty = "normal";
	/** Random natural disasters in the overworld. */
	public boolean naturalDisasters = true;
	/** Average minutes between natural disasters (before the difficulty multiplier). */
	public int naturalIntervalMinutes = 15;
	/** If false, disasters hurt and push but never destroy blocks. */
	public boolean blockDamage = true;
	/** Villagers hide, ring bells and react to sirens and disasters. */
	public boolean villagerReactions = true;
	/** Strong+ earthquakes are followed by aftershocks that come with almost no warning. */
	public boolean aftershocks = true;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static ModConfig instance = new ModConfig();

	public static ModConfig get() {
		return instance;
	}

	public Challenge challenge() {
		return Challenge.parse(difficulty);
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("naturaldisasters.json");
	}

	public static void load() {
		Path path = file();
		try {
			if (Files.exists(path)) {
				ModConfig read = GSON.fromJson(Files.readString(path), ModConfig.class);
				if (read != null) {
					instance = read;
				}
			}
			save(); // writes defaults / adds new fields
		} catch (IOException | JsonParseException e) {
			System.err.println("[naturaldisasters] could not read config, using defaults: " + e);
		}
	}

	public static void save() {
		try {
			Files.writeString(file(), GSON.toJson(instance));
		} catch (IOException e) {
			System.err.println("[naturaldisasters] could not save config: " + e);
		}
	}
}
