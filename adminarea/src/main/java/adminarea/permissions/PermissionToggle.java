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
        private final Category category; // Add category field
    
        // Constructor for form toggle usage - remove SQLException
        public PermissionToggle(String displayName, String permissionNode, boolean defaultValue, Category category) {
            this.plugin = null; // Not needed for form toggles
            this.dbConnection = null; // Remove database connection for form toggles
            this.displayName = displayName;
            this.permissionNode = permissionNode;
            this.defaultValue = defaultValue;
            this.category = category;
            
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
            this.category = null; // Not needed for permission management
            
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
    public static List<PermissionToggle> getDefaultToggles() {
        List<PermissionToggle> toggles = new ArrayList<>();
        
        // Building toggles
    toggles.add(new PermissionToggle("Allow Building", PERM_BUILD, false, Category.BUILDING));
    toggles.add(new PermissionToggle("Allow Breaking", PERM_BREAK, false, Category.BUILDING));
    toggles.add(new PermissionToggle("Allow General Interaction", PERM_INTERACT, false, Category.BUILDING));
    toggles.add(new PermissionToggle("Allow Container Access", "allowContainer", false, Category.BUILDING));
    toggles.add(new PermissionToggle("Allow Item Frame Access", "allowItemFrame", false, Category.BUILDING));
    toggles.add(new PermissionToggle("Allow Armor Stand Access", "allowArmorStand", false, Category.BUILDING));
    
    // Redstone & Mechanics
    toggles.add(new PermissionToggle("Allow Redstone", "allowRedstone", true, Category.TECHNICAL));
    toggles.add(new PermissionToggle("Allow Pistons", "allowPistons", true, Category.TECHNICAL));
    toggles.add(new PermissionToggle("Allow Hoppers", "allowHopper", true, Category.TECHNICAL));
    toggles.add(new PermissionToggle("Allow Dispensers", "allowDispenser", true, Category.TECHNICAL));
    
    // Environment
    toggles.add(new PermissionToggle("Allow Fire Spread", "allowFire", false, Category.ENVIRONMENT));
    toggles.add(new PermissionToggle("Allow Liquid Flow", "allowLiquid", true, Category.ENVIRONMENT));
    toggles.add(new PermissionToggle("Allow Block Spread", "allowBlockSpread", true, Category.ENVIRONMENT));
    toggles.add(new PermissionToggle("Allow Leaf Decay", "allowLeafDecay", true, Category.ENVIRONMENT));
    toggles.add(new PermissionToggle("Allow Ice Form/Melt", "allowIceForm", true, Category.ENVIRONMENT));
    toggles.add(new PermissionToggle("Allow Snow Form/Melt", "allowSnowForm", true, Category.ENVIRONMENT));
    
    // Entities
    toggles.add(new PermissionToggle("Allow PvP", "allowPvP", false, Category.ENTITY));
    toggles.add(new PermissionToggle("Allow Monster Spawning", "allowMonsterSpawn", false, Category.ENTITY));
    toggles.add(new PermissionToggle("Allow Animal Spawning", "allowAnimalSpawn", true, Category.ENTITY));
    toggles.add(new PermissionToggle("Allow Entity Damage", "allowDamageEntities", false, Category.ENTITY));
    toggles.add(new PermissionToggle("Allow Animal Breeding", "allowBreeding", true, Category.ENTITY));
    toggles.add(new PermissionToggle("Allow Monster Target", "allowMonsterTarget", false, Category.ENTITY));
    toggles.add(new PermissionToggle("Allow Entity Leashing", "allowLeashing", true, Category.ENTITY));
    
    // Items & Drops
    toggles.add(new PermissionToggle("Allow Item Drops", "allowItemDrop", true, Category.SPECIAL));
    toggles.add(new PermissionToggle("Allow Item Pickup", "allowItemPickup", true, Category.SPECIAL));
    toggles.add(new PermissionToggle("Allow XP Drops", "allowXPDrop", true, Category.SPECIAL));
    toggles.add(new PermissionToggle("Allow XP Pickup", "allowXPPickup", true, Category.SPECIAL));
    
    // Explosions & Protection
    toggles.add(new PermissionToggle("Allow TNT", "allowTNT", false, Category.SPECIAL));
    toggles.add(new PermissionToggle("Allow Creeper", "allowCreeper", false, Category.SPECIAL));
    toggles.add(new PermissionToggle("Allow Bed Explosions", "allowBedExplosion", false, Category.SPECIAL));
    toggles.add(new PermissionToggle("Allow Crystal Explosions", "allowCrystalExplosion", false, Category.SPECIAL));
    
    // Vehicles
    toggles.add(new PermissionToggle("Allow Vehicle Place", "allowVehiclePlace", true, Category.SPECIAL));
    toggles.add(new PermissionToggle("Allow Vehicle Break", "allowVehicleBreak", false, Category.SPECIAL));
    toggles.add(new PermissionToggle("Allow Vehicle Enter", "allowVehicleEnter", true, Category.SPECIAL));
    toggles.add(new PermissionToggle("Allow Vehicle Collision", "allowVehicleCollide", true, Category.SPECIAL));
    
    // Player Effects
    toggles.add(new PermissionToggle("Allow Fall Damage", "allowFallDamage", true, Category.SPECIAL));
    toggles.add(new PermissionToggle("Allow Hunger", "allowHunger", true, Category.SPECIAL));
    toggles.add(new PermissionToggle("Allow Flight", "allowFlight", false, Category.SPECIAL));
    toggles.add(new PermissionToggle("Allow Ender Pearl", "allowEnderPearl", false, Category.SPECIAL));
    toggles.add(new PermissionToggle("Allow Chorus Fruit", "allowChorusFruit", false, Category.SPECIAL));
    
    return toggles;
}


    // Returns the default player-relevant toggles for LuckPerms overrides
    public static PermissionToggle[] getPlayerToggles() {
        return new PermissionToggle[] {
            new PermissionToggle("Allow PvP", "allowPvP", true, Category.ENTITY),
            new PermissionToggle("Allow Block Break", "allowBlockBreak", true, Category.BUILDING),
            new PermissionToggle("Allow Block Place", "allowBlockPlace", true, Category.BUILDING),
            new PermissionToggle("Allow Object Interaction", "allowInteract", true, Category.BUILDING),
            new PermissionToggle("Allow Hunger", "allowHunger", true, Category.SPECIAL),
            new PermissionToggle("Allow Fall Damage", "allowFallDamage", true, Category.SPECIAL)
        };
    }

    public enum Category {
        BUILDING("Building"),
        ENVIRONMENT("Environment"),
        ENTITY("Entity Controls"),
        TECHNICAL("Redstone & Mechanics"),
        SPECIAL("Special Permissions");

        private final String displayName;

        Category(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public static Map<Category, List<PermissionToggle>> getTogglesByCategory() {
        Map<Category, List<PermissionToggle>> toggles = new EnumMap<>(Category.class);
    
        // Building toggles
        toggles.put(Category.BUILDING, Arrays.asList(
            new PermissionToggle("Allow Building", PERM_BUILD, false, Category.BUILDING),
            new PermissionToggle("Allow Breaking", PERM_BREAK, false, Category.BUILDING),
            new PermissionToggle("Allow Interaction", PERM_INTERACT, false, Category.BUILDING),
            new PermissionToggle("Allow Container Access", "allowContainer", false, Category.BUILDING),
            new PermissionToggle("Allow Item Frame Access", "allowItemFrame", false, Category.BUILDING),
            new PermissionToggle("Allow Armor Stand Access", "allowArmorStand", false, Category.BUILDING)
        ));
    
        // Environment toggles
        toggles.put(Category.ENVIRONMENT, Arrays.asList(
            new PermissionToggle("Allow Fire Spread", "allowFire", false, Category.ENVIRONMENT),
            new PermissionToggle("Allow Liquid Flow", "allowLiquid", true, Category.ENVIRONMENT),
            new PermissionToggle("Allow Block Spread", "allowBlockSpread", true, Category.ENVIRONMENT),
            new PermissionToggle("Allow Leaf Decay", "allowLeafDecay", true, Category.ENVIRONMENT),
            new PermissionToggle("Allow Ice Form", "allowIceForm", true, Category.ENVIRONMENT),
            new PermissionToggle("Allow Snow Form", "allowSnowForm", true, Category.ENVIRONMENT)
        ));
    
        // Entity toggles
        toggles.put(Category.ENTITY, Arrays.asList(
            new PermissionToggle("Allow PvP", "allowPvP", false, Category.ENTITY),
            new PermissionToggle("Allow Monster Spawning", "allowMonsterSpawn", false, Category.ENTITY),
            new PermissionToggle("Allow Animal Spawning", "allowAnimalSpawn", true, Category.ENTITY),
            new PermissionToggle("Allow Entity Damage", "allowDamageEntities", false, Category.ENTITY),
            new PermissionToggle("Allow Animal Breeding", "allowBreeding", true, Category.ENTITY),
            new PermissionToggle("Allow Monster Target", "allowMonsterTarget", false, Category.ENTITY),
            new PermissionToggle("Allow Leashing", "allowLeashing", true, Category.ENTITY)
        ));
    
        // Technical toggles
        toggles.put(Category.TECHNICAL, Arrays.asList(
            new PermissionToggle("Allow Redstone", "allowRedstone", true, Category.TECHNICAL),
            new PermissionToggle("Allow Pistons", "allowPistons", true, Category.TECHNICAL),
            new PermissionToggle("Allow Hoppers", "allowHopper", true, Category.TECHNICAL),
            new PermissionToggle("Allow Dispensers", "allowDispenser", true, Category.TECHNICAL)
        ));
    
        // Special toggles
        toggles.put(Category.SPECIAL, Arrays.asList(
            new PermissionToggle("Allow Item Drops", "allowItemDrop", true, Category.SPECIAL),
            new PermissionToggle("Allow Item Pickup", "allowItemPickup", true, Category.SPECIAL),
            new PermissionToggle("Allow XP Drops", "allowXPDrop", true, Category.SPECIAL),
            new PermissionToggle("Allow XP Pickup", "allowXPPickup", true, Category.SPECIAL),
            new PermissionToggle("Allow Vehicle Place", "allowVehiclePlace", true, Category.SPECIAL),
            new PermissionToggle("Allow Vehicle Break", "allowVehicleBreak", false, Category.SPECIAL),
            new PermissionToggle("Allow Vehicle Enter", "allowVehicleEnter", true, Category.SPECIAL),
            new PermissionToggle("Allow Vehicle Collision", "allowVehicleCollide", true, Category.SPECIAL),
            new PermissionToggle("Allow TNT", "allowTNT", false, Category.SPECIAL),
            new PermissionToggle("Allow Creeper", "allowCreeper", false, Category.SPECIAL),
            new PermissionToggle("Allow Bed Explosions", "allowBedExplosion", false, Category.SPECIAL),
            new PermissionToggle("Allow Crystal Explosions", "allowCrystalExplosion", false, Category.SPECIAL),
            new PermissionToggle("Allow Fall Damage", "allowFallDamage", true, Category.SPECIAL),
            new PermissionToggle("Allow Hunger", "allowHunger", true, Category.SPECIAL),
            new PermissionToggle("Allow Flight", "allowFlight", false, Category.SPECIAL),
            new PermissionToggle("Allow Ender Pearl", "allowEnderPearl", false, Category.SPECIAL),
            new PermissionToggle("Allow Chorus Fruit", "allowChorusFruit", false, Category.SPECIAL)
        ));
    
        return toggles;
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
    
    public static final String PERMISSION_PREFIX = "adminarea.";
    
    // Standardize common permission nodes
    public static final String PERM_BUILD = "allowBlockPlace";
    public static final String PERM_BREAK = "allowBlockBreak";
    public static final String PERM_INTERACT = "allowInteract";
    public static final String PERM_ADMIN = "admin";
    public static final String PERM_BYPASS = "bypass";
    
    // Category-based permissions are derived from static permission map
    private static final Map<Category, List<PermissionToggle>> TOGGLE_MAP;
    
    static {
        TOGGLE_MAP = new EnumMap<>(Category.class);
        
        // Building toggles
        TOGGLE_MAP.put(Category.BUILDING, Arrays.asList(
            new PermissionToggle("Allow Building", PERM_BUILD, false, Category.BUILDING),
            new PermissionToggle("Allow Breaking", PERM_BREAK, false, Category.BUILDING),
            new PermissionToggle("Allow Interaction", PERM_INTERACT, false, Category.BUILDING),
            new PermissionToggle("Allow Container Access", "allowContainer", false, Category.BUILDING),
            new PermissionToggle("Allow Item Frame Usage", "allowItemFrame", false, Category.BUILDING),
            new PermissionToggle("Allow Armor Stand Usage", "allowArmorStand", false, Category.BUILDING)
        ));
        
        // Environment toggles
        TOGGLE_MAP.put(Category.ENVIRONMENT, Arrays.asList(
            new PermissionToggle("Allow Fire Spread", "allowFire", false, Category.ENVIRONMENT),
            new PermissionToggle("Allow Liquids", "allowLiquid", true, Category.ENVIRONMENT),
            new PermissionToggle("Allow Block Spread", "allowBlockSpread", true, Category.ENVIRONMENT),
            new PermissionToggle("Allow Leaf Decay", "allowLeafDecay", true, Category.ENVIRONMENT),
            new PermissionToggle("Allow Ice Form", "allowIceForm", true, Category.ENVIRONMENT),
            new PermissionToggle("Allow Snow Form", "allowSnowForm", true, Category.ENVIRONMENT)
        ));
        
        // Entity toggles
        TOGGLE_MAP.put(Category.ENTITY, Arrays.asList(
            new PermissionToggle("Allow PvP", "allowPvP", false, Category.ENTITY),
            new PermissionToggle("Allow Monster Spawning", "allowMonsterSpawn", false, Category.ENTITY),
            new PermissionToggle("Allow Animal Spawning", "allowAnimalSpawn", true, Category.ENTITY),
            new PermissionToggle("Allow Entity Damage", "allowDamageEntities", false, Category.ENTITY),
            new PermissionToggle("Allow Animal Breeding", "allowBreeding", true, Category.ENTITY),
            new PermissionToggle("Allow Monster Target", "allowMonsterTarget", false, Category.ENTITY),
            new PermissionToggle("Allow Leashing", "allowLeashing", true, Category.ENTITY)
        ));
        
        // Technical toggles
        TOGGLE_MAP.put(Category.TECHNICAL, Arrays.asList(
            new PermissionToggle("Allow Redstone", "allowRedstone", true, Category.TECHNICAL),
            new PermissionToggle("Allow Pistons", "allowPistons", true, Category.TECHNICAL),
            new PermissionToggle("Allow Hoppers", "allowHopper", true, Category.TECHNICAL),
            new PermissionToggle("Allow Dispensers", "allowDispenser", true, Category.TECHNICAL)
        ));
        
        // Special toggles - Add missing toggles here
        TOGGLE_MAP.put(Category.SPECIAL, Arrays.asList(
            new PermissionToggle("Allow Item Drops", "allowItemDrop", true, Category.SPECIAL),
            new PermissionToggle("Allow Item Pickup", "allowItemPickup", true, Category.SPECIAL),
            new PermissionToggle("Allow XP Drops", "allowXPDrop", true, Category.SPECIAL),
            new PermissionToggle("Allow XP Pickup", "allowXPPickup", true, Category.SPECIAL),
            new PermissionToggle("Allow Vehicle Place", "allowVehiclePlace", true, Category.SPECIAL),
            new PermissionToggle("Allow Vehicle Break", "allowVehicleBreak", false, Category.SPECIAL),
            new PermissionToggle("Allow Vehicle Enter", "allowVehicleEnter", true, Category.SPECIAL),
            new PermissionToggle("Allow Vehicle Collision", "allowVehicleCollide", true, Category.SPECIAL),
            new PermissionToggle("Allow TNT", "allowTNT", false, Category.SPECIAL),
            new PermissionToggle("Allow Creeper", "allowCreeper", false, Category.SPECIAL),
            new PermissionToggle("Allow Bed Explosions", "allowBedExplosion", false, Category.SPECIAL),
            new PermissionToggle("Allow Crystal Explosions", "allowCrystalExplosion", false, Category.SPECIAL),
            new PermissionToggle("Allow Fall Damage", "allowFallDamage", true, Category.SPECIAL),
            new PermissionToggle("Allow Hunger", "allowHunger", true, Category.SPECIAL),
            new PermissionToggle("Allow Flight", "allowFlight", false, Category.SPECIAL),
            new PermissionToggle("Allow Ender Pearl", "allowEnderPearl", false, Category.SPECIAL),
            new PermissionToggle("Allow Chorus Fruit", "allowChorusFruit", false, Category.SPECIAL)
        ));
    }

    /**
     * Get the category this toggle belongs to
     * @return The toggle's category
     */
    public Category getCategory() {
        return category;
    }

    /**
     * Look up a toggle by its permission node name
     * @param toggleName The name of the toggle to find
     * @return The PermissionToggle if found, null otherwise
     */
    public static PermissionToggle getToggle(String toggleName) {
        // Search through all categories
        for (Map.Entry<Category, List<PermissionToggle>> entry : getTogglesByCategory().entrySet()) {
            // Search toggles in this category
            for (PermissionToggle toggle : entry.getValue()) {
                if (toggle.getPermissionNode().equals(toggleName)) {
                    return toggle;
                }
            }
        }
        return null;
    }
}
