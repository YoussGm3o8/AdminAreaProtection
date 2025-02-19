package adminarea.managers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaBuilder;
import adminarea.area.AreaDTO;
import adminarea.constants.AdminAreaConstants;
import adminarea.data.FormTrackingData;
import adminarea.form.FormFactory;
import adminarea.form.FormRegistry;
import adminarea.form.validation.FormValidator;
import adminarea.form.validation.ValidationResult;
import adminarea.permissions.PermissionToggle;
import adminarea.form.handlers.*;
import cn.nukkit.Player;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.form.window.FormWindowSimple;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.element.ElementInput;
import cn.nukkit.form.element.ElementLabel;
import cn.nukkit.form.element.ElementToggle;
import cn.nukkit.level.Position;
import io.micrometer.core.instrument.Timer;
import adminarea.interfaces.IGuiManager;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Deque;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class GuiManager implements IGuiManager {
    private final AdminAreaProtectionPlugin plugin;
    private final FormFactory formFactory;
    private final FormRegistry formRegistry;

    // Add thread monitoring
    private final AtomicBoolean cleanupRunning = new AtomicBoolean(false);
    private final Map<String, Deque<String>> navigationHistory = new ConcurrentHashMap<>();
    private static final long FORM_EXPIRY_TIME = 300000; // 5 minutes in ms

    public GuiManager(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        // Initialize FormFactory first
        this.formFactory = new FormFactory(plugin);
        // Then create FormRegistry
        this.formRegistry = plugin.getFormRegistry();
        this.formRegistry.initialize(); // Initialize the registry once
    }

    private boolean checkPermission(Player player, String permission) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            if (!player.hasPermission(permission)) {
                player.sendMessage(plugin.getLanguageManager().get("messages.noPermission"));
                return false;
            }
            return true;
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "permission_check");
        }
    }

    @Override
    public void openMainMenu(Player player) {
        plugin.getLogger().info(String.format(
            "[GUI] Opening main menu for player %s",
            player.getName()
        ));
        checkThreadSafety("openMainMenu");
        if (!checkPermission(player, AdminAreaConstants.Permissions.MANAGE)) {
            return;
        }
        try {
            FormWindow form = formFactory.createMainMenu();
            plugin.getFormIdMap().put(player.getName(), 
                new FormTrackingData(AdminAreaConstants.FORM_MAIN_MENU, System.currentTimeMillis()));
            sendForm(player, form, AdminAreaConstants.FORM_MAIN_MENU);
        } catch (Exception e) {
            handleGuiError(player, "Error opening main menu", e);
        }
    }

    public void openCreateForm(Player player) {
        checkThreadSafety("openCreateForm");
        Timer.Sample formTimer = plugin.getPerformanceMonitor().startTimer();
        try {
            Position[] positions = plugin.getPlayerPositions().get(player.getName());
            FormWindow form = formFactory.createAreaForm(positions);
            sendForm(player, form, AdminAreaConstants.FORM_CREATE_AREA);
            if (plugin.getConfigManager().isDebugEnabled()) {
                plugin.getLogger().debug("Opened create form for player: " + player.getName());
            }
        } catch (Exception e) {
            plugin.getPerformanceMonitor().stopTimer(formTimer, "form_create_show_failed");
            handleGuiError(player, "Error opening create form", e);
        }
    }

    public void openEditForm(Player player, Area area) {
        try {
            if (area == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound"));
                return;
            }
            FormWindow form = createEditForm(area);
            plugin.getFormIdMap().put(player.getName() + "_editing", 
                new FormTrackingData(area.getName(), System.currentTimeMillis()));
            sendForm(player, form, AdminAreaConstants.FORM_EDIT_AREA);
            if (plugin.getConfigManager().isDebugEnabled()) {
                plugin.getLogger().debug("Opened edit form for area: " + area.getName());
            }
        } catch (Exception e) {
            handleGuiError(player, "Error opening edit form", e);
        }
    }

    public void openAreaListForm(Player player, String title, String content, String formId) {
        try {
            FormWindow form = formFactory.createAreaListForm(title, content, plugin.getAreas());
            sendForm(player, form, formId);
        } catch (Exception e) {
            handleGuiError(player, "Error opening area list", e);
        }
    }

    public void openLuckPermsOverrideForm(Player player, Area area) {
        checkThreadSafety("openLuckPermsOverrideForm");
        try {
            if (!plugin.isLuckPermsEnabled()) {
                player.sendMessage(plugin.getLanguageManager().get("messages.form.luckpermsNotAvailable"));
                return;
            }
            if (!plugin.getLuckPermsApi().getUserManager().isLoaded(player.getUniqueId())) {
                player.sendMessage(plugin.getLanguageManager().get("messages.form.luckpermsUserNotLoaded"));
                return;
            }
            FormWindow form = formFactory.createLuckPermsOverrideForm(area);
            sendForm(player, form, AdminAreaConstants.FORM_OVERRIDE_EDIT);
            plugin.getFormIdMap().put(player.getName() + "_editing", new FormTrackingData(area.getName(), System.currentTimeMillis()));
        } catch (Exception e) {
            handleGuiError(player, "Error opening permissions form", e);
        }
    }

    public void openEditList(Player player) {
        try {
            String content = plugin.getLanguageManager().get("gui.editArea.selectArea");
            openAreaListForm(player, plugin.getLanguageManager().get("gui.editArea.title"), content, AdminAreaConstants.FORM_EDIT_LIST);
        } catch (Exception e) {
            handleGuiError(player, "Error opening edit list", e);
        }
    }

    public void openDeleteList(Player player) {
        try {
            FormWindow form = formFactory.createDeleteAreaForm();
            if (form != null) {
                sendForm(player, form, AdminAreaConstants.FORM_DELETE_SELECT);
            } else {
                player.sendMessage(plugin.getLanguageManager().get("messages.form.error.createDeleteForm"));
            }
        } catch (Exception e) {
            handleGuiError(player, "Error opening delete list", e);
        }
    }

    public void openPlayerAreaManagement(Player player) {
        try {
            if (!player.hasPermission("adminarea.admin")) {
                player.sendMessage(plugin.getLanguageManager().get("messages.form.noPermission"));
                return;
            }
            FormWindow form = formFactory.createPlayerAreaManagementForm();
            sendForm(player, form, AdminAreaConstants.FORM_PLAYER_MANAGEMENT);
        } catch (Exception e) {
            handleGuiError(player, "Error opening player management", e);
        }
    }

    public void openDeleteArea(Player player, Area area) {
        try {
            String message = plugin.getLanguageManager().get("gui.deleteArea.confirmMessage") + area.getName() + "?";
            FormWindow form = formFactory.createDeleteConfirmForm(area.getName(), message);
            sendForm(player, form, AdminAreaConstants.FORM_DELETE_CONFIRM);
        } catch (Exception e) {
            handleGuiError(player, "Error opening delete confirmation", e);
        }
    }

    @Override
    public void openAreaSettings(Player player, Area area) {
        plugin.getLogger().info(String.format(
            "[GUI] Opening area settings for player %s, area %s",
            player.getName(),
            area.getName()
        ));
        try {
            FormWindow form = formFactory.createAreaSettingsMenu(area);
            pushForm(player, AdminAreaConstants.FORM_AREA_SETTINGS);
            plugin.getFormIdMap().put(player.getName() + "_editing", 
                new FormTrackingData(area.getName(), System.currentTimeMillis()));
            sendForm(player, form, AdminAreaConstants.FORM_AREA_SETTINGS);
        } catch (Exception e) {
            handleGuiError(player, "Error opening area settings", e);
        }
    }

    public void openLuckPermsGroupList(Player player, Area area) {
        try {
            checkLuckPermsAvailable(player);
            checkThreadSafety("openLuckPermsGroupList");

            FormWindow form = formFactory.createLuckPermsGroupListForm(area);
            if (form != null) {
                plugin.getFormIdMap().put(player.getName() + "_editing", 
                    new FormTrackingData(area.getName(), System.currentTimeMillis()));
                sendForm(player, form, AdminAreaConstants.FORM_GROUP_SELECT);
            }
        } catch (IllegalStateException e) {
            player.sendMessage(plugin.getLanguageManager().get("messages.form.luckpermsNotAvailable"));
        } catch (Exception e) {
            handleGuiError(player, "Failed to open group list", e);
        }
    }

    public void showError(Player player, String message) {
        try {
            FormWindow form = formFactory.createErrorForm(message);
            sendForm(player, form, AdminAreaConstants.FORM_ERROR);
        } catch (Exception e) {
            player.sendMessage("§c" + message);
        }
    }

    public void openBasicSettings(Player player, Area area) {
        try {
            FormWindow form = createBasicSettingsForm(area);
            plugin.getFormIdMap().put(player.getName() + "_editing", 
                new FormTrackingData(area.getName(), System.currentTimeMillis()));
            sendForm(player, form, AdminAreaConstants.FORM_BASIC_SETTINGS);
        } catch (Exception e) {
            handleGuiError(player, "Error opening basic settings", e);
        }
    }

    public void openProtectionSettings(Player player, Area area) {
        try {
            FormWindow form = formFactory.createProtectionSettingsForm(area);
            plugin.getFormIdMap().put(player.getName() + "_editing", 
                new FormTrackingData(area.getName(), System.currentTimeMillis()));
            sendForm(player, form, AdminAreaConstants.FORM_PROTECTION_SETTINGS);
        } catch (Exception e) {
            handleGuiError(player, 
                "Failed to open protection settings: " + e.getMessage(), e);
        }
    }

    public void sendForm(Player player, FormWindow form, String formId) {
        Timer.Sample formTimer = plugin.getPerformanceMonitor().startTimer();
        try {
            // Add detailed logging
            plugin.getLogger().info(String.format(
                "[GUI] Sending form '%s' (ID: %s) to player %s",
                form.getClass().getSimpleName(),
                formId,
                player.getName()
            ));

            if (plugin.isDebugMode()) {
                if (form instanceof FormWindowCustom) {
                    plugin.getLogger().debug(String.format(
                        "[GUI] Custom form details:\n" +
                        "  Title: %s\n" +
                        "  Elements: %d",
                        ((FormWindowCustom) form).getTitle(),
                        ((FormWindowCustom) form).getElements().size()
                    ));
                } else if (form instanceof FormWindowSimple) {
                    plugin.getLogger().debug(String.format(
                        "[GUI] Simple form details:\n" +
                        "  Title: %s\n" +
                        "  Content: %s\n" +
                        "  Buttons: %d",
                        ((FormWindowSimple) form).getTitle(),
                        ((FormWindowSimple) form).getContent(),
                        ((FormWindowSimple) form).getButtons().size()
                    ));
                }
            }
            
            if (plugin.isDebugMode()) {
                plugin.debug(String.format("[GUI] Sending form %s to player %s", 
                    formId, player.getName()));
            }
            
            cleanup();
            
            // Store form data
            plugin.getFormIdMap().put(player.getName(), 
                new FormTrackingData(formId, System.currentTimeMillis()));
            
            if (plugin.isDebugMode()) {
                plugin.debug(String.format("[GUI] Form tracking data stored for %s: %s",
                    player.getName(), formId));
            }
            
            player.showFormWindow(form);
            
        } catch (Exception e) {
            plugin.getPerformanceMonitor().stopTimer(formTimer, "form_show_failed");
            handleGuiError(player, "Error showing form", e);
        } finally {
            plugin.getPerformanceMonitor().stopTimer(formTimer, "form_show");
        }
    }

    private void handleGuiError(Player player, String message, Exception e) {
        plugin.getLogger().error(String.format(
            "[GUI] Error occurred:\n" +
            "  Player: %s\n" +
            "  Message: %s\n" +
            "  Exception: %s\n" +
            "  Cause: %s",
            player.getName(),
            message,
            e.getClass().getName(),
            e.getMessage()
        ));
        String detailedError = String.format("%s: %s", message, e.toString());
        plugin.getLogger().error(detailedError);
        
        if (plugin.isDebugMode()) {
            plugin.debug("[GUI] Error Details:");
            plugin.debug("  Player: " + player.getName());
            plugin.debug("  Message: " + message);
            plugin.debug("  Exception: " + e.getClass().getName());
            plugin.debug("  Cause: " + e.getMessage());
            
            // Log stack trace
            StackTraceElement[] trace = e.getStackTrace();
            if (trace.length > 0) {
                plugin.debug("  Stack trace:");
                for (int i = 0; i < Math.min(5, trace.length); i++) {
                    plugin.debug("    " + trace[i].toString());
                }
            }
        }

        player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
    }

    @Override
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
            plugin.getLogger().debug("[GUI] Cleanup already running, skipping...");
            return;
        }

        plugin.getLogger().debug("[GUI] Starting form cleanup");
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            final long currentTime = System.currentTimeMillis();
            
            // Cleanup form tracking data
            plugin.getFormIdMap().entrySet().removeIf(entry -> {
                FormTrackingData data = entry.getValue();
                boolean expired = currentTime - data.getCreationTime() > FORM_EXPIRY_TIME;
                if (expired && plugin.isDebugMode()) {
                    plugin.debug("Removing expired form data for: " + entry.getKey());
                }
                return expired;
            });

            // Cleanup navigation history
            navigationHistory.entrySet().removeIf(entry -> {
                boolean empty = entry.getValue().isEmpty();
                if (empty && plugin.isDebugMode()) {
                    plugin.debug("Removing empty navigation history for: " + entry.getKey());
                }
                return empty;
            });

        } catch (Exception e) {
            plugin.getLogger().error("Error during form cleanup", e);
        } finally {
            cleanupRunning.set(false);
            plugin.getPerformanceMonitor().stopTimer(sample, "form_cleanup");
            plugin.getLogger().debug("[GUI] Form cleanup complete");
        }
    }

    // Add method to check thread safety during development
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

    public FormWindow createEditForm(Area area) {
        if (area == null) return null;
        
        FormWindowSimple form = new FormWindowSimple("Edit Area: " + area.getName(), "Select what to edit:");
        
        // Basic options with clear distinction between categories
        form.addButton(new ElementButton("Area Settings\n§7Basic area configuration"));
        form.addButton(new ElementButton("Protection Settings\n§7Manage protection flags"));
        
        // Category-specific buttons with unique identifiers
        form.addButton(new ElementButton("Building Settings\n§7Building and interaction"));
        form.addButton(new ElementButton("Environment Settings\n§7Natural events"));
        form.addButton(new ElementButton("Entity Settings\n§7Mobs and entities"));
        form.addButton(new ElementButton("Technical Settings\n§7Redstone and mechanics"));
        form.addButton(new ElementButton("Special Settings\n§7Other permissions"));
        
        // LuckPerms integration button
        if (plugin.isLuckPermsEnabled()) {
            form.addButton(new ElementButton("Group Permissions\n§7LuckPerms groups"));
        }
        
        form.addButton(new ElementButton("Back to Main Menu"));
        
        return form;
    }

    @Override
    public void openBuildingPermissions(Player player, Area area) {
        FormWindow form = createCategoryForm(area, PermissionToggle.Category.BUILDING);
        sendForm(player, form, AdminAreaConstants.FORM_BUILDING_SETTINGS);
    }

    @Override
    public void openEnvironmentSettings(Player player, Area area) {
        FormWindow form = createCategoryForm(area, PermissionToggle.Category.ENVIRONMENT);
        sendForm(player, form, AdminAreaConstants.FORM_ENVIRONMENT_SETTINGS);
    }

    @Override
    public void openEntityControls(Player player, Area area) {
        FormWindow form = createCategoryForm(area, PermissionToggle.Category.ENTITY);
        sendForm(player, form, AdminAreaConstants.FORM_ENTITY_SETTINGS);
    }

    @Override
    public void openRedstoneAndMechanics(Player player, Area area) {
        FormWindow form = createCategoryForm(area, PermissionToggle.Category.TECHNICAL);
        sendForm(player, form, AdminAreaConstants.FORM_TECHNICAL_SETTINGS);
    }

    @Override
    public void openSpecialPermissions(Player player, Area area) {
        FormWindow form = createCategoryForm(area, PermissionToggle.Category.SPECIAL);
        sendForm(player, form, AdminAreaConstants.FORM_SPECIAL_SETTINGS);
    }

    public FormWindow createBuildingPermissionsForm(Area area) {
        return createCategoryForm(area, PermissionToggle.Category.BUILDING);
    }

    public FormWindow createEnvironmentSettingsForm(Area area) {
        return createCategoryForm(area, PermissionToggle.Category.ENVIRONMENT);
    }

    public FormWindow createEntityControlsForm(Area area) {
        return createCategoryForm(area, PermissionToggle.Category.ENTITY);
    }

    public FormWindow createRedstoneAndMechanicsForm(Area area) {
        return createCategoryForm(area, PermissionToggle.Category.TECHNICAL);
    }

    public FormWindow createSpecialPermissionsForm(Area area) {
        return createCategoryForm(area, PermissionToggle.Category.SPECIAL);
    }

    public FormWindow createCategoryForm(Area area, PermissionToggle.Category category) {
        FormWindowCustom form = new FormWindowCustom(category.getDisplayName() + ": " + area.getName());
        
        // Add header label
        form.addElement(new ElementLabel("§2" + category.getDisplayName() + " Settings"));

        // Add category-specific toggles based on direct attributes
        switch (category) {
            case BUILDING -> addBuildingToggles(form, area);
            case ENVIRONMENT -> addEnvironmentToggles(form, area);
            case ENTITY -> addEntityToggles(form, area);
            case TECHNICAL -> addTechnicalToggles(form, area);
            case SPECIAL -> addSpecialToggles(form, area);
        }

        return form;
    }

    private void addBuildingToggles(FormWindowCustom form, Area area) {
        AreaDTO dto = area.toDTO();
        AreaDTO.Permissions perms = dto.permissions();
        form.addElement(new ElementToggle("Allow Building", perms.allowBuild()));
        form.addElement(new ElementToggle("Allow Breaking", perms.allowBreak()));
        form.addElement(new ElementToggle("Allow Container Access", perms.allowContainer()));
        form.addElement(new ElementToggle("Allow Item Frame Usage", perms.allowItemFrame()));
        form.addElement(new ElementToggle("Allow Armor Stand Usage", perms.allowArmorStand()));
    }

    private void addEnvironmentToggles(FormWindowCustom form, Area area) {
        AreaDTO dto = area.toDTO();
        AreaDTO.Permissions perms = dto.permissions();
        form.addElement(new ElementToggle("Allow Fire Spread", perms.allowFire()));
        form.addElement(new ElementToggle("Allow Liquid Flow", perms.allowLiquid()));
        form.addElement(new ElementToggle("Allow Block Spread", perms.allowBlockSpread()));
        form.addElement(new ElementToggle("Allow Leaf Decay", perms.allowLeafDecay()));
        form.addElement(new ElementToggle("Allow Ice Form/Melt", perms.allowIceForm()));
        form.addElement(new ElementToggle("Allow Snow Form/Melt", perms.allowSnowForm()));
    }

    private void addEntityToggles(FormWindowCustom form, Area area) {
        AreaDTO dto = area.toDTO();
        AreaDTO.Permissions perms = dto.permissions();
        form.addElement(new ElementToggle("Allow PvP", perms.allowPvP()));
        form.addElement(new ElementToggle("Allow Monster Spawning", perms.allowMonsterSpawn()));
        form.addElement(new ElementToggle("Allow Animal Spawning", perms.allowAnimalSpawn()));
        form.addElement(new ElementToggle("Allow Entity Damage", perms.allowDamageEntities()));
        form.addElement(new ElementToggle("Allow Animal Breeding", perms.allowBreeding()));
        form.addElement(new ElementToggle("Allow Monster Targeting", perms.allowMonsterTarget()));
        form.addElement(new ElementToggle("Allow Leashing", perms.allowLeashing()));
    }

    private void addTechnicalToggles(FormWindowCustom form, Area area) {
        AreaDTO dto = area.toDTO();
        AreaDTO.Permissions perms = dto.permissions();
        form.addElement(new ElementToggle("Allow Redstone", perms.allowRedstone()));
        form.addElement(new ElementToggle("Allow Pistons", perms.allowPistons()));
        form.addElement(new ElementToggle("Allow Hoppers", perms.allowHopper()));
        form.addElement(new ElementToggle("Allow Dispensers", perms.allowDispenser()));
    }

    private void addSpecialToggles(FormWindowCustom form, Area area) {
        AreaDTO dto = area.toDTO();
        AreaDTO.Permissions perms = dto.permissions();
        form.addElement(new ElementToggle("Allow Item Drops", perms.allowItemDrop()));
        form.addElement(new ElementToggle("Allow Item Pickup", perms.allowItemPickup()));
        form.addElement(new ElementToggle("Allow Vehicle Placement", perms.allowVehiclePlace()));
        form.addElement(new ElementToggle("Allow Vehicle Entry", perms.allowVehicleEnter()));
        form.addElement(new ElementToggle("Allow Flight", perms.allowFlight()));
        form.addElement(new ElementToggle("Allow Ender Pearl", perms.allowEnderPearl()));
        form.addElement(new ElementToggle("Allow Chorus Fruit", perms.allowChorusFruit()));
    }

    @Override
    public void handleEditFormResponse(Player player, int buttonId, Area area) {
        plugin.getLogger().info(String.format(
            "[GUI] Player %s clicked button %d in edit form for area %s",
            player.getName(),
            buttonId,
            area != null ? area.getName() : "null"
        ));
        checkThreadSafety("handleEditFormResponse");
        try {
            // Add Object parameter and type check
            if (area == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound"));
                return;
            }

            switch (buttonId) {
                case 0 -> openBasicSettings(player, area);
                case 1 -> openProtectionSettings(player, area);
                case 2 -> openBuildingPermissions(player, area);
                case 3 -> openEnvironmentSettings(player, area);
                case 4 -> openEntityControls(player, area);
                case 5 -> openRedstoneAndMechanics(player, area);
                case 6 -> openSpecialPermissions(player, area);
                case 7 -> {
                    if (plugin.isLuckPermsEnabled()) {
                        openLuckPermsGroupList(player, area);
                    } else {
                        plugin.saveArea(area);
                        openMainMenu(player);
                    }
                }
                case 8 -> {
                    plugin.saveArea(area);
                    openMainMenu(player);
                }
                default -> player.sendMessage("§cInvalid selection.");
            }
            
            // Update form tracking
            plugin.getFormIdMap().put(player.getName() + "_editing", 
                new FormTrackingData(area.getName(), System.currentTimeMillis()));
            
            if (plugin.getConfigManager().isDebugEnabled()) {
                plugin.getLogger().debug("Processed edit form response for area: " + area.getName() 
                    + ", button: " + buttonId);
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error handling edit form response", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.error.updateArea", 
                Map.of("error", e.getMessage())));
        }
    }

    public FormWindow createBasicSettingsForm(Area area) {
        FormWindowCustom form = new FormWindowCustom("Basic Settings: " + area.getName());
        
        AreaDTO dto = area.toDTO();
        
        // Basic area properties
        form.addElement(new ElementLabel("§2General Settings"));
        form.addElement(new ElementInput("Area Name", "Enter area name", dto.name()));
        form.addElement(new ElementInput("Priority", "Enter priority (0-100)", 
            String.valueOf(dto.priority())));
            
        // Display settings
        form.addElement(new ElementLabel("§2Display Settings"));
        form.addElement(new ElementToggle("Show Area Title", dto.showTitle()));
        form.addElement(new ElementInput("Enter Message", "Message shown when entering", 
            dto.enterMessage() != null ? dto.enterMessage() : ""));
        form.addElement(new ElementInput("Leave Message", "Message shown when leaving", 
            dto.leaveMessage() != null ? dto.leaveMessage() : ""));
            
        return form;
    }

    private void handleSpecialResponse(FormResponseCustom response, AreaBuilder builder) {
        int index = 1;
        // Set each permission individually using setPermission
        builder.setPermission("allowItemDrop", response.getToggleResponse(index++))
               .setPermission("allowItemPickup", response.getToggleResponse(index++))
               .setPermission("allowCreeper", response.getToggleResponse(index++))
               .setPermission("allowChorusFruit", response.getToggleResponse(index++));
    }

    private void checkLuckPermsAvailable(Player player) throws IllegalStateException {
        if (!plugin.isLuckPermsEnabled()) {
            throw new IllegalStateException("LuckPerms is not available");
        }
        if (!plugin.getLuckPermsApi().getUserManager().isLoaded(player.getUniqueId())) {
            throw new IllegalStateException("LuckPerms user data not loaded");
        }
    }

    @Override 
    public void openLuckPermsGroupPermissions(Player player, Area area, String groupName) {
        if (player == null) {
            return;
        }
        
        checkThreadSafety("openLuckPermsGroupPermissions");
        if (!checkPermission(player, AdminAreaConstants.Permissions.LUCKPERMS_EDIT)) {
            return;
        }
        try {
            // Validate parameters
            if (player == null || area == null || groupName == null) {
                plugin.debug("Invalid parameters for openLuckPermsGroupPermissions");
                return;
            }

            checkLuckPermsAvailable(player);

            FormWindow form = formFactory.createGroupPermissionForm(area, groupName);
            if (form != null) {
                // Store both area and group tracking data
                plugin.getFormIdMap().put(player.getName() + "_editing", 
                    new FormTrackingData(area.getName(), System.currentTimeMillis()));
                plugin.getFormIdMap().put(player.getName() + "_group",
                    new FormTrackingData(groupName, System.currentTimeMillis()));
                    
                sendForm(player, form, AdminAreaConstants.FORM_GROUP_PERMISSIONS);
                
                if (plugin.getConfigManager().isDebugEnabled()) {
                    plugin.debug("Opened LuckPerms group permissions form for " + 
                        groupName + " in area " + area.getName());
                }
            }
        } catch (IllegalStateException e) {
            player.sendMessage(plugin.getLanguageManager().get("messages.form.luckpermsNotAvailable"));
        } catch (Exception e) {
            handleGuiError(player, "Failed to open group permissions", e);
        }
    }

    @Override
    public void handleBackNavigation(Player player) {
        String previousForm = popForm(player);
        if (previousForm != null) {
            FormTrackingData areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
            if (areaData != null) {
                Area area = plugin.getArea(areaData.getFormId());
                if (area != null) {
                    openFormById(player, previousForm, area);
                }
            }
        }
    }

    private void openFormById(Player player, String formId, Area area) {
        switch (formId) {
            case AdminAreaConstants.FORM_MAIN_MENU -> openMainMenu(player);
            case AdminAreaConstants.FORM_AREA_SETTINGS -> openAreaSettings(player, area);
            case AdminAreaConstants.FORM_EDIT_AREA -> openEditForm(player, area);
            // Add cases for other form types
        }
    }

    /**
     * Opens a form showing all groups in a LuckPerms track
     * @param player The player to show the form to
     * @param area The area being edited
     * @param trackName The name of the LuckPerms track
     */
    public void openTrackGroupsForm(Player player, Area area, String trackName) {
        checkThreadSafety("openTrackGroupsForm");
        try {
            // Validate LuckPerms availability
            checkLuckPermsAvailable(player);
            
            // Get groups in track
            List<String> groups = plugin.getGroupsByTrack(trackName);
            if (groups.isEmpty()) {
                player.sendMessage(plugin.getLanguageManager().get("messages.luckperms.noGroupsInTrack", 
                    Map.of("track", trackName)));
                return;
            }

            FormWindow form = formFactory.createTrackGroupsForm(trackName);
            if (form != null) {
                // Store tracking data
                plugin.getFormIdMap().put(player.getName() + "_editing", 
                    new FormTrackingData(area.getName(), System.currentTimeMillis()));
                plugin.getFormIdMap().put(player.getName() + "_track",
                    new FormTrackingData(trackName, System.currentTimeMillis()));

                // Push form to navigation history
                pushForm(player, AdminAreaConstants.FORM_TRACK_GROUPS);
                
                // Send form
                sendForm(player, form, AdminAreaConstants.FORM_TRACK_GROUPS);

                if (plugin.getConfigManager().isDebugEnabled()) {
                    plugin.debug("Opened track groups form for track: " + trackName + 
                        " in area: " + area.getName());
                }
            }
        } catch (IllegalStateException e) {
            player.sendMessage(plugin.getLanguageManager().get("messages.form.luckpermsNotAvailable"));
        } catch (Exception e) {
            handleGuiError(player, "Failed to open track groups form", e);
        }
    }

    public void openListForm(Player player) {
        String title = plugin.getLanguageManager().get("gui.areaList.title");
        String content = plugin.getLanguageManager().get("gui.areaList.content");
        FormWindow form = plugin.getFormFactory().createAreaListForm(title, content, plugin.getAreas());
        player.showFormWindow(form);
    }

    public void openDeleteSelectForm(Player player) {
        FormWindow form = plugin.getFormFactory().createDeleteAreaForm();
        player.showFormWindow(form);
    }

}
