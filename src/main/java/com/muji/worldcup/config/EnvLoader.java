package com.muji.worldcup.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads a .env file and sets each KEY=VALUE pair as a system property
 * (accessible via System.getenv() won't work post-JVM-start, so we use
 * System.setProperty and a thin getRequired() helper instead).
 */
public class EnvLoader {

    private static final Logger log = LoggerFactory.getLogger(EnvLoader.class);

    public static void load(Path envFile) throws IOException {
        if (!Files.exists(envFile)) {
            log.warn(".env file not found at {}, relying on real environment variables", envFile);
            return;
        }
        for (String line : Files.readAllLines(envFile)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int eq = trimmed.indexOf('=');
            if (eq < 1) continue;
            String key = trimmed.substring(0, eq).strip();
            String value = trimmed.substring(eq + 1).strip();
            // Only set if the real environment doesn't already provide a value,
            // so a deployed systemd unit can override via Environment= directives.
            if (System.getenv(key) == null) {
                System.setProperty(key, value);
            }
        }
        log.debug("Loaded .env from {}", envFile);
    }

    /** Returns the value from system property (loaded from .env) or real env var. */
    public static String getRequired(String key) {
        String val = get(key);
        if (val == null || val.isBlank()) {
            throw new IllegalStateException("Required env var not set: " + key);
        }
        return val;
    }

    public static String get(String key) {
        String fromEnv = System.getenv(key);
        return fromEnv != null ? fromEnv : System.getProperty(key);
    }
}
