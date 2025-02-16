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
import java.util.logging.Level;

/**
 * Manages permission overrides with advanced database operations and caching.
 */
public class OverrideManager implements AutoCloseable {
    private final AdminAreaProtectionPlugin plugin;
    private final HikariDataSource dataSource;
    private final Map<String, Map<String, PermissionOverride>> playerOverrides;
    private final Map<String, Map<String, PermissionOverride>> areaOverrides;
    private final Map<String, Set<String>> inheritanceChains;
    private final ScheduledExecutorService scheduler;
    private final Cache<String, Boolean> overrideCache;
    private static final int CACHE_SIZE = 1000;
    private static final long CACHE_DURATION = TimeUnit.MINUTES.toMillis(5);

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
        this.inheritanceChains = new ConcurrentHashMap<>();
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.overrideCache = CacheBuilder.newBuilder()
            .maximumSize(CACHE_SIZE)
            .expireAfterWrite(CACHE_DURATION, TimeUnit.MILLISECONDS)
            .build();
        this.dataSource = initializeDataSource();
        
        initializeDatabase();
        setupScheduledTasks();
    }

    private HikariDataSource initializeDataSource() {
        HikariConfig config = new HikariConfig();
        Path dbPath = plugin.getDataFolder().toPath().resolve("overrides.db");
        config.setJdbcUrl("jdbc:sqlite:" + dbPath);
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(5);
        config.setIdleTimeout(300000); // 5 minutes
        config.setMaxLifetime(600000); // 10 minutes
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        return new HikariDataSource(config);
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
        // Cleanup expired overrides
        scheduler.scheduleAtFixedRate(this::cleanupExpiredOverrides, 1, 1, TimeUnit.MINUTES);
        
        // Backup database
        scheduler.scheduleAtFixedRate(this::backupDatabase, 6, 6, TimeUnit.HOURS);
        
        // Cache maintenance
        scheduler.scheduleAtFixedRate(overrideCache::cleanUp, 1L, 1L, TimeUnit.MINUTES);
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

    private Map<String, Map<String, PermissionOverride>> getOverridesMap(String targetType) {
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

    private void cleanupExpiredOverrides() {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                 "DELETE FROM overrides WHERE expiry IS NOT NULL AND expiry < ?")) {
            
            stmt.setObject(1, Instant.now());
            int removed = stmt.executeUpdate();
            
            if (removed > 0) {
                plugin.getLogger().info("Cleaned up " + removed + " expired overrides");
                
                // Clean up in-memory state
                playerOverrides.values().forEach(overrides -> 
                    overrides.entrySet().removeIf(e -> 
                        e.getValue().expiry != null && e.getValue().expiry.isBefore(Instant.now())));
                
                areaOverrides.values().forEach(overrides -> 
                    overrides.entrySet().removeIf(e -> 
                        e.getValue().expiry != null && e.getValue().expiry.isBefore(Instant.now())));
                
                overrideCache.invalidateAll();
            }
        } catch (SQLException e) {
            plugin.getLogger().error("Failed to cleanup expired overrides", e);
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "cleanup_overrides");
        }
    }

    private void backupDatabase() {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Path backupDir = plugin.getDataFolder().toPath().resolve("override_backups");
            java.nio.file.Files.createDirectories(backupDir);
            
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            Path backupFile = backupDir.resolve("overrides_" + timestamp + ".db");
            
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement("BACKUP TO ?")) {
                stmt.setString(1, backupFile.toString());
                stmt.executeUpdate();
            }
        } catch (Exception e) {
            plugin.getLogger().error("Failed to backup override database", e);
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "backup_overrides");
        }
    }

    @Override
    public void close() {
        try {
            scheduler.shutdown();
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
            backupDatabase();
            dataSource.close();
        } catch (Exception e) {
            plugin.getLogger().error("Error closing OverrideManager", e);
        }
    }
}
