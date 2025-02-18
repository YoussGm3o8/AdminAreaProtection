package adminarea.util;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.permissions.PermissionToggle;
import org.json.JSONObject;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ValidationUtils {
    private static final Pattern AREA_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{3,32}$");
    private static final int MIN_PRIORITY = 0;
    private static final int MAX_PRIORITY = 100;
    
    // Toggle dependency map
    private static final Map<String, List<String>> TOGGLE_DEPENDENCIES = new HashMap<>() {{
        put("container", Arrays.asList("interact")); 
        put("itemFrame", Arrays.asList("interact"));
        put("armorStand", Arrays.asList("interact"));
        put("hopper", Arrays.asList("redstone"));
        put("dispenser", Arrays.asList("redstone"));
        put("piston", Arrays.asList("redstone")); 
        put("breeding", Arrays.asList("interact"));
    }};
    // Toggle conflict map
    private static final Map<String, List<String>> TOGGLE_CONFLICTS = new HashMap<>() {{
        put("pvp", Arrays.asList("damageEntities"));
        put("monsterSpawn", Arrays.asList("peaceful"));
        put("fire", Arrays.asList("fireProtection"));
    }};

    public static void validateAreaName(String name) {
        if (name == null || !AREA_NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid area name. Use only letters, numbers, hyphens, and underscores (3-32 characters).");
        }
    }

    public static void validateCoordinates(int min, int max, String axis) {
        if (min > max) {
            throw new IllegalArgumentException(axis + " coordinates are invalid: min (" + min + ") is greater than max (" + max + ")");
        }
    }

    public static void validatePriority(int priority) {
        if (priority < MIN_PRIORITY || priority > MAX_PRIORITY) {
            throw new IllegalArgumentException("Priority must be between " + MIN_PRIORITY + " and " + MAX_PRIORITY);
        }
    }

    public static void validateAreaSettings(JSONObject settings) {
        if (settings == null) {
            throw new IllegalArgumentException("Settings cannot be null");
        }

        // Validate each toggle setting
        for (String key : settings.keySet()) {
            if (!isValidToggle(key)) {
                throw new IllegalArgumentException("Invalid toggle setting: " + key);
            }
            
            // Check that value is boolean
            if (!settings.isNull(key) && !(settings.get(key) instanceof Boolean)) {
                throw new IllegalArgumentException("Toggle value must be boolean: " + key);
            }
        }

        // Validate toggle dependencies
        validateToggleDependencies(settings);
        
        // Validate toggle conflicts
        validateToggleConflicts(settings);
    }

    public static boolean isValidToggle(String toggleName) {
        // Check if toggle exists in PermissionToggle registry
        return PermissionToggle.getTogglesByCategory().values().stream()
            .flatMap(List::stream)
            .anyMatch(toggle -> toggle.getPermissionNode().equals(toggleName));
    }

    public static void validateToggleDependencies(JSONObject settings) {
        for (Map.Entry<String, List<String>> entry : TOGGLE_DEPENDENCIES.entrySet()) {
            String toggle = entry.getKey();
            if (settings.has(toggle) && settings.getBoolean(toggle)) {
                // If a toggle is enabled, check its dependencies
                for (String dependency : entry.getValue()) {
                    if (!settings.has(dependency) || !settings.getBoolean(dependency)) {
                        throw new IllegalArgumentException(
                            "Toggle '" + toggle + "' requires '" + dependency + "' to be enabled");
                    }
                }
            }
        }
    }

    public static void validateToggleConflicts(JSONObject settings) {
        for (Map.Entry<String, List<String>> entry : TOGGLE_CONFLICTS.entrySet()) {
            String toggle = entry.getKey();
            if (settings.has(toggle) && settings.getBoolean(toggle)) {
                // If a toggle is enabled, check for conflicts
                for (String conflict : entry.getValue()) {
                    if (settings.has(conflict) && settings.getBoolean(conflict)) {
                        throw new IllegalArgumentException(
                            "Toggle '" + toggle + "' conflicts with '" + conflict + "'");
                    }
                }
            }
        }
    }

    public static List<String> getDependencies(String toggle) {
        return TOGGLE_DEPENDENCIES.getOrDefault(toggle, Collections.emptyList());
    }

    public static List<String> getConflicts(String toggle) {
        return TOGGLE_CONFLICTS.getOrDefault(toggle, Collections.emptyList());
    }

    public static boolean hasDependencies(String toggle) {
        return TOGGLE_DEPENDENCIES.containsKey(toggle);
    }

    public static boolean hasConflicts(String toggle) {
        return TOGGLE_CONFLICTS.containsKey(toggle);
    }

    public static void validateToggleState(String toggle, boolean newState, JSONObject currentSettings) {
        // If enabling toggle
        if (newState) {
            // Check dependencies
            List<String> deps = TOGGLE_DEPENDENCIES.get(toggle);
            if (deps != null) {
                for (String dep : deps) {
                    if (!currentSettings.optBoolean(dep, false)) {
                        throw new IllegalArgumentException(
                            "Toggle '" + toggle + "' requires '" + dep + "' to be enabled");
                    }
                }
            }

            // Check conflicts  
            List<String> conflicts = TOGGLE_CONFLICTS.get(toggle);
            if (conflicts != null) {
                for (String conflict : conflicts) {
                    if (currentSettings.optBoolean(conflict, false)) {
                        throw new IllegalArgumentException(
                            "Toggle '" + toggle + "' conflicts with enabled toggle '" + conflict + "'");
                    }
                }
            }
        }
        // If disabling toggle
        else {
            // Check if any enabled toggles depend on this
            for (Map.Entry<String, List<String>> entry : TOGGLE_DEPENDENCIES.entrySet()) {
                if (entry.getValue().contains(toggle) &&
                    currentSettings.optBoolean(entry.getKey(), false)) {
                    throw new IllegalArgumentException(
                        "Cannot disable '" + toggle + "' because '" + entry.getKey() + "' depends on it");
                }
            }
        }
    }

    public static void validateString(String value, int minLength, int maxLength, String pattern, String fieldName) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " cannot be null or empty");
        }
        if (value.length() < minLength || value.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " length must be between " + minLength + " and " + maxLength);
        }
        if (!Pattern.compile(pattern).matcher(value).matches()) {
            throw new IllegalArgumentException(fieldName + " contains invalid characters");
        }
    }

    public static void validatePermission(String permission) {
        if (permission == null || permission.isEmpty()) {
            throw new IllegalArgumentException("Permission cannot be null or empty");
        }
        if (!Pattern.compile("^[a-z]+(\\.[a-z]+)*$").matcher(permission).matches()) {
            throw new IllegalArgumentException("Invalid permission format. Use lowercase letters and dots only");
        }
    }

    /**
     * Validates a map of permissions
     */
    public static void validatePermissionMap(Map<String, Boolean> permissions) {
        if (permissions == null) {
            throw new IllegalArgumentException("Permission map cannot be null");
        }
        
        for (String permission : permissions.keySet()) {
            validatePermission(permission);
        }
    }

    /**
     * Validates a category of permissions
     */
    public static void validateCategoryPermissions(PermissionToggle.Category category, 
            Map<String, Boolean> permissions) {
        validatePermissionMap(permissions);
        
        // Verify permissions belong to category
        List<PermissionToggle> categoryToggles = PermissionToggle.getTogglesByCategory().get(category);
        Set<String> validNodes = categoryToggles.stream()
            .map(PermissionToggle::getPermissionNode)
            .collect(Collectors.toSet());
            
        for (String permission : permissions.keySet()) {
            if (!validNodes.contains(permission)) {
                throw new IllegalArgumentException(
                    "Permission " + permission + " does not belong to category " + category);
            }
        }
    }

    public static void validateNumericRange(int value, int min, int max, String fieldName) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(fieldName + " must be between " + min + " and " + max);
        }
    }
}
