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
import java.util.HashMap;

public class EntitySettingsHandler extends BaseFormHandler {

    public EntitySettingsHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
        validateFormId();
    }

    @Override
    public String getFormId() {
        return FormIds.ENTITY_SETTINGS;
    }

    @Override
    public FormWindow createForm(Player player) {
        return createForm(player, getEditingArea(player));
    }

    @Override
    public FormWindow createForm(Player player, Area area) {
        if (area == null) return null;

        try {
            FormWindowCustom form = new FormWindowCustom(plugin.getLanguageManager().get("gui.entitySettings.title", 
                Map.of("area", area.getName())));
            
            // Add header with clear instructions
            form.addElement(new ElementLabel(plugin.getLanguageManager().get("gui.entitySettings.header")));
            
            // Get area settings
            AreaDTO dto = area.toDTO();
            JSONObject settings = dto.settings();

            // Add toggles for this category
            List<PermissionToggle> toggles = PermissionToggle.getTogglesByCategory().get(PermissionToggle.Category.ENTITY);
            if (toggles != null) {
                for (PermissionToggle toggle : toggles) {
                    try {
                        String description = plugin.getLanguageManager().get(
                            "gui.permissions.toggles." + toggle.getPermissionNode());
                        
                        // Add player placeholder for relevant toggles
                        Map<String, String> placeholders = new HashMap<>();
                        placeholders.put("name", toggle.getDisplayName());
                        placeholders.put("description", description);
                        if (description.contains("{player}")) {
                            placeholders.put("player", "players");
                        }
                        
                        form.addElement(new ElementToggle(
                            plugin.getLanguageManager().get("gui.permissions.toggle.format", placeholders),
                            settings.optBoolean(toggle.getPermissionNode(), toggle.getDefaultValue())
                        ));
                    } catch (Exception e) {
                        plugin.getLogger().error("Error adding toggle " + toggle.getPermissionNode(), e);
                        player.sendMessage(plugin.getLanguageManager().get("messages.form.entity.mobTypeError",
                            Map.of("type", toggle.getPermissionNode(), "toggle", toggle.getDisplayName())));
                    }
                }
            }
            
            return form;
        } catch (Exception e) {
            plugin.getLogger().error("Error creating entity settings form", e);
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
            List<PermissionToggle> toggles = PermissionToggle.getTogglesByCategory().get(PermissionToggle.Category.ENTITY);
            if (toggles == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.error.invalidCategory"));
                plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, area);
                return;
            }

            // Track number of changes
            int changedSettings = 0;

            // Skip header label (index 0)
            // Process each toggle starting at index 1
            for (int i = 0; i < toggles.size(); i++) {
                try {
                    Boolean value = response.getToggleResponse(i + 1);
                    if (value == null) {
                        player.sendMessage(plugin.getLanguageManager().get("messages.form.entity.toggleError",
                            Map.of(
                                "toggle", toggles.get(i).getDisplayName(),
                                "error", "No value provided"
                            )));
                        continue;
                    }
                    
                    // Only count as changed if value is different
                    boolean currentValue = updatedSettings.optBoolean(toggles.get(i).getPermissionNode(), 
                        toggles.get(i).getDefaultValue());
                    if (currentValue != value) {
                        changedSettings++;
                    }
                    
                    updatedSettings.put(toggles.get(i).getPermissionNode(), value);
                } catch (Exception e) {
                    plugin.getLogger().error("Error processing toggle " + toggles.get(i).getPermissionNode(), e);
                    player.sendMessage(plugin.getLanguageManager().get("messages.form.entity.toggleError",
                        Map.of(
                            "toggle", toggles.get(i).getDisplayName(),
                            "error", e.getMessage()
                        )));
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
            plugin.getLogger().error("Error handling entity settings", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.entity.saveError",
                Map.of(
                    "area", getEditingArea(player).getName(),
                    "error", e.getMessage()
                )));
            plugin.getGuiManager().openMainMenu(player);
        }
    }

    @Override
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        // Entity settings only use custom form responses
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