package adminarea.managers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.constants.AdminAreaConstants;
import adminarea.form.FormFactory;
import adminarea.form.FormRegistry;
import adminarea.form.validation.FormValidator;
import adminarea.form.validation.ValidationResult;
import adminarea.form.handlers.*;
import cn.nukkit.Player;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.level.Position;
import io.micrometer.core.instrument.Timer;

public class GuiManager {
    private final AdminAreaProtectionPlugin plugin;
    private final FormFactory formFactory;
    private final FormRegistry formRegistry;

    public GuiManager(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        this.formFactory = new FormFactory(plugin);
        this.formRegistry = new FormRegistry();
        registerFormHandlers();
    }

    private void registerFormHandlers() {
        formRegistry.registerHandler(new MainMenuHandler(plugin));
        formRegistry.registerHandler(new CreateAreaHandler(plugin));
        formRegistry.registerHandler(new EditAreaHandler(plugin));
        formRegistry.registerHandler(new DeleteAreaHandler(plugin));
        formRegistry.registerHandler(new AreaListHandler(plugin));
        formRegistry.registerHandler(new PlayerAreaHandler(plugin));
        formRegistry.registerHandler(new LuckPermsOverrideHandler(plugin));
        formRegistry.registerHandler(new PermissionSettingsHandler(plugin));
        formRegistry.registerHandler(new LuckPermsGroupPermHandler(plugin));
    }

    public void openMainMenu(Player player) {
        try {
            FormWindow form = formFactory.createMainMenu();
            sendForm(player, form, AdminAreaConstants.FORM_MAIN_MENU);
        } catch (Exception e) {
            handleGuiError(player, "Error opening main menu", e);
        }
    }

    public void openCreateForm(Player player) {
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
                player.sendMessage("§cArea not found!");
                return;
            }
            FormWindow form = formFactory.createEditForm(area);
            plugin.getFormIdMap().put(player.getName() + "_editing", area.getName()); // Ensure the area name is stored
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
            FormWindow form = formFactory.createAreaListForm(title, content);
            sendForm(player, form, formId);
        } catch (Exception e) {
            handleGuiError(player, "Error opening area list", e);
        }
    }

    public void openLuckPermsOverrideForm(Player player, Area area) {
        try {
            if (!plugin.getLuckPermsApi().getUserManager().isLoaded(player.getUniqueId())) {
                player.sendMessage("§cError: LuckPerms data not loaded");
                return;
            }
            FormWindow form = formFactory.createLuckPermsOverrideForm(area);
            sendForm(player, form, AdminAreaConstants.FORM_OVERRIDE_EDIT);
            plugin.getFormIdMap().put(player.getName() + "_editing", area.getName());
        } catch (Exception e) {
            handleGuiError(player, "Error opening permissions form", e);
        }
    }

    public void openEditList(Player player) {
        try {
            String content = "Select an area to edit:";
            openAreaListForm(player, "Edit Area", content, AdminAreaConstants.FORM_EDIT_LIST);
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
                player.sendMessage("§cError creating delete form");
            }
        } catch (Exception e) {
            handleGuiError(player, "Error opening delete list", e);
        }
    }

    public void openPlayerAreaManagement(Player player) {
        try {
            if (!player.hasPermission("adminarea.admin")) {
                player.sendMessage("§cYou don't have permission to manage player areas");
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
            String message = "Are you sure you want to delete area: " + area.getName() + "?";
            FormWindow form = formFactory.createDeleteConfirmForm(area.getName(), message);
            sendForm(player, form, AdminAreaConstants.FORM_DELETE_CONFIRM);
        } catch (Exception e) {
            handleGuiError(player, "Error opening delete confirmation", e);
        }
    }

    public void openAreaSettings(Player player, Area area) {
        try {
            FormWindow form = formFactory.createAreaSettingsForm(area);
            plugin.getFormIdMap().put(player.getName() + "_editing", area.getName());
            sendForm(player, form, AdminAreaConstants.FORM_AREA_SETTINGS);
        } catch (Exception e) {
            handleGuiError(player, "Error opening area settings", e);
        }
    }

    public void openLuckPermsGroupList(Player player, Area area) {
        try {
            if (plugin.getLuckPermsApi() == null) {
                player.sendMessage("§cLuckPerms is not available");
                return;
            }
            FormWindow form = formFactory.createLuckPermsGroupListForm(area);
            plugin.getFormIdMap().put(player.getName() + "_editing", area.getName());
            sendForm(player, form, AdminAreaConstants.FORM_GROUP_SELECT);
        } catch (Exception e) {
            handleGuiError(player, "Error opening group list", e);
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

    private void sendForm(Player player, FormWindow form, String formId) {
        Timer.Sample formTimer = plugin.getPerformanceMonitor().startTimer();
        try {
            if (form == null) {
                plugin.getPerformanceMonitor().stopTimer(formTimer, "form_creation_failed");
                player.sendMessage("§cError: Form could not be created");
                return;
            }
            // Store the form ID before showing the form
            plugin.getFormIdMap().put(player.getName(), formId);
            player.showFormWindow(form);
            if (plugin.getConfigManager().isDebugEnabled()) {
                plugin.getLogger().debug("Sent form " + formId + " to player " + player.getName());
            }
            plugin.getPerformanceMonitor().stopTimer(formTimer, "form_show_success");
        } catch (Exception e) {
            plugin.getPerformanceMonitor().stopTimer(formTimer, "form_show_failed");
            handleGuiError(player, "Error showing form", e);
        }
    }

    private void handleGuiError(Player player, String message, Exception e) {
        plugin.getLogger().error(message + ": " + e.getMessage());
        player.sendMessage("§c" + message);
        // Log detailed error for debugging
        if (plugin.getConfigManager().isDebugEnabled()) {
            e.printStackTrace();
        }
    }

    public boolean validateForm(Player player, FormResponseCustom response, String formType) {
        ValidationResult result = FormValidator.validate(response, formType);
        if (!result.isValid()) {
            player.sendMessage("§c" + result.getMessage());
            if (plugin.getConfigManager().isDebugEnabled()) {
                plugin.getLogger().debug("Form validation failed for " + formType + ": " + result.getMessage());
            }
            return false;
        }
        return true;
    }

    public void cleanup() {
        // Clean up any stored form data older than 5 minutes
        plugin.getFormIdMap().entrySet().removeIf(entry -> 
            System.currentTimeMillis() - entry.getValue().hashCode() > 300000);
    }
}
