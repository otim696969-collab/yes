package com.selfmade;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Keeps every player's {@link PlayerIdentity} in memory and saves them to <world>/selfmade/identity.json. */
public final class IdentityStore {
	private static final Logger LOGGER = LoggerFactory.getLogger("selfmade");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type FILE_TYPE = new TypeToken<Map<String, PlayerIdentity>>() {
	}.getType();

	private final Map<UUID, PlayerIdentity> players = new HashMap<>();
	private Path file;

	/** Loads the identity file for the world that just started. */
	public synchronized void load(Path dir) {
		players.clear();
		file = dir.resolve("identity.json");
		if (!Files.exists(file)) {
			return;
		}
		try {
			String json = Files.readString(file, StandardCharsets.UTF_8);
			Map<String, PlayerIdentity> raw = GSON.fromJson(json, FILE_TYPE);
			if (raw != null) {
				raw.forEach((key, value) -> {
					try {
						value.normalize();
						players.put(UUID.fromString(key), value);
					} catch (RuntimeException e) {
						LOGGER.warn("Skipping unreadable identity entry {}", key);
					}
				});
			}
			LOGGER.info("Loaded {} identities", players.size());
		} catch (IOException | RuntimeException e) {
			LOGGER.error("Could not read {}; keeping a copy as identity.json.broken and starting fresh", file, e);
			try {
				Files.move(file, file.resolveSibling("identity.json.broken"), StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException ignored) {
				// nothing more we can do
			}
		}
	}

	public synchronized PlayerIdentity get(UUID id) {
		return players.computeIfAbsent(id, k -> new PlayerIdentity());
	}

	/** Writes to a temporary file first, so a crash can never leave a half-written save behind. */
	public synchronized void save() {
		if (file == null) {
			return;
		}
		try {
			Files.createDirectories(file.getParent());
			Map<String, PlayerIdentity> out = new HashMap<>();
			players.forEach((id, identity) -> out.put(id.toString(), identity));

			Path tmp = file.resolveSibling("identity.json.tmp");
			Files.writeString(tmp, GSON.toJson(out, FILE_TYPE), StandardCharsets.UTF_8);
			try {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			LOGGER.error("Could not save identities", e);
		}
	}
}
