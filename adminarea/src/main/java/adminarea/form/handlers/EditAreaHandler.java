package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.constants.AdminAreaConstants;
import adminarea.constants.FormIds;
import adminarea.data.FormTrackingData;
import cn.nukkit.Player;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;

public class EditAreaHandler extends BaseFormHandler {

    private final String formId = FormIds.EDIT_AREA;

    public EditAreaHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
    }

    @Override
    public String getFormId() {
        return AdminAreaConstants.FORM_EDIT_AREA;
    }

    @Override
    public FormWindow createForm(Player player) {
        // Safely handle null player
        if (player == null) {
            return null;
        }

        // Get the area being edited from tracking data
        FormTrackingData trackingData = plugin.getFormIdMap().get(player.getName() + "_editing");
        if (trackingData == null) {
            return null;
        }

        Area area = plugin.getArea(trackingData.getFormId());
        if (area == null) {
            return null;
        }

        return plugin.getGuiManager().createEditForm(area);
    }

    @Override
    protected void handleCustomResponse(Player player, FormResponseCustom response) {
        // Edit form doesn't use custom responses
        throw new UnsupportedOperationException("This handler only supports simple forms");
    }

    @Override
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        if (player == null || response == null) {
            return;
        }

        try {
            // Store form data
            plugin.getFormIdMap().put(player.getName(),
                new FormTrackingData(AdminAreaConstants.FORM_EDIT_AREA, System.currentTimeMillis()));

            FormTrackingData trackingData = plugin.getFormIdMap().get(player.getName() + "_editing");
            if (trackingData == null) {
                return;
            }

            Area area = plugin.getArea(trackingData.getFormId());
            if (area == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound"));
                return;
            }

            plugin.getGuiManager().handleEditFormResponse(player, response.getClickedButtonId(), area);

        } catch (Exception e) {
            handleError(player, e);
        }
    }

    private void handleError(Player player, Exception e) {
        plugin.getLogger().error(e.getMessage(), e);
        player.sendMessage(plugin.getLanguageManager().get("messages.errorOccurred"));
    }

    public void handle(EditAreaFormData formData) {
        // Example of data flow:
        // 1. Get data from the form
        String areaId = formData.getAreaId();
        String newAreaName = formData.getNewAreaName();

        // 2. Validate the data
        if (areaId == null || areaId.isEmpty()) {
            System.err.println("Error: Area ID is required.");
            return;
        }

        // 3. Update the database
        updateAreaInDatabase(areaId, newAreaName);

        // 4. Confirm the update to the user (e.g., display a success message)
        System.out.println("Area updated successfully.");
    }

    private void updateAreaInDatabase(String areaId, String newAreaName) {
        // Implementation to update the area in the database
        // ...
    }

    public static class EditAreaFormData {
        private String areaId;
        private String newAreaName;
        // ... other fields ...

        public String getAreaId() {
            return areaId;
        }

        public void setAreaId(String areaId) {
            this.areaId = areaId;
        }

        public String getNewAreaName() {
            return newAreaName;
        }

        public void setNewAreaName(String newAreaName) {
            this.newAreaName = newAreaName;
        }

        // ... getters and setters for other fields ...
    }
}
