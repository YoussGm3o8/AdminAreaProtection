package adminarea.permissions;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.util.PerformanceMonitor;
import adminarea.util.ValidationUtils;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.Timer;

import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Manages permission overrides with advanced database operations and caching.
 */
public class OverrideManager implements AutoCloseable {
    private final AdminAreaProtectionPlugin plugin;
    private final HikariDataSource dataSource;
    private final ConcurrentMap<String, ConcurrentMap<String, PermissionOverride>> playerOverrides;
    private final ConcurrentMap<String, ConcurrentMap<String, PermissionOverride>> areaOverrides;
    private final ScheduledThreadPoolExecutor scheduler;
    private final Cache<String, Boolean> overrideCache;
    private final ConcurrentMap<String, Set<String>> inheritanceChains = new ConcurrentHashMap<>();
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);
    private final Object dbLock = new Object(); // Lock for database operations
    private static final int CACHE_SIZE = 1000;
    private static final long CACHE_DURATION = TimeUnit.MINUTES.toMillis(5);
    private final PermissionChecker permissionChecker;

    private record PermissionOverride(
        String target,
        String permission,
        boolean state,
        Instant expiry,
        int priority
    ) {}

    public OverrideManager(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        this.playerOverrides = new ConcurrentHashMap<>();
        this.areaOverrides = new ConcurrentHashMap<>();
        this.scheduler = (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(1);
        this.overrideCache = CacheBuilder.newBuilder()
            .maximumSize(CACHE_SIZE)
            .expireAfterWrite(CACHE_DURATION, TimeUnit.MILLISECONDS)
            .build();
        this.dataSource = initializeDataSource();
        this.permissionChecker = new PermissionChecker(plugin);
        
        initializeDatabase();
        setupScheduledTasks();
    }

    private HikariDataSource initializeDataSource() {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            HikariConfig config = new HikariConfig();
            Path dbPath = plugin.getDataFolder().toPath().resolve("overrides.db");
            config.setJdbcUrl("jdbc:sqlite:" + dbPath);
            config.setMaximumPoolSize(10);
            config.setMinimumIdle(5);
            config.setIdleTimeout(300000); // 5 minutes
            config.setMaxLifetime(600000); // 10 minutes
            config.setConnectionTimeout(30000);
            // Add connection test query
            config.setConnectionTestQuery("SELECT 1");
            // Enable JMX monitoring
            config.setRegisterMbeans(true);
            return new HikariDataSource(config);
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "override_ds_init");
        }
    }

    private void initializeDatabase() {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            
            conn.setAutoCommit(false);
            try {
                // Create tables with indexes
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS overrides (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        target_type TEXT NOT NULL,
                        target_id TEXT NOT NULL,
                        permission TEXT NOT NULL,
                        state BOOLEAN NOT NULL,
                        priority INTEGER NOT NULL DEFAULT 0,
                        expiry TIMESTAMP,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        UNIQUE(target_type, target_id, permission)
                    )
                """);

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS inheritance (
                        parent_id TEXT NOT NULL,
                        child_id TEXT NOT NULL,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (parent_id, child_id)
                    )
                """);

                // Create indexes
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_overrides_target ON overrides(target_type, target_id)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_overrides_expiry ON overrides(expiry)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_inheritance_parent ON inheritance(parent_id)");
                
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            plugin.getLogger().error("Failed to initialize database", e);
            throw new RuntimeException("Database initialization failed", e);
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "override_db_init");
        }
    }

    private void setupScheduledTasks() {
        // Use rejection handler for scheduled tasks
        RejectedExecutionHandler handler = (r, executor) -> 
            plugin.getLogger().warning("Scheduled task rejected: " + r.toString());

        scheduler.setRejectedExecutionHandler(handler);

        // Schedule cleanup with fixed delay to prevent overlap
        scheduler.scheduleWithFixedDelay(
            this::cleanupExpiredOverridesThreadSafe, 
            1, 1, TimeUnit.MINUTES
        );

        // Schedule backup with fixed delay
        scheduler.scheduleWithFixedDelay(
            this::backupDatabaseThreadSafe,
            6, 6, TimeUnit.HOURS
        );

        // Cache maintenance - use fixed delay
        scheduler.scheduleWithFixedDelay(
            overrideCache::cleanUp,
            1, 1, TimeUnit.MINUTES
        );
    }

    /**
     * Adds or updates an override for a player or area.
     */
    public void setOverride(String targetType, String targetId, String permission, 
                           boolean state, Duration duration, int priority) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            validateOverrideInput(targetType, targetId, permission, priority);
            
            Instant expiry = duration != null ? Instant.now().plus(duration) : null;
            
            synchronized (dbLock) {
                try (Connection conn = dataSource.getConnection();
                     PreparedStatement stmt = conn.prepareStatement("""
                         INSERT OR REPLACE INTO overrides 
                         (target_type, target_id, permission, state, priority, expiry)
                         VALUES (?, ?, ?, ?, ?, ?)
                     """)) {
                    
                    conn.setAutoCommit(false);
                    try {
                        stmt.setString(1, targetType);
                        stmt.setString(2, targetId);
                        stmt.setString(3, permission);
                        stmt.setBoolean(4, state);
                        stmt.setInt(5, priority);
                        stmt.setObject(6, expiry);
                        stmt.executeUpdate();
                        
                        // Update in-memory cache
                        Map<String, PermissionOverride> overrides = getOverridesMap(targetType)
                            .computeIfAbsent(targetId, k -> new ConcurrentHashMap<>());
                        overrides.put(permission, new PermissionOverride(targetId, permission, state, expiry, priority));
                        
                        // Invalidate affected cache entries
                        invalidateRelatedCaches(targetType, targetId, permission);
                        
                        conn.commit();
                    } catch (SQLException e) {
                        conn.rollback();
                        throw e;
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().error("Failed to set override", e);
            throw new RuntimeException("Failed to set override", e);
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "set_override");
        }
    }

    /**
     * Gets the effective override state for a target.
     */
    public boolean getEffectiveOverride(String targetType, String targetId, String permission) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            String cacheKey = targetType + ":" + targetId + ":" + permission;
            Boolean cached = overrideCache.getIfPresent(cacheKey);
            if (cached != null) {
                return cached;
            }

            // Check direct overrides
            Map<String, PermissionOverride> overrides = getOverridesMap(targetType).get(targetId);
            if (overrides != null) {
                PermissionOverride override = overrides.get(permission);
                if (override != null && (override.expiry == null || override.expiry.isAfter(Instant.now()))) {
                    overrideCache.put(cacheKey, override.state);
                    return override.state;
                }
            }

            // Check inheritance chain
            boolean result = resolveInheritedOverride(targetType, targetId, permission);
            overrideCache.put(cacheKey, result);
            return result;
            
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "get_override");
        }
    }

    private boolean resolveInheritedOverride(String targetType, String targetId, String permission) {
        Set<String> visited = new HashSet<>();
        PriorityQueue<PermissionOverride> candidates = new PriorityQueue<>(
            Comparator.comparingInt(PermissionOverride::priority).reversed());
        
        Queue<String> queue = new LinkedList<>();
        queue.offer(targetId);
        
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!visited.add(current)) continue;
            
            // Check overrides for current target
            Map<String, PermissionOverride> currentOverrides = getOverridesMap(targetType).get(current);
            if (currentOverrides != null) {
                PermissionOverride override = currentOverrides.get(permission);
                if (override != null && (override.expiry == null || override.expiry.isAfter(Instant.now()))) {
                    candidates.offer(override);
                }
            }
            
            // Add parents to queue
            Set<String> parents = inheritanceChains.getOrDefault(current, Collections.emptySet());
            queue.addAll(parents);
        }
        
        return candidates.isEmpty() ? false : candidates.peek().state;
    }

    private ConcurrentMap<String, ConcurrentMap<String, PermissionOverride>> getOverridesMap(String targetType) {
        return switch (targetType) {
            case "player" -> playerOverrides;
            case "area" -> areaOverrides;
            default -> throw new IllegalArgumentException("Invalid target type: " + targetType);
        };
    }

    private void validateOverrideInput(String targetType, String targetId, 
                                     String permission, int priority) {
        if (!Set.of("player", "area").contains(targetType)) {
            throw new IllegalArgumentException("Invalid target type: " + targetType);
        }
        
        ValidationUtils.validateString(targetId, 2, 64, 
            "^[a-zA-Z0-9_-]+$", "Target ID");
        ValidationUtils.validatePermission(permission);
        ValidationUtils.validateNumericRange(priority, -100, 100, "Priority");
    }

    private void invalidateRelatedCaches(String targetType, String targetId, String permission) {
        overrideCache.invalidate(targetType + ":" + targetId + ":" + permission);
        
        // Invalidate inherited caches
        Set<String> children = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        queue.offer(targetId);
        
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!children.add(current)) continue;
            
            inheritanceChains.forEach((child, parents) -> {
                if (parents.contains(current)) {
                    queue.offer(child);
                }
            });
        }
        
        children.forEach(child -> 
            overrideCache.invalidate(targetType + ":" + child + ":" + permission));
    }

    void cleanupExpiredOverridesThreadSafe() {
        if (isShuttingDown.get()) return;

        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            synchronized (dbLock) {
                try (Connection conn = dataSource.getConnection();
                     PreparedStatement stmt = conn.prepareStatement(
                         "DELETE FROM overrides WHERE expiry IS NOT NULL AND expiry < ?")) {
                    
                    stmt.setObject(1, Instant.now());
                    int removed = stmt.executeUpdate();
                    
                    if (removed > 0) {
                        plugin.getLogger().info("Cleaned up " + removed + " expired overrides");
                        
                        // Clean up in-memory state safely
                        playerOverrides.values().forEach(overrides -> 
                            overrides.entrySet().removeIf(e -> 
                                e.getValue().expiry != null && e.getValue().expiry.isBefore(Instant.now())));
                        
                        areaOverrides.values().forEach(overrides -> 
                            overrides.entrySet().removeIf(e -> 
                                e.getValue().expiry != null && e.getValue().expiry.isBefore(Instant.now())));
                        
                        overrideCache.invalidateAll();
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().error("Failed to cleanup expired overrides", e);
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "cleanup_overrides");
        }
    }

    private void backupDatabaseThreadSafe() {
        if (isShuttingDown.get()) return;

        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            synchronized (dbLock) {
                Path backupDir = plugin.getDataFolder().toPath().resolve("override_backups");
                java.nio.file.Files.createDirectories(backupDir);
                
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .replace(":", "-");
                Path backupFile = backupDir.resolve("overrides_" + timestamp + ".db");
                
                try (Connection conn = dataSource.getConnection();
                     PreparedStatement stmt = conn.prepareStatement("BACKUP TO ?")) {
                    stmt.setString(1, backupFile.toString());
                    stmt.executeUpdate();
                }
            }
        } catch (Exception e) {
            plugin.getLogger().error("Failed to backup override database", e);
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "backup_overrides");
        }
    }

    /**
     * Gets all permission overrides for a specific group.
     * 
     * @param groupName The name of the group
     * @return Map of permission nodes to their states
     */
    public Map<String, Boolean> getGroupPermissions(String groupName) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        Map<String, Boolean> permissions = new HashMap<>();
        
        try {
            synchronized (dbLock) {
                try (Connection conn = dataSource.getConnection();
                     PreparedStatement stmt = conn.prepareStatement("""
                         SELECT permission, state 
                         FROM overrides 
                         WHERE target_type = 'group' 
                         AND target_id = ? 
                         AND (expiry IS NULL OR expiry > CURRENT_TIMESTAMP)
                     """)) {
                    
                    stmt.setString(1, groupName);
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            permissions.put(rs.getString("permission"), rs.getBoolean("state"));
                        }
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().error("Failed to get group permissions", e);
            throw new RuntimeException("Failed to get group permissions", e);
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "get_group_permissions");
        }
        
        return permissions;
    }
    
    /**
     * Sets permission overrides for a specific group.
     * 
     * @param groupName The name of the group
     * @param permissions Map of permission nodes to their states
     */
    public void setGroupOverrides(String groupName, Map<String, Boolean> permissions) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            ValidationUtils.validateString(groupName, 2, 64, "^[a-zA-Z0-9_-]+$", "Group name");
            
            synchronized (dbLock) {
                Connection conn = null;
                try {
                    conn = dataSource.getConnection();
                    conn.setAutoCommit(false);
                    
                    // First delete existing overrides
                    try (PreparedStatement deleteStmt = conn.prepareStatement(
                        "DELETE FROM overrides WHERE target_type = 'group' AND target_id = ?")) {
                        deleteStmt.setString(1, groupName);
                        deleteStmt.executeUpdate();
                    }
                    
                    // Then insert new overrides
                    try (PreparedStatement insertStmt = conn.prepareStatement("""
                        INSERT INTO overrides (target_type, target_id, permission, state)
                        VALUES ('group', ?, ?, ?)
                    """)) {
                        for (Map.Entry<String, Boolean> entry : permissions.entrySet()) {
                            insertStmt.setString(1, groupName);
                            insertStmt.setString(2, entry.getKey());
                            insertStmt.setBoolean(3, entry.getValue());
                            insertStmt.addBatch();
                        }
                        insertStmt.executeBatch();
                    }
                    
                    conn.commit();
                } catch (SQLException e) {
                    if (conn != null) {
                        try {
                            conn.rollback();
                        } catch (SQLException rollbackEx) {
                            plugin.getLogger().error("Failed to rollback transaction", rollbackEx);
                        }
                    }
                    throw e;
                } finally {
                    if (conn != null) {
                        try {
                            conn.close();
                        } catch (SQLException closeEx) {
                            plugin.getLogger().error("Failed to close connection", closeEx);
                        }
                    }
                }
            }
            
            // Invalidate affected cache entries
            overrideCache.invalidateAll();
            
        } catch (SQLException e) {
            plugin.getLogger().error("Failed to set group overrides", e);
            throw new RuntimeException("Failed to set group overrides", e);
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "set_group_overrides");
        }
    }

    /**
     * Gets the permission checker instance.
     * @return The PermissionChecker instance
     */
    public PermissionChecker getPermissionChecker() {
        return permissionChecker;
    }

    @Override
    public void close() {
        if (!isShuttingDown.compareAndSet(false, true)) {
            return; // Already shutting down
        }

        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            // Shutdown scheduler first
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }

            synchronized (dbLock) {
                // Perform final backup
                backupDatabaseThreadSafe();
                
                // Close data source
                if (dataSource != null && !dataSource.isClosed()) {
                    dataSource.close();
                }
            }

            // Clear caches and maps
            overrideCache.invalidateAll();
            playerOverrides.clear();
            areaOverrides.clear();
            inheritanceChains.clear();

        } catch (Exception e) {
            plugin.getLogger().error("Error during OverrideManager shutdown", e);
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "override_manager_close");
        }
    }
}
