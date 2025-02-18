package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.form.IFormHandler;
import adminarea.managers.GuiManager;
import adminarea.constants.AdminAreaConstants;
import adminarea.data.FormTrackingData;
import cn.nukkit.Player;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowSimple;
import cn.nukkit.form.element.ElementButton;
import adminarea.constants.FormIds;

public class AreaListHandler implements IFormHandler {
    private final AdminAreaProtectionPlugin plugin;
    private final String formId = FormIds.AREA_LIST;

    public AreaListHandler(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getFormId() {
        return AdminAreaConstants.FORM_EDIT_LIST;  // Changed from FORM_EDIT_SELECT
    }

    @Override
    public FormWindow createForm(Player player) {
        FormWindowSimple form = new FormWindowSimple("Edit Area", "Select an area to edit:");
        for (Area area : plugin.getAreas()) {
            form.addButton(new ElementButton(area.getName()));
        }
        return form;
    }

    @Override
    public void handleResponse(Player player, Object response) {
        if (!(response instanceof FormResponseSimple)) return;
        
        FormResponseSimple simpleResponse = (FormResponseSimple) response;
        int btnId = simpleResponse.getClickedButtonId();
        
        if (btnId >= 0 && btnId < plugin.getAreas().size()) {
            Area area = plugin.getAreas().get(btnId);
            plugin.getFormIdMap().put(player.getName() + "_editing", 
                new FormTrackingData(area.getName(), System.currentTimeMillis()));
            plugin.getGuiManager().openEditForm(player, area);
        }
    }

    public void handle(AreaListFormData formData) {
        // Process the form data
        // Example:
        // - Retrieve area list based on criteria in formData
        // - Format the area list for display in the GUI
        // - Potentially update the GUI with the area list
    }

    // Inner class to represent the form data (adjust as needed)
    public static class AreaListFormData {
        // Example fields:
        private String searchCriteria;
        private int pageNumber;

        // Getters and setters for the fields
        public String getSearchCriteria() {
            return searchCriteria;
        }

        public void setSearchCriteria(String searchCriteria) {
            this.searchCriteria = searchCriteria;
        }

        public int getPageNumber() {
            return pageNumber;
        }

        public void setPageNumber(int pageNumber) {
            this.pageNumber = pageNumber;
        }
    }
}
