package adminarea;

import cn.nukkit.event.Listener;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.player.PlayerFormRespondedEvent;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowSimple;
import cn.nukkit.level.Position;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.track.TrackManager;
import cn.nukkit.Player;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.response.FormResponseCustom;
import org.json.JSONObject;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.stream.Collectors;

public class FormResponseListener implements Listener {

    private final AdminAreaProtectionPlugin plugin;
    private AreaCommand areaCommand;
    private final HashMap<String, Area> editAreaMap = new HashMap<>();

    public FormResponseListener(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        this.areaCommand = new AreaCommand(plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFormResponded(PlayerFormRespondedEvent event) {
        // Early null check: If response is null (i.e. the form is closed without response), return.
        if (event.getResponse() == null) {
            plugin.getLogger().info("Form closed without response.");
            return;
        }
        Player player = event.getPlayer();
        FormWindow window = event.getWindow();
        String playerName = player.getName();
        HashMap<String, String> formIdMap = plugin.getFormIdMap();
        String storedId = formIdMap.get(playerName);
        plugin.getLogger().info("Stored form ID for player " + playerName + ": " + storedId);
        
        // If a custom form is received, ignore the stored ID and use the form title.
        if (window instanceof FormWindowCustom) {
            formIdMap.remove(playerName);
            String title = ((FormWindowCustom) window).getTitle();
            // Branch for the Create Area form.
            if (title.equals("Create Area")) {
                // Retrieve positions set via /area pos1 and /area pos2.
                Position[] positions = plugin.getPlayerPositions().remove(playerName);
                if (!(event.getResponse() instanceof FormResponseCustom)) {
                    plugin.getLogger().warning("Unexpected response type");
                    return;
                }
                FormResponseCustom response = (FormResponseCustom) event.getResponse();
                HashMap<Integer, Object> responses = response.getResponses();
                List<Object> data = new ArrayList<>(responses.values());
                try {
                    // First fields: index 0-area name, 1-priority, 2-global toggle,
                    // indices 3-8: positions, so toggles start at index 9.
                    String name = data.get(0).toString().trim();
                    if (name.isEmpty()) {
                        player.sendMessage("§cArea name cannot be empty.");
                        return;
                    }
                    int priority = Integer.parseInt(data.get(1).toString());
                    boolean globalArea = (Boolean) data.get(2);
                    int posOffset = 3; // indices 3-8 for positions
                    int xMin, xMax, yMin, yMax, zMin, zMax;
                    if (globalArea) {
                        // For global area, use numeric inputs from the form (or defaults)
                        xMin = Integer.parseInt(data.get(posOffset + 0).toString());
                        yMin = Integer.parseInt(data.get(posOffset + 1).toString());
                        zMin = Integer.parseInt(data.get(posOffset + 2).toString());
                        xMax = Integer.parseInt(data.get(posOffset + 3).toString());
                        yMax = Integer.parseInt(data.get(posOffset + 4).toString());
                        zMax = Integer.parseInt(data.get(posOffset + 5).toString());
                    } else {
                        // Use stored positions if not global
                        if (positions == null || positions[0] == null || positions[1] == null) {
                            player.sendMessage("§cPositions not set.");
                            return;
                        }
                        xMin = Math.min((int) positions[0].getX(), (int) positions[1].getX());
                        xMax = Math.max((int) positions[0].getX(), (int) positions[1].getX());
                        yMin = Math.min((int) positions[0].getY(), (int) positions[1].getY());
                        yMax = Math.max((int) positions[0].getY(), (int) positions[1].getY());
                        zMin = Math.min((int) positions[0].getZ(), (int) positions[1].getZ());
                        zMax = Math.max((int) positions[0].getZ(), (int) positions[1].getZ());
                    }

                    // Now, process permission toggles.
                    // Assume form elements are added in the same order as PermissionToggle.getDefaultToggles()
                    int toggleStartIndex = posOffset + 6;
                    PermissionToggle[] toggles = PermissionToggle.getDefaultToggles();
                    org.json.JSONObject settings = new org.json.JSONObject();
                    for (int i = 0; i < toggles.length; i++) {
                        boolean value = (Boolean) data.get(toggleStartIndex + i);
                        settings.put(toggles[i].getPermissionNode(), value);
                    }
                    // At this point the regular toggles are set.
                    // Later, if a LuckPerms override form applies, it will adjust these values.
                    
                    String world = player.getLevel().getFolderName();
                    Area area = new Area(name, world, xMin, xMax, yMin, yMax, zMin, zMax, priority, settings.optBoolean("show.title", true));
                    area.setSettings(settings);
                    plugin.getLogger().info("Adding area: " + area.getName());
                    plugin.addArea(area);
                    player.sendMessage("Area " + name + " created successfully.");
                } catch(Exception e) {
                    player.sendMessage("Error creating area: " + e.getMessage());
                    e.printStackTrace();
                }
                return;
            }
            // Branch for the Edit Area form.
            else if (title.startsWith("Edit Area -")) {
                if (!(event.getResponse() instanceof FormResponseCustom)) {
                    plugin.getLogger().warning("Unexpected response type: " + event.getResponse().getClass().getName());
                    return;
                }
                FormResponseCustom response = (FormResponseCustom) event.getResponse();
                HashMap<Integer, Object> responses = response.getResponses();
                List<Object> data = new ArrayList<>(responses.values());
                try {
                    // Extract area name from the title instead of using editAreaMap.
                    String originalName = title.replace("Edit Area - ", "").trim();
                    Area area = null;
                    for (Area a : plugin.getAreas()) {
                        if (a.getName().equalsIgnoreCase(originalName)) {
                            area = a;
                            break;
                        }
                    }
                    if (area == null) {
                        player.sendMessage("Area not found.");
                        return;
                    }
                    // First fields: index 0-area name, 1-priority; toggles start at index 2.
                    String areaName = data.get(0).toString().trim();
                    int priority = Integer.parseInt(data.get(1).toString());
                    PermissionToggle[] toggles = PermissionToggle.getDefaultToggles();
                    org.json.JSONObject settings = new org.json.JSONObject();
                    for (int i = 0; i < toggles.length; i++) {
                        boolean value = (Boolean) data.get(2 + i);
                        settings.put(toggles[i].getPermissionNode(), value);
                    }
                    area.setPriority(priority);
                    area.setSettings(settings);
                    // Save back to the database.
                    plugin.updateArea(area);
                    player.sendMessage("Area " + area.getName() + " updated successfully.");
                } catch(Exception e) {
                    player.sendMessage("Error updating area: " + e.getMessage());
                    e.printStackTrace();
                }
                return;
            }
        }
        
        // Process main menu form (should be of type FormWindowSimple).
        if (window instanceof FormWindowSimple && "main_menu".equals(storedId)) {
            formIdMap.remove(playerName);
            if (event.getResponse() == null) {
                plugin.getLogger().info("Main menu closed without response.");
                return;
            }
            FormResponseSimple response = (FormResponseSimple) event.getResponse();
            int btnId = response.getClickedButtonId();
            plugin.getLogger().info("Main menu button clicked: " + btnId);
            GuiManager gui = new GuiManager(plugin);
            switch (btnId) {
                case 0:
                    gui.openCreateForm(player);
                    break;
                case 1:
                    // Edit functionality to be selected via /area edit <name>.
                    openEditList(player);
                    break;
                case 2:
                    // Delete functionality to be selected via /area delete <name>.
                    openDeleteList(player);
                    break;
                case 3:
                    // If admin, the extra Player Areas button exists
                    if (player.hasPermission("adminarea.admin.manageplayerareas")) {
                        openPlayerAreaManagement(player);
                    } else {
                        plugin.getLogger().info("Listing areas.");
                        // Backup: list all areas for non-admins
                        new AreaCommand(plugin).sendAreaList(player);
                    }
                    break;
                default:
                    player.sendMessage("Unrecognized option.");
            }
            return;
        }
        // Handle response from edit area selection list
        else if (window instanceof FormWindowSimple && "edit_area_select".equals(storedId)) {
            formIdMap.remove(playerName);
            FormResponseSimple response = (FormResponseSimple) event.getResponse();
            if (response != null) {
                int buttonId = response.getClickedButtonId();
                List<Area> areas = plugin.getAreas();
                if (buttonId >= 0 && buttonId < areas.size()) {
                    Area selectedArea = areas.get(buttonId);
                    openEditOptions(player, selectedArea);
                }
            }
            return;
        }
        // Branch for handling Edit Options form response.
        else if (window instanceof FormWindowSimple && "edit_options".equals(storedId)) {
            formIdMap.remove(playerName);
            FormResponseSimple response = (FormResponseSimple) event.getResponse();
            int btnId = response.getClickedButtonId();
            Area area = editAreaMap.get(playerName);
            if (area == null) return;
            GuiManager gui = new GuiManager(plugin);
            if (btnId == 0) {
                // Edit Basic Settings.
                gui.openEditForm(player, area);
            } else if (btnId == 1) {
                // Edit LuckPerms Overrides.
                gui.openLuckPermsOverrideForm(player, area);
            }
            editAreaMap.remove(playerName);
            return;
        }
        // Handle response from delete area selection list
        else if (window instanceof FormWindowSimple && "delete_area_select".equals(storedId)) {
            formIdMap.remove(playerName);
            FormResponseSimple response = (FormResponseSimple) event.getResponse();
            if (response != null) {
                int buttonId = response.getClickedButtonId();
                List<Area> areas = plugin.getAreas();
                if (buttonId >= 0 && buttonId < areas.size()) {
                    Area areaToDelete = areas.get(buttonId);
                    plugin.removeArea(areaToDelete);
                    player.sendMessage("§aArea '" + areaToDelete.getName() + "' deleted successfully.");
                }
            }
        }
        // Branch for handling Player Areas management response
        else if (window instanceof FormWindowSimple && "player_area_manage".equals(storedId)) {
            formIdMap.remove(playerName);
            FormResponseSimple response = (FormResponseSimple) event.getResponse();
            int btnId = response.getClickedButtonId();
            List<Area> areas = plugin.getAreas();
            if (btnId < areas.size()) {
                Area selected = areas.get(btnId);
                // Compute the center point of the area
                double centerX = (selected.getXMin() + selected.getXMax()) / 2.0;
                double centerY = (selected.getYMin() + selected.getYMax()) / 2.0;
                double centerZ = (selected.getZMin() + selected.getZMax()) / 2.0;
                Position tpPos = new Position(centerX, centerY, centerZ, player.getLevel());
                player.teleport(tpPos);
                player.sendMessage("Teleported to player area: " + selected.getName());
            }
            return;
        }
        // Branch for LuckPerms override track selection form.
        else if (window instanceof FormWindowSimple && "override_track_select".equals(storedId)) {
            formIdMap.remove(playerName);
            FormResponseSimple response = (FormResponseSimple) event.getResponse();
            int btnId = response.getClickedButtonId();
            // Retrieve area from temporary storage.
            String areaName = formIdMap.get(playerName + "_area");
            if (areaName == null) return;
            // Assume plugin.getAreas() contains the area; otherwise, adjust retrieval.
            Area area = null;
            for (Area a : plugin.getAreas()) {
                if (a.getName().equals(areaName)) {
                    area = a;
                    break;
                }
            }
            if (area == null) return;
            // Retrieve selected track name (we assume the button index corresponds to the list order from LuckPerms API).
            LuckPermsOverrideForm lpForm = new LuckPermsOverrideForm(plugin);
            LuckPerms lp = plugin.getLuckPermsApi();
            List<String> trackNames = lp.getTrackManager().getLoadedTracks().stream()
                    .map(track -> track.getName())
                    .collect(Collectors.toList());
            if (btnId < 0 || btnId >= trackNames.size()) return;
            String trackName = trackNames.get(btnId);
            // Proceed to group selection.
            lpForm.openGroupSelection(player, area, trackName);
            return;
        }
        // Branch for LuckPerms override group selection form.
        else if (window instanceof FormWindowSimple && "override_group_select".equals(storedId)) {
            formIdMap.remove(playerName);
            FormResponseSimple response = (FormResponseSimple) event.getResponse();
            int btnId = response.getClickedButtonId();
            String areaName = formIdMap.get(playerName + "_area");
            String trackName = formIdMap.get(playerName + "_track");
            if (areaName == null || trackName == null) return;
            Area area = null;
            for (Area a : plugin.getAreas()) {
                if (a.getName().equals(areaName)) {
                    area = a;
                    break;
                }
            }
            if (area == null) return;
            // Retrieve selected group from the list provided by LuckPermsOverrideForm.
            // We assume the groups list order is same as provided in openGroupSelection.
            LuckPerms lp = plugin.getLuckPermsApi();
            TrackManager tm = lp.getTrackManager();
            List<String> groups = new ArrayList<>(tm.getTrack(trackName).getGroups());
            if (btnId < 0 || btnId >= groups.size()) return;
            String group = groups.get(btnId);
            // Proceed to open the permission override edit form.
            LuckPermsOverrideForm lpForm = new LuckPermsOverrideForm(plugin);
            lpForm.openPermissionEditForm(player, area, trackName, group);
            return;
        }
        // Branch for LuckPerms override edit form submission.
        else if (window instanceof FormWindowCustom && "override_edit".equals(storedId)) {
            formIdMap.remove(playerName);
            if (!(event.getResponse() instanceof FormResponseCustom)) return;
            FormResponseCustom response = (FormResponseCustom) event.getResponse();
            LuckPermsOverrideForm lpForm = new LuckPermsOverrideForm(plugin);
            lpForm.processPermissionOverrideResponse(player, response);
            return;
        }
    }

    private void openEditList(Player player) {
        FormWindowSimple form = new FormWindowSimple("Edit Area", "Select an area to edit:");
        List<Area> areas = plugin.getAreas();
        if (areas.isEmpty()) {
            player.sendMessage("§eThere are no protected areas to edit.");
            return;
        }
        for (Area area : areas) {
            // Updated button label to include the world name in parentheses.
            form.addButton(new ElementButton(area.getName() + " (" + area.getWorld() + ")"));
        }
        plugin.getFormIdMap().put(player.getName(), "edit_area_select");
        player.showFormWindow(form);
    }

    private void openDeleteList(Player player) {
        FormWindowSimple form = new FormWindowSimple("Delete Area", "Select an area to delete:");
        List<Area> areas = plugin.getAreas();
        if (areas.isEmpty()) {
            player.sendMessage("§eThere are no protected areas to delete.");
            return;
        }
        for (Area area : areas) {
            form.addButton(new ElementButton(area.getName() + " (" + area.getWorld() + ")"));
        }
        plugin.getFormIdMap().put(player.getName(), "delete_area_select");
        player.showFormWindow(form);
    }

    // New helper method for opening Player Area Management form.
    private void openPlayerAreaManagement(Player player) {
        FormWindowSimple form = new FormWindowSimple("Player Areas", "Select an area to manage:");
        List<Area> areas = plugin.getAreas();
        if (areas.isEmpty()) {
            player.sendMessage("§eThere are no player areas.");
            return;
        }
        for (Area area : areas) {
            form.addButton(new ElementButton(area.getName() + " (" + area.getWorld() + ")"));
        }
        plugin.getFormIdMap().put(player.getName(), "player_area_manage");
        player.showFormWindow(form);
    }

    // New helper method: open an edit options chooser.
    private void openEditOptions(Player player, Area area) {
        FormWindowSimple form = new FormWindowSimple("Edit Options", "Select edit function for area: " + area.getName());
        form.addButton(new ElementButton("Basic Settings"));
        form.addButton(new ElementButton("LuckPerms Overrides"));
        plugin.getFormIdMap().put(player.getName(), "edit_options");
        // Store the selected area for later retrieval.
        editAreaMap.put(player.getName(), area);
        player.showFormWindow(form);
    }
}