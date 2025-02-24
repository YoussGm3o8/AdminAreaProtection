package adminarea.util;

import adminarea.AdminAreaProtectionPlugin;

public class Logger {
    private final AdminAreaProtectionPlugin plugin;
    private final String prefix;

    public Logger(AdminAreaProtectionPlugin plugin, String className) {
        this.plugin = plugin;
        this.prefix = "[" + className + "] ";
    }

    public void debug(String message, Object... args) {
        if (plugin.isDebugMode()) {
            plugin.getLogger().debug(prefix + String.format(message, args));
        }
    }

    public void info(String message, Object... args) {
        plugin.getLogger().info(prefix + String.format(message, args));
    }

    public void warn(String message, Object... args) {
        plugin.getLogger().warning(prefix + String.format(message, args));
    }

    public void error(String message, Object... args) {
        plugin.getLogger().error(prefix + String.format(message, args));
    }

    public void error(String message, Throwable error) {
        plugin.getLogger().error(prefix + message, error);
    }
}
