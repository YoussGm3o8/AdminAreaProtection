package adminarea.form;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaBuilder;
import adminarea.area.AreaDTO;
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

                // Get current DTO
                AreaDTO currentDTO = area.toDTO();

                // Create updated area using builder
                Area updatedArea = AreaBuilder.fromDTO(currentDTO)
                    .name(name)
                    .priority(priority)
                    .showTitle(customResponse.getToggleResponse(FormFields.SHOW_TITLE))
                    .enterMessage(customResponse.getInputResponse(FormFields.ENTER_MSG))
                    .leaveMessage(customResponse.getInputResponse(FormFields.LEAVE_MSG))
                    .build();

                plugin.updateArea(updatedArea);
                plugin.getGuiManager().openAreaSettings(player, updatedArea);

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

            // Get current DTO
            AreaDTO currentDTO = area.toDTO();

            // Create new settings object based on current settings
            JSONObject updatedSettings = new JSONObject(currentDTO.settings());

            // Update permissions
            List<PermissionToggle> toggles = PermissionToggle.getDefaultToggles();
            for (int i = 0; i < toggles.size(); i++) {
                boolean value = customResponse.getToggleResponse(i);
                updatedSettings.put(toggles.get(i).getPermissionNode(), value);
            }

            // Create updated area using builder
            Area updatedArea = AreaBuilder.fromDTO(currentDTO)
                .settings(updatedSettings)
                .build();

            plugin.updateArea(updatedArea);
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

    public FormWindowSimple createMainMenu() {
        FormWindowSimple form = new FormWindowSimple("Area Protection Menu", "Select an action:");
        
        // Basic area management with descriptive subtitles
        form.addButton(new ElementButton("Create Area\n§7Create new protected area"));
        form.addButton(new ElementButton("Edit Area\n§7Modify existing area"));
        form.addButton(new ElementButton("Delete Area\n§7Remove protected area"));
        form.addButton(new ElementButton("List Areas\n§7View all protected areas"));
        
        // Advanced management with permission checks
        if (plugin.isLuckPermsEnabled()) {
            form.addButton(new ElementButton("Permission Settings\n§7Manage group access"));
        }
        
        return form;
    }

    public FormWindow createAreaForm(Position[] positions) {
        FormWindowCustom form = new FormWindowCustom("Create Protected Area");

        // Add header with clear instructions
        form.addElement(new ElementLabel("§2Enter Area Details\n§7Fill in the required information below"));
        
        // Basic information with improved descriptions
        form.addElement(new ElementInput("Area Name", "Enter a unique name for this area", ""));
        form.addElement(new ElementInput("Priority", "Enter priority (0-100)", "50"));
        form.addElement(new ElementToggle("Show Area Name", true));
        
        // Messages with placeholders suggestion
        form.addElement(new ElementInput("Enter Message", 
            "Message shown when entering (use {player} for player name)", ""));
        form.addElement(new ElementInput("Leave Message", 
            "Message shown when leaving (use {player} for player name)", ""));
        
        // Permission settings
        addPermissionToggles(form);
        
        return form;
    }

    private void addPermissionToggles(FormWindowCustom form) {
        form.addElement(new ElementLabel("\n§2Protection Settings\n§7Configure area permissions"));
        
        for (PermissionToggle.Category category : PermissionToggle.Category.values()) {
            form.addElement(new ElementLabel("\n§6" + category.getDisplayName()));
            
            List<PermissionToggle> toggles = PermissionToggle.getTogglesByCategory().get(category);
            if (toggles != null) {
                for (PermissionToggle toggle : toggles) {
                    String description = plugin.getLanguageManager().get(
                        "gui.permissions.toggles." + toggle.getPermissionNode(), 
                        Map.of("default", toggle.getDisplayName()));
                    form.addElement(new ElementToggle(
                        toggle.getDisplayName() + "\n§7" + description,
                        toggle.getDefaultValue()
                    ));
                }
            }
        }
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
        AreaDTO dto = area.toDTO();
        Map<String, Map<String, Boolean>> groupPerms = dto.groupPermissions();

        for (String groupName : plugin.getGroupNames()) {
            Map<String, Boolean> perms = groupPerms.getOrDefault(groupName, new HashMap<>());
            form.addElement(new ElementToggle("Group: " + groupName, perms.containsKey("access")));
            form.addElement(new ElementToggle("  Build", perms.getOrDefault("build", true)));
            form.addElement(new ElementToggle("  Break", perms.getOrDefault("break", true)));
            form.addElement(new ElementToggle("  Interact", perms.getOrDefault("interact", true)));
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
        for (PermissionToggle toggle : PermissionToggle.getDefaultToggles()) {
            boolean currentValue = area.getEffectivePermission(groupName, toggle.getPermissionNode());
            form.addElement(new ElementToggle(toggle.getDisplayName(), currentValue));
        }

        return form;
    }

    public FormWindow createBasicSettingsForm(Area area) {
        FormWindowCustom form = new FormWindowCustom("Basic Settings: " + area.getName());
        AreaDTO dto = area.toDTO();
        
        form.addElement(new ElementInput("Area Name", "Enter area name", dto.name()));
        form.addElement(new ElementInput("Priority", "Enter priority (0-100)", 
            String.valueOf(dto.priority())));
        form.addElement(new ElementToggle("Show Title", dto.showTitle()));
        form.addElement(new ElementInput("Enter Message", "Message shown when entering", 
            dto.enterMessage()));
        form.addElement(new ElementInput("Leave Message", "Message shown when leaving", 
            dto.leaveMessage()));
            
        return form;
    }

    public FormWindow createProtectionSettingsForm(Area area) {
        FormWindowCustom form = new FormWindowCustom("Protection Settings: " + area.getName());
        
        AreaDTO dto = area.toDTO();
        JSONObject settings = dto.settings();
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
        FormWindowCustom form = new FormWindowCustom("Track Groups: " + trackName);
        Set<String> groups = plugin.getLuckPermsCache().getTrackGroups(trackName);
        
        for (String group : groups) {
            form.addElement(new ElementToggle("Enable " + group, true));
        }
        
        return form;
    }

    public FormWindow createGroupPermissionsForm(String groupName, Area area) {
        FormWindowCustom form = new FormWindowCustom("Group Permissions: " + groupName);
        
        // Get inherited permissions
        List<String> inheritance = plugin.getLuckPermsCache().getInheritanceChain(groupName);
        
        AreaDTO dto = area.toDTO();
        Map<String, Map<String, Boolean>> groupPerms = dto.groupPermissions();
        Map<String, Boolean> currentPerms = groupPerms.getOrDefault(groupName, new HashMap<>());

        // Add toggles for each permission
        for (PermissionToggle toggle : PermissionToggle.getDefaultToggles()) {
            boolean currentValue = currentPerms.getOrDefault(toggle.getPermissionNode(), false);
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
        
        AreaDTO dto = area.toDTO();
        JSONObject settings = dto.settings();

        List<PermissionToggle> specialToggles = PermissionToggle.getTogglesByCategory()
            .get(PermissionToggle.Category.SPECIAL);

        if (specialToggles != null) {
            for (PermissionToggle toggle : specialToggles) {
                String description = plugin.getLanguageManager().get(
                    "gui.permissions.toggles." + toggle.getPermissionNode());
                form.addElement(new ElementToggle(
                    toggle.getDisplayName() + "\n§7" + description,
                    settings.optBoolean(toggle.getPermissionNode(), toggle.getDefaultValue())
                ));
            }
        }
        
        return form;
    }

    public FormWindow createTechnicalSettingsForm(Area area) {
        FormWindowCustom form = new FormWindowCustom("Technical Settings: " + area.getName());
        
        AreaDTO dto = area.toDTO();
        JSONObject settings = dto.settings();

        List<PermissionToggle> technicalToggles = PermissionToggle.getTogglesByCategory()
            .get(PermissionToggle.Category.TECHNICAL);
            
        if (technicalToggles != null) {
            for (PermissionToggle toggle : technicalToggles) {
                String description = plugin.getLanguageManager().get(
                    "gui.permissions.toggles." + toggle.getPermissionNode());
                form.addElement(new ElementToggle(
                    toggle.getDisplayName() + "\n§7" + description,
                    settings.optBoolean(toggle.getPermissionNode(), toggle.getDefaultValue())
                ));
            }
        }
        
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
