package adminarea.util;

import adminarea.AdminAreaProtectionPlugin;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.management.*;
import java.util.concurrent.*;
import java.util.Map;
import java.util.function.Consumer;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Comprehensive performance monitoring system for the AdminAreaProtection plugin.
 * Provides high-precision timing, memory monitoring, and performance metrics tracking.
 */
public class PerformanceMonitor implements AutoCloseable {
    private final AdminAreaProtectionPlugin plugin;
    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Timer> timers;
    private final ConcurrentHashMap<String, Consumer<Long>> thresholdAlerts;
    private final ScheduledExecutorService scheduler;
    private final Path metricsPath;
    private final Map<String, Gauge> memoryGauges;
    private final ThreadMXBean threadBean;
    private final MemoryMXBean memoryBean;
    private volatile boolean isRunning;

    private static final long DEFAULT_THRESHOLD_MS = 100;
    private static final int METRICS_RETENTION_DAYS = 7;

    public PerformanceMonitor(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        this.registry = new SimpleMeterRegistry();
        this.timers = new ConcurrentHashMap<>();
        this.thresholdAlerts = new ConcurrentHashMap<>();
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.memoryGauges = new ConcurrentHashMap<>();
        this.threadBean = ManagementFactory.getThreadMXBean();
        this.memoryBean = ManagementFactory.getMemoryMXBean();
        this.metricsPath = plugin.getDataFolder().toPath().resolve("metrics");
        this.isRunning = true;

        initializeMonitoring();
    }

    private void initializeMonitoring() {
        try {
            Files.createDirectories(metricsPath);
            setupMemoryMonitoring();
            setupGCMonitoring();
            setupPeriodicMetricsExport();
            setupThresholdMonitoring();
        } catch (Exception e) {
            plugin.getLogger().error("Failed to initialize performance monitoring", e);
        }
    }

    private void setupMemoryMonitoring() {
        Gauge.builder("heap.used", memoryBean,
                     mem -> mem.getHeapMemoryUsage().getUsed())
             .tags("type", "heap")
             .register(registry);

        Gauge.builder("heap.committed", memoryBean,
                     mem -> mem.getHeapMemoryUsage().getCommitted())
             .tags("type", "heap")
             .register(registry);

        // Monitor active threads
        Gauge.builder("threads.active", threadBean,
                     ThreadMXBean::getThreadCount)
             .register(registry);
    }

    private void setupGCMonitoring() {
        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            Gauge.builder("gc.count", gcBean, GarbageCollectorMXBean::getCollectionCount)
                 .tags("gc", gcBean.getName())
                 .register(registry);
            
            Gauge.builder("gc.time", gcBean, GarbageCollectorMXBean::getCollectionTime)
                 .tags("gc", gcBean.getName())
                 .register(registry);
        }
    }

    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    public void stopTimer(Timer.Sample sample, String operation) {
        stopTimer(sample, operation, DEFAULT_THRESHOLD_MS);
    }

    public void stopTimer(Timer.Sample sample, String operation, long thresholdMs) {
        try {
            Timer timer = timers.computeIfAbsent(operation,
                k -> Timer.builder("area_protection_" + k)
                         .description("Time taken for " + k)
                         .tag("operation", k)
                         .register(registry));

            long duration = sample.stop(timer);
            long durationMs = TimeUnit.NANOSECONDS.toMillis(duration);

            // Check threshold and alert if necessary
            if (durationMs > thresholdMs) {
                Consumer<Long> alert = thresholdAlerts.get(operation);
                if (alert != null) {
                    alert.accept(durationMs);
                }
                logSlowOperation(operation, durationMs);
            }

        } catch (Exception e) {
            plugin.getLogger().error("Error stopping timer for operation: " + operation, e);
        }
    }

    private void logSlowOperation(String operation, long durationMs) {
        String message = String.format("Slow operation detected - %s took %dms", 
            operation, durationMs);
        plugin.getLogger().warning(message);
        
        try {
            Path logFile = metricsPath.resolve("slow_operations.log");
            String logEntry = String.format("[%s] %s%n", 
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                message);
            Files.write(logFile, logEntry.getBytes(), 
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            plugin.getLogger().error("Failed to log slow operation", e);
        }
    }

    public void setThresholdAlert(String operation, Consumer<Long> alert) {
        thresholdAlerts.put(operation, alert);
    }

    public double getAverageTime(String operation) {
        Timer timer = timers.get(operation);
        return timer != null ? timer.mean(TimeUnit.SECONDS) : 0.0;
    }

    public void reset() {
        timers.clear();
        registry.clear();
        setupMemoryMonitoring();
        setupGCMonitoring();
    }

    private void setupPeriodicMetricsExport() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                exportMetrics();
                cleanupOldMetrics();
            } catch (Exception e) {
                plugin.getLogger().error("Error in periodic metrics export", e);
            }
        }, 1, 1, TimeUnit.HOURS);
    }

    private void setupThresholdMonitoring() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                checkMemoryThresholds();
                checkThreadThresholds();
            } catch (Exception e) {
                plugin.getLogger().error("Error in threshold monitoring", e);
            }
        }, 1, 1, TimeUnit.MINUTES);
    }

    private void checkMemoryThresholds() {
        long usedMemory = memoryBean.getHeapMemoryUsage().getUsed();
        long maxMemory = memoryBean.getHeapMemoryUsage().getMax();
        double memoryUsagePercent = (double) usedMemory / maxMemory * 100;

        if (memoryUsagePercent > 85) {
            plugin.getLogger().warning(String.format(
                "High memory usage detected: %.2f%%", memoryUsagePercent));
        }
    }

    private void checkThreadThresholds() {
        int threadCount = threadBean.getThreadCount();
        if (threadCount > 100) {
            plugin.getLogger().warning(String.format(
                "High thread count detected: %d threads", threadCount));
        }
    }

    private void exportMetrics() {
        try {
            Path metricsFile = metricsPath.resolve(String.format("metrics_%s.csv",
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE)));

            StringBuilder metrics = new StringBuilder("Timestamp,Metric,Value\n");
            
            try {
                registry.getMeters().forEach(meter -> {
                    try {
                        for (Measurement measurement : meter.measure()) {
                            metrics.append(String.format("%s,%s,%.2f%n",
                                LocalDateTime.now(),
                                meter.getId().getName(),
                                measurement.getValue()));
                        }
                    } catch (Exception e) {
                        // Skip individual metric on error
                        plugin.getLogger().error("Failed to process meter: " + meter.getId().getName(), e);
                    }
                });
                
                Files.write(metricsFile, metrics.toString().getBytes(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception e) {
                plugin.getLogger().error("Failed to process metrics collection", e);
            }

        } catch (Exception e) {
            plugin.getLogger().error("Failed to export metrics", e);
        }
    }

    private void cleanupOldMetrics() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(METRICS_RETENTION_DAYS);
            Files.list(metricsPath)
                .filter(path -> path.toString().endsWith(".csv"))
                .filter(path -> {
                    try {
                        String fileName = path.getFileName().toString();
                        LocalDateTime fileDate = LocalDateTime.parse(
                            fileName.substring(8, 18),
                            DateTimeFormatter.ISO_LOCAL_DATE);
                        return fileDate.isBefore(cutoff);
                    } catch (Exception e) {
                        return false;
                    }
                })
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (Exception e) {
                        plugin.getLogger().error("Failed to delete old metrics file: " + path, e);
                    }
                });
        } catch (Exception e) {
            plugin.getLogger().error("Failed to cleanup old metrics", e);
        }
    }

    @Override
    public void close() {
        isRunning = false;
        try {
            // Safely shutdown the scheduler first
            try {
                if (scheduler != null && !scheduler.isShutdown()) {
                    scheduler.shutdown();
                    if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                        scheduler.shutdownNow();
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().error("Error shutting down scheduler", e);
            }
            
            // Try to export metrics one last time, with extra error protection
            try {
                if (plugin.isDebugMode()) {
                    plugin.debug("Attempting final metrics export during shutdown");
                }
                exportMetrics();
            } catch (Throwable t) {
                // Catch all throwables to ensure plugin shutdown completes
                plugin.getLogger().error("Error during final metrics export", t);
            }
            
            // Close the registry
            try {
                if (registry != null) {
                    registry.close();
                }
            } catch (Exception e) {
                plugin.getLogger().error("Error closing metrics registry", e);
            }
        } catch (Throwable t) {
            // Catch absolutely everything to ensure plugin shutdown
            plugin.getLogger().error("Unexpected error during performance monitor shutdown", t);
        }
    }
    
    public MeterRegistry getRegistry() {
        return registry;
    }
}
