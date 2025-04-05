package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaBuilder;
import adminarea.area.AreaDTO;
import adminarea.constants.FormIds;
import adminarea.form.utils.FormUtils;
import adminarea.util.AreaValidationUtils;
import adminarea.util.ValidationUtils;
import cn.nukkit.Player;
import cn.nukkit.form.element.ElementInput;
import cn.nukkit.form.element.ElementLabel;
import cn.nukkit.form.element.ElementToggle;
import cn.nukkit.form.handler.FormResponseHandler;
import cn.nukkit.form.window.FormWindowCustom;
import java.util.HashMap;
import java.util.Map;

/**
 * Custom form for editing basic settings of an area.
 */
public class BasicSettingsHandler {
    private static final int NAME_INDEX = 1;
    private static final int PRIORITY_INDEX = 2;
    private static final int SHOW_TITLE_INDEX = 3;
    private static final int ENTER_TITLE_INDEX = 5;
    private static final int ENTER_MESSAGE_INDEX = 6;
    private static final int LEAVE_TITLE_INDEX = 7;
    private static final int LEAVE_MESSAGE_INDEX = 8;
    
    private final AdminAreaProtectionPlugin plugin;
    private final AreaValidationUtils areaValidationUtils;

    public BasicSettingsHandler(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        this.areaValidationUtils = new AreaValidationUtils(plugin);
    }

    /**
     * Open the basic settings form for a player and area
     */
    public void open(Player player, Area area) {
        if (area == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.error.noAreaSelected"));
            FormUtils.cleanup(player);
            plugin.getGuiManager().openMainMenu(player);
            return;
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
            
            // Store a reference to the current area
            final Area finalArea = area;
            
            // Add response handler
            form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
                if (form.wasClosed()) {
                    handleCancel(player);
                    return;
                }
                
                try {
                    // Get current values
                    String oldName = finalArea.getName();
                    int oldPriority = finalArea.getPriority();
                    boolean oldShowTitle = finalArea.toDTO().showTitle();
                    
                    // Get old title values from area or areaTitles.yml if available
                    String oldEnterTitle = finalArea.toDTO().enterTitle();
                    String oldEnterMessage = finalArea.toDTO().enterMessage();
                    String oldLeaveTitle = finalArea.toDTO().leaveTitle();
                    String oldLeaveMessage = finalArea.toDTO().leaveMessage();
                    
                    // Check if we have custom titles configured in areaTitles.yml
                    String basePath = "areaTitles." + finalArea.getName();
                    if (plugin.getConfigManager().exists(basePath)) {
                        oldEnterTitle = plugin.getConfigManager().getSettingString(basePath + ".enter.main", oldEnterTitle);
                        oldEnterMessage = plugin.getConfigManager().getSettingString(basePath + ".enter.subtitle", oldEnterMessage);
                        oldLeaveTitle = plugin.getConfigManager().getSettingString(basePath + ".leave.main", oldLeaveTitle);
                        oldLeaveMessage = plugin.getConfigManager().getSettingString(basePath + ".leave.subtitle", oldLeaveMessage);
                    }

                    // Get new values
                    String newName = form.getResponse().getInputResponse(NAME_INDEX);
                    String priorityInput = form.getResponse().getInputResponse(PRIORITY_INDEX);
                    boolean newShowTitle = form.getResponse().getToggleResponse(SHOW_TITLE_INDEX);
                    String newEnterTitle = form.getResponse().getInputResponse(ENTER_TITLE_INDEX);
                    String newEnterMessage = form.getResponse().getInputResponse(ENTER_MESSAGE_INDEX);
                    String newLeaveTitle = form.getResponse().getInputResponse(LEAVE_TITLE_INDEX);
                    String newLeaveMessage = form.getResponse().getInputResponse(LEAVE_MESSAGE_INDEX);
                    
                    // Validate name (skip validation if name hasn't changed)
                    if (!oldName.equals(newName)) {
                        // Check if name is empty first
                        if (newName == null || newName.trim().isEmpty()) {
                            player.sendMessage(plugin.getLanguageManager().get("validation.form.area.name.required"));
                            FormUtils.cleanup(player);
                            open(player, finalArea);
                            FormUtils.markValidationErrorShown();
                            return;
                        }
                        
                        // Direct validation with specific error message
                        try {
                            ValidationUtils.validateAreaName(newName);
                            
                            // Check if area already exists (only if name format is valid)
                            if (!oldName.equals(newName) && plugin.getArea(newName) != null) {
                                player.sendMessage(plugin.getLanguageManager().get("validation.form.area.name.exists", 
                                    Map.of("name", newName)));
                                FormUtils.cleanup(player);
                                open(player, finalArea);
                                FormUtils.markValidationErrorShown();
                                return;
                            }
                        } catch (IllegalArgumentException e) {
                            player.sendMessage(plugin.getLanguageManager().get("validation.area.name.format") + ": " + e.getMessage());
                            FormUtils.cleanup(player);
                            open(player, finalArea);
                            FormUtils.markValidationErrorShown();
                            return;
                        }
                    }
                    
                    // Validate priority
                    int newPriority;
                    try {
                        newPriority = Integer.parseInt(priorityInput);
                        if (!areaValidationUtils.validatePriority(newPriority, player, FormIds.BASIC_SETTINGS)) {
                            FormUtils.cleanup(player);
                            FormUtils.markValidationErrorShown();
                            return;
                        }
                    } catch (NumberFormatException e) {
                        player.sendMessage(plugin.getLanguageManager().get("validation.form.area.priority.notNumber"));
                        FormUtils.cleanup(player);
                        open(player, finalArea);
                        FormUtils.markValidationErrorShown();
                        return;
                    }
                    
                    // Validate message lengths
                    if (!areaValidationUtils.validateMessages(newEnterMessage, newLeaveMessage, player, FormIds.BASIC_SETTINGS)) {
                        FormUtils.cleanup(player);
                        FormUtils.markValidationErrorShown();
                        return;
                    }
                    
                    // Track changes
                    int changedSettings = 0;
                    
                    // Apply changes to area DTO
                    AreaDTO currentDTO = finalArea.toDTO();
                    
                    // Check if any basic settings changed
                    if (!oldName.equals(newName) || oldPriority != newPriority || oldShowTitle != newShowTitle) {
                        if (!oldName.equals(newName)) {
                            changedSettings++;
                            // Handle area renaming - this requires special care
                            handleAreaRename(player, finalArea, newName);
                        }
                        
                        if (oldPriority != newPriority) {
                            // Create a new DTO with the updated priority
                            AreaDTO updatedDTO = new AreaDTO(
                                currentDTO.name(),
                                currentDTO.world(),
                                currentDTO.bounds(),
                                newPriority, // Update priority here
                                currentDTO.showTitle(),
                                currentDTO.settings(),
                                currentDTO.groupPermissions(),
                                currentDTO.inheritedPermissions(),
                                currentDTO.toggleStates(),
                                currentDTO.defaultToggleStates(),
                                currentDTO.inheritedToggleStates(),
                                currentDTO.permissions(),
                                currentDTO.enterMessage(),
                                currentDTO.leaveMessage(),
                                currentDTO.enterTitle(),
                                currentDTO.leaveTitle(),
                                currentDTO.trackPermissions(),
                                currentDTO.playerPermissions(),
                                currentDTO.potionEffects()
                            );
                            // Apply the updated DTO to the area
                            plugin.getAreaManager().recreateArea(updatedDTO);
                            changedSettings++;
                        }
                        
                        if (oldShowTitle != newShowTitle) {
                            currentDTO = new AreaDTO(
                                currentDTO.name(),
                                currentDTO.world(),
                                currentDTO.bounds(),
                                currentDTO.priority(),
                                newShowTitle,
                                currentDTO.settings(),
                                currentDTO.groupPermissions(),
                                currentDTO.inheritedPermissions(),
                                currentDTO.toggleStates(),
                                currentDTO.defaultToggleStates(),
                                currentDTO.inheritedToggleStates(),
                                currentDTO.permissions(),
                                currentDTO.enterMessage(),
                                currentDTO.leaveMessage(),
                                currentDTO.enterTitle(),
                                currentDTO.leaveTitle(),
                                currentDTO.trackPermissions(),
                                currentDTO.playerPermissions(),
                                currentDTO.potionEffects()
                            );
                            changedSettings++;
                        }
                    }
                    
                    // Check if any title settings changed
                    boolean titleChanged = false;
                    if (!oldEnterTitle.equals(newEnterTitle) || !oldEnterMessage.equals(newEnterMessage) ||
                        !oldLeaveTitle.equals(newLeaveTitle) || !oldLeaveMessage.equals(newLeaveMessage)) {
                        
                        titleChanged = true;
                        changedSettings++;
                        
                        // Save titles in config
                        String areaName = finalArea.getName();
                        plugin.getConfigManager().set("areaTitles." + areaName + ".enter.title", newEnterTitle);
                        plugin.getConfigManager().set("areaTitles." + areaName + ".enter.subtitle", newEnterMessage);
                        plugin.getConfigManager().set("areaTitles." + areaName + ".leave.title", newLeaveTitle);
                        plugin.getConfigManager().set("areaTitles." + areaName + ".leave.subtitle", newLeaveMessage);
                        plugin.getConfigManager().save();
                        
                        if (plugin.isDebugMode()) {
                            plugin.debug("Updated title settings for area: " + areaName);
                        }
                    }
                    
                    // Update area in database if any setting changed
                    if (changedSettings > 0) {
                        // Update the area in manager and database
                        plugin.getAreaManager().updateArea(finalArea);
                        plugin.getDatabaseManager().updateArea(finalArea);
                        
                        // Show success message
                        player.sendMessage(plugin.getLanguageManager().get("messages.area.updated", 
                            Map.of("area", finalArea.getName(), "count", String.valueOf(changedSettings))));
                    } else {
                        player.sendMessage(plugin.getLanguageManager().get("messages.area.noChanges", 
                            Map.of("area", finalArea.getName())));
                    }
                    
                    // Return to edit area menu
                    plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, finalArea);
                    
                } catch (Exception e) {
                    plugin.getLogger().error("Error handling basic settings form response", e);
                    player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
                    handleCancel(player);
                }
            }));
            
            // Show the form
            player.showFormWindow(form);
            
        } catch (Exception e) {
            plugin.getLogger().error("Error creating basic settings form", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
            FormUtils.cleanup(player);
        }
    }
    
    /**
     * Open the form using the area stored in form tracking data
     */
    public void open(Player player) {
        Area area = getEditingArea(player);
        if (area == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.error.noAreaSelected"));
            FormUtils.cleanup(player);
            plugin.getGuiManager().openMainMenu(player);
            return;
        }
        
        // Open using the area
        open(player, area);
    }
    
    /**
     * Handle when the form is cancelled
     */
    private void handleCancel(Player player) {
        Area area = getEditingArea(player);
        if (area != null) {
            // Return to edit area menu
            plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, area);
        } else {
            // Return to main menu if no area is being edited
            FormUtils.cleanup(player);
            plugin.getGuiManager().openMainMenu(player);
        }
    }
    
    /**
     * Handle renaming an area (special case)
     */
    private void handleAreaRename(Player player, Area area, String newName) {
        // This would be implemented based on your rename logic
        // The old implementation would be migrated here
    }
    
    /**
     * Get the area that's currently being edited
     */
    private Area getEditingArea(Player player) {
        var areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
        if (areaData == null) return null;
        return plugin.getArea(areaData.getFormId());
    }
}
