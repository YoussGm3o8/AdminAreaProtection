package adminarea.listeners;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.constants.AdminAreaConstants;
import adminarea.data.FormTrackingData;
import adminarea.form.FormRegistry;
import adminarea.form.IFormHandler;
import adminarea.form.handlers.AreaListHandler;
import adminarea.form.handlers.CreateAreaHandler;
import adminarea.form.handlers.DeleteAreaHandler;
import adminarea.form.handlers.EditAreaHandler;
import adminarea.form.handlers.LuckPermsOverrideHandler;
import adminarea.form.LuckPermsOverrideForm;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerFormRespondedEvent;
import cn.nukkit.form.response.FormResponse;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.form.window.FormWindowSimple;
import cn.nukkit.plugin.PluginBase;
import io.micrometer.core.instrument.Timer;

public class FormResponseListener implements Listener {
    private static final long CLEANUP_INTERVAL = 300000; // 5 minutes
    private long lastCleanup = 0;
    private final AdminAreaProtectionPlugin plugin;
    private final FormRegistry formRegistry;

    public FormResponseListener(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        this.formRegistry = plugin.getFormRegistry();
    }

    @EventHandler
    public void onFormResponded(PlayerFormRespondedEvent event) {
        if (event.getResponse() == null || event.wasClosed()) {
            if (plugin.isDebugMode()) {
                plugin.debug(String.format("[Form] %s %s form", 
                    event.getPlayer().getName(),
                    event.wasClosed() ? "closed" : "submitted null response for"));
            }
            return;
        }

        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            checkCleanup();

            FormTrackingData formData = plugin.getFormIdMap().remove(event.getPlayer().getName());
            if (formData == null) {
                if (plugin.isDebugMode()) {
                    plugin.debug(String.format("[Form] No form data found for player %s", 
                        event.getPlayer().getName()));
                }
                return;
            }

            String formId = formData.getFormId();
            IFormHandler handler = formRegistry.getHandler(formId);
            
            if (handler == null) {
                if (plugin.isDebugMode()) {
                    plugin.debug(String.format("[Form] No handler found for form ID: %s", formId));
                }
                return;
            }

            if (plugin.isDebugMode()) {
                plugin.debug(String.format("[Form] Processing form response:"));
                plugin.debug(String.format("  Player: %s", event.getPlayer().getName()));
                plugin.debug(String.format("  Form ID: %s", formId));
                plugin.debug(String.format("  Handler: %s", handler.getClass().getSimpleName()));
                plugin.debug(String.format("  Response Type: %s", event.getResponse().getClass().getSimpleName()));
            }

            // Handle response based on type with validation
            handleFormResponse(event, handler, formId);

        } catch (Exception e) {
            handleFormError(event, e);
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "form_response_handle");
        }
    }

    private void handleFormResponse(PlayerFormRespondedEvent event, IFormHandler handler, String formId) {
        if (event.getResponse() instanceof FormResponseCustom) {
            if (plugin.isDebugMode()) {
                FormResponseCustom response = (FormResponseCustom) event.getResponse();
                plugin.debug("[Form] Custom form response data:");
                response.getResponses().forEach((key, value) -> 
                    plugin.debug(String.format("  %s: %s", key, value)));
            }
            handleCustomForm(event, handler, formId);
        } else {
            if (plugin.isDebugMode()) {
                FormResponseSimple response = (FormResponseSimple) event.getResponse();
                plugin.debug(String.format("[Form] Simple form response - Button: %d", 
                    response.getClickedButtonId()));
            }
            handleSimpleForm(event, handler);
        }
    }

    private void handleCustomForm(PlayerFormRespondedEvent event, IFormHandler handler, String formId) {
        FormResponseCustom response = (FormResponseCustom) event.getResponse();
        if (plugin.getGuiManager().validateForm(event.getPlayer(), response, formId)) {
            handler.handleResponse(event.getPlayer(), response);
        } else {
            event.getPlayer().sendMessage(plugin.getLanguageManager().get("messages.form.invalidInput"));
        }
    }

    private void handleSimpleForm(PlayerFormRespondedEvent event, IFormHandler handler) {
        handler.handleResponse(event.getPlayer(), event.getResponse());
    }

    private void handleFormError(PlayerFormRespondedEvent event, Exception e) {
        plugin.getLogger().error("Error handling form response", e);
        if (plugin.isDebugMode()) {
            plugin.debug("[Form] Error processing form response:");
            plugin.debug("  Player: " + event.getPlayer().getName());
            plugin.debug("  Exception: " + e.getMessage());
            e.printStackTrace();
        }
        event.getPlayer().sendMessage(plugin.getLanguageManager().get("messages.form.error"));
    }

    private void checkCleanup() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastCleanup > CLEANUP_INTERVAL) {
            plugin.getGuiManager().cleanup();
            formRegistry.clearHandlers();
            lastCleanup = currentTime;
            
            if (plugin.isDebugMode()) {
                plugin.debug("Performed form cleanup");
            }
        }
    }
}