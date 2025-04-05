package adminarea.managers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaDTO;
import adminarea.constants.FormIds;
import adminarea.data.FormTrackingData;
import adminarea.form.validation.FormValidator;
import adminarea.form.validation.ValidationResult;
import adminarea.util.FormLogger;
import cn.nukkit.Player;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.form.window.FormWindowSimple;
import cn.nukkit.level.particle.DustParticle;
import cn.nukkit.math.Vector3;
import cn.nukkit.scheduler.TaskHandler;
import io.micrometer.core.instrument.Timer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class GuiManager {
    private final AdminAreaProtectionPlugin plugin;
    private final AtomicBoolean cleanupRunning = new AtomicBoolean(false);
    private final Map<String, Deque<String>> navigationHistory = new ConcurrentHashMap<>();
    private final long FORM_EXPIRY_TIME = 900000; // 15 minutes
    private final FormLogger formLogger;
    private final Map<String, TaskHandler> visualizationTasks = new ConcurrentHashMap<>();

    public GuiManager(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        this.formLogger = new FormLogger(plugin);
    }

    public void openLuckPermsOverrideForm(Player player, Area area) {
        plugin.getFormModuleManager().openGroupPermissions(player, area);
    }

    public void openLuckPermsGroupList(Player player, Area area) {
        plugin.getFormModuleManager().openGroupPermissions(player, area);
    }

    public void openBasicSettings(Player player, Area area) {
        plugin.getFormModuleManager().openBasicSettings(player, area);
    }

    public void openBuildingPermissions(Player player, Area area) {
        plugin.getFormModuleManager().openBuildingSettings(player, area);
    }

    public void openMainMenu(Player player) {
        plugin.getFormModuleManager().openMainMenu(player);
    }

    public void openEditForm(Player player, Area area) {
        plugin.getFormModuleManager().openEditForm(player, area);
    }

    public void openSettingsMenu(Player player) {
        plugin.getFormModuleManager().openPluginSettings(player);
    }

    public void openEditListForm(Player player) {
        plugin.getFormModuleManager().openEditListForm(player);
    }

    public void openCreateForm(Player player) {
        plugin.getFormModuleManager().openCreateForm(player);
    }

    public void openDeleteList(Player player) {
        plugin.getFormModuleManager().openDeleteAreaForm(player);
    }

    public void openFormById(Player player, String formId, Area area) {
        Timer.Sample formTimer = plugin.getPerformanceMonitor().startTimer();
        try {
            // Forward to the FormModuleManager for MOT-style forms
            plugin.getFormModuleManager().openFormById(player, formId, area);
        } catch (Exception e) {
            plugin.getPerformanceMonitor().stopTimer(formTimer, "form_open_failed");
            // Check for validation errors first
            if (e instanceof RuntimeException && e.getMessage() != null && 
                e.getMessage().contains("_validation_error_already_shown")) {
                if (plugin.isDebugMode()) {
                    plugin.debug("[Form] Not showing generic error for validation error: " + e.getMessage());
                }
            } else {
                handleGuiError(player, "Error opening form: " + formId, e);
            }
            // Only cleanup on error
            cleanup(player);
        } finally {
            plugin.getPerformanceMonitor().stopTimer(formTimer, "form_open");
        }
    }

    private void logFormSend(Player player, FormWindow form, String formId) {
        if (!plugin.isDebugMode()) return;
        
        plugin.debug(String.format("[GUI] Sending form %s to %s:", 
            formId, player.getName()));

        if (form instanceof FormWindowCustom customForm) {
            plugin.debug(String.format("  Type: Custom\n  Title: %s\n  Elements: %d",
                customForm.getTitle(), customForm.getElements().size()));
        } else if (form instanceof FormWindowSimple simpleForm) {
            plugin.debug(String.format("  Type: Simple\n  Title: %s\n  Buttons: %d",
                simpleForm.getTitle(), simpleForm.getButtons().size()));
        }
    }

    private void handleGuiError(Player player, String message, Exception e) {
        // Check for validation errors that are already shown to the player
        if (e instanceof RuntimeException && e.getMessage() != null && 
            e.getMessage().contains("_validation_error_already_shown")) {
            // Don't show generic error for validation errors that were already handled
            if (plugin.isDebugMode()) {
                plugin.debug("[GUI] Validation error already shown to player " + player.getName());
            }
            return;
        }
        
        // Log error details
        String error = String.format("[GUI] %s: %s", message, e.getMessage());
        plugin.getLogger().error(error);
        
        // Log debug info if enabled
        if (plugin.isDebugMode()) {
            plugin.debug("[GUI] Error Details:");
            plugin.debug("  Player: " + player.getName());
            plugin.debug("  Message: " + message);
            plugin.debug("  Exception: " + e.getClass().getName());
            plugin.debug("  Cause: " + e.getMessage());
            e.printStackTrace();
        }

        // Notify player
        player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
    }

    public boolean validateForm(Player player, FormResponseCustom response, String formType) {
        Timer.Sample timer = plugin.getPerformanceMonitor().startTimer();
        try {
            ValidationResult result = FormValidator.validate(response, formType);
            if (!result.isValid()) {
                player.sendMessage("§c" + result.getMessage());
                if (plugin.isDebugMode()) {
                    plugin.debug("[Form] Validation failed: " + result.getMessage());
                }
                // Throw validation error to prevent showing generic error
                throw new RuntimeException("_validation_error_already_shown");
            }
            return true;
        } catch (RuntimeException e) {
            // Rethrow validation errors
            if (e.getMessage() != null && e.getMessage().contains("_validation_error_already_shown")) {
                throw e;
            }
            
            plugin.getLogger().error("Validation error", e);
            player.sendMessage("§cAn error occurred while validating your input");
            // Don't use return false here, throw an exception with the special marker
            throw new RuntimeException("_validation_error_already_shown");
        } finally {
            plugin.getPerformanceMonitor().stopTimer(timer, "form_validation");
        }
    }

    public void cleanup() {
        // Prevent concurrent cleanup runs
        if (!cleanupRunning.compareAndSet(false, true)) {
            return;
        }

        try {
            final long currentTime = System.currentTimeMillis();
            
            // Only cleanup expired form data
            plugin.getFormIdMap().entrySet().removeIf(entry -> {
                FormTrackingData data = entry.getValue();
                return currentTime - data.getCreationTime() > FORM_EXPIRY_TIME;
            });

            // Don't clear navigation history to maintain state
            navigationHistory.entrySet().removeIf(entry -> 
                entry.getValue().isEmpty() && !plugin.getFormIdMap().containsKey(entry.getKey()));

        } finally {
            cleanupRunning.set(false);
            if (plugin.isDebugMode()) {
                plugin.debug("Form cleanup completed");
            }
        }
    }

    public void cleanup(Player player) {
        if (plugin.isDebugMode()) {
            plugin.debug("Cleaning up form data for " + player.getName());
            formLogger.logFormData(player.getName());
        }
        
        // Remove all form tracking data
        plugin.getFormIdMap().remove(player.getName());
        plugin.getFormIdMap().remove(player.getName() + "_editing");
        
        // Clear navigation history
        clearNavigationHistory(player);
        
        // Remove any custom form data keys
        String playerName = player.getName();
        plugin.getFormIdMap().entrySet().removeIf(entry -> 
            entry.getKey().startsWith(playerName + "_"));
    }

    private void checkThreadSafety(String operation) {
        if (plugin.isDebugMode() && !plugin.getServer().isPrimaryThread()) {
            plugin.getLogger().warning(String.format(
                "[GUI] Operation '%s' called from non-main thread!\n" +
                "Thread: %s\n" +
                "Stack trace:",
                operation,
                Thread.currentThread().getName()
            ));
            Thread.dumpStack();
        }
    }

    private void pushForm(Player player, String formId) {
        navigationHistory.computeIfAbsent(player.getName(), k -> new ArrayDeque<>())
                        .push(formId);
    }

    private String popForm(Player player) {
        Deque<String> history = navigationHistory.get(player.getName());
        return history != null ? history.poll() : null;
    }

    public void handleBackNavigation(Player player) {
        String previousForm = popForm(player);
        if (previousForm != null) {
            // Get area context if it exists
            FormTrackingData areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
            Area area = null;
            if (areaData != null) {
                area = plugin.getArea(areaData.getFormId());
            }
            
            // Open previous form with area context if needed
            openFormById(player, previousForm, area);
        } else {
            // If no history, go to main menu
            openFormById(player, FormIds.MAIN_MENU, null);
        }
    }

    public void clearNavigationHistory(Player player) {
        navigationHistory.remove(player.getName());
    }

    protected void checkLuckPermsAvailable(Player player) {
        if (!plugin.isLuckPermsEnabled()) {
            throw new IllegalStateException("LuckPerms is not available");
        }
        
        try {
            // Use reflection to safely access the user manager
            Object luckPermsApi = plugin.getLuckPermsApi();
            Object userManager = luckPermsApi.getClass().getMethod("getUserManager").invoke(luckPermsApi);
            
            // Check if user is loaded
            boolean isLoaded = (boolean) userManager.getClass()
                .getMethod("isLoaded", java.util.UUID.class)
                .invoke(userManager, player.getUniqueId());
                
            if (!isLoaded) {
                throw new IllegalStateException("LuckPerms user data not loaded");
            }
        } catch (Exception e) {
            throw new IllegalStateException("Error accessing LuckPerms: " + e.getMessage(), e);
        }
    }

    public void visualizeArea(Player player, Area area, int duration) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            stopVisualization(player);

            // Calculate visualization points more efficiently
            List<Vector3> points = calculateVisualizationPoints(area);
            Iterator<Vector3> iterator = points.iterator();

            TaskHandler task = plugin.getServer().getScheduler().scheduleRepeatingTask(plugin, () -> {
                if (!iterator.hasNext()) {
                    return;
                }

                // Spawn particles in batches for better performance
                for (int i = 0; i < 10 && iterator.hasNext(); i++) {
                    Vector3 point = iterator.next();
                    spawnParticle(player, point, new DustParticle(point, 255, 0, 0));
                }
            }, duration);

            visualizationTasks.put(player.getName(), task);
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "area_visualize");
        }
    }

    public void stopVisualization(Player player) {
        TaskHandler task = visualizationTasks.remove(player.getName());
        if (task != null) {
            task.cancel();
        }
    }

    private List<Vector3> calculateVisualizationPoints(Area area) {
        List<Vector3> points = new ArrayList<>();
        int spacing = 2; // Adjust spacing between particles
        AreaDTO.Bounds bounds = area.toDTO().bounds();

        // Calculate edges more efficiently
        for (double x = bounds.xMin(); x <= bounds.xMax(); x += spacing) {
            addEdgePoints(points, x, bounds.yMin(), bounds.zMin(), bounds.yMax(), bounds.zMax());
        }
        
        for (double y = bounds.yMin(); y <= bounds.yMax(); y += spacing) {
            addEdgePoints(points, bounds.xMin(), y, bounds.zMin(), bounds.xMax(), bounds.zMax());
        }
        
        for (double z = bounds.zMin(); z <= bounds.zMax(); z += spacing) {
            addEdgePoints(points, bounds.xMin(), bounds.yMin(), z, bounds.xMax(), bounds.yMax());
        }

        Collections.shuffle(points); // Randomize for better visual effect
        return points;
    }

    private void addEdgePoints(List<Vector3> points, double x, double y, double z, double maxY, double maxZ) {
        points.add(new Vector3(x, y, z));
        points.add(new Vector3(x, y, maxZ));
        points.add(new Vector3(x, maxY, z));
        points.add(new Vector3(x, maxY, maxZ));
    }

    private void spawnParticle(Player player, Vector3 point, DustParticle particle) {
        particle.setComponents(point.x, point.y, point.z);
        player.getLevel().addParticle(particle);
    }
}
