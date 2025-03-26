package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.constants.FormIds;
import adminarea.data.FormTrackingData;
import cn.nukkit.Player;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowSimple;

import java.util.List;
import java.util.Map;

public class DeleteAreaHandler extends BaseFormHandler {
    public DeleteAreaHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
        validateFormId();
    }

    @Override
    public String getFormId() {
        return FormIds.DELETE_LIST;
    }

    @Override
    public FormWindow createForm(Player player) {
        try {
            FormWindowSimple form = new FormWindowSimple(
                plugin.getLanguageManager().get("gui.deleteArea.title"),
                plugin.getLanguageManager().get("gui.deleteArea.content")
            );

            // Get the current areas and ensure they're unique to prevent duplicates
            List<Area> areas = plugin.getAreas();
            
            // As an additional safety measure, make sure there are no duplicates in the list
            if (plugin.isDebugMode()) {
                plugin.debug("Safely processing area list for deletion menu");
            }
            
            // Deduplicate areas by name
            java.util.Set<String> areaNames = new java.util.HashSet<>();
            java.util.List<Area> uniqueAreas = new java.util.ArrayList<>();
            
            for (Area area : areas) {
                if (!areaNames.contains(area.getName().toLowerCase())) {
                    areaNames.add(area.getName().toLowerCase());
                    uniqueAreas.add(area);
                } else if (plugin.isDebugMode()) {
                    plugin.debug("Found duplicate area in delete list: " + area.getName() + " - skipping it");
                }
            }
            
            // Use the deduplicated list
            areas = uniqueAreas;
            
            if (areas.isEmpty()) {
                player.sendMessage(plugin.getLanguageManager().get("messages.area.list.empty"));
                // Clean up form tracking data when there are no areas
                cleanup(player);
                // Set form tracking data for main menu
                plugin.getFormIdMap().put(player.getName(), 
                    new FormTrackingData(FormIds.MAIN_MENU, System.currentTimeMillis()));
                // Reopen main menu
                plugin.getGuiManager().openMainMenu(player);
                return null;
            }

            // Add buttons for each area, with special formatting for global areas
            for (Area area : areas) {
                String buttonText = area.getName();
                String worldName = area.getWorld().length() > 20 ? area.getWorld().substring(0, 17) + "..." : area.getWorld();
                if (area.toDTO().bounds().isGlobal()) {
                    buttonText += "\n§3(Global - " + worldName + ")";
                } else {
                    buttonText += "\n§8(World - " + worldName + ")";
                }
                form.addButton(new ElementButton(buttonText));
            }

            return form;
        } catch (Exception e) {
            plugin.getLogger().error("Error creating delete area form", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
            cleanup(player);
            return null;
        }
    }

    @Override
    protected void handleCustomResponse(Player player, FormResponseCustom response) {
        // Not used for simple form
    }

    @Override
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        if (response == null) {
            handleCancel(player);
            return;
        }

        try {
            int buttonId = response.getClickedButtonId();
            List<Area> areas = plugin.getAreas();
            
            if (buttonId < 0 || buttonId >= areas.size()) {
                player.sendMessage(plugin.getLanguageManager().get("validation.form.error.generic"));
                return;
            }

            Area area = areas.get(buttonId);
            if (area == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound"));
                return;
            }

            // Store area being deleted and show confirmation form
            plugin.getFormIdMap().put(player.getName() + "_editing", 
                new FormTrackingData(area.getName(), System.currentTimeMillis()));
            plugin.getGuiManager().openFormById(player, FormIds.DELETE_CONFIRM, area);

        } catch (Exception e) {
            plugin.getLogger().error("Error handling delete area response", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
            handleCancel(player);
        }
    }

    @Override
    public void handleCancel(Player player) {
        // Clean up data without opening the main menu
        cleanup(player);
    }

    private void processAreaDeletion(Player player, Area area) {
        try {
            String areaName = area.getName();
            String worldName = area.getWorld();
            
            // Check deletion permission
            if (!player.hasPermission("adminarea.area.delete")) {
                player.sendMessage(plugin.getLanguageManager().get("messages.permissions.noPermission"));
                return;
            }
            
            // Delete area from manager
            plugin.getAreaManager().removeArea(area);
            
            // Remove area title configuration from config
            plugin.getConfigManager().remove("areaTitles." + areaName);
            plugin.getConfigManager().save();
            
            // Invalidate permission cache for this area
            plugin.getPermissionOverrideManager().getPermissionChecker().invalidateCache(areaName);
            
            // Explicitly delete all permissions for this area from the permission database
            try {
                plugin.getPermissionOverrideManager().deleteAreaPermissions(areaName);
                if (plugin.isDebugMode()) {
                    plugin.debug("Explicitly deleted all permissions for area: " + areaName);
                }
            } catch (Exception e) {
                plugin.getLogger().error("Failed to delete permissions for area: " + areaName, e);
            }
            
            // Clean up form tracking data for this area
            plugin.getFormIdMap().remove(player.getName() + "_editing");
            
            // Show success message
            Map<String, String> placeholders = Map.of(
                "area", areaName,
                "world", worldName
            );
            player.sendMessage(plugin.getLanguageManager().get("success.area.delete.single", placeholders));
            
            // Return to main menu
            plugin.getFormIdMap().put(player.getName(),
                new FormTrackingData(FormIds.MAIN_MENU, System.currentTimeMillis()));
            plugin.getGuiManager().openMainMenu(player);
            
            if (plugin.isDebugMode()) {
                plugin.debug("Deleted area: " + areaName + " and removed title configuration");
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error deleting area", e);
            player.sendMessage(plugin.getLanguageManager().get("validation.form.error.generic"));
        }
    }
}
