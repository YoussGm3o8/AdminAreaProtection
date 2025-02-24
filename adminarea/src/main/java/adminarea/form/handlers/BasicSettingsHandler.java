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

    public BasicSettingsHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
        validateFormId();
    }

    @Override
    public String getFormId() {
        return FormIds.BASIC_SETTINGS;
    }

    @Override
    public FormWindow createForm(Player player, Area area) {
        try {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("area", area.getName());
            placeholders.put("player", player.getName());

            FormWindowCustom form = new FormWindowCustom(
                plugin.getLanguageManager().get("gui.basicSettings.title", placeholders)
            );

            // General Settings Section
            form.addElement(new ElementLabel(plugin.getLanguageManager().get("gui.basicSettings.sections.general")));
            
            // Area Name
            form.addElement(new ElementInput(
                plugin.getLanguageManager().get("gui.basicSettings.labels.name"),
                plugin.getLanguageManager().get("gui.basicSettings.labels.namePlaceholder"),
                area.getName()
            ));

            // Priority
            form.addElement(new ElementInput(
                plugin.getLanguageManager().get("gui.basicSettings.labels.priority"),
                plugin.getLanguageManager().get("gui.basicSettings.labels.priorityPlaceholder"),
                String.valueOf(area.getPriority())
            ));

            // Show Title Toggle
            form.addElement(new ElementToggle(
                plugin.getLanguageManager().get("gui.basicSettings.labels.showTitle"),
                area.toDTO().showTitle()
            ));

            // Display Settings Section
            form.addElement(new ElementLabel(plugin.getLanguageManager().get("gui.basicSettings.sections.display")));

            // Enter Message
            form.addElement(new ElementInput(
                plugin.getLanguageManager().get("gui.basicSettings.labels.enterMessage"),
                plugin.getLanguageManager().get("gui.basicSettings.labels.enterPlaceholder"),
                area.toDTO().enterMessage()
            ));

            // Leave Message
            form.addElement(new ElementInput(
                plugin.getLanguageManager().get("gui.basicSettings.labels.leaveMessage"),
                plugin.getLanguageManager().get("gui.basicSettings.labels.leavePlaceholder"),
                area.toDTO().leaveMessage()
            ));

            return form;

        } catch (Exception e) {
            plugin.getLogger().error("Error creating basic settings form", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
            return null;
        }
    }

    @Override
    protected void handleCustomResponse(Player player, FormResponseCustom response) {
        try {
            // Get area being edited with proper null checks
            var trackingData = plugin.getFormIdMap().get(player.getName() + "_editing");
            if (trackingData == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.form.error.noAreaSelected"));
                plugin.getGuiManager().openMainMenu(player);
                return;
            }

            Area area = plugin.getArea(trackingData.getFormId());
            if (area == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.area.notFound", 
                    Map.of("area", trackingData.getFormId())));
                plugin.getGuiManager().openMainMenu(player);
                return;
            }

            // Get form responses with null checks
            // Skip index 0 (General Settings Label)
            String newName = response.getInputResponse(1);        // Area Name Input
            String priorityStr = response.getInputResponse(2);    // Priority Input
            
            // Handle show title toggle safely
            boolean showTitle = area.toDTO().showTitle();  // Default to current value
            try {
                Object toggleResponse = response.getResponse(3);  // Get raw response for toggle
                if (toggleResponse instanceof Boolean) {
                    showTitle = (Boolean) toggleResponse;
                } else {
                    plugin.getLogger().debug("Toggle response was not boolean type: " + (toggleResponse != null ? toggleResponse.getClass().getName() : "null"));
                }
            } catch (Exception e) {
                plugin.getLogger().debug("Error reading toggle response, using current value: " + e.getMessage());
            }
            
            // Skip index 4 (Display Settings Label)
            String enterMessage = response.getInputResponse(5);   // Enter Message Input
            String leaveMessage = response.getInputResponse(6);   // Leave Message Input

            // Validate inputs
            if (newName == null || priorityStr == null) {
                player.sendMessage(plugin.getLanguageManager().get("validation.form.error.generic"));
                plugin.getGuiManager().openFormById(player, getFormId(), area);
                return;
            }

            // Validate name is not empty
            if (newName.trim().isEmpty()) {
                player.sendMessage(plugin.getLanguageManager().get("validation.area.name.empty"));
                plugin.getGuiManager().openFormById(player, getFormId(), area);
                return;
            }

            int priority;
            try {
                priority = Integer.parseInt(priorityStr);
            } catch (NumberFormatException e) {
                player.sendMessage(plugin.getLanguageManager().get("validation.area.priority.notNumber"));
                plugin.getGuiManager().openFormById(player, getFormId(), area);
                return;
            }

            // Build updated area
            AreaDTO currentDTO = area.toDTO();
            
            // Ensure we have valid settings
            JSONObject settings = currentDTO.settings();
            if (settings == null) {
                settings = new JSONObject();
            }
            
            Area updatedArea = AreaBuilder.fromDTO(currentDTO)
                .name(newName)
                .priority(priority)
                .showTitle(showTitle)
                .enterMessage(enterMessage != null ? enterMessage : "")
                .leaveMessage(leaveMessage != null ? leaveMessage : "")
                .settings(settings)
                .build();

            // Save changes
            plugin.updateArea(updatedArea);
            
            // Send success message
            player.sendMessage(plugin.getLanguageManager().get("success.settings.save.basic", 
                Map.of("area", updatedArea.getName())));

            // Return to edit menu
            plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, updatedArea);

        } catch (Exception e) {
            plugin.getLogger().error("Error handling basic settings form", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
            plugin.getGuiManager().openMainMenu(player);
        }
    }

    @Override
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        handleCancel(player);
    }

    @Override
    public FormWindow createForm(Player player) {
        FormTrackingData data = plugin.getFormIdMap().get(player.getName() + "_editing");
        if (data == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.noAreaSelected"));
            return null;
        }

        Area area = plugin.getArea(data.getFormId());
        if (area == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.noAreaSelected"));
            return null;
        }

        return createForm(player, area);
    }
}
