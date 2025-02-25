package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaBuilder;
import adminarea.area.AreaDTO;
import adminarea.constants.FormIds;
import adminarea.data.FormTrackingData;
import adminarea.form.validation.FormValidator;
import adminarea.form.validation.ValidationResult;
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

    public BasicSettingsHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
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
            int newPriority = Integer.parseInt(response.getInputResponse(PRIORITY_INDEX));
            boolean newShowTitle = response.getToggleResponse(SHOW_TITLE_INDEX);
            String newEnterMessage = response.getInputResponse(ENTER_MESSAGE_INDEX);
            String newLeaveMessage = response.getInputResponse(LEAVE_MESSAGE_INDEX);

            // Build updated area
            Area updatedArea = AreaBuilder.fromDTO(area.toDTO())
                .name(newName)
                .priority(newPriority)
                .showTitle(newShowTitle)
                .enterMessage(newEnterMessage)
                .leaveMessage(newLeaveMessage)
                .build();

            // Check if title config needs updating
            if (!oldName.equals(newName) || !oldEnterMessage.equals(newEnterMessage) || 
                !oldLeaveMessage.equals(newLeaveMessage) || oldShowTitle != newShowTitle) {
                // If area name changed, remove old title entry and create new one
                if (!oldName.equals(newName)) {
                    plugin.getConfigManager().remove("areaTitles." + oldName);
                }
                
                if (newShowTitle) {
                    updateAreaTitles(newName, newEnterMessage, newLeaveMessage);
                }
            }

            // Update area in plugin
            plugin.updateArea(updatedArea);
            
            // Show success message
            int changedFields = 0;
            if (!oldName.equals(newName)) changedFields++;
            if (oldPriority != newPriority) changedFields++;
            if (oldShowTitle != newShowTitle) changedFields++;
            if (!oldEnterMessage.equals(newEnterMessage)) changedFields++;
            if (!oldLeaveMessage.equals(newLeaveMessage)) changedFields++;
            
            player.sendMessage(plugin.getLanguageManager().get("messages.area.updated", 
                Map.of(
                    "area", newName,
                    "count", String.valueOf(changedFields)
                )));

            // If name changed, update editing reference
            if (!oldName.equals(newName)) {
                plugin.getFormIdMap().put(player.getName() + "_editing", 
                    plugin.getFormIdMap().get(player.getName() + "_editing").withFormId(newName));
            }

            // Return to edit area form
            plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, updatedArea);

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

    /**
     * Updates area title information in config.yml
     * 
     * @param areaName The area name
     * @param enterMessage The enter message
     * @param leaveMessage The leave message
     */
    private void updateAreaTitles(String areaName, String enterMessage, String leaveMessage) {
        // Create base path for area titles
        String basePath = "areaTitles." + areaName;
        
        // Set title values
        plugin.getConfigManager().set(basePath + ".enter.main", "§6Welcome to " + areaName);
        plugin.getConfigManager().set(basePath + ".enter.subtitle", enterMessage);
        plugin.getConfigManager().set(basePath + ".enter.fadeIn", 20);
        plugin.getConfigManager().set(basePath + ".enter.stay", 40);
        plugin.getConfigManager().set(basePath + ".enter.fadeOut", 20);
        
        plugin.getConfigManager().set(basePath + ".leave.main", "§6Leaving " + areaName);
        plugin.getConfigManager().set(basePath + ".leave.subtitle", leaveMessage);
        plugin.getConfigManager().set(basePath + ".leave.fadeIn", 20);
        plugin.getConfigManager().set(basePath + ".leave.stay", 40);
        plugin.getConfigManager().set(basePath + ".leave.fadeOut", 20);
        
        // Save the config
        plugin.getConfigManager().save();
        
        if (plugin.isDebugMode()) {
            plugin.debug("Updated title config for area " + areaName);
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
