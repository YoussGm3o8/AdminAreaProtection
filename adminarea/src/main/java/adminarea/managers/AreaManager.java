package adminarea.managers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaDTO;
import adminarea.event.AreaPermissionUpdateEvent;
import adminarea.area.AreaBuilder;
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

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;

public class AreaManager implements IAreaManager {
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

            // Initialize default toggle states for all available toggles if not set
            if (plugin.isDebugMode()) {
                plugin.debug("Initializing default toggle states for area " + area.getName());
                plugin.debug("Current toggle states: " + area.toDTO().toggleStates());
            }

            PermissionToggle.getDefaultToggles().forEach(toggle -> {
                String key = toggle.getPermissionNode();
                if (!key.startsWith("gui.permissions.toggles.")) {
                    key = "gui.permissions.toggles." + key;
                }
                if (!area.getToggleState(key)) {  // Changed from hasToggleState to getToggleState
                    area.setToggleState(key, toggle.getDefaultValue());
                }
            });

            if (plugin.isDebugMode()) {
                plugin.debug("Toggle states after initialization: " + area.toDTO().toggleStates());
            }

            // Detect global area
            boolean isGlobal = isGlobalArea(area);
            
            if (plugin.isDebugMode()) {
                plugin.debug("Adding area to memory: " + area.getName() + 
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
                plugin.debug("Successfully added area: " + area.getName() + 
                          (isGlobal ? " (global, world: " + area.getWorld() + ")" : ""));
                plugin.debug("Current areas in memory: " + areasByName.size() + 
                          " (global: " + globalAreasByWorld.size() + ", local: " + areas.size() + ")");
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

    @Override
    public void updateArea(Area area) {
        if (area == null) return;
        
        if (plugin.isDebugMode()) {
            plugin.debug("Updating area: " + area.getName());
            plugin.debug("  Player permissions: " + area.getPlayerPermissions());
            plugin.debug("  Toggle states: " + area.toDTO().toggleStates());
        }
        
        try {
            // Synchronize toggle states before saving
            area = area.synchronizeToggleStates();
            
            if (plugin.isDebugMode()) {
                plugin.debug("  After synchronization - Toggle states: " + area.toDTO().toggleStates());
                plugin.debug("  After synchronization - Settings: " + area.toDTO().settings());
            }
            
            // First update in database
            plugin.getDatabaseManager().updateArea(area);
            
            // Update in memory
            Area oldArea = areasByName.get(area.getName().toLowerCase());
            String world = area.getWorld();
            
            // Check if we need to re-register the area (bounds changed)
            boolean needsReregister = false;
            if (oldArea != null) {
                needsReregister = !oldArea.getBounds().equals(area.getBounds()) ||
                                  !oldArea.getWorld().equals(world);
            }
            
            // Update in all collections to avoid duplication
            boolean isGlobal = isGlobalArea(area);
            
            // Remove old area from appropriate collections
            if (oldArea != null) {
                if (isGlobalArea(oldArea)) {
                    // Remove from global areas if it was previously a global area
                    globalAreasByWorld.remove(oldArea.getWorld());
                } else {
                    // Remove from areas list if it was a regular area
                    areas.remove(oldArea);
                }
                
                // Remove old area from world areas list
                List<Area> worldAreaList = worldAreas.get(oldArea.getWorld());
                if (worldAreaList != null) {
                    worldAreaList.remove(oldArea);
                }
                
                // Unregister old area to clean up spatial indexes
                unregisterArea(oldArea);
            }
            
            // Add the updated area to the appropriate collections
            if (isGlobal) {
                globalAreasByWorld.put(world, area);
            } else {
                areas.add(area);
                addToSpatialIndex(area);
            }
            
            // Update in world areas collection
            worldAreas.computeIfAbsent(world, k -> new ArrayList<>()).add(area);
            
            // Update in name lookup map
            areasByName.put(area.getName().toLowerCase(), area);
            
            // Re-register in chunk map
            registerArea(area);
            
            // Fire AreaPermissionUpdateEvent if permissions changed
            if (oldArea != null && !oldArea.getPlayerPermissions().equals(area.getPlayerPermissions())) {
                plugin.getServer().getPluginManager().callEvent(
                    new AreaPermissionUpdateEvent(
                        area,
                        PermissionToggle.Category.ALL,
                        null,
                        oldArea.getPlayerPermissions(),
                        area.getPlayerPermissions()
                    )
                );
            }
            
            // Clear caches to ensure fresh data is used
            locationCache.invalidateAll();
            nameCache.invalidate(area.getName().toLowerCase());
            
            if (plugin.isDebugMode()) {
                plugin.debug("Area updated successfully: " + area.getName());
                plugin.debug("  Player permissions after update: " + area.getPlayerPermissions());
                
                // Verify in database
                try {
                    Area freshArea = plugin.getDatabaseManager().loadArea(area.getName());
                    if (freshArea != null) {
                        plugin.debug("  Verified area from database:");
                        plugin.debug("  Player permissions from DB: " + freshArea.getPlayerPermissions());
                        plugin.debug("  Toggle states from DB: " + freshArea.toDTO().toggleStates());
                        plugin.debug("  Settings from DB: " + freshArea.toDTO().settings());
                    }
                } catch (Exception e) {
                    plugin.getLogger().error("Failed to verify area update in database", e);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().error("Failed to update area: " + area.getName(), e);
        }
    }

    /**
     * Invalidates all caches for the specified area
     */
    @SuppressWarnings("unused")
    private void invalidateAreaCaches(Area area) {
        if (area == null) return;
        
        // Reload area from database to get fresh data
        try {
            Area freshArea = plugin.getDatabaseManager().loadAreas().stream()
                .filter(a -> a.getName().equals(area.getName()))
                .findFirst()
                .orElse(area);
            
            // Update in memory without firing another event
            areasByName.put(area.getName(), freshArea);
            
            // Fire update event ONLY if permissions actually changed
            if (!area.getPlayerPermissions().equals(freshArea.getPlayerPermissions()) ||
                !area.getGroupPermissions().equals(freshArea.getGroupPermissions())) {
                
                AreaPermissionUpdateEvent event = new AreaPermissionUpdateEvent(
                    freshArea,                         // Area
                    PermissionToggle.Category.ALL,     // Category
                    null,                             // Player (null for full update)
                    area.getGroupPermissions(),        // Old permissions
                    freshArea.getGroupPermissions()    // New permissions
                );
                plugin.getServer().getPluginManager().callEvent(event);
            }
            
        } catch (Exception e) {
            plugin.getLogger().error("Failed to reload area data: " + area.getName(), e);
        }
    }

    @Override
    public Area getArea(String name) {
        Area area = areasByName.get(name.toLowerCase());
        if (plugin.isDebugMode() && area != null) {
            plugin.debug("Retrieved area " + name + ":");
            plugin.debug("  Player permissions: " + area.getPlayerPermissions());
            plugin.debug("  Toggle states: " + area.toDTO().toggleStates());
        }
        return area;
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

    /** Reloads all areas from storage */
    public void reloadAreas() {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            if (plugin.isDebugMode()) {
                plugin.debug("Starting area reload...");
            }

            // Clear existing data
            areas.clear();
            areasByName.clear();
            globalAreasByWorld.clear();
            locationCache.invalidateAll();
            nameCache.invalidateAll();

            // Stop all active visualizations
            visualizationTasks.forEach((playerName, taskId) -> {
                plugin.getServer().getScheduler().cancelTask(taskId.getTaskId());
            });
            visualizationTasks.clear();

            // Reload areas from database
            try {
                List<Area> loadedAreas = databaseManager.loadAreas();
                if (plugin.isDebugMode()) {
                    plugin.debug("Loaded " + loadedAreas.size() + " areas from database");
                }

                for (Area area : loadedAreas) {
                    if (plugin.isDebugMode()) {
                        plugin.debug("Processing area: " + area.getName());
                        plugin.debug("  Player permissions: " + area.getPlayerPermissions());
                        plugin.debug("  Toggle states: " + area.toDTO().toggleStates());
                    }

                    if (isGlobalArea(area)) {
                        globalAreasByWorld.put(area.getWorld(), area);
                        if (plugin.isDebugMode()) {
                            plugin.debug("  Added as global area for world: " + area.getWorld());
                        }
                    } else {
                        areas.add(area);
                        addToSpatialIndex(area);
                        if (plugin.isDebugMode()) {
                            plugin.debug("  Added as local area");
                        }
                    }
                    areasByName.put(area.getName().toLowerCase(), area);

                    // Register area in chunkAreaMap
                    registerArea(area);
                }

                if (plugin.isDebugMode()) {
                    plugin.debug("Area reload complete:");
                    plugin.debug("  Total areas: " + loadedAreas.size());
                    plugin.debug("  Global areas: " + globalAreasByWorld.size());
                    plugin.debug("  Local areas: " + areas.size());
                    for (Area area : loadedAreas) {
                        plugin.debug("  Area '" + area.getName() + "':");
                        plugin.debug("    Player permissions: " + area.getPlayerPermissions());
                        plugin.debug("    Toggle states: " + area.toDTO().toggleStates());
                    }
                }

                plugin.getLogger().info("Successfully reloaded " + loadedAreas.size() + " areas");
            } catch (Exception e) {
                plugin.getLogger().error("Failed to reload areas from database", e);
                throw new RuntimeException("Failed to reload areas", e);
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "area_reload");
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
     * Normalizes toggle states for all areas to ensure consistent prefixing
     * Call this method during plugin startup to fix inconsistencies
     */
    public void normalizeAllAreaToggleStates() {
        if (plugin.isDebugMode()) {
            plugin.debug("Normalizing toggle states for all areas");
        }
        
        // Process each area
        for (Area area : getAllAreas()) {
            area.normalizeToggleStates();
            
            // Save each area individually
            try {
                plugin.getDatabaseManager().saveArea(area);
            } catch (Exception e) {
                plugin.getLogger().error("Failed to save area " + area.getName() + " after normalizing toggle states", e);
            }
        }
        
        if (plugin.isDebugMode()) {
            plugin.debug("All area toggle states normalized and saved");
        }
    }

    /**
     * Recreates an area by deleting and creating a new one with the same name but updated settings.
     * This is a more robust way to ensure all settings, including toggles, are properly persisted.
     * 
     * @param area The area with updated settings to recreate
     * @return The newly created area
     */
    public Area recreateArea(Area area) {
        if (area == null) return null;
        
        if (plugin.isDebugMode()) {
            plugin.debug("Recreating area: " + area.getName());
            plugin.debug("  Toggle states before recreation: " + area.toDTO().toggleStates());
            plugin.debug("  Settings before recreation: " + area.toDTO().settings());
            plugin.debug("  Potion effects before recreation: " + area.toDTO().potionEffects());
        }
        
        try {
            // Synchronize toggle states first to ensure we have all the latest changes
            area = area.synchronizeToggleStates();
            
            // Get all the area information we need to recreate it
            AreaDTO currentDTO = area.toDTO();
            String areaName = currentDTO.name();
            
            if (plugin.isDebugMode()) {
                plugin.debug("  Toggle states after synchronization: " + currentDTO.toggleStates());
                plugin.debug("  Settings after synchronization: " + currentDTO.settings());
                plugin.debug("  Potion effects after synchronization: " + currentDTO.potionEffects());
            }
            
            // Remove the area from memory maps
            if (isGlobalArea(area)) {
                globalAreasByWorld.remove(area.getWorld());
            } else {
                areas.remove(area);
                removeFromSpatialIndex(area);
            }
            
            // Remove from name lookup map
            areasByName.remove(areaName.toLowerCase());
            
            // Delete from database
            try {
                databaseManager.deleteArea(areaName);
            } catch (DatabaseException e) {
                plugin.getLogger().error("Failed to delete area for recreation: " + areaName, e);
                // Continue with recreation even if database delete fails
            }
            
            // Clear caches
            locationCache.invalidateAll();
            nameCache.invalidate(areaName.toLowerCase());
            
            // Unregister area from chunkAreaMap
            unregisterArea(area);
            
            // Filter out invalid toggle states
            JSONObject filteredToggleStates = new JSONObject();
            JSONObject originalToggleStates = currentDTO.toggleStates();
            
            // Only keep valid toggle states
            for (String key : originalToggleStates.keySet()) {
                // Keep all toggle states except for obviously invalid ones (like null or empty)
                if (key != null && !key.isEmpty()) {
                    if (originalToggleStates.isNull(key)) {
                        if (plugin.isDebugMode()) {
                            plugin.debug("  Filtering out null toggle value for key: " + key);
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
                            plugin.debug("  Error processing toggle key: " + key + ", error: " + e.getMessage());
                        }
                    }
                } else {
                    if (plugin.isDebugMode()) {
                        plugin.debug("  Filtering out invalid toggle key: " + key);
                    }
                }
            }
            
            if (plugin.isDebugMode()) {
                plugin.debug("  Original toggle states: " + originalToggleStates);
                plugin.debug("  Filtered toggle states: " + filteredToggleStates);
            }
            
            // Ensure potion effects are properly preserved
            JSONObject potionEffects = new JSONObject(currentDTO.potionEffects().toString());
            
            if (plugin.isDebugMode()) {
                plugin.debug("  Preserved potion effects: " + potionEffects);
            }
            
            // Create a new area with the same settings but filtered toggle states
            Area newArea = Area.builder()
                .name(currentDTO.name())
                .world(currentDTO.world())
                .coordinates(
                    currentDTO.bounds().xMin(), currentDTO.bounds().xMax(),
                    currentDTO.bounds().yMin(), currentDTO.bounds().yMax(),
                    currentDTO.bounds().zMin(), currentDTO.bounds().zMax()
                )
                .priority(currentDTO.priority())
                .showTitle(currentDTO.showTitle())
                .enterMessage(currentDTO.enterMessage())
                .leaveMessage(currentDTO.leaveMessage())
                .settings(currentDTO.settings())
                .toggleStates(filteredToggleStates)
                .defaultToggleStates(currentDTO.defaultToggleStates())
                .inheritedToggleStates(currentDTO.inheritedToggleStates())
                .groupPermissions(currentDTO.groupPermissions())
                .inheritedPermissions(currentDTO.inheritedPermissions())
                .playerPermissions(currentDTO.playerPermissions())
                .trackPermissions(currentDTO.trackPermissions())
                .potionEffects(potionEffects)
                .build();
            
            if (plugin.isDebugMode()) {
                plugin.debug("New area built with:");
                plugin.debug("  Toggle states: " + newArea.toDTO().toggleStates());
                plugin.debug("  Potion effects: " + newArea.toDTO().potionEffects());
            }
            
            // Before saving, ensure toggle states and potion effects are populated
            if (newArea.toDTO().toggleStates().length() == 0 && filteredToggleStates.length() > 0) {
                plugin.debug("WARNING: Toggle states were lost during area creation!");
                
                // Try to manually apply the toggle states
                for (String key : filteredToggleStates.keySet()) {
                    boolean value = filteredToggleStates.getBoolean(key);
                    newArea.setToggleState(key, value);
                }
            }
            
            if (newArea.toDTO().potionEffects().length() == 0 && potionEffects.length() > 0) {
                plugin.debug("WARNING: Potion effects were lost during area creation!");
                
                // Try to manually apply the potion effects - this depends on how your potion effects are stored
                // You may need to customize this part based on your implementation
                try {
                    // Use reflection to access the setPotionEffects method if available
                    java.lang.reflect.Method method = newArea.getClass().getMethod("setPotionEffects", JSONObject.class);
                    method.invoke(newArea, potionEffects);
                } catch (Exception e) {
                    plugin.debug("Could not restore potion effects: " + e.getMessage());
                }
            }
            
            // Save the new area to the database
            if (plugin.isDebugMode()) {
                plugin.debug("Saving area to database with toggle states: " + newArea.toDTO().toggleStates());
                plugin.debug("Saving area to database with potion effects: " + newArea.toDTO().potionEffects());
            }
            
            databaseManager.saveArea(newArea);
            
            // Add the new area to memory maps
            addArea(newArea);
            
            if (plugin.isDebugMode()) {
                plugin.debug("Area recreated successfully: " + newArea.getName());
                plugin.debug("  Toggle states after recreation: " + newArea.toDTO().toggleStates());
                plugin.debug("  Settings after recreation: " + newArea.toDTO().settings());
                plugin.debug("  Potion effects after recreation: " + newArea.toDTO().potionEffects());
                
                // Verify in database
                try {
                    Area freshArea = databaseManager.loadArea(newArea.getName());
                    if (freshArea != null) {
                        plugin.debug("  Verified area from database:");
                        plugin.debug("  Toggle states from DB: " + freshArea.toDTO().toggleStates());
                        plugin.debug("  Settings from DB: " + freshArea.toDTO().settings());
                        plugin.debug("  Potion effects from DB: " + freshArea.toDTO().potionEffects());
                    }
                } catch (Exception e) {
                    plugin.getLogger().error("Failed to verify area recreation in database", e);
                }
            }
            
            return newArea;
        } catch (Exception e) {
            plugin.getLogger().error("Failed to recreate area: " + area.getName(), e);
            // Try to keep the original area if recreation fails
            try {
                // Make sure the area is still in the system
                if (!hasArea(area.getName())) {
                    addArea(area);
                }
            } catch (Exception ex) {
                plugin.getLogger().error("Failed to restore original area after failed recreation", ex);
            }
            return area;
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
}
