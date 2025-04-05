package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.constants.FormIds;
import adminarea.area.Area;
import adminarea.permissions.PermissionToggle;
import cn.nukkit.Player;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.element.ElementLabel;
import cn.nukkit.form.element.ElementToggle;
import cn.nukkit.form.response.FormResponseData;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.form.window.FormWindowSimple;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Handler for group-specific permissions settings
 */
public class GroupPermissionsHandler {
    private final AdminAreaProtectionPlugin plugin;
    
    /**
     * Constructor
     * 
     * @param plugin Plugin instance
     */
    public GroupPermissionsHandler(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
    }
    
    /**
     * Open the group settings form for a player
     * 
     * @param player Player to open form for
     */
    public void open(Player player) {
        player.sendMessage("§cYou need to select an area first.");
        plugin.getFormModuleManager().openEditListForm(player);
    }
    
    /**
     * Open the group settings form for a player with a specific area
     * 
     * @param player Player to open form for
     * @param area   Area to open form for
     */
    public void open(Player player, Area area) {
        if (area == null) {
            player.sendMessage("§cNo area selected.");
            plugin.getFormModuleManager().openEditListForm(player);
            return;
        }
        
        openGroupSelectionForm(player, area);
    }
    
    /**
     * Open the group selection form
     * 
     * @param player Player to open form for
     * @param area   Area to configure permissions for
     */
    private void openGroupSelectionForm(Player player, Area area) {
        FormWindowSimple form = new FormWindowSimple(
                "Select Group - " + area.getName(),
                "Choose a group to edit permissions for:");
        
        // Get available groups from LuckPerms
        List<String> groups = new ArrayList<>();
        try {
            Set<String> groupNames = plugin.getGroupNames();
            if (groupNames != null && !groupNames.isEmpty()) {
                for (String groupName : groupNames) {
                    groups.add(groupName);
                    form.addButton(new ElementButton(groupName));
                }
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error accessing LuckPerms groups: " + e.getMessage());
        }
        
        if (groups.isEmpty()) {
            form.addButton(new ElementButton("No groups available"));
        }
        
        // Show form to player
        player.showFormWindow(form);
        
        // The form response will be handled by the FormResponseListener in the plugin
        // We'll implement response handling in the next update
    }
    
    /**
     * Open group permission form for a specific group
     * 
     * @param player      Player to open form for
     * @param area        Area to configure permissions for
     * @param groupName   Group to configure permissions for
     */
    private void openGroupPermissionForm(Player player, Area area, String groupName) {
        FormWindowCustom form = new FormWindowCustom("Group Permissions: " + groupName);
        
        // Get current permissions for this group
        Map<String, Boolean> currentPerms = new HashMap<>();
        Map<String, Map<String, Boolean>> groupPerms = area.getGroupPermissions();
        if (groupPerms != null && groupPerms.containsKey(groupName)) {
            Map<String, Boolean> perms = groupPerms.get(groupName);
            if (perms != null) {
                currentPerms.putAll(perms);
            }
        }
        
        // Add header with instructions
        form.addElement(new ElementLabel("Configure permissions for group: " + groupName));
        
        // Add toggles for relevant permissions
        for (PermissionToggle toggle : PermissionToggle.getDefaultToggles()) {
            boolean currentValue = currentPerms.getOrDefault(toggle.getPermissionNode(), false);
            form.addElement(new ElementToggle(toggle.getDisplayName(), currentValue));
        }
        
        // Add toggle for deleting permissions
        form.addElement(new ElementToggle("Delete all permissions for this group", false));
        
        // Show form to player
        player.showFormWindow(form);
        
        // The form response will be handled by the FormResponseListener in the plugin
    }
    
    /**
     * Process the response from the group selection form
     * 
     * @param player    Player who submitted the form
     * @param area      Area being edited
     * @param response  The form response
     */
    public void handleGroupSelectionResponse(Player player, Area area, Object response) {
        if (response == null) {
            // Handle cancellation
            plugin.getFormModuleManager().openEditForm(player, area);
            return;
        }
        
        try {
            int buttonId = ((cn.nukkit.form.response.FormResponseSimple) response).getClickedButtonId();
            
            // Get available groups
            List<String> groups = new ArrayList<>(plugin.getGroupNames());
            
            if (groups.isEmpty() || buttonId >= groups.size()) {
                player.sendMessage("§cNo groups available or invalid selection.");
                plugin.getFormModuleManager().openEditForm(player, area);
                return;
            }
            
            String selectedGroup = groups.get(buttonId);
            
            // Store the selected group in form tracking data
            plugin.getFormIdMap().put(player.getName() + "_selected_group", 
                new adminarea.data.FormTrackingData(selectedGroup, System.currentTimeMillis()));
            
            if (plugin.isDebugMode()) {
                plugin.debug("Stored selected group in tracking data: " + selectedGroup);
            }
            
            openGroupPermissionForm(player, area, selectedGroup);
        } catch (Exception e) {
            plugin.getLogger().error("Error processing group selection: " + e.getMessage());
            e.printStackTrace();
            player.sendMessage("§cAn error occurred while selecting a group.");
            plugin.getFormModuleManager().openEditForm(player, area);
        }
    }
    
    /**
     * Process the response from the group permission form
     * 
     * @param player     Player who submitted the form
     * @param area       Area being edited
     * @param groupName  Group being edited
     * @param response   The form response
     */
    public void handleGroupPermissionResponse(Player player, Area area, String groupName, Object response) {
        if (response == null) {
            // Handle cancellation
            plugin.getFormModuleManager().openEditForm(player, area);
            return;
        }
        
        try {
            Map<Integer, Object> responseData = ((cn.nukkit.form.response.FormResponseCustom) response).getResponses();
            
            // Check if delete permissions was selected (last toggle)
            int lastIndex = PermissionToggle.getDefaultToggles().size() + 1;
            if (responseData.containsKey(lastIndex) && responseData.get(lastIndex) instanceof Boolean && (Boolean) responseData.get(lastIndex)) {
                // Delete all permissions for this group
                // Get fresh copy of area to avoid concurrent modification issues
                Area updatedArea = plugin.getAreaManager().getArea(area.getName());
                if (updatedArea == null) {
                    player.sendMessage("§cArea no longer exists.");
                    plugin.getFormModuleManager().openEditListForm(player);
                    return;
                }
                
                // Remove group permissions
                Map<String, Map<String, Boolean>> groupPerms = updatedArea.getGroupPermissions();
                if (groupPerms != null) {
                    groupPerms.remove(groupName);
                    // Update area with modified permissions
                    plugin.updateArea(updatedArea);
                }
                
                player.sendMessage("§aDeleted all permissions for group §e" + 
                        groupName + "§a in area §e" + 
                        area.getName() + "§a.");
                
                // Return to edit area form
                plugin.getFormModuleManager().openEditForm(player, updatedArea);
                return;
            }
            
            // Process permissions
            Map<String, Boolean> newPerms = new HashMap<>();
            int index = 1; // Skip the header label
            for (PermissionToggle toggle : PermissionToggle.getDefaultToggles()) {
                if (responseData.containsKey(index) && responseData.get(index) instanceof Boolean) {
                    Boolean value = (Boolean) responseData.get(index);
                    newPerms.put(toggle.getPermissionNode(), value != null ? value : false);
                }
                index++;
            }
            
            // Update area with new group permissions
            Area updatedArea = plugin.getAreaManager().getArea(area.getName());
            if (updatedArea == null) {
                player.sendMessage("§cArea no longer exists.");
                plugin.getFormModuleManager().openEditListForm(player);
                return;
            }
            
            // Update group permissions
            Map<String, Map<String, Boolean>> groupPerms = updatedArea.getGroupPermissions();
            if (groupPerms == null) {
                groupPerms = new HashMap<>();
            }
            groupPerms.put(groupName, newPerms);
            
            // Update the area
            plugin.updateArea(updatedArea);
            
            player.sendMessage("§aUpdated permissions for group §e" + 
                    groupName + "§a.");
            
            // Return to edit area form
            plugin.getFormModuleManager().openEditForm(player, updatedArea);
        } catch (Exception e) {
            plugin.getLogger().error("Error processing group permissions form: " + e.getMessage());
            e.printStackTrace();
            player.sendMessage("§cAn error occurred while processing the form.");
            plugin.getFormModuleManager().openEditForm(player, area);
        }
    }
}
