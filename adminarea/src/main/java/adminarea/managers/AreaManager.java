package adminarea.managers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
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

public class AreaManager implements IAreaManager {
    private final AdminAreaProtectionPlugin plugin;
    private final Map<String, TaskHandler> visualizationTasks = new HashMap<>();
    private final Map<String, AreaStatistics> areaStats = new HashMap<>();
    private final Map<String, Area> areasByName = new HashMap<>();
    private final List<Area> areas = new ArrayList<>();
    
    // Add cache for area queries
    private final Cache<String, List<Area>> locationCache;
    private final Cache<String, Area> nameCache;

    public AreaManager(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        this.locationCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .build();
        this.nameCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();
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
            
            areas.add(area);
            areasByName.put(area.getName().toLowerCase(), area);
            nameCache.put(area.getName().toLowerCase(), area);
            invalidateLocationCache();
            plugin.getDatabaseManager().saveArea(area);
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "area_add");
        }
    }

    @Override
    public void removeArea(Area area) {
        areas.remove(area);
        areasByName.remove(area.getName().toLowerCase());
        plugin.getDatabaseManager().deleteArea(area.getName());
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
            plugin.getDatabaseManager().updateArea(area);
        }
    }

    @Override
    public Area getArea(String name) {
        return areasByName.get(name.toLowerCase());
    }

    @Override
    public List<Area> getAllAreas() {
        return new ArrayList<>(areas);
    }

    @Override
    public boolean hasArea(String name) {
        return areasByName.containsKey(name.toLowerCase());
    }

    public List<Area> getAreasAtLocation(String world, double x, double y, double z) {
        String cacheKey = String.format("%s:%d:%d:%d", world, (int)x, (int)y, (int)z);
        return locationCache.get(cacheKey, k -> calculateAreasAtLocation(world, x, y, z));
    }

    private List<Area> calculateAreasAtLocation(String world, double x, double y, double z) {
        return areas.stream()
            .filter(area -> area.isInside(world, x, y, z))
            .sorted((a1, a2) -> Integer.compare(a2.getPriority(), a1.getPriority()))
            .collect(Collectors.toList());
    }

    private void invalidateLocationCache() {
        locationCache.invalidateAll();
    }

    public Area getHighestPriorityArea(String world, double x, double y, double z) {
        return areas.stream()
            .filter(area -> area.isInside(world, x, y, z))
            .max((a1, a2) -> Integer.compare(a1.getPriority(), a2.getPriority()))
            .orElse(null);
    }

    public Area mergeAreas(Area area1, Area area2) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            if (!area1.getWorld().equals(area2.getWorld())) {
                throw new IllegalArgumentException("Cannot merge areas from different worlds");
            }

            // Create merged area with combined bounds
            Area merged = Area.builder()
                .name(area1.getName() + "_" + area2.getName())
                .world(area1.getWorld())
                .coordinates(
                    Math.min(area1.getXMin(), area2.getXMin()),
                    Math.max(area1.getXMax(), area2.getXMax()),
                    Math.min(area1.getYMin(), area2.getYMin()),
                    Math.max(area1.getYMax(), area2.getYMax()),
                    Math.min(area1.getZMin(), area2.getZMin()),
                    Math.max(area1.getZMax(), area2.getZMax())
                )
                .priority(Math.max(area1.getPriority(), area2.getPriority()))
                .build();

            // Merge settings (prefer more restrictive settings)
            JSONObject mergedSettings = new JSONObject();
            mergeSettings(mergedSettings, area1.getSettings(), area2.getSettings());
            merged.setSettings(mergedSettings);

            // Merge group permissions
            JSONObject mergedPermissions = new JSONObject();
            mergeGroupPermissions(mergedPermissions, area1.getGroupPermissions(), area2.getGroupPermissions());
            merged.setGroupPermissions(mergedPermissions);

            return merged;
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "area_merge");
        }
    }

    private void mergeSettings(JSONObject target, JSONObject settings1, JSONObject settings2) {
        Set<String> allKeys = new HashSet<>();
        allKeys.addAll(settings1.keySet());
        allKeys.addAll(settings2.keySet());

        for (String key : allKeys) {
            // For boolean settings, use most restrictive (false) value
            boolean value1 = settings1.optBoolean(key, true);
            boolean value2 = settings2.optBoolean(key, true);
            target.put(key, value1 && value2);
        }
    }

    private void mergeGroupPermissions(JSONObject target, JSONObject perms1, JSONObject perms2) {
        Set<String> allGroups = new HashSet<>();
        allGroups.addAll(perms1.keySet());
        allGroups.addAll(perms2.keySet());

        for (String group : allGroups) {
            JSONObject merged = new JSONObject();
            JSONObject groupPerms1 = perms1.optJSONObject(group, new JSONObject());
            JSONObject groupPerms2 = perms2.optJSONObject(group, new JSONObject());
            mergeSettings(merged, groupPerms1, groupPerms2);
            target.put(group, merged);
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

        // Calculate edges more efficiently
        for (double x = area.getXMin(); x <= area.getXMax(); x += spacing) {
            addEdgePoints(points, x, area.getYMin(), area.getZMin(), area.getYMax(), area.getZMax());
        }
        
        for (double y = area.getYMin(); y <= area.getYMax(); y += spacing) {
            addEdgePoints(points, area.getXMin(), y, area.getZMin(), area.getXMax(), area.getZMax());
        }
        
        for (double z = area.getZMin(); z <= area.getZMax(); z += spacing) {
            addEdgePoints(points, area.getXMin(), area.getYMin(), z, area.getXMax(), area.getYMax());
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

    // : Perform additional testing on visualization methods; possibly optimize particle spawn rate.
    private void showParticles(Player player, Area area, DustParticle particle) {
        // Draw edges
        for (double x = area.getXMin(); x <= area.getXMax(); x += 0.5) {
            spawnParticle(player, x, area.getYMin(), area.getZMin(), particle);
            spawnParticle(player, x, area.getYMin(), area.getZMax(), particle);
            spawnParticle(player, x, area.getYMax(), area.getZMin(), particle);
            spawnParticle(player, x, area.getYMax(), area.getZMax(), particle);
        }
        
        for (double y = area.getYMin(); y <= area.getYMax(); y += 0.5) {
            spawnParticle(player, area.getXMin(), y, area.getZMin(), particle);
            spawnParticle(player, area.getXMax(), y, area.getZMin(), particle);
            spawnParticle(player, area.getXMin(), y, area.getZMax(), particle);
            spawnParticle(player, area.getXMax(), y, area.getZMax(), particle);
        }
        
        for (double z = area.getZMin(); z <= area.getZMax(); z += 0.5) {
            spawnParticle(player, area.getXMin(), area.getYMin(), z, particle);
            spawnParticle(player, area.getXMax(), area.getYMin(), z, particle);
            spawnParticle(player, area.getXMin(), area.getYMax(), z, particle);
            spawnParticle(player, area.getXMax(), area.getYMax(), z, particle);
        }
    }

    private void spawnParticle(Player player, double x, double y, double z, DustParticle particle) {
        particle.setComponents(x, y, z);
        player.getLevel().addParticle(particle);
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

    public void reloadAreas() {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            // Clear existing data
            areas.clear();
            areasByName.clear();
            locationCache.invalidateAll();
            nameCache.invalidateAll();

            // Stop all active visualizations
            visualizationTasks.forEach((playerName, taskId) -> {
                plugin.getServer().getScheduler().cancelTask(taskId.getTaskId());
            });
            visualizationTasks.clear();

            // Reload areas from database
            try {
                List<Area> loadedAreas = plugin.getDatabaseManager().loadAreas();
                for (Area area : loadedAreas) {
                    areas.add(area);
                    areasByName.put(area.getName().toLowerCase(), area);
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
}
