package adminarea.permissions;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.util.PerformanceMonitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.nio.file.Path;
import java.time.Duration;
import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OverrideManagerTest {

    @Mock private AdminAreaProtectionPlugin plugin;
    @Mock private PerformanceMonitor performanceMonitor;
    private OverrideManager manager;
    
    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(plugin.getDataFolder()).thenReturn(tempDir.toFile());
        when(plugin.getPerformanceMonitor()).thenReturn(performanceMonitor);
        
        manager = new OverrideManager(plugin);
    }

    @Test
    void setOverride_ValidInput_Persists() {
        assertDoesNotThrow(() -> 
            manager.setOverride("player", "testPlayer", "test.permission", true, null, 0));
        
        assertTrue(manager.getEffectiveOverride("player", "testPlayer", "test.permission"));
    }

    @Test
    void setOverride_WithExpiry_ExpiresCorrectly() throws Exception {
        manager.setOverride("player", "testPlayer", "test.permission", true, 
            Duration.ofMillis(100), 0);
        
        assertTrue(manager.getEffectiveOverride("player", "testPlayer", "test.permission"));
        
        // Wait for expiry
        Thread.sleep(150);
        
        assertFalse(manager.getEffectiveOverride("player", "testPlayer", "test.permission"));
    }

    @Test
    void getEffectiveOverride_InheritancePriority_ReturnsHighestPriority() {
        // Set up inheritance chain
        manager.setOverride("area", "parent", "test.permission", true, null, 1);
        manager.setOverride("area", "child", "test.permission", false, null, 2);
        
        // Child override should take precedence due to higher priority
        assertFalse(manager.getEffectiveOverride("area", "child", "test.permission"));
    }

    @Test
    void setOverride_InvalidInput_ThrowsException() {
        assertThrows(IllegalArgumentException.class, () ->
            manager.setOverride("invalid", "testPlayer", "test.permission", true, null, 0));
        
        assertThrows(IllegalArgumentException.class, () ->
            manager.setOverride("player", "", "test.permission", true, null, 0));
        
        assertThrows(IllegalArgumentException.class, () ->
            manager.setOverride("player", "testPlayer", "", true, null, 0));
    }

    @Test
    void cleanup_RemovesExpiredOverrides() throws Exception {
        // Add test data with expiry
        manager.setOverride("player", "testPlayer", "test.permission", true,
            Duration.ofMillis(50), 0);
        
        // Wait for expiry
        Thread.sleep(100);
        
        // Trigger cleanup
        manager.cleanupExpiredOverrides();
        
        assertFalse(manager.getEffectiveOverride("player", "testPlayer", "test.permission"));
    }

    @Test
    void close_PerformsCleanShutdown() {
        assertDoesNotThrow(() -> manager.close());
        verify(plugin, never()).getLogger();
    }

    @Test
    void cache_PreventsDatabaseQueries() {
        // First call should hit database
        manager.setOverride("player", "testPlayer", "test.permission", true, null, 0);
        manager.getEffectiveOverride("player", "testPlayer", "test.permission");
        
        // Second call should use cache
        long start = System.nanoTime();
        manager.getEffectiveOverride("player", "testPlayer", "test.permission");
        long duration = System.nanoTime() - start;
        
        // Cache lookup should be very fast
        assertTrue(duration < 1_000_000); // Less than 1ms
    }
}
