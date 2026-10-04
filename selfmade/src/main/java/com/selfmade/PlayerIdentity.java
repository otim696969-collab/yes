package com.selfmade;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Everything the mod remembers about one player. Public non-transient fields are saved to JSON. */
public class PlayerIdentity {

	/** One recorded death. This is the seed of the future "Grave of You". */
	public static class DeathEcho {
		public String dim;
		public int x, y, z;
		public long when;
		public String cause;

		public DeathEcho() {
		}

		public DeathEcho(String dim, int x, int y, int z, long when, String cause) {
			this.dim = dim;
			this.x = x;
			this.y = y;
			this.z = z;
			this.when = when;
			this.cause = cause;
		}
	}

	/** Exponentially decaying counter, used to give repeated identical actions diminishing returns. */
	private static class Heat {
		double value;
		long lastTick;
	}

	/** Heat halves every minute (1200 ticks) of not repeating the same action. */
	private static final double HEAT_HALF_LIFE_TICKS = 1200.0;

	// ---- saved ----
	public Map<String, Double> traits = new LinkedHashMap<>();
	public List<DeathEcho> echoes = new ArrayList<>();
	public Set<Long> visitedCells = new HashSet<>();
	public Set<String> omensSeen = new HashSet<>();

	// ---- this session only (not saved) ----
	public transient DeathEcho pendingReturn;
	public transient long pendingFromTick;
	public transient long pendingUntilTick;
	public transient boolean wasLow;
	public transient long closeCallTick; // 0 = no close call being watched
	public transient double closeCallX;
	public transient double closeCallY;
	public transient double closeCallZ;
	public transient long lastHauntMessageTick = -100000L;
	private transient Map<String, Heat> heat = new HashMap<>();

	public double get(Trait trait) {
		return traits.getOrDefault(trait.name(), 0.0);
	}

	/**
	 * Adds to a trait. If a signal key is given, repeating that same signal in quick succession is worth less
	 * each time, so grinding a mob farm moves you far less than a handful of real fights.
	 */
	public void gain(Trait trait, double base, String signal, long nowTick) {
		double scale = 1.0;
		if (signal != null) {
			Heat h = heat.computeIfAbsent(signal, k -> new Heat());
			long dt = Math.max(0L, nowTick - h.lastTick);
			h.value *= Math.pow(0.5, dt / HEAT_HALF_LIFE_TICKS);
			scale = 1.0 / (1.0 + h.value);
			h.value += 1.0;
			h.lastTick = nowTick;
		}
		traits.merge(trait.name(), base * scale, Double::sum);
	}

	/** Slow fade, so old behaviour counts for less than recent behaviour and people can change. */
	public void decay(double factor) {
		traits.replaceAll((k, v) -> v * factor);
	}

	/** Called after loading from JSON: swap Gson's collection types for plain ones and fill any gaps. */
	public void normalize() {
		traits = traits == null ? new LinkedHashMap<>() : new LinkedHashMap<>(traits);
		echoes = echoes == null ? new ArrayList<>() : new ArrayList<>(echoes);
		visitedCells = visitedCells == null ? new HashSet<>() : new HashSet<>(visitedCells);
		omensSeen = omensSeen == null ? new HashSet<>() : new HashSet<>(omensSeen);
		echoes.removeIf(e -> e == null || e.dim == null);
		if (heat == null) {
			heat = new HashMap<>();
		}
	}
}
