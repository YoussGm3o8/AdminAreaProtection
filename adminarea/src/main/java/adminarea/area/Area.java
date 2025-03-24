package adminarea.area;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.permissions.PermissionToggle;
import adminarea.exception.DatabaseException;
import adminarea.permissions.PermissionOverrideManager;
import adminarea.permissions.PermissionChecker;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import cn.nukkit.math.Vector3;
import cn.nukkit.math.SimpleAxisAlignedBB;
import cn.nukkit.level.Position;
import org.json.JSONObject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

public final class Area {
    private final AreaDTO dto;
    private final Map<String, Boolean> effectivePermissionCache;
    private final AreaPermissionHandler permissionHandler;
    private final String name;
    private final String world;
    private final Vector3 pos1;
    private final Vector3 pos2;
    private final int priority;
    private final Map<String, Object> toggleStates;
    private final SimpleAxisAlignedBB boundingBox;
    private final Cache<String, Boolean> containsCache;
    // Increased cache size for better hit rate
    private static final int CONTAINS_CACHE_SIZE = 1000;
    private final AdminAreaProtectionPlugin plugin;
    
    // Common string constants to avoid repeated construction
    private static final String GUI_PERMISSIONS_PREFIX = "gui.permissions.toggles.";
    
    private Map<String, Map<String, Boolean>> groupPermissions;
    private Map<String, Map<String, Boolean>> trackPermissions;
    private Map<String, Map<String, Boolean>> playerPermissions;
    private Map<String, Map<String, Boolean>> cachedPlayerPermissions;
    
    // Cache for toggle state lookups
    private final Cache<String, Boolean> toggleStateCache;
    private static final int TOGGLE_CACHE_SIZE = 200;
    
    // Store potion effects
    private final JSONObject potionEffects;
    
    // Thread-local set to track areas being saved to prevent recursion
    private static final ThreadLocal<Set<String>> savingAreas = ThreadLocal.withInitial(() -> new HashSet<>());
    
    // ThreadLocal for tracking permissions operations to prevent area recreation
    private static final ThreadLocal<Set<String>> permissionOperations = ThreadLocal.withInitial(HashSet::new);
    
    Area(AreaDTO dto) {
        this.dto = dto;
        this.plugin = AdminAreaProtectionPlugin.getInstance();
        this.effectivePermissionCache = new ConcurrentHashMap<>(32, 0.75f, 2);
        this.permissionHandler = new AreaPermissionHandler(dto.groupPermissions(), dto.inheritedPermissions());
        this.name = dto.name();
        this.world = dto.world();
        
        // Get positions from bounds
        AreaDTO.Bounds bounds = dto.bounds();
        this.pos1 = new Vector3(bounds.xMin(), bounds.yMin(), bounds.zMin());
        this.pos2 = new Vector3(bounds.xMax(), bounds.yMax(), bounds.zMax());
        this.priority = dto.priority();
        
        // Convert toggle states
        this.toggleStates = new ConcurrentHashMap<>(32, 0.75f, 1);
        JSONObject toggles = dto.toggleStates();
        for (String key : toggles.keySet()) {
            try {
                // Normalize key during initialization
                String normalizedKey = normalizeToggleKey(key);
                
                // Handle strength settings (which are integers) differently from toggle settings (which are booleans)
                if (key.endsWith("Strength") || normalizedKey.endsWith("Strength")) {
                    // This is a strength setting, store it as an integer
                    this.toggleStates.put(normalizedKey, toggles.optInt(key, 0));
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Loaded strength setting: " + normalizedKey + " = " + toggles.optInt(key, 0));
                    }
                } else {
                    // This is a regular toggle setting, store it as a boolean
                    this.toggleStates.put(normalizedKey, toggles.optBoolean(key, false));
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Loaded toggle setting: " + normalizedKey + " = " + toggles.optBoolean(key, false));
                    }
                }
            } catch (Exception e) {
                if (plugin.isDebugMode()) {
                    plugin.debug("Error loading toggle: " + key + " - " + e.getMessage());
                }
                // Use a safe default if there's an error
                String normalizedKey = normalizeToggleKey(key);
                if (key.endsWith("Strength") || normalizedKey.endsWith("Strength")) {
                    this.toggleStates.put(normalizedKey, 0);
                } else {
                    this.toggleStates.put(normalizedKey, false);
                }
            }
        }
        
        // Load potion effects
        this.potionEffects = dto.potionEffects() != null ? dto.potionEffects() : new JSONObject();
        
        if (plugin.isDebugMode()) {
            plugin.debug("Loaded potion effects for area " + name + ": " + this.potionEffects.toString());
        }
        
        // Create bounding box for faster contains checks
        this.boundingBox = new SimpleAxisAlignedBB(
            bounds.xMin(), bounds.yMin(), bounds.zMin(),
            bounds.xMax() + 1, bounds.yMax() + 1, bounds.zMax() + 1
        );
        
        // Initialize contains cache with longer expiry for stable positions
        this.containsCache = Caffeine.newBuilder()
            .maximumSize(CONTAINS_CACHE_SIZE)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();
            
        // Initialize toggle state cache
        this.toggleStateCache = Caffeine.newBuilder()
            .maximumSize(TOGGLE_CACHE_SIZE)
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .build();

        // Initialize permission maps from DTO
        this.groupPermissions = new ConcurrentHashMap<>(dto.groupPermissions());
        this.trackPermissions = new ConcurrentHashMap<>(dto.trackPermissions());
        this.playerPermissions = new ConcurrentHashMap<>(dto.playerPermissions());

        if (plugin.isDebugMode()) {
            plugin.debug("Created area " + name + ":");
            plugin.debug("  Player permissions from DTO: " + dto.playerPermissions());
            plugin.debug("  Player permissions in area: " + playerPermissions);
            plugin.debug("  Group permissions in area: " + groupPermissions);
            plugin.debug("  Track permissions in area: " + trackPermissions);
        }
    }

    public static AreaBuilder builder() {
        return new AreaBuilder();
    }

    /**
     * Fast check to see if coordinates are inside this area
     * Uses simplified bounds check for better performance
     */
    public boolean isInside(String world, double x, double y, double z) {
        // Fast world name check first
        if (!this.world.equals(world)) return false;
        
        // Fast path for global areas - avoid unnecessary bounds checks
        if (isGlobal()) {
            return true; // Global areas contain all points in their world
        }
        
        // Use a smaller epsilon for more precise boundary detection
        final double EPSILON = 0.0001;
        AreaDTO.Bounds bounds = dto.bounds();
        
        // Add a small buffer to ensure points very close to the boundary
        // are still considered inside the area
        boolean isInside = x >= (bounds.xMin() - EPSILON) && x <= (bounds.xMax() + EPSILON) &&
                         y >= (bounds.yMin() - EPSILON) && y <= (bounds.yMax() + EPSILON) &&
                         z >= (bounds.zMin() - EPSILON) && z <= (bounds.zMax() + EPSILON);
        
        // Check if point is near a boundary (for debugging)
        boolean isEdgeCase = Math.abs(x - bounds.xMin()) < EPSILON || 
                          Math.abs(x - bounds.xMax()) < EPSILON ||
                          Math.abs(z - bounds.zMin()) < EPSILON ||
                          Math.abs(z - bounds.zMax()) < EPSILON;
        
        // Log detailed information for edge cases
        if (isEdgeCase && plugin.isDebugMode()) {
            plugin.debug("EDGE CASE boundary check for area " + name + ":");
            plugin.debug("  Position: (" + x + ", " + y + ", " + z + ")");
            plugin.debug("  Bounds: (" + bounds.xMin() + "-" + bounds.xMax() + ", " + 
                                     bounds.yMin() + "-" + bounds.yMax() + ", " + 
                                     bounds.zMin() + "-" + bounds.zMax() + ")");
            plugin.debug("  Result: " + isInside);
        }
        
        return isInside;
    }

    /**
     * Checks if this is a global area (covers an entire world)
     * Global areas have extreme bounds that cover the entire world
     * 
     * @return true if this is a global area
     */
    public boolean isGlobal() {
        AreaDTO.Bounds bounds = dto.bounds();
        // Global areas have extreme bounds
        return bounds.xMin() <= -29000000 && bounds.xMax() >= 29000000 &&
               bounds.zMin() <= -29000000 && bounds.zMax() >= 29000000;
    }

    /**
     * Get effective permission for a group
     * Uses caching for better performance
     */
    public boolean getEffectivePermission(String group, String permission) {
        if (group == null || permission == null) return false;
        
        String cacheKey = group + ":" + permission;
        return effectivePermissionCache.computeIfAbsent(cacheKey,
            k -> permissionHandler.calculateEffectivePermission(group, permission));
    }

    /**
     * Normalizes a toggle key to ensure consistent format
     */
    private String normalizeToggleKey(String key) {
        if (key == null || key.isEmpty()) return GUI_PERMISSIONS_PREFIX + "default";
        
        // Already has prefix
        if (key.startsWith(GUI_PERMISSIONS_PREFIX)) {
            return key;
        }
        
        // Simple permission without dots
        if (!key.contains(".")) {
            return GUI_PERMISSIONS_PREFIX + key;
        }
        
        // Return as is for special permission types
        return key;
    }

    /**
     * Sets a toggle state with proper key normalization and immediate database update
     */
    public void setToggleState(String permission, boolean state) {
        String normalizedPermission = normalizeToggleKey(permission);
        
        // Only add to toggle states if it's a toggle permission
        if (normalizedPermission.startsWith(GUI_PERMISSIONS_PREFIX)) {
            // Check if the value is actually changing
            boolean currentState = getToggleState(normalizedPermission);
            if (currentState == state) {
                // No change needed
                if (plugin.isDebugMode()) {
                    plugin.debug("Toggle state for " + normalizedPermission + " already set to " + state + " - no change needed");
                }
                return;
            }
            
            if (plugin.isDebugMode()) {
                plugin.debug("Changing toggle " + normalizedPermission + " from " + currentState + " to " + state);
            }
            
            // Update cache and storage
            toggleStates.put(normalizedPermission, state);
            toggleStateCache.invalidateAll(); // Clear the entire toggle state cache
            
            // Invalidate all protection caches to ensure changes take effect immediately
            if (plugin.getListenerManager() != null && 
                plugin.getListenerManager().getProtectionListener() != null) {
                plugin.getListenerManager().getProtectionListener().cleanup();
                
                if (plugin.getListenerManager().getEnvironmentListener() != null) {
                    plugin.getListenerManager().getEnvironmentListener().invalidateCache();
                }
            }
            
            // Special handling for critical toggles
            if (normalizedPermission.equals("gui.permissions.toggles.allowBlockPlace") || 
                normalizedPermission.equals("gui.permissions.toggles.allowBlockBreak") ||
                normalizedPermission.equals("gui.permissions.toggles.allowInteract")) {
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Critical toggle changed - immediately saving to database: " + normalizedPermission + " = " + state);
                }
                
                // Save toggle states to database immediately
                try {
                    boolean success = saveToggleStates();
                    
                    if (plugin.isDebugMode()) {
                        if (success) {
                            plugin.debug("  Successfully saved critical toggle to database");
                        } else {
                            plugin.debug("  Failed to save critical toggle to database - using retry mechanism");
                        }
                    }
                    
                    // If save failed, try updating just this specific toggle
                    if (!success) {
                        try {
                            JSONObject singleToggle = new JSONObject();
                            singleToggle.put(normalizedPermission, state);
                            plugin.getDatabaseManager().updateAreaToggleState(name, normalizedPermission, state);
                            
                            if (plugin.isDebugMode()) {
                                plugin.debug("  Updated critical toggle directly in database as fallback");
                            }
                        } catch (Exception singleEx) {
                            plugin.getLogger().error("Failed single toggle update as fallback", singleEx);
                        }
                    }
                    
                    // Force reload the area to ensure toggle state is consistent
                    try {
                        Area refreshed = plugin.getDatabaseManager().loadArea(name);
                        if (refreshed != null) {
                            boolean refreshedState = refreshed.getToggleState(normalizedPermission);
                            
                            if (plugin.isDebugMode()) {
                                plugin.debug("  Verified toggle state after reload: " + normalizedPermission + " = " + refreshedState);
                            }
                            
                            if (refreshedState != state) {
                                plugin.getLogger().error("Toggle state mismatch after reload - forcing update");
                                // Force update directly in database
                                plugin.getDatabaseManager().updateAreaToggleState(name, normalizedPermission, state);
                            }
                        }
                    } catch (Exception refreshEx) {
                        plugin.getLogger().error("Failed to refresh area after toggle update", refreshEx);
                    }
                } catch (Exception e) {
                    plugin.getLogger().error("Failed to save critical toggle state to database", e);
                }
            }
        }
    }

    /**
     * Sets a toggle state with proper key normalization for integer values
     */
    public void setToggleState(String permission, int value) {
        String normalizedPermission = normalizeToggleKey(permission);
        
        // Update cache and storage
        toggleStates.put(normalizedPermission, value);
        toggleStateCache.invalidateAll();
        
        // We don't need to update settings in DTO since it's not stored in the database
        // Just keep the toggle states updated in memory
        
        // Instead of updating the database directly for each toggle, just update memory
        // Individual database updates will be performed in bulk when the area is saved
        
        // Invalidate protection cache in ProtectionListener
        if (plugin.getListenerManager() != null && 
            plugin.getListenerManager().getProtectionListener() != null) {
            plugin.getListenerManager().getProtectionListener().cleanup();
            
            if (plugin.getListenerManager().getEnvironmentListener() != null) {
                plugin.getListenerManager().getEnvironmentListener().invalidateCache();
            }
        }
        
        if (plugin.isDebugMode()) {
            plugin.debug("Setting integer toggle state for " + normalizedPermission + " to " + value + " in area " + name);
        }
    }

    /**
     * Save toggle states to database in a single batch operation
     * Call this after multiple toggle states have been set
     * @return True if saved successfully, false otherwise
     */
    public boolean saveToggleStates() {
        // Prevent recursion during toggle state saving
        Set<String> inProgress = savingAreas.get();
        if (inProgress.contains(name)) {
            // We're already saving this area in the current call stack
            if (plugin.isDebugMode()) {
                plugin.debug("Preventing recursive save for area " + name);
            }
            return true; // Return success to avoid disrupting the parent operation
        }
        inProgress.add(name);
        
        try {
            // Skip database update if we're in a high-frequency context
            if (plugin.getRecentSaveTracker().isHighFrequencyContext()) {
                return true;
            }
            
            // Rate limit database updates
            long currentTime = System.currentTimeMillis();
            if (plugin.getRecentSaveTracker().wasRecentlySaved(name, currentTime)) {
                if (plugin.isDebugMode()) {
                    plugin.debug("Skipping save for area " + name + " due to rate limiting");
                }
                return true;
            }
            
            // Create a copy of the toggle states to send to the database
            JSONObject toggleStatesJson = new JSONObject(toggleStates);
            
            // Only log if we're in debug mode and not in a high-frequency context
            boolean shouldLog = plugin.isDebugMode() && !plugin.getRecentSaveTracker().isHighFrequencyContext();
            
            if (shouldLog) {
                plugin.debug("Saving toggle states to database for area " + name);
            }
            
            // Update toggle states in database
            try {
                plugin.getDatabaseManager().updateAllAreaToggleStates(name, toggleStatesJson);
                
                // Mark as saved for rate limiting
                plugin.getRecentSaveTracker().markSaved(name, currentTime);
                
                if (shouldLog) {
                    plugin.debug("  Successfully updated toggle states in the database");
                }
                
                return true;
            } catch (Exception e) {
                plugin.getLogger().error("Failed to update toggle states for area " + name, e);
                return false;
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error saving toggle states for area " + name, e);
            return false;
        } finally {
            // Clean up thread-local to prevent memory leaks
            inProgress.remove(name);
        }
    }

    /**
     * Gets a toggle state with proper key normalization and caching
     */
    public boolean getToggleState(String permission) {
        String normalizedPermission = normalizeToggleKey(permission);
        
        // Check cache first
        Boolean cachedState = toggleStateCache.getIfPresent(normalizedPermission);
        if (cachedState != null) {
            return cachedState;
        }
        
        try {
            boolean state;
            
            // Check if this is a potion effect permission
            if (normalizedPermission.startsWith("gui.permissions.toggles.allowPotion") || 
                normalizedPermission.startsWith("allowPotion")) {
                
                // For potion effects, don't use toggleStates but check if there's a strength value
                // Get the effect name - either with or without the prefix
                String effectName;
                if (normalizedPermission.startsWith("gui.permissions.toggles.")) {
                    effectName = normalizedPermission.replace("gui.permissions.toggles.", "");
                } else {
                    effectName = normalizedPermission;
                }
                
                // Get the strength value
                int strength = getPotionEffectStrength(effectName);
                
                // If strength > 0, consider it enabled (true)
                state = strength > 0;
                
                // if (plugin.isDebugMode()) {
                //     plugin.debug("[Area] Potion effect " + effectName + " has strength " + strength + ", state: " + state);
                // }
                
                // Cache the result
                toggleStateCache.put(normalizedPermission, state);
                return state;
            }
            
            // Not a potion effect, continue with normal toggle state handling
            Object value = toggleStates.get(normalizedPermission);
            
            if (value == null) {
                String permNode = normalizedPermission.replace(GUI_PERMISSIONS_PREFIX, "");
                PermissionToggle toggle = PermissionToggle.getToggle(permNode);
                if (toggle != null) {
                    state = toggle.getDefaultValue();
                    if (plugin.isDebugMode()) {
                        plugin.debug("[Area] Using default toggle state for " + permNode + ": " + state);
                    }
                } else {
                    state = true; // Default to true if not found
                    if (plugin.isDebugMode()) {
                        plugin.debug("[Area] No toggle found for " + permNode + ", using default state: " + state);
                    }
                }
            } else if (value instanceof Boolean) {
                state = (Boolean) value;
            } else if (value instanceof Integer) {
                state = (Integer) value > 0;
            } else {
                state = true; // Default to true for unknown types
            }
            
            // Cache the result
            toggleStateCache.put(normalizedPermission, state);
            return state;
        } catch (Exception e) {
            plugin.getLogger().error("Error getting toggle state: " + e.getMessage(), e);
            return true; // Default to true on error (least restrictive)
        }
    }

    /**
     * Gets the potion effect strength for the specified effect
     * 
     * @param effectName The name of the potion effect (e.g., "allowPotionSpeed")
     * @return The strength value (0-10), or 0 if not set or disabled
     */
    public int getPotionEffectStrength(String effectName) {
        // Normalization issues can occur here
        if (plugin.isDebugMode() && effectName.contains("Speed")) {
            // Only log Speed effect for now as it's the one being tested
            plugin.debug("[Area] Looking up potion effect strength for: " + effectName);
        }
        
        // Normalize permission name to ensure consistent format
        String normalizedKey = normalizeToggleKey(effectName);
        
        // Remove prefix for direct lookup in potionEffects
        String effectKey = normalizedKey;
        if (effectKey.startsWith(GUI_PERMISSIONS_PREFIX)) {
            effectKey = effectKey.substring(GUI_PERMISSIONS_PREFIX.length());
        }
        
        // Check if the effect exists in potionEffects
        if (potionEffects.has(effectKey)) {
            int strength = potionEffects.optInt(effectKey, 0);
            if (plugin.isDebugMode() && strength > 0 && effectKey.contains("Speed")) {
                // Only log non-zero values for Speed to reduce spam
                plugin.debug("[Area] Found in potionEffects: " + effectKey + " = " + strength);
            }
            return strength;
        }
        
        // Some effects might be stored with just the name part (e.g., "Speed" instead of "allowPotionSpeed")
        if (effectKey.startsWith("allowPotion")) {
            String simpleName = effectKey.substring("allowPotion".length());
            if (potionEffects.has(simpleName)) {
                int strength = potionEffects.optInt(simpleName, 0);
                if (plugin.isDebugMode() && strength > 0 && simpleName.contains("Speed")) {
                    // Only log non-zero values for Speed to reduce spam
                    plugin.debug("[Area] Found simplified key: " + simpleName + " = " + strength);
                }
                return strength;
            }
        }
        
        // Fall back to checking toggleStates with "Strength" suffix
        String strengthKey = normalizedKey.endsWith("Strength") ? 
            normalizedKey : normalizedKey + "Strength";
            
        Object value = toggleStates.get(strengthKey);
        if (value instanceof Integer) {
            int strength = (Integer) value;
            if (plugin.isDebugMode() && strength > 0 && strengthKey.contains("Speed")) {
                // Only log non-zero values for Speed to reduce spam
                plugin.debug("[Area] Found in toggleStates: " + strengthKey + " = " + strength);
            }
            return strength;
        }
        
        // Only log failures for Speed effect to reduce spam
        if (plugin.isDebugMode() && effectName.contains("Speed")) {
            plugin.debug("[Area] No strength found for: " + effectName);
        }
        return 0;
    }
    
    /**
     * Sets the strength of a potion effect for this area
     * 
     * @param effectName The name of the potion effect (e.g., "allowPotionSpeed")
     * @param strength The strength value (0-10)
     */
    public void setPotionEffectStrength(String effectName, int strength) {
        // Validation
        if (effectName == null || effectName.isEmpty()) {
            return;
        }
        
        // Normalize the key (remove the prefix if present)
        String normalizedKey = effectName;
        if (effectName.startsWith("gui.permissions.toggles.")) {
            normalizedKey = effectName.replace("gui.permissions.toggles.", "");
        }
        
        // Validate the strength (0-10)
        int validStrength = Math.max(0, Math.min(10, strength));
        
        // Store in potionEffects for the new way
        potionEffects.put(normalizedKey, validStrength);
        
        // Clear the toggle state cache for this effect 
        // so that getToggleState will recompute based on new strength
        String toggleKey = "gui.permissions.toggles." + normalizedKey;
        toggleStateCache.invalidate(toggleKey);
        
        // If it's zero, remove it to keep the data clean
        if (validStrength == 0) {
            potionEffects.remove(normalizedKey);
        }
        
        // if (plugin.isDebugMode()) {
        //     plugin.debug("Set potion effect " + normalizedKey + " strength to " + validStrength + 
        //         " (requested: " + strength + ")");
        // }
    }
    
    /**
     * Gets all potion effects and their strengths for this area
     * 
     * @return A map of potion effect names to their strength values
     */
    public Map<String, Integer> getAllPotionEffects() {
        Map<String, Integer> effects = new HashMap<>();
        
        // Add all effects from potionEffects JSONObject
        for (String key : potionEffects.keySet()) {
            effects.put(key, potionEffects.optInt(key, 0));
        }
        
        // Also check toggleStates for any strength values
        for (Map.Entry<String, Object> entry : toggleStates.entrySet()) {
            String key = entry.getKey();
            if (key.endsWith("Strength") && entry.getValue() instanceof Integer) {
                effects.put(key, (Integer) entry.getValue());
            }
        }
        
        return effects;
    }

    public AreaDTO toDTO() {
        // Create a copy of the toggle states map for the DTO
        JSONObject toggleStatesJson = new JSONObject(toggleStates);
        
        // Create a copy of the settings for the DTO
        JSONObject updatedSettings = new JSONObject(dto.settings());
        
        // Always synchronize critical toggle states
        String[] criticalToggles = {
            "gui.permissions.toggles.allowBlockPlace",
            "gui.permissions.toggles.allowBlockBreak",
            "gui.permissions.toggles.allowInteract"
        };
        
        for (String toggle : criticalToggles) {
            // Get the current value from memory
            boolean memoryValue = getToggleState(toggle);
            
            // Apply the memory value to both JSON objects
            toggleStatesJson.put(toggle, memoryValue);
            
            // Also update the settings with the same value (without the prefix)
            String shortKey = toggle.replace("gui.permissions.toggles.", "");
            updatedSettings.put(shortKey, memoryValue);
            
            if (plugin.isDebugMode()) {
                plugin.debug("Ensuring critical toggle '" + toggle + "' is consistent: " + memoryValue);
            }
        }
        
        // Use cached permissions instead of fetching from database to avoid recursive calls
        Map<String, Map<String, Boolean>> latestGroupPerms = this.groupPermissions;
        Map<String, Map<String, Boolean>> latestTrackPerms = this.trackPermissions;
        Map<String, Map<String, Boolean>> latestPlayerPerms;
        
        try {
            if (plugin.isDebugMode()) {
                plugin.debug("Getting latest player permissions from database for DTO creation");
            }
            latestPlayerPerms = plugin.getPermissionOverrideManager().getAllPlayerPermissions(name);
            
            if (plugin.isDebugMode()) {
                plugin.debug("Retrieved player permissions for area " + name + ": " + 
                           latestPlayerPerms.size() + " players");
                for (Map.Entry<String, Map<String, Boolean>> entry : latestPlayerPerms.entrySet()) {
                    plugin.debug("  Player " + entry.getKey() + ": " + entry.getValue().size() + " permissions");
                }
            }
            
            // Update our cached permissions while we're at it
            if (latestPlayerPerms != null && !latestPlayerPerms.isEmpty()) {
                this.playerPermissions = new HashMap<>(latestPlayerPerms);
            } else if (this.playerPermissions != null && !this.playerPermissions.isEmpty()) {
                // Keep existing if database returned empty but we have data
                latestPlayerPerms = new HashMap<>(this.playerPermissions);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Database returned empty permissions but we have " + 
                               this.playerPermissions.size() + " cached player permissions");
                }
            }
        } catch (Exception e) {
            plugin.getLogger().error("Failed to get player permissions from database", e);
            // Fall back to cached permissions
            latestPlayerPerms = this.playerPermissions != null ? 
                new HashMap<>(this.playerPermissions) : new HashMap<>();
        }
        
        // Create a new DTO with the updated settings and toggle states
        return new AreaDTO(
            name,
            world,
            dto.bounds(),
            priority,
            dto.showTitle(),
            updatedSettings,
            latestGroupPerms,
            new HashMap<>(),   // inheritedPermissions - no longer needed
            toggleStatesJson,
            dto.defaultToggleStates(),
            dto.inheritedToggleStates(),
            dto.permissions(),
            dto.enterMessage(),
            dto.leaveMessage(),
            dto.enterTitle(),
            dto.leaveTitle(),
            latestTrackPerms,
            latestPlayerPerms,
            potionEffects
        );
    }

    /**
     * Call this before saving the area to ensure all permission data is consistent
     */
    public boolean syncPermissions() {
        // Prevent recursion during permission syncing
        Set<String> inProgress = savingAreas.get();
        if (inProgress.contains(name + "_perm")) {
            // We're already syncing permissions for this area in the current call stack
            if (plugin.isDebugMode()) {
                plugin.debug("Preventing recursive permission sync for area " + name);
            }
            return true; // Return success to avoid disrupting the parent operation
        }
        inProgress.add(name + "_perm");
        
        try {
            // Skip permission sync if we're in a high-frequency context
            if (plugin.getRecentSaveTracker().isHighFrequencyContext()) {
                return true;
            }
            
            // Rate limit permission syncs
            long currentTime = System.currentTimeMillis();
            if (plugin.getRecentSaveTracker().wasRecentlySynced(name, currentTime)) {
                if (plugin.isDebugMode()) {
                    plugin.debug("Skipping permission sync for area " + name + " due to rate limiting");
                }
                return true;
            }
            
            // Only log if we're in debug mode and not in a high-frequency context
            boolean shouldLog = plugin.isDebugMode() && !plugin.getRecentSaveTracker().isHighFrequencyContext();
            
            if (shouldLog) {
                plugin.debug("Synchronizing permissions for area " + name);
            }
            
            try {
                // Use the new bidirectional synchronization method
                plugin.getPermissionOverrideManager().synchronizePermissions(
                    this, 
                    PermissionOverrideManager.SyncDirection.BIDIRECTIONAL
                );
                
                // Mark as synced for rate limiting
                plugin.getRecentSaveTracker().markSynced(name, currentTime);
                
                if (shouldLog) {
                    plugin.debug("  Successfully synchronized permissions bidirectionally for area " + name);
                }
                
                return true;
            } catch (Exception e) {
                plugin.getLogger().error("Failed to synchronize permissions for area " + name, e);
                return false;
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error syncing permissions for area " + name, e);
            return false;
        } finally {
            // Clean up thread-local to prevent memory leaks
            inProgress.remove(name + "_perm");
        }
    }

    // Simple getters that delegate to DTO
    public String getName() { return name; }
    public String getWorld() { return world; }
    public AreaDTO.Bounds getBounds() { return dto.bounds(); }
    public int getPriority() { return priority; }

    public boolean contains(Position pos) {
        if (!world.equals(pos.getLevel().getName())) {
            return false;
        }
        
        // For global areas, skip caching and detailed checks
        if (isGlobal()) {
            return true;
        }
        
        // Use exact coordinates for consistency with isInside method
        String cacheKey = String.format("%.3f:%.3f:%.3f", pos.getX(), pos.getY(), pos.getZ());
        Boolean cached = containsCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }
        
        // Quick check using bounding box
        if (!boundingBox.isVectorInside(pos)) {
            containsCache.put(cacheKey, false);
            return false;
        }
        
        // Use the same small epsilon for consistency with isInside method
        final double EPSILON = 0.0001;
        
        // Detailed check if within bounding box with small buffer for boundary cases
        boolean result = pos.getX() >= (Math.min(pos1.getX(), pos2.getX()) - EPSILON) &&
                        pos.getX() <= (Math.max(pos1.getX(), pos2.getX()) + EPSILON) &&
                        pos.getY() >= (Math.min(pos1.getY(), pos2.getY()) - EPSILON) &&
                        pos.getY() <= (Math.max(pos1.getY(), pos2.getY()) + EPSILON) &&
                        pos.getZ() >= (Math.min(pos1.getZ(), pos2.getZ()) - EPSILON) &&
                        pos.getZ() <= (Math.max(pos1.getZ(), pos2.getZ()) + EPSILON);
        
        containsCache.put(cacheKey, result);
        return result;
    }

    public void cleanup() {
        containsCache.invalidateAll();
        effectivePermissionCache.clear();
        // Clear other caches if memory pressure is high
        if (Runtime.getRuntime().freeMemory() < Runtime.getRuntime().maxMemory() * 0.2) {
            groupPermissions.clear();
            trackPermissions.clear();
            playerPermissions.clear();
        }
    }

    public void clearCaches() {
        if (plugin.isDebugMode()) {
            plugin.debug("Clearing caches for area " + name);
        }
        
        containsCache.invalidateAll();
        effectivePermissionCache.clear();
        toggleStateCache.invalidateAll();
        cachedPlayerPermissions = null;
        
        // Completely refresh toggle states from the DTO
        toggleStates.clear();
        JSONObject toggles = dto.toggleStates();
        for (String key : toggles.keySet()) {
            try {
                toggleStates.put(key, toggles.get(key));
            } catch (Exception e) {
                plugin.getLogger().warning("Error restoring toggle state for key: " + key);
            }
        }
        
        // Only log detailed potion effects info if there are any non-zero values
        if (plugin.isDebugMode()) {
            // Check if there are any non-zero potion effects
            boolean hasEffects = false;
            for (String key : potionEffects.keySet()) {
                if (potionEffects.optInt(key, 0) > 0) {
                    hasEffects = true;
                    break;
                }
            }
            
            if (hasEffects) {
                plugin.debug("Area " + name + " has potion effects: " + potionEffects.toString());
            }
        }

        // Reload permissions
        playerPermissions = null;
        groupPermissions = null;
        trackPermissions = null;
    }

    // Getters
    public Vector3 getPos1() { return pos1; }
    public Vector3 getPos2() { return pos2; }
    public Map<String, Object> getToggleStates() { return new HashMap<>(toggleStates); }
    public SimpleAxisAlignedBB getBoundingBox() { return boundingBox; }
    
    // For efficient area lookup
    public boolean overlaps(SimpleAxisAlignedBB other) {
        return this.boundingBox.intersectsWith(other);
    }
    
    public boolean overlaps(Area other) {
        return this.boundingBox.intersectsWith(other.boundingBox);
    }

    public Map<String, Boolean> getTrackPermissions(String trackName) {
        return plugin.getPermissionOverrideManager().getTrackPermissions(name, trackName);
    }

    /**
     * Gets the internal track permissions map directly without triggering database queries
     * This method is for internal use only to avoid recursive database calls
     * 
     * @return The internal track permissions map (not a defensive copy)
     */
    public Map<String, Map<String, Boolean>> getInternalTrackPermissions() {
        return this.trackPermissions;
    }

    public Map<String, Map<String, Boolean>> getTrackPermissions() {
        try {
            // Check if we have already cached permissions
            if (trackPermissions == null || trackPermissions.isEmpty()) {
                // Load from database if not cached
                trackPermissions = plugin.getPermissionOverrideManager().getAllTrackPermissions(name);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Loaded track permissions from database for area " + name + 
                               ": " + (trackPermissions != null ? trackPermissions.size() : "0") + " tracks");
                }
                
                // Initialize with empty map if null to avoid NPEs
                if (trackPermissions == null) {
                    trackPermissions = new HashMap<>();
                    if (plugin.isDebugMode()) {
                        plugin.debug("Initialized empty track permissions map after database load returned null");
                    }
                }
            } else if (plugin.isDebugMode()) {
                plugin.debug("Using cached track permissions for area " + name + 
                           ": " + trackPermissions.size() + " tracks");
            }
            
            // Return a defensive copy to prevent external modification
            // We will NOT try to verify and save back to the database here to prevent recursion
            return new HashMap<>(trackPermissions);
        } catch (Exception e) {
            plugin.getLogger().error("Error retrieving track permissions for area " + name, e);
            // Return an empty map rather than null if there's an error
            return new HashMap<>();
        }
    }

    /**
     * Get all player-specific permissions for this area
     * @return Map of player names to their permission maps
     */
    public Map<String, Map<String, Boolean>> getPlayerPermissions() {
        try {
            // Always fetch fresh data from PermissionOverrideManager
            Map<String, Map<String, Boolean>> freshPermissions = plugin.getPermissionOverrideManager().getAllPlayerPermissions(name);
            
            if (plugin.isDebugMode()) {
                plugin.debug("Retrieved player permissions for area " + name + ": " + 
                            freshPermissions.size() + " players");
            }
            
            // Update the cached map
            this.playerPermissions = new HashMap<>(freshPermissions);
            
            // Return a defensive copy
            return new HashMap<>(this.playerPermissions);
        } catch (Exception e) {
            plugin.getLogger().error("Error retrieving player permissions for area " + name, e);
            // Return current cache or empty map if null
            return playerPermissions != null ? new HashMap<>(playerPermissions) : new HashMap<>();
        }
    }
    
    /**
     * Get permissions for a specific player in this area
     */
    public Map<String, Boolean> getPlayerPermissions(String playerName) {
        if (playerName == null) return new HashMap<>();
        
        try {
            // Always get fresh data from PermissionOverrideManager
            Map<String, Boolean> playerPerms = plugin.getPermissionOverrideManager().getPlayerPermissions(name, playerName);
            
            if (plugin.isDebugMode()) {
                plugin.debug("Retrieved permissions for player " + playerName + " in area " + 
                            name + ": " + (playerPerms != null ? playerPerms.size() : "null") + " permissions");
            }
            
            // Update the cached permissions map
            if (playerPermissions != null && playerPerms != null) {
                playerPermissions.put(playerName, new HashMap<>(playerPerms));
            }
            
            return playerPerms != null ? new HashMap<>(playerPerms) : new HashMap<>();
        } catch (Exception e) {
            plugin.getLogger().error("Error retrieving permissions for player " + playerName, e);
            return new HashMap<>();
        }
    }

    public void setPlayerPermissions(String playerName, Map<String, Boolean> permissions) throws DatabaseException {
        if (plugin.isDebugMode()) {
            plugin.debug("Setting permissions for player " + playerName + " in area " + name);
            plugin.debug("  New permissions: " + permissions);
        }
        
        // This operation is for permissions only and should not trigger area recreation
        String permOpsKey = "permission_only_op:" + name + ":" + playerName;
        Set<String> processingSet = permissionOperations.get();
        boolean shouldRemove = false;
        
        if (!processingSet.contains(permOpsKey)) {
            processingSet.add(permOpsKey);
            shouldRemove = true;
        }
        
        try {
            // Directly save to PermissionOverrideManager
            plugin.getPermissionOverrideManager().setPlayerPermissions(name, playerName, permissions);
            
            // Update local cache
            if (playerPermissions != null) {
                playerPermissions.put(playerName, new HashMap<>(permissions));
            }
            if (cachedPlayerPermissions != null) {
                cachedPlayerPermissions.remove(playerName);
            }
            
            // Ensure the permission checker's cache is also invalidated
            PermissionChecker permissionChecker = plugin.getPermissionOverrideManager().getPermissionChecker();
            if (permissionChecker != null) {
                permissionChecker.invalidateCache(name);
                permissionChecker.invalidatePlayerCache(playerName);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("  Invalidated permission checker cache for area: " + name + " and player: " + playerName);
                }
            }
            
            if (plugin.isDebugMode()) {
                plugin.debug("  Saved player permissions to PermissionOverrideManager and updated local cache");
            }
        } finally {
            if (shouldRemove) {
                processingSet.remove(permOpsKey);
            }
        }
    }

    public void setPlayerPermission(String playerName, String permission, boolean value) {
        if (playerName == null || permission == null) return;
        
        try {
            // Get current permissions
            Map<String, Boolean> perms = getPlayerPermissions(playerName);
            if (perms == null) {
                perms = new HashMap<>();
            }
            
            // Update permission
            perms.put(permission, value);
            
            // Save updated permissions
            setPlayerPermissions(playerName, perms);
            
            // Prevent recursive synchronization
            String syncKey = name + "_sync_" + playerName;
            if (!savingAreas.get().contains(syncKey)) {
                savingAreas.get().add(syncKey);
                try {
                    // Ensure permissions are synchronized immediately
                    plugin.getPermissionOverrideManager().synchronizeFromArea(this);
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Set player permission for " + playerName + " in area " + name);
                        plugin.debug("  Permission: " + permission + " = " + value);
                        plugin.debug("  Updated permissions: " + perms);
                        plugin.debug("  Synchronized changes to database immediately");
                    }
                } finally {
                    savingAreas.get().remove(syncKey);
                }
            } else if (plugin.isDebugMode()) {
                plugin.debug("Prevented recursive synchronization for player " + playerName + " in area " + name);
            }
        } catch (DatabaseException e) {
            plugin.getLogger().error("Failed to update player permission", e);
        }
    }

    public boolean getPlayerPermission(String playerName, String permission) {
        if (playerName == null || permission == null) return false;
        
        // Get fresh permissions from PermissionOverrideManager
        Map<String, Boolean> perms = getPlayerPermissions(playerName);
        if (perms == null) return false;
        
        return perms.getOrDefault(permission, false);
    }

    public Map<String, Boolean> getGroupPermissions(String groupName) {
        if (groupName == null) return new HashMap<>();
        
        try {
            Map<String, Boolean> groupPerms = plugin.getPermissionOverrideManager().getGroupPermissions(name, groupName);
            
            if (plugin.isDebugMode()) {
                plugin.debug("Retrieved permissions for group " + groupName + " in area " + 
                            name + ": " + (groupPerms != null ? groupPerms.size() : "null") + " permissions");
            }
            
            return groupPerms != null ? new HashMap<>(groupPerms) : new HashMap<>();
        } catch (Exception e) {
            plugin.getLogger().error("Error retrieving permissions for group " + groupName, e);
            return new HashMap<>();
        }
    }

    public Map<String, Map<String, Boolean>> getGroupPermissions() {
        try {
            // Check if we have already cached permissions
            if (groupPermissions == null || groupPermissions.isEmpty()) {
                // Load from database if not cached
                groupPermissions = plugin.getPermissionOverrideManager().getAllGroupPermissions(name);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Loaded group permissions from database for area " + name + 
                               ": " + (groupPermissions != null ? groupPermissions.size() : "0") + " groups");
                }
                
                // Initialize with empty map if null to avoid NPEs
                if (groupPermissions == null) {
                    groupPermissions = new HashMap<>();
                    if (plugin.isDebugMode()) {
                        plugin.debug("Initialized empty group permissions map after database load returned null");
                    }
                }
            } else if (plugin.isDebugMode()) {
                plugin.debug("Using cached group permissions for area " + name + 
                           ": " + groupPermissions.size() + " groups");
            }
            
            // Make sure group permissions are saved to the database (belt and suspenders approach)
            if (groupPermissions != null && !groupPermissions.isEmpty()) {
                boolean needsSaving = false;
                
                // Check if permissions actually exist in the database
                try (Connection conn = plugin.getPermissionOverrideManager().getConnection();
                     PreparedStatement stmt = conn.prepareStatement(
                         "SELECT COUNT(*) FROM group_permissions WHERE area_name = ?")) {
                    
                    // First verify the table exists
                    boolean tableExists = false;
                    try (Statement checkStmt = conn.createStatement();
                         ResultSet tableRs = checkStmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='group_permissions'")) {
                        tableExists = tableRs.next();
                    }
                    
                    if (!tableExists) {
                        if (plugin.isDebugMode()) {
                            plugin.debug("  group_permissions table does not exist! Creating it now.");
                        }
                        try (Statement createStmt = conn.createStatement()) {
                            createStmt.execute("CREATE TABLE IF NOT EXISTS group_permissions (" +
                                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                                "area_name TEXT NOT NULL, " +
                                "group_name TEXT NOT NULL, " +
                                "permission TEXT NOT NULL, " +
                                "state INTEGER NOT NULL DEFAULT 1, " +
                                "UNIQUE(area_name, group_name, permission))");
                        }
                        needsSaving = true;
                    } else {
                        // Table exists, check if permissions exist for this area
                        stmt.setString(1, name);
                        try (ResultSet rs = stmt.executeQuery()) {
                            int count = rs.next() ? rs.getInt(1) : 0;
                            needsSaving = (count == 0);
                            
                            if (plugin.isDebugMode()) {
                                if (needsSaving) {
                                    plugin.debug("  No group permissions found in database for area " + name + 
                                               " but " + groupPermissions.size() + " groups in memory; will force save");
                                } else {
                                    plugin.debug("  Found " + count + " group permission entries in database for area " + name);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().error("Error checking group permissions in database", e);
                    // Assume we need to save if we can't verify
                    needsSaving = true;
                }
                
                // If no permissions in database, force save them
                if (needsSaving) {
                    int savedGroups = 0;
                    for (Map.Entry<String, Map<String, Boolean>> entry : groupPermissions.entrySet()) {
                        try {
                            // Use force=true to ensure the save happens
                            plugin.getPermissionOverrideManager().setGroupPermissions(
                                name, entry.getKey(), new HashMap<>(entry.getValue()), true);
                            savedGroups++;
                            
                            if (plugin.isDebugMode() && savedGroups % 5 == 0) {
                                plugin.debug("  Force saved permissions for " + savedGroups + " groups so far");
                            }
                        } catch (Exception e) {
                            plugin.getLogger().error("Error force saving group permissions during getGroupPermissions for group " + 
                                entry.getKey() + " in area " + name, e);
                        }
                    }
                    
                    if (plugin.isDebugMode() && savedGroups > 0) {
                        plugin.debug("  Auto-recovery: Force saved permissions for " + savedGroups + " groups to database");
                    }
                }
            }
            
            // Return a defensive copy to prevent external modification
            return new HashMap<>(groupPermissions);
        } catch (Exception e) {
            plugin.getLogger().error("Error retrieving group permissions for area " + name, e);
            // Return an empty map rather than null if there's an error
            return new HashMap<>();
        }
    }

    public void setGroupPermissions(String groupName, Map<String, Boolean> permissions) throws DatabaseException {
        setGroupPermissions(groupName, permissions, false);
    }
    
    /**
     * Sets group permissions for a specific group with option to force saving
     * 
     * @param groupName The name of the group
     * @param permissions The map of permissions to set
     * @param force Whether to force saving even if no changes are detected
     * @throws DatabaseException if there's an error saving to the database
     */
    public void setGroupPermissions(String groupName, Map<String, Boolean> permissions, boolean force) throws DatabaseException {
        if (plugin.isDebugMode()) {
            plugin.debug("Setting permissions for group " + groupName + " in area " + name + (force ? " (forced)" : ""));
            plugin.debug("  New permissions: " + permissions);
        }
        
        // This operation is for permissions only and should not trigger area recreation
        String permOpsKey = "permission_only_op:" + name + ":group:" + groupName;
        Set<String> processingSet = permissionOperations.get();
        boolean shouldRemove = false;
        
        if (!processingSet.contains(permOpsKey)) {
            processingSet.add(permOpsKey);
            shouldRemove = true;
        }
        
        try {
            // Directly save to PermissionOverrideManager
            plugin.getPermissionOverrideManager().setGroupPermissions(name, groupName, permissions, force);
            
            // Update local cache
            if (groupPermissions != null) {
                groupPermissions.put(groupName, new HashMap<>(permissions));
            }
            
            // Ensure permissions are synchronized immediately
            plugin.getPermissionOverrideManager().synchronizeFromArea(this);
        } finally {
            if (shouldRemove) {
                processingSet.remove(permOpsKey);
            }
        }
    }

    /**
     * Set permissions for a track
     */
    public void setTrackPermissions(String trackName, Map<String, Boolean> permissions) throws DatabaseException {
        if (trackName == null || permissions == null) {
            return;
        }
        
        // This operation is for permissions only and should not trigger area recreation
        String permOpsKey = "permission_only_op:" + name + ":track:" + trackName;
        Set<String> processingSet = permissionOperations.get();
        boolean shouldRemove = false;
        
        if (!processingSet.contains(permOpsKey)) {
            processingSet.add(permOpsKey);
            shouldRemove = true;
        }
        
        try {
            // Check if we're already in a synchronization operation to prevent recursion
            if (!processingSet.contains("sync_operation:" + name)) {
                // Directly save to PermissionOverrideManager with forced flag to ensure saving even if no changes detected
                plugin.getPermissionOverrideManager().setTrackPermissions(this, trackName, permissions, true);
            }
            
            // Update local cache
            if (trackPermissions != null) {
                Map<String, Boolean> trackPerms = trackPermissions.get(trackName);
                if (trackPerms == null) {
                    trackPerms = new HashMap<>();
                    trackPermissions.put(trackName, trackPerms);
                } else {
                    trackPerms.clear();
                }
                
                trackPerms.putAll(permissions);
            }
            
            // Clear effective permission cache
            effectivePermissionCache.clear();
            
            // Clear toggle state caches
            toggleStateCache.invalidateAll();
        } finally {
            if (shouldRemove) {
                processingSet.remove(permOpsKey);
            }
        }
    }

    /**
     * Get an integer setting from the area's settings
     * @param key The setting key
     * @param defaultValue The default value if setting doesn't exist or isn't an integer
     * @return The integer value of the setting
     */
    public int getSettingInt(String key, int defaultValue) {
        try {
            JSONObject settings = dto.settings();
            if (settings != null && settings.has(key)) {
                return settings.optInt(key, defaultValue);
            }
        } catch (Exception e) {
            if (plugin.isDebugMode()) {
                plugin.debug("Error getting integer setting " + key + ": " + e.getMessage());
            }
        }
        return defaultValue;
    }
    
    /**
     * Get a string setting from the area's settings
     * @param key The setting key
     * @param defaultValue The default value if setting doesn't exist
     * @return The string value of the setting
     */
    public String getSettingString(String key, String defaultValue) {
        try {
            JSONObject settings = dto.settings();
            if (settings != null && settings.has(key)) {
                return settings.optString(key, defaultValue);
            }
        } catch (Exception e) {
            if (plugin.isDebugMode()) {
                plugin.debug("Error getting string setting " + key + ": " + e.getMessage());
            }
        }
        return defaultValue;
    }
    
    /**
     * Get a boolean setting from the area's settings
     * @param key The setting key
     * @param defaultValue The default value if setting doesn't exist
     * @return The boolean value of the setting
     */
    public boolean getSettingBoolean(String key, boolean defaultValue) {
        try {
            JSONObject settings = dto.settings();
            if (settings != null && settings.has(key)) {
                return settings.optBoolean(key, defaultValue);
            }
        } catch (Exception e) {
            if (plugin.isDebugMode()) {
                plugin.debug("Error getting boolean setting " + key + ": " + e.getMessage());
            }
        }
        return defaultValue;
    }

    public void clearAllPermissions() throws DatabaseException {
        // Clear all permissions in PermissionOverrideManager
        plugin.getPermissionOverrideManager().deleteAreaPermissions(name);
        
        // Clear local caches
        groupPermissions.clear();
        trackPermissions.clear();
        playerPermissions.clear();
        cachedPlayerPermissions = null;
        
        if (plugin.isDebugMode()) {
            plugin.debug("Cleared all permissions for area " + name);
        }
    }

    /**
     * Normalizes all toggle states to ensure consistent prefixing
     * This ensures all toggle states are properly saved with the correct prefix
     */
    public void normalizeToggleStates() {
        if (plugin.isDebugMode()) {
            plugin.debug("Normalizing toggle states for area " + name);
            plugin.debug("  Before - Toggle states: " + toggleStates);
        }
        
        Map<String, Object> normalizedToggles = new HashMap<>();
        
        // Process each toggle state and ensure it has the correct prefix
        for (Map.Entry<String, Object> entry : toggleStates.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            
            // Skip null keys
            if (key == null) continue;
            
            // Normalize the key
            String normalizedKey = normalizeToggleKey(key);
            normalizedToggles.put(normalizedKey, value);
        }
        
        // Replace toggle states with normalized version
        toggleStates.clear();
        toggleStates.putAll(normalizedToggles);
        
        // Invalidate toggle state cache
        toggleStateCache.invalidateAll();
        
        if (plugin.isDebugMode()) {
            plugin.debug("  After - Toggle states: " + toggleStates);
        }
    }

    /**
     * Force clear all caches including the toggle state cache.
     * This should be called when experiencing permission issues or inconsistencies.
     */
    public void emergencyClearCaches() {
        if (plugin.isDebugMode()) {
            plugin.debug("EMERGENCY CACHE CLEAR for area " + name);
        }
        
        // Clear all caches
        toggleStateCache.invalidateAll();
        containsCache.invalidateAll();
        effectivePermissionCache.clear();
        
        // Force reload settings from database
        try {
            // Reload area from database
            Area freshArea = plugin.getDatabaseManager().loadArea(name);
            if (freshArea != null) {
                // Update toggle states
                this.toggleStates.clear();
                this.toggleStates.putAll(freshArea.getToggleStates());
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Reloaded toggle states from database: " + toggleStates);
                }
            }
        } catch (Exception e) {
            if (plugin.isDebugMode()) {
                plugin.debug("Error reloading area from database: " + e.getMessage());
            }
        }
        
        // Notify all listeners that settings might have changed
        if (plugin.getListenerManager() != null) {
            if (plugin.getListenerManager().getProtectionListener() != null) {
                plugin.getListenerManager().getProtectionListener().cleanup();
            }
            if (plugin.getListenerManager().getEnvironmentListener() != null) {
                plugin.getListenerManager().getEnvironmentListener().invalidateCache();
            }
        }
    }

    /**
     * Get a setting value with automatic type casting.
     * This is a more optimized version of getSettingInt/getSettingBoolean that avoids multiple lookups.
     *
     * @param key The setting key to get
     * @param defaultValue The default value to return if the setting is not found
     * @return The setting value, or defaultValue if not found
     */
    @SuppressWarnings("unchecked")
    public <T> T getSettingObject(String key, T defaultValue) {
        String normalizedKey = normalizeToggleKey(key);
        
        // Try cache first for better performance
        if (toggleStateCache != null) {
            if (defaultValue instanceof Boolean) {
                Boolean cachedResult = toggleStateCache.getIfPresent(normalizedKey);
                if (cachedResult != null) {
                    return (T) cachedResult;
                }
            }
        }
        
        // Try to get the value from toggleStates
        Object result = toggleStates.get(normalizedKey);
        if (result == null) {
            return defaultValue;
        }
        
        // Handle type conversion
        try {
            if (defaultValue instanceof Boolean && result instanceof Number) {
                // If we're expecting a boolean but got a number, treat 0 as false, anything else as true
                return (T) Boolean.valueOf(((Number) result).intValue() != 0);
            } else if (defaultValue instanceof Integer && result instanceof Boolean) {
                // If we're expecting an integer but got a boolean, treat false as 0, true as 1
                return (T) Integer.valueOf(((Boolean) result) ? 1 : 0);
            } else if (defaultValue instanceof Integer && result instanceof Number) {
                // If we're expecting an integer and got a number, convert to integer
                return (T) Integer.valueOf(((Number) result).intValue());
            } else if (defaultValue instanceof Boolean && result instanceof Boolean) {
                // If we're expecting a boolean and got a boolean, return as is
                return (T) result;
            } else if (defaultValue instanceof String && result instanceof String) {
                // If we're expecting a string and got a string, return as is
                return (T) result;
            }
        } catch (Exception e) {
            if (plugin.isDebugMode()) {
                plugin.debug("Error converting setting " + key + " with value " + result + ": " + e.getMessage());
            }
        }
        
        // If we can't convert the type properly, return the default
        return defaultValue;
    }

    /**
     * Synchronize toggle states with database
     * @return Updated area with synchronized toggle states
     */
    public Area synchronizeToggleStates() {
        // Check if this is a permission-only operation that shouldn't trigger area recreation
        if (plugin.getPermissionOverrideManager().isPermissionOnlyOperation()) {
            if (plugin.isDebugMode()) {
                plugin.debug("Skipping toggle state synchronization for permission-only operation on area: " + name);
            }
            return this; // Just return the existing area without synchronization
        }
        
        // Before synchronizing, ensure we are not in a permission-setting context
        // This is critical to prevent recursion
        String syncKey = name + "_syncing_toggles";
        Set<String> inProgress = savingAreas.get();
        if (inProgress.contains(syncKey)) {
            if (plugin.isDebugMode()) {
                plugin.debug("Preventing recursive toggle state synchronization for area: " + name);
            }
            return this;
        }
        
        inProgress.add(syncKey);
        try {
            AreaDTO currentDTO = toDTO();
            JSONObject settings = currentDTO.settings();
            JSONObject toggleStatesJson = currentDTO.toggleStates();
            
            // Check if we actually need to synchronize - prevent unnecessary work
            boolean needsSync = false;
            
            // Check if any toggle needs updating
            for (Map.Entry<String, Object> entry : toggleStates.entrySet()) {
                String key = entry.getKey();
                if (entry.getValue() instanceof Boolean) {
                    boolean value = (Boolean) entry.getValue();
                    if (!toggleStatesJson.has(key) || toggleStatesJson.optBoolean(key) != value) {
                        needsSync = true;
                        break;
                    }
                }
            }
            
            if (!needsSync) {
                if (plugin.isDebugMode()) {
                    plugin.debug("No toggle state changes detected for area " + name + " - skipping synchronization");
                }
                return this;
            }
            
            if (plugin.isDebugMode()) {
                plugin.debug("Synchronizing toggle states for area " + name);
                plugin.debug("  Before - Toggle states: " + toggleStatesJson);
            }
            
            // Process all toggle settings from the settings object
            for (String key : settings.keySet()) {
                // Only process toggle-related settings
                if (PermissionToggle.isValidToggle(key)) {
                    Object value = settings.opt(key);
                    
                    // Determine the correct key format to use in toggleStatesJson
                    String toggleKey = key;
                    
                    // If it's a short key (no prefix), convert to full format for toggleStatesJson
                    if (!key.startsWith(GUI_PERMISSIONS_PREFIX)) {
                        toggleKey = GUI_PERMISSIONS_PREFIX + key;
                    }
                    
                    // Add to toggleStatesJson if it's not already there or has a different value
                    if (!toggleStatesJson.has(toggleKey) || !toggleStatesJson.get(toggleKey).equals(value)) {
                        toggleStatesJson.put(toggleKey, value);
                        
                        if (plugin.isDebugMode()) {
                            plugin.debug("  Adding toggle from settings: " + toggleKey + " = " + value);
                        }
                    }
                }
            }
            
            // Also ensure all toggle states from the in-memory map are in the JSON
            for (Map.Entry<String, Object> entry : toggleStates.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                
                // Make sure the key is in the proper format for toggleStatesJson
                String toggleKey = key;
                if (!key.startsWith(GUI_PERMISSIONS_PREFIX) && key.startsWith("allow")) {
                    toggleKey = GUI_PERMISSIONS_PREFIX + key;
                }
                
                // Add to toggleStatesJson if it's not already there or has a different value
                if (!toggleStatesJson.has(toggleKey)) {
                    toggleStatesJson.put(toggleKey, value);
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("  Adding missing toggle from memory: " + toggleKey + " = " + value);
                    }
                }
            }
            
            if (plugin.isDebugMode()) {
                plugin.debug("  After - Toggle states: " + toggleStatesJson);
            }
            
            // Invalidate toggle state cache after synchronization
            toggleStateCache.invalidateAll();
            
            // Update area with synchronized toggle states
            return AreaBuilder.fromDTO(currentDTO)
                .toggleStates(toggleStatesJson)
                .build();
            
        } finally {
            inProgress.remove(syncKey);
        }
    }

    /**
     * Gets all toggle keys that have been set in this area
     * @return Set of all toggle keys
     */
    public Set<String> getAllToggleKeys() {
        Set<String> keys = new HashSet<>();
        
        // Add keys from toggleStates
        for (String key : toggleStates.keySet()) {
            keys.add(key);
        }
        
        // Add keys from settings
        JSONObject settingsJson = toDTO().settings();
        for (String key : settingsJson.keySet()) {
            if (key.startsWith("gui.permissions.toggles.")) {
                keys.add(key);
            }
        }
        
        // Add keys from default toggles
        for (PermissionToggle toggle : PermissionToggle.getDefaultToggles()) {
            keys.add(toggle.getPermissionNode());
        }
        
        return keys;
    }

    /**
     * Checks if this area has any potion effects
     * @return true if the area has potion effects, false otherwise
     */
    public boolean hasPotionEffects() {
        // Check if potionEffects JSON has any entries
        if (potionEffects != null && potionEffects.length() > 0) {
            return true;
        }
        
        // Check for potion effect toggles in the settings
        JSONObject settingsJson = toDTO().settings();
        for (String key : settingsJson.keySet()) {
            if (key.startsWith("gui.permissions.toggles.allowPotion")) {
                boolean value = settingsJson.optBoolean(key, false);
                if (value) {
                    return true;
                }
                
                // Also check for strength values > 0
                String strengthKey = key + "Strength";
                int strength = settingsJson.optInt(strengthKey, 0);
                if (strength > 0) {
                    return true;
                }
            }
        }
        
        // Also check toggleStates map for direct toggle values
        for (String key : toggleStates.keySet()) {
            if (key.contains("allowPotion")) {
                Object value = toggleStates.get(key);
                
                // Check boolean toggle values
                if (value instanceof Boolean && (Boolean)value) {
                    return true;
                }
                
                // Check strength values
                if (key.endsWith("Strength") && value instanceof Integer && (Integer)value > 0) {
                    return true;
                }
            }
        }
        
        return false;
    }

    /**
     * Gets all potion effects as a JSONObject
     * @return JSONObject containing potion effects
     */
    public JSONObject getPotionEffectsAsJSON() {
        // Return existing potionEffects if available
        if (potionEffects != null && potionEffects.length() > 0) {
            return new JSONObject(potionEffects.toString());
        }
        
        // Rebuild from settings
        JSONObject result = new JSONObject();
        JSONObject settingsJson = toDTO().settings();
        
        // Check for potion effect toggles and their strength values
        for (String key : settingsJson.keySet()) {
            if (key.startsWith("gui.permissions.toggles.allowPotion")) {
                boolean value = settingsJson.optBoolean(key, false);
                if (value) {
                    // Extract the potion effect name
                    String potionName = key.replace("gui.permissions.toggles.allowPotion", "");
                    
                    // Check for strength value
                    String strengthKey = key + "Strength";
                    int strength = settingsJson.optInt(strengthKey, 1);
                    
                    // Add to result
                    result.put(potionName, strength);
                }
            }
        }
        
        return result;
    }

    /**
     * Updates the internal permission maps with data from the database.
     * This method is called by PermissionOverrideManager during synchronization.
     * 
     * @param playerPerms Map of player permissions from the database
     * @param groupPerms Map of group permissions from the database
     * @param trackPerms Map of track permissions from the database
     */
    public void updateInternalPermissions(
            Map<String, Map<String, Boolean>> playerPerms,
            Map<String, Map<String, Boolean>> groupPerms,
            Map<String, Map<String, Boolean>> trackPerms) {
        
        if (plugin.isDebugMode()) {
            plugin.debug("Updating internal permission maps for area " + name);
        }
        
        // Update player permissions - but only if the provided map is not empty
        // This prevents accidental clearing of permissions during area recreation
        if (playerPerms != null) {
            if (!playerPerms.isEmpty()) {
                this.playerPermissions = new HashMap<>(playerPerms);
            } else if (this.playerPermissions == null) {
                // Only initialize with empty map if current permissions are null
                this.playerPermissions = new HashMap<>();
            }
            // Otherwise keep existing permissions
        }
        
        // Update group permissions
        if (groupPerms != null) {
            this.groupPermissions = new HashMap<>(groupPerms);
        }
        
        // Update track permissions - ensure we never set to null
        if (trackPerms != null) {
            this.trackPermissions = new HashMap<>(trackPerms);
            
            if (plugin.isDebugMode()) {
                plugin.debug("  Updated track permissions with " + trackPerms.size() + " tracks");
                if (!trackPerms.isEmpty()) {
                    plugin.debug("  Track names: " + String.join(", ", trackPerms.keySet()));
                    // Log a sample of permissions for one track
                    if (!trackPerms.isEmpty()) {
                        String firstTrack = trackPerms.keySet().iterator().next();
                        Map<String, Boolean> perms = trackPerms.get(firstTrack);
                        plugin.debug("  Sample track permissions for " + firstTrack + ": " + 
                                   (perms != null ? perms.size() : 0) + " permissions");
                    }
                }
            }
        } else if (this.trackPermissions == null) {
            // Initialize with empty map if null to avoid NPEs
            this.trackPermissions = new HashMap<>();
            if (plugin.isDebugMode()) {
                plugin.debug("  Initialized empty track permissions map");
            }
        }
        
        // Clear the effective permission cache to force recalculation
        this.effectivePermissionCache.clear();
        
        if (plugin.isDebugMode()) {
            plugin.debug("  Updated player permissions: " + 
                (this.playerPermissions != null ? this.playerPermissions.size() : 0) + " players");
            plugin.debug("  Updated group permissions: " + 
                (this.groupPermissions != null ? this.groupPermissions.size() : 0) + " groups");
            plugin.debug("  Updated track permissions: " + 
                (this.trackPermissions != null ? this.trackPermissions.size() : 0) + " tracks");
        }
    }

    /**
     * Gets the internal group permissions map directly without triggering database queries
     * This method is for internal use only to avoid recursive database calls
     * 
     * @return The internal group permissions map (not a defensive copy)
     */
    public Map<String, Map<String, Boolean>> getInternalGroupPermissions() {
        return this.groupPermissions;
    }

    /**
     * Updates a toggle state in memory only, without triggering database updates
     * This is a fallback method for when database updates fail
     * @param permission The permission toggle to update
     * @param state The new toggle state value
     */
    public void updateToggleStateInMemory(String permission, boolean state) {
        String normalizedPermission = normalizeToggleKey(permission);
        
        // Only update if it's a toggle permission
        if (normalizedPermission.startsWith(GUI_PERMISSIONS_PREFIX)) {
            // Check if the value is actually changing
            boolean currentState = getToggleState(normalizedPermission);
            if (currentState == state) {
                // No change needed
                if (plugin.isDebugMode()) {
                    plugin.debug("Memory toggle state for " + normalizedPermission + " already set to " + state + " - no change needed");
                }
                return;
            }
            
            if (plugin.isDebugMode()) {
                plugin.debug("Memory-only update of toggle " + normalizedPermission + " from " + currentState + " to " + state);
            }
            
            // Update cache and storage in memory only
            toggleStates.put(normalizedPermission, state);
            toggleStateCache.invalidateAll(); // Clear the entire toggle state cache
            
            // Invalidate all protection caches to ensure changes take effect immediately
            if (plugin.getListenerManager() != null && 
                plugin.getListenerManager().getProtectionListener() != null) {
                plugin.getListenerManager().getProtectionListener().cleanup();
                
                if (plugin.getListenerManager().getEnvironmentListener() != null) {
                    plugin.getListenerManager().getEnvironmentListener().invalidateCache();
                }
            }
            
            // Note: No database updates are triggered from this method
            
            if (plugin.isDebugMode()) {
                plugin.debug("  Memory-only toggle update complete - database was NOT updated");
            }
        }
    }

    public boolean saveToDatabase() {
        // Prevent recursion during database saves
        Set<String> inProgress = savingAreas.get();
        if (inProgress.contains(name)) {
            // We're already saving this area in the current call stack
            if (plugin.isDebugMode()) {
                plugin.debug("Preventing recursive database save for area " + name);
            }
            return true; // Return success to avoid disrupting the parent operation
        }
        inProgress.add(name);
        
        try {
            // Skip database update if we're in a high-frequency context
            if (plugin.getRecentSaveTracker().isHighFrequencyContext()) {
                return true;
            }
            
            // Rate limit database updates
            long currentTime = System.currentTimeMillis();
            if (plugin.getRecentSaveTracker().wasRecentlyUpdated(name, currentTime)) {
                if (plugin.isDebugMode()) {
                    plugin.debug("Skipping database update for area " + name + " due to rate limiting");
                }
                return true;
            }
            
            // Only log if we're in debug mode and not in a high-frequency context
            boolean shouldLog = plugin.isDebugMode() && !plugin.getRecentSaveTracker().isHighFrequencyContext();
            
            if (shouldLog) {
                plugin.debug("Saving area " + name + " to database");
            }
            
            try {
                plugin.getDatabaseManager().updateArea(this);
                
                // Mark as updated for rate limiting
                plugin.getRecentSaveTracker().markUpdated(name, currentTime);
                
                if (shouldLog) {
                    plugin.debug("  Successfully updated area in the database");
                }
                
                return true;
            } catch (Exception e) {
                plugin.getLogger().error("Failed to update area " + name + " in database", e);
                return false;
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error saving area " + name + " to database", e);
            return false;
        } finally {
            // Clean up thread-local to prevent memory leaks
            inProgress.remove(name);
        }
    }

    /**
     * Performs multiple update operations in a single batch to minimize database overhead
     * @param updateToggleStates Whether to update toggle states
     * @param updateDatabase Whether to update the area in database
     * @param syncPerms Whether to synchronize permissions
     * @return True if all operations succeeded, false if any failed
     */
    public boolean batchUpdate(boolean updateToggleStates, boolean updateDatabase, boolean syncPerms) {
        // Use high-frequency context for the entire batch operation
        return plugin.executeInHighFrequencyContext(() -> {
            boolean success = true;
            
            // Track operations for logging
            long startTime = System.currentTimeMillis();
            int operationsSuccess = 0;
            int operationsFailed = 0;
            
            // Only log at the start if we're in debug mode
            boolean shouldLog = plugin.isDebugMode();
            if (shouldLog) {
                plugin.debug("Starting batch update for area " + name);
            }
            
            // First update toggle states if requested
            if (updateToggleStates) {
                if (saveToggleStates()) {
                    operationsSuccess++;
                } else {
                    success = false;
                    operationsFailed++;
                }
            }
            
            // Then synchronize permissions if requested
            if (syncPerms) {
                if (syncPermissions()) {
                    operationsSuccess++;
                } else {
                    success = false;
                    operationsFailed++;
                }
            }
            
            // Finally update the area in database if requested
            if (updateDatabase) {
                if (saveToDatabase()) {
                    operationsSuccess++;
                } else {
                    success = false;
                    operationsFailed++;
                }
            }
            
            // Log completion if we're in debug mode
            if (shouldLog) {
                long duration = System.currentTimeMillis() - startTime;
                plugin.debug("Completed batch update for area " + name + 
                    " in " + duration + "ms (" + operationsSuccess + " succeeded, " + 
                    operationsFailed + " failed)");
            }
            
            return success;
        });
    }

    /**
     * Synchronizes all permission data between the Area object and PermissionOverrideManager
     * Call this before saving the area to ensure all permission data is consistent
     * @deprecated Use syncPermissions() instead which includes rate limiting and recursion prevention
     */
    @Deprecated
    public void synchronizePermissions() {
        syncPermissions();
    }

    /**
     * Get player permissions directly from memory without triggering database load.
     * This method is used internally by the permission system to avoid recursive calls.
     * @return The internal player permissions map reference, may be null
     */
    public Map<String, Map<String, Boolean>> getInternalPlayerPermissions() {
        return this.playerPermissions;
    }

    /**
     * Get the current set of permission operations being processed
     * @return Set of permission operations
     */
    public static Set<String> getPermissionOperations() {
        return permissionOperations.get();
    }
    
    /**
     * Get direct access to the ThreadLocal that tracks permission operations
     * This is used to avoid potential recursion issues
     * @return The ThreadLocal instance
     */
    public static ThreadLocal<Set<String>> getPermissionOperationsThreadLocal() {
        return permissionOperations;
    }
}