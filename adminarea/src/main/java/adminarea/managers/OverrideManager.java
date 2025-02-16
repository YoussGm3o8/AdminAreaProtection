package adminarea.managers;

import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

import adminarea.AdminAreaProtectionPlugin;
import io.micrometer.core.instrument.Timer;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import org.json.JSONException;

public class OverrideManager {

    private final AdminAreaProtectionPlugin plugin;
    private static final String OVERRIDES_KEY = "luckpermsOverrides";
    private DatabaseManager dbManager;
    private final Cache<String, PermissionState> overrideCache;
    private final Cache<String, Map<String, Boolean>> groupCache;
    
    private static final int CACHE_SIZE = 1000;
    private static final Duration CACHE_DURATION = Duration.ofMinutes(5);
    private static final Duration CLEANUP_INTERVAL = Duration.ofHours(6);

    private record PermissionState(boolean value, Instant expiry) {
        boolean isValid() {
            return expiry == null || expiry.isAfter(Instant.now());
        }
    }

    public OverrideManager(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        dbManager = plugin.getDatabaseManager();
        this.overrideCache = Caffeine.newBuilder()
            .maximumSize(CACHE_SIZE)
            .expireAfterWrite(CACHE_DURATION)
            .build();
        this.groupCache = Caffeine.newBuilder()
            .maximumSize(CACHE_SIZE)
            .expireAfterWrite(CACHE_DURATION)
            .build();
            
        setupScheduledTasks();
        createTable();
    }

    private void setupScheduledTasks() {
        // Schedule periodic cleanup
        plugin.getServer().getScheduler().scheduleRepeatingTask(plugin, () -> {
            Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
            try {
                cleanup();
            } finally {
                plugin.getPerformanceMonitor().stopTimer(sample, "override_cleanup");
            }
        }, (int)(CLEANUP_INTERVAL.toMillis() / 50)); // Convert to ticks
    }

    private void createTable() {
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS luckpermsoverrides (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "area TEXT NOT NULL," +
                    "override_type TEXT NOT NULL," +
                    "target TEXT NOT NULL," +
                    "permission TEXT NOT NULL," +
                    "value INTEGER NOT NULL," +
                    "overrides TEXT" +
                    ");");
        } catch (SQLException e) {
            plugin.getLogger().error("Failed to create luckpermsoverrides table", e);
        }
    }

    public static class OverrideEntry {
        public int id;
        public String area;
        public String overrideType; // e.g. "track" or "group"
        public String target;       // the track name or specific group
        public String permission;
        public boolean value;

        public OverrideEntry(int id, String area, String overrideType, String target, String permission, boolean value) {
            this.id = id;
            this.area = area;
            this.overrideType = overrideType;
            this.target = target;
            this.permission = permission;
            this.value = value;
        }
    }

    public List<OverrideEntry> getOverridesForArea(String areaName) {
        List<OverrideEntry> list = new ArrayList<>();
        String sql = "SELECT * FROM luckpermsoverrides WHERE area = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, areaName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    OverrideEntry entry = new OverrideEntry(
                            rs.getInt("id"),
                            rs.getString("area"),
                            rs.getString("override_type"),
                            rs.getString("target"),
                            rs.getString("permission"),
                            rs.getInt("value") != 0
                    );
                    list.add(entry);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().error("Failed to load overrides for area " + areaName, e);
        }
        return list;
    }

    public void addOverride(OverrideEntry entry) {
        String sql = "INSERT INTO luckpermsoverrides (area, override_type, target, permission, value) VALUES (?,?,?,?,?)";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, entry.area);
            ps.setString(2, entry.overrideType);
            ps.setString(3, entry.target);
            ps.setString(4, entry.permission);
            ps.setInt(5, entry.value ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().error("Failed to add override", e);
        }
    }

    public void updateOverride(OverrideEntry entry) {
        String sql = "UPDATE luckpermsoverrides SET permission = ?, value = ? WHERE id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, entry.permission);
            ps.setInt(2, entry.value ? 1 : 0);
            ps.setInt(3, entry.id);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().error("Failed to update override", e);
        }
    }

    public void deleteOverride(int id) {
        String sql = "DELETE FROM luckpermsoverrides WHERE id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().error("Failed to delete override with id " + id, e);
        }
    }

    // Gets the structured overrides JSON from the area's settings.
    public static JSONObject getOverrides(JSONObject settings) {
        if (settings.has(OVERRIDES_KEY)) {
            try {
                return new JSONObject(settings.getString(OVERRIDES_KEY));
            } catch (JSONException e) {
                // If parsing fails, start with an empty override object.
                return new JSONObject();
            }
        }
        return new JSONObject();
    }

    // Returns the override object (permission keys to boolean values) for a given rank.
    public static JSONObject getOverridesForRank(JSONObject settings, String rank) {
        JSONObject all = getOverrides(settings);
        if (all.has(rank)) {
            try {
                return all.getJSONObject(rank);
            } catch (JSONException e) {
                return new JSONObject();
            }
        }
        return new JSONObject();
    }

    // Updates or adds a permission override for the given rank.
    // Merges with any existing overrides without creating duplicates.
    public static void updateOverride(JSONObject settings, String rank, String permission, boolean allowed) {
        JSONObject all = getOverrides(settings);
        JSONObject rankOverrides = all.has(rank) ? all.optJSONObject(rank) : new JSONObject();
        rankOverrides.put(permission, allowed);
        all.put(rank, rankOverrides);
        settings.put(OVERRIDES_KEY, all.toString());
    }

    // Removes a specific permission override for a given rank.
    public static void removeOverride(JSONObject settings, String rank, String permission) {
        JSONObject all = getOverrides(settings);
        if (all.has(rank)) {
            JSONObject rankOverrides = all.optJSONObject(rank);
            rankOverrides.remove(permission);
            all.put(rank, rankOverrides);
            settings.put(OVERRIDES_KEY, all.toString());
        }
    }

    // Removes all overrides for a given rank.
    public static void clearOverridesForRank(JSONObject settings, String rank) {
        JSONObject all = getOverrides(settings);
        all.remove(rank);
        settings.put(OVERRIDES_KEY, all.toString());
    }
    
    // Example: Merges a new override JSON object for a rank with existing data.
    public static void mergeOverrides(JSONObject settings, String rank, JSONObject newOverrides) {
        JSONObject rankOverrides = getOverridesForRank(settings, rank);
        for(String key : newOverrides.keySet()){
            rankOverrides.put(key, newOverrides.getBoolean(key));
        }
        JSONObject all = getOverrides(settings);
        all.put(rank, rankOverrides);
        settings.put(OVERRIDES_KEY, all.toString());
    }

    // Retrieve overrides for a given area and target as JSONObject.
    public JSONObject getOverrides(String area, String target) {
        JSONObject json = new JSONObject();
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT overrides FROM luckpermsoverrides WHERE area=? AND target=?")) {
            ps.setString(1, area);
            ps.setString(2, target);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String overrideStr = rs.getString("overrides");
                    json = new JSONObject(overrideStr);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().error("Error fetching overrides for area '" + area + "', target '" + target + "'", e);
        }
        return json;
    }

    // Save overrides for a given area and target (overwrites any existing row).
    public void saveOverrides(String area, String target, JSONObject overrides) {
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO luckpermsoverrides (area, target, overrides) VALUES (?, ?, ?)")) {
            ps.setString(1, area);
            ps.setString(2, target);
            ps.setString(3, overrides.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().error("Error saving overrides for area '" + area + "', target '" + target + "'", e);
        }
    }

    public boolean checkPermission(String playerName, String areaName, String permission) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            String cacheKey = playerName + ":" + areaName + ":" + permission;
            
            // Check cache first
            PermissionState cached = overrideCache.getIfPresent(cacheKey);
            if (cached != null && cached.isValid()) {
                return cached.value();
            }

            // Check direct player override
            Boolean playerOverride = getPlayerOverride(playerName, areaName, permission);
            if (playerOverride != null) {
                overrideCache.put(cacheKey, new PermissionState(playerOverride, null));
                return playerOverride;
            }

            // Check LuckPerms group overrides
            if (plugin.getLuckPermsApi() != null) {
                boolean groupResult = checkGroupPermissions(playerName, areaName, permission);
                overrideCache.put(cacheKey, new PermissionState(groupResult, null));
                return groupResult;
            }

            // Default fallback
            overrideCache.put(cacheKey, new PermissionState(true, null));
            return true;
            
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "permission_check");
        }
    }

    private boolean checkGroupPermissions(String playerName, String areaName, String permission) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            var user = plugin.getLuckPermsApi().getUserManager().getUser(playerName);
            if (user == null) return true;

            var groups = user.getInheritedGroups(user.getQueryOptions());
            boolean result = true;

            // Check group permissions in priority order
            for (var group : groups) {
                String cacheKey = group.getName() + ":" + areaName;
                Map<String, Boolean> groupPerms = groupCache.get(cacheKey, k -> loadGroupPermissions(group.getName(), areaName));
                
                if (groupPerms.containsKey(permission)) {
                    result = groupPerms.get(permission);
                    break;
                }
            }

            return result;
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "group_permission_check");
        }
    }

    private Map<String, Boolean> loadGroupPermissions(String groupName, String areaName) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Map<String, Boolean> perms = new HashMap<>();
            try (Connection conn = dbManager.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                     "SELECT permission, value FROM luckpermsoverrides WHERE target = ? AND area = ?")) {
                stmt.setString(1, groupName);
                stmt.setString(2, areaName);
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    perms.put(rs.getString("permission"), rs.getBoolean("value"));
                }
            } catch (SQLException e) {
                plugin.getLogger().error("Failed to load group permissions", e);
            }
            return perms;
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "load_group_permissions");
        }
    }

    public void setOverride(String target, String area, String permission, boolean value) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            try (Connection conn = dbManager.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                     "INSERT OR REPLACE INTO luckpermsoverrides (target, area, permission, value) VALUES (?, ?, ?, ?)")) {
                stmt.setString(1, target);
                stmt.setString(2, area);
                stmt.setString(3, permission);
                stmt.setBoolean(4, value);
                stmt.executeUpdate();
                
                // Invalidate relevant caches
                invalidateCache(target, area, permission);
                
            } catch (SQLException e) {
                plugin.getLogger().error("Failed to set override", e);
                throw new RuntimeException("Failed to set override", e);
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "set_override");
        }
    }

    private void invalidateCache(String target, String area, String permission) {
        String cacheKey = target + ":" + area + ":" + permission;
        overrideCache.invalidate(cacheKey);
        groupCache.invalidate(target + ":" + area);
    }

    private Boolean getPlayerOverride(String playerName, String areaName, String permission) {
        try (Connection conn = dbManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "SELECT value FROM luckpermsoverrides WHERE target = ? AND area = ? AND permission = ?")) {
            stmt.setString(1, playerName);
            stmt.setString(2, areaName);
            stmt.setString(3, permission);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getBoolean("value");
            }
        } catch (SQLException e) {
            plugin.getLogger().error("Failed to get player override", e);
        }
        return null;
    }

    public void cleanup() {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            // Clear expired entries from caches
            overrideCache.cleanUp();
            groupCache.cleanUp();
            
            // Remove expired database entries
            try (Connection conn = dbManager.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                     "DELETE FROM luckpermsoverrides WHERE expiry < ?")) {
                stmt.setTimestamp(1, Timestamp.from(Instant.now()));
                int removed = stmt.executeUpdate();
                if (removed > 0) {
                    plugin.getLogger().info("Cleaned up " + removed + " expired overrides");
                }
            } catch (SQLException e) {
                plugin.getLogger().error("Failed to cleanup expired overrides", e);
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "cleanup");
        }
    }
}
