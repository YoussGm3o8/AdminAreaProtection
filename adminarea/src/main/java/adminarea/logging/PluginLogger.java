package adminarea.logging;

import adminarea.AdminAreaProtectionPlugin;
import cn.nukkit.utils.LogLevel;
import cn.nukkit.utils.Logger;
import cn.nukkit.utils.TextFormat;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class PluginLogger implements Logger {
    private final AdminAreaProtectionPlugin plugin;
    private final File logFile;
    private final SimpleDateFormat dateFormat;
    private final ConcurrentLinkedQueue<LogEntry> logQueue;
    private final Map<String, AtomicInteger> errorCounts;
    private static final int MAX_QUEUE_SIZE = 1000;
    private static final int FLUSH_THRESHOLD = 100;

    public PluginLogger(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        this.logFile = new File(plugin.getDataFolder(), "plugin.log");
        this.dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        this.logQueue = new ConcurrentLinkedQueue<>();
        this.errorCounts = new HashMap<>();

        // Create log directory if it doesn't exist
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }

        // Start log processing thread
        startLogProcessor();
    }

    private void startLogProcessor() {
        plugin.getServer().getScheduler().scheduleRepeatingTask(plugin, () -> {
            if (logQueue.size() >= FLUSH_THRESHOLD) {
                flushLogs();
            }
        }, 1200); // Check every minute
    }

    public void setDebugMode(boolean enabled) {
        plugin.setDebugMode(enabled);
        info("Debug mode " + (enabled ? "enabled" : "disabled"));
    }

    public boolean isDebugMode() {
        return plugin.isDebugMode();
    }

    @Override
    public void debug(String message) {
        // Forward debug messages to info level for visibility
        if (plugin.isDebugMode()) {
            info("[Debug] " + message);
        }
        // Also log to file with DEBUG level for reference
        log("DEBUG", message);
    }

    @Override
    public void debug(String message, Throwable t) {
        // Forward debug messages to info level for visibility
        if (plugin.isDebugMode()) {
            info("[Debug] " + message + (t != null ? ": " + t.getMessage() : ""));
        }
        // Also log to file with DEBUG level for reference
        log("DEBUG", message + (t != null ? ": " + t.getMessage() : ""));
    }

    @Override
    public void info(String message) {
        log("INFO", message);
    }

    @Override
    public void info(String message, Throwable t) {
        log("INFO", message + (t != null ? ": " + t.getMessage() : ""));
    }

    @Override
    public void warning(String message) {
        log("WARNING", message);
    }

    @Override
    public void warning(String message, Throwable t) {
        log("WARNING", message + (t != null ? ": " + t.getMessage() : ""));
    }

    @Override
    public void error(String message) {
        error(message, null);
    }

    @Override
    public void critical(String message) {
        log("CRITICAL", message);
    }

    @Override
    public void critical(String message, Throwable t) {
        log("CRITICAL", message + (t != null ? ": " + t.getMessage() : ""));
    }

    @Override
    public void notice(String message) {
        log("NOTICE", message);
    }

    @Override
    public void notice(String message, Throwable t) {
        log("NOTICE", message + (t != null ? ": " + t.getMessage() : ""));
    }

    @Override
    public void alert(String message) {
        log("ALERT", message);
    }

    @Override
    public void alert(String message, Throwable t) {
        log("ALERT", message + (t != null ? ": " + t.getMessage() : ""));
    }

    @Override
    public void emergency(String message) {
        log("EMERGENCY", message);
    }

    @Override
    public void emergency(String message, Throwable t) {
        log("EMERGENCY", message + (t != null ? ": " + t.getMessage() : ""));
    }

    @Override
    public void log(LogLevel level, String message) {
        log(level.name(), message);
    }

    @Override
    public void log(LogLevel level, String message, Throwable t) {
        log(level.name(), message + (t != null ? ": " + t.getMessage() : ""));
    }

    public void error(String message, Throwable error) {
        // Track error frequency
        errorCounts.computeIfAbsent(message, k -> new AtomicInteger(0)).incrementAndGet();

        // Log the error
        String errorMsg = error != null ? message + ": " + error.getMessage() : message;
        log("ERROR", errorMsg);

        // Log stack trace if available
        if (error != null && plugin.isDebugMode()) {
            debug("Stack trace for error: " + message);
            for (StackTraceElement element : error.getStackTrace()) {
                debug("    " + element.toString());
            }
        }
    }

    private void log(String level, String message) {
        LogEntry entry = new LogEntry(level, message);
        logQueue.offer(entry);

        // Log to console with appropriate color
        String coloredMessage = colorize(level, String.format("[%s] %s", plugin.getName(), message));
        System.out.println(coloredMessage);

        // Force flush debug messages immediately for real-time debugging
        if (level.equals("DEBUG")) {
            flushLogs();
        }
        // Auto-flush if queue gets too large
        else if (logQueue.size() >= MAX_QUEUE_SIZE) {
            flushLogs();
        }
    }

    private String colorize(String level, String message) {
        switch (level) {
            case "DEBUG":
                return TextFormat.GRAY + message + TextFormat.RESET;
            case "WARNING":
                return TextFormat.YELLOW + message + TextFormat.RESET;
            case "ERROR":
                return TextFormat.RED + message + TextFormat.RESET;
            default:
                return message;
        }
    }

    private LogLevel getLogLevel(String level) {
        switch (level) {
            case "DEBUG":
                return LogLevel.DEBUG;
            case "WARNING":
                return LogLevel.WARNING;
            case "ERROR":
                return LogLevel.ERROR;
            default:
                return LogLevel.INFO;
        }
    }

    public synchronized void flushLogs() {
        if (logQueue.isEmpty()) {
            return;
        }

        try (PrintWriter writer = new PrintWriter(new FileWriter(logFile, true))) {
            LogEntry entry;
            while ((entry = logQueue.poll()) != null) {
                writer.println(String.format("[%s] [%s] %s",
                    dateFormat.format(entry.timestamp),
                    entry.level,
                    entry.message));
            }
        } catch (IOException e) {
            System.err.println("Failed to write to log file: " + e.getMessage());
        }
    }

    public Map<String, Integer> getErrorSummary() {
        Map<String, Integer> summary = new HashMap<>();
        errorCounts.forEach((message, count) -> summary.put(message, count.get()));
        return summary;
    }

    public void cleanup() {
        flushLogs();
        // Rotate logs if file gets too large (>10MB)
        if (logFile.length() > 10 * 1024 * 1024) {
            rotateLogFile();
        }
    }

    private void rotateLogFile() {
        File backupFile = new File(plugin.getDataFolder(), "plugin.log.1");
        if (backupFile.exists()) {
            backupFile.delete();
        }
        logFile.renameTo(backupFile);
    }

    private static class LogEntry {
        final Date timestamp;
        final String level;
        final String message;

        LogEntry(String level, String message) {
            this.timestamp = new Date();
            this.level = level;
            this.message = message;
        }
    }
}
