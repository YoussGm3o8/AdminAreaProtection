package adminarea.listeners;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.constants.FormIds;
import adminarea.data.FormTrackingData;
import adminarea.form.FormRegistry;
import adminarea.form.IFormHandler;
import adminarea.util.FormLogger;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerFormRespondedEvent;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.form.window.FormWindowSimple;
import io.micrometer.core.instrument.Timer;

public class FormResponseListener implements Listener {
    private final FormLogger formLogger;
    // Increase cleanup interval to 15 minutes
    private static final long CLEANUP_INTERVAL = 900000; // 15 minutes
    private long lastCleanup = 0;
    private final AdminAreaProtectionPlugin plugin;
    private final FormRegistry formRegistry;

    public FormResponseListener(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        this.formRegistry = plugin.getFormRegistry();
        this.formLogger = new FormLogger(plugin);
    }

    @EventHandler
    public void onFormResponded(PlayerFormRespondedEvent event) {
        if (event.getResponse() == null || event.wasClosed()) {
            if (plugin.isDebugMode()) {
                plugin.debug(String.format("[Form] %s %s form", 
                    event.getPlayer().getName(),
                    event.wasClosed() ? "closed" : "submitted null response for"));
                formLogger.logFormData(event.getPlayer().getName());
            }
            handleFormCancel(event);
            return;
        }

        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            FormTrackingData formData = plugin.getFormIdMap().get(event.getPlayer().getName());
            if (formData == null) {
                if (plugin.isDebugMode()) {
                    plugin.debug("No form tracking data found for " + event.getPlayer().getName());
                    plugin.debug("Form type: " + event.getWindow().getClass().getSimpleName());
                    if (event.getWindow() instanceof FormWindowCustom) {
                        plugin.debug("Form title: " + ((FormWindowCustom)event.getWindow()).getTitle());
                    } else if (event.getWindow() instanceof FormWindowSimple simple) {
                        plugin.debug("Form title: " + simple.getTitle());
                        
                        // Try to recover form tracking data based on window title and content
                        if ("Delete Area".equals(simple.getTitle())) {
                            // Check if this is the delete confirmation form by looking for the confirmation message
                            String content = simple.getContent();
                            if (content != null && content.contains("Are you sure you want to delete area '")) {
                                // Extract area name from confirmation message
                                int startIndex = content.indexOf("'") + 1;
                                int endIndex = content.indexOf("'", startIndex);
                                if (startIndex > 0 && endIndex > startIndex) {
                                    String areaName = content.substring(startIndex, endIndex);
                                    
                                    // Recover both form tracking data and area being edited
                                    formData = new FormTrackingData(FormIds.DELETE_CONFIRM, System.currentTimeMillis());
                                    plugin.getFormIdMap().put(event.getPlayer().getName(), formData);
                                    plugin.getFormIdMap().put(event.getPlayer().getName() + "_editing",
                                        new FormTrackingData(areaName, System.currentTimeMillis()));
                                        
                                    if (plugin.isDebugMode()) {
                                        plugin.debug("Recovered form tracking data for delete confirmation form");
                                        plugin.debug("Recovered area being edited: " + areaName);
                                    }
                                }
                            }
                        }
                    }
                    // Log current form tracking data
                    formLogger.logFormData(event.getPlayer().getName());
                }
            }

            checkCleanup();

            if (formData == null) {
                if (plugin.isDebugMode()) {
                    plugin.debug(String.format("[Form] No form data found for player %s", 
                        event.getPlayer().getName()));
                    // Add debug info about existing tracking data
                    plugin.debug("Current form tracking data:");
                    plugin.getFormIdMap().forEach((key, value) -> 
                        plugin.debug(String.format("  %s: %s (%d ms ago)", 
                            key,
                            value.getFormId(),
                            System.currentTimeMillis() - value.getCreationTime())));

                    // Log form window details
                    FormWindow form = event.getWindow();
                    if (form != null) {
                        plugin.debug("Received form response:");
                        plugin.debug("  Form type: " + form.getClass().getSimpleName());
                        if (form instanceof FormWindowCustom) {
                            plugin.debug("  Title: " + ((FormWindowCustom)form).getTitle());
                        } else if (form instanceof FormWindowSimple) {
                            plugin.debug("  Title: " + ((FormWindowSimple)form).getTitle());
                        }
                    }
                }
                return;
            }

            String formId = formData.getFormId();
            if (plugin.isDebugMode()) {
                plugin.debug("Looking up handler for formId: " + formId);
                formLogger.logFormData(event.getPlayer().getName());
            }

            IFormHandler handler = formRegistry.getHandler(formId);
            
            if (handler == null) {
                if (plugin.isDebugMode()) {
                    plugin.debug(String.format("[Form] No handler found for form ID: %s", formId));
                    // Add form registry debug info
                    plugin.debug("Available handlers in registry:");
                    plugin.debug(formRegistry.toString());
                }
                return;
            }

            logDebugInfo(event, handler, formId);
            
            // Only remove form data after successful response handling
            try {
                handleFormResponse(event, handler, formId);
            } catch (Exception e) {
                plugin.getLogger().error("Error handling form response", e);
                throw e; // Re-throw to be caught by outer catch
            }

        } catch (Exception e) {
            handleFormError(event, e);
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "form_response_handle");
        }
    }

    private void logDebugInfo(PlayerFormRespondedEvent event, IFormHandler handler, String formId) {
        if (!plugin.isDebugMode() || handler == null) return;
        
        plugin.debug(String.format("[Form] Processing form response:"));
        plugin.debug(String.format("  Player: %s", event.getPlayer().getName()));
        plugin.debug(String.format("  Form ID: %s", formId));
        plugin.debug(String.format("  Handler: %s", handler.getClass().getSimpleName()));
        plugin.debug(String.format("  Response Type: %s", event.getResponse().getClass().getSimpleName()));

        if (event.getResponse() instanceof FormResponseCustom response) {
            plugin.debug("[Form] Custom form response data:");
            response.getResponses().forEach((key, value) -> 
                plugin.debug(String.format("  %s: %s", key, value)));
        } else if (event.getResponse() instanceof FormResponseSimple response) {
            plugin.debug(String.format("[Form] Simple form response - Button: %d", 
                response.getClickedButtonId()));
        }
    }

    private void handleFormCancel(PlayerFormRespondedEvent event) {
        FormTrackingData formData = plugin.getFormIdMap().remove(event.getPlayer().getName());
        if (formData != null) {
            try {
                IFormHandler handler = formRegistry.getHandler(formData.getFormId());
                if (handler != null) {
                    handler.handleCancel(event.getPlayer());
                }
            } catch (Exception e) {
                // Check for validation errors that are already shown to the player
                if (e instanceof RuntimeException && e.getMessage() != null && 
                    e.getMessage().contains("_validation_error_already_shown")) {
                    if (plugin.isDebugMode()) {
                        plugin.debug("[Form] Not showing generic error for validation error in form cancel handler");
                    }
                } else {
                    plugin.getLogger().error("Error handling form cancel", e);
                    event.getPlayer().sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
                }
            }
        }
        // Always ensure form data is cleaned up
        ensureFormDataCleanup(event.getPlayer().getName());
    }

    private void handleFormResponse(PlayerFormRespondedEvent event, IFormHandler handler, String formId) {
        if (event.getResponse() instanceof FormResponseCustom) {
            handleCustomForm(event, handler, formId);
        } else if (event.getResponse() instanceof FormResponseSimple) {
            handleSimpleForm(event, handler);
        } else {
            plugin.getLogger().error("Unknown form response type: " + event.getResponse().getClass().getName());
        }
    }

    private void handleCustomForm(PlayerFormRespondedEvent event, IFormHandler handler, String formId) {
        FormResponseCustom response = (FormResponseCustom) event.getResponse();
        
        try {
            // Use GuiManager for validation - this will now throw an exception for validation errors
            plugin.getGuiManager().validateForm(event.getPlayer(), response, formId);
            
            // Only handle the response if validation succeeds
            handler.handleResponse(event.getPlayer(), response);
        } catch (Exception e) {
            // Check for validation errors that are already shown to the player
            if (e instanceof RuntimeException && e.getMessage() != null && 
                e.getMessage().contains("_validation_error_already_shown")) {
                if (plugin.isDebugMode()) {
                    plugin.debug("[Form] Not showing generic error for validation error in custom form handler");
                }
            } else {
                plugin.getLogger().error("Error handling custom form response", e);
                event.getPlayer().sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
            }
            // Clean up form data
            ensureFormDataCleanup(event.getPlayer().getName());
        }
    }

    private void handleSimpleForm(PlayerFormRespondedEvent event, IFormHandler handler) {
        try {
            // Save the editing area data before handling response
            var editingData = plugin.getFormIdMap().get(event.getPlayer().getName() + "_editing");
            var currentFormData = plugin.getFormIdMap().get(event.getPlayer().getName());
            
            if (plugin.isDebugMode()) {
                plugin.debug("Before handling simple form response:");
                if (currentFormData != null) {
                    plugin.debug("  Current form: " + currentFormData.getFormId());
                }
                if (editingData != null) {
                    plugin.debug("  Editing area: " + editingData.getFormId());
                }
            }
            
            // Handle the form response
            handler.handleResponse(event.getPlayer(), event.getResponse());
            
            // Check if we need to restore form tracking data
            var newFormData = plugin.getFormIdMap().get(event.getPlayer().getName());
            var newEditingData = plugin.getFormIdMap().get(event.getPlayer().getName() + "_editing");
            
            if (plugin.isDebugMode()) {
                plugin.debug("After handling simple form response:");
                if (newFormData != null) {
                    plugin.debug("  New form: " + newFormData.getFormId());
                }
                if (newEditingData != null) {
                    plugin.debug("  New editing area: " + newEditingData.getFormId());
                }
            }
            
            // If we had editing data and it wasn't cleaned up by the handler, restore it
            if (editingData != null && newEditingData == null && 
                (newFormData != null && !newFormData.equals(currentFormData))) {
                plugin.getFormIdMap().put(event.getPlayer().getName() + "_editing", editingData);
                if (plugin.isDebugMode()) {
                    plugin.debug("Restored editing area data after form transition");
                }
            }
        } catch (Exception e) {
            // Check for validation errors that are already shown to the player
            if (e instanceof RuntimeException && e.getMessage() != null && 
                e.getMessage().contains("_validation_error_already_shown")) {
                if (plugin.isDebugMode()) {
                    plugin.debug("[Form] Not showing generic error for validation error in simple form handler");
                }
            } else {
                plugin.getLogger().error("Error handling simple form response", e);
                event.getPlayer().sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
            }
            // Don't automatically open main menu
            ensureFormDataCleanup(event.getPlayer().getName());
        }
    }

    private void handleFormError(PlayerFormRespondedEvent event, Exception e) {
        // Check for validation errors that are already shown to the player
        if (e instanceof RuntimeException && e.getMessage() != null && 
            e.getMessage().contains("_validation_error_already_shown")) {
            if (plugin.isDebugMode()) {
                plugin.debug("[Form] Validation error already shown to player " + event.getPlayer().getName() + 
                    ", suppressing generic error");
            }
            // Still clean up form tracking data
            String playerName = event.getPlayer().getName();
            plugin.getFormIdMap().remove(playerName);
            plugin.getFormIdMap().remove(playerName + "_editing");
            return;
        }
        
        plugin.getLogger().error("Error handling form response", e);
        if (plugin.isDebugMode()) {
            plugin.debug("[Form] Error processing form response:");
            plugin.debug("  Player: " + event.getPlayer().getName());
            plugin.debug("  Exception: " + e.getMessage());
            e.printStackTrace();
        }
        
        // Clean up form tracking data
        String playerName = event.getPlayer().getName();
        plugin.getFormIdMap().remove(playerName);
        plugin.getFormIdMap().remove(playerName + "_editing");
        
        event.getPlayer().sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
    }

    private void checkCleanup() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastCleanup > CLEANUP_INTERVAL) {
            // Only cleanup forms, not handlers
            plugin.getGuiManager().cleanup();
            // Remove formRegistry.clearHandlers() to maintain handlers
            lastCleanup = currentTime;
            
            if (plugin.isDebugMode()) {
                plugin.debug("Performed form cleanup");
            }
        }
    }

    private void ensureFormDataCleanup(String playerName) {
        // Remove all form tracking data for player
        plugin.getFormIdMap().remove(playerName);
        plugin.getFormIdMap().remove(playerName + "_editing");
        
        if (plugin.isDebugMode()) {
            plugin.debug("Cleaned up form data for player: " + playerName);
        }
    }
}