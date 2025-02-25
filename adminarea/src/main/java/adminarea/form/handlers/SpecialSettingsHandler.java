package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaBuilder;
import adminarea.area.AreaDTO;
import adminarea.constants.FormIds;
import adminarea.permissions.PermissionToggle;
import cn.nukkit.Player;
import cn.nukkit.form.element.ElementLabel;
import cn.nukkit.form.element.ElementToggle;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowCustom;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;

public class SpecialSettingsHandler extends BaseFormHandler {

    public SpecialSettingsHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
        validateFormId();
    }

    @Override
    public String getFormId() {
        return FormIds.SPECIAL_SETTINGS;
    }

    @Override
    public FormWindow createForm(Player player) {
        return createForm(player, getEditingArea(player));
    }

    @Override
    public FormWindow createForm(Player player, Area area) {
        if (area == null) return null;

        try {
            FormWindowCustom form = new FormWindowCustom(plugin.getLanguageManager().get("gui.specialSettings.title", 
                Map.of("area", area.getName())));
            
            // Add header with clear instructions
            form.addElement(new ElementLabel(plugin.getLanguageManager().get("gui.specialSettings.header")));
            
            // Get area settings
            AreaDTO dto = area.toDTO();
            JSONObject settings = dto.settings();

            // Add toggles for this category
            List<PermissionToggle> toggles = PermissionToggle.getTogglesByCategory().get(PermissionToggle.Category.SPECIAL);
            if (toggles != null) {
                for (PermissionToggle toggle : toggles) {
                    String description = plugin.getLanguageManager().get(
                        "gui.permissions.toggles." + toggle.getPermissionNode());
                    form.addElement(new ElementToggle(
                        plugin.getLanguageManager().get("gui.permissions.toggle.format", 
                            Map.of("name", toggle.getDisplayName(), "description", description)),
                        settings.optBoolean(toggle.getPermissionNode(), toggle.getDefaultValue(toggle.getPermissionNode()))
                    ));
                }
            }
            return form;
        } catch (Exception e) {
            plugin.getLogger().error("Error creating special settings form", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.createError",
                Map.of("error", e.getMessage())));
            return null;
        }
    }

    @Override
    protected void handleCustomResponse(Player player, FormResponseCustom response) {
        try {
            Area area = getEditingArea(player);
            if (area == null) return;

            // Get current DTO
            AreaDTO currentDTO = area.toDTO();
            JSONObject updatedSettings = new JSONObject(currentDTO.settings());

            // Get category toggles
            List<PermissionToggle> toggles = PermissionToggle.getTogglesByCategory().get(PermissionToggle.Category.SPECIAL);
            if (toggles == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.error.invalidCategory"));
                plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, area);
                return;
            }

            // Track number of changes
            int changedSettings = 0;

            // Debug log the response data
            if (plugin.isDebugMode()) {
                plugin.debug("Processing special settings form response:");
                plugin.debug("Number of responses: " + response.getResponses().size());
                for (int i = 0; i < response.getResponses().size(); i++) {
                    plugin.debug("Response " + i + ": " + response.getResponse(i));
                }
            }

            // Skip header label (index 0)
            // Process each toggle starting at index 1
            for (int i = 0; i < toggles.size(); i++) {
                try {
                    // Get current value from settings
                    boolean currentValue = updatedSettings.optBoolean(
                        toggles.get(i).getPermissionNode(), 
                        toggles.get(i).getDefaultValue(toggles.get(i).getPermissionNode())
                    );
                    
                    // Get raw response first
                    Object rawResponse = response.getResponse(i + 1);
                    if (rawResponse == null) {
                        if (plugin.isDebugMode()) {
                            plugin.debug("Null raw response for toggle " + toggles.get(i).getPermissionNode() + " at index " + (i + 1));
                        }
                        continue;
                    }

                    // Convert response to boolean
                    boolean toggleValue;
                    if (rawResponse instanceof Boolean) {
                        toggleValue = (Boolean) rawResponse;
                    } else {
                        if (plugin.isDebugMode()) {
                            plugin.debug("Invalid response type for toggle " + toggles.get(i).getPermissionNode() + 
                                ": " + rawResponse.getClass().getName());
                        }
                        continue;
                    }
                    
                    // Only count as changed if value is different
                    if (currentValue != toggleValue) {
                        changedSettings++;
                        updatedSettings.put(toggles.get(i).getPermissionNode(), toggleValue);
                        if (plugin.isDebugMode()) {
                            plugin.debug("Updated toggle " + toggles.get(i).getPermissionNode() + " from " + 
                                currentValue + " to " + toggleValue);
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().debug("Error processing toggle " + toggles.get(i).getPermissionNode() + ": " + e.getMessage());
                }
            }

            // Create updated area
            Area updatedArea = AreaBuilder.fromDTO(currentDTO)
                .settings(updatedSettings)
                .build();

            // Save changes
            plugin.updateArea(updatedArea); 
            
            // Show success message with change count
            player.sendMessage(plugin.getLanguageManager().get("messages.area.updated",
                Map.of(
                    "area", area.getName(),
                    "count", String.valueOf(changedSettings)
                )));

            // Return to edit menu
            plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, updatedArea);

        } catch (Exception e) {
            plugin.getLogger().error("Error handling special settings", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
            plugin.getGuiManager().openMainMenu(player);
        }
    }

    @Override
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        // Special settings only use custom form responses
        plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, getEditingArea(player));
    }

    private Area getEditingArea(Player player) {
        var areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
        if (areaData == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.error.noAreaSelected"));
            return null;
        }
        return plugin.getArea(areaData.getFormId());
    }
} 