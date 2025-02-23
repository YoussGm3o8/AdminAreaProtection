package adminarea.managers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaDTO;
import adminarea.area.AreaBuilder;
import adminarea.exception.DatabaseException;
import adminarea.interfaces.IAreaManager;
import adminarea.stats.AreaStatistics;
import cn.nukkit.Player;
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
    private final Map<String, TaskHandler> visualizationTasks = new HashMap<>();
    private final Map<String, AreaStatistics> areaStats = new HashMap<>();
    private final Map<String, Area> areasByName = new HashMap<>();
    private final List<Area> areas = new ArrayList<>();
    private final Map<String, Area> globalAreasByWorld = new HashMap<>(); // Changed to single area per world
    
    // Add cache for area queries with smaller size for global areas
    private final Cache<String, List<Area>> locationCache;
    private final Cache<String, Area> nameCache;
    private final Map<String, Map<Integer, List<Area>>> spatialIndex;
    private static final int CACHE_SIZE = 1000; // Reduced cache size
    private static final long CACHE_EXPIRY = TimeUnit.MINUTES.toMillis(2); // Reduced expiry time
    private static final int CHUNK_CHECK_RADIUS = 4;
    private final DatabaseManager databaseManager;

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
    
    private void addToSpatialIndex(Area area) {
        AreaDTO.Bounds bounds = area.toDTO().bounds();
        String world = area.getWorld();
        
        // Get chunk coordinates
        int minChunkX = bounds.xMin() >> 4;
        int maxChunkX = bounds.xMax() >> 4;
        int minChunkZ = bounds.zMin() >> 4;
        int maxChunkZ = bounds.zMax() >> 4;
        
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
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            if (area == null) {
                throw new IllegalArgumentException("Area cannot be null");
            }
            if (hasArea(area.getName())) {
                throw new IllegalStateException("Area with name " + area.getName() + " already exists");
            }
            
            // Check if this is a global area
            if (isGlobalArea(area)) {
                // Replace any existing global area for this world
                Area oldGlobalArea = globalAreasByWorld.put(area.getWorld(), area);
                if (oldGlobalArea != null) {
                    areasByName.remove(oldGlobalArea.getName().toLowerCase());
                }
            } else {
                areas.add(area);
                addToSpatialIndex(area);
            }
            
            areasByName.put(area.getName().toLowerCase(), area);
            nameCache.put(area.getName().toLowerCase(), area);
            invalidateLocationCache();
            try {
                databaseManager.saveArea(area);
            } catch (DatabaseException e) {
                throw new RuntimeException("Failed to save area to database", e);
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "area_add");
        }
    }

    private boolean isGlobalArea(Area area) {
        AreaDTO.Bounds bounds = area.toDTO().bounds();
        // Consider an area global if it spans a very large region
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
        Area oldArea = getArea(area.getName());
        if (oldArea != null) {
            areas.remove(oldArea);
            areas.add(area);
            areasByName.put(area.getName().toLowerCase(), area);
            nameCache.invalidate(area.getName().toLowerCase());
            invalidateLocationCache();
            try {
                databaseManager.updateArea(area);
            } catch (DatabaseException e) {
                throw new RuntimeException("Failed to update area in database", e);
            }
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
     * Gets all areas at a specific location, sorted by priority in descending order.
     * When areas overlap, the area with the highest priority takes precedence.
     * Example: If area A (priority 50) and area B (priority 25) overlap,
     * area A's settings will override area B's settings in the overlapping space.
     *
     * @param world The world name
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @return List of areas at location, sorted by priority (highest first)
     */
    public List<Area> getAreasAtLocation(String world, double x, double y, double z) {
        String cacheKey = String.format("%s:%d:%d:%d", world, (int)x, (int)y, (int)z);
        return locationCache.get(cacheKey, k -> calculateAreasAtLocation(world, x, y, z));
    }

    private List<Area> calculateAreasAtLocation(String world, double x, double y, double z) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            List<Area> result = new ArrayList<>();
            
            // First check global area for this world - more efficient lookup
            Area globalArea = globalAreasByWorld.get(world);
            if (globalArea != null) {
                if (plugin.isDebugMode()) {
                    plugin.debug("Found global area for world " + world + ":");
                    plugin.debug("  Player permissions: " + globalArea.getPlayerPermissions());
                }
                result.add(globalArea);
            }
            
            // Only check local areas if we're near a player
            if (isNearAnyPlayer(x, z)) {
                // Get chunk key
                int chunkKey = getChunkKey((int)x >> 4, (int)z >> 4);
                
                // Get areas from spatial index
                Map<Integer, List<Area>> worldIndex = spatialIndex.get(world);
                if (worldIndex != null) {
                    List<Area> chunkAreas = worldIndex.get(chunkKey);
                    if (chunkAreas != null) {
                        for (Area area : chunkAreas) {
                            if (area.isInside(world, x, y, z)) {
                                if (plugin.isDebugMode()) {
                                    plugin.debug("Found local area " + area.getName() + " at location:");
                                    plugin.debug("  Player permissions: " + area.getPlayerPermissions());
                                }
                                result.add(area);
                            }
                        }
                    }
                }
            }
            
            // Sort by priority (highest first)
            result.sort((a1, a2) -> Integer.compare(a2.getPriority(), a1.getPriority()));
            return result;
            
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "area_lookup");
        }
    }

    private boolean isNearAnyPlayer(double x, double z) {
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
            double z = position.getZ();

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
    private List<Area> getActiveAreas() {
        Set<Area> activeAreas = new HashSet<>();
        
        // Get all online players
        for (Player player : plugin.getServer().getOnlinePlayers().values()) {
            // Get chunk coordinates
            int chunkX = player.getChunkX();
            int chunkZ = player.getChunkZ();
            
            // Check chunks in radius around player
            for (int dx = -CHUNK_CHECK_RADIUS; dx <= CHUNK_CHECK_RADIUS; dx++) {
                for (int dz = -CHUNK_CHECK_RADIUS; dz <= CHUNK_CHECK_RADIUS; dz++) {
                    int checkX = (chunkX + dx) << 4; // Convert chunk coordinates to block coordinates
                    int checkZ = (chunkZ + dz) << 4;
                    
                    // Add any areas that overlap this chunk
                    activeAreas.addAll(getAreasInChunk(player.getLevel().getName(), checkX, checkZ));
                }
            }
        }
        
        return new ArrayList<>(activeAreas);
    }

    /**
     * Gets areas that overlap with a specific chunk
     */
    private List<Area> getAreasInChunk(String world, int chunkX, int chunkZ) {
        return areas.stream()
            .filter(area -> area.getWorld().equals(world))
            .filter(area -> {
                AreaDTO.Bounds bounds = area.getBounds();
                // Check if chunk overlaps with area bounds
                return bounds.xMin() <= (chunkX + 16) && bounds.xMax() >= chunkX &&
                       bounds.zMin() <= (chunkZ + 16) && bounds.zMax() >= chunkZ;
            })
            .collect(Collectors.toList());
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
}
