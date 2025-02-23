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
        if (area == null) return null;

        FormWindowCustom form = new FormWindowCustom("Basic Settings: " + area.getName());
        
        AreaDTO dto = area.toDTO();
        
        // Header
        form.addElement(new ElementLabel("§2General Settings"));
        
        // Basic properties
        form.addElement(new ElementInput("Area Name", "Enter area name", dto.name()));
        form.addElement(new ElementInput("Priority", "Enter priority (0-100)", 
            String.valueOf(dto.priority())));
            
        // Display settings
        form.addElement(new ElementLabel("\n§2Display Settings"));
        form.addElement(new ElementToggle("Show Area Title", dto.showTitle()));
        form.addElement(new ElementInput("Enter Message", "Message shown when entering", 
            dto.enterMessage() != null ? dto.enterMessage() : ""));
        form.addElement(new ElementInput("Leave Message", "Message shown when leaving", 
            dto.leaveMessage() != null ? dto.leaveMessage() : ""));
            
        return form;
    }

    @Override
    protected void handleCustomResponse(Player player, FormResponseCustom response) {
        try {
            Area area = getEditingArea(player);
            if (area == null) return;

            // Get current DTO
            AreaDTO currentDTO = area.toDTO();

            // Validate inputs first 
            String name = response.getInputResponse(1);
            ValidationResult nameResult = FormValidator.validateAreaName(name);
            if (!nameResult.isValid()) {
                player.sendMessage(nameResult.getMessage());
                // Set form tracking data before reopening basic settings
                plugin.getFormIdMap().put(player.getName(),
                    new FormTrackingData(FormIds.BASIC_SETTINGS, System.currentTimeMillis()));
                plugin.getGuiManager().openBasicSettings(player, area);
                return;
            }

            int priority;
            try {
                priority = Integer.parseInt(response.getInputResponse(2));
                if (priority < 0 || priority > 100) {
                    throw new IllegalArgumentException("Priority must be between 0-100");
                }
            } catch (Exception e) {
                player.sendMessage(plugin.getLanguageManager().get("messages.error.invalidNumber")); 
                // Set form tracking data before reopening basic settings
                plugin.getFormIdMap().put(player.getName(),
                    new FormTrackingData(FormIds.BASIC_SETTINGS, System.currentTimeMillis()));
                plugin.getGuiManager().openBasicSettings(player, area);
                return;
            }

            // Build updated area
            Area updatedArea = AreaBuilder.fromDTO(currentDTO)
                .name(name)
                .priority(priority) 
                .showTitle(response.getToggleResponse(4))
                .enterMessage(response.getInputResponse(5))
                .leaveMessage(response.getInputResponse(6))
                .build();

            // Save changes
            plugin.updateArea(updatedArea);
            player.sendMessage(plugin.getLanguageManager().get("messages.area.updated", 
                Map.of("area", area.getName())));

            // Important: Update tracking data with new area name if it changed
            if (!name.equals(area.getName())) {
                plugin.getFormIdMap().put(player.getName() + "_editing",
                    new FormTrackingData(name, System.currentTimeMillis()));
            }

            // Set form tracking data before opening edit area form
            plugin.getFormIdMap().put(player.getName(),
                new FormTrackingData(FormIds.EDIT_AREA, System.currentTimeMillis()));
            // Return to area settings
            plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, updatedArea);

        } catch (Exception e) {
            plugin.getLogger().error("Error handling basic settings", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.error.saveChanges"));
            // Set form tracking data before opening main menu
            plugin.getFormIdMap().put(player.getName(),
                new FormTrackingData(FormIds.MAIN_MENU, System.currentTimeMillis()));
            plugin.getGuiManager().openMainMenu(player);
        }
    }

    @Override
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        throw new UnsupportedOperationException("Basic settings only uses custom form");
    }

    private Area getEditingArea(Player player) {
        var areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
        if (areaData == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.error.noAreaSelected"));
            return null;
        }
        return plugin.getArea(areaData.getFormId());
    }

    @Override
    public FormWindow createForm(Player player) {
        // Try to get area being edited from form tracking data
        var areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
        if (areaData == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.error.noAreaSelected"));
            return null;
        }
        
        Area area = plugin.getArea(areaData.getFormId());
        if (area == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.error.areaNotFound"));
            return null;
        }
        
        return createForm(player, area);
    }
}
