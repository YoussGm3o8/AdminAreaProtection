package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.area.AreaBuilder;
import adminarea.constants.AdminAreaConstants;
import adminarea.form.IFormHandler;
import adminarea.permissions.PermissionToggle;
import cn.nukkit.Player;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.form.element.ElementInput;
import cn.nukkit.form.element.ElementToggle;
import cn.nukkit.level.Position;
import org.json.JSONObject;

public class CreateAreaHandler implements IFormHandler {
    private final AdminAreaProtectionPlugin plugin;

    public CreateAreaHandler(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getFormId() {
        return AdminAreaConstants.FORM_CREATE_AREA;
    }

    @Override
    public FormWindow createForm(Player player) {
        FormWindowCustom form = new FormWindowCustom("Create Area");
        form.addElement(new ElementInput("Area Name", "Enter area name"));

        Position[] positions = plugin.getPlayerPositions().get(player.getName());
        // If positions are set, pre-fill the coordinates
        if (positions != null && positions[0] != null && positions[1] != null) {
            Position pos1 = positions[0];
            Position pos2 = positions[1];
            form.addElement(new ElementInput("Min X", "Enter min X", String.valueOf(Math.min(pos1.getFloorX(), pos2.getFloorX()))));
            form.addElement(new ElementInput("Min Y", "Enter min Y", String.valueOf(Math.min(pos1.getFloorY(), pos2.getFloorY()))));
            form.addElement(new ElementInput("Min Z", "Enter min Z", String.valueOf(Math.min(pos1.getFloorZ(), pos2.getFloorZ()))));
            form.addElement(new ElementInput("Max X", "Enter max X", String.valueOf(Math.max(pos1.getFloorX(), pos2.getFloorX()))));
            form.addElement(new ElementInput("Max Y", "Enter max Y", String.valueOf(Math.max(pos1.getFloorY(), pos2.getFloorY()))));
            form.addElement(new ElementInput("Max Z", "Enter max Z", String.valueOf(Math.max(pos1.getFloorZ(), pos2.getFloorZ()))));
        } else {
            // Empty fields if no positions are set
            form.addElement(new ElementInput("Min X", "Enter min X"));
            form.addElement(new ElementInput("Min Y", "Enter min Y"));
            form.addElement(new ElementInput("Min Z", "Enter min Z"));
            form.addElement(new ElementInput("Max X", "Enter max X"));
            form.addElement(new ElementInput("Max Y", "Enter max Y"));
            form.addElement(new ElementInput("Max Z", "Enter max Z"));
        }
        form.addElement(new ElementInput("Priority", "Enter priority (default: 0)", "0"));
        form.addElement(new ElementToggle("Global Area", false));
        
        // Add permission toggles
        for (PermissionToggle toggle : PermissionToggle.getDefaultToggles()) {
            form.addElement(new ElementToggle(toggle.getDisplayName(), toggle.getDefaultValue()));
        }
        
        return form;
    }

    @Override
    public void handleResponse(Player player, Object response) {
        if (!(response instanceof FormResponseCustom)) return;
        
        FormResponseCustom customResponse = (FormResponseCustom) response;
        try {
            // Validate form data first
            if (!plugin.getGuiManager().validateForm(player, customResponse, getFormId())) {
                return;
            }

            // Basic info
            String name = customResponse.getInputResponse(0);
            int priority = Integer.parseInt(customResponse.getInputResponse(1));
            
            // Check if responses map contains the toggle response
            boolean isGlobal = false;
            if (customResponse.getResponse(2) instanceof Boolean) {
                isGlobal = (Boolean) customResponse.getResponse(2);
            }
            
            // Build area using coordinates
            AreaBuilder builder = Area.builder()
                .name(name)
                .world(player.getLevel().getName())
                .priority(priority);

            if (isGlobal) {
                builder.coordinates(
                    Integer.MIN_VALUE, Integer.MAX_VALUE,
                    0, 255,
                    Integer.MIN_VALUE, Integer.MAX_VALUE
                );
            } else {
                // Parse coordinates safely
                int xMin = parseCoordinate(customResponse.getInputResponse(3), player.getFloorX());
                int xMax = parseCoordinate(customResponse.getInputResponse(4), player.getFloorX());
                int yMin = parseCoordinate(customResponse.getInputResponse(5), player.getFloorY());
                int yMax = parseCoordinate(customResponse.getInputResponse(6), player.getFloorY());
                int zMin = parseCoordinate(customResponse.getInputResponse(7), player.getFloorZ());
                int zMax = parseCoordinate(customResponse.getInputResponse(8), player.getFloorZ());

                builder.coordinates(xMin, xMax, yMin, yMax, zMin, zMax);
            }

            // Process permission toggles safely using standardized nodes
            JSONObject settings = new JSONObject();
            int toggleIndex = 9; // Adjust based on your form layout
            for (PermissionToggle toggle : PermissionToggle.getDefaultToggles()) {
                Object toggleResponse = customResponse.getResponse(toggleIndex++);
                boolean value = toggleResponse instanceof Boolean ? (Boolean) toggleResponse : toggle.getDefaultValue();
                settings.put(toggle.getPermissionNode(), value);
            }
            builder.settings(settings);

            // Build and save the area
            Area area = builder.build();

            // Set messages safely
            int lastIndex = customResponse.getResponses().size() - 1;
            String enterMsg = customResponse.getInputResponse(lastIndex - 1);
            String leaveMsg = customResponse.getInputResponse(lastIndex);
            if (enterMsg != null) area.setEnterMessage(enterMsg);
            if (leaveMsg != null) area.setLeaveMessage(leaveMsg);

            // Add and save the area
            plugin.addArea(area);
            plugin.saveArea(area);
            
            player.sendMessage(String.format(AdminAreaConstants.MSG_AREA_CREATED, name));
            
        } catch (Exception e) {
            player.sendMessage("§cError creating area: " + e.getMessage());
            if (plugin.getConfigManager().isDebugEnabled()) {
                e.printStackTrace();
            }
        }
    }

    private int parseCoordinate(String input, int playerPos) {
        if (input == null || input.isEmpty() || input.equals("~")) {
            return playerPos;
        }
        if (input.startsWith("~")) {
            try {
                return playerPos + Integer.parseInt(input.substring(1));
            } catch (NumberFormatException e) {
                return playerPos;
            }
        }
        try {
            return Integer.parseInt(input);
        } catch (NumberFormatException e) {
            return playerPos;
        }
    }
}
