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

    private static final int MIN_CACHE_EXPIRY = 1;
    private static final int MAX_CACHE_EXPIRY = 1440; // 24 hours
    private static final int MIN_UNDO_HISTORY = 1;
    private static final int MAX_UNDO_HISTORY = 100;
    private static final int MIN_UPDATE_INTERVAL = 60; // 1 minute
    private static final int MAX_UPDATE_INTERVAL = 3600; // 1 hour

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
            // Removed allowRegularAreaCreation toggle

            // Area Settings
            form.addElement(new ElementLabel(plugin.getLanguageManager().get("gui.pluginSettings.sections.area")));
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
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.createError", Map.of("error", e.getMessage())));
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
            // Validate all numeric inputs first
            validateNumericInputs(response);

            // General Settings
            plugin.setDebugMode(response.getToggleResponse(1));
            plugin.getConfigManager().set("debugStackTraces", response.getToggleResponse(2));
            plugin.getConfigManager().set("enableMessages", response.getToggleResponse(3));
            // Removed allowRegularAreaCreation toggle

            // Area Settings
            plugin.getConfigManager().set("areaSettings.useMostRestrictiveMerge", response.getToggleResponse(5));

            // Tool Settings
            int wandItemType = Integer.parseInt(response.getInputResponse(7));
            plugin.getConfigManager().set("wandItemType", wandItemType);
            
            // Handle particle visualization settings
            Map<String, Object> particleSection = new HashMap<>();
            particleSection.put("enabled", response.getToggleResponse(8));
            particleSection.put("duration", Integer.parseInt(response.getInputResponse(9)));
            plugin.getConfigManager().set("particleVisualization", particleSection);
            
            plugin.getConfigManager().set("selectionCooldown", Integer.parseInt(response.getInputResponse(10)));

            // Cache Settings
            int cacheExpiry = Integer.parseInt(response.getInputResponse(12));
            validateRange("cacheExpiry", cacheExpiry, MIN_CACHE_EXPIRY, MAX_CACHE_EXPIRY);
            plugin.getConfigManager().set("cacheExpiryMinutes", cacheExpiry);

            int undoHistory = Integer.parseInt(response.getInputResponse(13));
            validateRange("undoHistory", undoHistory, MIN_UNDO_HISTORY, MAX_UNDO_HISTORY);
            plugin.getConfigManager().set("undoHistorySize", undoHistory);

            // Integrations
            plugin.getConfigManager().set("luckperms.enabled", response.getToggleResponse(15));
            plugin.getConfigManager().set("luckperms.inheritPermissions", response.getToggleResponse(16));
            
            int updateInterval = Integer.parseInt(response.getInputResponse(17));
            validateRange("updateInterval", updateInterval, MIN_UPDATE_INTERVAL, MAX_UPDATE_INTERVAL);
            plugin.getConfigManager().set("luckperms.updateInterval", updateInterval);

            // Save and notify
            plugin.getConfigManager().getConfig().save();
            player.sendMessage(plugin.getLanguageManager().get("success.plugin.reload"));

            // Cleanup
            cleanup(player);

        } catch (NumberFormatException e) {
            player.sendMessage(plugin.getLanguageManager().get("validation.form.settings.invalid", 
                Map.of("value", e.getMessage())));
            plugin.getGuiManager().openFormById(player, FormIds.PLUGIN_SETTINGS, null);
        } catch (IllegalArgumentException e) {
            player.sendMessage(plugin.getLanguageManager().get("validation.form.settings.outOfRange", 
                Map.of("error", e.getMessage())));
            plugin.getGuiManager().openFormById(player, FormIds.PLUGIN_SETTINGS, null);
        } catch (Exception e) {
            plugin.getLogger().error("Error handling plugin settings response", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
            handleCancel(player);
        }
    }

    private void validateNumericInputs(FormResponseCustom response) throws NumberFormatException {
        // Validate all numeric inputs
        Integer.parseInt(response.getInputResponse(7));  // wandItemType
        Integer.parseInt(response.getInputResponse(9)); // visualizationDuration
        Integer.parseInt(response.getInputResponse(10)); // selectionCooldown
        Integer.parseInt(response.getInputResponse(12)); // cacheExpiry
        Integer.parseInt(response.getInputResponse(13)); // undoHistory
        Integer.parseInt(response.getInputResponse(17)); // updateInterval
    }

    private void validateRange(String field, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(plugin.getLanguageManager().get("validation.form.settings.outOfRange",
                Map.of(
                    "field", field,
                    "min", String.valueOf(min),
                    "max", String.valueOf(max),
                    "value", String.valueOf(value)
                )));
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
