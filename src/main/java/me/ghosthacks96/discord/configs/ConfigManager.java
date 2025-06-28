package me.ghosthacks96.discord.configs;


import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class ConfigManager {
    private static final Logger LOGGER = Logger.getLogger(ConfigManager.class.getName());

    // Singleton instance
    private static volatile ConfigManager instance;
    private static final Object INSTANCE_LOCK = new Object();

    // Configuration storage
    private final Map<String, Config> configs = new ConcurrentHashMap<>();
    private final Map<String, ConfigSource> configSources = new ConcurrentHashMap<>();
    private final ReadWriteLock globalLock = new ReentrantReadWriteLock();

    // Change notification
    private final Map<String, List<Consumer<Config>>> changeListeners = new ConcurrentHashMap<>();

    // Auto-reload and monitoring
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Map<String, WatchService> fileWatchers = new ConcurrentHashMap<>();
    private volatile boolean autoReloadEnabled = false;
    private volatile long reloadCheckInterval = 30; // seconds

    // Environment and profile support
    private String activeProfile = "default";
    private final Map<String, String> environmentOverrides = new ConcurrentHashMap<>();

    // Configuration hierarchy
    private final List<String> configPrecedence = new ArrayList<>();

    private ConfigManager() {
        setupShutdownHook();
        loadEnvironmentOverrides();
    }

    /**
     * Get singleton instance
     */
    public static ConfigManager getInstance() {
        if (instance == null) {
            synchronized (INSTANCE_LOCK) {
                if (instance == null) {
                    instance = new ConfigManager();
                }
            }
        }
        return instance;
    }

    /**
     * Configuration Source abstraction
     */
    public interface ConfigSource {
        Config load() throws Exception;
        boolean hasChanged();
        String getSourceInfo();
        boolean isReadOnly();
    }

    /**
     * File-based configuration source
     */
    public static class FileConfigSource implements ConfigSource {
        private final String filePath;
        private volatile long lastModified = 0;

        public FileConfigSource(String filePath) {
            this.filePath = filePath;
        }

        @Override
        public Config load() throws Exception {
            Config config = new Config(filePath);
            Path path = Paths.get(filePath);
            if (Files.exists(path)) {
                lastModified = Files.getLastModifiedTime(path).toMillis();
            }
            return config;
        }

        @Override
        public boolean hasChanged() {
            try {
                Path path = Paths.get(filePath);
                if (!Files.exists(path)) return false;

                long currentModified = Files.getLastModifiedTime(path).toMillis();
                return currentModified > lastModified;
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error checking file modification time: " + filePath, e);
                return false;
            }
        }

        @Override
        public String getSourceInfo() {
            return "File: " + filePath;
        }

        @Override
        public boolean isReadOnly() {
            try {
                return !Files.isWritable(Paths.get(filePath));
            } catch (Exception e) {
                return true;
            }
        }
    }

    /**
     * Classpath resource configuration source
     */
    public static class ClasspathConfigSource implements ConfigSource {
        private final String resourcePath;

        public ClasspathConfigSource(String resourcePath) {
            this.resourcePath = resourcePath;
        }

        @Override
        public Config load() throws Exception {
            return new Config("classpath:" + resourcePath);
        }

        @Override
        public boolean hasChanged() {
            return false; // Classpath resources don't change during runtime
        }

        @Override
        public String getSourceInfo() {
            return "Classpath: " + resourcePath;
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }
    }

    /**
     * Environment variables configuration source
     */
    public static class EnvironmentConfigSource implements ConfigSource {
        private final String prefix;

        public EnvironmentConfigSource(String prefix) {
            this.prefix = prefix != null ? prefix : "";
        }

        @Override
        public Config load() throws Exception {
            Map<String, Object> envData = new HashMap<>();

            System.getenv().entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(prefix))
                    .forEach(entry -> {
                        String key = entry.getKey().substring(prefix.length()).toLowerCase().replace('_', '.');
                        envData.put(key, entry.getValue());
                    });

            return new Config(envData);
        }

        @Override
        public boolean hasChanged() {
            return false; // Environment variables typically don't change during runtime
        }

        @Override
        public String getSourceInfo() {
            return "Environment variables (prefix: " + prefix + ")";
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }
    }

    /**
     * Register a configuration source
     */
    public ConfigManager registerConfig(String name, ConfigSource source) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Config name cannot be null or empty");
        }
        if (source == null) {
            throw new IllegalArgumentException("Config source cannot be null");
        }

        globalLock.writeLock().lock();
        try {
            configSources.put(name, source);

            // Load the configuration
            reloadConfig(name);

            // Setup file watching if needed and auto-reload is enabled
            if (autoReloadEnabled && source instanceof FileConfigSource) {
                setupFileWatcher(name, ((FileConfigSource) source).filePath);
            }

            LOGGER.info("Registered config: " + name + " from " + source.getSourceInfo());

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to register config: " + name, e);
            throw new ConfigManager.ConfigManagerException("Failed to register config: " + name, e);
        } finally {
            globalLock.writeLock().unlock();
        }

        return this;
    }

    /**
     * Convenience methods for common config sources
     */
    public ConfigManager registerFileConfig(String name, String filePath) {
        return registerConfig(name, new FileConfigSource(filePath));
    }

    public ConfigManager registerClasspathConfig(String name, String resourcePath) {
        return registerConfig(name, new ClasspathConfigSource(resourcePath));
    }

    public ConfigManager registerEnvironmentConfig(String name, String prefix) {
        return registerConfig(name, new EnvironmentConfigSource(prefix));
    }

    /**
     * Get a configuration by name
     */
    public Config getConfig(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Config name cannot be null or empty");
        }

        globalLock.readLock().lock();
        try {
            Config config = configs.get(name);
            if (config == null) {
                throw new ConfigManagerException("Configuration not found: " + name);
            }
            return config;
        } finally {
            globalLock.readLock().unlock();
        }
    }

    /**
     * Get configuration with profile support
     */
    public Config getConfig(String name, String profile) {
        String profiledName = profile != null ? name + "." + profile : name;

        // Try profiled version first, fall back to base config
        globalLock.readLock().lock();
        try {
            Config config = configs.get(profiledName);
            if (config != null) {
                return config;
            }

            // Fallback to base config
            return getConfig(name);
        } finally {
            globalLock.readLock().unlock();
        }
    }

    /**
     * Get the main/default configuration
     */
    public Config getMainConfig() {
        return getConfig("main");
    }

    /**
     * Check if a configuration exists
     */
    public boolean hasConfig(String name) {
        globalLock.readLock().lock();
        try {
            return configs.containsKey(name);
        } finally {
            globalLock.readLock().unlock();
        }
    }

    /**
     * Get all configuration names
     */
    public Set<String> getConfigNames() {
        globalLock.readLock().lock();
        try {
            return new HashSet<>(configs.keySet());
        } finally {
            globalLock.readLock().unlock();
        }
    }

    /**
     * Reload a specific configuration
     */
    public void reloadConfig(String name) {
        ConfigSource source = configSources.get(name);
        if (source == null) {
            throw new ConfigManagerException("Config source not found: " + name);
        }

        globalLock.writeLock().lock();
        try {
            Config oldConfig = configs.get(name);
            Config newConfig = source.load();

            // Apply environment overrides
            applyEnvironmentOverrides(newConfig);

            configs.put(name, newConfig);

            // Notify listeners if config changed
            if (oldConfig == null || !configsEqual(oldConfig, newConfig)) {
                notifyConfigChanged(name, newConfig);
            }

            LOGGER.info("Reloaded config: " + name);

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to reload config: " + name, e);
            throw new ConfigManagerException("Failed to reload config: " + name, e);
        } finally {
            globalLock.writeLock().unlock();
        }
    }

    /**
     * Reload all configurations
     */
    public void reloadAll() {
        globalLock.writeLock().lock();
        try {
            List<String> configNames = new ArrayList<>(configSources.keySet());
            for (String name : configNames) {
                try {
                    reloadConfig(name);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Failed to reload config during reload all: " + name, e);
                }
            }
        } finally {
            globalLock.writeLock().unlock();
        }
    }

    /**
     * Save a configuration (if writable)
     */
    public void saveConfig(String name) {
        ConfigSource source = configSources.get(name);
        if (source == null) {
            throw new ConfigManagerException("Config source not found: " + name);
        }

        if (source.isReadOnly()) {
            throw new ConfigManagerException("Config is read-only: " + name);
        }

        Config config = getConfig(name);
        if (source instanceof FileConfigSource) {
            config.save();
            LOGGER.info("Saved config: " + name);
        } else {
            throw new ConfigManagerException("Save not supported for this config type: " + name);
        }
    }

    /**
     * Auto-reload configuration
     */
    public ConfigManager enableAutoReload(long intervalSeconds) {
        this.reloadCheckInterval = intervalSeconds;
        this.autoReloadEnabled = true;

        // Start the auto-reload scheduler
        scheduler.scheduleAtFixedRate(this::checkForChanges,
                intervalSeconds, intervalSeconds, TimeUnit.SECONDS);

        // Setup file watchers for existing file configs
        globalLock.readLock().lock();
        try {
            for (Map.Entry<String, ConfigSource> entry : configSources.entrySet()) {
                if (entry.getValue() instanceof FileConfigSource) {
                    setupFileWatcher(entry.getKey(), ((FileConfigSource) entry.getValue()).filePath);
                }
            }
        } finally {
            globalLock.readLock().unlock();
        }

        LOGGER.info("Auto-reload enabled with interval: " + intervalSeconds + " seconds");
        return this;
    }

    public ConfigManager disableAutoReload() {
        this.autoReloadEnabled = false;

        // Clean up file watchers
        for (WatchService watcher : fileWatchers.values()) {
            try {
                watcher.close();
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Error closing file watcher", e);
            }
        }
        fileWatchers.clear();

        LOGGER.info("Auto-reload disabled");
        return this;
    }

    /**
     * Profile support
     */
    public ConfigManager setActiveProfile(String profile) {
        this.activeProfile = profile != null ? profile : "default";
        LOGGER.info("Active profile set to: " + this.activeProfile);
        return this;
    }

    public String getActiveProfile() {
        return activeProfile;
    }

    /**
     * Environment override support
     */
    public ConfigManager setEnvironmentOverride(String key, String value) {
        if (key != null && value != null) {
            environmentOverrides.put(key, value);

            // Apply to all existing configs
            globalLock.writeLock().lock();
            try {
                for (Config config : configs.values()) {
                    config.set(key, value);
                }
            } finally {
                globalLock.writeLock().unlock();
            }
        }
        return this;
    }

    public ConfigManager removeEnvironmentOverride(String key) {
        environmentOverrides.remove(key);
        return this;
    }

    /**
     * Change notification support
     */
    public ConfigManager addChangeListener(String configName, Consumer<Config> listener) {
        if (configName != null && listener != null) {
            changeListeners.computeIfAbsent(configName, k -> new ArrayList<>()).add(listener);
        }
        return this;
    }

    public ConfigManager removeChangeListener(String configName, Consumer<Config> listener) {
        List<Consumer<Config>> listeners = changeListeners.get(configName);
        if (listeners != null) {
            listeners.remove(listener);
            if (listeners.isEmpty()) {
                changeListeners.remove(configName);
            }
        }
        return this;
    }

    /**
     * Configuration merging and hierarchy
     */
    public ConfigManager setConfigPrecedence(String... configNames) {
        globalLock.writeLock().lock();
        try {
            configPrecedence.clear();
            configPrecedence.addAll(Arrays.asList(configNames));
        } finally {
            globalLock.writeLock().unlock();
        }
        return this;
    }

    public Config getMergedConfig(String... configNames) {
        globalLock.readLock().lock();
        try {
            Config merged = new Config();

            // Use precedence order if specified, otherwise use provided order
            List<String> orderedNames = configPrecedence.isEmpty() ?
                    Arrays.asList(configNames) :
                    configPrecedence.stream()
                            .filter(name -> Arrays.asList(configNames).contains(name))
                            .collect(Collectors.toList());

            for (String name : orderedNames) {
                Config config = configs.get(name);
                if (config != null) {
                    merged.merge(config);
                }
            }

            return merged;
        } finally {
            globalLock.readLock().unlock();
        }
    }

    /**
     * Utility methods
     */
    public Map<String, String> getConfigInfo() {
        globalLock.readLock().lock();
        try {
            Map<String, String> info = new HashMap<>();
            for (Map.Entry<String, ConfigSource> entry : configSources.entrySet()) {
                info.put(entry.getKey(), entry.getValue().getSourceInfo());
            }
            return info;
        } finally {
            globalLock.readLock().unlock();
        }
    }

    public boolean isHealthy() {
        globalLock.readLock().lock();
        try {
            // Check if all configs are loadable
            for (Map.Entry<String, ConfigSource> entry : configSources.entrySet()) {
                try {
                    entry.getValue().load();
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Health check failed for config: " + entry.getKey(), e);
                    return false;
                }
            }
            return true;
        } finally {
            globalLock.readLock().unlock();
        }
    }

    /**
     * Private helper methods
     */
    private void checkForChanges() {
        if (!autoReloadEnabled) return;

        for (Map.Entry<String, ConfigSource> entry : configSources.entrySet()) {
            try {
                if (entry.getValue().hasChanged()) {
                    LOGGER.info("Detected changes in config: " + entry.getKey());
                    reloadConfig(entry.getKey());
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error checking for changes in config: " + entry.getKey(), e);
            }
        }
    }

    private void setupFileWatcher(String configName, String filePath) {
        try {
            Path path = Paths.get(filePath);
            Path directory = path.getParent();

            if (directory != null && Files.exists(directory)) {
                WatchService watcher = FileSystems.getDefault().newWatchService();
                directory.register(watcher, StandardWatchEventKinds.ENTRY_MODIFY);
                fileWatchers.put(configName, watcher);

                // Start watching in a separate thread
                scheduler.submit(() -> watchFile(configName, watcher, path.getFileName().toString()));
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to setup file watcher for: " + filePath, e);
        }
    }

    private void watchFile(String configName, WatchService watcher, String fileName) {
        try {
            while (autoReloadEnabled) {
                WatchKey key = watcher.take();

                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.context().toString().equals(fileName)) {
                        LOGGER.info("File watcher detected change in: " + configName);
                        reloadConfig(configName);
                    }
                }

                if (!key.reset()) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "File watcher error for: " + configName, e);
        }
    }

    private void loadEnvironmentOverrides() {
        String prefix = "CONFIG_OVERRIDE_";
        System.getenv().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .forEach(entry -> {
                    String key = entry.getKey().substring(prefix.length()).toLowerCase().replace('_', '.');
                    environmentOverrides.put(key, entry.getValue());
                });
    }

    private void applyEnvironmentOverrides(Config config) {
        for (Map.Entry<String, String> override : environmentOverrides.entrySet()) {
            config.set(override.getKey(), override.getValue());
        }
    }

    private boolean configsEqual(Config config1, Config config2) {
        try {
            return config1.toYaml().equals(config2.toYaml());
        } catch (Exception e) {
            return false;
        }
    }

    private void notifyConfigChanged(String configName, Config newConfig) {
        List<Consumer<Config>> listeners = changeListeners.get(configName);
        if (listeners != null) {
            for (Consumer<Config> listener : listeners) {
                try {
                    listener.accept(newConfig);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error in config change listener for: " + configName, e);
                }
            }
        }
    }

    private void setupShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("ConfigManager shutting down...");
            disableAutoReload();
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }));
    }

    /**
     * Builder pattern for easy setup
     */
    public static class Builder {
        private final ConfigManager manager = new ConfigManager();

        public Builder addFileConfig(String name, String filePath) {
            manager.registerFileConfig(name, filePath);
            return this;
        }

        public Builder addClasspathConfig(String name, String resourcePath) {
            manager.registerClasspathConfig(name, resourcePath);
            return this;
        }

        public Builder addEnvironmentConfig(String name, String prefix) {
            manager.registerEnvironmentConfig(name, prefix);
            return this;
        }

        public Builder enableAutoReload(long intervalSeconds) {
            manager.enableAutoReload(intervalSeconds);
            return this;
        }

        public Builder setProfile(String profile) {
            manager.setActiveProfile(profile);
            return this;
        }

        public Builder setPrecedence(String... configNames) {
            manager.setConfigPrecedence(configNames);
            return this;
        }

        public ConfigManager build() {
            return manager;
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Custom exception for ConfigManager errors
     */
    public static class ConfigManagerException extends RuntimeException {
        public ConfigManagerException(String message) {
            super(message);
        }

        public ConfigManagerException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
