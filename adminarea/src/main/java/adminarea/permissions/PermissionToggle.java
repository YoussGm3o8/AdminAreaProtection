package adminarea.permissions;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.util.PerformanceMonitor;
import adminarea.util.ValidationUtils;
import io.micrometer.core.instrument.Timer;
import cn.nukkit.form.element.ElementToggle;

import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.io.IOException;
import java.nio.file.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

/**
 * Manages permission toggles for players and groups with inheritance support.
 */
public class PermissionToggle implements AutoCloseable {
    private final AdminAreaProtectionPlugin plugin;
    private Connection dbConnection;
        private final Map<String, Map<String, Boolean>> playerToggles;
        private final Map<String, Map<String, Boolean>> groupToggles;
        private final Map<String, Set<String>> groupMembership;
        private final Map<String, Boolean> defaultToggles;
        private final ScheduledExecutorService scheduler;
        private final Cache<String, Boolean> toggleCache;
        private final Path backupPath;
        
        private static final int CACHE_SIZE = 1000;
        private static final long CACHE_DURATION = TimeUnit.MINUTES.toMillis(10);
        private static final String DB_FILE = "permission_toggles.db";
    
        private final String displayName;
        private final String permissionNode;
        private final boolean defaultValue;
    
        // Constructor for form toggle usage
        public PermissionToggle(String displayName, String permissionNode, boolean defaultValue) throws SQLException {
            this.plugin = null; // Not needed for form toggles
            this.dbConnection = DriverManager.getConnection("jdbc:sqlite::memory:"); // In-memory database for form toggles
            this.displayName = displayName;
            this.permissionNode = permissionNode;
            this.defaultValue = defaultValue;
            
            // Initialize remaining fields to null/empty as they're not needed for form toggles
            this.playerToggles = null;
            this.groupToggles = null;
            this.groupMembership = null;
            this.defaultToggles = null;
            this.scheduler = null;
            this.toggleCache = null;
            this.backupPath = null;
        }
    
        // Original constructor for permission management
        public PermissionToggle(AdminAreaProtectionPlugin plugin) throws SQLException {
            this.plugin = plugin;
            this.playerToggles = new ConcurrentHashMap<>();
            this.groupToggles = new ConcurrentHashMap<>();
            this.groupMembership = new ConcurrentHashMap<>();
            this.defaultToggles = new ConcurrentHashMap<>();
            this.scheduler = Executors.newScheduledThreadPool(1);
            this.toggleCache = new Cache<>(CACHE_SIZE, CACHE_DURATION);
            this.backupPath = plugin.getDataFolder().toPath().resolve("toggle_backups");
            this.displayName = null; // Not needed for permission management
            this.permissionNode = null; // Not needed for permission management
            this.defaultValue = false; // Not needed for permission management
            
            Path dbPath = plugin.getDataFolder().toPath().resolve(DB_FILE);
            this.dbConnection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            initializeDatabase();
            loadDefaultToggles();
            setupScheduledTasks();
        }
    
        private void initializeDatabase() throws SQLException {
            Path dbPath = plugin.getDataFolder().toPath().resolve(DB_FILE);
            dbConnection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        
        try (Statement stmt = dbConnection.createStatement()) {
            // Create tables
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS player_toggles (
                    player_id TEXT NOT NULL,
                    permission TEXT NOT NULL,
                    state BOOLEAN NOT NULL,
                    last_modified TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (player_id, permission)
                )
            """);
            
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS group_toggles (
                    group_id TEXT NOT NULL,
                    permission TEXT NOT NULL,
                    state BOOLEAN NOT NULL,
                    last_modified TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (group_id, permission)
                )
            """);
            
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS group_membership (
                    player_id TEXT NOT NULL,
                    group_id TEXT NOT NULL,
                    PRIMARY KEY (player_id, group_id)
                )
            """);
        }
    }

    private void setupScheduledTasks() {
        // Schedule periodic cache cleanup
        scheduler.scheduleAtFixedRate(toggleCache::cleanup, 1, 1, TimeUnit.MINUTES);
        
        // Schedule database backup
        scheduler.scheduleAtFixedRate(this::backup, 6, 6, TimeUnit.HOURS);
        
        // Schedule data cleanup
        scheduler.scheduleAtFixedRate(this::cleanup, 1, 1, TimeUnit.DAYS);
    }

    /**
     * Sets a toggle state for a player.
     */
    public void setPlayerToggle(String playerId, String permission, boolean state) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            ValidationUtils.validateString(playerId, 3, 16, "^[a-zA-Z0-9_]+$", "Player ID");
            ValidationUtils.validatePermission(permission);
            
            try {
                // Update database
                try (PreparedStatement stmt = dbConnection.prepareStatement(
                    "INSERT OR REPLACE INTO player_toggles (player_id, permission, state) VALUES (?, ?, ?)")) {
                    stmt.setString(1, playerId);
                    stmt.setString(2, permission);
                    stmt.setBoolean(3, state);
                    stmt.executeUpdate();
                }
                
                // Update cache
                playerToggles.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>())
                            .put(permission, state);
                toggleCache.invalidate(getCacheKey(playerId, permission));
                
            } catch (SQLException e) {
                plugin.getLogger().error("Failed to set player toggle", e);
                throw new RuntimeException("Failed to set player toggle", e);
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "set_player_toggle");
        }
    }

    /**
     * Sets a toggle state for a group.
     */
    public void setGroupToggle(String groupId, String permission, boolean state) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            ValidationUtils.validateString(groupId, 2, 32, "^[a-zA-Z0-9_-]+$", "Group ID");
            ValidationUtils.validatePermission(permission);
            
            try {
                // Update database
                try (PreparedStatement stmt = dbConnection.prepareStatement(
                    "INSERT OR REPLACE INTO group_toggles (group_id, permission, state) VALUES (?, ?, ?)")) {
                    stmt.setString(1, groupId);
                    stmt.setString(2, permission);
                    stmt.setBoolean(3, state);
                    stmt.executeUpdate();
                }
                
                // Update cache
                groupToggles.computeIfAbsent(groupId, k -> new ConcurrentHashMap<>())
                           .put(permission, state);
                
                // Invalidate affected player caches
                invalidateGroupMemberCaches(groupId, permission);
                
            } catch (SQLException e) {
                plugin.getLogger().error("Failed to set group toggle", e);
                throw new RuntimeException("Failed to set group toggle", e);
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "set_group_toggle");
        }
    }

    /**
     * Gets the effective toggle state for a player.
     */
    public boolean getEffectiveToggle(String playerId, String permission) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            String cacheKey = getCacheKey(playerId, permission);
            Boolean cached = toggleCache.get(cacheKey);
            if (cached != null) {
                return cached;
            }

            // Check player-specific toggle
            Map<String, Boolean> playerPerms = playerToggles.get(playerId);
            if (playerPerms != null && playerPerms.containsKey(permission)) {
                boolean state = playerPerms.get(permission);
                toggleCache.put(cacheKey, state);
                return state;
            }

            // Check group toggles
            Set<String> groups = groupMembership.getOrDefault(playerId, Collections.emptySet());
            for (String groupId : groups) {
                Map<String, Boolean> groupPerms = groupToggles.get(groupId);
                if (groupPerms != null && groupPerms.containsKey(permission)) {
                    boolean state = groupPerms.get(permission);
                    toggleCache.put(cacheKey, state);
                    return state;
                }
            }

            // Fall back to default
            boolean defaultState = defaultToggles.getOrDefault(permission, false);
            toggleCache.put(cacheKey, defaultState);
            return defaultState;
            
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "get_effective_toggle");
        }
    }

    private void invalidateGroupMemberCaches(String groupId, String permission) {
        groupMembership.entrySet().stream()
            .filter(entry -> entry.getValue().contains(groupId))
            .forEach(entry -> toggleCache.invalidate(getCacheKey(entry.getKey(), permission)));
    }

    private String getCacheKey(String playerId, String permission) {
        return playerId + ":" + permission;
    }

    private void loadDefaultToggles() {
        Path configPath = plugin.getDataFolder().toPath().resolve("default_toggles.json");
        if (Files.exists(configPath)) {
            try {
                Gson gson = new Gson();
                Map<String, Boolean> defaults = gson.fromJson(
                    Files.readString(configPath), 
                    new TypeToken<Map<String, Boolean>>(){}.getType()
                );
                defaultToggles.putAll(defaults);
            } catch (Exception e) {
                plugin.getLogger().error("Failed to load default toggles", e);
            }
        }
    }

    private void backup() {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Files.createDirectories(backupPath);
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            Path backupFile = backupPath.resolve("toggles_" + timestamp + ".db");
            
            // Backup database
            try (PreparedStatement stmt = dbConnection.prepareStatement("BACKUP TO ?")) {
                stmt.setString(1, backupFile.toString());
                stmt.execute();
            }
            
            // Backup in-memory state
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            Map<String, Object> state = new HashMap<>();
            state.put("playerToggles", playerToggles);
            state.put("groupToggles", groupToggles);
            state.put("groupMembership", groupMembership);
            
            Files.write(
                backupPath.resolve("memory_state_" + timestamp + ".json"),
                gson.toJson(state).getBytes()
            );
            
        } catch (Exception e) {
            plugin.getLogger().error("Failed to create backup", e);
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "toggle_backup");
        }
    }

    private void cleanup() {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            // Remove old backups
            try {
                Files.list(backupPath)
                    .filter(path -> {
                        try {
                            return Files.getLastModifiedTime(path).toInstant()
                                .isBefore(Instant.now().minus(Duration.ofDays(7)));
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            plugin.getLogger().error("Failed to delete old backup: " + path, e);
                        }
                    });
            } catch (IOException e) {
                plugin.getLogger().error("Failed to cleanup old backups", e);
            }
            
            // Clear expired cache entries
            toggleCache.cleanup();
            
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "toggle_cleanup");
        }
    }

    @Override
    public void close() {
        try {
            scheduler.shutdown();
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
            backup();
            dbConnection.close();
        } catch (Exception e) {
            plugin.getLogger().error("Error closing PermissionToggle", e);
        }
    }

    // Form-related methods
    public String getDisplayName() {
        return displayName;
    }

    public String getPermissionNode() {
        return permissionNode;
    }

    public boolean getDefaultValue() {
        return defaultValue;
    }

    public ElementToggle toElementToggle() {
        return new ElementToggle(displayName, defaultValue);
    }

    // Returns the default set of permission toggles
    public static PermissionToggle[] getDefaultToggles() {
        try {
            return new PermissionToggle[] {
                // Existing toggles
                new PermissionToggle("Show Title", "area.show.title", true),
                new PermissionToggle("Block Breaking", "area.block.break", true),
                new PermissionToggle("Block Placing", "area.block.place", true),
                new PermissionToggle("PvP", "area.pvp", false),
                new PermissionToggle("Mob Spawning", "area.mob.spawn", true),
                new PermissionToggle("Item Drops", "area.item.drop", true),
                new PermissionToggle("Item Pickup", "area.item.pickup", true),
                new PermissionToggle("Container Access", "area.container.access", false),
                // Add new protection toggles
                new PermissionToggle("Redstone", "area.redstone", true),
                new PermissionToggle("Fire Spread", "area.fire.spread", false),
                new PermissionToggle("Block Spread", "area.block.spread", true),
                new PermissionToggle("Liquid Flow", "area.liquid.flow", true),
                new PermissionToggle("Explosions", "area.explosions", false),
                new PermissionToggle("Entity Damage", "area.entity.damage", true),
                new PermissionToggle("Vehicle Damage", "area.vehicle.damage", false),
                new PermissionToggle("Pistons", "area.pistons", true),
                new PermissionToggle("Hanging Items", "area.hanging.break", false),
                new PermissionToggle("Fire Ignition", "area.fire.start", false)
            };
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create default toggles", e);
        }
    }

    // Returns the default player-relevant toggles for LuckPerms overrides
    public static PermissionToggle[] getPlayerToggles() {
        try {
            return new PermissionToggle[] {
                new PermissionToggle("Allow PvP", "pvp", true),
                new PermissionToggle("Allow Block Break", "block.break", true),
                new PermissionToggle("Allow Block Place", "block.place", true),
                new PermissionToggle("Allow Object Interaction", "object.interact", true),
                new PermissionToggle("Allow Hunger", "hunger", true),
                new PermissionToggle("Allow Fall Damage", "fall.damage", true)
            };
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create player toggles", e);
        }
    }

    /**
     * Simple cache implementation with size and time-based expiration.
     */
    private static class Cache<K, V> {
        private final Map<K, CacheEntry<V>> map;
        private final int maxSize;
        private final long duration;

        private static class CacheEntry<V> {
            final V value;
            final long expiry;

            CacheEntry(V value, long expiry) {
                this.value = value;
                this.expiry = expiry;
            }
        }

        Cache(int maxSize, long duration) {
            this.map = new ConcurrentHashMap<>();
            this.maxSize = maxSize;
            this.duration = duration;
        }

        void put(K key, V value) {
            cleanup();
            if (map.size() >= maxSize) {
                // Remove random entry if full
                Iterator<K> it = map.keySet().iterator();
                if (it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
            map.put(key, new CacheEntry<>(value, 
                System.currentTimeMillis() + duration));
        }

        V get(K key) {
            CacheEntry<V> entry = map.get(key);
            if (entry != null && System.currentTimeMillis() < entry.expiry) {
                return entry.value;
            }
            map.remove(key);
            return null;
        }

        void invalidate(K key) {
            map.remove(key);
        }

        void cleanup() {
            long now = System.currentTimeMillis();
            map.entrySet().removeIf(entry -> now >= entry.getValue().expiry);
        }
    }
}
