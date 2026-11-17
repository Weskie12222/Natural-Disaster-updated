package com.naturaldisasters.disaster;

import com.naturaldisasters.item.DetectorItem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Owns every running disaster. Handles natural spawning, who gets warned, detector alerts and the siren query.
 * Disaster state is in memory only: it is cleaned up (temporary water removed) on server stop.
 */
public final class DisasterManager {
	private static final List<Disaster> ACTIVE = new ArrayList<>();
	private static boolean naturalEnabled = true;
	/** false: only players with a detector or within earshot of a siren are warned. true: everyone in range. */
	private static boolean warnEveryone = false;
	/** Average natural disaster interval in seconds (before difficulty scaling). */
	private static int meanSeconds = 900;
	private static long tickCount;
	/** How far away a siren is heard (blocks). Matches the sound's attenuation distance. */
	public static final double SIREN_HEARING = 300.0;

	/** Lit sirens: level -> (packed block pos -> server tick it was last seen wailing). */
	private static final Map<ServerLevel, Map<Long, Long>> SIRENS = new HashMap<>();

	private DisasterManager() {
	}

	public static void initialize() {
		ServerTickEvents.END_SERVER_TICK.register(DisasterManager::tick);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			for (Disaster d : ACTIVE) {
				d.cleanup();
			}
			ACTIVE.clear();
			TEST_ALARMS.clear();
			SIRENS.clear();
		});
	}

	// ------------------------------------------------------------------ tick

	private static void tick(MinecraftServer server) {
		tickCount++;

		for (Disaster d : new ArrayList<>(ACTIVE)) {
			if (d.tick()) {
				ACTIVE.remove(d);
			}
		}

		TEST_ALARMS.removeIf(a -> --a.ticksLeft <= 0);

		if (tickCount % 20 == 0) {
			warnPlayers();
			VillageReactions.tick(server, ACTIVE, tickCount);
		}
		if (tickCount % 40 == 0) {
			passiveDetectorAlerts(server);
		}
		if (tickCount % 200 == 0) {
			pruneSirens();
		}
		if (naturalEnabled && tickCount % 20 == 0) {
			naturalSpawn(server);
		}
	}

	// ------------------------------------------------------------------ warnings

	/**
	 * Sends the forecast to players who can know about it: a matching detector in range, or a wailing siren
	 * within earshot. This is what makes the tools necessary: without them, you only get the natural cues
	 * (rumbling, rain turning, a roar in the distance).
	 */
	private static void warnPlayers() {
		for (Disaster d : ACTIVE) {
			boolean countdown = d.warningPhase();
			for (ServerPlayer p : d.level.players()) {
				double dist = p.position().distanceTo(d.position());
				if (dist > d.warningRadius()) {
					continue;
				}
				boolean first = !d.warned.contains(p.getUUID());
				String via = null;
				if (warnEveryone) {
					via = "public broadcast";
				} else {
					int range = detectorRangeFor(p, d.type);
					if (range > 0 && dist <= range) {
						via = "detector";
					} else if (d.triggersSirens() && nearSiren(d.level, p.position(), SIREN_HEARING)) {
						via = "siren";
					}
				}
				if (via == null) {
					continue;
				}
				if (first) {
					d.warned.add(p.getUUID());
					Warnings.forecastTo(d, p, via);
				} else if (countdown) {
					p.sendSystemMessage(Component.literal("⚠ ").withStyle(d.severity.color)
							.append(Warnings.line(d, p.position())), true);
				}
			}
		}
	}

	// ------------------------------------------------------------------ starting disasters

	/** Lead time before impact: stronger disasters give less warning. */
	private static int leadTicks(DisasterType type, Severity severity) {
		double factor = 1.0 - 0.12 * (severity.level - 1);
		return Math.max(100, (int) (type.warningSeconds * 20 * factor));
	}

	/** Starts a disaster around {@code target} (used by commands and natural spawning). */
	public static Disaster spawnNear(DisasterType type, ServerLevel level, Vec3 target, Severity severity) {
		RandomSource r = level.getRandom();
		int warn = leadTicks(type, severity);
		Disaster d;
		switch (type) {
			case EARTHQUAKE -> d = new EarthquakeDisaster(level, offset(target, r, 10, 60), severity, warn, false);
			case FISSURE -> d = new FissureDisaster(level, offset(target, r, 15, 40), severity, warn);
			case TORNADO -> {
				double ang = r.nextDouble() * Math.PI * 2;
				double dist = 90 + r.nextDouble() * 50;
				Vec3 start = new Vec3(target.x + Math.cos(ang) * dist, target.y, target.z + Math.sin(ang) * dist);
				double heading = Math.atan2(target.z - start.z, target.x - start.x) + (r.nextDouble() - 0.5) * 0.3;
				d = new TornadoDisaster(level, start, heading, severity, warn);
			}
			case HURRICANE -> {
				double ang = r.nextDouble() * Math.PI * 2;
				Vec3 start = new Vec3(target.x + Math.cos(ang) * 150, target.y, target.z + Math.sin(ang) * 150);
				double heading = Math.atan2(target.z - start.z, target.x - start.x);
				d = new HurricaneDisaster(level, start, heading, severity, warn);
			}
			case FLOOD -> d = new FloodDisaster(level, target, severity, warn);
			case TSUNAMI -> d = createTsunami(level, target, severity, warn);
			default -> throw new IllegalStateException();
		}
		ACTIVE.add(d);
		return d;
	}

	/** Starts a disaster at an exact position (used by chained events: quake -> rift/tsunami/aftershock, etc.). */
	public static void spawnLinked(DisasterType type, ServerLevel level, Vec3 at, Severity severity, int warnSeconds) {
		int warn = warnSeconds * 20;
		Disaster d = switch (type) {
			case EARTHQUAKE -> new EarthquakeDisaster(level, at, severity, warn, true);
			case FISSURE -> new FissureDisaster(level, at, severity, warn);
			case TORNADO -> new TornadoDisaster(level, at, level.getRandom().nextDouble() * Math.PI * 2, severity, warn);
			case TSUNAMI -> createTsunami(level, at, severity, warn);
			case FLOOD -> new FloodDisaster(level, at, severity, warn);
			default -> null;
		};
		if (d != null) {
			ACTIVE.add(d);
		}
	}

	private static Disaster createTsunami(ServerLevel level, Vec3 target, Severity severity, int warn) {
		RandomSource r = level.getRandom();
		Vec3 ocean = findOcean(level, target, 192);
		Vec3 start;
		Vec3 dir;
		if (ocean != null) {
			dir = new Vec3(target.x - ocean.x, 0, target.z - ocean.z).normalize();
			start = ocean.subtract(dir.scale(30));
		} else {
			double ang = r.nextDouble() * Math.PI * 2;
			dir = new Vec3(Math.cos(ang), 0, Math.sin(ang));
			start = target.subtract(dir.scale(110));
			start = new Vec3(start.x, target.y, start.z);
		}
		double distance = start.distanceTo(target) + 110 + 20 * severity.level;
		return new TsunamiDisaster(level, start, dir, distance, severity, warn);
	}

	private static Vec3 offset(Vec3 base, RandomSource r, double min, double max) {
		double ang = r.nextDouble() * Math.PI * 2;
		double dist = min + r.nextDouble() * (max - min);
		return new Vec3(base.x + Math.cos(ang) * dist, base.y, base.z + Math.sin(ang) * dist);
	}

	// ------------------------------------------------------------------ ocean detection

	/** Finds the closest loaded sea-level water in 16 directions. */
	public static Vec3 findOcean(ServerLevel level, Vec3 from, int maxDist) {
		Vec3 best = null;
		double bestDist = Double.MAX_VALUE;
		int sea = level.getSeaLevel() - 1;
		for (int i = 0; i < 16; i++) {
			double ang = i * Math.PI / 8;
			for (int dist = 8; dist <= maxDist; dist += 8) {
				int x = (int) Math.floor(from.x + Math.cos(ang) * dist);
				int z = (int) Math.floor(from.z + Math.sin(ang) * dist);
				if (!DisasterUtil.loaded(level, x, z)) {
					break;
				}
				if (level.getBlockState(new BlockPos(x, sea, z)).is(Blocks.WATER)) {
					if (dist < bestDist) {
						bestDist = dist;
						best = new Vec3(x, sea + 1, z);
					}
					break;
				}
			}
		}
		return best;
	}

	public static boolean isCoastal(ServerLevel level, Vec3 at, int maxDist) {
		return findOcean(level, at, maxDist) != null;
	}

	// ------------------------------------------------------------------ natural spawning

	/** Weights per disaster type, in {@link DisasterType} order: earthquake, rift, tornado, hurricane, tsunami, flood. */
	private static int[] weights(Environment env) {
		return switch (env) {
			case ARID -> new int[] {3, 2, 3, 3, 0, 1};
			case SNOWY -> new int[] {3, 1, 1, 4, 1, 1};
			case WET -> new int[] {2, 1, 2, 3, 2, 4};
			case MOUNTAIN -> new int[] {5, 3, 1, 1, 0, 1};
			case OCEAN -> new int[] {2, 0, 2, 4, 4, 0};
			default -> new int[] {3, 2, 4, 1, 2, 3};
		};
	}

	private static DisasterType pickNatural(Environment env, boolean coastal, RandomSource r) {
		int[] w = weights(env).clone();
		if (!coastal && env != Environment.OCEAN) {
			w[DisasterType.TSUNAMI.ordinal()] = 0;
		}
		int total = 0;
		for (int v : w) {
			total += v;
		}
		int roll = r.nextInt(Math.max(1, total));
		DisasterType[] types = DisasterType.values();
		for (int i = 0; i < w.length; i++) {
			roll -= w[i];
			if (roll < 0) {
				return types[i];
			}
		}
		return DisasterType.EARTHQUAKE;
	}

	private static void naturalSpawn(MinecraftServer server) {
		ServerLevel overworld = server.getLevel(Level.OVERWORLD);
		if (overworld == null || overworld.players().isEmpty() || overworld.getDifficulty() == Difficulty.PEACEFUL) {
			return;
		}
		double scale = switch (overworld.getDifficulty()) {
			case EASY -> 1.5;
			case HARD -> 0.7;
			default -> 1.0;
		};
		RandomSource r = overworld.getRandom();
		if (r.nextInt(Math.max(20, (int) (meanSeconds * scale))) != 0) {
			return;
		}
		// Do not stack natural disasters on top of a serious one.
		for (Disaster d : ACTIVE) {
			if (d.severity.level >= Severity.STRONG.level) {
				return;
			}
		}
		ServerPlayer player = overworld.players().get(r.nextInt(overworld.players().size()));
		Vec3 target = player.position();

		// Disasters like to hit villages: 40% of the time aim at a village near the player.
		if (r.nextInt(100) < 40) {
			AABB box = new AABB(target.x - 160, overworld.getMinY(), target.z - 160,
					target.x + 160, overworld.getMaxY(), target.z + 160);
			List<LivingEntity> villagers = new ArrayList<>(overworld.getEntities(EntityType.VILLAGER, box, e -> true));
			if (!villagers.isEmpty()) {
				target = villagers.get(r.nextInt(villagers.size())).position();
			}
		}

		Environment env = Environment.at(overworld, target.x, target.z);
		boolean coastal = isCoastal(overworld, target, 192);
		DisasterType type = pickNatural(env, coastal, r);
		spawnNear(type, overworld, target, Severity.random(r, overworld.getDifficulty()));
	}

	public static boolean naturalEnabled() {
		return naturalEnabled;
	}

	public static void setNaturalEnabled(boolean enabled) {
		naturalEnabled = enabled;
	}

	public static boolean warnEveryone() {
		return warnEveryone;
	}

	public static void setWarnEveryone(boolean everyone) {
		warnEveryone = everyone;
	}

	public static int meanSeconds() {
		return meanSeconds;
	}

	public static void setMeanSeconds(int seconds) {
		meanSeconds = Math.max(30, seconds);
	}

	// ------------------------------------------------------------------ queries

	public static List<Disaster> active() {
		return List.copyOf(ACTIVE);
	}

	public static void stopAll() {
		for (Disaster d : ACTIVE) {
			d.cleanup();
		}
		ACTIVE.clear();
		TEST_ALARMS.clear();
	}

	// ------------------------------------------------------------------ sirens

	private static final class TestAlarm {
		final ServerLevel level;
		final Vec3 center;
		final double radius;
		int ticksLeft;

		TestAlarm(ServerLevel level, Vec3 center, double radius, int ticksLeft) {
			this.level = level;
			this.center = center;
			this.radius = radius;
			this.ticksLeft = ticksLeft;
		}
	}

	private static final List<TestAlarm> TEST_ALARMS = new ArrayList<>();

	/** Manual alarm for /disaster siren: sirens within {@code radius} blocks of {@code center} wail for {@code seconds}. */
	public static void startTestAlarm(ServerLevel level, Vec3 center, double radius, int seconds) {
		TEST_ALARMS.add(new TestAlarm(level, center, radius, seconds * 20));
	}

	/** Should a siren at this position be wailing? (siren-worthy disaster in range, or a manual test alarm) */
	public static boolean alarmActive(ServerLevel level, BlockPos pos) {
		Vec3 p = Vec3.atCenterOf(pos);
		for (Disaster d : ACTIVE) {
			if (d.level == level && d.triggersSirens() && p.distanceTo(d.position()) <= d.warningRadius()) {
				return true;
			}
		}
		for (TestAlarm a : TEST_ALARMS) {
			if (a.level == level && p.distanceTo(a.center) <= a.radius) {
				return true;
			}
		}
		return false;
	}

	/** Called by a siren every time it polls while wailing. */
	public static void noteSiren(ServerLevel level, BlockPos pos) {
		SIRENS.computeIfAbsent(level, k -> new HashMap<>()).put(pos.asLong(), tickCount);
	}

	/** Is a wailing siren within {@code range} blocks of this position? */
	public static boolean nearSiren(ServerLevel level, Vec3 at, double range) {
		Map<Long, Long> map = SIRENS.get(level);
		if (map == null) {
			return false;
		}
		for (Map.Entry<Long, Long> e : map.entrySet()) {
			if (tickCount - e.getValue() <= 30 && Vec3.atCenterOf(BlockPos.of(e.getKey())).distanceTo(at) <= range) {
				return true;
			}
		}
		return false;
	}

	private static void pruneSirens() {
		for (Map<Long, Long> map : SIRENS.values()) {
			map.values().removeIf(last -> tickCount - last > 200);
		}
	}

	// ------------------------------------------------------------------ detectors

	/** Right-click readout of a detector. */
	public static void sendReadout(ServerPlayer player, ServerLevel level, String deviceName, Set<DisasterType> types, int range) {
		List<Disaster> found = new ArrayList<>();
		for (Disaster d : ACTIVE) {
			if (d.level == level && types.contains(d.type)
					&& player.position().distanceTo(d.position()) <= range) {
				found.add(d);
			}
		}
		player.sendSystemMessage(Component.literal("[" + deviceName + "] range " + range + " blocks")
				.withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
		if (found.isEmpty()) {
			player.sendSystemMessage(Component.literal("  All clear - no activity detected.").withStyle(ChatFormatting.GREEN));
			return;
		}
		for (Disaster d : found) {
			player.sendSystemMessage(Component.literal("  ").append(Warnings.line(d, player.position())));
		}
	}

	/** Every 2 seconds: players carrying a detector get an action-bar alert for the worst matching disaster. */
	private static void passiveDetectorAlerts(MinecraftServer server) {
		for (ServerLevel level : server.getAllLevels()) {
			for (ServerPlayer player : level.players()) {
				Disaster worst = null;
				for (Disaster d : ACTIVE) {
					if (d.level != level) {
						continue;
					}
					int range = detectorRangeFor(player, d.type);
					if (range <= 0 || player.position().distanceTo(d.position()) > range) {
						continue;
					}
					if (worst == null || d.severity.level > worst.severity.level) {
						worst = d;
					}
				}
				if (worst != null) {
					Component msg = Component.literal("⚠ ").withStyle(worst.severity.color)
							.append(Warnings.line(worst, player.position()));
					player.sendSystemMessage(msg, true);
				}
			}
		}
	}

	/** Best detector range the player carries for this disaster type, or 0 if none. */
	private static int detectorRangeFor(ServerPlayer player, DisasterType type) {
		int best = 0;
		Container inv = player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.getItem() instanceof DetectorItem det && det.detects(type)) {
				best = Math.max(best, det.range());
			}
		}
		return best;
	}
}
