package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.constants.FormIds;
import adminarea.area.Area;
import cn.nukkit.Player;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.element.ElementDropdown;
import cn.nukkit.form.element.ElementInput;
import cn.nukkit.form.element.ElementLabel;
import cn.nukkit.form.element.ElementToggle;
import cn.nukkit.form.handler.FormResponseHandler;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.form.window.FormWindowSimple;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/**
 * Handler for LuckPerms settings forms
 */
public class LuckPermsSettingsHandler {
    private static final String TRACK_DATA_KEY = "_selected_track";
    private final AdminAreaProtectionPlugin plugin;
    
    /**
     * Constructor
     * 
     * @param plugin Plugin instance
     */
    public LuckPermsSettingsHandler(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
    }
    
    /**
     * Open the LuckPerms settings form for a player
     * 
     * @param player Player to open form for
     */
    public void open(Player player) {
        player.sendMessage("§cYou need to select an area first.");
        plugin.getFormModuleManager().openEditListForm(player);
    }
    
    /**
     * Open the LuckPerms settings form for a player with a specific area
     * 
     * @param player Player to open form for
     * @param area   Area to open form for
     */
    public void open(Player player, Area area) {
        FormWindowSimple form = new FormWindowSimple(
                "LuckPerms Settings for " + area.getName(),
                "Select an option to manage LuckPerms settings for this area.");
        
        form.addButton(new ElementButton("Track Permissions"));
        
        player.showFormWindow(form);
        
        // The form response will be handled by the FormResponseListener
    }
    
    /**
     * Open the track selection form for a player
     * 
     * @param player Player to open form for
     * @param area   Area to open form for
     */
    private void openTrackSelectionForm(Player player, Area area) {
        List<String> tracks = new ArrayList<>();
        
        tracks.add("default");
        tracks.add("vip");
        tracks.add("admin");
        
        FormWindowCustom form = new FormWindowCustom("Select Track");
        
        if (tracks.isEmpty()) {
            form.addElement(new ElementLabel("No tracks available."));
        } else {
            form.addElement(new ElementLabel("Select a track to manage its permissions:"));
            form.addElement(new ElementDropdown("Track", tracks, 0));
        }
        
        player.showFormWindow(form);
        
        // The form response will be handled by the FormResponseListener
    }
    
    /**
     * Open the track permission form for a player
     * 
     * @param player      Player to open form for
     * @param area        Area to open form for
     * @param trackName   Name of the track to manage
     */
    private void openTrackPermissionForm(Player player, Area area, String trackName) {
        String currentPermission = getTrackPermission(area, trackName);
        
        FormWindowCustom form = new FormWindowCustom("Track Permissions for " + trackName);
        
        form.addElement(new ElementLabel("Configure permissions for track: " + trackName));
        form.addElement(new ElementLabel("Current permission: " + 
                (currentPermission != null && !currentPermission.isEmpty() ? currentPermission : "None")));
        form.addElement(new ElementInput("New permission", "Enter permission node", 
                currentPermission != null ? currentPermission : ""));
        form.addElement(new ElementToggle("Delete permission", false));
        
        player.showFormWindow(form);
        
        // The form response will be handled by the FormResponseListener
    }
    
    /**
     * Helper method to get track permission from area
     * 
     * @param area       The area
     * @param trackName  The track name
     * @return The permission for the track, or null if none
     */
    private String getTrackPermission(Area area, String trackName) {
        Map<String, String> trackPermissions = getTrackPermissionsMap(area);
        return trackPermissions != null ? trackPermissions.get(trackName) : null;
    }
    
    /**
     * Helper method to get track permissions map from area
     * 
     * @param area The area
     * @return Map of track permissions
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> getTrackPermissionsMap(Area area) {
        return plugin.getAreaManager().getTrackPermissions(area);
    }
    
    /**
     * Process the response from the main LuckPerms settings form
     * 
     * @param player    Player who submitted the form
     * @param area      Area being edited
     * @param response  The form response
     */
    public void handleMainFormResponse(Player player, Area area, Object response) {
        if (response == null) {
            // Handle cancellation
            plugin.getFormModuleManager().openEditForm(player, area);
            return;
        }
        
        try {
            int buttonId = ((FormResponseSimple) response).getClickedButtonId();
            
            if (buttonId == 0) {
                openTrackSelectionForm(player, area);
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error processing LuckPerms settings form: " + e.getMessage());
            e.printStackTrace();
            player.sendMessage("§cAn error occurred while processing the form.");
            plugin.getFormModuleManager().openEditForm(player, area);
        }
    }
    
    /**
     * Process the response from the track selection form
     * 
     * @param player    Player who submitted the form
     * @param area      Area being edited
     * @param response  The form response
     */
    public void handleTrackSelectionResponse(Player player, Area area, Object response) {
        if (response == null) {
            // Handle cancellation
            plugin.getFormModuleManager().openEditForm(player, area);
            return;
        }
        
        try {
            List<String> tracks = new ArrayList<>();
            
            tracks.add("default");
            tracks.add("vip");
            tracks.add("admin");
            
            if (tracks.isEmpty()) {
                player.sendMessage("§cNo tracks available.");
                plugin.getFormModuleManager().openEditForm(player, area);
                return;
            }
            
            FormResponseCustom customResponse = (FormResponseCustom) response;
            int trackIndex = customResponse.getDropdownResponse(1).getElementID();
            
            if (trackIndex >= 0 && trackIndex < tracks.size()) {
                String selectedTrack = tracks.get(trackIndex);
                openTrackPermissionForm(player, area, selectedTrack);
            } else {
                player.sendMessage("§cInvalid track selection.");
                plugin.getFormModuleManager().openEditForm(player, area);
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error processing track selection: " + e.getMessage());
            e.printStackTrace();
            player.sendMessage("§cAn error occurred while selecting a track.");
            plugin.getFormModuleManager().openEditForm(player, area);
        }
    }
    
    /**
     * Process the response from the track permission form
     * 
     * @param player     Player who submitted the form
     * @param area       Area being edited
     * @param trackName  Track being edited
     * @param response   The form response
     */
    public void handleTrackPermissionResponse(Player player, Area area, String trackName, Object response) {
        if (response == null) {
            // Handle cancellation
            plugin.getFormModuleManager().openEditForm(player, area);
            return;
        }
        
        try {
            FormResponseCustom customResponse = (FormResponseCustom) response;
            
            // Check if delete permission was selected (toggle at index 3)
            Boolean shouldDelete = customResponse.getToggleResponse(3);
            if (shouldDelete) {
                // Delete permission
                String permission = getTrackPermission(area, trackName);
                if (permission != null && !permission.isEmpty()) {
                    Area updatedArea = plugin.getAreaManager().getArea(area.getName());
                    if (updatedArea == null) {
                        player.sendMessage("§cArea no longer exists.");
                        plugin.getFormModuleManager().openEditListForm(player);
                        return;
                    }
                    
                    // Remove the track permission
                    plugin.getAreaManager().removeTrackPermission(updatedArea, trackName);
                    
                    player.sendMessage("§aPermission for track §e" + trackName + 
                            "§a deleted successfully.");
                    
                    // Return to edit area form
                    plugin.getFormModuleManager().openEditForm(player, updatedArea);
                } else {
                    player.sendMessage("§cNo permission to delete for track §e" + trackName);
                    // Return to edit area form with original area
                    plugin.getFormModuleManager().openEditForm(player, area);
                }
                return;
            }
            
            // Check if new permission was provided (input at index 2)
            String newPermission = customResponse.getInputResponse(2);
            if (newPermission != null && !newPermission.isEmpty()) {
                // Update permission
                Area updatedArea = plugin.getAreaManager().getArea(area.getName());
                if (updatedArea == null) {
                    player.sendMessage("§cArea no longer exists.");
                    plugin.getFormModuleManager().openEditListForm(player);
                    return;
                }
                
                // Set the track permission
                plugin.getAreaManager().setTrackPermission(updatedArea, trackName, newPermission);
                
                player.sendMessage("§aPermission for track §e" + trackName + 
                        "§a updated to: §f" + newPermission);
                
                // Return to edit area form
                plugin.getFormModuleManager().openEditForm(player, updatedArea);
            } else {
                // User didn't make any changes
                player.sendMessage("§cNo changes were made to the track permission.");
                plugin.getFormModuleManager().openEditForm(player, area);
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error processing track permission form: " + e.getMessage());
            e.printStackTrace();
            player.sendMessage("§cAn error occurred while processing the LuckPerms track permission form.");
            // Return to edit area form with original area
            plugin.getFormModuleManager().openEditForm(player, area);
        }
    }
} 