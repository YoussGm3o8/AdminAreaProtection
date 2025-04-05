package adminarea.listeners;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.constants.FormIds;
import adminarea.data.FormTrackingData;
import adminarea.form.utils.FormUtils;
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

    public FormResponseListener(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
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
            // No need to handle form cancel with the MOT structure - it's handled automatically
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
                plugin.debug("Processing form response for formId: " + formId);
                formLogger.logFormData(event.getPlayer().getName());
            }

            logDebugInfo(event, formId);
            
            // Handle special cases for forms that need additional processing
            if (formId.equals(FormIds.GROUP_PERMISSIONS)) {
                handleGroupPermissionsForm(event);
            } else if (formId.equals(FormIds.PLAYER_SETTINGS)) {
                handlePlayerSettingsForm(event);
            } else if (formId.equals(FormIds.PLUGIN_SETTINGS)) {
                handlePluginSettingsForm(event);
            }
            
            // Only remove form data after successful response handling
            ensureFormDataCleanup(event.getPlayer().getName());

        } catch (Exception e) {
            handleFormError(event, e);
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "form_response_handle");
        }
    }

    private void logDebugInfo(PlayerFormRespondedEvent event, String formId) {
        if (!plugin.isDebugMode()) return;
        
        plugin.debug(String.format("[Form] Processing form response:"));
        plugin.debug(String.format("  Player: %s", event.getPlayer().getName()));
        plugin.debug(String.format("  Form ID: %s", formId));
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
    
    private void handleGroupPermissionsForm(PlayerFormRespondedEvent event) {
        try {
            // Get current area being edited
            FormTrackingData areaData = plugin.getFormIdMap().get(event.getPlayer().getName() + "_editing");
            if (areaData == null) {
                if (plugin.isDebugMode()) {
                    plugin.debug("No area data found for group permissions form");
                }
                return;
            }
            
            // Get the area from the area manager
            Area area = plugin.getArea(areaData.getFormId());
            if (area == null) {
                if (plugin.isDebugMode()) {
                    plugin.debug("Area not found: " + areaData.getFormId());
                }
                return;
            }
            
            // Check if we're in group selection or permission editing mode
            FormWindow window = event.getWindow();
            if (window instanceof FormWindowSimple) {
                // This is the group selection form
                plugin.getFormModuleManager().getGroupPermissionsHandler()
                    .handleGroupSelectionResponse(event.getPlayer(), area, event.getResponse());
            } else if (window instanceof FormWindowCustom) {
                // This is the permission editing form
                // Get the selected group from the form title
                String selectedGroup = "default"; // Fallback
                
                // Extract the group name from the form title
                // The title format is expected to be "Group Permissions: [group]"
                String formTitle = ((FormWindowCustom) window).getTitle();
                if (formTitle != null && formTitle.contains(": ")) {
                    selectedGroup = formTitle.substring(formTitle.lastIndexOf(": ") + 2);
                    if (plugin.isDebugMode()) {
                        plugin.debug("Extracted group name from form title: " + selectedGroup);
                    }
                } else {
                    // Try to get from form tracking data
                    FormTrackingData groupData = plugin.getFormIdMap().get(event.getPlayer().getName() + "_selected_group");
                    if (groupData != null) {
                        selectedGroup = groupData.getFormId();
                        if (plugin.isDebugMode()) {
                            plugin.debug("Retrieved group name from tracking data: " + selectedGroup);
                        }
                    } else if (plugin.isDebugMode()) {
                        plugin.debug("Using default group name: " + selectedGroup);
                    }
                }
                
                plugin.getFormModuleManager().getGroupPermissionsHandler()
                    .handleGroupPermissionResponse(event.getPlayer(), area, selectedGroup, event.getResponse());
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error handling group permissions form", e);
            event.getPlayer().sendMessage("§cAn error occurred while processing your form submission.");
        }
    }

    private void handlePlayerSettingsForm(PlayerFormRespondedEvent event) {
        try {
            // Get current area being edited
            FormTrackingData areaData = plugin.getFormIdMap().get(event.getPlayer().getName() + "_editing");
            if (areaData == null) {
                if (plugin.isDebugMode()) {
                    plugin.debug("No area data found for player settings form");
                }
                return;
            }
            
            // Get the area from the area manager
            Area area = plugin.getArea(areaData.getFormId());
            if (area == null) {
                if (plugin.isDebugMode()) {
                    plugin.debug("Area not found: " + areaData.getFormId());
                }
                return;
            }
            
            // Check if we're in player selection or permission editing mode
            FormWindow window = event.getWindow();
            if (window instanceof FormWindowCustom) {
                String formTitle = ((FormWindowCustom) window).getTitle();
                
                if (formTitle.startsWith("Player Permissions for ")) {
                    // This is the player selection form
                    plugin.getFormModuleManager().getPlayerSettingsHandler()
                        .handlePlayerSelectionResponse(event.getPlayer(), area, event.getResponse());
                } else if (formTitle.startsWith("Permissions for ")) {
                    // This is the permission editing form - extract the player name from the title
                    String targetPlayer = formTitle.substring("Permissions for ".length());
                    plugin.getFormModuleManager().getPlayerSettingsHandler()
                        .handlePlayerPermissionResponse(event.getPlayer(), area, targetPlayer, event.getResponse());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error handling player settings form", e);
            event.getPlayer().sendMessage("§cAn error occurred while processing your form submission.");
        }
    }

    private void handlePluginSettingsForm(PlayerFormRespondedEvent event) {
        try {
            // This is a simpler form without area context
            FormWindow window = event.getWindow();
            if (window instanceof FormWindowCustom) {
                String formTitle = ((FormWindowCustom) window).getTitle();
                
                if (formTitle.equals("Plugin Settings")) {
                    // Process the plugin settings form
                    plugin.getFormModuleManager().getPluginSettingsHandler()
                        .handleResponse(event.getPlayer(), event.getResponse());
                }
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error handling plugin settings form", e);
            event.getPlayer().sendMessage("§cAn error occurred while processing your form submission.");
        }
    }

    private void handleFormError(PlayerFormRespondedEvent event, Exception e) {
        // Check for validation errors that are already shown to the player
        if (e instanceof RuntimeException && e.getMessage() != null && 
            e.getMessage().contains("_validation_error_already_shown")) {
            if (plugin.isDebugMode()) {
                plugin.debug("Validation error already shown to player: " + e.getMessage());
            }
            return;
        }
        
        plugin.getLogger().error(String.format("Error handling form response from %s: %s", 
            event.getPlayer().getName(), e.getMessage()), e);
        event.getPlayer().sendMessage("§cAn error occurred while processing your form submission.");
    }

    private void checkCleanup() {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastCleanup > CLEANUP_INTERVAL) {
            lastCleanup = currentTime;
            plugin.getGuiManager().cleanup();
            if (plugin.isDebugMode()) {
                plugin.debug("Performed scheduled form data cleanup");
            }
        }
    }

    private void ensureFormDataCleanup(String playerName) {
        // Clean up form tracking data
        plugin.getFormIdMap().remove(playerName);
    }
}