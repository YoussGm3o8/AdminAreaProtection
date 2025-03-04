package adminarea.managers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaDTO;
import adminarea.constants.FormIds;
import adminarea.data.FormTrackingData;
import adminarea.form.FormRegistry;
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
    private final FormRegistry formRegistry;
    private final AtomicBoolean cleanupRunning = new AtomicBoolean(false);
    private final Map<String, Deque<String>> navigationHistory = new ConcurrentHashMap<>();
    private final long FORM_EXPIRY_TIME = 900000; // 15 minutes
    private final FormLogger formLogger;
    private final Map<String, TaskHandler> visualizationTasks = new ConcurrentHashMap<>();

    public GuiManager(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        this.formRegistry = plugin.getFormRegistry();
        this.formRegistry.initialize();
        this.formLogger = new FormLogger(plugin);
    }

    public void openLuckPermsOverrideForm(Player player, Area area) {
        openFormById(player, FormIds.GROUP_PERMISSIONS, area);
    }

    public void openLuckPermsGroupList(Player player, Area area) {
        openFormById(player, FormIds.GROUP_SELECT, area);
    }

    public void openBasicSettings(Player player, Area area) {
        openFormById(player, FormIds.BASIC_SETTINGS, area);
    }

    public void openBuildingPermissions(Player player, Area area) {
        openFormById(player, FormIds.BUILDING_SETTINGS, area);
    }

    public void openMainMenu(Player player) {
        openFormById(player, FormIds.MAIN_MENU, null);
    }

    public void openEditForm(Player player, Area area) {
        openFormById(player, FormIds.EDIT_AREA, area); 
    }

    public void openSettingsMenu(Player player) {
        openFormById(player, FormIds.PLUGIN_SETTINGS, null);
    }

    public void openEditListForm(Player player) {
        openFormById(player, FormIds.EDIT_LIST, null);
    }

    public void openCreateForm(Player player) {
        openFormById(player, FormIds.CREATE_AREA, null);
    }

    public void openDeleteList(Player player) {
        openFormById(player, FormIds.DELETE_LIST, null);
    }

    public void openFormById(Player player, String formId, Area area) {
        Timer.Sample formTimer = plugin.getPerformanceMonitor().startTimer();
        try {
            // Get handler and validate
            var handler = formRegistry.getHandler(formId);
            if (handler == null) {
                plugin.getLogger().error("No handler found for form ID: " + formId);
                return;
            }

            // Set form tracking data before creating form
            plugin.getFormIdMap().put(player.getName(),
                new FormTrackingData(formId, System.currentTimeMillis()));

            // Create and send form
            FormWindow form;
            if (area != null) {
                form = handler.createForm(player, area);
                // Store area being edited if provided
                plugin.getFormIdMap().put(player.getName() + "_editing",
                    new FormTrackingData(area.getName(), System.currentTimeMillis()));
            } else {
                form = handler.createForm(player);
            }

            if (form != null) {
                if (plugin.isDebugMode()) {
                    plugin.debug(String.format("[Form] Sending form %s to %s:", formId, player.getName()));
                    if (form instanceof FormWindowCustom customForm) {
                        plugin.debug(String.format("  Type: Custom\n  Title: %s\n  Elements: %d",
                            customForm.getTitle(), customForm.getElements().size()));
                    } else if (form instanceof FormWindowSimple simpleForm) {
                        plugin.debug(String.format("  Type: Simple\n  Title: %s\n  Buttons: %d",
                            simpleForm.getTitle(), simpleForm.getButtons().size()));
                    }
                    plugin.debug("[Form] Current form tracking data:");
                    var currentData = plugin.getFormIdMap().get(player.getName());
                    if (currentData != null) {
                        plugin.debug(String.format("  Current Form: %s (%.1fs ago)",
                            currentData.getFormId(),
                            (System.currentTimeMillis() - currentData.getCreationTime()) / 1000.0));
                    }
                    var editingData = plugin.getFormIdMap().get(player.getName() + "_editing");
                    if (editingData != null) {
                        plugin.debug(String.format("  Editing Area: %s (%.1fs ago)",
                            editingData.getFormId(),
                            (System.currentTimeMillis() - editingData.getCreationTime()) / 1000.0));
                    }
                }

                // Push current form to navigation history
                pushForm(player, formId);
                player.showFormWindow(form);
            }
        } catch (Exception e) {
            plugin.getPerformanceMonitor().stopTimer(formTimer, "form_open_failed");
            handleGuiError(player, "Error opening form: " + formId, e);
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
                if (plugin.getConfigManager().isDebugEnabled()) {
                    plugin.getLogger().debug("Form validation failed: " + result.getMessage());
                }
                return false;
            }
            return true;
        } catch (Exception e) {
            plugin.getLogger().error("Validation error", e);
            player.sendMessage("§cAn error occurred while validating your input");
            return false;
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
        if (!plugin.getLuckPermsApi().getUserManager().isLoaded(player.getUniqueId())) {
            throw new IllegalStateException("LuckPerms user data not loaded");
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
