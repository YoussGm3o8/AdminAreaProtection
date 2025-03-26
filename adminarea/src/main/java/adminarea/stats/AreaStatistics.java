package adminarea.stats;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.util.ValidationUtils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer.Sample;

import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Collectors;

/**
 * Manages statistics tracking for protected areas including interactions,
 * violations, and usage patterns.
 */
public class AreaStatistics implements AutoCloseable {
    private final AdminAreaProtectionPlugin plugin;
    private Connection dbConnection;
    private final ScheduledExecutorService scheduler;
    private final Map<String, AtomicInteger> interactionCounters;
    private final Map<String, AtomicInteger> violationCounters;
    private final Map<String, AtomicInteger> permissionChecks;
    private final ConcurrentLinkedQueue<AreaModification> modificationHistory;
    private final MeterRegistry meterRegistry;
    private final Path backupPath;
    private final Instant creationTime;
    private final Map<String, AtomicLong> eventCounts;
    
    private static final int MAX_HISTORY_SIZE = 1000;
    private static final int BACKUP_INTERVAL_HOURS = 24;
    private static final String DB_FILE = "area_statistics.db";
    private static final String STATS_TABLE = "area_statistics";

    public AreaStatistics(AdminAreaProtectionPlugin plugin) throws SQLException {
        this.plugin = plugin;
        this.creationTime = Instant.now();
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.interactionCounters = new ConcurrentHashMap<>();
        this.violationCounters = new ConcurrentHashMap<>();
        this.permissionChecks = new ConcurrentHashMap<>();
        this.modificationHistory = new ConcurrentLinkedQueue<>();
        this.meterRegistry = plugin.getPerformanceMonitor().getRegistry();
        this.backupPath = plugin.getDataFolder().toPath().resolve("statistics_backup");
        this.eventCounts = new ConcurrentHashMap<>();
        
        initializeDatabase();
        setupScheduledTasks();
    }

    private void initializeDatabase() throws SQLException {
        Path dbPath = plugin.getDataFolder().toPath().resolve(DB_FILE);
        dbConnection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        
        try (Statement stmt = dbConnection.createStatement()) {
            // Create tables
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS interactions (
                    area_id TEXT NOT NULL,
                    player_id TEXT NOT NULL,
                    action_type TEXT NOT NULL,
                    timestamp DATETIME DEFAULT CURRENT_TIMESTAMP
                )
            """);
            
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS violations (
                    area_id TEXT NOT NULL,
                    player_id TEXT NOT NULL,
                    violation_type TEXT NOT NULL,
                    timestamp DATETIME DEFAULT CURRENT_TIMESTAMP
                )
            """);
            
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS modifications (
                    area_id TEXT NOT NULL,
                    player_id TEXT NOT NULL,
                    modification_type TEXT NOT NULL,
                    details TEXT,
                    timestamp DATETIME DEFAULT CURRENT_TIMESTAMP
                )
            """);
            
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS area_statistics (
                    area_name TEXT NOT NULL,
                    event_type TEXT NOT NULL,
                    count INTEGER NOT NULL,
                    last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (area_name, event_type)
                )
            """);
            
            // Create indexes
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_interactions_area ON interactions(area_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_violations_area ON violations(area_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_modifications_area ON modifications(area_id)");
        }
    }

    private void setupScheduledTasks() {
        // Schedule periodic data aggregation
        scheduler.scheduleAtFixedRate(this::aggregateData, 1, 1, TimeUnit.HOURS);
        
        // Schedule backups
        scheduler.scheduleAtFixedRate(this::performBackup, 
            BACKUP_INTERVAL_HOURS, BACKUP_INTERVAL_HOURS, TimeUnit.HOURS);
        
        // Schedule cleanup
        scheduler.scheduleAtFixedRate(this::cleanup, 1, 1, TimeUnit.DAYS);
    }

    public void recordInteraction(String areaId, String playerId, String actionType) {
        ValidationUtils.validateAreaName(areaId);
        try {
            // Update in-memory counter
            interactionCounters.computeIfAbsent(areaId, k -> new AtomicInteger())
                             .incrementAndGet();
            
            // Record in database
            try (PreparedStatement stmt = dbConnection.prepareStatement(
                "INSERT INTO interactions (area_id, player_id, action_type) VALUES (?, ?, ?)")) {
                stmt.setString(1, areaId);
                stmt.setString(2, playerId);
                stmt.setString(3, actionType);
                stmt.executeUpdate();
            }
            
            // Update metrics
            Counter.builder("area.interactions")
                  .tag("area", areaId)
                  .tag("action", actionType)
                  .register(meterRegistry)
                  .increment();
        } catch (SQLException e) {
            plugin.getLogger().error("Failed to record interaction", e);
        }
    }

    public void recordViolation(String areaId, String playerId, String violationType) {
        try {
            // Fix: Increment counter first before DB operation
            violationCounters.computeIfAbsent(areaId, k -> new AtomicInteger()).incrementAndGet();
            
            try (PreparedStatement stmt = dbConnection.prepareStatement(
                "INSERT INTO violations (area_id, player_id, violation_type) VALUES (?, ?, ?)")) {
                stmt.setString(1, areaId);
                stmt.setString(2, playerId); 
                stmt.setString(3, violationType);
                stmt.executeUpdate();
            }
            
            Counter.builder("area.violations")
                  .tag("area", areaId)
                  .tag("type", violationType) 
                  .register(meterRegistry)
                  .increment();

    } catch (SQLException e) {
        plugin.getLogger().error("Failed to record violation", e);
    }
}

    public void recordModification(String areaId, String playerId, 
                                 String modificationType, String details) {
        try {
            AreaModification mod = new AreaModification(areaId, playerId, 
                modificationType, details, Instant.now());
            
            modificationHistory.offer(mod);
            while (modificationHistory.size() > MAX_HISTORY_SIZE) {
                modificationHistory.poll();
            }
            
            try (PreparedStatement stmt = dbConnection.prepareStatement(
                "INSERT INTO modifications (area_id, player_id, modification_type, details) VALUES (?, ?, ?, ?)")) {
                stmt.setString(1, areaId);
                stmt.setString(2, playerId);
                stmt.setString(3, modificationType);
                stmt.setString(4, details);
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().error("Failed to record modification", e);
        }
    }

    /**
     * Increments the counter for a specific event type.
     * @param eventType The type of event to increment
     */
    public void incrementEvent(String eventType) {
        Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            // Update in-memory counter
            interactionCounters.computeIfAbsent(eventType, k -> new AtomicInteger())
                             .incrementAndGet();
            
            // Record in database
            try (PreparedStatement stmt = dbConnection.prepareStatement(
                "INSERT INTO interactions (area_id, player_id, action_type) VALUES (?, 'SYSTEM', ?)")) {
                stmt.setString(1, "GENERAL");
                stmt.setString(2, eventType);
                stmt.executeUpdate();
            }
            
            // Update metrics
            Counter.builder("area.events")
                  .tag("type", eventType)
                  .register(meterRegistry)
                  .increment();
                  
        } catch (SQLException e) {
            plugin.getLogger().error("Failed to increment event counter", e);
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "increment_event");
        }
    }

    private void updateDatabase(String eventType) throws SQLException {
        long count = eventCounts.get(eventType).get();
        try (PreparedStatement stmt = dbConnection.prepareStatement(
            "INSERT OR REPLACE INTO area_statistics (area_name, event_type, count) VALUES (?, ?, ?)"
        )) {
            stmt.setString(1, plugin.getName());
            stmt.setString(2, eventType);
            stmt.setLong(3, count);
            stmt.executeUpdate();
        }
    }

    public Map<String, Integer> getInteractionStats(String areaId) {
        Map<String, Integer> stats = new HashMap<>();
        try (PreparedStatement stmt = dbConnection.prepareStatement(
            "SELECT action_type, COUNT(*) as count FROM interactions WHERE area_id = ? GROUP BY action_type")) {
            stmt.setString(1, areaId);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                stats.put(rs.getString("action_type"), rs.getInt("count"));
            }
        } catch (SQLException e) {
            plugin.getLogger().error("Failed to get interaction stats", e);
        }
        return stats;
    }

    public List<AreaModification> getRecentModifications(String areaId, int limit) {
        return modificationHistory.stream()
            .filter(mod -> mod.areaId().equals(areaId))
            .sorted(Comparator.comparing(AreaModification::timestamp).reversed())
            .limit(limit)
            .collect(Collectors.toList());
    }

    private void aggregateData() {
        try {
            // Aggregate daily statistics
            try (PreparedStatement stmt = dbConnection.prepareStatement("""
                INSERT INTO daily_stats 
                SELECT area_id, DATE(timestamp), 
                       COUNT(*) as interaction_count,
                       SUM(CASE WHEN action_type = 'VIOLATION' THEN 1 ELSE 0 END) as violation_count
                FROM interactions 
                WHERE DATE(timestamp) = DATE('now', '-1 day')
                GROUP BY area_id, DATE(timestamp)
            """)) {
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().error("Failed to aggregate data", e);
        }
    }

    private void performBackup() {
        try {
            Files.createDirectories(backupPath);
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
            Path backupFile = backupPath.resolve("statistics_" + timestamp + ".db");
            
            // Backup database
            try (PreparedStatement stmt = dbConnection.prepareStatement("BACKUP TO ?")) {
                stmt.setString(1, backupFile.toString());
                stmt.execute();
            }
            
            // Backup in-memory state
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            Map<String, Object> memoryState = new HashMap<>();
            memoryState.put("interactionCounters", interactionCounters);
            memoryState.put("violationCounters", violationCounters);
            memoryState.put("modificationHistory", modificationHistory);
            
            Files.write(backupPath.resolve("memory_state_" + timestamp + ".json"),
                gson.toJson(memoryState).getBytes());
        } catch (Exception e) {
            plugin.getLogger().error("Failed to perform backup", e);
        }
    }

    public void cleanup() {
        try {
            // Remove old data
            try (PreparedStatement stmt = dbConnection.prepareStatement(
                "DELETE FROM interactions WHERE timestamp < DATE('now', '-30 days')")) {
                stmt.executeUpdate();
            }
            
            // Remove old backups
            Files.list(backupPath)
                .filter(path -> {
                    try {
                        return Files.getLastModifiedTime(path).toInstant()
                            .isBefore(Instant.now().minus(Duration.ofDays(30)));
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
        } catch (Exception e) {
            plugin.getLogger().error("Failed to perform cleanup", e);
        }
    }

    @Override
    public void close() {
        try {
            scheduler.shutdown();
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
            performBackup();
            dbConnection.close();
        } catch (Exception e) {
            plugin.getLogger().error("Error closing AreaStatistics", e);
        }
    }

    /**
     * Gets the uptime in milliseconds since this statistics instance was created.
     * @return The uptime in milliseconds
     */
    public long getUptime() {
        return Duration.between(creationTime, Instant.now()).toMillis();
    }

    /**
     * Gets all recorded statistics as a map.
     * @return Map of event types to their counts
     */
    public Map<String, Integer> getAllStats() {
        Map<String, Integer> stats = new HashMap<>();
        interactionCounters.forEach((key, value) -> stats.put(key, value.get()));
        violationCounters.forEach((key, value) -> stats.put("violation." + key, value.get()));
        return stats;
    }

    public void exportStats(Path exportPath) {
        try {
            if (!Files.exists(exportPath)) {
                Files.createDirectories(exportPath);
            }

            Map<String, Object> stats = new HashMap<>();
            stats.put("uptime", getUptime());
            stats.put("events", getAllStats());
            stats.put("exportTime", Instant.now().toString());

            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            Path statsFile = exportPath.resolve("area_stats_" + 
                Instant.now().toString().replace(":", "-") + ".json");

            Files.write(statsFile, gson.toJson(stats).getBytes());

        } catch (Exception e) {
            plugin.getLogger().error("Failed to export statistics", e);
        }
    }

    // Add protected getter for testing
    protected Connection getConnection() {
        return dbConnection;
    }

    /**
     * Records a player visit to an area.
     * @param areaId The area identifier
     * @param playerId The player identifier
     */
    public void recordVisit(String areaId, String playerId) {
        recordInteraction(areaId, playerId, "visits");
    }
    
    /**
     * Records a block broken in an area.
     * @param areaId The area identifier
     * @param playerId The player identifier
     */
    public void recordBlockBreak(String areaId, String playerId) {
        recordInteraction(areaId, playerId, "blocks_broken");
    }
    
    /**
     * Records a block placed in an area.
     * @param areaId The area identifier
     * @param playerId The player identifier
     */
    public void recordBlockPlace(String areaId, String playerId) {
        recordInteraction(areaId, playerId, "blocks_placed");
    }
    
    /**
     * Records a pvp fight in an area.
     * @param areaId The area identifier
     * @param attackerId The attacker identifier
     * @param victimId The victim identifier
     */
    public void recordPvpFight(String areaId, String attackerId, String victimId) {
        try {
            // Update in-memory counter
            interactionCounters.computeIfAbsent("pvp_fights", k -> new AtomicInteger())
                .incrementAndGet();
            
            // Record in database with more details
            try (PreparedStatement stmt = dbConnection.prepareStatement(
                "INSERT INTO interactions (area_id, player_id, action_type, details) VALUES (?, ?, ?, ?)")) {
                stmt.setString(1, areaId);
                stmt.setString(2, attackerId);
                stmt.setString(3, "pvp_fights");
                stmt.setString(4, "victim:" + victimId);
                stmt.executeUpdate();
            }
            
            // Update metrics
            Counter.builder("area.interactions")
                  .tag("area", areaId)
                  .tag("action", "pvp_fights")
                  .register(meterRegistry)
                  .increment();
        } catch (SQLException e) {
            plugin.getLogger().error("Failed to record PvP fight", e);
        }
    }
    
    /**
     * Records a container access in an area.
     * @param areaId The area identifier
     * @param playerId The player identifier
     * @param containerType The type of container accessed
     */
    public void recordContainerAccess(String areaId, String playerId, String containerType) {
        try {
            // Update in-memory counter
            interactionCounters.computeIfAbsent("container_accesses", k -> new AtomicInteger())
                .incrementAndGet();
            
            // Record in database with container type
            try (PreparedStatement stmt = dbConnection.prepareStatement(
                "INSERT INTO interactions (area_id, player_id, action_type, details) VALUES (?, ?, ?, ?)")) {
                stmt.setString(1, areaId);
                stmt.setString(2, playerId);
                stmt.setString(3, "container_accesses");
                stmt.setString(4, containerType);
                stmt.executeUpdate();
            }
            
            // Update metrics
            Counter.builder("area.interactions")
                  .tag("area", areaId)
                  .tag("action", "container_accesses")
                  .tag("container", containerType)
                  .register(meterRegistry)
                  .increment();
        } catch (SQLException e) {
            plugin.getLogger().error("Failed to record container access", e);
        }
    }
    
    /**
     * Gets stats for a specific area formatted for display in the AreaCommand.
     * Returns a JSONObject with all the required stat fields.
     * 
     * @param areaId The area identifier
     * @return JSONObject containing stats for this area
     */
    public org.json.JSONObject getAreaCommandStats(String areaId) {
        org.json.JSONObject stats = new org.json.JSONObject();
        
        try {
            // Get all interaction stats from the database
            Map<String, Integer> interactions = getInteractionStats(areaId);
            
            // Set default values of 0 for all tracked stats
            stats.put("visits", interactions.getOrDefault("visits", 0));
            stats.put("blocks_broken", interactions.getOrDefault("blocks_broken", 0));
            stats.put("blocks_placed", interactions.getOrDefault("blocks_placed", 0));
            stats.put("pvp_fights", interactions.getOrDefault("pvp_fights", 0));
            stats.put("container_accesses", interactions.getOrDefault("container_accesses", 0));
            
            // Add any additional stats that might be tracked
            for (Map.Entry<String, Integer> entry : interactions.entrySet()) {
                String key = entry.getKey();
                if (!stats.has(key)) {
                    stats.put(key, entry.getValue());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().error("Failed to get area command stats", e);
        }
        
        return stats;
    }
}

