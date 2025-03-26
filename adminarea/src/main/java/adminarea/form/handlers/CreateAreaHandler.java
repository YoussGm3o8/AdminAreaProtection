package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaBuilder;
import adminarea.constants.FormIds;
import adminarea.data.FormTrackingData;
import adminarea.util.AreaValidationUtils;
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
    private static final int ENTER_TITLE_INDEX = 12;
    private static final int ENTER_MESSAGE_INDEX = 13;
    private static final int LEAVE_TITLE_INDEX = 14;
    private static final int LEAVE_MESSAGE_INDEX = 15;
    private static final int TOGGLE_START_INDEX = 17;

    private final AreaValidationUtils areaValidationUtils;

    public CreateAreaHandler(AdminAreaProtectionPlugin plugin) {
        super(plugin);
        this.areaValidationUtils = new AreaValidationUtils(plugin);
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
            String name = response.getInputResponse(NAME_INDEX);
            if (name == null || name.trim().isEmpty()) {
                player.sendMessage(plugin.getLanguageManager().get("validation.form.area.name.required"));
                cleanup(player);
                plugin.getGuiManager().openFormById(player, FormIds.CREATE_AREA, null);
                markValidationErrorShown();
                return;
            }
            
            // Add explicit check for area name format using ValidationUtils
            try {
                adminarea.util.ValidationUtils.validateAreaName(name);
            } catch (IllegalArgumentException e) {
                player.sendMessage(plugin.getLanguageManager().get("validation.area.name.format") + ": " + e.getMessage());
                cleanup(player);
                plugin.getGuiManager().openFormById(player, FormIds.CREATE_AREA, null);
                markValidationErrorShown(); // Mark that we've already shown a specific error
                return;
            }
            
            // Add explicit check for existing area names
            if (plugin.getArea(name) != null) {
                player.sendMessage(plugin.getLanguageManager().get("validation.form.area.name.exists", 
                    Map.of("name", name)));
                cleanup(player);
                plugin.getGuiManager().openFormById(player, FormIds.CREATE_AREA, null);
                markValidationErrorShown(); // Mark that we've already shown a specific error
                return;
            }
            
            int priority;
            try {
                priority = Integer.parseInt(response.getInputResponse(PRIORITY_INDEX));
                if (!areaValidationUtils.validatePriority(priority, player, FormIds.CREATE_AREA)) {
                    cleanup(player);
                    markValidationErrorShown(); // Mark that we've already shown a specific error
                    return;
                }
            } catch (NumberFormatException e) {
                player.sendMessage(plugin.getLanguageManager().get("validation.form.area.priority.notNumber"));
                cleanup(player);
                plugin.getGuiManager().openFormById(player, FormIds.CREATE_AREA, null);
                markValidationErrorShown(); // Mark that we've already shown a specific error
                return;
            }
            
            boolean showTitle = response.getToggleResponse(SHOW_TITLE_INDEX);
            boolean isGlobal = response.getToggleResponse(PROTECT_WORLD_INDEX);
            String worldName = player.getLevel().getName();

            // Get title messages if show title is enabled
            String enterTitle = "", enterMsg = "", leaveTitle = "", leaveMsg = "";
            if (showTitle) {
                enterTitle = response.getInputResponse(ENTER_TITLE_INDEX);
                enterMsg = response.getInputResponse(ENTER_MESSAGE_INDEX);
                leaveTitle = response.getInputResponse(LEAVE_TITLE_INDEX);
                leaveMsg = response.getInputResponse(LEAVE_MESSAGE_INDEX);
                
                if (enterTitle == null) enterTitle = "";
                if (enterMsg == null) enterMsg = "";
                if (leaveTitle == null) leaveTitle = "";
                if (leaveMsg == null) leaveMsg = "";
                
                // Validate message lengths
                if (!areaValidationUtils.validateMessages(enterMsg, leaveMsg, player, FormIds.CREATE_AREA)) {
                    cleanup(player);
                    markValidationErrorShown(); // Mark that we've already shown a specific error
                    return;
                }
            }

            // Extract and validate coordinates
            int[] coords;
            try {
                coords = areaValidationUtils.extractCoordinatesFromForm(response, POS1_X_INDEX, isGlobal);
            } catch (IllegalArgumentException e) {
                player.sendMessage(plugin.getLanguageManager().get("validation.form.area.selection.invalid", 
                    Map.of("error", e.getMessage())));
                // Clean up form data before reopening form
                cleanup(player);
                plugin.getGuiManager().openFormById(player, FormIds.CREATE_AREA, null);
                markValidationErrorShown(); // Mark that we've already shown a specific error
                return;
            }
            
            int x1 = coords[0], x2 = coords[1], y1 = coords[2], y2 = coords[3], z1 = coords[4], z2 = coords[5];

            // Validate area creation parameters (name, global status, overlap, size)
            if (!areaValidationUtils.validateAreaCreation(
                    name, x1, x2, y1, y2, z1, z2, worldName, isGlobal, player, FormIds.CREATE_AREA, null)) {
                // Clean up form data to prevent requiring reset command
                cleanup(player);
                markValidationErrorShown(); // Mark that we've already shown a specific error
                return;
            }

            // Initialize settings from DTO
            AreaDTO currentDTO = Area.builder()
                .name(name)
                .priority(priority)
                .showTitle(showTitle)
                .world(worldName)
                .coordinates(x1, x2, y1, y2, z1, z2)
                .enterTitle(enterTitle)
                .enterMessage(enterMsg)
                .leaveTitle(leaveTitle)
                .leaveMessage(leaveMsg)
                .build()
                .toDTO();
            
            // Process permission toggles using centralized validation
            int[] changedSettings = new int[1]; // Use array to pass by reference
            JSONObject settings;
            
            try {
                settings = areaValidationUtils.validateAndCreateToggleSettings(
                    response, TOGGLE_START_INDEX, changedSettings);
            } catch (IllegalArgumentException e) {
                player.sendMessage(plugin.getLanguageManager().get("messages.error.invalidToggles") + ": " + e.getMessage());
                // Clean up form data on validation failure
                cleanup(player);
                plugin.getGuiManager().openFormById(player, FormIds.CREATE_AREA, null);
                markValidationErrorShown(); // Mark that we've already shown a specific error
                return;
            }

            // Create area with validated settings
            Area area = AreaBuilder.fromDTO(currentDTO)
                .settings(settings)
                .build();
            
            if (plugin.isDebugMode()) {
                plugin.debug("Created area " + area.getName() + ":");
                plugin.debug("  Settings: " + settings.toString());
                plugin.debug("  Changed settings count: " + changedSettings[0]);
                plugin.debug("  Toggle states: " + settings.toString());
            }
            
            // Save title configuration if enabled
            if (showTitle) {
                areaValidationUtils.configureAreaTitles(name, enterTitle, enterMsg, leaveTitle, leaveMsg);
            }
            
            // Save the area
            if (plugin.isDebugMode()) {
                plugin.debug("Saving area to database: " + area.getName());
            }
            
            try {
                // Save directly to the database
                plugin.getDatabaseManager().saveArea(area);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Area saved successfully to database");
                }
                
                // Explicitly add to memory through area manager
                plugin.getAreaManager().addArea(area);
                
                if (plugin.isDebugMode()) {
                    plugin.debug("Area added to memory cache: " + area.getName());
                    plugin.debug("Area cache size: " + plugin.getAreaManager().getAllAreas().size());
                }
            } catch (Exception e) {
                // Skip handling if it's a validation error already handled
                if (e instanceof RuntimeException && e.getMessage() != null && e.getMessage().contains("_validation_error_already_shown")) {
                    // Just rethrow to let the parent handler deal with it
                    throw (RuntimeException) e;
                }
                
                plugin.getLogger().error("Failed to save area to database", e);
                player.sendMessage(plugin.getLanguageManager().get("messages.error.areaNotSaved"));
                cleanup(player);
                markValidationErrorShown(); // Mark that we've already shown a specific error
                return;
            }
            
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
            // Skip handling if it's a validation error already handled
            if (e instanceof RuntimeException && e.getMessage() != null && e.getMessage().contains("_validation_error_already_shown")) {
                // Just rethrow to let the parent handler deal with it
                throw (RuntimeException) e;
            }
            
            plugin.getLogger().error("Error handling form response", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.error.errorProcessingInput"));
            cleanup(player);
            // Try to reopen the form
            plugin.getGuiManager().openFormById(player, FormIds.CREATE_AREA, null);
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
                selection = Map.of(
                    "x1", 0, "x2", 0,
                    "y1", -64, "y2", 320, 
                    "z1", 0, "z2", 0
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
            
            // Title messages with placeholders suggestion
            form.addElement(new ElementInput(
                plugin.getLanguageManager().get("gui.createArea.labels.enterTitle"),
                plugin.getLanguageManager().get("gui.createArea.labels.enterTitlePlaceholder"),
                "§6Welcome to {area}"
            ));
            form.addElement(new ElementInput(
                plugin.getLanguageManager().get("gui.createArea.labels.enterMessage"),
                plugin.getLanguageManager().get("gui.createArea.labels.enterPlaceholder"),
                "§eEnjoy your stay {player}!"
            ));
            form.addElement(new ElementInput(
                plugin.getLanguageManager().get("gui.createArea.labels.leaveTitle"),
                plugin.getLanguageManager().get("gui.createArea.labels.leaveTitlePlaceholder"),
                "§6Leaving {area}"
            ));
            form.addElement(new ElementInput(
                plugin.getLanguageManager().get("gui.createArea.labels.leaveMessage"),
                plugin.getLanguageManager().get("gui.createArea.labels.leavePlaceholder"),
                "§eThank you for visiting {player}!"
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
        super.cleanup(player);
        // Additional cleanup specific to CreateAreaHandler
        // Clean up ALL form tracking data
        plugin.getFormIdMap().remove(player.getName());
        plugin.getFormIdMap().remove(player.getName() + "_editing");
        plugin.getPlayerPositions().remove(player.getName());
        if (plugin.isDebugMode()) {
            plugin.debug("Cleaned up all form data for " + player.getName() + " after area creation");
        }
    }

    @Override
    public void handleCancel(Player player) {
        //player.sendMessage(plugin.getLanguageManager().get("messages.cancelled"));
        cleanup(player);
    }
}
