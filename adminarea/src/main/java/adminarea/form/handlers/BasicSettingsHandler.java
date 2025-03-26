package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaBuilder;
import adminarea.area.AreaDTO;
import adminarea.constants.FormIds;
import adminarea.util.AreaValidationUtils;
import adminarea.util.ValidationUtils;
import cn.nukkit.Player;
import cn.nukkit.form.element.ElementInput;
import cn.nukkit.form.element.ElementLabel;
import cn.nukkit.form.element.ElementToggle;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowCustom;
import java.util.HashMap;
import java.util.Map;

public class BasicSettingsHandler extends BaseFormHandler {
    private static final int NAME_INDEX = 1;
    private static final int PRIORITY_INDEX = 2;
    private static final int SHOW_TITLE_INDEX = 3;
    private static final int ENTER_TITLE_INDEX = 5;
    private static final int ENTER_MESSAGE_INDEX = 6;
    private static final int LEAVE_TITLE_INDEX = 7;
    private static final int LEAVE_MESSAGE_INDEX = 8;
    
    private final AreaValidationUtils areaValidationUtils;

    public BasicSettingsHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
        this.areaValidationUtils = new AreaValidationUtils(plugin);
    }

    @Override
    public String getFormId() {
        return FormIds.BASIC_SETTINGS;
    }

    @Override
    public FormWindow createForm(Player player) {
        return createForm(player, getEditingArea(player));
    }

    @Override
    public FormWindow createForm(Player player, Area area) {
        if (area == null) {
            return null;
        }

        try {
            // Force reload the area from the database to ensure we have the latest data
            try {
                Area freshArea = plugin.getDatabaseManager().loadArea(area.getName());
                if (freshArea != null) {
                    // Use the fresh area
                    area = freshArea;
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Reloaded area from database for basic settings form");
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().error("Failed to reload area from database", e);
            }
            
            // Get title values from areaTitles.yml if available
            String enterTitle = area.toDTO().enterTitle();
            String enterMessage = area.toDTO().enterMessage();
            String leaveTitle = area.toDTO().leaveTitle();
            String leaveMessage = area.toDTO().leaveMessage();
            
            // Check if we have custom titles configured in areaTitles.yml
            if (plugin.getConfigManager().hasAreaTitleConfig(area.getName())) {
                enterTitle = plugin.getConfigManager().getAreaTitleText(area.getName(), "enter", "main", enterTitle);
                enterMessage = plugin.getConfigManager().getAreaTitleText(area.getName(), "enter", "subtitle", enterMessage);
                leaveTitle = plugin.getConfigManager().getAreaTitleText(area.getName(), "leave", "main", leaveTitle);
                leaveMessage = plugin.getConfigManager().getAreaTitleText(area.getName(), "leave", "subtitle", leaveMessage);
            }
            
            FormWindowCustom form = new FormWindowCustom(plugin.getLanguageManager().get("gui.basicSettings.title", 
                Map.of("area", area.getName())));
            
            // Add general settings section
            form.addElement(new ElementLabel(plugin.getLanguageManager().get("gui.basicSettings.sections.general")));
            form.addElement(new ElementInput(
                plugin.getLanguageManager().get("gui.basicSettings.labels.name"),
                plugin.getLanguageManager().get("gui.basicSettings.labels.namePlaceholder"),
                area.getName()
            ));
            form.addElement(new ElementInput(
                plugin.getLanguageManager().get("gui.basicSettings.labels.priority"),
                plugin.getLanguageManager().get("gui.basicSettings.labels.priorityPlaceholder"),
                String.valueOf(area.getPriority())
            ));
            form.addElement(new ElementToggle(
                plugin.getLanguageManager().get("gui.basicSettings.labels.showTitle"),
                area.toDTO().showTitle()
            ));
            
            // Add display settings section
            form.addElement(new ElementLabel(plugin.getLanguageManager().get("gui.basicSettings.sections.display")));
            form.addElement(new ElementInput(
                plugin.getLanguageManager().get("gui.basicSettings.labels.enterTitle"),
                plugin.getLanguageManager().get("gui.basicSettings.labels.enterTitlePlaceholder"),
                enterTitle
            ));
            form.addElement(new ElementInput(
                plugin.getLanguageManager().get("gui.basicSettings.labels.enterMessage"),
                plugin.getLanguageManager().get("gui.basicSettings.labels.enterPlaceholder"),
                enterMessage
            ));
            form.addElement(new ElementInput(
                plugin.getLanguageManager().get("gui.basicSettings.labels.leaveTitle"),
                plugin.getLanguageManager().get("gui.basicSettings.labels.leaveTitlePlaceholder"),
                leaveTitle
            ));
            form.addElement(new ElementInput(
                plugin.getLanguageManager().get("gui.basicSettings.labels.leaveMessage"),
                plugin.getLanguageManager().get("gui.basicSettings.labels.leavePlaceholder"),
                leaveMessage
            ));
            
            return form;
        } catch (Exception e) {
            plugin.getLogger().error("Error creating basic settings form", e);
            return null;
        }
    }

    @Override
    protected void handleCustomResponse(Player player, FormResponseCustom response) {
        if (response == null) {
            handleCancel(player);
            return;
        }
        
        try {
            Area area = getEditingArea(player);
            if (area == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.error.noAreaSelected"));
                markValidationErrorShown(); // Mark that we've already shown a specific error
                return;
            }

            // Get current values
            String oldName = area.getName();
            int oldPriority = area.getPriority();
            boolean oldShowTitle = area.toDTO().showTitle();
            
            // Get old title values from area or areaTitles.yml if available
            String oldEnterTitle = area.toDTO().enterTitle();
            String oldEnterMessage = area.toDTO().enterMessage();
            String oldLeaveTitle = area.toDTO().leaveTitle();
            String oldLeaveMessage = area.toDTO().leaveMessage();
            
            // Check if we have custom titles configured in areaTitles.yml
            String basePath = "areaTitles." + area.getName();
            if (plugin.getConfigManager().exists(basePath)) {
                oldEnterTitle = plugin.getConfigManager().getSettingString(basePath + ".enter.main", oldEnterTitle);
                oldEnterMessage = plugin.getConfigManager().getSettingString(basePath + ".enter.subtitle", oldEnterMessage);
                oldLeaveTitle = plugin.getConfigManager().getSettingString(basePath + ".leave.main", oldLeaveTitle);
                oldLeaveMessage = plugin.getConfigManager().getSettingString(basePath + ".leave.subtitle", oldLeaveMessage);
            }

            // Get new values
            String newName = response.getInputResponse(NAME_INDEX);
            String priorityInput = response.getInputResponse(PRIORITY_INDEX);
            boolean newShowTitle = response.getToggleResponse(SHOW_TITLE_INDEX);
            String newEnterTitle = response.getInputResponse(ENTER_TITLE_INDEX);
            String newEnterMessage = response.getInputResponse(ENTER_MESSAGE_INDEX);
            String newLeaveTitle = response.getInputResponse(LEAVE_TITLE_INDEX);
            String newLeaveMessage = response.getInputResponse(LEAVE_MESSAGE_INDEX);
            
            // Validate name (skip validation if name hasn't changed)
            if (!oldName.equals(newName)) {
                // Check if name is empty first
                if (newName == null || newName.trim().isEmpty()) {
                    player.sendMessage(plugin.getLanguageManager().get("validation.form.area.name.required"));
                    plugin.getGuiManager().openFormById(player, FormIds.BASIC_SETTINGS, area);
                    markValidationErrorShown(); // Mark that we've already shown a specific error
                    return;
                }
                
                // Direct validation with specific error message, similar to CreateAreaHandler
                try {
                    ValidationUtils.validateAreaName(newName);
                    
                    // Check if area already exists (only if name format is valid)
                    if (!oldName.equals(newName) && plugin.getArea(newName) != null) {
                        player.sendMessage(plugin.getLanguageManager().get("validation.form.area.name.exists", 
                            Map.of("name", newName)));
                        plugin.getGuiManager().openFormById(player, FormIds.BASIC_SETTINGS, area);
                        markValidationErrorShown(); // Mark that we've already shown a specific error
                        return;
                    }
                } catch (IllegalArgumentException e) {
                    player.sendMessage(plugin.getLanguageManager().get("validation.area.name.format") + ": " + e.getMessage());
                    plugin.getGuiManager().openFormById(player, FormIds.BASIC_SETTINGS, area);
                    markValidationErrorShown(); // Mark that we've already shown a specific error
                    return;
                }
            }
            
            // Validate priority
            int newPriority;
            try {
                newPriority = Integer.parseInt(priorityInput);
                if (!areaValidationUtils.validatePriority(newPriority, player, FormIds.BASIC_SETTINGS)) {
                    markValidationErrorShown(); // Mark that we've already shown a specific error
                    return;
                }
            } catch (NumberFormatException e) {
                player.sendMessage(plugin.getLanguageManager().get("validation.form.area.priority.notNumber"));
                plugin.getGuiManager().openFormById(player, FormIds.BASIC_SETTINGS, area);
                markValidationErrorShown(); // Mark that we've already shown a specific error
                return;
            }
            
            // Validate messages
            if (!areaValidationUtils.validateMessages(newEnterMessage, newLeaveMessage, player, FormIds.BASIC_SETTINGS)) {
                markValidationErrorShown(); // Mark that we've already shown a specific error
                return;
            }
            
            // Validate titles
            if (!areaValidationUtils.validateTitles(newEnterTitle, newLeaveTitle, player, FormIds.BASIC_SETTINGS)) {
                markValidationErrorShown(); // Mark that we've already shown a specific error
                return;
            }

            try {
                // Start a transaction-like block with direct modification
                if (plugin.isDebugMode()) {
                    plugin.debug("Starting area update for " + oldName);
                }

                // Count changed fields for the message
                int changedFields = 0;

                // Create a builder for updating the area instead of direct modifications
                AreaDTO currentDTO = area.toDTO();
                
                // Check what needs to be updated
                boolean updatePriority = oldPriority != newPriority;
                boolean updateShowTitle = oldShowTitle != newShowTitle;
                boolean updateEnterTitle = !oldEnterTitle.equals(newEnterTitle);
                boolean updateEnterMsg = !oldEnterMessage.equals(newEnterMessage);
                boolean updateLeaveTitle = !oldLeaveTitle.equals(newLeaveTitle);
                boolean updateLeaveMsg = !oldLeaveMessage.equals(newLeaveMessage);
                boolean updateName = !oldName.equals(newName);
                
                // Track changed fields for message
                if (updatePriority) changedFields++;
                if (updateShowTitle) changedFields++;
                if (updateEnterTitle) changedFields++;
                if (updateEnterMsg) changedFields++;
                if (updateLeaveTitle) changedFields++;
                if (updateLeaveMsg) changedFields++;
                if (updateName) changedFields++;
                
                // If we need to update anything other than the name
                if (updatePriority || updateShowTitle || updateEnterTitle || updateEnterMsg || updateLeaveTitle || updateLeaveMsg) {
                    // Create updated area using builder
                    Area updatedArea = AreaBuilder.fromDTO(currentDTO)
                        .priority(updatePriority ? newPriority : currentDTO.priority())
                        .showTitle(updateShowTitle ? newShowTitle : currentDTO.showTitle())
                        .enterTitle(updateEnterTitle ? newEnterTitle : currentDTO.enterTitle())
                        .enterMessage(updateEnterMsg ? newEnterMessage : currentDTO.enterMessage())
                        .leaveTitle(updateLeaveTitle ? newLeaveTitle : currentDTO.leaveTitle())
                        .leaveMessage(updateLeaveMsg ? newLeaveMessage : currentDTO.leaveMessage())
                        .build();
                        
                    // Update the area in the system
                    plugin.getAreaManager().updateArea(updatedArea);
                    
                    // Update our reference if we're not changing the name
                    if (!updateName) {
                        area = updatedArea;
                    }
                }

                // Handle title configuration updates
                if (updateShowTitle || updateEnterTitle || updateEnterMsg || updateLeaveTitle || updateLeaveMsg || updateName) {
                    // If area name changed, remove old title entry
                    if (updateName) {
                        plugin.getConfigManager().removeAreaTitleConfig(oldName);
                    }
                    
                    // Configure new title settings if enabled
                    if (newShowTitle) {
                        // Update title settings in areaTitles.yml
                        plugin.getConfigManager().setAreaTitleText(newName, "enter", "main", newEnterTitle);
                        plugin.getConfigManager().setAreaTitleText(newName, "enter", "subtitle", newEnterMessage);
                        plugin.getConfigManager().setAreaTitleText(newName, "leave", "main", newLeaveTitle);
                        plugin.getConfigManager().setAreaTitleText(newName, "leave", "subtitle", newLeaveMessage);
                    } else {
                        // Remove title configuration if title display is disabled
                        plugin.getConfigManager().removeAreaTitleConfig(newName);
                    }
                }

                // Handle name change (most complex change) - must be done last
                Area updatedArea = area;
                if (updateName) {
                    // For name changes, we need to create a new area and delete the old one
                    
                    // Check if this is a global area and preserve that status
                    boolean isGlobalArea = currentDTO.bounds().isGlobal() || area.isGlobal();
                    
                    // Create builder from existing DTO
                    AreaBuilder builder = AreaBuilder.fromDTO(currentDTO)
                        .name(newName)
                        .priority(updatePriority ? newPriority : currentDTO.priority())
                        .showTitle(updateShowTitle ? newShowTitle : currentDTO.showTitle())
                        .enterTitle(updateEnterTitle ? newEnterTitle : currentDTO.enterTitle())
                        .enterMessage(updateEnterMsg ? newEnterMessage : currentDTO.enterMessage())
                        .leaveTitle(updateLeaveTitle ? newLeaveTitle : currentDTO.leaveTitle())
                        .leaveMessage(updateLeaveMsg ? newLeaveMessage : currentDTO.leaveMessage());
                    
                    // If this is a global area, ensure we use global coordinates
                    if (isGlobalArea) {
                        builder.global(currentDTO.world());
                        if (plugin.isDebugMode()) {
                            plugin.debug("Preserving global area status when updating: " + oldName + " -> " + newName);
                        }
                    }
                    
                    // Build the updated area
                    updatedArea = builder.build();
                    
                    // Save new area first
                    plugin.getDatabaseManager().saveArea(updatedArea);
                    
                    // Add to memory
                    plugin.getAreaManager().addArea(updatedArea);
                    
                    // Remove old area
                    plugin.getAreaManager().removeArea(area);
                    
                    // Update form tracking with new name
                    plugin.getFormIdMap().put(player.getName() + "_editing",
                        plugin.getFormIdMap().get(player.getName() + "_editing").withFormId(newName));

                    // Invalidate permission caches for both names
                    plugin.getPermissionOverrideManager().getPermissionChecker().invalidateCache(oldName);
                    plugin.getPermissionOverrideManager().getPermissionChecker().invalidateCache(newName);
                }
                
                // Create message placeholders
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("area", newName);
                placeholders.put("count", String.valueOf(changedFields));
                if (updateName) {
                    placeholders.put("oldName", oldName);
                }

                // Send success message only if changes were made
                if (changedFields > 0) {
                    player.sendMessage(plugin.getLanguageManager().get("success.area.update.settings", placeholders));
                } else {
                    player.sendMessage(plugin.getLanguageManager().get("messages.area.noChanges", 
                        Map.of("area", area.getName())));
                }

                // Return to edit area form
                plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, updatedArea);

            } catch (Exception e) {
                // Skip handling if it's a validation error already handled
                if (e instanceof RuntimeException && e.getMessage() != null && e.getMessage().contains("_validation_error_already_shown")) {
                    // Just rethrow to let the parent handler deal with it
                    throw (RuntimeException) e;
                }
                
                plugin.getLogger().error("Error updating area settings", e);
                player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
                
                // Reopen the form with current values
                plugin.getGuiManager().openFormById(player, FormIds.BASIC_SETTINGS, area);
                markValidationErrorShown(); // Mark that we've already shown a specific error
            }
        } catch (Exception e) {
            // Skip handling if it's a validation error already handled
            if (e instanceof RuntimeException && e.getMessage() != null && e.getMessage().contains("_validation_error_already_shown")) {
                // Just rethrow to let the parent handler deal with it
                throw (RuntimeException) e;
            }
            
            plugin.getLogger().error("Error handling basic settings form", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
            
            // Try to reopen the form with the current area
            Area currentArea = getEditingArea(player);
            if (currentArea != null) {
                plugin.getGuiManager().openFormById(player, FormIds.BASIC_SETTINGS, currentArea);
            }
        }
    }

    @Override
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        // Not used for custom form
        plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, getEditingArea(player));
    }

    private Area getEditingArea(Player player) {
        var areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
        if (areaData == null) {
            return null;
        }
        return plugin.getArea(areaData.getFormId());
    }
}
