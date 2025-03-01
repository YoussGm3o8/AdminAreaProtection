package adminarea.managers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaDTO;
import adminarea.area.AreaBuilder;
import adminarea.exception.DatabaseException;
import adminarea.permissions.PermissionToggle;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class DatabaseManager {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseManager.class);
    private final AdminAreaProtectionPlugin plugin;
    private final HikariDataSource dataSource;
    private final Cache<String, Area> areaCache;
    private static final int CACHE_SIZE = 100;
    private static final long CACHE_DURATION = TimeUnit.MINUTES.toMillis(5);

    public DatabaseManager(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        this.dataSource = initializeDataSource();
        this.areaCache = Caffeine.newBuilder()
            .maximumSize(CACHE_SIZE)
            .expireAfterWrite(CACHE_DURATION, TimeUnit.MILLISECONDS)
            .build();
    }

    private HikariDataSource initializeDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + plugin.getDataFolder() + "/areas.db");
        config.setMaximumPoolSize(20);  // Increased from 10 to handle more concurrent requests
        config.setMinimumIdle(10);      // Increased to keep more connections ready
        config.setIdleTimeout(600000);  // 10 minutes - increased to keep connections longer
        config.setMaxLifetime(1800000); // 30 minutes - increased for longer sessions
        config.setConnectionTimeout(5000); // 5 seconds - reduced to fail faster if there's an issue
        config.setConnectionTestQuery("SELECT 1");

        // Only enable HikariCP debug logging if plugin debug mode is on
        if (!plugin.isDebugMode()) {
            //config.addDataSourceProperty("logger", "none");
            //config.setPoolName("HikariPool-AdminArea");
            //LoggerFactory.getLogger("com.zaxxer.hikari").warn("Disabling HikariCP logging");
        }

        return new HikariDataSource(config);
    }

    public void init() throws DatabaseException {
        try {
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                
                conn.setAutoCommit(false);
                try {
                    // Create areas table
                    stmt.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS areas (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            name TEXT UNIQUE,
                            world TEXT,
                            x_min INTEGER,
                            x_max INTEGER,
                            y_min INTEGER,
                            y_max INTEGER,
                            z_min INTEGER,
                            z_max INTEGER,
                            priority INTEGER,
                            show_title BOOLEAN,
                            group_permissions TEXT,
                            inherited_permissions TEXT,
                            enter_message TEXT,
                            leave_message TEXT,
                            toggle_states TEXT DEFAULT '{}',
                            default_toggle_states TEXT DEFAULT '{}',
                            inherited_toggle_states TEXT DEFAULT '{}',
                            track_permissions TEXT DEFAULT '{}',
                            player_permissions TEXT DEFAULT '{}',
                            potion_effects TEXT DEFAULT '{}'
                        )
                    """);

                    // Check if potion_effects column exists, and add it if it doesn't
                    try (ResultSet rs = conn.getMetaData().getColumns(null, null, "areas", "potion_effects")) {
                        if (!rs.next()) {
                            // Column doesn't exist, add it
                            if (plugin.isDebugMode()) {
                                plugin.debug("Adding potion_effects column to areas table");
                            }
                            stmt.executeUpdate("ALTER TABLE areas ADD COLUMN potion_effects TEXT DEFAULT '{}'");
                        }
                    }

                    // Check if settings column exists and migrate data
                    try (ResultSet rs = conn.getMetaData().getColumns(null, null, "areas", "settings")) {
                        if (rs.next()) {
                            // Column exists, migrate data
                            if (plugin.isDebugMode()) {
                                plugin.debug("Migrating settings data to toggle_states");
                            }

                            // Get all areas with settings data
                            try (ResultSet areas = stmt.executeQuery("SELECT name, settings, toggle_states FROM areas WHERE settings IS NOT NULL")) {
                                while (areas.next()) {
                                    String name = areas.getString("name");
                                    String settings = areas.getString("settings");
                                    String toggleStates = areas.getString("toggle_states");

                                    // Merge settings into toggle_states
                                    JSONObject merged = new JSONObject(toggleStates);
                                    JSONObject settingsObj = new JSONObject(settings);
                                    for (String key : settingsObj.keySet()) {
                                        if (!merged.has(key)) {
                                            merged.put(key, settingsObj.getBoolean(key));
                                        }
                                    }

                                    // Update the area with merged data
                                    try (PreparedStatement ps = conn.prepareStatement("UPDATE areas SET toggle_states = ? WHERE name = ?")) {
                                        ps.setString(1, merged.toString());
                                        ps.setString(2, name);
                                        ps.executeUpdate();
                                    }
                                }
                            }

                            // Drop settings column
                            stmt.executeUpdate("ALTER TABLE areas DROP COLUMN settings");
                            
                            if (plugin.isDebugMode()) {
                                plugin.debug("Migration complete - settings column removed");
                            }
                        }
                    }

                    conn.commit();
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                }
            }

        } catch (SQLException e) {
            throw new DatabaseException("Failed to initialize database connection", e);
        }
    }

    public void saveArea(Area area) throws DatabaseException {
        if (plugin.isDebugMode()) {
            plugin.debug("Saving area to database: " + area.getName());
            plugin.debug("  Toggle states to save: " + area.toDTO().toggleStates());
            plugin.debug("  Potion effects to save: " + area.toDTO().potionEffects());
        }
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                "INSERT OR REPLACE INTO areas (name, world, x_min, x_max, y_min, y_max, z_min, z_max, " +
                "priority, show_title, group_permissions, inherited_permissions, enter_message, leave_message, " +
                "toggle_states, default_toggle_states, inherited_toggle_states, track_permissions, player_permissions, potion_effects) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
             )) {

            AreaDTO dto = area.toDTO();
            stmt.setString(1, dto.name());
            stmt.setString(2, dto.world());
            stmt.setInt(3, dto.bounds().xMin());
            stmt.setInt(4, dto.bounds().xMax());
            stmt.setInt(5, dto.bounds().yMin());
            stmt.setInt(6, dto.bounds().yMax());
            stmt.setInt(7, dto.bounds().zMin());
            stmt.setInt(8, dto.bounds().zMax());
            stmt.setInt(9, dto.priority());
            stmt.setBoolean(10, dto.showTitle());

            // Convert maps to JSON strings
            stmt.setString(11, new JSONObject(dto.groupPermissions()).toString());
            stmt.setString(12, new JSONObject(dto.inheritedPermissions()).toString());
            
            // Store enter/leave messages
            stmt.setString(13, dto.enterMessage());
            stmt.setString(14, dto.leaveMessage());
            
            // Save toggle states JSON - ensure it's not an empty object if there are actual toggle states
            String toggleStatesJson = dto.toggleStates().toString();
            if (toggleStatesJson.equals("{}") && !area.getAllToggleKeys().isEmpty()) {
                // If the JSON is empty but we have toggle keys, something is wrong
                // Try to rebuild the toggle states
                JSONObject rebuiltToggles = new JSONObject();
                for (String key : area.getAllToggleKeys()) {
                    rebuiltToggles.put(key, area.getToggleState(key));
                }
                
                if (rebuiltToggles.length() == 0) {
                    // Still no toggles - something is seriously wrong, use defaults
                    plugin.debug("  No toggle states found, applying defaults");
                    for (PermissionToggle toggle : PermissionToggle.getDefaultToggles()) {
                        String permNode = toggle.getPermissionNode();
                        if (!permNode.startsWith("gui.permissions.toggles.")) {
                            permNode = "gui.permissions.toggles." + permNode;
                        }
                        rebuiltToggles.put(permNode, toggle.getDefaultValue());
                    }
                }
                
                toggleStatesJson = rebuiltToggles.toString();
                if (plugin.isDebugMode()) {
                    plugin.debug("  Rebuilt toggle states: " + toggleStatesJson);
                }
            }
            stmt.setString(15, toggleStatesJson);
            
            stmt.setString(16, dto.defaultToggleStates().toString());
            stmt.setString(17, dto.inheritedToggleStates().toString());
            stmt.setString(18, new JSONObject(dto.trackPermissions()).toString());
            stmt.setString(19, new JSONObject(dto.playerPermissions()).toString());
            
            // Save potion effects JSON - ensure it's not empty if there are actual potion effects
            String potionEffectsJson = dto.potionEffects().toString();
            if (potionEffectsJson.equals("{}") && area.hasPotionEffects()) {
                // Try to rebuild the potion effects if we have them but they're not in the JSON
                // This would need to be customized based on how your potion effects are stored
                try {
                    java.lang.reflect.Method method = area.getClass().getMethod("getPotionEffectsAsJSON");
                    Object result = method.invoke(area);
                    if (result instanceof JSONObject) {
                        potionEffectsJson = result.toString();
                    }
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("  Rebuilt potion effects: " + potionEffectsJson);
                    }
                } catch (Exception e) {
                    if (plugin.isDebugMode()) {
                        plugin.debug("  Could not rebuild potion effects: " + e.getMessage());
                    }
                }
            }
            stmt.setString(20, potionEffectsJson);

            stmt.executeUpdate();
            
            if (plugin.isDebugMode()) {
                // Verify what was actually saved by reading it back
                try {
                    Area freshArea = loadArea(area.getName());
                    if (freshArea != null) {
                        plugin.debug("  Verified saved area from database:");
                        plugin.debug("  Toggle states in DB: " + freshArea.toDTO().toggleStates());
                        plugin.debug("  Potion effects in DB: " + freshArea.toDTO().potionEffects());
                    }
                } catch (Exception e) {
                    plugin.debug("  Failed to verify saved area: " + e.getMessage());
                }
            }

        } catch (SQLException e) {
            throw new DatabaseException("Failed to save area: " + area.getName(), e);
        }
    }

    /**
     * Ensures that the area has a title configuration in config.yml
     * This helps recover config entries if the file is reset
     */
    private void ensureAreaTitlesConfig(AreaDTO dto) {
        if (!dto.showTitle()) {
            return;
        }
        
        // Check if area title config exists
        String basePath = "areaTitles." + dto.name();
        if (!plugin.getConfigManager().exists(basePath)) {
            // No config entry exists, create from saved data
            plugin.getConfigManager().set(basePath + ".enter.main", "§6Welcome to " + dto.name());
            plugin.getConfigManager().set(basePath + ".enter.subtitle", dto.enterMessage());
            plugin.getConfigManager().set(basePath + ".enter.fadeIn", 20);
            plugin.getConfigManager().set(basePath + ".enter.stay", 40);
            plugin.getConfigManager().set(basePath + ".enter.fadeOut", 20);
            
            plugin.getConfigManager().set(basePath + ".leave.main", "§6Leaving " + dto.name());
            plugin.getConfigManager().set(basePath + ".leave.subtitle", dto.leaveMessage());
            plugin.getConfigManager().set(basePath + ".leave.fadeIn", 20);
            plugin.getConfigManager().set(basePath + ".leave.stay", 40);
            plugin.getConfigManager().set(basePath + ".leave.fadeOut", 20);
            
            plugin.getConfigManager().save();
            plugin.debug("Recreated missing title config for area " + dto.name());
        }
    }

    public void updateArea(Area area) throws DatabaseException {
        if (plugin.isDebugMode()) {
            plugin.debug("Updating area in database: " + area.getName());
            plugin.debug("Toggle states being saved: " + area.toDTO().toggleStates());
        }
        
        // Force area to clear its caches before saving
        area.clearCaches();
        
        // Save to database
        saveArea(area);
        
        if (plugin.isDebugMode()) {
            plugin.debug("Area updated in database successfully");
        }
    }

    public void deleteArea(String name) throws DatabaseException {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM areas WHERE name = ?")) {
            ps.setString(1, name);
            ps.executeUpdate();
            
            // Remove from cache
            areaCache.invalidate(name);
        } catch (SQLException e) {
            throw new DatabaseException("Failed to delete area: " + name, e);
        }
    }

    public List<Area> loadAreas() throws DatabaseException {
        List<Area> areas = new ArrayList<>();
        
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM areas")) {
            
            while (rs.next()) {
                Area area = buildAreaFromResultSet(rs);
                areas.add(area);
                areaCache.put(area.getName(), area);
            }
            
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load areas", e);
        }
        
        return areas;
    }

    private Area buildAreaFromResultSet(ResultSet rs) throws SQLException {
        String areaName = rs.getString("name");
        if (plugin.isDebugMode()) {
            plugin.debug("Loading area " + areaName + " from database");
        }

        // Parse JSON fields
        JSONObject toggleStates = new JSONObject(rs.getString("toggle_states"));
        JSONObject defaultToggleStates = new JSONObject(rs.getString("default_toggle_states"));
        JSONObject inheritedToggleStates = new JSONObject(rs.getString("inherited_toggle_states"));
        
        // Get potion effects JSON
        String potionEffectsJson = rs.getString("potion_effects");
        JSONObject potionEffects = potionEffectsJson != null && !potionEffectsJson.isEmpty() ? 
            new JSONObject(potionEffectsJson) : new JSONObject();
        
        if (plugin.isDebugMode()) {
            plugin.debug("  Loaded potion effects from DB: " + potionEffects.toString());
        }
        
        // Get raw player permissions JSON
        String playerPermsJson = rs.getString("player_permissions");
        if (plugin.isDebugMode()) {
            plugin.debug("  Raw player permissions from DB: " + playerPermsJson);
        }

        // Parse permissions
        Map<String, Map<String, Boolean>> groupPerms = parseJsonToMap(rs.getString("group_permissions"));
        Map<String, Map<String, Boolean>> inheritedPerms = parseJsonToMap(rs.getString("inherited_permissions"));
        Map<String, Map<String, Boolean>> trackPerms = parseJsonToMap(rs.getString("track_permissions"));
        Map<String, Map<String, Boolean>> playerPerms = parseJsonToMap(playerPermsJson);

        if (plugin.isDebugMode()) {
            plugin.debug("  Parsed player permissions: " + playerPerms);
            plugin.debug("  Player permissions for YoussGm3o8: " + 
                (playerPerms.containsKey("YoussGm3o8") ? playerPerms.get("YoussGm3o8") : "none"));
        }

        // Get bounds
        AreaDTO.Bounds bounds = new AreaDTO.Bounds(
            rs.getInt("x_min"),
            rs.getInt("x_max"),
            rs.getInt("y_min"),
            rs.getInt("y_max"),
            rs.getInt("z_min"),
            rs.getInt("z_max")
        );

        // Convert toggle states to permissions map
        Map<String, Boolean> permissionsMap = new HashMap<>();
        for (String key : toggleStates.keySet()) {
            permissionsMap.put(key, toggleStates.getBoolean(key));
        }

        // Create DTO
        AreaDTO dto = new AreaDTO(
            areaName,
            rs.getString("world"),
            bounds,
            rs.getInt("priority"),
            rs.getBoolean("show_title"),
            toggleStates,
            groupPerms,
            inheritedPerms,
            toggleStates,
            defaultToggleStates,
            inheritedToggleStates,
            AreaDTO.Permissions.fromMap(permissionsMap),
            rs.getString("enter_message"),
            rs.getString("leave_message"),
            trackPerms,
            playerPerms,
            potionEffects
        );

        if (plugin.isDebugMode()) {
            plugin.debug("Successfully loaded area " + areaName);
            plugin.debug("  Final player permissions in DTO: " + dto.playerPermissions());
        }

        return AreaBuilder.fromDTO(dto).build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Boolean>> parseJsonToMap(String json) {
        if (json == null || json.isEmpty() || json.equals("{}")) {
            if (plugin.isDebugMode()) {
                plugin.debug("  parseJsonToMap: Input JSON is null, empty, or {}");
            }
            return new HashMap<>();
        }
        
        if (plugin.isDebugMode()) {
            plugin.debug("  parseJsonToMap: Parsing JSON: " + json);
        }
        
        Map<String, Map<String, Boolean>> result = new HashMap<>();
        try {
            JSONObject jsonObj = new JSONObject(json);
            
            for (String key : jsonObj.keySet()) {
                if (plugin.isDebugMode()) {
                    plugin.debug("  parseJsonToMap: Processing key: " + key);
                    plugin.debug("  parseJsonToMap: Value: " + jsonObj.get(key));
                }

                Object value = jsonObj.get(key);
                Map<String, Boolean> innerMap = new HashMap<>();

                if (value instanceof JSONObject) {
                    JSONObject innerJson = (JSONObject) value;
                    for (String permKey : innerJson.keySet()) {
                        if (plugin.isDebugMode()) {
                            plugin.debug("    Processing permission: " + permKey + " = " + innerJson.get(permKey));
                        }
                        if (innerJson.get(permKey) instanceof Boolean) {
                            innerMap.put(permKey, innerJson.getBoolean(permKey));
                        }
                    }
                }

                if (!innerMap.isEmpty()) {
                    if (plugin.isDebugMode()) {
                        plugin.debug("  Adding permissions for " + key + ": " + innerMap);
                    }
                    result.put(key, innerMap);
                }
            }
        } catch (Exception e) {
            if (plugin.isDebugMode()) {
                plugin.debug("  parseJsonToMap: Error parsing JSON: " + e.getMessage());
                e.printStackTrace();
            }
            return new HashMap<>();
        }

        if (plugin.isDebugMode()) {
            plugin.debug("  parseJsonToMap: Final result: " + result);
        }
        
        return result;
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    /**
     * Loads a single area by name from the database.
     * 
     * @param areaName The name of the area to load
     * @return The loaded area, or null if not found
     * @throws DatabaseException If there's an error accessing the database
     */
    public Area loadArea(String areaName) throws DatabaseException {
        if (areaName == null || areaName.isEmpty()) {
            throw new IllegalArgumentException("Area name cannot be null or empty");
        }
        
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT * FROM areas WHERE name = ?")) {
            
            stmt.setString(1, areaName);
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Area area = buildAreaFromResultSet(rs);
                    areaCache.put(area.getName(), area);
                    return area;
                }
            }
            
            return null; // Area not found
            
        } catch (SQLException e) {
            throw new DatabaseException("Failed to load area: " + areaName, e);
        }
    }

    /**
     * Updates a single toggle state in the database without recreating the entire area
     */
    public void updateAreaToggleState(String areaName, String permission, Object value) throws DatabaseException {
        try (Connection conn = getConnection()) {
            // First load existing area data
            Area area = loadArea(areaName);
            if (area == null) {
                throw new DatabaseException("Area not found: " + areaName);
            }

            // Get current toggle states
            JSONObject toggleStates = area.toDTO().toggleStates();
            
            // Update the specific toggle
            toggleStates.put(permission, value);

            // Update database with new toggle states - only update toggle_states column
            try (PreparedStatement stmt = conn.prepareStatement(
                "UPDATE areas SET toggle_states = ? WHERE name = ?")) {
                stmt.setString(1, toggleStates.toString());
                stmt.setString(2, areaName);
                stmt.executeUpdate();
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Updated toggle state in database for area " + areaName);
                    plugin.debug("  Permission: " + permission + ", Value: " + value);
                    plugin.debug("  New toggle states: " + toggleStates.toString());
                }
            }

        } catch (SQLException e) {
            throw new DatabaseException("Failed to update toggle state", e);
        }
    }
}
