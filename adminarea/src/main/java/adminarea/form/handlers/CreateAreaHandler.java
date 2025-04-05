package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaBuilder;
import adminarea.constants.FormIds;
import adminarea.data.FormTrackingData;
import adminarea.form.utils.FormUtils;
import adminarea.util.AreaValidationUtils;
import cn.nukkit.Player;
import cn.nukkit.form.handler.FormResponseHandler;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.form.element.ElementLabel;
import cn.nukkit.form.element.ElementInput;
import cn.nukkit.form.element.ElementToggle;
import adminarea.permissions.PermissionToggle;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import adminarea.area.AreaDTO;

/**
 * Custom form for creating a new area.
 */
public class CreateAreaHandler {
    
    private static final int NAME_INDEX = 2;
    private static final int PRIORITY_INDEX = 3;
    private static final int SHOW_TITLE_INDEX = 4;
    private static final int PROTECT_WORLD_INDEX = 6;
    private static final int POS1_X_INDEX = 8;
    private static final int POS1_Y_INDEX = 9;
    private static final int POS1_Z_INDEX = 10;
    private static final int POS2_X_INDEX = 11;
    private static final int POS2_Y_INDEX = 12;
    private static final int POS2_Z_INDEX = 13;
    private static final int ENTER_TITLE_INDEX = 14;
    private static final int ENTER_MESSAGE_INDEX = 15;
    private static final int LEAVE_TITLE_INDEX = -1;
    private static final int LEAVE_MESSAGE_INDEX = -1;
    private static final int TOGGLE_START_INDEX = 19;
    
    private final AdminAreaProtectionPlugin plugin;
    private final AreaValidationUtils areaValidationUtils;
    
    public CreateAreaHandler(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        this.areaValidationUtils = new AreaValidationUtils(plugin);
    }

    /**
     * Open the create area form for a player
     */
    public void open(Player player) {
        try {
            FormWindowCustom form = new FormWindowCustom(
                plugin.getLanguageManager().get("gui.createArea.title")
            );
            
            // Add header info
            form.addElement(new ElementLabel(plugin.getLanguageManager().get("gui.createArea.header")));
            form.addElement(new ElementLabel(plugin.getLanguageManager().get("gui.createArea.instructions")));
            
            // Area basic info
            form.addElement(new ElementInput(
                plugin.getLanguageManager().get("gui.createArea.name.label"),
                plugin.getLanguageManager().get("gui.createArea.name.placeholder"),
                ""
            ));
            
            form.addElement(new ElementInput(
                plugin.getLanguageManager().get("gui.createArea.priority.label"),
                plugin.getLanguageManager().get("gui.createArea.priority.placeholder"),
                "0"
            ));
            
            form.addElement(new ElementToggle(
                plugin.getLanguageManager().get("gui.createArea.showTitle.label"),
                false
            ));
            
            // Add section header for location settings
            form.addElement(new ElementLabel(plugin.getLanguageManager().get("gui.createArea.location.header")));
            
            form.addElement(new ElementToggle(
                plugin.getLanguageManager().get("gui.createArea.protectWorld.label"),
                false
            ));
            
            // Add custom coordinates section
            form.addElement(new ElementLabel(plugin.getLanguageManager().get("gui.createArea.coordinates.header")));
            
            // Position 1
            form.addElement(new ElementInput(
                plugin.getLanguageManager().get("gui.createArea.pos1.x"),
                plugin.getLanguageManager().get("gui.createArea.pos.placeholder"),
                String.valueOf(player.getFloorX())
            ));
            
            form.addElement(new ElementInput(
                plugin.getLanguageManager().get("gui.createArea.pos1.y"),
                plugin.getLanguageManager().get("gui.createArea.pos.placeholder"),
                String.valueOf(player.getFloorY())
            ));
            
            form.addElement(new ElementInput(
                plugin.getLanguageManager().get("gui.createArea.pos1.z"),
                plugin.getLanguageManager().get("gui.createArea.pos.placeholder"),
                String.valueOf(player.getFloorZ())
            ));
            
            // Position 2
            form.addElement(new ElementInput(
                plugin.getLanguageManager().get("gui.createArea.pos2.x"),
                plugin.getLanguageManager().get("gui.createArea.pos.placeholder"),
                String.valueOf(player.getFloorX())
            ));
            
            form.addElement(new ElementInput(
                plugin.getLanguageManager().get("gui.createArea.pos2.y"),
                plugin.getLanguageManager().get("gui.createArea.pos.placeholder"),
                String.valueOf(player.getFloorY())
            ));
            
            form.addElement(new ElementInput(
                plugin.getLanguageManager().get("gui.createArea.pos2.z"),
                plugin.getLanguageManager().get("gui.createArea.pos.placeholder"),
                String.valueOf(player.getFloorZ())
            ));
            
            // Title messages (only used if show title is enabled)
            form.addElement(new ElementInput(
                plugin.getLanguageManager().get("gui.createArea.enterTitle.label"),
                plugin.getLanguageManager().get("gui.createArea.enterTitle.placeholder"),
                ""
            ));
            
            form.addElement(new ElementInput(
                plugin.getLanguageManager().get("gui.createArea.enterMessage.label"),
                plugin.getLanguageManager().get("gui.createArea.enterMessage.placeholder"),
                ""
            ));
            
            // Create a response handler to process the form submission
            form.addHandler(FormResponseHandler.withoutPlayer(ignored -> {
                if (form.wasClosed()) {
                    handleCancel(player);
                    return;
                }
                
                try {
                    // Get area name
                    String name = form.getResponse().getInputResponse(NAME_INDEX);
                    
                    if (name == null || name.trim().isEmpty()) {
                        player.sendMessage(plugin.getLanguageManager().get("validation.form.area.name.required"));
                        FormUtils.cleanup(player);
                        open(player);
                        FormUtils.markValidationErrorShown();
                        return;
                    }
                    
                    // Add explicit check for area name format
                    try {
                        adminarea.util.ValidationUtils.validateAreaName(name);
                    } catch (IllegalArgumentException e) {
                        player.sendMessage(plugin.getLanguageManager().get("validation.area.name.format") + ": " + e.getMessage());
                        FormUtils.cleanup(player);
                        open(player);
                        FormUtils.markValidationErrorShown();
                        return;
                    }
                    
                    // Check if area already exists
                    if (plugin.getArea(name) != null) {
                        player.sendMessage(plugin.getLanguageManager().get("validation.form.area.name.exists", 
                            Map.of("name", name)));
                        FormUtils.cleanup(player);
                        open(player);
                        FormUtils.markValidationErrorShown();
                        return;
                    }
                    
                    // Get and validate priority
                    int priority;
                    try {
                        priority = Integer.parseInt(form.getResponse().getInputResponse(PRIORITY_INDEX));
                        if (!areaValidationUtils.validatePriority(priority, player, FormIds.CREATE_AREA)) {
                            FormUtils.cleanup(player);
                            FormUtils.markValidationErrorShown();
                            return;
                        }
                    } catch (NumberFormatException e) {
                        player.sendMessage(plugin.getLanguageManager().get("validation.form.area.priority.notNumber"));
                        FormUtils.cleanup(player);
                        open(player);
                        FormUtils.markValidationErrorShown();
                        return;
                    }
                    
                    // Get other form values
                    boolean showTitle = form.getResponse().getToggleResponse(SHOW_TITLE_INDEX);
                    boolean isGlobal = form.getResponse().getToggleResponse(PROTECT_WORLD_INDEX);
                    String worldName = player.getLevel().getName();
                    
                    // Process title messages if enabled
                    String enterTitle = "", enterMsg = "", leaveTitle = "", leaveMsg = "";
                    if (showTitle) {
                        enterTitle = form.getResponse().getInputResponse(ENTER_TITLE_INDEX);
                        enterMsg = form.getResponse().getInputResponse(ENTER_MESSAGE_INDEX);
                        
                        // Only try to get leave messages if the form has those fields
                        if (LEAVE_TITLE_INDEX > 0) {
                            leaveTitle = form.getResponse().getInputResponse(LEAVE_TITLE_INDEX);
                        }
                        if (LEAVE_MESSAGE_INDEX > 0) {
                            leaveMsg = form.getResponse().getInputResponse(LEAVE_MESSAGE_INDEX);
                        }
                        
                        if (enterTitle == null) enterTitle = "";
                        if (enterMsg == null) enterMsg = "";
                        if (leaveTitle == null) leaveTitle = "";
                        if (leaveMsg == null) leaveMsg = "";
                        
                        // Validate message lengths
                        if (!areaValidationUtils.validateMessages(enterMsg, leaveMsg, player, FormIds.CREATE_AREA)) {
                            FormUtils.cleanup(player);
                            FormUtils.markValidationErrorShown();
                            return;
                        }
                    }
                    
                    // Process coordinates
                    int[] coords;
                    try {
                        if (isGlobal) {
                            // For global areas, use world bounds
                            coords = new int[] {
                                -30000000, 30000000, // x1, x2
                                0, 256,              // y1, y2
                                -30000000, 30000000  // z1, z2
                            };
                        } else {
                            // Extract coordinates from form
                            String x1Str = form.getResponse().getInputResponse(POS1_X_INDEX);
                            String y1Str = form.getResponse().getInputResponse(POS1_Y_INDEX);
                            String z1Str = form.getResponse().getInputResponse(POS1_Z_INDEX);
                            String x2Str = form.getResponse().getInputResponse(POS2_X_INDEX);
                            String y2Str = form.getResponse().getInputResponse(POS2_Y_INDEX);
                            String z2Str = form.getResponse().getInputResponse(POS2_Z_INDEX);
                            
                            coords = areaValidationUtils.parseCoordinates(
                                player, x1Str, y1Str, z1Str, x2Str, y2Str, z2Str);
                        }
                    } catch (IllegalArgumentException e) {
                        player.sendMessage(plugin.getLanguageManager().get("validation.form.area.selection.invalid", 
                            Map.of("error", e.getMessage())));
                        FormUtils.cleanup(player);
                        open(player);
                        FormUtils.markValidationErrorShown();
                        return;
                    }
                    
                    int x1 = coords[0], x2 = coords[1], y1 = coords[2], y2 = coords[3], z1 = coords[4], z2 = coords[5];
                    
                    // Validate area creation parameters
                    if (!areaValidationUtils.validateAreaCreation(
                            name, x1, x2, y1, y2, z1, z2, worldName, isGlobal, player, FormIds.CREATE_AREA, null)) {
                        FormUtils.cleanup(player);
                        FormUtils.markValidationErrorShown();
                        return;
                    }
                    
                    // Initialize and create the area
                    Area newArea = new AreaBuilder()
                        .name(name)
                        .coordinates(x1, x2, y1, y2, z1, z2)
                        .world(worldName)
                        .priority(priority)
                        .build();
                    
                    // Add the area to the manager
                    plugin.getAreaManager().addArea(newArea);
                    
                    // Save area title settings if enabled
                    if (showTitle) {
                        plugin.getConfigManager().set("areaTitles." + name + ".enter.title", enterTitle);
                        plugin.getConfigManager().set("areaTitles." + name + ".enter.subtitle", enterMsg);
                        plugin.getConfigManager().set("areaTitles." + name + ".leave.title", leaveTitle);
                        plugin.getConfigManager().set("areaTitles." + name + ".leave.subtitle", leaveMsg);
                        plugin.getConfigManager().save();
                    }
                    
                    // Notify player of success
                    player.sendMessage(plugin.getLanguageManager().get("success.area.create", 
                        Map.of("area", name)));
                    
                    // Return to main menu
                    plugin.getFormIdMap().put(player.getName(),
                        new FormTrackingData(FormIds.MAIN_MENU, System.currentTimeMillis()));
                    plugin.getGuiManager().openMainMenu(player);
                    
                } catch (Exception e) {
                    plugin.getLogger().error("Error processing create area form", e);
                    player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
                    handleCancel(player);
                }
            }));
            
            // Show the form
            player.showFormWindow(form);
            
        } catch (Exception e) {
            plugin.getLogger().error("Error creating form for area creation", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
        }
    }
    
    /**
     * Handle when the form is cancelled
     */
    private void handleCancel(Player player) {
        FormUtils.cleanup(player);
        plugin.getGuiManager().openMainMenu(player);
    }
}
