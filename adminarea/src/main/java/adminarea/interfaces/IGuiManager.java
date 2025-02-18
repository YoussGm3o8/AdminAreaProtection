package adminarea.interfaces;

import adminarea.area.Area;
import cn.nukkit.Player;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.response.FormResponseCustom;

public interface IGuiManager {
    void openMainMenu(Player player);
    void openCreateForm(Player player);
    void openEditForm(Player player, Area area);
    void openEditList(Player player);
    void openDeleteList(Player player);
    void openPlayerAreaManagement(Player player);
    void openAreaSettings(Player player, Area area);
    void openBasicSettings(Player player, Area area);
    void openProtectionSettings(Player player, Area area);
    void openBuildingPermissions(Player player, Area area);
    void openEnvironmentSettings(Player player, Area area);
    void openEntityControls(Player player, Area area);
    void openRedstoneAndMechanics(Player player, Area area);
    void openSpecialPermissions(Player player, Area area);
    void openLuckPermsGroupList(Player player, Area area);
    void sendForm(Player player, FormWindow form, String formId);

    /**
     * Opens the LuckPerms group permissions form for a specific area and group
     * @param player The player to show the form to
     * @param area The area to modify permissions for
     * @param groupName The LuckPerms group name
     */
    void openLuckPermsGroupPermissions(Player player, Area area, String groupName);

    /**
     * Validates a form response before processing
     * @param player The player who submitted the form
     * @param response The form response to validate
     * @param formType The type of form being validated
     * @return true if valid, false otherwise
     */
    boolean validateForm(Player player, FormResponseCustom response, String formType);
    
    void handleEditFormResponse(Player player, int buttonId, Area area);

    /**
     * Handles navigation back to previous form
     * @param player The player navigating back
     */
    void handleBackNavigation(Player player);
}
