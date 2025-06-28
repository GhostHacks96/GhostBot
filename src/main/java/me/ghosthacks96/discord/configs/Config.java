package me.ghosthacks96.discord.configs;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;
import java.time.Instant;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Robust multi-purpose configuration class using SnakeYAML
 * Features: Thread-safety, validation, backup/recovery, change detection
 */
public class Config {
    private static final Logger LOGGER = Logger.getLogger(Config.class.getName());

    // Thread-safe data storage
    private final Map<String, Object> data = new ConcurrentHashMap<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    // YAML handling
    private final Yaml yaml;
    private final LoaderOptions loaderOptions;
    private final DumperOptions dumpOptions;

    // File management
    private volatile String configFile;
    private volatile long lastModified = 0;
    private volatile boolean autoReload = false;

    // Validation
    private final Set<String> requiredKeys = new HashSet<>();
    private final Map<String, Class<?>> typeConstraints = new HashMap<>();
    private final Map<String, Pattern> valuePatterns = new HashMap<>();

    // Constants
    private static final String BACKUP_SUFFIX = ".backup";
    private static final int MAX_BACKUP_COUNT = 5;
    private static final String KEY_SEPARATOR = ".";

    public Config() {
        this.loaderOptions = createLoaderOptions();
        this.dumpOptions = createDumperOptions();
        this.yaml = createYaml();
    }

    public Config(String configFile) {
        this();
        setConfigFile(configFile);
        load();
    }

    public Config(Map<String, Object> initialData) {
        this();
        if (initialData != null) {
            lock.writeLock().lock();
            try {
                this.data.putAll(initialData);
            } finally {
                lock.writeLock().unlock();
            }
        }
    }

    private LoaderOptions createLoaderOptions() {
        LoaderOptions options = new LoaderOptions();
        options.setProcessComments(true);
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(100);
        options.setAllowRecursiveKeys(false); // Security: prevent recursive bombs
        options.setCodePointLimit(1024 * 1024); // 1MB limit
        return options;
    }

    private DumperOptions createDumperOptions() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        options.setCanonical(false);
        options.setSplitLines(true);
        options.setWidth(120);
        return options;
    }

    private Yaml createYaml() {
        Representer representer = new Representer(dumpOptions);
        representer.getPropertyUtils().setSkipMissingProperties(true);
        return new Yaml(new Constructor(loaderOptions), representer, dumpOptions);
    }

    /**
     * Set the configuration file path with validation
     */
    public void setConfigFile(String configFile) {
        validateConfigPath(configFile);
        this.configFile = configFile;
    }

    public String getConfigFile() {
        return configFile;
    }

    public String getName() {
        return configFile != null ? Paths.get(configFile).getFileName().toString() : "unnamed";
    }

    /**
     * Enable/disable automatic reloading when file changes
     */
    public void setAutoReload(boolean autoReload) {
        this.autoReload = autoReload;
    }

    /**
     * Add validation constraints
     */
    public Config requireKey(String key) {
        if (key != null && !key.trim().isEmpty()) {
            requiredKeys.add(key.trim());
        }
        return this;
    }

    public Config requireType(String key, Class<?> type) {
        if (key != null && type != null) {
            typeConstraints.put(key, type);
        }
        return this;
    }

    public Config requirePattern(String key, String pattern) {
        if (key != null && pattern != null) {
            valuePatterns.put(key, Pattern.compile(pattern));
        }
        return this;
    }

    /**
     * Load configuration with robust error handling
     */
    public void load() {
        if (configFile == null) {
            throw new IllegalStateException("Config file path not set");
        }
        // Skip loading for classpath resources
        if (configFile.startsWith("classpath:")) {
            LOGGER.info("Skipping load for classpath resource: " + configFile);
            return;
        }
        load(configFile);
    }

    @SuppressWarnings("unchecked")
    public void load(String filename) {
        validateConfigPath(filename);
        // Skip loading for classpath resources
        if (filename.startsWith("classpath:")) {
            LOGGER.info("Skipping load for classpath resource: " + filename);
            return;
        }
        lock.writeLock().lock();
        try {
            this.configFile = filename;
            Path path = Paths.get(filename);

            // Check if we should reload based on file modification
            if (autoReload && Files.exists(path)) {
                long currentModified = Files.getLastModifiedTime(path).toMillis();
                if (currentModified <= lastModified) {
                    LOGGER.fine("Config file not modified, skipping reload: " + filename);
                    return;
                }
                lastModified = currentModified;
            }

            if (!Files.exists(path)) {
                LOGGER.info("Config file does not exist, creating: " + filename);
                data.clear();
                save();
                return;
            }

            // Create backup before loading
            createBackup(path);

            Map<String, Object> loadedData = loadFromFile(path);

            // Validate loaded data
            validateConfiguration(loadedData);

            // Update data atomically
            data.clear();
            data.putAll(loadedData);

            LOGGER.info("Successfully loaded config: " + filename);

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to load config: " + filename, e);
            // Try to recover from backup
            if (!tryRecoverFromBackup(filename)) {
                throw new ConfigException("Failed to load config and recovery failed: " + filename, e);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadFromFile(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path, StandardOpenOption.READ)) {
            Object loaded = yaml.load(input);
            return (loaded instanceof Map) ? (Map<String, Object>) loaded : new HashMap<>();
        } catch (YAMLException e) {
            throw new ConfigException("Invalid YAML format in: " + path, e);
        }
    }

    /**
     * Save configuration with atomic write and backup
     */
    public void save() {
        if (configFile == null) {
            throw new IllegalStateException("Config file path not set");
        }
        // Skip saving for classpath resources
        if (configFile.startsWith("classpath:")) {
            LOGGER.info("Skipping save for classpath resource: " + configFile);
            return;
        }
        save(configFile);
    }

    public void save(String filename) {
        validateConfigPath(filename);
        // Skip saving for classpath resources
        if (filename.startsWith("classpath:")) {
            LOGGER.info("Skipping save for classpath resource: " + filename);
            return;
        }
        lock.readLock().lock();
        try {
            // Validate before saving
            validateConfiguration(data);

            Path path = Paths.get(filename);
            Path tempPath = Paths.get(filename + ".tmp");

            // Ensure parent directory exists
            Files.createDirectories(path.getParent());

            // Write to temporary file first (atomic write)
            try (Writer writer = Files.newBufferedWriter(tempPath, StandardCharsets.UTF_8)) {
                yaml.dump(new HashMap<>(data), writer);
            }

            // Move temp file to final location
            Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);

            lastModified = Files.getLastModifiedTime(path).toMillis();

            LOGGER.info("Successfully saved config: " + filename);

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to save config: " + filename, e);
            throw new ConfigException("Failed to save config: " + filename, e);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Thread-safe getter with dot notation support
     */
    public Object get(String key) {
        return get(key, null);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, T defaultValue) {
        if (!isValidKey(key)) {
            return defaultValue;
        }

        lock.readLock().lock();
        try {
            String[] parts = key.split("\\.");
            Object current = data;

            for (String part : parts) {
                if (!(current instanceof Map)) {
                    return defaultValue;
                }
                current = ((Map<String, Object>) current).get(part);
                if (current == null) {
                    return defaultValue;
                }
            }

            return (T) current;
        } catch (ClassCastException e) {
            LOGGER.warning("Type mismatch for key '" + key + "': " + e.getMessage());
            return defaultValue;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Enhanced type-safe getters with validation
     */
    public String getString(String key, String defaultValue) {
        Object value = get(key);
        if (value == null) return defaultValue;

        String result = value.toString();

        // Apply pattern validation if configured
        Pattern pattern = valuePatterns.get(key);
        if (pattern != null && !pattern.matcher(result).matches()) {
            LOGGER.warning("Value for key '" + key + "' doesn't match required pattern");
            return defaultValue;
        }

        return result;
    }

    public String getString(String key) {
        return getString(key, null);
    }

    public Integer getInt(String key, Integer defaultValue) {
        Object value = get(key);
        if (value == null) return defaultValue;

        if (value instanceof Number) {
            return ((Number) value).intValue();
        }

        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            LOGGER.warning("Cannot convert value to integer for key '" + key + "': " + value);
            return defaultValue;
        }
    }

    public Integer getInt(String key) {
        return getInt(key, null);
    }

    public Double getDouble(String key, Double defaultValue) {
        Object value = get(key);
        if (value == null) return defaultValue;

        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }

        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            LOGGER.warning("Cannot convert value to double for key '" + key + "': " + value);
            return defaultValue;
        }
    }

    public Double getDouble(String key) {
        return getDouble(key, null);
    }

    public Boolean getBoolean(String key, Boolean defaultValue) {
        Object value = get(key);
        if (value == null) return defaultValue;

        if (value instanceof Boolean) {
            return (Boolean) value;
        }

        String str = value.toString().toLowerCase().trim();
        return "true".equals(str) || "yes".equals(str) || "1".equals(str) || "on".equals(str);
    }

    public Boolean getBoolean(String key) {
        return getBoolean(key, null);
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> getList(String key, List<T> defaultValue) {
        Object value = get(key);
        if (value instanceof List) {
            return new ArrayList<>((List<T>) value);
        }
        return defaultValue != null ? new ArrayList<>(defaultValue) : new ArrayList<>();
    }

    public List<Object> getList(String key) {
        return getList(key, new ArrayList<>());
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getMap(String key, Map<String, Object> defaultValue) {
        Object value = get(key);
        if (value instanceof Map) {
            return new HashMap<>((Map<String, Object>) value);
        }
        return defaultValue != null ? new HashMap<>(defaultValue) : new HashMap<>();
    }

    public Map<String, Object> getMap(String key) {
        return getMap(key, new HashMap<>());
    }

    /**
     * Thread-safe setter with validation
     */
    @SuppressWarnings("unchecked")
    public void set(String key, Object value) {
        if (!isValidKey(key)) {
            throw new IllegalArgumentException("Invalid key: " + key);
        }

        // Validate type constraints
        Class<?> expectedType = typeConstraints.get(key);
        if (expectedType != null && value != null && !expectedType.isInstance(value)) {
            throw new IllegalArgumentException("Value for key '" + key + "' must be of type " + expectedType.getSimpleName());
        }

        lock.writeLock().lock();
        try {
            String[] parts = key.split("\\.");
            Map<String, Object> current = data;

            // Navigate to parent
            for (int i = 0; i < parts.length - 1; i++) {
                String part = parts[i];
                Object next = current.get(part);

                if (!(next instanceof Map)) {
                    next = new HashMap<String, Object>();
                    current.put(part, next);
                }
                current = (Map<String, Object>) next;
            }

            current.put(parts[parts.length - 1], value);

        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Thread-safe remove
     */
    @SuppressWarnings("unchecked")
    public boolean remove(String key) {
        if (!isValidKey(key)) {
            return false;
        }

        lock.writeLock().lock();
        try {
            String[] parts = key.split("\\.");
            Map<String, Object> current = data;

            // Navigate to parent
            for (int i = 0; i < parts.length - 1; i++) {
                String part = parts[i];
                Object next = current.get(part);
                if (!(next instanceof Map)) {
                    return false;
                }
                current = (Map<String, Object>) next;
            }

            return current.remove(parts[parts.length - 1]) != null;

        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean has(String key) {
        return get(key) != null;
    }

    public Set<String> getKeys() {
        lock.readLock().lock();
        try {
            Set<String> keys = new HashSet<>();
            flattenKeys("", data, keys);
            return keys;
        } finally {
            lock.readLock().unlock();
        }
    }

    @SuppressWarnings("unchecked")
    private void flattenKeys(String prefix, Map<String, Object> map, Set<String> keys) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + KEY_SEPARATOR + entry.getKey();
            keys.add(key);

            if (entry.getValue() instanceof Map) {
                flattenKeys(key, (Map<String, Object>) entry.getValue(), keys);
            }
        }
    }

    public Map<String, Object> getData() {
        lock.readLock().lock();
        try {
            return deepCopy(data);
        } finally {
            lock.readLock().unlock();
        }
    }

    public void setData(Map<String, Object> newData) {
        if (newData == null) {
            throw new IllegalArgumentException("Data cannot be null");
        }

        validateConfiguration(newData);

        lock.writeLock().lock();
        try {
            data.clear();
            data.putAll(newData);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void clear() {
        lock.writeLock().lock();
        try {
            data.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Deep merge with conflict resolution
     */
    public void merge(Config other) {
        if (other != null) {
            merge(other.getData());
        }
    }

    @SuppressWarnings("unchecked")
    public void merge(Map<String, Object> otherData) {
        if (otherData == null) return;

        lock.writeLock().lock();
        try {
            for (Map.Entry<String, Object> entry : otherData.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();

                if (value instanceof Map && data.get(key) instanceof Map) {
                    // Deep merge nested maps
                    Map<String, Object> existing = (Map<String, Object>) data.get(key);
                    Map<String, Object> incoming = (Map<String, Object>) value;
                    deepMerge(existing, incoming);
                } else {
                    data.put(key, value);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @SuppressWarnings("unchecked")
    private void deepMerge(Map<String, Object> target, Map<String, Object> source) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (value instanceof Map && target.get(key) instanceof Map) {
                deepMerge((Map<String, Object>) target.get(key), (Map<String, Object>) value);
            } else {
                target.put(key, value);
            }
        }
    }

    public String toYaml() {
        lock.readLock().lock();
        try {
            return yaml.dump(deepCopy(data));
        } finally {
            lock.readLock().unlock();
        }
    }

    @SuppressWarnings("unchecked")
    public void fromYaml(String yamlString) {
        if (yamlString == null || yamlString.trim().isEmpty()) {
            throw new IllegalArgumentException("YAML string cannot be null or empty");
        }

        try {
            Object loaded = yaml.load(yamlString);
            Map<String, Object> newData = (loaded instanceof Map) ?
                    (Map<String, Object>) loaded : new HashMap<>();

            validateConfiguration(newData);
            setData(newData);

        } catch (YAMLException e) {
            throw new ConfigException("Invalid YAML format", e);
        }
    }

    /**
     * Validation methods
     */
    private void validateConfiguration(Map<String, Object> configData) {
        // Check required keys
        for (String requiredKey : requiredKeys) {
            if (!hasKey(configData, requiredKey)) {
                throw new ConfigException("Required key missing: " + requiredKey);
            }
        }

        // Check type constraints
        for (Map.Entry<String, Class<?>> constraint : typeConstraints.entrySet()) {
            String key = constraint.getKey();
            Class<?> expectedType = constraint.getValue();
            Object value = getValueFromMap(configData, key);

            if (value != null && !expectedType.isInstance(value)) {
                throw new ConfigException("Type constraint violation for key '" + key +
                        "': expected " + expectedType.getSimpleName() + ", got " + value.getClass().getSimpleName());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private boolean hasKey(Map<String, Object> map, String key) {
        String[] parts = key.split("\\.");
        Object current = map;

        for (String part : parts) {
            if (!(current instanceof Map)) {
                return false;
            }
            current = ((Map<String, Object>) current).get(part);
            if (current == null) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private Object getValueFromMap(Map<String, Object> map, String key) {
        String[] parts = key.split("\\.");
        Object current = map;

        for (String part : parts) {
            if (!(current instanceof Map)) {
                return null;
            }
            current = ((Map<String, Object>) current).get(part);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private boolean isValidKey(String key) {
        return key != null && !key.trim().isEmpty() && !key.startsWith(".") && !key.endsWith(".");
    }

    private void validateConfigPath(String path) {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("Config file path cannot be null or empty");
        }

        if (path.contains("..")) {
            throw new IllegalArgumentException("Config file path cannot contain '..'");
        }
    }

    /**
     * Backup and recovery methods
     */
    private void createBackup(Path configPath) {
        // Skip backup for classpath resources
        if (configFile != null && configFile.startsWith("classpath:")) {
            LOGGER.info("Skipping backup for classpath resource: " + configFile);
            return;
        }
        try {
            Path backupPath = Paths.get(configPath.toString() + BACKUP_SUFFIX);
            Files.copy(configPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
            cleanupOldBackups(backupPath.getParent(), configPath.getFileName().toString());
        } catch (IOException e) {
            LOGGER.warning("Failed to create backup: " + e.getMessage());
        }
    }

    private void cleanupOldBackups(Path directory, String baseName) {
        // Implementation for cleaning up old backups (keep last N backups)
        // This is a simplified version - you might want more sophisticated cleanup
    }

    private boolean tryRecoverFromBackup(String filename) {
        Path backupPath = Paths.get(filename + BACKUP_SUFFIX);
        if (Files.exists(backupPath)) {
            try {
                LOGGER.info("Attempting recovery from backup: " + backupPath);
                Map<String, Object> backupData = loadFromFile(backupPath);
                data.clear();
                data.putAll(backupData);
                return true;
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Backup recovery failed", e);
            }
        }
        return false;
    }

    /**
     * Utility methods
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> deepCopy(Map<String, Object> original) {
        Map<String, Object> copy = new HashMap<>();
        for (Map.Entry<String, Object> entry : original.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map) {
                copy.put(entry.getKey(), deepCopy((Map<String, Object>) value));
            } else if (value instanceof List) {
                copy.put(entry.getKey(), new ArrayList<>((List<?>) value));
            } else {
                copy.put(entry.getKey(), value);
            }
        }
        return copy;
    }

    @Override
    public String toString() {
        lock.readLock().lock();
        try {
            return "Config{file=" + configFile + ", keys=" + data.keySet().size() + "}";
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Custom exception for configuration errors
     */
    public static class ConfigException extends RuntimeException {
        public ConfigException(String message) {
            super(message);
        }

        public ConfigException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

