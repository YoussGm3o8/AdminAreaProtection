package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.constants.FormIds;
import adminarea.data.FormTrackingData;
import adminarea.form.utils.FormUtils;
import cn.nukkit.Player;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.handler.FormResponseHandler;
import cn.nukkit.form.window.FormWindowSimple;

import java.util.HashMap;
import java.util.Map;

/**
 * Handler for the area editing main menu form.
 */
public class EditAreaHandler {
    
    private final AdminAreaProtectionPlugin plugin;
    
    public EditAreaHandler(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Open the edit area form for a player and specific area
     */
    public void open(Player player, Area area) {
        try {
            if (area == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound"));
                FormUtils.cleanup(player);
                plugin.getGuiManager().openMainMenu(player);
                return;
            }
            
            // Store the area being edited in the form tracking data
            plugin.getFormIdMap().put(player.getName() + "_editing", 
                new FormTrackingData(area.getName(), System.currentTimeMillis()));
            
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("area", area.getName());

            FormWindowSimple form = new FormWindowSimple(
                plugin.getLanguageManager().get("gui.editArea.title", placeholders),
                plugin.getLanguageManager().get("gui.editArea.content")
            );

            // Add all category buttons
            form.addButton(new ElementButton(plugin.getLanguageManager().get("gui.editArea.buttons.basicSettings")));
            form.addButton(new ElementButton(plugin.getLanguageManager().get("gui.editArea.buttons.buildingSettings")));
            form.addButton(new ElementButton(plugin.getLanguageManager().get("gui.editArea.buttons.environmentSettings")));
            form.addButton(new ElementButton(plugin.getLanguageManager().get("gui.editArea.buttons.entitySettings")));
            form.addButton(new ElementButton(plugin.getLanguageManager().get("gui.editArea.buttons.technicalSettings")));
            form.addButton(new ElementButton(plugin.getLanguageManager().get("gui.editArea.buttons.specialSettings")));
            form.addButton(new ElementButton(plugin.getLanguageManager().get("gui.editArea.buttons.itemsDropsSettings")));
            form.addButton(new ElementButton(plugin.getLanguageManager().get("gui.editArea.buttons.potionEffectsSettings")));

            // Add LuckPerms button if enabled
            if (plugin.isLuckPermsEnabled()) {
                form.addButton(new ElementButton(plugin.getLanguageManager().get("gui.editArea.buttons.groupPermissions")));
            }

            // Add player settings button
            form.addButton(new ElementButton(plugin.getLanguageManager().get("gui.editArea.buttons.playerPermissions")));

            // Add response handler
            form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
                if (form.wasClosed()) {
                    handleCancel(player);
                    return;
                }
                
                try {
                    // Get the area being edited
                    FormTrackingData data = plugin.getFormIdMap().get(player.getName() + "_editing");
                    if (data == null) return;

                    Area targetArea = plugin.getArea(data.getFormId());
                    if (targetArea == null) return;

                    // Determine which form to open next based on the button clicked
                    String formId;
                    int buttonId = form.getResponse().getClickedButtonId();
                    
                    switch (buttonId) {
                        case 0:
                            formId = FormIds.BASIC_SETTINGS;
                            break;
                        case 1:
                            formId = FormIds.BUILDING_SETTINGS;
                            break;
                        case 2:
                            formId = FormIds.ENVIRONMENT_SETTINGS;
                            break;
                        case 3:
                            formId = FormIds.ENTITY_SETTINGS;
                            break;
                        case 4:
                            formId = FormIds.TECHNICAL_SETTINGS;
                            break;
                        case 5:
                            formId = FormIds.SPECIAL_SETTINGS;
                            break;
                        case 6:
                            formId = FormIds.ITEMS_DROPS_SETTINGS;
                            break;
                        case 7:
                            formId = FormIds.POTION_EFFECTS_SETTINGS;
                            break;
                        case 8:
                            if (plugin.isLuckPermsEnabled()) {
                                formId = FormIds.LUCKPERMS_SETTINGS;
                            } else {
                                formId = FormIds.PLAYER_SETTINGS;
                            }
                            break;
                        case 9:
                            formId = FormIds.PLAYER_SETTINGS;
                            break;
                        default:
                            return;
                    }

                    // Open the selected form
                    plugin.getGuiManager().openFormById(player, formId, targetArea);
                    
                } catch (Exception e) {
                    plugin.getLogger().error("Error handling edit area form response", e);
                    player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
                    handleCancel(player);
                }
            }));
            
            // Show the form
            player.showFormWindow(form);
            
        } catch (Exception e) {
            plugin.getLogger().error("Error creating edit area form", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
            FormUtils.cleanup(player);
        }
    }
    
    /**
     * Open the form using the area stored in form tracking data
     */
    public void open(Player player) {
        FormTrackingData data = plugin.getFormIdMap().get(player.getName() + "_editing");
        if (data == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.error.noAreaSelected"));
            FormUtils.cleanup(player);
            plugin.getGuiManager().openMainMenu(player);
            return;
        }

        Area area = plugin.getArea(data.getFormId());
        if (area == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.error.areaNotFound"));
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
        FormUtils.cleanup(player);
        plugin.getGuiManager().openMainMenu(player);
    }
    
    /**
     * Get the area that's currently being edited
     */
    protected Area getEditingArea(Player player) {
        var areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
        if (areaData == null) return null;
        return plugin.getArea(areaData.getFormId());
    }
}
