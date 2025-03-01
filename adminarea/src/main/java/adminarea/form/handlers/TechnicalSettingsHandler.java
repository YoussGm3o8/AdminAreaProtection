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
import java.util.ArrayList;

public class TechnicalSettingsHandler extends BaseFormHandler {

    public TechnicalSettingsHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
        validateFormId();
    }

    @Override
    public String getFormId() {
        return FormIds.TECHNICAL_SETTINGS;
    }

    @Override
    public FormWindow createForm(Player player) {
        return createForm(player, getEditingArea(player));
    }

    @Override
    public FormWindow createForm(Player player, Area area) {
        if (area == null) return null;

        try {
            FormWindowCustom form = new FormWindowCustom(plugin.getLanguageManager().get("gui.technicalSettings.title", 
                Map.of("area", area.getName())));
            
            // Add header with clear instructions
            form.addElement(new ElementLabel(plugin.getLanguageManager().get("gui.technicalSettings.header")));
            
            // Get area settings
            AreaDTO dto = area.toDTO();
            JSONObject settings = dto.settings();

            // Add toggles for this category
            List<PermissionToggle> toggles = PermissionToggle.getTogglesByCategory().get(PermissionToggle.Category.TECHNICAL);
            if (toggles != null) {
                for (PermissionToggle toggle : toggles) {
                    String permissionNode = normalizePermissionNode(toggle.getPermissionNode());
                    String description = plugin.getLanguageManager().get(
                        "gui.permissions.toggles." + toggle.getPermissionNode());
                    form.addElement(new ElementToggle(
                        plugin.getLanguageManager().get("gui.permissions.toggle.format", 
                            Map.of("name", toggle.getDisplayName(), "description", description)),
                        settings.optBoolean(permissionNode, toggle.getDefaultValue())
                    ));
                }
            }
            
            return form;
        } catch (Exception e) {
            plugin.getLogger().error("Error creating technical settings form", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.createError",
                Map.of("error", e.getMessage())));
            return null;
        }
    }

    @Override
    protected void handleCustomResponse(Player player, FormResponseCustom response) {
        if (player == null || response == null) return;

        Area area = getEditingArea(player);
        if (area == null) return;

        if (plugin.isDebugMode()) {
            plugin.debug("Processing technical toggles for area " + area.getName());
        }

        List<String> changedToggles = new ArrayList<>();
        List<PermissionToggle> toggles = PermissionToggle.getTogglesByCategory().get(PermissionToggle.Category.TECHNICAL);

        // Skip header label element (index 0)
        // Process each toggle starting at index 1
        for (int i = 0; i < toggles.size(); i++) {
            try {
                String permissionNode = normalizePermissionNode(toggles.get(i).getPermissionNode());
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Processing toggle: " + toggles.get(i).getDisplayName() + 
                                " with permission node: " + permissionNode);
                }
                
                // Get current value
                boolean currentValue = area.getToggleState(permissionNode);
                
                // Get raw response
                Object rawResponse = response.getResponse(i + 1);
                if (rawResponse == null) continue;

                // Convert response to boolean
                boolean toggleValue;
                if (rawResponse instanceof Boolean) {
                    toggleValue = (Boolean) rawResponse;
                } else continue;
                
                // Only update if value changed
                if (currentValue != toggleValue) {
                    area.setToggleState(permissionNode, toggleValue);
                    changedToggles.add(permissionNode);
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Updated toggle " + permissionNode + " from " + 
                            currentValue + " to " + toggleValue);
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().error("Error processing toggle " + toggles.get(i).getPermissionNode(), e);
            }
        }

        if (!changedToggles.isEmpty()) {
            player.sendMessage(plugin.getLanguageManager().get("messages.area.updated",
                Map.of("count", String.valueOf(changedToggles.size()))));
        }

        // Return to edit menu
        plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, area);
    }

    @Override
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        // Technical settings only use custom form responses
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

    protected String normalizePermissionNode(String node) {
            return node.startsWith("gui.permissions.toggles.") ? node : "gui.permissions.toggles." + node;
        }
}