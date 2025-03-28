package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.constants.FormIds;
import adminarea.permissions.PermissionToggle;
import cn.nukkit.Player;
import cn.nukkit.form.element.ElementLabel;
import cn.nukkit.form.element.ElementToggle;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowCustom;
import java.util.HashMap;
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

        try {
            // Force reload the area from the database to ensure we have the latest data
            try {
                Area freshArea = plugin.getDatabaseManager().loadArea(area.getName());
                if (freshArea != null) {
                    // Use the fresh area
                    area = freshArea;
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Reloaded area from database for " + category.name().toLowerCase() + " settings form");
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().error("Failed to reload area from database", e);
            }
            
            FormWindowCustom form = new FormWindowCustom(plugin.getLanguageManager().get("gui.categorySettings.title", 
                Map.of("category", category.getDisplayName(), "area", area.getName())));
            
            // Add header with clear instructions
            form.addElement(new ElementLabel(plugin.getLanguageManager().get("gui.categorySettings.header", 
                Map.of("category", category.getDisplayName()))));
            
            // Get area toggle states directly from the area object
            if (plugin.isDebugMode()) {
                plugin.debug("Creating " + category.name().toLowerCase() + " settings form for area: " + area.getName());
            }

            // Add toggles for this category
            List<PermissionToggle> categoryToggles = PermissionToggle.getTogglesByCategory().get(category);
            if (categoryToggles != null) {
                for (PermissionToggle toggle : categoryToggles) {
                    String permissionNode = normalizePermissionNode(toggle.getPermissionNode());
                    String description = plugin.getLanguageManager().get(
                        "gui.permissions.toggles." + toggle.getPermissionNode());
                    
                    // Get current toggle state with default fallback
                    boolean currentValue = area.getToggleState(permissionNode);
                    
                    if (plugin.isDebugMode()) {
                        plugin.debug("Toggle " + toggle.getDisplayName() + " (" + permissionNode + ") value: " + currentValue);
                    }
                    
                    form.addElement(new ElementToggle(
                        plugin.getLanguageManager().get("gui.permissions.toggle.format",
                            Map.of("name", toggle.getDisplayName(), "description", description)),
                        currentValue
                    ));
                }
            }
            
            return form;
        } catch (Exception e) {
            plugin.getLogger().error("Error creating " + category.name().toLowerCase() + " settings form", e);
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

        Map<String, Boolean> toggleChanges = new HashMap<>();
        List<PermissionToggle> toggles = PermissionToggle.getTogglesByCategory().get(category);

        if (toggles == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.error.invalidCategory"));
            plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, area);
            return;
        }

        // Skip header label element (index 0)
        int index = 1;
        for (PermissionToggle toggle : toggles) {
            String permissionNode = normalizePermissionNode(toggle.getPermissionNode());
            boolean currentValue = area.getToggleState(permissionNode);
            
            // Get response value
            boolean newValue = false;
            Object rawValue = response.getResponse(index++);
            if (rawValue instanceof Boolean) {
                newValue = (Boolean)rawValue;
            } else if (rawValue != null) {
                try {
                    newValue = Boolean.parseBoolean(rawValue.toString());
                } catch (Exception e) {
                    continue;
                }
            }
            
            // Only track changes, don't apply yet
            if (currentValue != newValue) {
                toggleChanges.put(permissionNode, newValue);
            }
        }
        
        // Use the standardized method to update toggles
        int changedToggles = updateAreaToggles(area, toggleChanges);
        
        // Use the standardized method to update the area and notify the player
        updateAreaAndNotifyPlayer(player, area, changedToggles, FormIds.EDIT_AREA, category.name().toLowerCase());
    }

    protected Area getEditingArea(Player player) {
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
