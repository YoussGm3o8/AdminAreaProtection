package adminarea.util;

import adminarea.AdminAreaProtectionPlugin;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.form.window.FormWindowSimple;
import adminarea.data.FormTrackingData;

public class FormLogger {
    private final AdminAreaProtectionPlugin plugin;

    public FormLogger(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
    }

    public void logFormSend(String playerName, FormWindow form, String formId) {
        if (!plugin.isDebugMode()) return;
        
        plugin.debug(String.format("[Form] Sending form %s to %s:", formId, playerName));

        if (form instanceof FormWindowCustom customForm) {
            plugin.debug(String.format("  Type: Custom\n  Title: %s\n  Elements: %d",
                customForm.getTitle(), customForm.getElements().size()));
        } else if (form instanceof FormWindowSimple simpleForm) {
            plugin.debug(String.format("  Type: Simple\n  Title: %s\n  Buttons: %d",
                simpleForm.getTitle(), simpleForm.getButtons().size()));
        }
    }

    public void logFormData(String playerName) {
        if (!plugin.isDebugMode()) return;

        FormTrackingData currentForm = plugin.getFormIdMap().get(playerName);
        FormTrackingData editingArea = plugin.getFormIdMap().get(playerName + "_editing");
        
        plugin.debug("[Form] Current form tracking data:");
        if (currentForm != null) {
            plugin.debug(String.format("  Current Form: %s (%.1fs ago)", 
                currentForm.getFormId(),
                (System.currentTimeMillis() - currentForm.getCreationTime()) / 1000.0));
        } else {
            plugin.debug("  No current form data");
        }

        if (editingArea != null) {
            plugin.debug(String.format("  Editing Area: %s (%.1fs ago)",
                editingArea.getFormId(), 
                (System.currentTimeMillis() - editingArea.getCreationTime()) / 1000.0));
        }
    }

    public void logFormError(String message, Exception e) {
        plugin.getLogger().error("[Form] " + message);
        if (plugin.isDebugMode()) {
            plugin.debug("Stack trace:");
            e.printStackTrace();
        }
    }
}
