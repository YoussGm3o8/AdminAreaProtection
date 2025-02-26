package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.constants.FormIds;
import adminarea.form.IFormHandler;
import cn.nukkit.Player;
import cn.nukkit.form.response.FormResponse;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseModal;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;

public abstract class BaseFormHandler implements IFormHandler {
    protected final AdminAreaProtectionPlugin plugin;

    public BaseFormHandler(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
    }

    protected void validateFormId() {
        String formId = getFormId();
        if (formId == null || formId.trim().isEmpty()) {
            throw new IllegalStateException("Form handler must provide a non-null, non-empty formId");
        }
    }

    @Override
    public void handleResponse(Player player, Object response) {
        try {
            // Save current form tracking data
            var currentFormData = plugin.getFormIdMap().get(player.getName());
            var editingData = plugin.getFormIdMap().get(player.getName() + "_editing");
            
            if (plugin.isDebugMode()) {
                plugin.debug("Handling form response with tracking data:");
                if (currentFormData != null) {
                    plugin.debug("  Current form: " + currentFormData.getFormId());
                }
                if (editingData != null) {
                    plugin.debug("  Editing area: " + editingData.getFormId());
                }
            }

            // Handle null response (form closed) first
            if (response == null) {
                if (plugin.isDebugMode()) {
                    plugin.debug("Form was closed by player, cleaning up");
                }
                cleanup(player);
                return;
            }

            // Handle the response based on form type
            if (response instanceof FormResponseCustom) {
                handleCustomResponse(player, (FormResponseCustom) response);
            } else if (response instanceof FormResponseSimple) {
                handleSimpleResponse(player, (FormResponseSimple) response);
            } else if (response instanceof FormResponseModal) {
                handleModalResponse(player, (FormResponseModal) response);
            }

            // Check if we're transitioning to a new form
            var newFormData = plugin.getFormIdMap().get(player.getName());
            if (newFormData == null || newFormData.equals(currentFormData)) {
                if (plugin.isDebugMode()) {
                    plugin.debug("No form transition detected, cleaning up");
                }
                cleanup(player);
            } else if (plugin.isDebugMode()) {
                plugin.debug("Form transition detected, preserving data");
                plugin.debug("  New form: " + newFormData.getFormId());
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error handling form response", e);
            cleanup(player);
            throw e;
        }
    }

    @Override
    public void handleCancel(Player player) {
        var currentFormData = plugin.getFormIdMap().get(player.getName());
        
        // Just cleanup for all forms when cancelled/closed
        cleanup(player);
    }

    protected void cleanup(Player player) {
        if (plugin.isDebugMode()) {
            plugin.debug("Cleaning up form data in BaseFormHandler");
            var currentForm = plugin.getFormIdMap().get(player.getName());
            if (currentForm != null) {
                plugin.debug("  Removing form data: " + currentForm.getFormId());
            }
        }
        // Remove both regular and editing form data
        plugin.getFormIdMap().remove(player.getName());
        plugin.getFormIdMap().remove(player.getName() + "_editing");
        // Clear navigation history
        plugin.getGuiManager().clearNavigationHistory(player);
    }

    @Override
    public FormWindow createForm(Player player, Area area) {
        return createForm(player);
    }

    protected abstract void handleCustomResponse(Player player, FormResponseCustom response);
    protected abstract void handleSimpleResponse(Player player, FormResponseSimple response);
    protected void handleModalResponse(Player player, FormResponseModal response) {
        // Default implementation does nothing
    }

    /**
     * Normalizes a permission node to ensure it has the correct prefix
     * @param permissionNode The permission node to normalize
     * @return The normalized permission node
     */
    protected String normalizePermissionNode(String permissionNode) {
        if (permissionNode == null || permissionNode.isEmpty()) {
            return "gui.permissions.toggles.default";
        }
        
        // If already has the prefix, return as is
        if (permissionNode.startsWith("gui.permissions.toggles.")) {
            return permissionNode;
        }
        
        // Add the prefix
        return "gui.permissions.toggles." + permissionNode;
    }
}