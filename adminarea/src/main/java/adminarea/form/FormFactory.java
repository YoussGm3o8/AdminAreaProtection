package adminarea.form;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.constants.AdminAreaConstants;
import adminarea.permissions.PermissionToggle;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.element.ElementInput;
import cn.nukkit.form.element.ElementLabel;
import cn.nukkit.form.element.ElementToggle;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.form.window.FormWindowSimple;
import cn.nukkit.level.Position;
import cn.nukkit.Player;
import adminarea.data.FormTrackingData;
import net.luckperms.api.track.Track;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.stream.Collectors;

import org.json.JSONObject;

public class FormFactory {
    private final AdminAreaProtectionPlugin plugin;
    private final Map<String, FormResponseHandler> responseHandlers;

    public FormFactory(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        this.responseHandlers = new HashMap<>();
        registerResponseHandlers();
    }

    private void registerResponseHandlers() {
        // Basic settings form response handler
        responseHandlers.put(AdminAreaConstants.FORM_BASIC_SETTINGS, (player, response) -> {
            if (!(response instanceof FormResponseCustom)) return;
            FormResponseCustom customResponse = (FormResponseCustom) response;
            
            try {
                FormTrackingData areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
                if (areaData == null) {
                    player.sendMessage(plugin.getLanguageManager().get("messages.error.invalidForm")); 
                    return;
                }
                
                Area area = plugin.getArea(areaData.getFormId());
                if (area == null) {
                    player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound"));
                    return;
                }

                // Validate input values
                String name = customResponse.getInputResponse(FormFields.NAME);
                if (name == null || name.isEmpty()) {
                    player.sendMessage(plugin.getLanguageManager().get("messages.error.invalidName"));
                    return;
                }

                int priority;
                try {
                    priority = Integer.parseInt(customResponse.getInputResponse(FormFields.PRIORITY));
                    if (priority < 0 || priority > 100) {
                        throw new IllegalArgumentException("Priority must be between 0-100");
                    }
                } catch (NumberFormatException e) {
                    player.sendMessage(plugin.getLanguageManager().get("messages.error.invalidPriority")); 
                    return;
                }

                // Apply validated changes
                area.setName(name)
                    .setPriority(priority)
                    .setShowTitle(customResponse.getToggleResponse(FormFields.SHOW_TITLE))
                    .setEnterMessage(customResponse.getInputResponse(FormFields.ENTER_MSG))
                    .setLeaveMessage(customResponse.getInputResponse(FormFields.LEAVE_MSG));

                plugin.saveArea(area);
                plugin.getGuiManager().openAreaSettings(player, area);

            } catch (Exception e) {
                plugin.getLogger().error("Error handling basic settings form", e);
                player.sendMessage(plugin.getLanguageManager().get("messages.error.saveChanges"));
            }
        });

        // Protection settings form response handler 
        responseHandlers.put(AdminAreaConstants.FORM_PROTECTION_SETTINGS, (player, response) -> {
            if (!(response instanceof FormResponseCustom)) return;
            FormResponseCustom customResponse = (FormResponseCustom) response;
            
            FormTrackingData areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
            Area area = plugin.getArea(areaData.getFormId());
            
            if (area == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound"));
                return;
            }

            JSONObject settings = area.getSettings();
            List<PermissionToggle> toggles = PermissionToggle.getDefaultToggles();
            
            for (int i = 0; i < toggles.size(); i++) {
                boolean value = customResponse.getToggleResponse(i);
                settings.put(toggles.get(i).getPermissionNode(), value);
            }

            area.setSettings(settings);
            plugin.saveArea(area);
        });

        // Track selection form response handler
        responseHandlers.put(AdminAreaConstants.FORM_TRACK_SELECT, (player, response) -> {
            if (!(response instanceof FormResponseSimple)) return;
            FormResponseSimple simpleResponse = (FormResponseSimple) response;
            
            FormTrackingData areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
            Area area = plugin.getArea(areaData.getFormId());
            
            if (area == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound"));
                return;
            }

            int buttonId = simpleResponse.getClickedButtonId();
            List<String> tracks = new ArrayList<>(plugin.getLuckPermsApi()
                .getTrackManager().getLoadedTracks().stream()
                .map(Track::getName)
                .collect(Collectors.toList()));

            if (buttonId >= 0 && buttonId < tracks.size()) {
                String trackName = tracks.get(buttonId);
                plugin.getGuiManager().openTrackGroupsForm(player, area, trackName);
            } else {
                plugin.getGuiManager().openAreaSettings(player, area);
            }
        });
    }

    public void handleResponse(String formId, Player player, Object response) {
        FormResponseHandler handler = responseHandlers.get(formId);
        if (handler != null) {
            try {
                handler.handle(player, response);
            } catch (Exception e) {
                plugin.getLogger().error("Error handling form response", e);
                player.sendMessage(plugin.getLanguageManager().get("messages.form.error"));
            }
        }
    }

    @FunctionalInterface
    private interface FormResponseHandler {
        void handle(Player player, Object response);
    }

    // Add constants for form field indices
    private static final class FormFields {
        static final int NAME = 0;
        static final int PRIORITY = 1;
        static final int SHOW_TITLE = 2;
        static final int ENTER_MSG = 3;
        static final int LEAVE_MSG = 4;
        // Add other field indices
    }

    // Add helper method for common form elements
    private void addBasicSettingsElements(FormWindowCustom form, Area area) {
        form.addElement(new ElementInput(
            plugin.getLanguageManager().get("gui.labels.areaName"),
            "", area.getName()));
        form.addElement(new ElementInput(
            plugin.getLanguageManager().get("gui.labels.priority"),
            "0-100", String.valueOf(area.getPriority())));
        form.addElement(new ElementToggle(
            plugin.getLanguageManager().get("gui.labels.showTitle"),
            area.isShowTitle()));
        form.addElement(new ElementInput(
            plugin.getLanguageManager().get("gui.labels.enterMessage"),
            "", area.getEnterMessage() != null ? area.getEnterMessage() : ""));
        form.addElement(new ElementInput(
            plugin.getLanguageManager().get("gui.labels.leaveMessage"),
            "", area.getLeaveMessage() != null ? area.getLeaveMessage() : ""));
    }

    public FormWindowSimple createMainMenu() {
        FormWindowSimple form = new FormWindowSimple(AdminAreaConstants.TITLE_MAIN_MENU, "Select an option:");
        
        // Basic area management
        form.addButton(new ElementButton("Create Area"));
        form.addButton(new ElementButton("Edit Area"));
        form.addButton(new ElementButton("Delete Area"));
        
        // Advanced management
        form.addButton(new ElementButton("Manage Player Permissions"));
        form.addButton(new ElementButton("LuckPerms Integration"));
        form.addButton(new ElementButton("Area Settings"));
        
        // Utility options
        form.addButton(new ElementButton("List Areas"));
        form.addButton(new ElementButton("Toggle Protection"));
        form.addButton(new ElementButton("Reload Config"));
        
        return form;
    }

    public FormWindow createAreaForm(Position[] positions) {
        FormWindowCustom form = new FormWindowCustom(plugin.getLanguageManager().get("gui.createArea.title"));
        
        // Basic information
        form.addElement(new ElementInput(
            plugin.getLanguageManager().get("gui.createArea.labels.name"),
            plugin.getLanguageManager().get("gui.createArea.labels.namePlaceholder"), 
            ""));
        
        form.addElement(new ElementInput(
            plugin.getLanguageManager().get("gui.createArea.labels.priority"),
            plugin.getLanguageManager().get("gui.createArea.labels.priorityPlaceholder"), 
            "0"));
            
        form.addElement(new ElementToggle(
            plugin.getLanguageManager().get("gui.createArea.labels.globalArea"), 
            false));

        // Coordinates
        if (positions != null && positions[0] != null && positions[1] != null) {
            Position pos1 = positions[0];
            Position pos2 = positions[1];
            form.addElement(new ElementInput("X Min", "Enter X minimum", 
                String.valueOf(Math.min(pos1.getFloorX(), pos2.getFloorX()))));
            form.addElement(new ElementInput("X Max", "Enter X maximum", 
                String.valueOf(Math.max(pos1.getFloorX(), pos2.getFloorX()))));
            form.addElement(new ElementInput("Y Min", "Enter Y minimum", 
                String.valueOf(Math.min(pos1.getFloorY(), pos2.getFloorY()))));
            form.addElement(new ElementInput("Y Max", "Enter Y maximum", 
                String.valueOf(Math.max(pos1.getFloorY(), pos2.getFloorY()))));
            form.addElement(new ElementInput("Z Min", "Enter Z minimum", 
                String.valueOf(Math.min(pos1.getFloorZ(), pos2.getFloorZ()))));
            form.addElement(new ElementInput("Z Max", "Enter Z maximum", 
                String.valueOf(Math.max(pos1.getFloorZ(), pos2.getFloorZ()))));
        } else {
            form.addElement(new ElementInput("X Min", "Enter X minimum", "~"));
            form.addElement(new ElementInput("X Max", "Enter X maximum", "~"));
            form.addElement(new ElementInput("Y Min", "Enter Y minimum", "0"));
            form.addElement(new ElementInput("Y Max", "Enter Y maximum", "255"));
            form.addElement(new ElementInput("Z Min", "Enter Z minimum", "~"));
            form.addElement(new ElementInput("Z Max", "Enter Z maximum", "~"));
        }

        // Add all permission toggles with descriptions
        for (PermissionToggle toggle : PermissionToggle.getDefaultToggles()) {
            String description = plugin.getLanguageManager().get("gui.permissions.toggles." + toggle.getPermissionNode());
            form.addElement(new ElementToggle(
                toggle.getDisplayName() + "\n§7" + description,
                toggle.getDefaultValue()
            ));
        }
        
        return form;
    }

    public FormWindow createAreaForm() {
        return createAreaForm(null);
    }

    public FormWindow createEditForm(Area area) {
        if (area == null) return null;
        
        FormWindowSimple form = new FormWindowSimple("Edit Area: " + area.getName(), "Select what to edit:");
        
        // Basic options
        form.addButton(new ElementButton("Basic Settings"));
        form.addButton(new ElementButton("Protection Settings"));
        
        // Only show LuckPerms button if integration is available and working
        if (plugin.isLuckPermsEnabled()) {
            form.addButton(new ElementButton("Group Permissions"));
        }
        
        // Add track management if LuckPerms is enabled
        if (plugin.isLuckPermsEnabled()) {
            form.addButton(new ElementButton("Track Management"));
        }
        
        form.addButton(new ElementButton("Back to Main Menu"));
        
        return form;
    }

    public FormWindow createAreaListForm(String title, String content, List<Area> areas) {
        FormWindowSimple form = new FormWindowSimple(title, content);
        
        if (areas.isEmpty()) {
            form.setContent(AdminAreaConstants.MSG_NO_AREAS);
            return form;
        }

        for (Area area : areas) {
            form.addButton(new ElementButton(area.getName()));
        }
        
        return form;
    }

    public FormWindow createLuckPermsOverrideForm(Area area) {
        FormWindowCustom form = new FormWindowCustom("Permission Overrides - " + area.getName());
        
        // Add toggle buttons for each group permission
        JSONObject groupPerms = area.getGroupPermissions();
        for (String groupName : plugin.getGroupNames()) {
            JSONObject perms = groupPerms.optJSONObject(groupName, new JSONObject());
            form.addElement(new ElementToggle("Group: " + groupName, perms.has("access")));
            form.addElement(new ElementToggle("  Build", perms.optBoolean("build", true)));
            form.addElement(new ElementToggle("  Break", perms.optBoolean("break", true)));
            form.addElement(new ElementToggle("  Interact", perms.optBoolean("interact", true)));
        }
        
        return form;
    }

    public FormWindow createPlayerAreaManagementForm() {
        FormWindowSimple form = new FormWindowSimple("Manage Player Areas", "Select an option:");
        form.addButton(new ElementButton("Manage Permissions"));
        form.addButton(new ElementButton("View Player Access"));
        return form;
    }

    public FormWindowSimple createDeleteAreaForm() {
        FormWindowSimple form = new FormWindowSimple("Delete Area", "Select an area to delete:");
        
        if (plugin.getAreas().isEmpty()) {
            form.setContent("There are no areas to delete.");
        } else {
            for (Area area : plugin.getAreas()) {
                form.addButton(new ElementButton(area.getName()));
            }
        }
        
        return form;
    }

    public FormWindowSimple createForm(String formId) {
        return switch (formId) {
            case AdminAreaConstants.FORM_DELETE_SELECT -> createDeleteAreaForm();
            case AdminAreaConstants.FORM_MAIN_MENU -> createMainMenu();
            default -> null;
        };
    }

    public FormWindow createDeleteConfirmForm(String areaName, String message) {
        FormWindowSimple form = new FormWindowSimple("Delete Area", message);
        form.addButton(new ElementButton("§cConfirm Delete"));
        form.addButton(new ElementButton("§aCancel"));
        return form;
    }

    public FormWindow createAreaSettingsForm(Area area) {
        if (area == null) return null;
        
        FormWindowCustom form = new FormWindowCustom("Area Settings: " + area.getName());
        
        // Basic settings with null checks
        form.addElement(new ElementToggle("Show Title", area.isShowTitle()));
        form.addElement(new ElementInput("Enter Message", "Message shown when entering", 
            area.getEnterMessage() != null ? area.getEnterMessage() : ""));
        form.addElement(new ElementInput("Leave Message", "Message shown when leaving", 
            area.getLeaveMessage() != null ? area.getLeaveMessage() : ""));
        
        // Protect against null settings
        JSONObject settings = area.getSettings() != null ? area.getSettings() : new JSONObject();
        for (PermissionToggle toggle : PermissionToggle.getDefaultToggles()) {
            form.addElement(new ElementToggle(toggle.getDisplayName(), 
                settings.optBoolean(toggle.getPermissionNode(), toggle.getDefaultValue())));
        }
        
        return form;
    }

    public FormWindow createLuckPermsGroupListForm(Area area) {
        FormWindowSimple form = new FormWindowSimple("Select Group", 
            "Choose a group to manage permissions for " + area.getName());
        
        for (String groupName : plugin.getGroupNames()) {
            form.addButton(new ElementButton(groupName));
        }
        
        return form;
    }

    public FormWindow createErrorForm(String message) {
        FormWindowSimple form = new FormWindowSimple("Error", message);
        form.addButton(new ElementButton("OK"));
        return form;
    }

    public FormWindowSimple createAreaSettingsMenu(Area area) {
        FormWindowSimple form = new FormWindowSimple(
            "Area Settings: " + area.getName(),
            "Configure area settings and permissions:"
        );

        form.addButton(new ElementButton("Basic Settings"));
        form.addButton(new ElementButton("Protection Settings"));
        
        // Only show LuckPerms button if LuckPerms is available
        if (plugin.getLuckPermsApi() != null) {
            form.addButton(new ElementButton("LuckPerms Group Permissions"));
        }
        
        form.addButton(new ElementButton("Back"));

        return form;
    }

    public FormWindowSimple createGroupSelectionForm(Area area) {
        FormWindowSimple form = new FormWindowSimple(
            "Select Group - " + area.getName(),
            "Choose a group to edit permissions for:"
        );

        // Add buttons for each LuckPerms group
        plugin.getGroupNames().forEach(groupName -> 
            form.addButton(new ElementButton(groupName)));

        form.addButton(new ElementButton("Back"));

        return form;
    }

    public FormWindowCustom createGroupPermissionForm(Area area, String groupName) {
        FormWindowCustom form = new FormWindowCustom("Edit " + groupName + " Permissions");

        // Add toggles for each permission
        for (PermissionToggle toggle : PermissionToggle.getPlayerToggles()) {
            boolean currentValue = area.getGroupPermission(groupName, toggle.getPermissionNode());
            form.addElement(new ElementToggle(toggle.getDisplayName(), currentValue));
        }

        return form;
    }

    public FormWindow createBasicSettingsForm(Area area) {
        FormWindowCustom form = new FormWindowCustom("Basic Settings: " + area.getName());
        
        form.addElement(new ElementInput("Area Name", "Enter area name", area.getName()));
        form.addElement(new ElementInput("Priority", "Enter priority (0-100)", 
            String.valueOf(area.getPriority())));
        form.addElement(new ElementToggle("Show Title", area.isShowTitle()));
        form.addElement(new ElementInput("Enter Message", "Message shown when entering", 
            area.getEnterMessage() != null ? area.getEnterMessage() : ""));
        form.addElement(new ElementInput("Leave Message", "Message shown when leaving", 
            area.getLeaveMessage() != null ? area.getLeaveMessage() : ""));
            
        return form;
    }

    public FormWindow createProtectionSettingsForm(Area area) {
        FormWindowCustom form = new FormWindowCustom("Protection Settings: " + area.getName());
        
        JSONObject settings = area.getSettings();
        for (PermissionToggle toggle : PermissionToggle.getDefaultToggles()) {
            String description = plugin.getLanguageManager().get("gui.permissions.toggles." + toggle.getPermissionNode());
            form.addElement(new ElementToggle(
                toggle.getDisplayName() + "\n§7" + description,
                settings.optBoolean(toggle.getPermissionNode(), toggle.getDefaultValue())
            ));
        }
        
        return form;
    }

    public FormWindow createTrackSelectionForm() {
        FormWindowSimple form = new FormWindowSimple("Select Track", 
            "Choose a track to manage permissions:");
        
        if (plugin.getLuckPermsApi() != null) {
            plugin.getLuckPermsApi().getTrackManager().getLoadedTracks().forEach(track -> {
                form.addButton(new ElementButton(track.getName()));
            });
        }
        
        form.addButton(new ElementButton("Back"));
        return form;
    }

    public FormWindow createTrackGroupsForm(String trackName) {
        Set<String> groups = plugin.getLuckPermsCache().getTrackGroups(trackName);
        FormWindowCustom form = new FormWindowCustom("Track Groups: " + trackName);
        
        for (String group : groups) {
            form.addElement(new ElementToggle("Enable " + group, true));
        }
        
        return form;
    }

    public FormWindow createGroupPermissionsForm(String groupName, Area area) {
        FormWindowCustom form = new FormWindowCustom("Group Permissions: " + groupName);
        
        // Get inherited permissions
        List<String> inheritance = plugin.getLuckPermsCache().getInheritanceChain(groupName);
        
        // Add toggles for each permission
        for (PermissionToggle toggle : PermissionToggle.getDefaultToggles()) {
            boolean currentValue = area.getEffectivePermission(groupName, toggle.getPermissionNode());
            String description = plugin.getLanguageManager().get("gui.permissions.toggles." + toggle.getPermissionNode());
            
            // Add inheritance info if available
            String displayText = toggle.getDisplayName() + "\n§7" + description;
            if (inheritance.size() > 1) {
                displayText += "\n" + plugin.getLanguageManager().get("gui.labels.inheritedFrom", 
                    Map.of("groups", String.join(", ", inheritance.subList(1, inheritance.size()))));
            }
            
            form.addElement(new ElementToggle(displayText, currentValue));
        }
        
        return form;
    }

    public FormWindow createSpecialSettingsForm(Area area) {
        FormWindowCustom form = new FormWindowCustom("Special Settings: " + area.getName());
        
        List<PermissionToggle> specialToggles = PermissionToggle.getTogglesByCategory()
            .get(PermissionToggle.Category.SPECIAL);

        if (specialToggles != null) {
            for (PermissionToggle toggle : specialToggles) {
                String description = plugin.getLanguageManager().get(
                    "gui.permissions.toggles." + toggle.getPermissionNode());
                form.addElement(new ElementToggle(
                    toggle.getDisplayName() + "\n§7" + description,
                    area.getSettings().optBoolean(toggle.getPermissionNode(), toggle.getDefaultValue())
                ));
            }
        }
        
        return form;
    }

    public FormWindow createTechnicalSettingsForm(Area area) {
        FormWindowCustom form = new FormWindowCustom("Technical Settings: " + area.getName());
        
        List<PermissionToggle> technicalToggles = PermissionToggle.getTogglesByCategory()
            .get(PermissionToggle.Category.TECHNICAL);
            
        if (technicalToggles != null) {
            for (PermissionToggle toggle : technicalToggles) {
                String description = plugin.getLanguageManager().get(
                    "gui.permissions.toggles." + toggle.getPermissionNode());
                form.addElement(new ElementToggle(
                    toggle.getDisplayName() + "\n§7" + description,
                    area.getSettings().optBoolean(toggle.getPermissionNode(), toggle.getDefaultValue())
                ));
            }
        }
        
        return form;
    }

    public FormWindow createAreaCreationForm() {
        FormWindowCustom form = new FormWindowCustom("Create Area");
        
        // Add basic area information inputs
        form.addElement(new ElementInput(
            "Area Name", 
            "Enter a name for this area", 
            ""));
        form.addElement(new ElementInput(
            "Priority", 
            "Enter priority (0-100)", 
            "0"));
        form.addElement(new ElementToggle(
            "Show Title", 
            true));
        form.addElement(new ElementInput(
            "Enter Message", 
            "Message shown when entering area", 
            ""));
        form.addElement(new ElementInput(
            "Leave Message", 
            "Message shown when leaving area", 
            ""));
        
        return form;
    }

    public FormWindow createMainMenuForm() {
        FormWindowSimple form = new FormWindowSimple(
            plugin.getLanguageManager().get("gui.mainMenu.title"),
            plugin.getLanguageManager().get("gui.mainMenu.content")
        );
        
        // Basic area management
        form.addButton(new ElementButton(plugin.getLanguageManager().get("gui.buttons.createArea")));
        form.addButton(new ElementButton(plugin.getLanguageManager().get("gui.buttons.editArea")));
        form.addButton(new ElementButton(plugin.getLanguageManager().get("gui.buttons.deleteArea")));
        form.addButton(new ElementButton(plugin.getLanguageManager().get("gui.buttons.areaSettings")));
        form.addButton(new ElementButton(plugin.getLanguageManager().get("gui.buttons.protectionSettings")));
        
        // LuckPerms integration buttons
        if (plugin.isLuckPermsEnabled()) {
            form.addButton(new ElementButton(plugin.getLanguageManager().get("gui.buttons.luckperms")));
        }
        
        return form;
    }
}
