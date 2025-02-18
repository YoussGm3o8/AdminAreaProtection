package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.constants.AdminAreaConstants;
import adminarea.data.FormTrackingData;
import adminarea.form.IFormHandler;
import adminarea.permissions.PermissionToggle;
import cn.nukkit.Player;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;

import java.util.List;
import java.util.Map;

public class CategorySettingsHandler extends BaseFormHandler {
    private final String formId;

    public CategorySettingsHandler(AdminAreaProtectionPlugin plugin, String formId) {
        super(plugin);
        this.formId = formId;
    }

    @Override
    public String getFormId() {
        return formId;
    }

    @Override
    public FormWindow createForm(Player player) {
        FormTrackingData formData = plugin.getFormIdMap().get(player.getName() + "_editing");
        String areaName = formData != null ? formData.getFormId() : null;
        Area area = plugin.getArea(areaName);
        
        if (area == null) {
            return null;
        }

        PermissionToggle.Category category = getCategoryFromFormId(formId);
        return plugin.getGuiManager().createCategoryForm(area, category);
    }

    @Override
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        // Category settings only use custom forms
        throw new UnsupportedOperationException("Category settings only support custom forms");
    }

    @Override
    protected void handleCustomResponse(Player player, FormResponseCustom response) {
        FormTrackingData formData = plugin.getFormIdMap().get(player.getName() + "_editing");
        Area area = plugin.getArea(formData != null ? formData.getFormId() : null);
        
        if (area == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound"));
            return;
        }

        processCategorySettings(player, response, area);
    }


    private void processCategorySettings(Player player, FormResponseCustom customResponse, Area area) {
        PermissionToggle.Category category = getCategoryFromFormId(formId);
        List<PermissionToggle> categoryToggles = PermissionToggle.getTogglesByCategory().get(category);

        if (categoryToggles != null) {
            // Process toggle settings
            int toggleIndex = 1;
            for (PermissionToggle toggle : categoryToggles) {
                boolean value = customResponse.getToggleResponse(toggleIndex++);
                area.getSettings().put(toggle.getPermissionNode(), value);
            }
            
            // Save changes
            try {
                plugin.getDatabaseManager().saveArea(area);
                player.sendMessage(plugin.getLanguageManager().get("messages.settingsUpdated"));
                
                // Return to area edit menu
                plugin.getGuiManager().openAreaSettings(player, area);
            } catch (Exception e) {
                plugin.getLogger().error("Failed to save area changes", e);
                player.sendMessage(plugin.getLanguageManager().get("messages.error.saveChanges"));
            }
        }
    }

    private PermissionToggle.Category getCategoryFromFormId(String formId) {
        return switch (formId) {
            case AdminAreaConstants.FORM_BUILDING_SETTINGS -> PermissionToggle.Category.BUILDING;
            case AdminAreaConstants.FORM_ENVIRONMENT_SETTINGS -> PermissionToggle.Category.ENVIRONMENT;
            case AdminAreaConstants.FORM_ENTITY_SETTINGS -> PermissionToggle.Category.ENTITY;
            case AdminAreaConstants.FORM_TECHNICAL_SETTINGS -> PermissionToggle.Category.TECHNICAL;
            case AdminAreaConstants.FORM_SPECIAL_SETTINGS -> PermissionToggle.Category.SPECIAL;
            default -> throw new IllegalStateException("Unknown category form ID: " + formId);
        };
    }
}
