package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.constants.AdminAreaConstants;
import adminarea.data.FormTrackingData;
import adminarea.permissions.PermissionToggle;
import cn.nukkit.Player;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple; 
import cn.nukkit.form.window.FormWindow;

import java.util.Map;
import java.util.HashMap;
import java.util.List;

public class ProtectionSettingsHandler extends BaseFormHandler {

    public ProtectionSettingsHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
    }

    @Override
    public String getFormId() {
        return AdminAreaConstants.FORM_PROTECTION_SETTINGS;
    }

    @Override 
    public FormWindow createForm(Player player) {
        FormTrackingData areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
        if (areaData == null) return null;

        Area area = plugin.getArea(areaData.getFormId());
        if (area == null) return null;

        return plugin.getFormFactory().createProtectionSettingsForm(area);
    }

    @Override
    protected void handleCustomResponse(Player player, FormResponseCustom response) {
        FormTrackingData areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
        Area area = plugin.getArea(areaData != null ? areaData.getFormId() : null);

        if (area == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound"));
            return;
        }

        processProtectionSettings(player, response, area);
    }

    private void processProtectionSettings(Player player, FormResponseCustom response, Area area) {
        try {
            Map<PermissionToggle.Category, List<PermissionToggle>> togglesByCategory = 
                PermissionToggle.getTogglesByCategory();

            // Store current settings for event
            Map<String, Boolean> oldSettings = new HashMap<>();
            for (String key : area.getSettings().keySet()) {
                oldSettings.put(key, area.getSettings().getBoolean(key));
            }
            
            int toggleIndex = 0;
            // Process each category in order
            for (PermissionToggle.Category category : PermissionToggle.Category.values()) {
                Map<String, Boolean> newSettings = new HashMap<>();

                if (plugin.isDebugMode()) {
                    plugin.debug(String.format("Processing category %s for area %s", 
                        category.name(), area.getName()));
                }

                List<PermissionToggle> categoryToggles = togglesByCategory.get(category);
                if (categoryToggles == null || categoryToggles.isEmpty()) {
                    if (plugin.isDebugMode()) {
                        plugin.debug(String.format("No toggles found for category %s", category.name()));
                    }
                    continue;
                }

                // Process each toggle in the category
                for (PermissionToggle toggle : categoryToggles) {
                    try {
                        boolean value = response.getToggleResponse(toggleIndex++);
                        String permission = toggle.getPermissionNode();
                        
                        if (plugin.isDebugMode()) {
                            plugin.debug(String.format("Processing toggle %s = %b", permission, value));
                        }

                        // Validate toggle state before applying
                        plugin.getValidationUtils().validateToggleState(permission, value, area.getSettings());
                        newSettings.put(permission, value);
                        
                    } catch (IndexOutOfBoundsException e) {
                        plugin.getLogger().error(String.format("Invalid toggle index %d for category %s", 
                            toggleIndex - 1, category.name()));
                        throw new IllegalStateException("Form response missing toggle data", e);
                    } catch (IllegalArgumentException e) {
                        player.sendMessage(plugin.getLanguageManager().getErrorMessage(e.getMessage()));
                        return;
                    }
                }

                // Update category batch and fire event
                Map<String, Boolean> oldCategorySettings = new HashMap<>();
                for (PermissionToggle toggle : categoryToggles) {
                    String node = toggle.getPermissionNode();
                    oldCategorySettings.put(node, oldSettings.getOrDefault(node, false));
                }
                
                // Fire update event for this category
                plugin.fireAreaPermissionUpdateEvent(area, category, oldCategorySettings, newSettings);
                
                // Update permissions for this category
                area.updateCategoryPermissions(category, newSettings);
            }

            // Save changes
            plugin.saveArea(area);

            player.sendMessage(plugin.getLanguageManager().get("messages.settingsUpdated"));

            // Return to area settings menu
            plugin.getGuiManager().openAreaSettings(player, area);

        } catch (Exception e) {
            plugin.getLogger().error("Error processing protection settings", e);
            if (plugin.isDebugMode()) {
                e.printStackTrace();
            }
            player.sendMessage(plugin.getLanguageManager().get("messages.error.saveChanges")); 
        }
    }

    @Override
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        // Protection settings only use custom forms
        throw new UnsupportedOperationException("Protection settings only supports custom forms");
    }
}
