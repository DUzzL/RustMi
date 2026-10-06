package com.github.tatercertified.rustmi;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.impl.FabricLoaderImpl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public record Config(String version, boolean debug, boolean test, boolean reducedMemoryUsage, boolean enhancedLithiumSupport) {
    private static final String CONFIG_VERSION_KEY = "config-version";
    private static final String CONFIG_VERSION = "1.0";
    private static Config instance;
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("rustmi.properties");
    public static void init() {
        final Properties properties = new Properties();

        if (Files.exists(CONFIG_PATH)) {
            try {
                loadConfig(properties);
            } catch (IOException e) {
                // instance is still null on this path — log unconditionally
                Rustmi.LOGGER.error("Config creation failed", e);
            }
        }

        // A foreign config-version is rewritten (not merely left alone): with
        // every other key present the old path never parsed and left instance
        // null, crashing the first Config.getInstance() caller.
        boolean changed = !CONFIG_VERSION.equals(properties.getProperty(CONFIG_VERSION_KEY));
        properties.setProperty(CONFIG_VERSION_KEY, CONFIG_VERSION);
        changed |= checkProperty("debug", "false", properties);
        changed |= checkProperty("test-mode", "false", properties);
        changed |= checkProperty("reduced-memory-usage", "true", properties);
        changed |= checkProperty("enhanced-lithium-compat", "true", properties);

        // Parse before storing so an unwritable config dir cannot leave instance null.
        parse(properties);
        if (changed) {
            try {
                storeConfig(properties);
            } catch (IOException e) {
                if (instance.debug()) {
                    Rustmi.LOGGER.error("Config storing failed", e);
                }
            }
        }
    }

    private static void storeConfig(Properties properties) throws IOException {
        // No explicit options = CREATE + TRUNCATE_EXISTING + WRITE. CREATE alone
        // keeps stale trailing bytes whenever the rewritten file is shorter.
        try (OutputStream output = Files.newOutputStream(CONFIG_PATH)) {
            properties.store(output, null);
        }
    }

    private static boolean checkProperty(String key, String defaultValue, Properties properties) {
        boolean missingKey = !properties.containsKey(key);
        if (missingKey) {
            properties.setProperty(key, defaultValue);
        }
        return missingKey;
    }

    private static void loadConfig(Properties properties) throws IOException {
        try (InputStream input = Files.newInputStream(CONFIG_PATH)) {
            properties.load(input);
        }
    }

    private static void parse(Properties properties) {
        boolean debug = Boolean.parseBoolean(properties.getProperty("debug"));
        boolean testingMode = Boolean.parseBoolean(properties.getProperty("test-mode"));
        boolean reducedMemUse = Boolean.parseBoolean(properties.getProperty("reduced-memory-usage"));
        boolean enhancedLithiumCompat = Boolean.parseBoolean(properties.getProperty("enhanced-lithium-compat"));
        instance = new Config(CONFIG_VERSION, debug, testingMode, reducedMemUse, enhancedLithiumCompat);
    }

    public static Config getInstance() {
        return instance;
    }

    public static boolean isLithiumOptimizationEnabled() {
        return instance.enhancedLithiumSupport() && FabricLoaderImpl.InitHelper.get().isModLoaded("lithium");
    }

    public static boolean isTestingEnabled() {
        return instance.test();
    }
}
