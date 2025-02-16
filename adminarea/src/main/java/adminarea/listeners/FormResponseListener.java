package adminarea.listeners;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.form.FormRegistry;
import adminarea.form.IFormHandler;
import adminarea.form.handlers.AreaListHandler;
import adminarea.form.handlers.CreateAreaHandler;
import adminarea.form.handlers.DeleteAreaHandler;
import adminarea.form.handlers.EditAreaHandler;
import adminarea.form.handlers.LuckPermsOverrideHandler;
import adminarea.form.handlers.MainMenuHandler;
import adminarea.form.handlers.PlayerAreaHandler;
import adminarea.form.LuckPermsOverrideForm;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerFormRespondedEvent;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.plugin.PluginBase;
import io.micrometer.core.instrument.Timer;

public class FormResponseListener implements Listener {
    private final AdminAreaProtectionPlugin plugin;
    private final FormRegistry formRegistry;

    public FormResponseListener(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        this.formRegistry = new FormRegistry();
        initializeHandlers();
    }

    private void initializeHandlers() {
        // Register all form handlers
        formRegistry.registerHandler(new MainMenuHandler(plugin));
        formRegistry.registerHandler(new CreateAreaHandler(plugin));
        formRegistry.registerHandler(new EditAreaHandler(plugin));
        formRegistry.registerHandler(new DeleteAreaHandler(plugin));
        formRegistry.registerHandler(new AreaListHandler(plugin));
        formRegistry.registerHandler(new PlayerAreaHandler(plugin));
        formRegistry.registerHandler(new LuckPermsOverrideHandler(plugin));
    }

    @EventHandler
    public void onFormResponded(PlayerFormRespondedEvent event) {
        if (event.getResponse() == null || event.wasClosed()) {
            return;
        }

        // Get form ID from player's stored form ID
        String formId = plugin.getFormIdMap().remove(event.getPlayer().getName());
        if (formId == null) {
            plugin.getLogger().debug("No form ID found for player " + event.getPlayer().getName());
            return;
        }

        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            IFormHandler handler = formRegistry.getHandler(formId);
            if (handler != null) {
                if (plugin.getConfigManager().isDebugEnabled()) {
                    plugin.getLogger().debug("Handling form response for ID: " + formId);
                }
                
                if (event.getResponse() instanceof FormResponseCustom) {
                    // Validate form response if it's a custom form
                    if (plugin.getGuiManager().validateForm(event.getPlayer(), 
                        (FormResponseCustom) event.getResponse(), formId)) {
                        handler.handleResponse(event.getPlayer(), event.getResponse());
                    }
                } else {
                    handler.handleResponse(event.getPlayer(), event.getResponse());
                }
            } else {
                plugin.getLogger().debug("No handler found for form ID: " + formId);
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error handling form response: " + e.getMessage());
            if (plugin.getConfigManager().isDebugEnabled()) {
                e.printStackTrace();
            }
            event.getPlayer().sendMessage("§cAn error occurred while processing your input");
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "form_response_handle");
        }
    }
}