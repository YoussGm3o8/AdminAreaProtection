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

            // Create builder
            AreaBuilder builder = Area.builder()
                .name(name)
                .priority(priority)
                .showTitle(showTitle);

            // Set world from player's current world
            builder.world(player.getLevel().getName());

            // Handle coordinates if not global
            if (!isGlobal) {
                // Get coordinates (indices 6-11)
                int x1 = Integer.parseInt(response.getInputResponse(6));
                int y1 = Integer.parseInt(response.getInputResponse(7));
                int z1 = Integer.parseInt(response.getInputResponse(8));
                int x2 = Integer.parseInt(response.getInputResponse(9));
                int y2 = Integer.parseInt(response.getInputResponse(10));
                int z2 = Integer.parseInt(response.getInputResponse(11));
                builder.coordinates(x1, x2, y1, y2, z1, z2);
            } else {
                // Set global coordinates
                builder.coordinates(-29000000, 29000000, 0, 255, -29000000, 29000000);
            }

            // Only process messages if show title is enabled
            if (showTitle) {
                String enterMsg = response.getInputResponse(12);
                String leaveMsg = response.getInputResponse(13);
                if (!enterMsg.isEmpty()) builder.enterMessage(enterMsg);
                if (!leaveMsg.isEmpty()) builder.leaveMessage(leaveMsg);
            }

            // Initialize settings object
            JSONObject settings = new JSONObject();

            // Process permission toggles
            int toggleIndex = 15; // Start after the protection settings label
            for (PermissionToggle.Category category : PermissionToggle.Category.values()) {
                toggleIndex++; // Skip category label
                List<PermissionToggle> toggles = PermissionToggle.getTogglesByCategory().get(category);
                if (toggles != null) {
                    for (PermissionToggle toggle : toggles) {
                        try {
                            Boolean value = response.getToggleResponse(toggleIndex);
                            if (value == null) {
                                if (plugin.isDebugMode()) {
                                    plugin.debug("Invalid toggle response at index " + toggleIndex + " for " + toggle.getPermissionNode());
                                }
                                throw new IllegalArgumentException("Missing toggle value for " + toggle.getPermissionNode());
                            }
                            settings.put(toggle.getPermissionNode(), value);
                        } catch (Exception e) {
                            if (plugin.isDebugMode()) {
                                plugin.debug("Error processing toggle " + toggle.getPermissionNode() + ": " + e.getMessage());
                            }
                            // Only use default value if we can't get the response
                            settings.put(toggle.getPermissionNode(), toggle.getDefaultValue());
                        }
                        toggleIndex++;
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

            // Set settings and build area
            builder.settings(settings);
            Area area = builder.build();
            plugin.saveArea(area);
            
            player.sendMessage(plugin.getLanguageManager().get("messages.areaCreated", 
                Map.of("area", area.getName())));

            // Set form tracking data for edit area form
            plugin.getFormIdMap().put(player.getName(), 
                new FormTrackingData(FormIds.EDIT_AREA, System.currentTimeMillis()));

            // Store area being edited
            plugin.getFormIdMap().put(player.getName() + "_editing",
                new FormTrackingData(area.getName(), System.currentTimeMillis()));

            // Open edit form
            plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, area);

        } catch (Exception e) {
            plugin.getLogger().error("Error handling form response", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.error.errorProcessingInput"));
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
            
            addPermissionToggles(form);
            
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
        // Only clean up form data after successful form handling
        // or explicit cancellation
        if (plugin.isDebugMode()) {
            plugin.debug("Cleaning up form data for " + player.getName());
        }
        super.cleanup(player);
        // Also clean up any position data
        plugin.getPlayerPositions().remove(player.getName());
    }

    private void addPermissionToggles(FormWindowCustom form) {
        form.addElement(new ElementLabel("\n§2Protection Settings\n§7Configure area permissions"));
        
        // Add toggles by category in a consistent order
        for (PermissionToggle.Category category : PermissionToggle.Category.values()) {
            // Add category header with clear separation
            form.addElement(new ElementLabel("\n§6" + category.getDisplayName()));
            
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
                        "§e" + toggle.getDisplayName() + "\n§7" + description,
                        toggle.getDefaultValue()
                    ));
                }
            }
        }
    }
}
