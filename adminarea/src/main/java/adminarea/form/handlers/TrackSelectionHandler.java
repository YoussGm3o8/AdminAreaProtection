package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.constants.FormIds;
import adminarea.data.FormTrackingData;
import cn.nukkit.Player;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import io.micrometer.core.instrument.Timer;

import java.util.List;
import java.util.Map;

public class TrackSelectionHandler extends BaseFormHandler {

    public TrackSelectionHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
    }

    @Override
    public String getFormId() {
        return FormIds.TRACK_SELECT;
    }

    @Override
    public FormWindow createForm(Player player) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            if (!player.hasPermission("adminarea.luckperms.view")) {
                return null;
            }

            FormTrackingData areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
            if (areaData == null) return null;

            Area area = plugin.getArea(areaData.getFormId());
            if (area == null) return null;

            // Changed to call without area parameter
            return plugin.getFormFactory().createTrackSelectionForm();
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "create_track_selection_form");
        }
    }

    @Override
    protected void handleCustomResponse(Player player, FormResponseCustom response) {
        // Track selection uses simple forms only
        throw new UnsupportedOperationException("Track selection does not use custom forms");
    }

    @Override
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            // Verify permissions
            if (!player.hasPermission("adminarea.luckperms.edit")) {
                player.sendMessage(plugin.getLanguageManager().get("messages.noPermission"));
                return;
            }

            FormTrackingData areaData = plugin.getFormIdMap().get(player.getName() + "_editing");
            if (areaData == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.form.invalidSession"));
                return;
            }

            Area area = plugin.getArea(areaData.getFormId());
            if (area == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound"));
                return;
            }

            // Get selected track
            List<String> tracks = plugin.getLuckPermsCache().getGroupTracks().keySet().stream().toList();
            int selection = response.getClickedButtonId();

            // Handle back button
            if (selection >= tracks.size()) {
                plugin.getGuiManager().openAreaSettings(player, area);
                return;
            }

            String trackName = tracks.get(selection);
            plugin.getFormIdMap().put(player.getName() + "_track",
                new FormTrackingData(trackName, System.currentTimeMillis()));

            // Changed to only pass trackName parameter
            FormWindow groupsForm = plugin.getFormFactory().createTrackGroupsForm(trackName);
            if (groupsForm != null) {
                plugin.getGuiManager().sendForm(player, groupsForm, FormIds.TRACK_GROUPS);
            } else {
                player.sendMessage(plugin.getLanguageManager().get("messages.form.errorCreatingForm"));
            }

        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "handle_track_selection");
        }
    }
}
