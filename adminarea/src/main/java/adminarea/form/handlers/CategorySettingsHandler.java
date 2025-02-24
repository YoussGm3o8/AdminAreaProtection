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

public class CategorySettingsHandler extends BaseFormHandler {
    private final String formId;
    private final PermissionToggle.Category category;

    public CategorySettingsHandler(AdminAreaProtectionPlugin plugin, String formId) {
        super(plugin);
        this.formId = formId;
        this.category = getCategoryFromFormId(formId);
        validateFormId();
    }

    @Override
    public String getFormId() {
        return formId;
    }

    @Override 
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        // Category settings only use custom form responses
        plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, getEditingArea(player));
    }

    @Override
    public FormWindow createForm(Player player) {
        return createForm(player, getEditingArea(player));
    }

    @Override
    public FormWindow createForm(Player player, Area area) {
        if (area == null) return null;

        FormWindowCustom form = new FormWindowCustom(plugin.getLanguageManager().get("gui.categorySettings.title", 
            Map.of("category", category.getDisplayName(), "area", area.getName())));
        
        // Add header with clear instructions
        form.addElement(new ElementLabel(plugin.getLanguageManager().get("gui.categorySettings.header", 
            Map.of("category", category.getDisplayName()))));
        
        // Get area settings
        AreaDTO dto = area.toDTO();
        JSONObject settings = dto.settings();

        // Add toggles for this category
        List<PermissionToggle> categoryToggles = PermissionToggle.getTogglesByCategory().get(category);
        if (categoryToggles != null) {
            for (PermissionToggle toggle : categoryToggles) {
                String description = plugin.getLanguageManager().get(
                    "gui.permissions.toggles." + toggle.getPermissionNode());
                form.addElement(new ElementToggle(
                    plugin.getLanguageManager().get("gui.permissions.toggle.format",
                        Map.of("name", toggle.getDisplayName(), "description", description)),
                    settings.optBoolean(toggle.getPermissionNode(), toggle.getDefaultValue())
                ));
            }
        }
        
        return form;
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
            List<PermissionToggle> categoryToggles = PermissionToggle.getTogglesByCategory().get(category);
            if (categoryToggles == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.error.invalidCategory"));
                plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, area);
                return;
            }

            // Skip header label element (index 0)
            // Process each toggle starting at index 1
            for (int i = 0; i < categoryToggles.size(); i++) {
                Boolean value = response.getToggleResponse(i + 1);
                if (value == null) {
                    player.sendMessage(plugin.getLanguageManager().get("messages.error.invalidForm"));
                    plugin.getGuiManager().openFormById(player, getFormId(), area);
                    return;
                }
                updatedSettings.put(categoryToggles.get(i).getPermissionNode(), value);
            }

            // Create updated area
            Area updatedArea = AreaBuilder.fromDTO(currentDTO)
                .settings(updatedSettings)
                .build();

            // Save changes
            plugin.updateArea(updatedArea); 
            player.sendMessage(plugin.getLanguageManager().get("messages.area.updated",
                Map.of("area", area.getName())));

            // Return to edit menu
            plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, updatedArea);

        } catch (Exception e) {
            plugin.getLogger().error("Error handling category settings", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.error.saveChanges"));
            plugin.getGuiManager().openMainMenu(player);
        }
    }

    private Area getEditingArea(Player player) {
        var areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
        if (areaData == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.error.noAreaSelected"));
            return null;
        }
        return plugin.getArea(areaData.getFormId());
    }

    private PermissionToggle.Category getCategoryFromFormId(String formId) {
        return switch (formId) {
            case FormIds.BUILDING_SETTINGS -> PermissionToggle.Category.BUILDING;
            case FormIds.ENVIRONMENT_SETTINGS -> PermissionToggle.Category.ENVIRONMENT;
            case FormIds.ENTITY_SETTINGS -> PermissionToggle.Category.ENTITY;
            case FormIds.TECHNICAL_SETTINGS -> PermissionToggle.Category.TECHNICAL;
            case FormIds.SPECIAL_SETTINGS -> PermissionToggle.Category.SPECIAL;
            default -> throw new IllegalStateException("Unknown category form ID: " + formId);
        };
    }
}
