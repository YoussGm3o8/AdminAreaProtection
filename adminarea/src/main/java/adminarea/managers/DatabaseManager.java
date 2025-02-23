package adminarea.managers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaDTO;
import adminarea.area.AreaBuilder;
import adminarea.exception.DatabaseException;
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
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(5);
        config.setIdleTimeout(300000); // 5 minutes
        config.setMaxLifetime(600000); // 10 minutes
        config.setConnectionTimeout(30000);
        config.setConnectionTestQuery("SELECT 1");
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
                            player_permissions TEXT DEFAULT '{}'
                        )
                    """);

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
        if (area == null) {
            throw new DatabaseException("Cannot save null area");
        }

        AreaDTO dto = area.toDTO();
        
        // Ensure all settings are in toggle_states
        JSONObject toggleStates = dto.toggleStates();
        Map<String, Boolean> permissions = dto.permissions().toMap();
        
        if (plugin.isDebugMode()) {
            plugin.debug("Saving area " + dto.name() + " with permissions:");
            plugin.debug("  Initial toggle states: " + toggleStates.toString());
            plugin.debug("  Permissions to merge: " + permissions);
            plugin.debug("  Player permissions before save: " + dto.playerPermissions());
        }

        for (Map.Entry<String, Boolean> entry : permissions.entrySet()) {
            if (!toggleStates.has(entry.getKey())) {
                toggleStates.put(entry.getKey(), entry.getValue());
                if (plugin.isDebugMode()) {
                    plugin.debug("  Adding missing toggle: " + entry.getKey() + " = " + entry.getValue());
                }
            }
        }

        if (plugin.isDebugMode()) {
            plugin.debug("  Final toggle states: " + toggleStates.toString());
        }

        // Convert player permissions to JSON
        JSONObject playerPermsJson = new JSONObject(dto.playerPermissions());
        if (plugin.isDebugMode()) {
            plugin.debug("  Player permissions JSON to save: " + playerPermsJson.toString(2));
        }

        String sql = """
            INSERT OR REPLACE INTO areas (
                name, world, x_min, x_max, y_min, y_max, z_min, z_max,
                priority, show_title, group_permissions, inherited_permissions,
                enter_message, leave_message, toggle_states, default_toggle_states,
                inherited_toggle_states, track_permissions, player_permissions
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            
            AreaDTO.Bounds bounds = dto.bounds();
            ps.setString(1, dto.name());
            ps.setString(2, dto.world());
            ps.setInt(3, bounds.xMin());
            ps.setInt(4, bounds.xMax());
            ps.setInt(5, bounds.yMin());
            ps.setInt(6, bounds.yMax());
            ps.setInt(7, bounds.zMin());
            ps.setInt(8, bounds.zMax());
            ps.setInt(9, dto.priority());
            ps.setBoolean(10, dto.showTitle());
            ps.setString(11, new JSONObject(dto.groupPermissions()).toString());
            ps.setString(12, new JSONObject(dto.inheritedPermissions()).toString());
            ps.setString(13, dto.enterMessage());
            ps.setString(14, dto.leaveMessage());
            ps.setString(15, toggleStates.toString());
            ps.setString(16, dto.defaultToggleStates().toString());
            ps.setString(17, dto.inheritedToggleStates().toString());
            ps.setString(18, new JSONObject(dto.trackPermissions()).toString());
            ps.setString(19, playerPermsJson.toString());
            
            ps.executeUpdate();
            
            // Update cache
            areaCache.put(dto.name(), area);
            
            if (plugin.isDebugMode()) {
                plugin.debug("Successfully saved area " + dto.name() + " to database");
                plugin.debug("  Toggle states saved: " + toggleStates.toString());
                plugin.debug("  Group permissions: " + dto.groupPermissions());
                plugin.debug("  Track permissions: " + dto.trackPermissions());
                plugin.debug("  Player permissions saved: " + playerPermsJson.toString(2));
                
                // Verify the save by reading back
                try (PreparedStatement readPs = conn.prepareStatement("SELECT player_permissions FROM areas WHERE name = ?")) {
                    readPs.setString(1, dto.name());
                    try (ResultSet rs = readPs.executeQuery()) {
                        if (rs.next()) {
                            String savedPerms = rs.getString("player_permissions");
                            plugin.debug("  Verified player permissions in database: " + savedPerms);
                        }
                    }
                }
            }
            
        } catch (SQLException e) {
            throw new DatabaseException("Failed to save area: " + area.getName(), e);
        }
    }

    public void updateArea(Area area) throws DatabaseException {
        saveArea(area); // Since we're using INSERT OR REPLACE
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
            playerPerms
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
}
