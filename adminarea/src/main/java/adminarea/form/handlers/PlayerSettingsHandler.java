package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.constants.FormIds;
import adminarea.area.Area;
import adminarea.permissions.PermissionToggle;
import cn.nukkit.Player;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.element.ElementDropdown;
import cn.nukkit.form.element.ElementInput;
import cn.nukkit.form.element.ElementLabel;
import cn.nukkit.form.element.ElementToggle;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.form.window.FormWindowSimple;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Handler for player-specific permissions settings
 */
public class PlayerSettingsHandler {
    private final AdminAreaProtectionPlugin plugin;
    
    /**
     * Constructor
     * 
     * @param plugin Plugin instance
     */
    public PlayerSettingsHandler(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
    }
    
    /**
     * Open the player settings form for a player
     * 
     * @param player Player to open form for
     */
    public void open(Player player) {
        player.sendMessage("§cYou need to select an area first.");
        plugin.getFormModuleManager().openEditListForm(player);
    }
    
    /**
     * Open the player settings form for a player with a specific area
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
        
        openPlayerSelectionForm(player, area);
    }
    
    /**
     * Open the player selection form
     * 
     * @param player Player to open form for
     * @param area   Area to configure permissions for
     */
    private void openPlayerSelectionForm(Player player, Area area) {
        FormWindowCustom form = new FormWindowCustom("Player Permissions for " + area.getName());
        
        // Add header with instructions
        form.addElement(new ElementLabel("Select a player to manage their permissions in this area."));
        
        // Add dropdown with online players
        List<String> onlinePlayers = new ArrayList<>(plugin.getServer().getOnlinePlayers().values()
            .stream()
            .map(Player::getName)
            .collect(Collectors.toList()));
        onlinePlayers.add(0, "-- Select a player --");
        form.addElement(new ElementDropdown("Online Players", onlinePlayers, 0));
        
        // Add manual input option
        form.addElement(new ElementInput("Or enter player name manually", "Enter player name", ""));
        
        // Add list of players with existing permissions
        Map<String, Map<String, Boolean>> existingPerms = area.getPlayerPermissions();
        if (!existingPerms.isEmpty()) {
            StringBuilder playerList = new StringBuilder();
            for (String playerName : existingPerms.keySet()) {
                playerList.append("- ").append(playerName).append("\n");
            }
            form.addElement(new ElementLabel("Players with custom permissions:\n" + playerList.toString()));
        }
        
        // Add reset all permissions button
        form.addElement(new ElementToggle("Reset all player permissions", false));
        
        player.showFormWindow(form);
    }
    
    /**
     * Handle the response from the player selection form
     *
     * @param player   Player who submitted the form
     * @param area     Area being edited
     * @param response The form response
     */
    public void handlePlayerSelectionResponse(Player player, Area area, Object response) {
        if (response == null) {
            // Handle cancellation
            plugin.getFormModuleManager().openEditForm(player, area);
            return;
        }
        
        try {
            FormResponseCustom formResponse = (FormResponseCustom) response;
            
            // Check if reset all permissions was selected
            if (formResponse.getToggleResponse(4)) {
                // Reset all player permissions
                Map<String, Map<String, Boolean>> allPlayerPerms = area.getPlayerPermissions();
                if (!allPlayerPerms.isEmpty()) {
                    // Clear all player permissions
                    for (String playerName : allPlayerPerms.keySet()) {
                        plugin.getAreaManager().updatePlayerPermissions(area, playerName, new HashMap<>());
                    }
                    
                    player.sendMessage("§aReset all player permissions for area §e" + 
                            area.getName() + "§a.");
                    
                    // Return to edit area form
                    plugin.getFormModuleManager().openEditForm(player, area);
                    return;
                }
            }
            
            // Handle player selection
            String selectedPlayer = null;
            
            // Check dropdown selection first
            int dropdownIndex = formResponse.getDropdownResponse(1).getElementID();
            List<String> onlinePlayers = new ArrayList<>(plugin.getServer().getOnlinePlayers().values()
                .stream()
                .map(Player::getName)
                .collect(Collectors.toList()));
            onlinePlayers.add(0, "-- Select a player --");
            
            if (dropdownIndex > 0) { // Skip default option
                selectedPlayer = onlinePlayers.get(dropdownIndex);
            }
            
            // If no dropdown selection, check manual input
            if (selectedPlayer == null || selectedPlayer.isEmpty()) {
                String manualInput = formResponse.getInputResponse(2);
                if (manualInput != null && !manualInput.trim().isEmpty()) {
                    selectedPlayer = manualInput.trim();
                }
            }
            
            // Validate player selection
            if (selectedPlayer == null || selectedPlayer.trim().isEmpty()) {
                player.sendMessage("§cNo player selected.");
                plugin.getFormModuleManager().openEditForm(player, area);
                return;
            }
            
            // Open permission form for selected player
            openPlayerPermissionForm(player, area, selectedPlayer);
        } catch (Exception e) {
            plugin.getLogger().error("Error processing player selection form: " + e.getMessage());
            e.printStackTrace();
            player.sendMessage("§cAn error occurred while processing the form.");
            plugin.getFormModuleManager().openEditForm(player, area);
        }
    }
    
    /**
     * Open player permission form for a specific player
     * 
     * @param player      Player to open form for
     * @param area        Area to configure permissions for
     * @param targetPlayer Player to configure permissions for
     */
    private void openPlayerPermissionForm(Player player, Area area, String targetPlayer) {
        FormWindowCustom form = new FormWindowCustom("Permissions for " + targetPlayer);
        
        // Get current permissions for this player
        Map<String, Boolean> currentPerms = area.getPlayerPermissions(targetPlayer);
        if (currentPerms == null) {
            currentPerms = new HashMap<>();
        }
        
        // Add header with instructions
        form.addElement(new ElementLabel("Configure permissions for player: " + targetPlayer));
        
        // Add toggles for player-relevant permissions
        for (PermissionToggle toggle : PermissionToggle.getPlayerToggles()) {
            boolean currentValue = currentPerms.getOrDefault(toggle.getPermissionNode(), false);
            form.addElement(new ElementToggle(toggle.getDisplayName(), currentValue));
        }
        
        player.showFormWindow(form);
    }
    
    /**
     * Handle the response from the player permission form
     *
     * @param player       Player who submitted the form
     * @param area         Area being edited
     * @param targetPlayer Player being edited
     * @param response     The form response
     */
    public void handlePlayerPermissionResponse(Player player, Area area, String targetPlayer, Object response) {
        if (response == null) {
            // Handle cancellation
            plugin.getFormModuleManager().openEditForm(player, area);
            return;
        }
        
        try {
            FormResponseCustom formResponse = (FormResponseCustom) response;
            
            // Process permissions
            Map<String, Boolean> newPerms = new HashMap<>();
            int index = 1; // Skip the header label
            for (PermissionToggle toggle : PermissionToggle.getPlayerToggles()) {
                boolean value = formResponse.getToggleResponse(index);
                newPerms.put(toggle.getPermissionNode(), value);
                index++;
            }
            
            // Update the player permissions
            plugin.getAreaManager().updatePlayerPermissions(area, targetPlayer, newPerms);
            
            player.sendMessage("§aUpdated permissions for player §e" + 
                    targetPlayer + "§a.");
            
            // Return to edit area form
            plugin.getFormModuleManager().openEditForm(player, area);
        } catch (Exception e) {
            plugin.getLogger().error("Error processing player permissions form: " + e.getMessage());
            e.printStackTrace();
            player.sendMessage("§cAn error occurred while processing the form.");
            plugin.getFormModuleManager().openEditForm(player, area);
        }
    }
}
