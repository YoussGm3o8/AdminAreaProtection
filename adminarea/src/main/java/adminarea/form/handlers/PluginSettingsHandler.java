package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.constants.FormIds;
import cn.nukkit.Player;
import cn.nukkit.form.element.ElementInput;
import cn.nukkit.form.element.ElementLabel;
import cn.nukkit.form.element.ElementToggle;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.utils.ConfigSection;

import java.util.HashMap;
import java.util.Map;

public class PluginSettingsHandler extends BaseFormHandler {

    public PluginSettingsHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
        validateFormId();
    }

    @Override
    public String getFormId() {
        return FormIds.PLUGIN_SETTINGS;
    }

    @Override
    public FormWindow createForm(Player player) {
        try {
            FormWindowCustom form = new FormWindowCustom(
                plugin.getLanguageManager().get("gui.pluginSettings.title")
            );

            // General Settings
            form.addElement(new ElementLabel(plugin.getLanguageManager().get("gui.pluginSettings.sections.general")));
            form.addElement(new ElementToggle(
                plugin.getLanguageManager().get("gui.pluginSettings.labels.debugMode"),
                plugin.getConfigManager().isDebugEnabled()
            ));
            form.addElement(new ElementToggle(
                plugin.getLanguageManager().get("gui.pluginSettings.labels.debugStackTraces"),
                plugin.getConfigManager().getBoolean("debugStackTraces", false)
            ));
            form.addElement(new ElementToggle(
                plugin.getLanguageManager().get("gui.pluginSettings.labels.enableMessages"),
                plugin.getConfigManager().getBoolean("enableMessages", true)
            ));
            form.addElement(new ElementToggle(
                plugin.getLanguageManager().get("gui.pluginSettings.labels.allowRegularAreaCreation"),
                plugin.getConfigManager().getBoolean("allowRegularAreaCreation", false)
            ));

            // Area Settings
            form.addElement(new ElementLabel(plugin.getLanguageManager().get("gui.pluginSettings.sections.area")));
            form.addElement(new ElementInput(
                plugin.getLanguageManager().get("gui.pluginSettings.labels.maxAreaPriority"),
                plugin.getLanguageManager().get("gui.pluginSettings.labels.maxAreaPriorityPlaceholder"),
                String.valueOf(plugin.getConfigManager().getMaxAreaPriority())
            ));
            form.addElement(new ElementToggle(
                plugin.getLanguageManager().get("gui.pluginSettings.labels.mostRestrictiveMerge"),
                plugin.getConfigManager().getBoolean("areaSettings.useMostRestrictiveMerge", true)
            ));

            // Tool Settings
            form.addElement(new ElementLabel(plugin.getLanguageManager().get("gui.pluginSettings.sections.tools")));
            form.addElement(new ElementInput(
                plugin.getLanguageManager().get("gui.pluginSettings.labels.wandItemType"),
                plugin.getLanguageManager().get("gui.pluginSettings.labels.wandItemTypePlaceholder"),
                String.valueOf(plugin.getConfigManager().getWandItemType())
            ));
            form.addElement(new ElementToggle(
                plugin.getLanguageManager().get("gui.pluginSettings.labels.particleVisualization"),
                plugin.getConfigManager().isParticleVisualizationEnabled()
            ));
            form.addElement(new ElementInput(
                plugin.getLanguageManager().get("gui.pluginSettings.labels.visualizationDuration"),
                plugin.getLanguageManager().get("gui.pluginSettings.labels.visualizationDurationPlaceholder"),
                String.valueOf(plugin.getConfigManager().getVisualizationDuration())
            ));
            form.addElement(new ElementInput(
                plugin.getLanguageManager().get("gui.pluginSettings.labels.selectionCooldown"),
                plugin.getLanguageManager().get("gui.pluginSettings.labels.selectionCooldownPlaceholder"),
                String.valueOf(plugin.getConfigManager().getSelectionCooldown())
            ));

            // Cache Settings
            form.addElement(new ElementLabel(plugin.getLanguageManager().get("gui.pluginSettings.sections.cache")));
            form.addElement(new ElementInput(
                plugin.getLanguageManager().get("gui.pluginSettings.labels.cacheExpiryTime"),
                plugin.getLanguageManager().get("gui.pluginSettings.labels.cacheExpiryTimePlaceholder"),
                String.valueOf(plugin.getConfigManager().getCacheExpiryMinutes())
            ));
            form.addElement(new ElementInput(
                plugin.getLanguageManager().get("gui.pluginSettings.labels.undoHistorySize"),
                plugin.getLanguageManager().get("gui.pluginSettings.labels.undoHistorySizePlaceholder"),
                String.valueOf(plugin.getConfigManager().getUndoHistorySize())
            ));

            // Integrations
            form.addElement(new ElementLabel(plugin.getLanguageManager().get("gui.pluginSettings.sections.integrations")));
            form.addElement(new ElementToggle(
                plugin.getLanguageManager().get("gui.pluginSettings.labels.luckpermsIntegration"),
                plugin.getConfigManager().getBoolean("luckperms.enabled", true)
            ));
            form.addElement(new ElementToggle(
                plugin.getLanguageManager().get("gui.pluginSettings.labels.inheritLuckpermsPermissions"),
                plugin.getConfigManager().getBoolean("luckperms.inheritPermissions", true)
            ));
            form.addElement(new ElementInput(
                plugin.getLanguageManager().get("gui.pluginSettings.labels.luckpermsUpdateInterval"),
                plugin.getLanguageManager().get("gui.pluginSettings.labels.luckpermsUpdateIntervalPlaceholder"),
                String.valueOf(plugin.getConfigManager().getInt("luckperms.updateInterval", 300))
            ));

            return form;
        } catch (Exception e) {
            plugin.getLogger().error("Error creating plugin settings form", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
            return null;
        }
    }

    @Override
    protected void handleCustomResponse(Player player, FormResponseCustom response) {
        if (response == null) {
            handleCancel(player);
            return;
        }

        try {
            // General Settings
            plugin.setDebugMode(response.getToggleResponse(1));
            plugin.getConfigManager().set("debugStackTraces", response.getToggleResponse(2));
            plugin.getConfigManager().set("enableMessages", response.getToggleResponse(3));
            plugin.getConfigManager().set("allowRegularAreaCreation", response.getToggleResponse(4));

            // Area Settings
            int maxPriority = Integer.parseInt(response.getInputResponse(6));
            if (maxPriority < 1 || maxPriority > 1000) {
                player.sendMessage(plugin.getLanguageManager().get("messages.form.error.invalidInput"));
                return;
            }
            plugin.getConfigManager().set("maxAreaPriority", maxPriority);
            plugin.getConfigManager().set("areaSettings.useMostRestrictiveMerge", response.getToggleResponse(7));

            // Tool Settings
            int wandItemType = Integer.parseInt(response.getInputResponse(9));
            plugin.getConfigManager().set("wandItemType", wandItemType);
            
            // Handle particle visualization settings
            Map<String, Object> particleSection = new HashMap<>();
            particleSection.put("enabled", response.getToggleResponse(10));
            particleSection.put("duration", Integer.parseInt(response.getInputResponse(11)));
            plugin.getConfigManager().set("particleVisualization", particleSection);
            
            plugin.getConfigManager().set("selectionCooldown", Integer.parseInt(response.getInputResponse(12)));

            // Cache Settings
            plugin.getConfigManager().set("cacheExpiryMinutes", Integer.parseInt(response.getInputResponse(14)));
            plugin.getConfigManager().set("undoHistorySize", Integer.parseInt(response.getInputResponse(15)));

            // Integrations
            plugin.getConfigManager().set("luckperms.enabled", response.getToggleResponse(17));
            plugin.getConfigManager().set("luckperms.inheritPermissions", response.getToggleResponse(18));
            plugin.getConfigManager().set("luckperms.updateInterval", Integer.parseInt(response.getInputResponse(19)));

            plugin.getConfigManager().getConfig().save();
            player.sendMessage(plugin.getLanguageManager().get("messages.config.reloaded"));

            // Just cleanup without opening any new menu
            cleanup(player);

        } catch (NumberFormatException e) {
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.invalidInput"));
            plugin.getGuiManager().openFormById(player, FormIds.PLUGIN_SETTINGS, null);
        } catch (Exception e) {
            plugin.getLogger().error("Error handling plugin settings response", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
            handleCancel(player);
        }
    }

    @Override
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        // Not used for custom form
    }

    @Override
    public FormWindow createForm(Player player, Area area) {
        return createForm(player); // Area context not needed
    }
}
