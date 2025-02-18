package adminarea.permissions;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.util.PerformanceMonitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PermissionToggleTest {
    
    @Mock private AdminAreaProtectionPlugin plugin;
    @Mock private PerformanceMonitor performanceMonitor;
    private PermissionToggle permissionToggle;
    
    @TempDir
    Path tempDir;
    
    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getPerformanceMonitor()).thenReturn(performanceMonitor);
        
        permissionToggle = new PermissionToggle(plugin);
    }
    
    @Test
    void setPlayerToggle_ValidInput_Persists() {
        String playerId = "testPlayer";
        String permission = "test.permission";
        
        assertDoesNotThrow(() -> 
            permissionToggle.setPlayerToggle(playerId, permission, true));
        
        assertTrue(permissionToggle.getEffectiveToggle(playerId, permission));
    }
    
    @Test
    void setGroupToggle_ValidInput_AffectsMembers() {
        String groupId = "testGroup";
        String playerId = "testPlayer";
        String permission = "test.permission";
        
        // Add player to group
        permissionToggle.addToGroup(playerId, groupId);
        
        // Set group toggle
        permissionToggle.setGroupToggle(groupId, permission, true);
        
        // Verify player inherits group toggle
        assertTrue(permissionToggle.getEffectiveToggle(playerId, permission));
    }
    
    @Test
    void getEffectiveToggle_Inheritance_CorrectPriority() {
        String playerId = "testPlayer";
        String groupId = "testGroup";
        String permission = "test.permission";
        
        // Set up hierarchy
        permissionToggle.addToGroup(playerId, groupId);
        permissionToggle.setGroupToggle(groupId, permission, true);
        permissionToggle.setPlayerToggle(playerId, permission, false);
        
        // Player toggle should override group toggle
        assertFalse(permissionToggle.getEffectiveToggle(playerId, permission));
    }
    
    @Test
    void cache_ExpiresCorrectly() throws Exception {
        String playerId = "testPlayer";
        String permission = "test.permission";
        
        permissionToggle.setPlayerToggle(playerId, permission, true);
        assertTrue(permissionToggle.getEffectiveToggle(playerId, permission));
        
        // Simulate cache expiration
        TimeUnit.MILLISECONDS.sleep(100);
        
        // Should still return same value
        assertTrue(permissionToggle.getEffectiveToggle(playerId, permission));
    }
    
    @Test
    void backup_CreatesValidBackup() throws Exception {
        // Set some data
        permissionToggle.setPlayerToggle("player1", "test.permission", true);
        permissionToggle.setGroupToggle("group1", "test.permission", false);
        
        // Trigger backup
        permissionToggle.backup();
        
        // Verify backup files exist
        assertTrue(tempDir.resolve("toggle_backups").toFile().exists());
    }
    
    @Test
    void cleanup_RemovesOldData() throws Exception {
        // Add old test data
        try (Connection conn = permissionToggle.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO player_toggles (player_id, permission, state, last_modified) " +
                        "VALUES ('oldPlayer', 'test.perm', 1, datetime('now', '-8 days'))");
        }
        
        permissionToggle.cleanup();
        
        // Verify old data is removed
        try (Connection conn = permissionToggle.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM player_toggles")) {
            assertEquals(0, rs.getInt(1));
        }
    }
    
    @Test
    void invalidInput_ThrowsValidationException() {
        assertThrows(IllegalArgumentException.class, () ->
            permissionToggle.setPlayerToggle("", "test.permission", true));
        
        assertThrows(IllegalArgumentException.class, () ->
            permissionToggle.setPlayerToggle("player1", "", true));
    }
    
    @Test
    void close_PerformsCleanShutdown() {
        assertDoesNotThrow(() -> permissionToggle.close());
        verify(plugin, never()).getLogger();
    }

    @Test
    void performanceMonitoring_RecordsMetrics() {
        String playerId = "testPlayer";
        String permission = "test.permission";
        
        // Set toggle and verify metrics are recorded
        permissionToggle.setPlayerToggle(playerId, permission, true);
        verify(performanceMonitor, times(1)).stopTimer(any(), eq("set_player_toggle"));
        
        // Get toggle and verify metrics
        permissionToggle.getEffectiveToggle(playerId, permission);
        verify(performanceMonitor, times(1)).stopTimer(any(), eq("get_effective_toggle"));
    }

    @Test
    void toggleCache_ImprovesCacheHitRatio() throws Exception {
        String playerId = "testPlayer";
        String permission = "test.permission";
        
        // First access - should miss cache
        permissionToggle.getEffectiveToggle(playerId, permission);
        
        // Second access - should hit cache
        permissionToggle.getEffectiveToggle(playerId, permission);
        
        // Third access - should hit cache
        permissionToggle.getEffectiveToggle(playerId, permission);
        
        // Verify cache metrics
        verify(performanceMonitor, times(1)).stopTimer(any(), eq("toggle_cache_miss"));
        verify(performanceMonitor, times(2)).stopTimer(any(), eq("toggle_cache_hit"));
    }
}
