package adminarea.form.handlers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.constants.AdminAreaConstants;
import adminarea.form.IFormHandler;
import adminarea.permissions.PermissionToggle;
import cn.nukkit.Player;
import cn.nukkit.form.element.ElementInput;
import cn.nukkit.form.element.ElementToggle;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowCustom;
import org.json.JSONObject;

public class EditAreaHandler implements IFormHandler {
    private final AdminAreaProtectionPlugin plugin;

    public EditAreaHandler(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getFormId() {
        return AdminAreaConstants.FORM_EDIT_AREA;
    }

    @Override
    public FormWindow createForm(Player player) {
        String areaName = plugin.getFormIdMap().get(player.getName() + "_editing");
        Area area = plugin.getArea(areaName);
        
        if (area == null) {
            return null;
        }

        FormWindowCustom form = new FormWindowCustom("Edit Area: " + area.getName());
        form.addElement(new ElementInput("Area Name", "Enter new name", area.getName()));
        form.addElement(new ElementInput("Priority", "Enter new priority", String.valueOf(area.getPriority())));

        // Add permission toggles
        JSONObject settings = area.getSettings();
        for (PermissionToggle toggle : PermissionToggle.getDefaultToggles()) {
            boolean current = settings.optBoolean(toggle.getPermissionNode(), toggle.getDefaultValue());
            form.addElement(new ElementToggle(toggle.getDisplayName(), current));
        }
        
        return form;
    }

    @Override
    public void handleResponse(Player player, Object response) {
        if (!(response instanceof FormResponseCustom)) return;
        
        FormResponseCustom customResponse = (FormResponseCustom) response;
        String areaName = plugin.getFormIdMap().get(player.getName() + "_editing");
        Area oldArea = plugin.getArea(areaName);
        
        if (oldArea == null) {
            player.sendMessage("§cArea not found");
            return;
        }

        try {
            String newName = customResponse.getInputResponse(0);
            int priority = Integer.parseInt(customResponse.getInputResponse(1));
            
            // Create new area using builder with updated values
            Area newArea = Area.builder()
                .name(newName)
                .world(oldArea.getWorld()) // Keep the original world
                .coordinates(
                    Integer.parseInt(customResponse.getInputResponse(2)), // xMin
                    Integer.parseInt(customResponse.getInputResponse(3)), // xMax
                    Integer.parseInt(customResponse.getInputResponse(4)), // yMin
                    Integer.parseInt(customResponse.getInputResponse(5)), // yMax
                    Integer.parseInt(customResponse.getInputResponse(6)), // zMin
                    Integer.parseInt(customResponse.getInputResponse(7))  // zMax
                )
                .priority(priority)
                .showTitle(oldArea.isShowTitle())
                .build();

            // Process permission toggles starting at index 8
            JSONObject settings = new JSONObject();
            int toggleIndex = 8;
            for (PermissionToggle toggle : PermissionToggle.getDefaultToggles()) {
                settings.put(toggle.getPermissionNode(), customResponse.getToggleResponse(toggleIndex++));
            }
            newArea.setSettings(settings);

            // Get custom messages
            newArea.setEnterMessage(customResponse.getInputResponse(customResponse.getResponses().size() - 2));
            newArea.setLeaveMessage(customResponse.getInputResponse(customResponse.getResponses().size() - 1));

            // Copy over group permissions from old area
            newArea.setGroupPermissions(oldArea.getGroupPermissions());

            // Remove old area and add new one
            plugin.removeArea(oldArea);
            plugin.addArea(newArea);
            plugin.saveArea(newArea);
            
            player.sendMessage(String.format(AdminAreaConstants.MSG_AREA_UPDATED, newName));
            
        } catch (NumberFormatException e) {
            player.sendMessage("§cInvalid number format in input fields");
        } catch (Exception e) {
            player.sendMessage("§cError updating area: " + e.getMessage());
            if (plugin.getConfigManager().isDebugEnabled()) {
                e.printStackTrace();
            }
        }
    }
}
