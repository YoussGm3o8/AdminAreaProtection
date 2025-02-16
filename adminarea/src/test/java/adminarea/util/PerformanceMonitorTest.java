package adminarea.util;

import adminarea.AdminAreaProtectionPlugin;
import cn.nukkit.plugin.PluginLogger;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;

class PerformanceMonitorTest {

    private AdminAreaProtectionPlugin mockPlugin;
    private PerformanceMonitor monitor;
    private PluginLogger mockLogger;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        mockPlugin = mock(AdminAreaProtectionPlugin.class);
        mockLogger = mock(PluginLogger.class);
        when(mockPlugin.getLogger()).thenReturn(mockLogger);
        when(mockPlugin.getDataFolder()).thenReturn(tempDir.toFile());
        
        monitor = new PerformanceMonitor(mockPlugin);
    }

    @Test
    void startAndStopTimer_ValidOperation_RecordsMetrics() {
        Timer.Sample sample = monitor.startTimer();
        assertNotNull(sample);
        
        // Simulate some work
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        monitor.stopTimer(sample, "test_operation");
        assertTrue(monitor.getAverageTime("test_operation") > 0);
    }

    @Test
    void stopTimer_ExceedsThreshold_TriggersAlert() {
        Timer.Sample sample = monitor.startTimer();
        
        // Set up alert
        boolean[] alertTriggered = {false};
        monitor.setThresholdAlert("slow_operation", 
            duration -> alertTriggered[0] = true);
        
        // Simulate slow operation
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        monitor.stopTimer(sample, "slow_operation", 100);
        assertTrue(alertTriggered[0]);
    }

    @Test
    void reset_ClearsAllMetrics() {
        // Record some metrics
        Timer.Sample sample = monitor.startTimer();
        monitor.stopTimer(sample, "test_operation");
        
        // Reset
        monitor.reset();
        
        // Verify metrics are cleared
        assertEquals(0.0, monitor.getAverageTime("test_operation"));
    }

    @Test
    void close_GracefulShutdown() {
        assertDoesNotThrow(() -> {
            monitor.close();
        });
        
        // Verify cleanup
        verify(mockLogger, never()).log(any(), anyString(), any(Throwable.class));
    }

    @Test
    void constructor_InitializesCorrectly() {
        assertNotNull(monitor);
        verify(mockLogger, never()).log(any(), anyString(), any(Throwable.class));
    }

    @Test
    void multipleOperations_TracksIndependently() {
        // Operation 1
        Timer.Sample sample1 = monitor.startTimer();
        monitor.stopTimer(sample1, "operation1");
        
        // Operation 2
        Timer.Sample sample2 = monitor.startTimer();
        monitor.stopTimer(sample2, "operation2");
        
        assertTrue(monitor.getAverageTime("operation1") >= 0);
        assertTrue(monitor.getAverageTime("operation2") >= 0);
    }
}
