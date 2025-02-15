package adminarea;

import cn.nukkit.Player;
import cn.nukkit.form.window.FormWindowSimple;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.element.ElementInput;
import cn.nukkit.form.element.ElementToggle;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.response.FormResponseCustom;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.messenger.Messenger;
import net.luckperms.api.track.Track;
import net.luckperms.api.track.TrackManager;
import org.json.JSONObject;

import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;

public class LuckPermsOverrideForm {

    private final AdminAreaProtectionPlugin plugin;
    private final OverrideManager overrideManager;

    public LuckPermsOverrideForm(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        this.overrideManager = new OverrideManager(plugin);
    }

    // Step 1: List available tracks from LuckPerms.
    public void openTrackSelection(Player player, Area area) {
        LuckPerms lp = plugin.getLuckPermsApi();
        TrackManager tm = lp.getTrackManager();
        List<Track> tracks = new ArrayList<>(tm.getLoadedTracks());
        if (tracks.isEmpty()) {
            player.sendMessage("§eNo tracks found in LuckPerms.");
            return;
        }
        FormWindowSimple form = new FormWindowSimple("Select Track", "Select a track for area: " + area.getName());
        for (Track track : tracks) {
            form.addButton(new ElementButton(track.getName()));
        }
        plugin.getFormIdMap().put(player.getName(), "override_track_select");
        // Store the area name in a temporary map for later retrieval.
        plugin.getFormIdMap().put(player.getName() + "_area", area.getName());
        player.showFormWindow(form);
    }

    // Step 2: After track is selected, list groups in the track.
    public void openGroupSelection(Player player, Area area, String trackName) {
        LuckPerms lp = plugin.getLuckPermsApi();
        Track track = lp.getTrackManager().getTrack(trackName);
        if (track == null) {
            player.sendMessage("§cSelected track not found.");
            return;
        }
        // Get groups in track (order preserved)
        List<String> groups = new ArrayList<>(track.getGroups());
        if (groups.isEmpty()) {
            player.sendMessage("§eNo groups are assigned to this track.");
            return;
        }
        FormWindowSimple form = new FormWindowSimple("Select Group", "Select a group in track: " + trackName);
        for (String group : groups) {
            form.addButton(new ElementButton(group));
        }
        plugin.getFormIdMap().put(player.getName(), "override_group_select");
        // Save both area and track for later retrieval.
        plugin.getFormIdMap().put(player.getName() + "_area", area.getName());
        plugin.getFormIdMap().put(player.getName() + "_track", trackName);
        player.showFormWindow(form);
    }

    // Step 3: Open the permission override editing form for the selected group.
    public void openPermissionEditForm(Player player, Area area, String trackName, String group) {
        FormWindowCustom form = new FormWindowCustom("Edit Overrides for " + group);
        // Use PermissionToggle to add toggles.
        PermissionToggle[] toggles = PermissionToggle.getDefaultToggles();
        for (PermissionToggle pt : toggles) {
            form.addElement(pt.toElementToggle());
        }
        plugin.getFormIdMap().put(player.getName(), "override_edit");
        plugin.getFormIdMap().put(player.getName() + "_area", area.getName());
        plugin.getFormIdMap().put(player.getName() + "_track", trackName);
        plugin.getFormIdMap().put(player.getName() + "_group", group);
        player.showFormWindow(form);
    }

    // Process submission of the permission override form.
    // This method should be called from your FormResponseListener when formId "override_edit" is received.
    public void processPermissionOverrideResponse(Player player, FormResponseCustom response) {
        String areaName = plugin.getFormIdMap().get(player.getName() + "_area");
        String trackName = plugin.getFormIdMap().get(player.getName() + "_track");
        String group = plugin.getFormIdMap().get(player.getName() + "_group");
        if (areaName == null || trackName == null || group == null) {
            player.sendMessage("§cTemporary data lost. Please try again.");
            return;
        }
        // Use PermissionToggle definition to process each toggle.
        PermissionToggle[] toggles = PermissionToggle.getDefaultToggles();
        // Retrieve responses; order corresponds to the toggles’ order.
        List<Object> data = new ArrayList<>(response.getResponses().values());
        if (data.size() < toggles.length) {
            player.sendMessage("§cIncomplete override data.");
            return;
        }
        for (int i = 0; i < toggles.length; i++) {
            boolean value = (Boolean) data.get(i);
            OverrideManager.OverrideEntry entry = new OverrideManager.OverrideEntry(
                0, areaName, "group", group, toggles[i].getPermissionNode(), value
            );
            overrideManager.addOverride(entry);
        }
        player.sendMessage("§aOverrides updated for group " + group + " in track " + trackName + ".");
        // Clean temporary storage.
        plugin.getFormIdMap().remove(player.getName() + "_area");
        plugin.getFormIdMap().remove(player.getName() + "_track");
        plugin.getFormIdMap().remove(player.getName() + "_group");
    }

    // Example: display a form to edit overrides for an area and target (action key)
    public void openForm(Player player, String areaName, String target) {
        // Retrieve current overrides for this area and target.
        JSONObject current = overrideManager.getOverrides(areaName, target);
        // ... display form with current JSON values ...
        // When player submits form, call saveForm(...)
    }

    // Called when the override form is submitted.
    public void saveForm(String areaName, String target, JSONObject newOverrides) {
        // Use only player toggles for overrides:
        PermissionToggle[] toggles = PermissionToggle.getPlayerToggles();
        JSONObject filteredOverrides = new JSONObject();
        for (PermissionToggle toggle : toggles) {
            String key = toggle.getPermissionNode();
            if (newOverrides.has(key)) {
                filteredOverrides.put(key, newOverrides.getBoolean(key));
            } else {
                filteredOverrides.put(key, toggle.getDefaultValue());
            }
        }
        overrideManager.saveOverrides(areaName, target, filteredOverrides);
    }
}
