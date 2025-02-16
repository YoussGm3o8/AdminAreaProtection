package adminarea.form;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.constants.AdminAreaConstants;
import adminarea.permissions.PermissionToggle;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.element.ElementInput;
import cn.nukkit.form.element.ElementToggle;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.form.window.FormWindowSimple;
import cn.nukkit.level.Position;

import java.util.List;

import org.json.JSONObject;

public class FormFactory {
    private final AdminAreaProtectionPlugin plugin;

    public FormFactory(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
    }

    public FormWindow createMainMenu() {
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
        FormWindowCustom form = new FormWindowCustom(AdminAreaConstants.TITLE_CREATE_AREA);
        
        // Basic information
        form.addElement(new ElementInput("Area Name", "Enter area name", ""));
        form.addElement(new ElementInput("Priority", "Enter priority (0-100)", "0"));
        form.addElement(new ElementToggle("Global Area", false));
        
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

        // Protection options - Add all protection toggles
        for (PermissionToggle toggle : PermissionToggle.getDefaultToggles()) {
            form.addElement(new ElementToggle(toggle.getDisplayName(), toggle.getDefaultValue()));
        }
        
        // Display options
        form.addElement(new ElementToggle("Show Title", true));
        form.addElement(new ElementInput("Enter Message", "Message shown when entering", "Welcome!"));
        form.addElement(new ElementInput("Leave Message", "Message shown when leaving", "Goodbye!"));
        
        return form;
    }

    public FormWindow createAreaForm() {
        return createAreaForm(null);
    }

    public FormWindow createEditForm(Area area) {
        if (area == null) return null;
        
        FormWindowCustom form = new FormWindowCustom("Edit Area: " + area.getName());
        
        // Basic information
        form.addElement(new ElementInput("Area Name", "Enter area name", area.getName()));
        form.addElement(new ElementInput("Priority", "Enter priority (0-100)", 
            String.valueOf(area.getPriority())));
        
        // Coordinates
        form.addElement(new ElementInput("X Min", "Enter X minimum", 
            String.valueOf(area.getXMin())));
        form.addElement(new ElementInput("X Max", "Enter X maximum", 
            String.valueOf(area.getXMax())));
        form.addElement(new ElementInput("Y Min", "Enter Y minimum", 
            String.valueOf(area.getYMin())));
        form.addElement(new ElementInput("Y Max", "Enter Y maximum", 
            String.valueOf(area.getYMax())));
        form.addElement(new ElementInput("Z Min", "Enter Z minimum", 
            String.valueOf(area.getZMin())));
        form.addElement(new ElementInput("Z Max", "Enter Z maximum", 
            String.valueOf(area.getZMax())));

        // Protection settings - ensure all toggles are included
        JSONObject settings = area.getSettings();
        for (PermissionToggle toggle : PermissionToggle.getDefaultToggles()) {
            String permNode = toggle.getPermissionNode();
            form.addElement(new ElementToggle(toggle.getDisplayName(), 
                settings.optBoolean(permNode, toggle.getDefaultValue())));
        }

        // Messages
        form.addElement(new ElementInput("Enter Message", "Message shown when entering",
            area.getEnterMessage()));
        form.addElement(new ElementInput("Leave Message", "Message shown when leaving",
            area.getLeaveMessage()));

        return form;
    }

    public FormWindow createAreaListForm(String title, String content) {
        FormWindowSimple form = new FormWindowSimple(title, content);
        List<Area> areas = plugin.getAreas();
        
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
        for (String groupName : plugin.getGroupNames()) {
            JSONObject groupPerms = area.getGroupPermissions().optJSONObject(groupName, new JSONObject());
            form.addElement(new ElementToggle("Group: " + groupName, groupPerms.has("access")));
            form.addElement(new ElementToggle("  Build", groupPerms.optBoolean("build", true)));
            form.addElement(new ElementToggle("  Break", groupPerms.optBoolean("break", true)));
            form.addElement(new ElementToggle("  Interact", groupPerms.optBoolean("interact", true)));
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
            // ...existing cases...
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
        FormWindowCustom form = new FormWindowCustom("Area Settings: " + area.getName());
        
        // Basic settings
        form.addElement(new ElementToggle("Show Title", area.isShowTitle()));
        form.addElement(new ElementInput("Enter Message", "Message shown when entering", 
            area.getEnterMessage() != null ? area.getEnterMessage() : ""));
        form.addElement(new ElementInput("Leave Message", "Message shown when leaving", 
            area.getLeaveMessage() != null ? area.getLeaveMessage() : ""));
        
        // Advanced settings
        JSONObject settings = area.getSettings();
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
}
