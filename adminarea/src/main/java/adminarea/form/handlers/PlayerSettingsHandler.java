package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaBuilder;
import adminarea.area.AreaDTO;
import adminarea.constants.FormIds;
import adminarea.data.FormTrackingData;
import adminarea.event.PlayerPermissionChangeEvent;
import adminarea.permissions.PermissionToggle;
import cn.nukkit.Player;
import cn.nukkit.form.element.*;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.form.window.FormWindowSimple;

import java.util.*;
import java.util.stream.Collectors;

public class PlayerSettingsHandler extends BaseFormHandler {
    private static final String PLAYER_DATA_KEY = "_player";

    public PlayerSettingsHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
        validateFormId();
    }

    @Override
    public String getFormId() {
        return FormIds.PLAYER_SETTINGS;
    }

    @Override
    public FormWindow createForm(Player player) {
        FormTrackingData areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
        if (areaData == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.error.noAreaSelected"));
            return null;
        }

        Area area = plugin.getArea(areaData.getFormId());
        if (area == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.error.areaNotFound"));
            return null;
        }

        FormTrackingData playerData = plugin.getFormIdMap().get(player.getName() + PLAYER_DATA_KEY);
        
        if (playerData == null) {
            // Show player selection form with dropdown and manual input
            FormWindowCustom form = new FormWindowCustom("Player Permissions - " + area.getName());
            
            // Add header with instructions
            form.addElement(new ElementLabel("§2Configure Player Permissions\n§7Select a player or enter a name manually"));
            
            // Add dropdown with online players
            List<String> onlinePlayers = new ArrayList<>(plugin.getServer().getOnlinePlayers().values()
                .stream()
                .map(Player::getName)
                .collect(Collectors.toList()));
            onlinePlayers.add(0, "-- Select Player --"); // Add default option
            form.addElement(new ElementDropdown("Online Players", onlinePlayers));
            
            // Add manual input option
            form.addElement(new ElementInput("Or enter player name manually:", "Player name", ""));
            
            return form;
        } else {
            // Show permission editing form for selected player
            return createPlayerPermissionForm(area, playerData.getFormId());
        }
    }

    private FormWindowCustom createPlayerPermissionForm(Area area, String playerName) {
        FormWindowCustom form = new FormWindowCustom("Edit " + playerName + " Permissions");

        Map<String, Boolean> currentPerms = area.getPlayerPermissions(playerName);
        
        // Add header with clear instructions
        form.addElement(new ElementLabel(
            "§2Player Permissions for " + playerName + "\n" +
            "§7These permissions will override track and group permissions.\n" +
            "§7Area permission changes will reset these settings."
        ));
        
        // Add toggles for player-relevant permissions
        for (PermissionToggle toggle : PermissionToggle.getPlayerToggles()) {
            boolean currentValue = currentPerms.getOrDefault(toggle.getPermissionNode(), area.getToggleState(toggle.getPermissionNode()));
            String description = plugin.getLanguageManager().get(
                "gui.permissions.toggles." + toggle.getPermissionNode(),
                Map.of("player", playerName)
            );
            form.addElement(new ElementToggle(
                toggle.getDisplayName() + "\n§7" + description,
                currentValue
            ));
        }

        return form;
    }

    @Override
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        handleCancel(player);
    }

    @Override
    protected void handleCustomResponse(Player player, FormResponseCustom response) {
        if (response == null) {
            handleCancel(player);
            return;
        }

        try {
            FormTrackingData areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
            FormTrackingData playerData = plugin.getFormIdMap().get(player.getName() + PLAYER_DATA_KEY);

            if (areaData == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.form.invalidSession"));
                return;
            }

            Area area = plugin.getArea(areaData.getFormId());
            if (area == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound"));
                return;
            }

            if (playerData == null) {
                // Handle player selection
                String selectedPlayer = null;
                
                // Check dropdown selection first
                int dropdownIndex = response.getDropdownResponse(1).getElementID();
                if (dropdownIndex > 0) { // Skip default option
                    List<String> onlinePlayers = new ArrayList<>(plugin.getServer().getOnlinePlayers().values()
                        .stream()
                        .map(Player::getName)
                        .collect(Collectors.toList()));
                    selectedPlayer = onlinePlayers.get(dropdownIndex - 1); // Adjust for default option
                }
                
                // If no dropdown selection, check manual input
                if (selectedPlayer == null || selectedPlayer.isEmpty()) {
                    selectedPlayer = response.getInputResponse(2);
                }
                
                // Validate player selection
                if (selectedPlayer == null || selectedPlayer.trim().isEmpty()) {
                    player.sendMessage(plugin.getLanguageManager().get("messages.form.error.invalidInput"));
                    return;
                }

                plugin.getFormIdMap().put(
                    player.getName() + PLAYER_DATA_KEY,
                    new FormTrackingData(selectedPlayer.trim(), System.currentTimeMillis())
                );
                
                plugin.getGuiManager().openFormById(player, FormIds.PLAYER_SETTINGS, area);
                return;
            }

            // Get current DTO
            AreaDTO currentDTO = area.toDTO();
            
            // Process permissions
            Map<String, Boolean> newPerms = new HashMap<>();
            int index = 1; // Skip the header label
            for (PermissionToggle toggle : PermissionToggle.getPlayerToggles()) {
                Boolean value = response.getToggleResponse(index);
                newPerms.put(toggle.getPermissionNode(), value != null ? value : false);
                index++;
            }

            // Update player permissions
            String targetPlayer = playerData.getFormId();
            Map<String, Map<String, Boolean>> playerPerms = new HashMap<>(currentDTO.playerPermissions());
            Map<String, Boolean> oldPerms = playerPerms.getOrDefault(targetPlayer, new HashMap<>());
            playerPerms.put(targetPlayer, newPerms);

            Area updatedArea = AreaBuilder.fromDTO(currentDTO)
                .playerPermissions(playerPerms)
                .build();

            // Fire event
            PlayerPermissionChangeEvent event = new PlayerPermissionChangeEvent(area, targetPlayer, oldPerms, newPerms);
            plugin.getServer().getPluginManager().callEvent(event);

            if (event.isCancelled()) {
                return;
            }

            // Update area in plugin
            plugin.updateArea(updatedArea);
            
            player.sendMessage(plugin.getLanguageManager().get("messages.form.playerPermissionsUpdated",
                Map.of("player", targetPlayer, "area", area.getName())));

            // Clear player selection and return to edit menu
            plugin.getFormIdMap().remove(player.getName() + PLAYER_DATA_KEY);
            plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, updatedArea);

        } catch (Exception e) {
            plugin.getLogger().error("Error handling player permissions response", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
            handleCancel(player);
        }
    }

    @Override
    public void handleCancel(Player player) {
        plugin.getFormIdMap().remove(player.getName() + PLAYER_DATA_KEY);
        plugin.getGuiManager().openMainMenu(player);
    }
} 