package com.selfmade;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Slice 0 of "The Self Made Flesh": the identity engine.
 * It watches what each player does, keeps eight hidden trait scores, remembers where they died,
 * and quietly starts to haunt those places. There is no Eidolon entity, mirror, memory system,
 * Inner World or boss yet; they all need to read from this first.
 */
public class SelfMade implements ModInitializer {
	public static final String MOD_ID = "selfmade";
	private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// Tuning knobs. These numbers are educated guesses: play, then adjust.
	private static final int SAMPLE_EVERY_TICKS = 10;       // look at each player twice a second
	private static final double OMEN_THRESHOLD = 25.0;      // trait score at which an omen is whispered
	private static final double DECAY_PER_MINUTE = 0.999;   // slow fade of old behaviour (online time only)
	private static final int ECHO_LIMIT = 64;               // remembered deaths per player

	private static final IdentityStore STORE = new IdentityStore();
	private static long tick = 0;

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(server ->
				STORE.load(server.getWorldPath(LevelResource.ROOT).resolve(MOD_ID)));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> STORE.save());
		ServerLivingEntityEvents.AFTER_DEATH.register(SelfMade::onDeath);
		ServerTickEvents.END_SERVER_TICK.register(SelfMade::onTick);
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				registerCommands(dispatcher));
		LOGGER.info("The Self Made Flesh: identity engine online. Something is watching what you do.");
	}

	// ------------------------------------------------------------------ events

	private static void onDeath(LivingEntity victim, DamageSource source) {
		Entity attacker = source.getEntity();
		if (attacker instanceof ServerPlayer killer && !(victim instanceof ServerPlayer)) {
			onPlayerKilled(killer, victim);
		}
		if (victim instanceof ServerPlayer player) {
			onPlayerDied(player, attacker);
		}
	}

	private static void onPlayerKilled(ServerPlayer killer, LivingEntity victim) {
		PlayerIdentity id = STORE.get(killer.getUUID());
		EntityType<?> type = victim.getType();

		if (type == EntityType.VILLAGER || type == EntityType.WANDERING_TRADER || type == EntityType.IRON_GOLEM) {
			id.gain(Trait.VIOLENCE, 4.0, "kill:innocent", tick);
		} else if (type.getCategory() == MobCategory.MONSTER) {
			id.gain(Trait.VIOLENCE, 0.6, "kill:monster", tick);
			if (id.closeCallTick != 0 && tick - id.closeCallTick < 600) {
				// They were nearly dead and kept fighting. That is a choice, so it counts for more than the kill.
				id.gain(Trait.RESOLVE, 2.0, "pressed-on", tick);
				id.gain(Trait.WILL, 1.0, "pressed-on", tick);
				id.closeCallTick = 0;
			}
		} else if (type.getCategory() == MobCategory.CREATURE) {
			id.gain(Trait.VIOLENCE, 0.3, "kill:animal", tick);
		}
	}

	private static void onPlayerDied(ServerPlayer player, Entity attacker) {
		PlayerIdentity id = STORE.get(player.getUUID());
		String cause = attacker == null ? "environment" : (attacker instanceof ServerPlayer ? "player" : "creature");

		PlayerIdentity.DeathEcho echo = new PlayerIdentity.DeathEcho(
				dimensionOf(player), player.getBlockX(), player.getBlockY(), player.getBlockZ(),
				System.currentTimeMillis(), cause);
		id.echoes.add(echo);
		while (id.echoes.size() > ECHO_LIMIT) {
			id.echoes.remove(0);
		}

		id.gain(Trait.FEAR, 3.0, "death", tick);

		// If they come back for the place where they died, that is Will (see trackReturnToDeathSite).
		id.pendingReturn = echo;
		id.pendingFromTick = tick + 600;             // not within the first 30 seconds
		id.pendingUntilTick = tick + 20L * 60 * 10;  // and not after 10 minutes
		id.closeCallTick = 0;
		id.wasLow = false;
	}

	private static void onTick(MinecraftServer server) {
		tick++;
		if (tick % SAMPLE_EVERY_TICKS != 0) {
			return;
		}
		boolean decayNow = tick % 1200 == 0;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			sample(player);
			if (decayNow) {
				STORE.get(player.getUUID()).decay(DECAY_PER_MINUTE);
			}
		}
		if (tick % 6000 == 0) {
			STORE.save();
		}
	}

	// ---------------------------------------------------------------- sampling

	private static void sample(ServerPlayer player) {
		if (!player.isAlive()) {
			return;
		}
		PlayerIdentity id = STORE.get(player.getUUID());
		String dim = dimensionOf(player);

		// Curiosity: stepping into a 64x64 patch of the world you have never stood in before.
		if (id.visitedCells.add(cellKey(dim, player.getBlockX() >> 6, player.getBlockZ() >> 6))) {
			id.gain(Trait.CURIOSITY, 0.5, null, tick);
		}

		trackCloseCalls(player, id);
		trackReturnToDeathSite(player, id, dim);

		if (tick % 20 == 0) {
			haunt(player, id, dim);
		}

		for (Trait trait : Trait.values()) {
			if (id.get(trait) >= OMEN_THRESHOLD && id.omensSeen.add(trait.name())) {
				whisper(player, trait.omen);
			}
		}
	}

	/** Fear or Resolve, depending on what the player does after nearly dying to a creature. */
	private static void trackCloseCalls(ServerPlayer player, PlayerIdentity id) {
		float hp = player.getHealth();
		float max = player.getMaxHealth();

		if (hp > max * 0.5f) {
			id.wasLow = false;
		}

		if (hp <= max * 0.3f && !id.wasLow) {
			id.wasLow = true;
			DamageSource last = player.getLastDamageSource();
			if (last != null && last.getEntity() != null) { // hurt by a creature, not by hunger or a fall
				id.closeCallTick = tick;
				id.closeCallX = player.getX();
				id.closeCallY = player.getY();
				id.closeCallZ = player.getZ();
			}
		}

		// Ten seconds after a close call, look at what they did about it.
		if (id.closeCallTick != 0 && tick - id.closeCallTick >= 200) {
			boolean fled = player.distanceToSqr(id.closeCallX, id.closeCallY, id.closeCallZ) > 20.0 * 20.0;
			if (fled) {
				id.gain(Trait.FEAR, 1.5, "fled", tick);
			} else {
				id.gain(Trait.RESOLVE, 1.0, "held-ground", tick);
			}
			id.closeCallTick = 0;
		}
	}

	/** Will: going back for the place you died. */
	private static void trackReturnToDeathSite(ServerPlayer player, PlayerIdentity id, String dim) {
		PlayerIdentity.DeathEcho site = id.pendingReturn;
		if (site == null) {
			return;
		}
		if (tick > id.pendingUntilTick) {
			id.pendingReturn = null;
			return;
		}
		if (tick < id.pendingFromTick || !site.dim.equals(dim)) {
			return;
		}
		if (player.distanceToSqr(site.x + 0.5, site.y, site.z + 0.5) < 6.0 * 6.0) {
			id.gain(Trait.WILL, 2.0, "returned", tick);
			id.gain(Trait.RESOLVE, 1.0, "returned", tick);
			id.pendingReturn = null;
		}
	}

	/** Soul particles where you have died. Three or more deaths in one spot and the place gets heavier. */
	private static void haunt(ServerPlayer player, PlayerIdentity id, String dim) {
		if (!(player.level() instanceof ServerLevel level)) {
			return;
		}
		for (PlayerIdentity.DeathEcho echo : id.echoes) {
			if (!echo.dim.equals(dim)) {
				continue;
			}
			double dx = echo.x + 0.5 - player.getX();
			double dz = echo.z + 0.5 - player.getZ();
			double distSq = dx * dx + dz * dz;
			if (distSq > 24.0 * 24.0) {
				continue;
			}

			int neighbours = 0;
			for (PlayerIdentity.DeathEcho other : id.echoes) {
				if (other != echo && other.dim.equals(dim)
						&& Math.abs(other.x - echo.x) <= 12 && Math.abs(other.z - echo.z) <= 12) {
					neighbours++;
				}
			}
			boolean haunted = neighbours >= 2;

			level.sendParticles(ParticleTypes.SOUL, echo.x + 0.5, echo.y + 0.5, echo.z + 0.5,
					haunted ? 8 : 2, 0.3, 0.6, 0.3, 0.01);
			if (haunted) {
				level.sendParticles(ParticleTypes.SCULK_SOUL, echo.x + 0.5, echo.y + 0.5, echo.z + 0.5,
						3, 0.4, 0.8, 0.4, 0.02);
				if (distSq < 10.0 * 10.0 && tick - id.lastHauntMessageTick > 6000) {
					id.lastHauntMessageTick = tick;
					whisper(player, "The ground here remembers you.");
				}
			}
		}
	}

	// ---------------------------------------------------------------- commands

	private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("eidolon")
				.then(Commands.literal("read").executes(ctx -> read(ctx.getSource())))
				.then(Commands.literal("debug").executes(ctx -> debug(ctx.getSource()))));
	}

	/** The subtle readout: no numbers, just what is starting to follow you. */
	private static int read(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		PlayerIdentity id = STORE.get(player.getUUID());

		List<Trait> ranked = new ArrayList<>(List.of(Trait.values()));
		ranked.sort((a, b) -> Double.compare(id.get(b), id.get(a)));
		Trait first = ranked.get(0);
		Trait second = ranked.get(1);

		if (id.get(first) < 1.0) {
			say(source, "Nothing follows you. Not yet.");
		} else {
			say(source, first.omen);
			if (id.get(second) >= 1.0) {
				say(source, second.omen);
			}
		}
		return 1;
	}

	/**
	 * Raw numbers. This is a development tool and anyone can run it for now;
	 * the design says the game should never tell the player what they are, so lock this down before release.
	 */
	private static int debug(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		PlayerIdentity id = STORE.get(player.getUUID());

		StringBuilder sb = new StringBuilder("Identity (debug)");
		for (Trait trait : Trait.values()) {
			sb.append('\n').append(String.format(Locale.ROOT, "  %s: %.1f", trait.label(), id.get(trait)));
		}
		sb.append("\n  death echoes: ").append(id.echoes.size());
		sb.append("\n  places visited: ").append(id.visitedCells.size());

		String text = sb.toString();
		source.sendSuccess(() -> Component.literal(text), false);
		return 1;
	}

	// ----------------------------------------------------------------- helpers

	private static void say(CommandSourceStack source, String text) {
		source.sendSuccess(() -> Component.literal(text)
				.withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.ITALIC), false);
	}

	private static void whisper(ServerPlayer player, String text) {
		player.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.ITALIC));
	}

	private static String dimensionOf(ServerPlayer player) {
		var key = player.level().dimension();
		if (key.equals(Level.OVERWORLD)) {
			return "overworld";
		}
		if (key.equals(Level.NETHER)) {
			return "nether";
		}
		if (key.equals(Level.END)) {
			return "end";
		}
		return "other";
	}

	/** Packs a dimension and a 64x64-block cell coordinate into one long. */
	private static long cellKey(String dim, int cellX, int cellZ) {
		long d = switch (dim) {
			case "overworld" -> 0L;
			case "nether" -> 1L;
			case "end" -> 2L;
			default -> 3L;
		};
		return (d << 40) | ((long) (cellX & 0xFFFFF) << 20) | (cellZ & 0xFFFFF);
	}
}
