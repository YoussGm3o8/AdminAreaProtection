package adminarea.util;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.LoaderOptions;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * A YAML configuration loader that uses SnakeYAML v1 directly instead of relying on Nukkit's Config class,
 * which may use SnakeYAML v2 in some server versions.
 */
public class YamlConfig {
    private final File file;
    private Map<String, Object> data;

    /**
     * Creates a new YAML configuration from a file
     * @param file The file to load from
     */
    public YamlConfig(File file) {
        this.file = file;
        this.data = new LinkedHashMap<>();
        reload();
    }

    /**
     * Creates a new YAML configuration from a file path
     * @param filePath The file path to load from
     */
    public YamlConfig(String filePath) {
        this(new File(filePath));
    }

    /**
     * Reloads the configuration from the file
     * @return true if the reload was successful, false otherwise
     */
    public boolean reload() {
        try {
            if (!file.exists()) {
                return false;
            }

            try (FileInputStream fileInputStream = new FileInputStream(file)) {
                LoaderOptions options = new LoaderOptions();
                Yaml yaml = new Yaml(new SafeConstructor(options));
                Object loadedData = yaml.load(fileInputStream);

                if (loadedData instanceof Map) {
                    //noinspection unchecked
                    this.data = (Map<String, Object>) loadedData;
                    return true;
                } else if (loadedData == null) {
                    this.data = new LinkedHashMap<>();
                    return true;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    /**
     * Saves the configuration to the file
     * @return true if the save was successful, false otherwise
     */
    public boolean save() {
        try {
            if (!file.exists()) {
                file.getParentFile().mkdirs();
                file.createNewFile();
            }

            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setPrettyFlow(true);
            options.setIndent(2);

            LoaderOptions loaderOptions = new LoaderOptions();
            Yaml yaml = new Yaml(new SafeConstructor(loaderOptions), new Representer(options), options);
            try (FileWriter fileWriter = new FileWriter(file)) {
                yaml.dump(data, fileWriter);
                return true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    /**
     * Checks if a key exists in the configuration
     * @param key The key to check
     * @return true if the key exists, false otherwise
     */
    public boolean exists(String key) {
        return get(key) != null;
    }

    /**
     * Gets a value from the configuration
     * @param key The key to get
     * @return The value, or null if it doesn't exist
     */
    public Object get(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }

        String[] parts = key.split("\\.");
        Map<String, Object> currentMap = data;

        for (int i = 0; i < parts.length - 1; i++) {
            Object value = currentMap.get(parts[i]);
            if (!(value instanceof Map)) {
                return null;
            }
            //noinspection unchecked
            currentMap = (Map<String, Object>) value;
        }

        return currentMap.get(parts[parts.length - 1]);
    }

    /**
     * Gets a string from the configuration
     * @param key The key to get
     * @return The string value, or null if it doesn't exist or isn't a string
     */
    public String getString(String key) {
        Object value = get(key);
        return value != null ? String.valueOf(value) : null;
    }

    /**
     * Gets a string from the configuration with a default value
     * @param key The key to get
     * @param defaultValue The default value to return if the key doesn't exist
     * @return The string value, or the default value if it doesn't exist or isn't a string
     */
    public String getString(String key, String defaultValue) {
        String value = getString(key);
        return value != null ? value : defaultValue;
    }

    /**
     * Gets an integer from the configuration
     * @param key The key to get
     * @return The integer value, or 0 if it doesn't exist or isn't an integer
     */
    public int getInt(String key) {
        return getInt(key, 0);
    }

    /**
     * Gets an integer from the configuration with a default value
     * @param key The key to get
     * @param defaultValue The default value to return if the key doesn't exist
     * @return The integer value, or the default value if it doesn't exist or isn't an integer
     */
    public int getInt(String key, int defaultValue) {
        Object value = get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        } else if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    /**
     * Gets a boolean from the configuration
     * @param key The key to get
     * @return The boolean value, or false if it doesn't exist or isn't a boolean
     */
    public boolean getBoolean(String key) {
        return getBoolean(key, false);
    }

    /**
     * Gets a boolean from the configuration with a default value
     * @param key The key to get
     * @param defaultValue The default value to return if the key doesn't exist
     * @return The boolean value, or the default value if it doesn't exist or isn't a boolean
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        Object value = get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        } else if (value instanceof String) {
            String stringValue = (String) value;
            if (stringValue.equalsIgnoreCase("true")) {
                return true;
            } else if (stringValue.equalsIgnoreCase("false")) {
                return false;
            }
        } else if (value instanceof Number) {
            return ((Number) value).intValue() == 1;
        }
        return defaultValue;
    }

    /**
     * Gets a list from the configuration
     * @param key The key to get
     * @return The list, or an empty list if it doesn't exist or isn't a list
     */
    public List<Object> getList(String key) {
        Object value = get(key);
        if (value instanceof List) {
            //noinspection unchecked
            return (List<Object>) value;
        }
        return new ArrayList<>();
    }

    /**
     * Gets a list of strings from the configuration
     * @param key The key to get
     * @return The list of strings, or an empty list if it doesn't exist or isn't a list
     */
    public List<String> getStringList(String key) {
        List<Object> list = getList(key);
        List<String> result = new ArrayList<>();
        for (Object object : list) {
            if (object != null) {
                result.add(String.valueOf(object));
            }
        }
        return result;
    }

    /**
     * Gets a section of the configuration
     * @param key The key to get
     * @return The section, or an empty map if it doesn't exist or isn't a section
     */
    public Map<String, Object> getSection(String key) {
        Object value = get(key);
        if (value instanceof Map) {
            //noinspection unchecked
            return (Map<String, Object>) value;
        }
        return new LinkedHashMap<>();
    }

    /**
     * Gets all the keys in a section
     * @param section The section to get keys from (empty for root)
     * @return The set of keys in the section
     */
    public Set<String> getKeys(String section) {
        if (section == null || section.isEmpty()) {
            return data.keySet();
        }
        return getSection(section).keySet();
    }

    /**
     * Gets the root section of the configuration
     * @return The root section
     */
    public Map<String, Object> getRootSection() {
        return data;
    }

    /**
     * Sets a value in the configuration
     * @param key The key to set
     * @param value The value to set
     */
    public void set(String key, Object value) {
        if (key == null || key.isEmpty()) {
            return;
        }

        String[] parts = key.split("\\.");
        Map<String, Object> currentMap = data;

        for (int i = 0; i < parts.length - 1; i++) {
            Object currentValue = currentMap.get(parts[i]);
            if (!(currentValue instanceof Map)) {
                currentValue = new LinkedHashMap<String, Object>();
                currentMap.put(parts[i], currentValue);
            }
            //noinspection unchecked
            currentMap = (Map<String, Object>) currentValue;
        }

        if (value == null) {
            currentMap.remove(parts[parts.length - 1]);
        } else {
            currentMap.put(parts[parts.length - 1], value);
        }
    }

    /**
     * Removes a key from the configuration
     * @param key The key to remove
     */
    public void remove(String key) {
        set(key, null);
    }

    /**
     * Checks if the file exists
     * @return true if the file exists, false otherwise
     */
    public boolean exists() {
        return file.exists();
    }
    
    /**
     * Gets the file
     * @return The file
     */
    public File getFile() {
        return file;
    }
} 