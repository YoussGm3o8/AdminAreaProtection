package adminarea;

import cn.nukkit.Player;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.form.window.FormWindowSimple;
import cn.nukkit.level.Position;
import cn.nukkit.form.element.ElementInput;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.element.ElementToggle;
import cn.nukkit.form.element.ElementButtonImageData;

public class GuiManager {
    private final AdminAreaProtectionPlugin plugin;

    public GuiManager(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
    }
    
    public void openMainMenu(Player player) {
        FormWindowSimple form = new FormWindowSimple("Area Protection", "Select an action");
        form.addButton(
            new ElementButton("Create Area",
                new ElementButtonImageData(ElementButtonImageData.IMAGE_DATA_TYPE_PATH, "textures/items/paper"))
        );
        form.addButton(
            new ElementButton("Edit Area",
                new ElementButtonImageData(ElementButtonImageData.IMAGE_DATA_TYPE_PATH, "textures/items/book_writable"))
        );
        form.addButton(
            new ElementButton("Delete Area",
                new ElementButtonImageData(ElementButtonImageData.IMAGE_DATA_TYPE_PATH, "textures/ui/crossout"))
        );
        plugin.getFormIdMap().put(player.getName(), "main_menu");
        player.showFormWindow(form);
    }
    
    public void openCreateForm(Player player) {
        FormWindowCustom form = new FormWindowCustom("Create Area");
        Position[] positions = plugin.getPlayerPositions().getOrDefault(player.getName(), new Position[2]);

        String pos1x = positions[0] != null ? String.valueOf((int) positions[0].getX()) : "";
        String pos1y = positions[0] != null ? String.valueOf((int) positions[0].getY()) : "";
        String pos1z = positions[0] != null ? String.valueOf((int) positions[0].getZ()) : "";

        String pos2x = positions[1] != null ? String.valueOf((int) positions[1].getX()) : "";
        String pos2y = positions[1] != null ? String.valueOf((int) positions[1].getY()) : "";
        String pos2z = positions[1] != null ? String.valueOf((int) positions[1].getZ()) : "";

        // Area definition inputs
        form.addElement(new ElementInput("Area Name", "Enter area name"));
        form.addElement(new ElementInput("Priority", "Enter priority (integer)", "0"));
        form.addElement(new ElementToggle("Global area (full world)", false)); 
        form.addElement(new ElementInput("Pos1 X", "", pos1x));
        form.addElement(new ElementInput("Pos1 Y", "", pos1y));
        form.addElement(new ElementInput("Pos1 Z", "", pos1z));
        form.addElement(new ElementInput("Pos2 X", "", pos2x));
        form.addElement(new ElementInput("Pos2 Y", "", pos2y));
        form.addElement(new ElementInput("Pos2 Z", "", pos2z));
        
        // Replace individual permission toggles with PermissionToggle iteration
        for (PermissionToggle pt : PermissionToggle.getDefaultToggles()) {
            form.addElement(pt.toElementToggle());
        }
        
        plugin.getFormIdMap().put(player.getName(), "create_area_form");
        player.showFormWindow(form);
    }
    
    public void openEditForm(Player player, Area area) {
        FormWindowCustom form = new FormWindowCustom("Edit Area - " + area.getName());
        
        String defaultPriority = String.valueOf(area.getPriority());
        
        // Build the edit form with area details and permission toggles.
        form.addElement(new ElementInput("Area Name", "Area name", area.getName()));
        form.addElement(new ElementInput("Priority", "Enter priority (integer)", defaultPriority));
        
        // Use PermissionToggle for all permission toggles.
        for (PermissionToggle pt : PermissionToggle.getDefaultToggles()) {
            boolean defaultValue;
            if (pt.getPermissionNode().equals("show.title")) {
                defaultValue = area.isShowTitle();
            } else {
                defaultValue = area.getSettings().optBoolean(pt.getPermissionNode(), pt.getDefaultValue());
            }
            form.addElement(new ElementToggle(pt.getDisplayName(), defaultValue));
        }
        
        plugin.getFormIdMap().put(player.getName(), "edit_area");
        player.showFormWindow(form);
    }

    public void openLuckPermsOverrideForm(Player player, Area area) {
        LuckPermsOverrideForm lpForm = new LuckPermsOverrideForm(plugin);
        lpForm.openTrackSelection(player, area);
    }
    
}
