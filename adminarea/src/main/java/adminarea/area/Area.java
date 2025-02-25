package adminarea.area;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.exception.DatabaseException;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import cn.nukkit.math.Vector3;
import cn.nukkit.math.SimpleAxisAlignedBB;
import cn.nukkit.level.Position;
import org.json.JSONObject;

public final class Area {
    private final AreaDTO dto;
    private final Map<String, Boolean> effectivePermissionCache;
    private final AreaPermissionHandler permissionHandler;
    private final String name;
    private final String world;
    private final Vector3 pos1;
    private final Vector3 pos2;
    private final int priority;
    private final Map<String, Boolean> toggleStates;
    private final SimpleAxisAlignedBB boundingBox;
    private final Cache<String, Boolean> containsCache;
    // Increased cache size for better hit rate
    private static final int CONTAINS_CACHE_SIZE = 1000;
    private final AdminAreaProtectionPlugin plugin;
    
    // Common string constants to avoid repeated construction
    private static final String GUI_PERMISSIONS_PREFIX = "gui.permissions.toggles.";
    
    private final Map<String, Map<String, Boolean>> groupPermissions;
    private final Map<String, Map<String, Boolean>> trackPermissions;
    private final Map<String, Map<String, Boolean>> playerPermissions;
    private Map<String, Map<String, Boolean>> cachedPlayerPermissions;
    
    // Cache for toggle state lookups
    private final Cache<String, Boolean> toggleStateCache;
    private static final int TOGGLE_CACHE_SIZE = 200;
    
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
            // Normalize key during initialization
            String normalizedKey = normalizeToggleKey(key);
            this.toggleStates.put(normalizedKey, toggles.getBoolean(key));
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
        
        // Check if values are outside bounds for early rejection
        AreaDTO.Bounds bounds = dto.bounds();
        return !(x < bounds.xMin() || x > bounds.xMax() ||
                 y < bounds.yMin() || y > bounds.yMax() ||
                 z < bounds.zMin() || z > bounds.zMax());
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
     * Sets a toggle state with proper key normalization
     */
    public void setToggleState(String permission, boolean state) {
        String normalizedPermission = normalizeToggleKey(permission);
        
        // Update cache and storage
        toggleStates.put(normalizedPermission, state);
        toggleStateCache.invalidate(normalizedPermission);
        
        if (plugin.isDebugMode()) {
            plugin.debug("Setting toggle state for " + normalizedPermission + " to " + state + " in area " + name);
        }
    }

    /**
     * Gets a toggle state with proper key normalization and caching
     */
    public boolean getToggleState(String permission) {
        // Normalize the permission name
        String normalizedPermission = normalizeToggleKey(permission);
        
        // Check cache first
        Boolean cachedValue = toggleStateCache.getIfPresent(normalizedPermission);
        if (cachedValue != null) {
            return cachedValue;
        }
        
        if (plugin.isDebugMode()) {
            plugin.debug("Getting toggle state for " + normalizedPermission + " in area " + name);
            plugin.debug("  Toggle states: " + toggleStates);
        }
        
        // Try with the normalized permission
        if (toggleStates.containsKey(normalizedPermission)) {
            boolean value = toggleStates.get(normalizedPermission);
            toggleStateCache.put(normalizedPermission, value);
            
            if (plugin.isDebugMode()) {
                plugin.debug("  Value: " + value);
            }
            return value;
        }
        
        // If the permission wasn't normalized correctly, try other formats
        // This is just a fallback for backward compatibility
        if (normalizedPermission.startsWith(GUI_PERMISSIONS_PREFIX)) {
            String permWithoutPrefix = normalizedPermission.substring(GUI_PERMISSIONS_PREFIX.length());
            if (toggleStates.containsKey(permWithoutPrefix)) {
                boolean value = toggleStates.get(permWithoutPrefix);
                toggleStateCache.put(normalizedPermission, value);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("  Value (without prefix): " + value);
                }
                return value;
            }
        } else if (!normalizedPermission.startsWith(GUI_PERMISSIONS_PREFIX)) {
            String permWithPrefix = GUI_PERMISSIONS_PREFIX + normalizedPermission;
            if (toggleStates.containsKey(permWithPrefix)) {
                boolean value = toggleStates.get(permWithPrefix);
                toggleStateCache.put(normalizedPermission, value);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("  Value (with prefix): " + value);
                }
                return value;
            }
        }
        
        // Default value if not found
        boolean defaultValue = false;
        toggleStateCache.put(normalizedPermission, defaultValue);
        
        if (plugin.isDebugMode()) {
            plugin.debug("  Value: false (default)");
        }
        return defaultValue;
    }

    public AreaDTO toDTO() {
        // Create a JSONObject from toggleStates map
        JSONObject toggleStatesJson = new JSONObject(toggleStates);
        
        if (plugin.isDebugMode()) {
            plugin.debug(String.format("Converting area %s to DTO", name));
            plugin.debug("Toggle states: " + toggleStatesJson.toString());
        }

        return new AreaDTO(
            name,
            world,
            dto.bounds(),
            priority,
            dto.showTitle(),
            dto.settings(),
            new HashMap<>(groupPermissions),
            dto.inheritedPermissions(),
            toggleStatesJson,  // Use the actual toggle states
            dto.defaultToggleStates(),
            dto.inheritedToggleStates(),
            dto.permissions(),
            dto.enterMessage(),
            dto.leaveMessage(),
            new HashMap<>(trackPermissions),
            new HashMap<>(playerPermissions)
        );
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
        
        String cacheKey = pos.getFloorX() + ":" + pos.getFloorY() + ":" + pos.getFloorZ();
        Boolean cached = containsCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }
        
        // Quick check using bounding box
        if (!boundingBox.isVectorInside(pos)) {
            containsCache.put(cacheKey, false);
            return false;
        }
        
        // Detailed check if within bounding box
        boolean result = pos.getX() >= Math.min(pos1.getX(), pos2.getX()) &&
                        pos.getX() <= Math.max(pos1.getX(), pos2.getX()) &&
                        pos.getY() >= Math.min(pos1.getY(), pos2.getY()) &&
                        pos.getY() <= Math.max(pos1.getY(), pos2.getY()) &&
                        pos.getZ() >= Math.min(pos1.getZ(), pos2.getZ()) &&
                        pos.getZ() <= Math.max(pos1.getZ(), pos2.getZ());
        
        containsCache.put(cacheKey, result);
        return result;
    }

    private boolean isGlobal() {
        AreaDTO.Bounds bounds = dto.bounds();
        return bounds.xMin() <= -29000000 && bounds.xMax() >= 29000000 &&
               bounds.zMin() <= -29000000 && bounds.zMax() >= 29000000;
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
        cachedPlayerPermissions = null;
        
        // Also clear the toggle states to force reload from DTO
        toggleStates.clear();
        JSONObject toggles = dto.toggleStates();
        for (String key : toggles.keySet()) {
            toggleStates.put(key, toggles.getBoolean(key));
        }
    }

    // Getters
    public Vector3 getPos1() { return pos1; }
    public Vector3 getPos2() { return pos2; }
    public Map<String, Boolean> getToggleStates() { return new HashMap<>(toggleStates); }
    public SimpleAxisAlignedBB getBoundingBox() { return boundingBox; }
    
    // For efficient area lookup
    public boolean overlaps(SimpleAxisAlignedBB other) {
        return this.boundingBox.intersectsWith(other);
    }
    
    public boolean overlaps(Area other) {
        return this.boundingBox.intersectsWith(other.boundingBox);
    }

    public Map<String, Boolean> getTrackPermissions(String trackName) {
        return trackPermissions.getOrDefault(trackName, new HashMap<>());
    }

    public Map<String, Map<String, Boolean>> getTrackPermissions() {
        return trackPermissions;
    }

    public Map<String, Map<String, Boolean>> getPlayerPermissions() {
        // Return cached permissions if available
        if (cachedPlayerPermissions != null) {
            return new HashMap<>(cachedPlayerPermissions);
        }

        if (plugin.isDebugMode()) {
            plugin.debug("Getting player permissions for area " + name + " (not cached)");
        }

        // Cache permissions
        cachedPlayerPermissions = new HashMap<>(playerPermissions);
        return new HashMap<>(cachedPlayerPermissions);
    }

    public Map<String, Boolean> getPlayerPermissions(String playerName) {
        if (plugin.isDebugMode()) {
            plugin.debug("Getting permissions for player " + playerName + " in area " + name);
            plugin.debug("  All player permissions: " + playerPermissions);
            plugin.debug("  Player's permissions: " + playerPermissions.get(playerName));
            plugin.debug("  DTO player permissions: " + dto.playerPermissions());
            plugin.debug("  DTO player permissions for " + playerName + ": " + 
                (dto.playerPermissions().containsKey(playerName) ? dto.playerPermissions().get(playerName) : "none"));
        }
        return playerPermissions.getOrDefault(playerName, new HashMap<>());
    }

    public void setPlayerPermissions(String playerName, Map<String, Boolean> permissions) throws DatabaseException {
        if (plugin.isDebugMode()) {
            plugin.debug("Setting permissions for player " + playerName + " in area " + name);
            plugin.debug("  New permissions: " + permissions);
            plugin.debug("  Old permissions in map: " + playerPermissions.get(playerName));
        }
        
        // Update local map
        playerPermissions.put(playerName, new HashMap<>(permissions));
        
        // Save to database
        try {
            plugin.getDatabaseManager().saveArea(this);
            if (plugin.isDebugMode()) {
                plugin.debug("  Saved updated permissions to database");
            }
        } catch (Exception e) {
            plugin.getLogger().error("Failed to save player permissions to database", e);
        }
    }

    public void setPlayerPermission(String playerName, String permission, boolean value) throws DatabaseException {
        if (plugin.isDebugMode()) {
            plugin.debug("Setting permission " + permission + " to " + value + " for player " + playerName + " in area " + name);
            plugin.debug("  Old permissions in map: " + playerPermissions.get(playerName));
        }

        // Update local map
        Map<String, Boolean> playerPerms = playerPermissions.computeIfAbsent(playerName, k -> new HashMap<>());
        playerPerms.put(permission, value);

        // Save to database
        try {
            plugin.getDatabaseManager().saveArea(this);
            if (plugin.isDebugMode()) {
                plugin.debug("  Saved updated permission to database");
            }
        } catch (Exception e) {
            plugin.getLogger().error("Failed to save player permission to database", e);
        }
    }

    public Map<String, Boolean> getGroupPermissions(String groupName) {
        return groupPermissions.getOrDefault(groupName, new HashMap<>());
    }

    public Map<String, Map<String, Boolean>> getGroupPermissions() {
        return groupPermissions;
    }

    public void clearAllPermissions() throws DatabaseException {
        // Temporarily disable debug logging
        boolean wasDebug = plugin.isDebugMode();
        plugin.setDebugMode(false);
        
        try {
            // Clear all permission maps
            groupPermissions.clear();
            trackPermissions.clear();
            playerPermissions.clear();
            
            // Clear cache
            cachedPlayerPermissions = null;
            
            // Save to database directly without triggering events
            plugin.getDatabaseManager().saveArea(this);
            
        } finally {
            // Restore debug mode
            plugin.setDebugMode(wasDebug);
            
            if (wasDebug) {
                plugin.debug("Cleared all permissions for area " + name);
                plugin.debug("  Group permissions cleared: " + groupPermissions);
                plugin.debug("  Track permissions cleared: " + trackPermissions);
                plugin.debug("  Player permissions cleared: " + playerPermissions);
            }
        }
    }

    /**
     * Ensures all toggle states are stored with consistent prefix
     * Call this method during plugin startup or area loading to fix inconsistencies
     */
    public void normalizeToggleStates() {
        if (plugin.isDebugMode()) {
            plugin.debug("Normalizing toggle states for area " + name);
            plugin.debug("  Before: " + toggleStates);
        }
        
        Map<String, Boolean> normalizedToggles = new ConcurrentHashMap<>(8, 0.75f, 1);
        
        // Process each toggle and normalize its key
        for (Map.Entry<String, Boolean> entry : toggleStates.entrySet()) {
            String key = entry.getKey();
            boolean value = entry.getValue();
            
            // Normalize the key
            if (!key.startsWith("gui.permissions.toggles.") && key.startsWith("allow")) {
                key = "gui.permissions.toggles." + key;
            }
            
            normalizedToggles.put(key, value);
        }
        
        // Replace the toggle states with normalized ones
        toggleStates.clear();
        toggleStates.putAll(normalizedToggles);
        
        if (plugin.isDebugMode()) {
            plugin.debug("  After: " + toggleStates);
        }
    }
}