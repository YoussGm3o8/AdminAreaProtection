package adminarea.managers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaDTO;
import adminarea.exception.DatabaseException;
import adminarea.interfaces.IAreaManager;
import adminarea.permissions.PermissionToggle;
import adminarea.stats.AreaStatistics;
import cn.nukkit.Player;
import cn.nukkit.level.Level;
import cn.nukkit.level.Position;
import cn.nukkit.level.particle.DustParticle;
import cn.nukkit.math.Vector3;
import cn.nukkit.scheduler.TaskHandler;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.json.JSONObject;
import io.micrometer.core.instrument.Timer;
import adminarea.permissions.PermissionOverrideManager;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AreaManager implements IAreaManager {
    private static final Logger logger = LoggerFactory.getLogger(AreaManager.class);
    private final AdminAreaProtectionPlugin plugin;
    private final java.util.concurrent.locks.ReentrantReadWriteLock lock = new java.util.concurrent.locks.ReentrantReadWriteLock();
    private final java.util.concurrent.locks.Lock writeLock = lock.writeLock();
    private final Map<String, TaskHandler> visualizationTasks = new HashMap<>();
    private final Map<String, AreaStatistics> areaStats = new HashMap<>();
    private final Map<String, Area> areasByName = new HashMap<>();
    private final List<Area> areas = new ArrayList<>();
    private final Map<String, Area> globalAreasByWorld = new HashMap<>();
    // Add map for managing areas by world
    private final Map<String, List<Area>> worldAreas = new HashMap<>();
    
    // Add cache for area queries with smaller size for global areas
    private final Cache<String, List<Area>> locationCache;
    private final Cache<String, Area> nameCache;
    private final Map<String, Map<Integer, List<Area>>> spatialIndex;
    private static final int CACHE_SIZE = 1000; // Reduced cache size
    private static final long CACHE_EXPIRY = TimeUnit.MINUTES.toMillis(2); // Reduced expiry time
    private static final int CHUNK_CHECK_RADIUS = 4;
    private final DatabaseManager databaseManager;

    /**
     * Stores areas keyed by their chunk coordinates (chunkX, chunkZ).
     */
    private final Map<Long, Set<Area>> chunkAreaMap = new HashMap<>();

    // Thread-local set to track areas being updated to prevent recursion
    private static final ThreadLocal<Set<String>> updatingAreas = ThreadLocal.withInitial(() -> new HashSet<>());

    public AreaManager(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        this.databaseManager = plugin.getDatabaseManager();
        this.locationCache = Caffeine.newBuilder()
            .maximumSize(CACHE_SIZE)
            .expireAfterWrite(CACHE_EXPIRY, TimeUnit.MILLISECONDS)
            .build();
        this.nameCache = Caffeine.newBuilder()
            .maximumSize(200) // Reduced size
            .expireAfterWrite(2, TimeUnit.MINUTES) // Reduced time
            .build();
        this.spatialIndex = new ConcurrentHashMap<>();
    }

    private int getChunkKey(int x, int z) {
        return (x >> 4) & 0xFFFFF | ((z >> 4) & 0xFFFFF) << 20;
    }
    
    /**
     * Builds a more efficient spatial index for the area
     * This significantly improves performance for area lookups
     */
    private void addToSpatialIndex(Area area) {
        // Skip null areas
        if (area == null) return;
        
        AreaDTO.Bounds bounds = area.toDTO().bounds();
        String world = area.getWorld();
        
        // Skip out-of-bounds areas
        if (world == null || bounds == null) return;
        
        // Global areas are handled differently - don't add to spatial index
        if (isGlobalArea(area)) return;
        
        // Optimize chunk calculation with bit shifts
        int minChunkX = bounds.xMin() >> 4;
        int maxChunkX = bounds.xMax() >> 4;
        int minChunkZ = bounds.zMin() >> 4;
        int maxChunkZ = bounds.zMax() >> 4;
        
        // Limit chunk range to reasonable values to prevent excessive memory usage
        int chunkSpanX = maxChunkX - minChunkX + 1;
        int chunkSpanZ = maxChunkZ - minChunkZ + 1;
        
        // Skip very large areas - they'll be handled differently
        if (chunkSpanX > 100 || chunkSpanZ > 100) {
            if (plugin.isDebugMode()) {
                plugin.debug("Area " + area.getName() + " is too large for spatial indexing: " + 
                             chunkSpanX + "x" + chunkSpanZ + " chunks");
            }
            return;
        }
        
        // Add area to all overlapping chunks
        Map<Integer, List<Area>> worldIndex = spatialIndex.computeIfAbsent(world, k -> new ConcurrentHashMap<>());
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) {
                int key = getChunkKey(x, z);
                worldIndex.computeIfAbsent(key, k -> new ArrayList<>()).add(area);
            }
        }
    }
    
    private void removeFromSpatialIndex(Area area) {
        AreaDTO.Bounds bounds = area.toDTO().bounds();
        String world = area.getWorld();
        
        Map<Integer, List<Area>> worldIndex = spatialIndex.get(world);
        if (worldIndex == null) return;
        
        // Get chunk coordinates
        int minChunkX = bounds.xMin() >> 4;
        int maxChunkX = bounds.xMax() >> 4;
        int minChunkZ = bounds.zMin() >> 4;
        int maxChunkZ = bounds.zMax() >> 4;
        
        // Remove area from all overlapping chunks
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) {
                int key = getChunkKey(x, z);
                List<Area> chunkAreas = worldIndex.get(key);
                if (chunkAreas != null) {
                    chunkAreas.remove(area);
                    if (chunkAreas.isEmpty()) {
                        worldIndex.remove(key);
                    }
                }
            }
        }
        
        if (worldIndex.isEmpty()) {
            spatialIndex.remove(world);
        }
    }

    @Override
    public void addArea(Area area) {
        if (area == null) {
            throw new IllegalArgumentException("Area cannot be null");
        }

        try {
            writeLock.lock();

            // Check for duplicate names
            if (hasArea(area.getName())) {
                if (plugin.isDebugMode()) {
                    plugin.debug("Area with name already exists: " + area.getName());
                }
                return;
            }
            
            if (plugin.isDebugMode()) {
                plugin.debug("Adding area to memory: " + area.getName());
            }

            // Initialize default toggle states if needed
            boolean needToSaveToggles = false;
            if (plugin.isDebugMode()) {
                plugin.debug("Checking toggle states for area " + area.getName());
                plugin.debug("Current toggle states: " + area.toDTO().toggleStates());
            }

            // Check if toggle states are empty or missing key permissions
            Map<String, Object> toggleStates = area.getToggleStates();
            if (toggleStates.isEmpty()) {
                if (plugin.isDebugMode()) {
                    plugin.debug("Toggle states empty, applying defaults");
                }
                // Apply defaults for all toggles
                for (PermissionToggle toggle : PermissionToggle.getDefaultToggles()) {
                    String key = toggle.getPermissionNode();
                    if (!key.startsWith("gui.permissions.toggles.")) {
                        key = "gui.permissions.toggles." + key;
                    }
                    area.setToggleState(key, toggle.getDefaultValue());
                    needToSaveToggles = true;
                }
            } else {
                // Check for missing toggles
                for (PermissionToggle toggle : PermissionToggle.getDefaultToggles()) {
                    String key = toggle.getPermissionNode();
                    if (!key.startsWith("gui.permissions.toggles.")) {
                        key = "gui.permissions.toggles." + key;
                    }
                    if (!toggleStates.containsKey(key)) {
                        if (plugin.isDebugMode()) {
                            plugin.debug("Adding missing toggle: " + key + " = " + toggle.getDefaultValue());
                        }
                        area.setToggleState(key, toggle.getDefaultValue());
                        needToSaveToggles = true;
                    }
                }
            }

            if (plugin.isDebugMode()) {
                plugin.debug("Toggle states after initialization: " + area.toDTO().toggleStates());
            }

            // Detect global area
            boolean isGlobal = isGlobalArea(area);
            
            if (plugin.isDebugMode()) {
                plugin.debug("Adding area to collections: " + area.getName() + 
                          (isGlobal ? " (GLOBAL AREA)" : ""));
            }

            // Add area to memory maps
            String world = area.getWorld();
            if (isGlobal) {
                globalAreasByWorld.put(world, area);
            } else {
                areas.add(area);
                // Only add non-global areas to spatial index
                addToSpatialIndex(area);
            }
            
            // Add to world areas and name lookup for all area types
            worldAreas.computeIfAbsent(world, k -> new ArrayList<>()).add(area);
            areasByName.put(area.getName().toLowerCase(), area);

            // Register area in chunkAreaMap 
            registerArea(area);

            if (plugin.isDebugMode()) {
                plugin.debug("Successfully added area to memory: " + area.getName());
                plugin.debug("Current areas in memory: " + areasByName.size() + 
                          " (global: " + globalAreasByWorld.size() + 
                          ", regular: " + areas.size() + ")");
            }

            // Save toggle states to database if we added any default ones
            if (needToSaveToggles) {
                if (plugin.isDebugMode()) {
                    plugin.debug("Saving initialized toggle states for " + area.getName());
                }
                try {
                    area.saveToggleStates();
                } catch (Exception e) {
                    plugin.getLogger().error("Failed to save toggle states for " + area.getName(), e);
                }
            }

        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Gets the global area for a specific world, if one exists.
     * This is optimized for quick lookups of global area settings.
     */
    public Area getGlobalAreaForWorld(String worldName) {
        return globalAreasByWorld.get(worldName);
    }

    /**
     * Checks if an area is global more efficiently by checking the world map first
     */
    private boolean isGlobalArea(Area area) {
        // First check if it's already in the global areas map
        if (globalAreasByWorld.containsValue(area)) {
            return true;
        }

        // If not found in map, check bounds
        AreaDTO.Bounds bounds = area.toDTO().bounds();
        return bounds.xMin() <= -29000000 && bounds.xMax() >= 29000000 &&
               bounds.zMin() <= -29000000 && bounds.zMax() >= 29000000;
    }

    @Override
    public void removeArea(Area area) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            if (area == null) return;

            // Remove from appropriate collection based on area type
            if (isGlobalArea(area)) {
                globalAreasByWorld.remove(area.getWorld());
            } else {
                areas.remove(area);
                removeFromSpatialIndex(area);
            }

            // Remove from name lookup map
            areasByName.remove(area.getName().toLowerCase());

            // Delete from database
            try {
                databaseManager.deleteArea(area.getName());
            } catch (DatabaseException e) {
                plugin.getLogger().error("Failed to delete area from database: " + area.getName(), e);
                // Continue with removal from memory even if database delete fails
            }

            // Clear caches
            locationCache.invalidateAll();
            nameCache.invalidate(area.getName().toLowerCase());

            // Unregister area from chunkAreaMap
            unregisterArea(area);

            if (plugin.isDebugMode()) {
                plugin.debug("Removed area: " + area.getName() + 
                    (isGlobalArea(area) ? " (global)" : ""));
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "area_remove");
        }
    }

    /**
     * Updates an area with optimized database operations
     * 
     * @param area The area to update
     */
    @Override
    public void updateArea(Area area) {
        if (area == null) {
            return;
        }
        
        // Prevent recursion during area updates
        Set<String> inProgress = updatingAreas.get();
        String areaKey = area.getName().toLowerCase();
        if (inProgress.contains(areaKey)) {
            // We're already updating this area in the current call stack
            if (plugin.isDebugMode()) {
                plugin.debug("Preventing recursive area update for area " + areaKey);
            }
            return; // Skip this update to avoid recursion
        }
        
        // Check if this is a permission-only operation
        boolean isPermissionOperation = isPermissionOperationInProgress(area.getName());
        
        if (isPermissionOperation) {
            if (plugin.isDebugMode()) {
                plugin.debug("Skipping area update for permission-only operation on area: " + area.getName());
                plugin.debug("  Permission operations use a separate database and don't need area updates");
            }
            return; // Skip the update entirely for permission operations
        }
        
        // Mark that we're processing this area
        inProgress.add(areaKey);
        
        writeLock.lock();
        try {
            long currentTime = System.currentTimeMillis();
            
            // IMPORTANT: Check if player permissions were updated and need to be preserved
            Map<String, Map<String, Boolean>> playerPermissions = null;
            try {
                // First try to get permissions from database directly for maximum reliability
                playerPermissions = plugin.getPermissionOverrideManager().getAllPlayerPermissionsFromDatabase(area.getName());
                
                // If that fails, get from DTO as backup
                if (playerPermissions == null || playerPermissions.isEmpty()) {
                    AreaDTO dto = area.toDTO();
                    playerPermissions = dto.playerPermissions();
                    
                    if (plugin.isDebugMode() && playerPermissions != null && !playerPermissions.isEmpty()) {
                        plugin.debug("Using player permissions from DTO as backup: " + playerPermissions.size() + " players");
                    }
                } else if (plugin.isDebugMode()) {
                    plugin.debug("Using player permissions from database: " + playerPermissions.size() + " players");
                }
            } catch (Exception e) {
                // Ignore and continue with possibly null permissions
                if (plugin.isDebugMode()) {
                    plugin.debug("Error retrieving player permissions: " + e.getMessage());
                }
            }
            boolean hasCustomPermissions = playerPermissions != null && !playerPermissions.isEmpty();
            
            if (hasCustomPermissions && plugin.isDebugMode()) {
                plugin.debug("Area " + area.getName() + " has custom player permissions that will be preserved");
                plugin.debug("Current player permissions: " + playerPermissions.size() + " players");
            }
            
            // Get updated area - IMPORTANT: we'll restore player permissions if needed
            Area updatedArea = recreateArea(area);
            
            // Preserve player permissions explicitly if they might be lost
            if (hasCustomPermissions) {
                Map<String, Map<String, Boolean>> updatedPermissions = null;
                try {
                    // Get permissions from DTO
                    AreaDTO dto = updatedArea.toDTO();
                    updatedPermissions = dto.playerPermissions();
                } catch (Exception e) {
                    // Ignore
                }
                boolean permissionsLost = updatedPermissions == null || updatedPermissions.isEmpty();
                
                if (permissionsLost) {
                    if (plugin.isDebugMode()) {
                        plugin.debug("Player permissions lost during area update - restoring them");
                    }
                    
                    // Restore permissions from the original area
                    for (Map.Entry<String, Map<String, Boolean>> entry : playerPermissions.entrySet()) {
                        String playerName = entry.getKey();
                        Map<String, Boolean> perms = entry.getValue();
                        
                        if (perms != null && !perms.isEmpty()) {
                            // Set player permissions directly on the area
                            for (Map.Entry<String, Boolean> permEntry : perms.entrySet()) {
                                updatedArea.setPlayerPermission(playerName, permEntry.getKey(), permEntry.getValue());
                            }
                            
                            if (plugin.isDebugMode()) {
                                plugin.debug("Restored " + perms.size() + " permissions for player " + playerName);
                            }
                        }
                    }
                    
                    // Save permissions to database using the direct update method
                    for (Map.Entry<String, Map<String, Boolean>> entry : playerPermissions.entrySet()) {
                        directUpdatePlayerPermissions(updatedArea.getName(), entry.getKey(), entry.getValue());
                    }
                }
            }
            
            // Save toggle states - only if needed, not on every update
            try {
                if (!plugin.getRecentSaveTracker().wasRecentlySaved(areaKey, currentTime)) {
                    updatedArea.saveToggleStates();
                    plugin.getRecentSaveTracker().markSaved(areaKey, currentTime);
                }
            } catch (Exception e) {
                plugin.getLogger().error("Error saving toggle states for area: " + updatedArea.getName(), e);
            }

            // Synchronize permissions - only if needed
            if (plugin.getPermissionOverrideManager() != null && 
                !plugin.getRecentSaveTracker().wasRecentlySynced(areaKey, currentTime)) {
                try {
                    plugin.getPermissionOverrideManager().synchronizePermissions(
                        updatedArea,
                        PermissionOverrideManager.SyncDirection.BIDIRECTIONAL
                    );
                    plugin.getRecentSaveTracker().markSynced(areaKey, currentTime);
                } catch (Exception e) {
                    plugin.getLogger().error("Failed to synchronize permissions for area: " + area.getName(), e);
                }
            }

            // Update database - only if needed
            try {
                if (!plugin.getRecentSaveTracker().wasRecentlyUpdated(areaKey, currentTime)) {
                    plugin.getDatabaseManager().updateArea(updatedArea);
                    // Only flush changes if we're not in a high-frequency update context
                    if (!plugin.getRecentSaveTracker().isHighFrequencyContext()) {
                        plugin.getDatabaseManager().flushChanges();
                    }
                    plugin.getRecentSaveTracker().markUpdated(areaKey, currentTime);
                }
            } catch (Exception e) {
                plugin.getLogger().error("Failed to update area in database: " + area.getName(), e);
                return; // Exit if database update fails to maintain consistency
            }

            // Remove old area from collections
            if (isGlobalArea(area)) {
                globalAreasByWorld.remove(area.getWorld());
            } else {
                areas.remove(area);
                removeFromSpatialIndex(area);
            }
            areasByName.remove(area.getName().toLowerCase());
            
            List<Area> areaList = worldAreas.get(area.getWorld());
            if (areaList != null) {
                areaList.removeIf(a -> a.getName().equalsIgnoreCase(area.getName()));
            }
            unregisterArea(area);

            // Add updated area to collections
            if (isGlobalArea(updatedArea)) {
                globalAreasByWorld.put(updatedArea.getWorld(), updatedArea);
            } else {
                areas.add(updatedArea);
                addToSpatialIndex(updatedArea);
            }
            areasByName.put(updatedArea.getName().toLowerCase(), updatedArea);
            
            if (areaList != null) {
                areaList.add(updatedArea);
            } else {
                worldAreas.computeIfAbsent(updatedArea.getWorld(), k -> new ArrayList<>()).add(updatedArea);
            }
            registerArea(updatedArea);

            // Only reload listeners if we're not in a high-frequency update context
            if (!plugin.getRecentSaveTracker().isHighFrequencyContext() && plugin.getListenerManager() != null) {
                plugin.getListenerManager().reload();
            }
        } finally {
            writeLock.unlock();
            // Clean up thread-local to prevent memory leaks
            inProgress.remove(areaKey);
        }
    }
    
    /**
     * Invalidates all caches for an area, ensuring fresh data is loaded next time
     * @param area The area to invalidate caches for
     */
    public void invalidateAllCaches(Area area) {
        if (area == null) return;
        
        // Clear all area-related caches
        area.clearCaches();
        
        // Clear location cache
        locationCache.invalidateAll();
        
        // Clear name cache
        nameCache.invalidate(area.getName().toLowerCase());
        
        // Clear associated permission caches
        if (plugin.getPermissionOverrideManager() != null) {
            plugin.getPermissionOverrideManager().invalidateCache(area.getName());
            
            // Also clear permission checker cache
            if (plugin.getPermissionOverrideManager().getPermissionChecker() != null) {
                plugin.getPermissionOverrideManager().getPermissionChecker().invalidateCache(area.getName());
            }
        }
        
        // Invalidate spatial index caches
        invalidateSpatialIndex(area);
        
        // Clear visualization tasks if active
        cancelVisualization(area.getName());
        
        if (plugin.isDebugMode()) {
            plugin.debug("Invalidated all caches for area: " + area.getName());
        }
    }
    
    /**
     * Invalidates spatial index entries for an area
     */
    private void invalidateSpatialIndex(Area area) {
        if (area == null) return;
        
        List<Long> chunks = computeChunkKeys(area);
        for (Long chunkKey : chunks) {
            Set<Area> areasInChunk = chunkAreaMap.get(chunkKey);
            if (areasInChunk != null) {
                // Remove and re-add to ensure updated version
                areasInChunk.remove(area);
                
                // Only re-add if this isn't a global area (those are handled separately)
                if (!isGlobalArea(area)) {
                    areasInChunk.add(area);
                }
            }
        }
    }
    
    /**
     * Cancels visualization for an area by name
     */
    private void cancelVisualization(String areaName) {
        if (areaName == null) return;
        
        // Cancel any active visualization tasks for this area
        try {
            // Since we store tasks by player name, we need a different approach
            // Loop through players on the server and check if they're visualizing this area
            for (Player player : plugin.getServer().getOnlinePlayers().values()) {
                // Check if this player has a visualization task
                if (player.isOnline()) {
                    // We don't have a direct way to tell which area is being visualized
                    // So we'll just stop all visualizations for players who might be seeing this area
                    stopVisualization(player);
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Stopped area visualization for player: " + player.getName());
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().error("Failed to cancel visualization for area: " + areaName, e);
        }
    }

    @Override
    public Area getArea(String name) {
        Area area = areasByName.get(name.toLowerCase());
        if (plugin.isDebugMode() && area != null) {
            plugin.debug("Retrieved area " + name + ":");
            // Avoid recursive calls that could lead to database connection issues
            // plugin.debug("  Player permissions: " + area.getPlayerPermissions());
            // plugin.debug("  Toggle states: " + area.toDTO().toggleStates());
        }
        return area;
    }

    /**
     * Gets an area directly from the internal map without triggering permission database queries
     * This method should only be used internally to avoid recursive database calls
     * 
     * @param name The name of the area
     * @return The area, or null if not found
     */
    public Area getAreaInternal(String name) {
        if (name == null) return null;
        return areasByName.get(name.toLowerCase());
    }

    @Override
    public List<Area> getAllAreas() {
        // Create a new list with all local areas
        List<Area> allAreas = new ArrayList<>(areas);
        
        // Add all global areas
        allAreas.addAll(globalAreasByWorld.values());
        
        // Sort by priority (highest first) for consistent ordering
        allAreas.sort((a1, a2) -> Integer.compare(a2.getPriority(), a1.getPriority()));
        
        return allAreas;
    }

    @Override
    public boolean hasArea(String name) {
        return areasByName.containsKey(name.toLowerCase());
    }

    /**
     * Gets a list of all areas that overlap with the given coordinates
     * Optimized for performance with spatial indexing and caching
     */
    public List<Area> getAreasAtLocation(String world, double x, double y, double z) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            // Skip if world name is invalid
            if (world == null || world.isEmpty()) {
                return Collections.emptyList();
            }
            
            // Check recursion to prevent stack overflow with overlapping areas
            String recursionKey = world + ":" + x + ":" + y + ":" + z;
            if (recursionChecks.contains(recursionKey)) {
                return Collections.emptyList();
            }
            recursionChecks.add(recursionKey);
            
            try {
                // Pre-allocate result list with estimated capacity
                List<Area> result = new ArrayList<>(4); // Most locations have few overlapping areas
                
                // Check for global area first (most efficient)
                Area globalArea = globalAreasByWorld.get(world);
                if (globalArea != null) {
                    result.add(globalArea);
                    
                    // For global areas, we can avoid further processing completely
                    // This is a significant optimization for servers with many players
                    return result; // Global area overrides all others
                }
                
                // Get all potential areas in this chunk
                long chunkKey = toLongKey((int)(x) >> 4, (int)(z) >> 4); // Use toLongKey instead of getChunkKey
                Set<Area> chunkAreas = chunkAreaMap.getOrDefault(chunkKey, Collections.emptySet());
                
                if (!chunkAreas.isEmpty()) {
                    // Filter areas that actually contain the point and sort by priority
                    for (Area area : chunkAreas) {
                        if (area.isInside(world, x, y, z)) { // Use isInside instead of contains
                            result.add(area);
                        }
                    }
                    
                    if (!result.isEmpty()) {
                        result.sort((a1, a2) -> Integer.compare(a2.getPriority(), a1.getPriority())); 
                    }
                }
                
                return result;
            } finally {
                recursionChecks.remove(recursionKey);
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "get_areas_at_location");
        }
    }

    private static final Set<String> recursionChecks = ConcurrentHashMap.newKeySet();

    public boolean isNearAnyPlayer(double x, double z) {
        // Get chunk coordinates of the target location
        int targetChunkX = (int)x >> 4;
        int targetChunkZ = (int)z >> 4;

        // Check if any online player is within range
        for (Player player : plugin.getServer().getOnlinePlayers().values()) {
            int playerChunkX = player.getChunkX();
            int playerChunkZ = player.getChunkZ();

            // Calculate chunk distance
            int chunkDistance = Math.max(
                Math.abs(playerChunkX - targetChunkX),
                Math.abs(playerChunkZ - targetChunkZ)
            );

            // If within check radius of any player, return true
            if (chunkDistance <= CHUNK_CHECK_RADIUS) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unused")
    private void invalidateLocationCache() {
        locationCache.invalidateAll();
    }

    /**
     * Gets the highest priority area at a specific location.
     * This is the area whose settings will take precedence over any other
     * overlapping areas at this location due to having the highest priority value.
     *
     * @param world The world name
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @return The highest priority area at the location, or null if no areas exist there
     */
    public Area getHighestPriorityArea(String world, double x, double y, double z) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            List<Area> areasAtLocation = getAreasAtLocation(world, x, y, z);
            return areasAtLocation.isEmpty() ? null : areasAtLocation.get(0);
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "area_lookup_highest");
        }
    }

    /**
     * Merges two areas, keeping the highest priority between them.
     * The merged area will use the higher priority value to maintain
     * proper precedence in the protection hierarchy.
     */
    public Area mergeAreas(Area area1, Area area2) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            AreaDTO dto1 = area1.toDTO();
            AreaDTO dto2 = area2.toDTO();

            if (!dto1.world().equals(dto2.world())) {
                throw new IllegalArgumentException("Cannot merge areas from different worlds");
            }

            // Create merged area with combined bounds
            AreaDTO.Bounds mergedBounds = new AreaDTO.Bounds(
                Math.min(dto1.bounds().xMin(), dto2.bounds().xMin()),
                Math.max(dto1.bounds().xMax(), dto2.bounds().xMax()),
                Math.min(dto1.bounds().yMin(), dto2.bounds().yMin()),
                Math.max(dto1.bounds().yMax(), dto2.bounds().yMax()),
                Math.min(dto1.bounds().zMin(), dto2.bounds().zMin()),
                Math.max(dto1.bounds().zMax(), dto2.bounds().zMax())
            );

            // Merge settings
            JSONObject mergedSettings = new JSONObject();
            mergeSettings(mergedSettings, dto1.settings(), dto2.settings());

            // Merge group permissions
            Map<String, Map<String, Boolean>> mergedGroupPerms = new HashMap<>(dto1.groupPermissions());
            dto2.groupPermissions().forEach((group, perms) -> {
                mergedGroupPerms.merge(group, perms, (existing, newPerms) -> {
                    Map<String, Boolean> merged = new HashMap<>(existing);
                    merged.putAll(newPerms);
                    return merged;
                });
            });

            // Create merged area using builder
            return Area.builder()
                .name(dto1.name() + "_" + dto2.name())
                .world(dto1.world())
                .coordinates(
                    mergedBounds.xMin(), mergedBounds.xMax(),
                    mergedBounds.yMin(), mergedBounds.yMax(),
                    mergedBounds.zMin(), mergedBounds.zMax()
                )
                .priority(Math.max(dto1.priority(), dto2.priority()))
                .settings(mergedSettings)
                .groupPermissions(mergedGroupPerms)
                .build();

        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "area_merge");
        }
    }

    /**
     * Merges settings from two areas using the most restrictive approach.
     * For boolean flags:
     * - true = allowed/unrestricted
     * - false = denied/restricted
     * We use AND (&&) to enforce the most restrictive combination:
     * - If either area restricts an action (false), the merged result is restricted
     * - Both areas must allow (true) for the merged result to allow
     * This ensures merged areas maintain the highest level of protection.
     *
     * @param target The target JSONObject to store merged settings
     * @param settings1 First area's settings
     * @param settings2 Second area's settings
     */
    private void mergeSettings(JSONObject target, JSONObject settings1, JSONObject settings2) {
        Set<String> allKeys = new HashSet<>();
        allKeys.addAll(settings1.keySet());
        allKeys.addAll(settings2.keySet());

        // Get merge mode from config, default to most restrictive (AND)
        boolean useMostRestrictive = plugin.getConfigManager().getBoolean(
            "areaSettings.useMostRestrictiveMerge", true);

        for (String key : allKeys) {
            // Default to true (unrestricted) if setting doesn't exist
            boolean value1 = settings1.optBoolean(key, true);
            boolean value2 = settings2.optBoolean(key, true);
            
            if (useMostRestrictive) {
                // AND operation - most restrictive approach
                target.put(key, value1 && value2);
            } else {
                // OR operation - least restrictive approach
                target.put(key, value1 || value2);
            }
        }
    }

    public void visualizeArea(Player player, Area area) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            stopVisualization(player);

            // Calculate visualization points more efficiently
            List<Vector3> points = calculateVisualizationPoints(area);
            Iterator<Vector3> iterator = points.iterator();

            TaskHandler task = plugin.getServer().getScheduler().scheduleRepeatingTask(plugin, () -> {
                if (!iterator.hasNext()) {
                    return;
                }

                // Spawn particles in batches for better performance
                for (int i = 0; i < 10 && iterator.hasNext(); i++) {
                    Vector3 point = iterator.next();
                    spawnParticle(player, point, new DustParticle(point, 255, 0, 0));
                }
            }, 5);

            visualizationTasks.put(player.getName(), task);
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "area_visualize");
        }
    }

    private List<Vector3> calculateVisualizationPoints(Area area) {
        List<Vector3> points = new ArrayList<>();
        int spacing = 2; // Adjust spacing between particles
        AreaDTO.Bounds bounds = area.toDTO().bounds();

        // Calculate edges more efficiently
        for (double x = bounds.xMin(); x <= bounds.xMax(); x += spacing) {
            addEdgePoints(points, x, bounds.yMin(), bounds.zMin(), bounds.yMax(), bounds.zMax());
        }
        
        for (double y = bounds.yMin(); y <= bounds.yMax(); y += spacing) {
            addEdgePoints(points, bounds.xMin(), y, bounds.zMin(), bounds.xMax(), bounds.zMax());
        }
        
        for (double z = bounds.zMin(); z <= bounds.zMax(); z += spacing) {
            addEdgePoints(points, bounds.xMin(), bounds.yMin(), z, bounds.xMax(), bounds.yMax());
        }

        Collections.shuffle(points); // Randomize for better visual effect
        return points;
    }

    private void addEdgePoints(List<Vector3> points, double x, double y, double z, double maxY, double maxZ) {
        points.add(new Vector3(x, y, z));
        points.add(new Vector3(x, y, maxZ));
        points.add(new Vector3(x, maxY, z));
        points.add(new Vector3(x, maxY, maxZ));
    }

    public void stopVisualization(Player player) {
        TaskHandler task = visualizationTasks.remove(player.getName());
        if (task != null) {
            task.cancel();
        }
    }

    private void spawnParticle(Player player, Vector3 point, DustParticle particle) {
        particle.setComponents(point.x, point.y, point.z);
        player.getLevel().addParticle(particle);
    }

    public void trackAreaEvent(String areaName, String eventType) {
        if (areaName == null || eventType == null) {
            throw new IllegalArgumentException("Area name and event type cannot be null");
        }

        try {
            // Get or create area statistics
            AreaStatistics stats = areaStats.computeIfAbsent(areaName, k -> {
                try {
                    return new AreaStatistics(plugin);
                } catch (SQLException e) {
                    throw new RuntimeException("Failed to create area statistics", e);
                }
            });

            // Increment the event counter
            stats.incrementEvent(eventType);

        } catch (Exception e) {
            plugin.getLogger().error("Failed to track area event: " + eventType + " for area: " + areaName, e);
        }
    }

    public AreaStatistics getAreaStats(String areaName) {
        if (areaName == null) {
            throw new IllegalArgumentException("Area name cannot be null");
        }

        try {
            return areaStats.computeIfAbsent(areaName, k -> {
                try {
                    return new AreaStatistics(plugin);
                } catch (SQLException e) {
                    throw new RuntimeException("Failed to create area statistics", e);
                }
            });
        } catch (Exception e) {
            plugin.getLogger().error("Failed to get area statistics for: " + areaName, e);
            throw new RuntimeException("Failed to get area statistics", e);
        }
    }

    /**
     * Loads all areas from the database
     */
    public void loadAreas() {
        if (plugin.isDebugMode()) {
            plugin.debug("Loading areas from database");
        }
        
        // Clear existing caches
        locationCache.invalidateAll();
        nameCache.invalidateAll();
        spatialIndex.clear();
        chunkAreaMap.clear();
        
        // Clear existing area collections
        writeLock.lock();
        try {
            areas.clear();
            areasByName.clear();
            worldAreas.clear();
            globalAreasByWorld.clear();
            
            long startTime = System.currentTimeMillis();
            
            try {
                int totalLoaded = 0;
                int globalLoaded = 0;
                
                // Load areas from database
                List<Area> loadedAreas = plugin.getDatabaseManager().loadAreas();
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Loaded " + loadedAreas.size() + " areas from database");
                }
                
                // Track areas with permissions that need to be preserved
                Map<String, Area> areasWithPermissions = new HashMap<>();
                
                // Process each area
                for (Area area : loadedAreas) {
                    try {
                        if (area == null) {
                            if (plugin.isDebugMode()) {
                                plugin.debug("Skipping null area found in database");
                            }
                            continue;
                        }
                        
                        totalLoaded++;
                        
                        // Detect if it's a global area
                        boolean isGlobal = isGlobalArea(area);
                        
                        if (isGlobal) {
                            globalLoaded++;
                            if (plugin.isDebugMode()) {
                                plugin.debug("Adding global area to memory: " + area.getName() + 
                                          " (world: " + area.getWorld() + ")");
                            }
                        }
                        
                        // No need to normalize toggle states here as it's done in the Area constructor
                        
                        // Add to appropriate collections
                        String world = area.getWorld();
                        if (isGlobal) {
                            globalAreasByWorld.put(world, area);
                        } else {
                            areas.add(area);
                            addToSpatialIndex(area);
                        }
                        
                        // Add to general collections
                        worldAreas.computeIfAbsent(world, k -> new ArrayList<>()).add(area);
                        areasByName.put(area.getName().toLowerCase(), area);
                        
                        // Remember this area has permissions if needed
                        areasWithPermissions.put(area.getName().toLowerCase(), area);
                        
                        // Register area in chunk map
                        registerArea(area);
                    } catch (Exception e) {
                        plugin.getLogger().error("Error processing area during load: " + 
                                              (area != null ? area.getName() : "null"), e);
                    }
                }
                
                // Log memory operations time
                long memoryOpTime = System.currentTimeMillis() - startTime;
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Areas processed into memory in " + memoryOpTime + "ms");
                    plugin.debug("Areas loaded into memory: " + totalLoaded + 
                              " (regular: " + (totalLoaded - globalLoaded) + 
                              ", global: " + globalLoaded + ")");
                }
            } catch (Exception e) {
                plugin.getLogger().error("Failed to load areas from database", e);
            }
        } finally {
            writeLock.unlock();
        }
    }

    public Area getAreaAt(Position position) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            if (position == null || position.getLevel() == null) {
                return null;
            }

            String world = position.getLevel().getName();
            double x = position.getX();
            double y = position.getY();
            double z = position.getZ(); // Initialize z coordinate

            // Use the highest priority area at the location
            List<Area> areasAtLocation = getAreasAtLocation(world, x, y, z);
            return areasAtLocation.isEmpty() ? null : areasAtLocation.get(0);
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "area_get_at");
        }
    }

    /**
     * Gets all areas that apply at a given position, sorted by priority in descending order.
     * The priority system determines which area's settings take precedence when areas overlap.
     * Areas with higher priority values override the settings of lower priority areas.
     * 
     * For example:
     * - Area A (priority 75) and Area B (priority 25) overlap at position P
     * - Area A's settings will be applied at position P, overriding Area B
     * - Both areas are still returned in the list, but sorted by priority
     *
     * @param position The position to check
     * @return List of applicable areas, sorted by priority (highest first)
     */
    public List<Area> getAllAreasAt(Position position) {
        if (position == null || position.getLevel() == null) {
            return new ArrayList<>();
        }
        
        return getAreasAtLocation(
            position.getLevel().getName(),
            position.getX(),
            position.getY(),
            position.getZ()
        );
    }

    /**
     * Gets all areas that are within range of any online player.
     * This helps optimize performance by only checking areas near players.
     */
    public List<Area> getActiveAreas() {
        Set<Area> activeAreas = new HashSet<>();
        Map<Integer, Level> loadedLevels = plugin.getServer().getLevels();

        // For each loaded world
        for (Level level : loadedLevels.values()) {
            String worldName = level.getName();
            
            // For each player in that world
            for (Player player : level.getPlayers().values()) {
                int playerChunkX = player.getChunkX();
                int playerChunkZ = player.getChunkZ();
                
                // Get chunks in radius around player
                for (int dx = -CHUNK_CHECK_RADIUS; dx <= CHUNK_CHECK_RADIUS; dx++) {
                    for (int dz = -CHUNK_CHECK_RADIUS; dz <= CHUNK_CHECK_RADIUS; dz++) {
                        int chunkX = playerChunkX + dx;
                        int chunkZ = playerChunkZ + dz;
                        
                        // Get areas containing this chunk
                        List<Area> areasInChunk = getAreasInChunk(worldName, chunkX, chunkZ);
                        activeAreas.addAll(areasInChunk);
                    }
                }
            }
        }

        if (plugin.isDebugMode()) {
            plugin.debug("Active areas found: " + activeAreas.size());
            for (Area area : activeAreas) {
                plugin.debug("  - " + area.getName() + " in world " + area.getWorld());
            }
        }

        return new ArrayList<>(activeAreas);
    }

    /**
     * Gets areas that overlap with a specific chunk
     */
    private List<Area> getAreasInChunk(String world, int chunkX, int chunkZ) {
        int blockX = chunkX << 4; // Convert chunk coords to block coords
        int blockZ = chunkZ << 4;
        
        return areas.stream()
            .filter(area -> area.toDTO().world().equals(world))
            .filter(area -> {
                var bounds = area.toDTO().bounds();
                return blockX <= bounds.xMax() && (blockX + 16) >= bounds.xMin() &&
                       blockZ <= bounds.zMax() && (blockZ + 16) >= bounds.zMin();
            })
            .sorted((a1, a2) -> Integer.compare(a2.getPriority(), a1.getPriority()))
            .toList();
    }

    /**
     * Gets all areas at a position, only checking areas near players
     */
    public List<Area> getAreasAtPosition(Position pos) {
        if (pos == null || pos.getLevel() == null) return Collections.emptyList();
        return getAreasAtLocation(pos.getLevel().getName(), pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * Gets the highest priority area at a position, only checking areas near players
     */
    public Area getHighestPriorityAreaAtPosition(Position pos) {
        if (pos == null || pos.getLevel() == null) return null;
        return getHighestPriorityArea(pos.getLevel().getName(), pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * Call this whenever an area is created or updated.
     */
    public void registerArea(Area area) {
        // Skip chunk registration for global areas - they apply to the entire world
        // This prevents excessive memory usage
        if (isGlobalArea(area)) {
            if (plugin.isDebugMode()) {
                plugin.debug("Skipping chunk registration for global area: " + area.getName());
            }
            return;
        }
        
        // Remove from existing chunk index first:
        unregisterArea(area);
        // Compute chunk bounds and add to chunkAreaMap
        for (long key : computeChunkKeys(area)) {
            chunkAreaMap.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(area);
        }
    }

    /**
     * Removes an area from chunkAreaMap.
     */
    public void unregisterArea(Area area) {
        // Skip for global areas as they aren't registered in the chunk map
        if (isGlobalArea(area)) {
            return;
        }
        
        for (Set<Area> set : chunkAreaMap.values()) {
            set.remove(area);
        }
    }

    /**
     * Returns areas within a 4-chunk radius around pos.
     * Adjust radius to your needs.
     */
    public Set<Area> getNearbyAreas(Position pos) {
        Set<Area> result = new HashSet<>();
        int baseX = pos.getFloorX() >> 4;
        int baseZ = pos.getFloorZ() >> 4;
        int radius = 4;
        for (int x = baseX - radius; x <= baseX + radius; x++) {
            for (int z = baseZ - radius; z <= baseZ + radius; z++) {
                long key = toLongKey(x, z);
                Set<Area> chunkAreas = chunkAreaMap.get(key);
                if (chunkAreas != null) {
                    result.addAll(chunkAreas);
                }
            }
        }
        return result;
    }

    /**
     * Helper to convert chunkX, chunkZ to a single long key.
     */
    private long toLongKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    /**
     * Determines chunk coordinates covered by the area.
     * Calculates all chunks that intersect with the area's bounding box.
     */
    @SuppressWarnings("unused")
    private List<Long> getCoveredChunks(Area area) {
        List<Long> keys = new ArrayList<>();
        AreaDTO.Bounds bounds = area.toDTO().bounds();
        
        // Skip processing for global areas
        if (isGlobalArea(area)) {
            return keys; // Return empty list for global areas
        }
        
        // Convert block coordinates to chunk coordinates
        int minChunkX = bounds.xMin() >> 4;
        int maxChunkX = bounds.xMax() >> 4;
        int minChunkZ = bounds.zMin() >> 4;
        int maxChunkZ = bounds.zMax() >> 4;
        
        // Safety check: limit the number of chunks to prevent memory issues
        // 100 x 100 chunks is a very large area (1.6M blocks) and should be sufficient
        int chunkXSpan = maxChunkX - minChunkX + 1;
        int chunkZSpan = maxChunkZ - minChunkZ + 1;
        
        final int MAX_CHUNK_SPAN = 100; // Maximum chunks in any direction
        
        if (chunkXSpan > MAX_CHUNK_SPAN || chunkZSpan > MAX_CHUNK_SPAN) {
            if (plugin.isDebugMode()) {
                plugin.debug("Area too large for chunk registration: " + area.getName() + 
                           " - " + chunkXSpan + "x" + chunkZSpan + " chunks");
            }
            
            // Reduce the span to a reasonable size
            if (chunkXSpan > MAX_CHUNK_SPAN) {
                int halfExcess = (chunkXSpan - MAX_CHUNK_SPAN) / 2;
                minChunkX += halfExcess;
                maxChunkX = minChunkX + MAX_CHUNK_SPAN - 1;
            }
            
            if (chunkZSpan > MAX_CHUNK_SPAN) {
                int halfExcess = (chunkZSpan - MAX_CHUNK_SPAN) / 2;
                minChunkZ += halfExcess;
                maxChunkZ = minChunkZ + MAX_CHUNK_SPAN - 1;
            }
            
            if (plugin.isDebugMode()) {
                plugin.debug("Reduced chunk span to " + MAX_CHUNK_SPAN + "x" + MAX_CHUNK_SPAN + 
                           " for area: " + area.getName());
            }
        }
        
        // Iterate through all chunks in the area's bounds
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) {
                keys.add(toLongKey(x, z));
            }
        }
        
        return keys;
    }

    /**
     * Efficiently determines if an event at a specific position should be processed
     * based on proximity to players and area containment
     *
     * @param pos The position to check
     * @param ignoreGlobal If true, even global areas require player proximity
     * @return true if the event should be processed
     */
    public boolean shouldProcessEvent(Position pos, boolean ignoreGlobal) {
        if (pos == null || pos.getLevel() == null) {
            return false;
        }
        
        String worldName = pos.getLevel().getName();

        // Get chunk coordinates of the event location
        int eventChunkX = pos.getChunkX();
        int eventChunkZ = pos.getChunkZ();

        // Skip if chunk is not loaded
        if (!pos.getLevel().isChunkLoaded(eventChunkX, eventChunkZ)) {
            return false;
        }

        // Check for global area first - most efficient path
        Area globalArea = globalAreasByWorld.get(worldName);
        if (globalArea != null && !ignoreGlobal) {
            // For global areas, still require a player to be online in the world
            // This prevents processing events in empty worlds with global protection
            return !pos.getLevel().getPlayers().isEmpty();
        }

        // For all other cases, require player proximity
        // This is a significant optimization that prevents processing events
        // in unpopulated chunks
        return isNearAnyPlayer(pos.x, pos.z);
    }

    /**
     * Master check that includes the specific area being checked
     */
    public boolean shouldProcessEvent(Position pos, Area area) {
        // If it's a global area, use less strict checking
        boolean ignoreGlobal = (area != null && area.isGlobal());
        return shouldProcessEvent(pos, ignoreGlobal);
    }

    /**
     * Normalizes toggle states for all areas in an optimized batch operation
     * @return The number of areas successfully normalized
     */
    public int normalizeAllToggleStates() {
        List<Area> allAreas = new ArrayList<>(areas);
        plugin.debug("Normalizing toggle states for all areas with optimized batch updates");
        
        int normalizedCount = 0;
        
        // This operation can be intensive, so use a high-frequency context
        plugin.getRecentSaveTracker().beginHighFrequencyContext();
        
        try {
            // Process areas in batches to avoid memory pressure
            int batchSize = 50; // Larger batch size since normalization is less intensive
            for (int i = 0; i < allAreas.size(); i += batchSize) {
                int end = Math.min(i + batchSize, allAreas.size());
                List<Area> batch = allAreas.subList(i, end);
                
                for (Area area : batch) {
                    try {
                        // Normalize the toggle states
                        area.normalizeToggleStates();
                        
                        // Save the toggle states to database
                        if (area.saveToggleStates()) {
                            normalizedCount++;
                        }
                    } catch (Exception e) {
                        plugin.getLogger().error("Failed to normalize toggle states for area: " 
                            + area.getName(), e);
                    }
                }
                
                if (plugin.isDebugMode() && i + batchSize < allAreas.size()) {
                    plugin.debug("Normalized batch " + (i/batchSize + 1) + 
                        ", progress: " + (i + batchSize) + "/" + allAreas.size() + 
                        " areas (" + normalizedCount + " succeeded)");
                }
            }
        } finally {
            // Always end high-frequency context
            plugin.getRecentSaveTracker().endHighFrequencyContext();
        }
        
        plugin.debug("Completed normalization of toggle states: " + normalizedCount + "/" 
            + allAreas.size() + " areas");
        
        return normalizedCount;
    }

    /**
     * Recreates an area in the database with the same settings but filtered toggle states.
     * This is useful when you want to clean up toggle states without losing area settings.
     * 
     * @param area The area to recreate
     * @param newToggleStates Optional new toggle states to use instead of the area's current toggle states
     * @return The recreated area
     */
    public Area recreateArea(Area area, JSONObject newToggleStates) {
        if (area == null) return null;
        
        // Check if this is a permission-only operation by checking both flags
        // This ensures we detect active permission operations correctly
        boolean isPermissionOperation = isPermissionOperationInProgress(area.getName());
                                      
        if (isPermissionOperation) {
            if (plugin.isDebugMode()) {
                plugin.debug("SKIPPING area recreation during permission-only operation for: " + area.getName());
            }
            plugin.getLogger().info("[Debug] Skipping area recreation during permission operation for: " + area.getName());
            return area; // Return existing area without recreation for permission-only operations
        }
        
        plugin.getLogger().info("[Debug] Recreating area: " + area.getName());
        
        // Track all permission operations for this area to ensure proper preservation
        Set<String> permissionOperations = Area.getPermissionOperations();
        if (permissionOperations != null && !permissionOperations.isEmpty()) {
            if (plugin.isDebugMode()) {
                plugin.debug("Permission operations in progress during area recreation: " + 
                             String.join(", ", permissionOperations));
            }
            
            // If we detect active permission operations, skip recreation to avoid disrupting them
            if (permissionOperations.contains(area.getName().toLowerCase() + "_permissions")) {
                if (plugin.isDebugMode()) {
                    plugin.debug("SKIPPING recreation due to active permission operations for: " + area.getName());
                }
                plugin.getLogger().info("[Debug] SKIPPING recreation due to active permission operations for: " + area.getName());
                return area;
            }
        }
        
        String areaName = area.getName();
        
        try {
            // CRITICAL: Check if this is a permission-only operation
            if (isPermissionOperationInProgress(areaName)) {
                if (plugin.isDebugMode()) {
                    plugin.debug("DETECTED permission operation in progress for: " + areaName);
                    plugin.debug("Skipping area recreation during permission operation");
                }
                plugin.getLogger().info("[Debug] DETECTED permission operation in progress for: " + areaName);
                // Return the existing area without recreation during active permission operations
                return area;
            }
            
            // Convert the area to a DTO for easier manipulation
            AreaDTO currentDTO = area.toDTO();
            
            if (plugin.isDebugMode()) {
                plugin.debug("Recreating area: " + areaName);
                plugin.debug("Current toggle states: " + currentDTO.toggleStates());
                plugin.debug("Current player permissions: " + 
                            (currentDTO.playerPermissions() != null ? 
                             currentDTO.playerPermissions().size() + " players" : "null"));
            }
            
            // First, preserve the player permissions before removing the area
            Map<String, Map<String, Boolean>> preservedPlayerPermissions;
            
            // Always load directly from database for latest permissions
            try {
                plugin.getLogger().info("[Debug] Getting player permissions directly from database for area: " + areaName);
                preservedPlayerPermissions = plugin.getPermissionOverrideManager()
                    .getAllPlayerPermissionsFromDatabase(areaName);
                plugin.getLogger().info("[Debug] Using fresh database permissions: " + 
                    (preservedPlayerPermissions != null ? preservedPlayerPermissions.size() : 0) + " players");
                
                // Log some permission details for debugging
                if (preservedPlayerPermissions != null && !preservedPlayerPermissions.isEmpty()) {
                    for (Map.Entry<String, Map<String, Boolean>> entry : preservedPlayerPermissions.entrySet()) {
                        plugin.getLogger().info("[Debug]   Player " + entry.getKey() + ": " + 
                            (entry.getValue() != null ? entry.getValue().size() : 0) + " permissions");
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().info("[Debug] Failed to load player permissions from database: " + e.getMessage());
                // Use the permissions from the original area as a backup
                preservedPlayerPermissions = area.getPlayerPermissions();
                plugin.getLogger().info("[Debug] Falling back to area player permissions: " + 
                    (preservedPlayerPermissions != null ? preservedPlayerPermissions.size() : 0) + " players");
            }
            
            // Create a backup of permissions to ensure they're not lost
            Map<String, Map<String, Boolean>> backupPlayerPermissions = null;
            
            if (preservedPlayerPermissions != null && !preservedPlayerPermissions.isEmpty()) {
                backupPlayerPermissions = new HashMap<>();
                for (Map.Entry<String, Map<String, Boolean>> entry : 
                        preservedPlayerPermissions.entrySet()) {
                    if (entry.getValue() != null) {
                        backupPlayerPermissions.put(entry.getKey(), new HashMap<>(entry.getValue()));
                    }
                }
            }
            
            // Create filtered toggle states - either use the provided ones or filter the current ones
            JSONObject filteredToggleStates;
            if (newToggleStates != null) {
                filteredToggleStates = new JSONObject(newToggleStates.toString());
            } else {
                JSONObject originalToggleStates = currentDTO.toggleStates();
                filteredToggleStates = getFilteredToggleStates(originalToggleStates);
            }
            
            // Create a new area with the preserved data
            Area newArea = recreateAreaFromDTO(currentDTO, filteredToggleStates, preservedPlayerPermissions);
            plugin.getLogger().info("[Debug] Player permissions set to: " + newArea.getPlayerPermissions());
            
            // Ensure player permissions are explicitly set from preserved ones
            if (preservedPlayerPermissions != null && !preservedPlayerPermissions.isEmpty()) {
                // Set player permissions explicitly to ensure they're properly stored
                for (Map.Entry<String, Map<String, Boolean>> playerEntry : preservedPlayerPermissions.entrySet()) {
                    String playerName = playerEntry.getKey();
                    Map<String, Boolean> permissions = playerEntry.getValue();
                    
                    if (permissions != null && !permissions.isEmpty()) {
                        if (plugin.isDebugMode()) {
                            plugin.debug("Explicitly setting " + permissions.size() + 
                                      " permissions for player " + playerName + " in recreated area");
                        }
                        
                        // Set each permission individually
                        for (Map.Entry<String, Boolean> permEntry : permissions.entrySet()) {
                            newArea.setPlayerPermission(playerName, permEntry.getKey(), permEntry.getValue());
                            plugin.getLogger().info("[Debug] Setting permission " + permEntry.getKey() + 
                                " = " + permEntry.getValue() + " for player " + playerName);
                        }
                    }
                }
            }
            
            if (plugin.isDebugMode()) {
                plugin.debug("Area recreated successfully: " + newArea.getName());
            }
            plugin.getLogger().info("[Debug] Area recreated successfully: " + newArea.getName());
            
            return newArea;
        } catch (Exception e) {
            plugin.getLogger().error("Failed to recreate area: " + area.getName(), e);
            // Return the original area if recreation fails
            return area;
        }
    }
    
    /**
     * Helper method to recreate an area from a DTO with modified toggle states and permissions
     */
    private Area recreateAreaFromDTO(AreaDTO dto, JSONObject toggleStates, Map<String, Map<String, Boolean>> playerPermissions) {
        return Area.builder()
            .name(dto.name())
            .world(dto.world())
            .coordinates(
                dto.bounds().xMin(), dto.bounds().xMax(),
                dto.bounds().yMin(), dto.bounds().yMax(),
                dto.bounds().zMin(), dto.bounds().zMax()
            )
            .priority(dto.priority())
            .showTitle(dto.showTitle())
            .enterMessage(dto.enterMessage())
            .leaveMessage(dto.leaveMessage())
            .settings(dto.settings())
            .toggleStates(toggleStates)
            .defaultToggleStates(dto.defaultToggleStates())
            .inheritedToggleStates(dto.inheritedToggleStates())
            .groupPermissions(dto.groupPermissions())
            .inheritedPermissions(dto.inheritedPermissions())
            .playerPermissions(playerPermissions)
            .trackPermissions(dto.trackPermissions())
            .potionEffects(dto.potionEffects())
            .build();
    }
    
    /**
     * Checks if the permission cache might be incomplete for an area
     * This helps determine if we should load permissions directly from the database
     */
    private boolean isPermissionCachePossiblyIncomplete(Area area) {
        if (area == null) return true;
        
        try {
            // Get permissions from cache
            String areaName = area.getName();
            Map<String, Map<String, Boolean>> cachedPerms = 
                plugin.getPermissionOverrideManager().getAllPlayerPermissions(areaName);
            
            // Quick check of current operations
            Set<String> permOps = Area.getPermissionOperations();
            boolean permOpsInProgress = permOps != null && !permOps.isEmpty();
            
            // Count permissions in cache
            int cachedPermCount = 0;
            if (cachedPerms != null) {
                for (Map<String, Boolean> playerPerms : cachedPerms.values()) {
                    if (playerPerms != null) {
                        cachedPermCount += playerPerms.size();
                    }
                }
            }
            
            // If we have active permission operations or very few cached permissions, 
            // the cache might be incomplete
            return permOpsInProgress || cachedPermCount < 5;
        } catch (Exception e) {
            // If we encounter any error, assume cache might be incomplete
            plugin.getLogger().error("Error checking permission cache completeness", e);
            return true;
        }
    }

    /**
     * Computes chunk keys for area registration
     */
    protected List<Long> computeChunkKeys(Area area) {
        List<Long> keys = new ArrayList<>();
        AreaDTO.Bounds bounds = area.toDTO().bounds();
        
        // Skip processing for global areas
        if (isGlobalArea(area)) {
            return keys; // Return empty list for global areas
        }
        
        // Convert block coordinates to chunk coordinates
        int minChunkX = bounds.xMin() >> 4;
        int maxChunkX = bounds.xMax() >> 4;
        int minChunkZ = bounds.zMin() >> 4;
        int maxChunkZ = bounds.zMax() >> 4;
        
        // Safety check: limit the number of chunks to prevent memory issues
        int chunkXSpan = maxChunkX - minChunkX + 1;
        int chunkZSpan = maxChunkZ - minChunkZ + 1;
        
        final int MAX_CHUNK_SPAN = 100; // Maximum chunks in any direction
        
        if (chunkXSpan > MAX_CHUNK_SPAN || chunkZSpan > MAX_CHUNK_SPAN) {
            if (plugin.isDebugMode()) {
                plugin.debug("Area too large for chunk registration: " + area.getName() + 
                           " - " + chunkXSpan + "x" + chunkZSpan + " chunks");
            }
            
            // Reduce the span to a reasonable size
            if (chunkXSpan > MAX_CHUNK_SPAN) {
                int halfExcess = (chunkXSpan - MAX_CHUNK_SPAN) / 2;
                minChunkX += halfExcess;
                maxChunkX = minChunkX + MAX_CHUNK_SPAN - 1;
            }
            
            if (chunkZSpan > MAX_CHUNK_SPAN) {
                int halfExcess = (chunkZSpan - MAX_CHUNK_SPAN) / 2;
                minChunkZ += halfExcess;
                maxChunkZ = minChunkZ + MAX_CHUNK_SPAN - 1;
            }
            
            if (plugin.isDebugMode()) {
                plugin.debug("Reduced chunk span to " + MAX_CHUNK_SPAN + "x" + MAX_CHUNK_SPAN + 
                           " for area: " + area.getName());
            }
        }
        
        // Iterate through all chunks in the area's bounds
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) {
                keys.add(toLongKey(x, z));
            }
        }
        return keys;
    }

    public List<Area> getAreasInWorld(String worldName) {
        return areas.stream()
            .filter(area -> area.getWorld().equalsIgnoreCase(worldName))
            .toList();
    }

    /**
     * Updates toggle states for an area
     * 
     * @param area The area to update
     * @param toggleStates Map of toggle states to apply
     * @return Updated area
     */
    public Area updateAreaToggleStates(Area area, Map<String, Boolean> toggleStates) {
        if (area == null || toggleStates == null || toggleStates.isEmpty()) {
            return area;
        }
        
        // Prevent recursion during area toggle state updates
        Set<String> inProgress = updatingAreas.get();
        String areaKey = area.getName().toLowerCase() + "_toggles";
        if (inProgress.contains(areaKey)) {
            // We're already updating toggle states for this area in the current call stack
            if (plugin.isDebugMode()) {
                plugin.debug("Preventing recursive toggle states update for area " + area.getName());
            }
            return area; // Return original area to avoid recursion
        }
        inProgress.add(areaKey);
        
        writeLock.lock();
        try {
            // Create a copy of the area
            Area updatedArea = recreateArea(area);
            
            // Apply all toggle states to the area
            for (Map.Entry<String, Boolean> entry : toggleStates.entrySet()) {
                updatedArea.setToggleState(entry.getKey(), entry.getValue());
            }

            // Update database
            try {
                plugin.getDatabaseManager().updateArea(updatedArea);
                plugin.getDatabaseManager().flushChanges();
            } catch (Exception e) {
                plugin.getLogger().error("Failed to update area in database", e);
                return area; // Return original area if database update fails
            }

            // Update area in memory
            if (isGlobalArea(updatedArea)) {
                globalAreasByWorld.put(updatedArea.getWorld(), updatedArea);
            } else {
                areas.remove(area);
                removeFromSpatialIndex(area);
                areas.add(updatedArea);
                addToSpatialIndex(updatedArea);
            }

            // Update in name lookup map
            areasByName.put(updatedArea.getName().toLowerCase(), updatedArea);

            // Update in world areas map
            List<Area> worldAreasList = worldAreas.get(updatedArea.getWorld());
            if (worldAreasList != null) {
                worldAreasList.removeIf(a -> a.getName().equalsIgnoreCase(area.getName()));
                worldAreasList.add(updatedArea);
            }

            // Update chunk registration
            unregisterArea(area);
            registerArea(updatedArea);

            // Trigger permission cache updates
            if (plugin.getListenerManager() != null) {
                plugin.getListenerManager().reload();
            }

            return updatedArea;
        } finally {
            writeLock.unlock();
            // Clean up thread-local to prevent memory leaks
            inProgress.remove(areaKey);
        }
    }
    
    /**
     * Updates a single potion effect for an area
     * 
     * @param area The area to update
     * @param effectName The name of the potion effect
     * @param strength The strength value (0-255)
     * @return The updated area
     */
    public Area updateAreaPotionEffect(Area area, String effectName, int strength) {
        if (area == null || effectName == null) {
            return area;
        }
        
        // Prevent recursion during potion effect updates
        Set<String> inProgress = updatingAreas.get();
        String areaKey = area.getName().toLowerCase() + "_potion_" + effectName;
        if (inProgress.contains(areaKey)) {
            // We're already updating this potion effect for this area in the current call stack
            if (plugin.isDebugMode()) {
                plugin.debug("Preventing recursive potion effect update for area " + area.getName() + ", effect: " + effectName);
            }
            return area; // Return original area to avoid recursion
        }
        inProgress.add(areaKey);
        
        try {
            // Set the potion effect strength directly
            area.setPotionEffectStrength(effectName, strength);
            
            // Update toggle state to match the potion effect status
            if (!effectName.endsWith("Strength")) {
                String permissionNode = effectName;
                if (!permissionNode.startsWith("gui.permissions.toggles.")) {
                    permissionNode = "gui.permissions.toggles." + permissionNode;
                }
                
                // Enable/disable toggle based on strength
                area.setToggleState(permissionNode, strength > 0);
            }
            
            // Update the area in the database
            try {
                plugin.getDatabaseManager().updateArea(area);
            } catch (Exception e) {
                plugin.getLogger().error("Failed to update potion effect for area: " + area.getName(), e);
            }
            
            return area;
        } finally {
            // Clean up thread-local to prevent memory leaks
            inProgress.remove(areaKey);
        }
    }
    
    /**
     * Updates multiple potion effects for an area
     * 
     * @param area The area to update
     * @param effectStrengths Map of effect names to strength values
     * @return The updated area
     */
    public Area updateAreaPotionEffects(Area area, Map<String, Integer> effectStrengths) {
        if (area == null || effectStrengths == null || effectStrengths.isEmpty()) {
            return area;
        }
        
        // Prevent recursion during potion effects updates
        Set<String> inProgress = updatingAreas.get();
        String areaKey = area.getName().toLowerCase() + "_potions_all";
        if (inProgress.contains(areaKey)) {
            // We're already updating potion effects for this area in the current call stack
            if (plugin.isDebugMode()) {
                plugin.debug("Preventing recursive potion effects update for area " + area.getName());
            }
            return area; // Return original area to avoid recursion
        }
        inProgress.add(areaKey);
        
        try {
            // Apply each potion effect
            for (Map.Entry<String, Integer> effect : effectStrengths.entrySet()) {
                area.setPotionEffectStrength(effect.getKey(), effect.getValue());
                
                // Update toggle state to match the potion effect status
                if (!effect.getKey().endsWith("Strength")) {
                    String permissionNode = effect.getKey();
                    if (!permissionNode.startsWith("gui.permissions.toggles.")) {
                        permissionNode = "gui.permissions.toggles." + permissionNode;
                    }
                    
                    // Enable/disable toggle based on strength
                    area.setToggleState(permissionNode, effect.getValue() > 0);
                }
            }
            
            // Update the area in the database
            try {
                plugin.getDatabaseManager().updateArea(area);
            } catch (Exception e) {
                plugin.getLogger().error("Failed to update potion effects for area: " + area.getName(), e);
            }
            
            return area;
        } finally {
            // Clean up thread-local to prevent memory leaks
            inProgress.remove(areaKey);
        }
    }
    
    /**
     * Invalidates the cache for a specific area
     * 
     * @param areaName The name of the area whose cache should be invalidated
     */
    public void invalidateAreaCache(String areaName) {
        if (areaName == null || areaName.isEmpty()) {
            return;
        }

        // Prevent infinite recursion
        ThreadLocal<Set<String>> recursionGuard = new ThreadLocal<>();
        Set<String> processed = recursionGuard.get();
        if (processed == null) {
            processed = new HashSet<>();
            recursionGuard.set(processed);
        } else if (processed.contains(areaName)) {
            return;
        }
        processed.add(areaName);

        try {
            // Clear caches
            locationCache.invalidateAll();
            nameCache.invalidate(areaName.toLowerCase());

            // Clear area-specific caches
            Area area = areasByName.get(areaName.toLowerCase());
            if (area != null) {
                area.clearCaches();
                area.emergencyClearCaches();
                
                // Refresh listeners if needed
                if (plugin.getListenerManager() != null) {
                    plugin.getListenerManager().reload();
                }
            }
        } finally {
            // Clean up thread-local to prevent memory leaks
            processed.remove(areaName);
            if (processed.isEmpty()) {
                recursionGuard.remove();
            }
        }
    }

    /**
     * Completely reloads all areas from the database.
     * This is a nuclear option to fix cache inconsistencies.
     * This method now includes safeguards to prevent data loss.
     */
    public void reloadAllAreasFromDatabase() {
        try {
            if (plugin.isDebugMode()) {
                plugin.debug("Reloading ALL areas from database - with data loss prevention");
            }
            
            // Create backups of current areas before clearing anything
            Map<String, Area> backupAreasByName = new HashMap<>(areasByName);
            List<Area> backupAreas = new ArrayList<>(areas);
            Map<String, Area> backupGlobalAreasByWorld = new HashMap<>(globalAreasByWorld);
            Map<String, List<Area>> backupWorldAreas = new HashMap<>();
            for (Map.Entry<String, List<Area>> entry : worldAreas.entrySet()) {
                backupWorldAreas.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
            
            // Backup the chunk area map
            Map<Long, Set<Area>> backupChunkAreaMap = new HashMap<>();
            for (Map.Entry<Long, Set<Area>> entry : chunkAreaMap.entrySet()) {
                backupChunkAreaMap.put(entry.getKey(), new HashSet<>(entry.getValue()));
            }
            
            if (plugin.isDebugMode()) {
                plugin.debug("Created backups of " + backupAreas.size() + " areas and " + 
                             backupGlobalAreasByWorld.size() + " global areas before reload");
            }
            
            // Clear all caches
            writeLock.lock();
            try {
                // Clear all in-memory collections first
                areasByName.clear();
                areas.clear();
                globalAreasByWorld.clear();
                worldAreas.clear();
                chunkAreaMap.clear();
                spatialIndex.clear();
                
                // Clear all caches
                locationCache.invalidateAll();
                nameCache.invalidateAll();
                
                // Reload all areas from database - using existing loadAreas method
                loadAreas();
                
                // SAFETY CHECK: If no areas were loaded, restore from backup
                if (areas.isEmpty() && !backupAreas.isEmpty()) {
                    plugin.getLogger().warning("No areas were loaded from database but " + 
                                            backupAreas.size() + " areas were in memory. Restoring from backup!");
                    
                    // Restore all collections from backup
                    areasByName.putAll(backupAreasByName);
                    areas.addAll(backupAreas);
                    globalAreasByWorld.putAll(backupGlobalAreasByWorld);
                    
                    // Restore world areas
                    for (Map.Entry<String, List<Area>> entry : backupWorldAreas.entrySet()) {
                        worldAreas.put(entry.getKey(), new ArrayList<>(entry.getValue()));
                    }
                    
                    // Restore chunk area map
                    for (Map.Entry<Long, Set<Area>> entry : backupChunkAreaMap.entrySet()) {
                        chunkAreaMap.put(entry.getKey(), new HashSet<>(entry.getValue()));
                    }
                    
                    // Rebuild spatial index
                    for (Area area : areas) {
                        if (!area.toDTO().bounds().isGlobal()) {
                            addToSpatialIndex(area);
                        }
                    }
                    
                    plugin.getLogger().info("Successfully restored " + areas.size() + 
                                           " areas from memory backup after failed database reload");
                } else if (plugin.isDebugMode()) {
                    plugin.debug("Reloaded " + areas.size() + " areas from database successfully");
                }
                
                // Verify no duplicates exist
                Set<String> uniqueNames = new HashSet<>();
                for (Area area : areas) {
                    if (!uniqueNames.add(area.getName().toLowerCase())) {
                        plugin.getLogger().warning("Duplicate area detected after reload: " + area.getName());
                    }
                }
            } finally {
                writeLock.unlock();
            }
            
            plugin.getLogger().info("Area reload complete - all areas have been refreshed");
        } catch (Exception e) {
            plugin.getLogger().error("Error reloading areas from database", e);
        }
    }
    
    /**
     * Invalidates all application-wide caches for all areas.
     * This can be used as a last resort to fix cache inconsistencies.
     */
    public void invalidateAllCaches() {
        if (plugin.isDebugMode()) {
            plugin.debug("Invalidating ALL area caches system-wide");
        }
        
        // Invalidate all areas individually
        for (Area area : getAllAreas()) {
            invalidateAllCaches(area);
        }
        
        // Clear all location-based caches
        locationCache.invalidateAll();
        nameCache.invalidateAll();
        
        // Clear all permission caches
        if (plugin.getPermissionOverrideManager() != null) {
            plugin.getPermissionOverrideManager().getPermissionChecker().refreshPermissionCaches();
        }
        
        if (plugin.isDebugMode()) {
            plugin.debug("All area caches have been invalidated");
        }
    }

    /**
     * Updates multiple areas in an optimized batch operation
     * @param areas The list of areas to update
     * @param updateToggleStates Whether to update toggle states
     * @param updateDatabase Whether to update the areas in database
     * @param syncPermissions Whether to synchronize permissions
     * @return The number of areas successfully updated
     */
    public int batchUpdateAreas(List<Area> areas, boolean updateToggleStates, 
                                   boolean updateDatabase, boolean syncPermissions) {
        if (areas == null || areas.isEmpty()) {
            return 0;
        }
        
        plugin.debug("Starting optimized batch update of " + areas.size() + " areas");
        long startTime = System.currentTimeMillis();
        
        int updatedCount = 0;
        
        // Mark as high-frequency operation to reduce logging
        plugin.getRecentSaveTracker().beginHighFrequencyContext();
        
        try {
            // Process areas in batches to avoid memory pressure
            int batchSize = 20;
            for (int i = 0; i < areas.size(); i += batchSize) {
                int end = Math.min(i + batchSize, areas.size());
                List<Area> batch = areas.subList(i, end);
                
                for (Area area : batch) {
                    try {
                        // Use the optimized batch update for each area
                        if (area.batchUpdate(updateToggleStates, updateDatabase, syncPermissions)) {
                            updatedCount++;
                            
                            // Also invalidate caches for this area
                            invalidateAllCaches(area);
                        }
                    } catch (Exception e) {
                        plugin.getLogger().error("Failed to update area: " + area.getName(), e);
                    }
                }
                
                if (plugin.isDebugMode() && i + batchSize < areas.size()) {
                    plugin.debug("Updated batch " + (i/batchSize + 1) + 
                        ", progress: " + (i + batchSize) + "/" + areas.size() + 
                        " areas (" + updatedCount + " succeeded)");
                }
            }
        } finally {
            // Always end high-frequency context
            plugin.getRecentSaveTracker().endHighFrequencyContext();
        }
        
        long duration = System.currentTimeMillis() - startTime;
        plugin.debug("Completed batch update of areas: " + updatedCount + "/" 
            + areas.size() + " areas in " + duration + "ms");
        
        return updatedCount;
    }

    /**
     * Saves all areas to the database in an optimized batch operation
     * @return The number of areas successfully saved
     */
    public int saveAllAreas() {
        List<Area> allAreas = new ArrayList<>(areas);
        plugin.debug("Saving all areas with optimized batch update");
        // Update toggle states and database, but skip permission sync for performance
        return batchUpdateAreas(allAreas, true, true, false);
    }

    /**
     * Synchronizes permissions for all areas in an optimized batch operation
     * @return The number of areas successfully synchronized
     */
    public int synchronizeAllPermissions() {
        List<Area> allAreas = new ArrayList<>(areas);
        plugin.debug("Synchronizing permissions for all areas with optimized batch update");
        // Skip toggle states and database updates, only sync permissions
        return batchUpdateAreas(allAreas, false, false, true);
    }

    /**
     * Normalizes toggle states for all areas to ensure consistent prefixing
     * Call this method during plugin startup to fix inconsistencies
     */
    public void normalizeAllAreaToggleStates() {
        boolean isInitialization = plugin.getListenerManager() == null;
        
        if (plugin.isDebugMode()) {
            plugin.debug("Normalizing toggle states for all areas (initialization: " + isInitialization + ")");
        }
        
        // Use the optimized batch method
        int normalizedCount = normalizeAllToggleStates();
        
        // After normalization, invalidate all caches
        if (isInitialization) {
            // During initialization, just invalidate basic caches without trying to access listeners
            locationCache.invalidateAll();
            nameCache.invalidateAll();
        } else {
            // Full invalidation during normal operation
            invalidateAllCaches();
        }
        
        if (plugin.isDebugMode()) {
            plugin.debug("All area toggle states normalized and saved: " + normalizedCount + " areas");
        }
    }

    /**
     * Filter toggle states to only keep valid ones
     * @param originalToggleStates The original toggle states
     * @return Filtered toggle states
     */
    private JSONObject getFilteredToggleStates(JSONObject originalToggleStates) {
        JSONObject filteredToggleStates = new JSONObject();
        
        // Only keep valid toggle states
        for (String key : originalToggleStates.keySet()) {
            // Keep all toggle states except for obviously invalid ones (like null or empty)
            if (key != null && !key.isEmpty()) {
                if (originalToggleStates.isNull(key)) {
                    if (plugin.isDebugMode()) {
                        plugin.debug("Filtering out null toggle value for key: " + key);
                    }
                    continue;
                }
                
                // Keep the toggle state regardless of whether it's "valid" according to the system
                // This ensures custom toggle states from forms are preserved
                try {
                    // Check if it's a boolean value
                    if (originalToggleStates.has(key)) {
                        Object value = originalToggleStates.get(key);
                        if (value instanceof Boolean) {
                            filteredToggleStates.put(key, originalToggleStates.getBoolean(key));
                        } else if (value instanceof Number) {
                            // For strength values
                            filteredToggleStates.put(key, originalToggleStates.getInt(key));
                        } else if (value instanceof String) {
                            // For string values, try to convert to boolean
                            try {
                                filteredToggleStates.put(key, Boolean.parseBoolean((String)value));
                            } catch (Exception e) {
                                // Keep as string if can't convert to boolean
                                filteredToggleStates.put(key, value);
                            }
                        } else {
                            // For other types, just convert to string
                            filteredToggleStates.put(key, String.valueOf(value));
                        }
                    }
                } catch (Exception e) {
                    if (plugin.isDebugMode()) {
                        plugin.debug("Error processing toggle key: " + key + ", error: " + e.getMessage());
                    }
                }
            } else {
                if (plugin.isDebugMode()) {
                    plugin.debug("Filtering out invalid toggle key: " + key);
                }
            }
        }
        
        return filteredToggleStates;
    }
    
    /**
     * Recreates an area by deleting and creating a new one with the same name but updated settings.
     * This is a more robust way to ensure all settings, including toggles, are properly persisted.
     * 
     * @param area The area with updated settings to recreate
     * @return The newly created area
     */
    public Area recreateArea(Area area) {
        return recreateArea(area, null);
    }

    /**
     * Marks that a permission operation is in progress for an area
     * This helps prevent unnecessary area recreation during permission updates
     * 
     * @param areaName The name of the area
     */
    public void markPermissionOperationInProgress(String areaName) {
        if (areaName == null || areaName.isEmpty()) return;
        
        // Access ThreadLocal directly to prevent potential recursion
        ThreadLocal<Set<String>> permissionOperationsLocal = Area.getPermissionOperationsThreadLocal();
        if (permissionOperationsLocal != null) {
            Set<String> permOps = permissionOperationsLocal.get();
            if (permOps != null) {
                String key = areaName.toLowerCase() + "_permissions";
                permOps.add(key);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Marked permission operation in progress for area: " + areaName);
                }
            }
        }
    }
    
    /**
     * Unmarks a permission operation for an area
     * Call this when the permission operation is complete
     * 
     * @param areaName The name of the area
     */
    public void unmarkPermissionOperation(String areaName) {
        if (areaName == null || areaName.isEmpty()) return;
        
        // Access ThreadLocal directly to prevent potential recursion
        // Instead of: Set<String> permOps = Area.getPermissionOperations();
        ThreadLocal<Set<String>> permissionOperations = Area.getPermissionOperationsThreadLocal();
        if (permissionOperations != null) {
            Set<String> permOps = permissionOperations.get();
            if (permOps != null) {
                String key = areaName.toLowerCase() + "_permissions";
                permOps.remove(key);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Unmarked permission operation for area: " + areaName);
                }
            }
        }
    }
    
    /**
     * Checks if a permission operation is in progress for an area
     * 
     * @param areaName The name of the area
     * @return true if a permission operation is in progress
     */
    public boolean isPermissionOperationInProgress(String areaName) {
        if (areaName == null || areaName.isEmpty()) return false;
        
        // Check area-specific permission operations with direct ThreadLocal access
        ThreadLocal<Set<String>> permissionOperationsLocal = Area.getPermissionOperationsThreadLocal();
        if (permissionOperationsLocal != null) {
            Set<String> permOps = permissionOperationsLocal.get();
            if (permOps != null && !permOps.isEmpty()) {
                String key = areaName.toLowerCase() + "_permissions";
                return permOps.contains(key);
            }
        }
        
        return false;
    }

    /**
     * Updates player permissions for an area
     * This is an optimized method that handles player permission updates separately from area updates
     * 
     * @param areaName The name of the area to update
     * @param playerName The name of the player whose permissions are being updated
     * @param permissions The new permissions to apply
     * @return True if successful, false otherwise
     */
    public boolean updatePlayerPermissions(String areaName, String playerName, Map<String, Boolean> permissions) {
        // Use the direct method for all permission updates
        return directUpdatePlayerPermissions(areaName, playerName, permissions);
    }

    /**
     * Direct method to update player permissions with minimal overhead
     * This method bypasses the normal area update process for efficiency and reliability
     * 
     * @param areaName The area name
     * @param playerName The player name
     * @param permissionUpdates The new permission values to apply
     * @return True if successful
     */
    public boolean directUpdatePlayerPermissions(String areaName, String playerName, Map<String, Boolean> permissionUpdates) {
        if (areaName == null || playerName == null || permissionUpdates == null || permissionUpdates.isEmpty()) {
            return false;
        }
        
        boolean markedOperation = false;
        try {
            // Check if the operation is already in progress to prevent recursion
            if (!isPermissionOperationInProgress(areaName)) {
                // First mark that we're in a permission-only operation to prevent other systems from interfering
                markPermissionOperationInProgress(areaName);
                markedOperation = true;
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Direct update of " + permissionUpdates.size() + " permissions for " + 
                              playerName + " in area " + areaName);
                }
            } else if (plugin.isDebugMode()) {
                plugin.debug("Permission operation already in progress for " + areaName + ", skipping recursive operation");
                return false;
            }
            
            // 1. Delete existing permissions first to ensure clean state
            boolean deleted = false;
            try {
                // Use direct database access instead of area synchronization
                try {
                    plugin.getPermissionOverrideManager().deletePlayerPermissions(areaName, playerName);
                    deleted = true;
                } catch (Exception e) {
                    plugin.getLogger().error("Error deleting player permissions: " + e.getMessage());
                }
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Deleted existing permissions for " + playerName + " in area " + areaName);
                }
            } catch (Exception e) {
                plugin.getLogger().error("Could not delete existing permissions: " + e.getMessage());
                // Continue anyway - we'll overwrite them
            }
            
            // 2. Insert new permissions directly using the database manager
            boolean success = true;
            try {
                // Save directly to database
                try {
                    // Use the permission manager's method to save permissions
                    plugin.getPermissionOverrideManager().setPlayerPermissions(areaName, playerName, permissionUpdates);
                } catch (Exception e) {
                    plugin.getLogger().error("Error saving player permissions: " + e.getMessage());
                    success = false;
                }
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Explicitly saving player permissions for player " + playerName + " in area " + areaName);
                    plugin.debug("  Permission count: " + permissionUpdates.size());
                    plugin.debug("  Permissions: " + permissionUpdates);
                }
                
                // Get the area and update in-memory permissions too
                Area area = getArea(areaName);
                if (area != null) {
                    // Update in-memory permissions
                    Map<String, Map<String, Boolean>> playerPermissions = area.getPlayerPermissions();
                    Map<String, Boolean> playerPerms = playerPermissions.computeIfAbsent(playerName, k -> new HashMap<>());
                    
                    // Clear existing permissions
                    playerPerms.clear();
                    
                    // Add new permissions
                    playerPerms.putAll(permissionUpdates);
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Updated in-memory player permissions map: " + playerPermissions);
                    }
                }
            } catch (Exception e) {
                success = false;
                plugin.getLogger().error("Error updating permissions: " + e.getMessage(), e);
            }
            
            // 3. Ensure the memory is in sync with database
            try {
                // Force checkpoint to ensure all changes are written to disk
                try {
                    plugin.getPermissionOverrideManager().forceWalCheckpoint();
                } catch (Exception e) {
                    plugin.getLogger().error("Error flushing permissions: " + e.getMessage());
                }
                
                // Invalidate all caches to ensure fresh data is loaded
                plugin.getPermissionOverrideManager().invalidateCache(areaName);
                
                // Force clear area cache
                nameCache.invalidate(areaName.toLowerCase());
                
                // Force clear protectionListener caches
                plugin.debug("ProtectionListener caches completely cleared");
                plugin.getListenerManager().getProtectionListener().cleanup();
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Invalidating permission cache for area: " + areaName);
                }
            } catch (Exception e) {
                plugin.getLogger().error("Error updating in-memory permissions: " + e.getMessage());
                // Not critical - database update is what matters most
            }
            
            // 4. Verify the permissions are set correctly
            if (success && plugin.isDebugMode()) {
                try {
                    Map<String, Boolean> verifyPerms = plugin.getPermissionOverrideManager()
                        .getPlayerPermissions(areaName, playerName);
                    
                    if (verifyPerms != null) {
                        plugin.debug("Retrieved permissions for player " + playerName + " in area " + areaName);
                        plugin.debug("Retrieved " + verifyPerms.size() + " permissions for player " + playerName + " in area " + areaName);
                        
                        plugin.debug("  Verification - retrieved permissions: " + verifyPerms.size() + " permissions");
                        plugin.debug("  Verification - permissions content: " + verifyPerms);
                        
                        // Direct database query check
                        plugin.debug("  Direct database query for player permissions:");
                        try {
                            plugin.getPermissionOverrideManager().debugDumpPlayerPermissions(areaName, playerName);
                        } catch (Exception e) {
                            plugin.getLogger().error("Error dumping player permissions: " + e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().error("Error verifying permissions: " + e.getMessage());
                }
            }
            
            return success;
        } finally {
            // Only unmark if we were the ones who marked it
            if (markedOperation) {
                unmarkPermissionOperation(areaName);
            }
        }
    }
    
    /**
     * Direct method to update group permissions with minimal overhead
     * This method bypasses the normal area update process for efficiency and reliability
     * 
     * @param areaName The area name
     * @param groupName The group name
     * @param permissionUpdates The new permission values to apply
     * @return True if successful
     */
    public boolean directUpdateGroupPermissions(String areaName, String groupName, Map<String, Boolean> permissionUpdates) {
        if (areaName == null || groupName == null || permissionUpdates == null || permissionUpdates.isEmpty()) {
            return false;
        }
        
        boolean markedOperation = false;
        try {
            // Check if the operation is already in progress to prevent recursion
            if (!isPermissionOperationInProgress(areaName)) {
                // First mark that we're in a permission-only operation to prevent other systems from interfering
                markPermissionOperationInProgress(areaName);
                markedOperation = true;
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Direct update of " + permissionUpdates.size() + " permissions for group " + 
                              groupName + " in area " + areaName);
                }
            } else if (plugin.isDebugMode()) {
                plugin.debug("Permission operation already in progress for " + areaName + ", skipping recursive operation");
                return false;
            }
            
            // 1. Delete existing permissions first to ensure clean state
            try {
                // First try to get the area
                Area area = getArea(areaName);
                if (area != null) {
                    // Get existing permissions and clear them
                    Map<String, Map<String, Boolean>> groupPermissions = area.getInternalGroupPermissions();
                    if (groupPermissions != null && groupPermissions.containsKey(groupName)) {
                        // Clear the permissions in memory by creating a new empty map
                        groupPermissions.put(groupName, new HashMap<>());
                        
                        // Sync changes to database - this will delete all existing permissions
                        plugin.getPermissionOverrideManager().synchronizePermissions(area, 
                            PermissionOverrideManager.SyncDirection.TO_DATABASE);
                        
                        if (plugin.isDebugMode()) {
                            plugin.debug("Deleted existing permissions for group " + groupName + " in area " + areaName);
                        }
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().error("Could not delete group permissions: " + e.getMessage());
                // Continue anyway - we'll overwrite them
            }
            
            // 2. Insert new permissions directly
            boolean success = true;
            try {
                // Get the area again to make sure we have a fresh version
                Area area = getArea(areaName);
                if (area != null) {
                    try {
                        // Apply group permissions directly using Area's method
                        area.setGroupPermissions(groupName, permissionUpdates);
                        
                        if (plugin.isDebugMode()) {
                            plugin.debug("Successfully updated " + permissionUpdates.size() + 
                                      " permissions for group " + groupName + " in area " + areaName);
                        }
                    } catch (Exception e) {
                        success = false;
                        plugin.getLogger().error("Failed to set group permissions: " + e.getMessage());
                    }
                } else {
                    success = false;
                    plugin.getLogger().error("Could not find area: " + areaName);
                }
            } catch (Exception e) {
                success = false;
                plugin.getLogger().error("Error updating group permissions: " + e.getMessage(), e);
            }
            
            // 3. Ensure the memory is in sync with database
            try {
                // Force reload the area with fresh data from database
                nameCache.invalidate(areaName.toLowerCase());
                Area area = getArea(areaName);
                
                // Force invalidate any caches to ensure fresh data
                if (area != null) {
                    invalidateAllCaches(area);
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Updated in-memory group permissions for area " + areaName);
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().error("Error updating in-memory group permissions: " + e.getMessage());
                // Not critical - database update is what matters most
            }
            
            return success;
        } finally {
            // Only unmark if we were the ones who marked it
            if (markedOperation) {
                unmarkPermissionOperation(areaName);
            }
        }
    }
    
    /**
     * Direct method to update track permissions with minimal overhead
     * This method bypasses the normal area update process for efficiency and reliability
     * 
     * @param areaName The area name
     * @param trackName The track name
     * @param permissionUpdates The new permission values to apply
     * @return True if successful
     */
    public boolean directUpdateTrackPermissions(String areaName, String trackName, Map<String, Boolean> permissionUpdates) {
        if (areaName == null || trackName == null || permissionUpdates == null || permissionUpdates.isEmpty()) {
            return false;
        }
        
        boolean markedOperation = false;
        try {
            // Check if the operation is already in progress to prevent recursion
            if (!isPermissionOperationInProgress(areaName)) {
                // First mark that we're in a permission-only operation to prevent other systems from interfering
                markPermissionOperationInProgress(areaName);
                markedOperation = true;
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Direct update of " + permissionUpdates.size() + " permissions for track " + 
                              trackName + " in area " + areaName);
                }
            } else if (plugin.isDebugMode()) {
                plugin.debug("Permission operation already in progress for " + areaName + ", skipping recursive operation");
                return false;
            }
            
            // 1. Delete existing permissions first to ensure clean state
            try {
                // First try to get the area
                Area area = getArea(areaName);
                if (area != null) {
                    // Get existing permissions and clear them
                    Map<String, Map<String, Boolean>> trackPermissions = area.getInternalTrackPermissions();
                    if (trackPermissions != null && trackPermissions.containsKey(trackName)) {
                        // Clear the permissions in memory by creating a new empty map
                        trackPermissions.put(trackName, new HashMap<>());
                        
                        // Sync changes to database - this will delete all existing permissions
                        plugin.getPermissionOverrideManager().synchronizePermissions(area, 
                            PermissionOverrideManager.SyncDirection.TO_DATABASE);
                        
                        if (plugin.isDebugMode()) {
                            plugin.debug("Deleted existing permissions for track " + trackName + " in area " + areaName);
                        }
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().error("Could not delete track permissions: " + e.getMessage());
                // Continue anyway - we'll overwrite them
            }
            
            // 2. Insert new permissions directly
            boolean success = true;
            try {
                // Get the area again to make sure we have a fresh version
                Area area = getArea(areaName);
                if (area != null) {
                    try {
                        // Apply track permissions directly using Area's method
                        area.setTrackPermissions(trackName, permissionUpdates);
                        
                        if (plugin.isDebugMode()) {
                            plugin.debug("Successfully updated " + permissionUpdates.size() + 
                                      " permissions for track " + trackName + " in area " + areaName);
                        }
                    } catch (Exception e) {
                        success = false;
                        plugin.getLogger().error("Failed to set track permissions: " + e.getMessage());
                    }
                } else {
                    success = false;
                    plugin.getLogger().error("Could not find area: " + areaName);
                }
            } catch (Exception e) {
                success = false;
                plugin.getLogger().error("Error updating track permissions: " + e.getMessage(), e);
            }
            
            // 3. Ensure the memory is in sync with database
            try {
                // Force reload the area with fresh data from database
                nameCache.invalidate(areaName.toLowerCase());
                Area area = getArea(areaName);
                
                // Force invalidate any caches to ensure fresh data
                if (area != null) {
                    invalidateAllCaches(area);
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Updated in-memory track permissions for area " + areaName);
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().error("Error updating in-memory track permissions: " + e.getMessage());
                // Not critical - database update is what matters most
            }
            
            return success;
        } finally {
            // Only unmark if we were the ones who marked it
            if (markedOperation) {
                unmarkPermissionOperation(areaName);
            }
        }
    }
    
    /**
     * Updates group permissions for an area
     * This method uses the direct approach to avoid excessive processing and permission resets
     * 
     * @param areaName The name of the area to update
     * @param groupName The name of the group whose permissions are being updated
     * @param permissions The new permissions to apply
     * @return True if successful, false otherwise
     */
    public boolean updateGroupPermissions(String areaName, String groupName, Map<String, Boolean> permissions) {
        // Use the direct method for all permission updates
        return directUpdateGroupPermissions(areaName, groupName, permissions);
    }
    
    /**
     * Updates track permissions for an area
     * This method uses the direct approach to avoid excessive processing and permission resets
     * 
     * @param areaName The name of the area to update
     * @param trackName The name of the track whose permissions are being updated
     * @param permissions The new permissions to apply
     * @return True if successful, false otherwise
     */
    public boolean updateTrackPermissions(String areaName, String trackName, Map<String, Boolean> permissions) {
        // Use the direct method for all permission updates
        return directUpdateTrackPermissions(areaName, trackName, permissions);
    }

    // Recreate an area from its DTO
    public Area recreateArea(AreaDTO dto) {
        boolean isPermissionOperation = isPermissionOperationInProgress(dto.name());
        
        if (isPermissionOperation) {
            plugin.getLogger().info("[Debug] Skipping area recreation during permission operation for: " + dto.name());
            return getArea(dto.name());
        }
        
        plugin.getLogger().info("[Debug] Recreating area from DTO: " + dto.name());
        
        // Load player permissions from database first
        Map<String, Map<String, Boolean>> playerPermissions = null;
        try {
            plugin.getLogger().info("[Debug] Getting player permissions directly from database for area: " + dto.name());
            playerPermissions = plugin.getPermissionOverrideManager().getAllPlayerPermissionsFromDatabase(dto.name());
            plugin.getLogger().info("[Debug] Using fresh database permissions: " + 
                (playerPermissions != null ? playerPermissions.size() : 0) + " players");
        } catch (Exception e) {
            plugin.getLogger().info("[Debug] Failed to load player permissions from database: " + e.getMessage());
            // If database fails, fall back to permissions from DTO
            playerPermissions = dto.playerPermissions();
            plugin.getLogger().info("[Debug] Falling back to DTO player permissions: " + 
                      (playerPermissions != null ? playerPermissions.size() : 0) + " players");
        }

        // Similar for group permissions
        Map<String, Map<String, Boolean>> groupPermissions = null;
        try {
            groupPermissions = plugin.getPermissionOverrideManager().getAllPlayerPermissionsFromDatabase(dto.name());
            plugin.getLogger().info("[Debug] Using fresh database group permissions: " + 
                (groupPermissions != null ? groupPermissions.size() : 0) + " groups");
        } catch (Exception e) {
            plugin.getLogger().info("[Debug] Failed to load group permissions from database: " + e.getMessage());
            // If database fails, fall back to permissions from DTO
            groupPermissions = dto.groupPermissions();
        }

        // And for track permissions
        Map<String, Map<String, Boolean>> trackPermissions = null;
        try {
            trackPermissions = plugin.getPermissionOverrideManager().getAllPlayerPermissionsFromDatabase(dto.name());
            plugin.getLogger().info("[Debug] Using fresh database track permissions: " + 
                (trackPermissions != null ? trackPermissions.size() : 0) + " tracks");
        } catch (Exception e) {
            plugin.getLogger().info("[Debug] Failed to load track permissions from database: " + e.getMessage());
            // If database fails, fall back to permissions from DTO
            trackPermissions = dto.trackPermissions();
        }
        
        // Create new area with fresh permissions from database
        Area area = Area.builder()
            .name(dto.name())
            .world(dto.world())
            .coordinates(
                dto.bounds().xMin(), dto.bounds().xMax(),
                dto.bounds().yMin(), dto.bounds().yMax(),
                dto.bounds().zMin(), dto.bounds().zMax()
            )
            .priority(dto.priority())
            .showTitle(dto.showTitle())
            .enterMessage(dto.enterMessage())
            .leaveMessage(dto.leaveMessage())
            .settings(dto.settings())
            .toggleStates(dto.toggleStates())
            .defaultToggleStates(dto.defaultToggleStates())
            .inheritedToggleStates(dto.inheritedToggleStates())
            .groupPermissions(groupPermissions)
            .playerPermissions(playerPermissions)
            .trackPermissions(trackPermissions)
            .potionEffects(dto.potionEffects())
            .build();
        
        plugin.getLogger().info("[Debug] Player permissions set to: " + area.getPlayerPermissions());
        
        // Make sure the area is saved with correct permissions
        areasByName.put(dto.name().toLowerCase(), area);
        nameCache.invalidate(dto.name());
        
        return area;
    }
}

