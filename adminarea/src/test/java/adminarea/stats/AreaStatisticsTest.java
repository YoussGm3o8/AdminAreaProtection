package adminarea.stats;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.util.PerformanceMonitor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AreaStatisticsTest {
    
    @Mock private AdminAreaProtectionPlugin plugin;
    @Mock private PerformanceMonitor performanceMonitor;
    private AreaStatistics statistics;
    private MeterRegistry registry;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        
        // Configure mocks
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        registry = new SimpleMeterRegistry();
        when(performanceMonitor.getRegistry()).thenReturn(registry);
        when(plugin.getPerformanceMonitor()).thenReturn(performanceMonitor);
        
        statistics = new AreaStatistics(plugin);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (statistics != null) {
            statistics.close();
        }
    }

    @Test
    void recordInteraction_ValidInput_StoresCorrectly() {
        String areaId = "test-area";
        String playerId = "player1";
        String actionType = "ENTER";
        
        statistics.recordInteraction(areaId, playerId, actionType);
        
        // Verify database entry
        try (Connection conn = statistics.getConnection()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT * FROM interactions")) {
                
                assertTrue(rs.next());
                assertEquals(areaId, rs.getString("area_id"));
                assertEquals(playerId, rs.getString("player_id"));
                assertEquals(actionType, rs.getString("action_type"));
            }
            conn.commit();
        }
        
        // Verify metrics
        assertEquals(1, registry.get("area.interactions")
            .tag("area", areaId)
            .tag("action", actionType)
            .counter().count());
    }

    @Test
    void recordViolation_ValidInput_StoresCorrectly() {
        String areaId = "test-area";
        String playerId = "player1";
        String violationType = "BREAK";
        
        statistics.recordViolation(areaId, playerId, violationType);
        
        Map<String, Integer> stats = statistics.getInteractionStats(areaId);
        assertEquals(1, stats.getOrDefault("VIOLATION", 0));
    }

    @Test
    void getRecentModifications_ReturnsCorrectOrder() {
        String areaId = "test-area";
        
        statistics.recordModification(areaId, "player1", "CREATE", "details1");
        statistics.recordModification(areaId, "player2", "MODIFY", "details2");
        
        List<AreaModification> modifications = statistics.getRecentModifications(areaId, 2);
        assertEquals(2, modifications.size());
        assertTrue(modifications.get(0).timestamp().isAfter(modifications.get(1).timestamp()));
    }

    @Test
    void cleanup_RemovesOldData() throws Exception {
        // Add old data
        try (Connection conn = statistics.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO interactions (area_id, player_id, action_type, timestamp) " +
                        "VALUES ('old-area', 'player1', 'TEST', datetime('now', '-31 days'))");
        }
        
        statistics.cleanup();
        
        // Verify old data is removed
        try (Connection conn = statistics.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM interactions")) {
            assertEquals(0, rs.getInt(1));
        }
    }

    @Test
    void close_PerformsBackupAndCleanup() throws Exception {
        statistics.recordInteraction("test-area", "player1", "TEST");
        statistics.close();
        
        // Verify backup files exist
        assertTrue(tempDir.resolve("statistics_backup").toFile().exists());
    }
}
