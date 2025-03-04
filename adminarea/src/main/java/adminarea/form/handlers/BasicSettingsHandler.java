package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaBuilder;
import adminarea.area.AreaDTO;
import adminarea.constants.FormIds;
import adminarea.data.FormTrackingData;
import adminarea.form.validation.FormValidator;
import adminarea.form.validation.ValidationResult;
import adminarea.util.AreaValidationUtils;
import cn.nukkit.Player;
import cn.nukkit.form.element.ElementInput;
import cn.nukkit.form.element.ElementLabel;
import cn.nukkit.form.element.ElementToggle;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowCustom;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class BasicSettingsHandler extends BaseFormHandler {
    private static final int NAME_INDEX = 1;
    private static final int PRIORITY_INDEX = 2;
    private static final int SHOW_TITLE_INDEX = 3;
    private static final int ENTER_MESSAGE_INDEX = 5;
    private static final int LEAVE_MESSAGE_INDEX = 6;
    
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
            plugin.getLanguageManager().get("gui.basicSettings.labels.enterMessage"),
            plugin.getLanguageManager().get("gui.basicSettings.labels.enterPlaceholder"),
            area.toDTO().enterMessage()
        ));
        form.addElement(new ElementInput(
            plugin.getLanguageManager().get("gui.basicSettings.labels.leaveMessage"),
            plugin.getLanguageManager().get("gui.basicSettings.labels.leavePlaceholder"),
            area.toDTO().leaveMessage()
        ));
        
        return form;
    }

    @Override
    protected void handleCustomResponse(Player player, FormResponseCustom response) {
        try {
            Area area = getEditingArea(player);
            if (area == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.error.noAreaSelected"));
                return;
            }

            // Get current values
            String oldName = area.getName();
            int oldPriority = area.getPriority();
            boolean oldShowTitle = area.toDTO().showTitle();
            String oldEnterMessage = area.toDTO().enterMessage();
            String oldLeaveMessage = area.toDTO().leaveMessage();

            // Get new values
            String newName = response.getInputResponse(NAME_INDEX);
            String priorityInput = response.getInputResponse(PRIORITY_INDEX);
            boolean newShowTitle = response.getToggleResponse(SHOW_TITLE_INDEX);
            String newEnterMessage = response.getInputResponse(ENTER_MESSAGE_INDEX);
            String newLeaveMessage = response.getInputResponse(LEAVE_MESSAGE_INDEX);
            
            // Validate name (skip validation if name hasn't changed)
            if (!oldName.equals(newName)) {
                // Use centralized validation method
                if (!areaValidationUtils.validateAreaName(newName, player, true, FormIds.BASIC_SETTINGS)) {
                    return;
                }
            }
            
            // Validate priority
            int newPriority;
            try {
                newPriority = Integer.parseInt(priorityInput);
                if (!areaValidationUtils.validatePriority(newPriority, player, FormIds.BASIC_SETTINGS)) {
                    return;
                }
            } catch (NumberFormatException e) {
                player.sendMessage(plugin.getLanguageManager().get("validation.area.priority.notNumber"));
                plugin.getGuiManager().openFormById(player, FormIds.BASIC_SETTINGS, area);
                return;
            }
            
            // Validate messages
            if (!areaValidationUtils.validateMessages(newEnterMessage, newLeaveMessage, player, FormIds.BASIC_SETTINGS)) {
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
                boolean updateEnterMsg = !oldEnterMessage.equals(newEnterMessage);
                boolean updateLeaveMsg = !oldLeaveMessage.equals(newLeaveMessage);
                boolean updateName = !oldName.equals(newName);
                
                // Track changed fields for message
                if (updatePriority) changedFields++;
                if (updateShowTitle) changedFields++;
                if (updateEnterMsg) changedFields++;
                if (updateLeaveMsg) changedFields++;
                if (updateName) changedFields++;
                
                // If we need to update anything other than the name
                if (updatePriority || updateShowTitle || updateEnterMsg || updateLeaveMsg) {
                    // Create updated area using builder
                    Area updatedArea = AreaBuilder.fromDTO(currentDTO)
                        .priority(updatePriority ? newPriority : currentDTO.priority())
                        .showTitle(updateShowTitle ? newShowTitle : currentDTO.showTitle())
                        .enterMessage(updateEnterMsg ? newEnterMessage : currentDTO.enterMessage())
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
                if (updateShowTitle || updateEnterMsg || updateLeaveMsg || updateName) {
                    // If area name changed, remove old title entry
                    if (updateName) {
                        // Code to handle title config for removed name
                    }
                    
                    // Configure new title settings if enabled
                    if (newShowTitle) {
                        // Code to configure title settings
                    }
                }

                // Handle name change (most complex change) - must be done last
                Area updatedArea = area;
                if (updateName) {
                    // For name changes, we need to create a new area and delete the old one
                    AreaDTO updatedDTO = AreaBuilder.fromDTO(currentDTO)
                        .name(newName)
                        .priority(updatePriority ? newPriority : currentDTO.priority())
                        .showTitle(updateShowTitle ? newShowTitle : currentDTO.showTitle())
                        .enterMessage(updateEnterMsg ? newEnterMessage : currentDTO.enterMessage())
                        .leaveMessage(updateLeaveMsg ? newLeaveMessage : currentDTO.leaveMessage())
                        .build().toDTO();
                    
                    // Create new area with the new name
                    updatedArea = AreaBuilder.fromDTO(updatedDTO).build();
                    
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
                    
                    if (plugin.isDebugMode()) {
                        // Debug logging for name change
                    }
                } else if (changedFields > 0) {
                    // Debug logging for updates without name change
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
                    // Fire area modified event with old and new area states (if name changed we have a new area instance)
                    if (updateName) {
                        // Fire event for name change
                    } else {
                        // Fire event for updates without name change
                    }
                    
                    player.sendMessage(plugin.getLanguageManager().get("success.area.update.settings", placeholders));
                } else {
                    player.sendMessage(plugin.getLanguageManager().get("messages.area.noChanges", 
                        Map.of("area", area.getName())));
                }

                // Return to edit area form
                plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, updatedArea);

            } catch (Exception e) {
                plugin.getLogger().error("Error updating area settings", e);
                player.sendMessage(plugin.getLanguageManager().get("validation.form.error.generic"));
                
                if (plugin.isDebugMode()) {
                    // Debug logging for errors
                }
                
                // Reopen the form with current values
                plugin.getGuiManager().openFormById(player, FormIds.BASIC_SETTINGS, area);
            }

        } catch (NumberFormatException e) {
            player.sendMessage(plugin.getLanguageManager().get("messages.error.invalidPriority"));
            plugin.getGuiManager().openFormById(player, FormIds.BASIC_SETTINGS, getEditingArea(player));
        } catch (Exception e) {
            plugin.getLogger().error("Error handling basic settings form", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
            if (plugin.isDebugMode()) {
                e.printStackTrace();
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
