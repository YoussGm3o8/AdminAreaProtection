package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaBuilder;
import adminarea.constants.FormIds;
import adminarea.data.FormTrackingData;
import adminarea.form.validation.FormValidator;
import adminarea.form.validation.ValidationResult;
import adminarea.util.ValidationUtils;
import cn.nukkit.Player;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import io.micrometer.core.instrument.Timer;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.form.element.ElementLabel;
import cn.nukkit.form.element.ElementInput;
import cn.nukkit.form.element.ElementToggle;
import adminarea.permissions.PermissionToggle;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import cn.nukkit.level.Position;
import java.util.HashMap;
import adminarea.area.AreaDTO;

public class CreateAreaHandler extends BaseFormHandler {

    private static final int NAME_INDEX = 1;
    private static final int PRIORITY_INDEX = 2;
    private static final int SHOW_TITLE_INDEX = 3;
    private static final int PROTECT_WORLD_INDEX = 4;
    private static final int POS1_X_INDEX = 6;
    private static final int POS1_Y_INDEX = 7;
    private static final int POS1_Z_INDEX = 8;
    private static final int POS2_X_INDEX = 9;
    private static final int POS2_Y_INDEX = 10;
    private static final int POS2_Z_INDEX = 11;
    private static final int ENTER_MESSAGE_INDEX = 12;
    private static final int LEAVE_MESSAGE_INDEX = 13;

    public CreateAreaHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
    }

    @Override
    public String getFormId() {
        return FormIds.CREATE_AREA;
    }

    @Override
    protected void handleCustomResponse(Player player, FormResponseCustom response) {
        if (response == null) {
            handleCancel(player);
            return;
        }

        try {
            // Get basic info
            String name = response.getInputResponse(1);
            int priority = Integer.parseInt(response.getInputResponse(2));
            boolean showTitle = response.getToggleResponse(3);
            boolean isGlobal = response.getToggleResponse(4);

            // Validate area name doesn't already exist
            if (plugin.hasArea(name)) {
                player.sendMessage(plugin.getLanguageManager().get("messages.area.exists", 
                    Map.of("area", name)));
                // Reopen form to let them try again
                plugin.getGuiManager().openFormById(player, FormIds.CREATE_AREA, null);
                return;
            }

            // Check if a global area already exists for this world when trying to create a global area
            if (isGlobal) {
                String worldName = player.getLevel().getName();
                Area existingGlobalArea = plugin.getAreaManager().getGlobalAreaForWorld(worldName);
                if (existingGlobalArea != null) {
                    player.sendMessage(plugin.getLanguageManager().get("messages.area.globalExists", 
                        Map.of("world", worldName, "area", existingGlobalArea.getName())));
                    // Reopen form to let them try again
                    plugin.getGuiManager().openFormById(player, FormIds.CREATE_AREA, null);
                    return;
                }
            }

            // Initialize coordinates
            int x1, x2, y1, y2, z1, z2;

            // Handle coordinates if not global
            if (!isGlobal) {
                // Get coordinates (indices 6-11)
                x1 = Integer.parseInt(response.getInputResponse(6));
                y1 = Integer.parseInt(response.getInputResponse(7));
                z1 = Integer.parseInt(response.getInputResponse(8));
                x2 = Integer.parseInt(response.getInputResponse(9));
                y2 = Integer.parseInt(response.getInputResponse(10));
                z2 = Integer.parseInt(response.getInputResponse(11));
            } else {
                // Set global coordinates
                x1 = -29000000;
                x2 = 29000000;
                y1 = 0;
                y2 = 255;
                z1 = -29000000;
                z2 = 29000000;
            }

            // Get enter/leave messages if show title is enabled
            String enterMsg = "", leaveMsg = "";
            if (showTitle) {
                enterMsg = response.getInputResponse(12);
                leaveMsg = response.getInputResponse(13);
                if (enterMsg == null) enterMsg = "";
                if (leaveMsg == null) leaveMsg = "";
                
                // Save title configuration to config.yml
                saveAreaTitles(name, enterMsg, leaveMsg);
            }

            // Initialize settings from DTO
            AreaDTO currentDTO = Area.builder()
                .name(name)
                .priority(priority)
                .showTitle(showTitle)
                .world(player.getLevel().getName())
                .coordinates(x1, x2, y1, y2, z1, z2)
                .enterMessage(enterMsg)
                .leaveMessage(leaveMsg)
                .build()
                .toDTO();
            
            // Create new settings object for toggles
            JSONObject settings = new JSONObject();

            // Track number of changes
            int changedSettings = 0;

            // Process permission toggles
            int toggleIndex = 15; // Start after the protection settings label
            for (PermissionToggle.Category category : PermissionToggle.Category.values()) {
                // Skip category label
                toggleIndex++;
                
                List<PermissionToggle> toggles = PermissionToggle.getTogglesByCategory().get(category);
                if (toggles != null) {
                    for (PermissionToggle toggle : toggles) {
                        try {
                            String permissionNode = toggle.getPermissionNode();
                            // Get toggle response at current index
                            Object rawResponse = response.getResponse(toggleIndex);
                            if (rawResponse instanceof Boolean) {
                                boolean toggleValue = (Boolean) rawResponse;
                                settings.put(permissionNode, toggleValue);
                                changedSettings++;
                                if (plugin.isDebugMode()) {
                                    plugin.debug("Set toggle " + permissionNode + " to " + toggleValue);
                                }
                            } else {
                                // Use default value if response is invalid
                                settings.put(permissionNode, toggle.getDefaultValue());
                                if (plugin.isDebugMode()) {
                                    plugin.debug("Using default value for " + permissionNode + ": " + toggle.getDefaultValue());
                                }
                            }
                            toggleIndex++; // Move to next toggle position
                        } catch (Exception e) {
                            plugin.getLogger().debug("Error processing toggle " + toggle.getPermissionNode() + ": " + e.getMessage());
                            settings.put(toggle.getPermissionNode(), toggle.getDefaultValue());
                            toggleIndex++; // Still need to move to next position even on error
                        }
                    }
                }
            }

            // Validate toggle dependencies and conflicts
            try {
                ValidationUtils.validateToggleDependencies(settings);
                ValidationUtils.validateToggleConflicts(settings);
            } catch (IllegalArgumentException e) {
                player.sendMessage(plugin.getLanguageManager().get("messages.error.invalidToggles") + ": " + e.getMessage());
                return;
            }

            // Create area with new settings
            Area area = AreaBuilder.fromDTO(currentDTO)
                .settings(settings)
                .build();
            
            if (plugin.isDebugMode()) {
                plugin.debug("Created area " + area.getName() + ":");
                plugin.debug("  Settings: " + settings.toString());
                plugin.debug("  Changed settings count: " + changedSettings);
                plugin.debug("  Player permissions from DTO: " + area.toDTO().playerPermissions());
                plugin.debug("  Player permissions in area: " + area.getPlayerPermissions());
                plugin.debug("  Group permissions in area: " + area.getGroupPermissions());
                plugin.debug("  Track permissions in area: " + area.getTrackPermissions());
                plugin.debug("  Toggle states: " + settings.toString());
            }
            
            // Successfully created area
            plugin.saveArea(area);
            
            player.sendMessage(plugin.getLanguageManager().get("messages.area.created",
                Map.of("area", area.getName(),
                       "world", area.getWorld(),
                       "priority", String.valueOf(area.getPriority()))));

            // Clear all form tracking data
            cleanup(player);
            
            // Open main menu instead of edit form
            plugin.getFormIdMap().put(player.getName(), 
                new FormTrackingData(FormIds.MAIN_MENU, System.currentTimeMillis()));
            plugin.getGuiManager().openMainMenu(player);

        } catch (Exception e) {
            plugin.getLogger().error("Error handling form response", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.error.errorProcessingInput"));
            cleanup(player);
        }
    }

    /**
     * Saves area title information to config.yml
     * 
     * @param areaName The area name
     * @param enterMessage The enter message
     * @param leaveMessage The leave message
     */
    private void saveAreaTitles(String areaName, String enterMessage, String leaveMessage) {
        // Create base path for area titles
        String basePath = "areaTitles." + areaName;
        
        // Set default title values
        plugin.getConfigManager().set(basePath + ".enter.main", "§6Welcome to " + areaName);
        plugin.getConfigManager().set(basePath + ".enter.subtitle", enterMessage.isEmpty() ? "§eEnjoy your stay!" : enterMessage);
        plugin.getConfigManager().set(basePath + ".enter.fadeIn", 20);
        plugin.getConfigManager().set(basePath + ".enter.stay", 40);
        plugin.getConfigManager().set(basePath + ".enter.fadeOut", 20);
        
        plugin.getConfigManager().set(basePath + ".leave.main", "§6Leaving " + areaName);
        plugin.getConfigManager().set(basePath + ".leave.subtitle", leaveMessage.isEmpty() ? "§eThank you for visiting!" : leaveMessage);
        plugin.getConfigManager().set(basePath + ".leave.fadeIn", 20);
        plugin.getConfigManager().set(basePath + ".leave.stay", 40);
        plugin.getConfigManager().set(basePath + ".leave.fadeOut", 20);
        
        // Save the config
        plugin.getConfigManager().save();
        
        if (plugin.isDebugMode()) {
            plugin.debug("Saved title config for area " + areaName);
        }
    }

    @Override
    protected void handleSimpleResponse(Player player, FormResponseSimple response) {
        throw new UnsupportedOperationException("Create area only uses custom form");
    }

    private boolean validateResponses(FormResponseCustom response) {
        // Check required fields have values
        if (response == null) return false;
        
        // Basic info validation
        String name = response.getInputResponse(NAME_INDEX);
        if (name == null || name.trim().isEmpty()) return false;
        
        // Check if area name already exists
        if (plugin.getArea(name) != null) {
            return false;
        }
        
        String priority = response.getInputResponse(PRIORITY_INDEX);
        if (priority == null || !priority.matches("\\d+")) return false;
        
        // Skip coordinates check if protecting entire world
        boolean protectWorld = response.getToggleResponse(PROTECT_WORLD_INDEX);
        if (!protectWorld) {
            // Check all coordinate fields have valid numbers
            for (int i = POS1_X_INDEX; i <= POS2_Z_INDEX; i++) {
                String coord = response.getInputResponse(i);
                if (coord == null || !coord.matches("-?\\d+")) return false;
            }
        }
        
        return true;
    }

    @Override
    public FormWindow createForm(Player player) {
        Timer.Sample formTimer = plugin.getPerformanceMonitor().startTimer();
        try {
            // Verify we have form tracking data
            FormTrackingData currentData = plugin.getFormIdMap().get(player.getName());
            if (currentData == null || !FormIds.CREATE_AREA.equals(currentData.getFormId())) {
                // Set tracking data if missing
                plugin.getFormIdMap().put(player.getName(),
                    new FormTrackingData(FormIds.CREATE_AREA, System.currentTimeMillis()));
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Setting new form tracking data for create area form");
                }
            }

            // Create form
            FormWindowCustom form = new FormWindowCustom(
                plugin.getLanguageManager().get("gui.createArea.title")
            );
            
            // Check for valid selection first
            Map<String, Object> selection = plugin.getPlayerSelection(player); 
            if (selection == null) {
                // Use default whole-world coordinates if no selection
                selection = Map.of(
                    "x1", -30000000, "x2", 30000000,
                    "y1", -64, "y2", 320, 
                    "z1", -30000000, "z2", 30000000
                );
            }

            // Add header with clear instructions
            form.addElement(new ElementLabel(plugin.getLanguageManager().get("gui.createArea.header")));
            
            // Basic information with improved descriptions
            form.addElement(new ElementInput(
                plugin.getLanguageManager().get("gui.createArea.labels.name"),
                plugin.getLanguageManager().get("gui.createArea.labels.namePlaceholder"),
                ""
            ));
            form.addElement(new ElementInput(
                plugin.getLanguageManager().get("gui.createArea.labels.priority"),
                plugin.getLanguageManager().get("gui.createArea.labels.priorityPlaceholder"),
                plugin.getLanguageManager().get("gui.createArea.labels.defaultPriority")
            ));
            form.addElement(new ElementToggle(
                plugin.getLanguageManager().get("gui.createArea.labels.showTitle"),
                true
            ));
            
            // World area toggle is now less important since positions are optional
            form.addElement(new ElementToggle(
                plugin.getLanguageManager().get("gui.createArea.labels.protectWorld"),
                false
            ));
            
            // Position inputs using wand selection
            form.addElement(new ElementLabel(plugin.getLanguageManager().get("gui.createArea.labels.bounds")));
            form.addElement(new ElementInput(
                plugin.getLanguageManager().get("gui.createArea.labels.pos1x"),
                plugin.getLanguageManager().get("gui.createArea.labels.coordPlaceholder", Map.of("axis", "X")),
                String.valueOf(selection.get("x1"))
            ));
            form.addElement(new ElementInput(
                plugin.getLanguageManager().get("gui.createArea.labels.pos1y"),
                plugin.getLanguageManager().get("gui.createArea.labels.coordPlaceholder", Map.of("axis", "Y")),
                String.valueOf(selection.get("y1"))
            ));
            form.addElement(new ElementInput(
                plugin.getLanguageManager().get("gui.createArea.labels.pos1z"),
                plugin.getLanguageManager().get("gui.createArea.labels.coordPlaceholder", Map.of("axis", "Z")),
                String.valueOf(selection.get("z1"))
            ));
            form.addElement(new ElementInput(
                plugin.getLanguageManager().get("gui.createArea.labels.pos2x"),
                plugin.getLanguageManager().get("gui.createArea.labels.coordPlaceholder", Map.of("axis", "X")),
                String.valueOf(selection.get("x2"))
            ));
            form.addElement(new ElementInput(
                plugin.getLanguageManager().get("gui.createArea.labels.pos2y"),
                plugin.getLanguageManager().get("gui.createArea.labels.coordPlaceholder", Map.of("axis", "Y")),
                String.valueOf(selection.get("y2"))
            ));
            form.addElement(new ElementInput(
                plugin.getLanguageManager().get("gui.createArea.labels.pos2z"),
                plugin.getLanguageManager().get("gui.createArea.labels.coordPlaceholder", Map.of("axis", "Z")),
                String.valueOf(selection.get("z2"))
            ));
            
            // Messages with placeholders suggestion
            form.addElement(new ElementInput(
                plugin.getLanguageManager().get("gui.createArea.labels.enterMessage"),
                plugin.getLanguageManager().get("gui.createArea.labels.enterPlaceholder"),
                ""
            ));
            form.addElement(new ElementInput(
                plugin.getLanguageManager().get("gui.createArea.labels.leaveMessage"),
                plugin.getLanguageManager().get("gui.createArea.labels.leavePlaceholder"),
                ""
            ));
            
            // Add protection settings header
            form.addElement(new ElementLabel(plugin.getLanguageManager().get("gui.createArea.labels.protection")));
            
            // Add toggles by category in a consistent order
            for (PermissionToggle.Category category : PermissionToggle.Category.values()) {
                // Add category header with clear separation
                form.addElement(new ElementLabel(plugin.getLanguageManager().get("gui.createArea.labels.categoryHeader", 
                    Map.of("category", category.getDisplayName()))));
                
                List<PermissionToggle> toggles = PermissionToggle.getTogglesByCategory().get(category);
                if (toggles != null) {
                    for (PermissionToggle toggle : toggles) {
                        // Get description from language file or use default
                        String description = plugin.getLanguageManager().get(
                            "gui.permissions.toggles." + toggle.getPermissionNode(),
                            Map.of("default", toggle.getDisplayName())
                        );
                        
                        // Add toggle with clear formatting
                        form.addElement(new ElementToggle(
                            plugin.getLanguageManager().get("gui.permissions.toggle.format",
                                Map.of("name", toggle.getDisplayName(), "description", description)),
                            toggle.getDefaultValue()
                        ));
                    }
                }
            }

            if (plugin.isDebugMode()) {
                plugin.debug("Created create area form with tracking data: " + 
                    plugin.getFormIdMap().get(player.getName()));
            }

            return form;

        } catch (Exception e) {
            plugin.getPerformanceMonitor().stopTimer(formTimer, "create_form_failed");
            plugin.getLogger().error("Error creating form", e);
            return null;
        }
    }

    @Override
    protected void cleanup(Player player) {
        // Clean up ALL form tracking data
        plugin.getFormIdMap().remove(player.getName());
        plugin.getFormIdMap().remove(player.getName() + "_editing");
        plugin.getPlayerPositions().remove(player.getName());
        if (plugin.isDebugMode()) {
            plugin.debug("Cleaned up all form data for " + player.getName() + " after area creation");
        }
    }
}
