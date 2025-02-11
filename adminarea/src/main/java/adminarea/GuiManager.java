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
        
        // Permissions toggles
        form.addElement(new ElementToggle("Show Title on Enter/Exit", true));
        form.addElement(new ElementToggle("Allow Block Break", true));      // key: "break"
        form.addElement(new ElementToggle("Allow Block Place", true));      // key: "place"
        form.addElement(new ElementToggle("Allow Fall Damage", true));      // key: "no_fall"
        form.addElement(new ElementToggle("Allow PvP", true));              // key: "pvp"
        form.addElement(new ElementToggle("Allow TNT", true));              // key: "tnt"
        form.addElement(new ElementToggle("Allow Hunger", true));           // key: "hunger"
        form.addElement(new ElementToggle("Allow Projectile", true));       // key: "no_projectile"
        form.addElement(new ElementToggle("Allow Fire", true));             // key: "set_fire"
        form.addElement(new ElementToggle("Allow Fire Spread", true));      // key: "fire_spread"
        form.addElement(new ElementToggle("Allow Water Flow", true));       // key: "water_flow"
        form.addElement(new ElementToggle("Allow Lava Flow", true));        // key: "lava_flow"
        form.addElement(new ElementToggle("Allow Mob Spawning", true));     // key: "mob_spawning"
        form.addElement(new ElementToggle("Allow Item Use", true));         // key: "item_use"
        
        plugin.getFormIdMap().put(player.getName(), "create_area_form");
        player.showFormWindow(form);
    }
    
    public void openEditForm(Player player, Area area) {
        FormWindowCustom form = new FormWindowCustom("Edit Area - " + area.getName());
        
        String defaultPriority = String.valueOf(area.getPriority());
        // Retrieve permission toggles from area settings; default to true if not set.
        boolean allowBreak      = area.getSettings().optBoolean("break", true);
        boolean allowPlace      = area.getSettings().optBoolean("place", true);
        boolean allowFall       = area.getSettings().optBoolean("no_fall", true);
        boolean allowPvP        = area.getSettings().optBoolean("pvp", true);
        boolean allowTNT        = area.getSettings().optBoolean("tnt", true);
        boolean allowHunger     = area.getSettings().optBoolean("hunger", true);
        boolean allowProjectile = area.getSettings().optBoolean("no_projectile", true);
        boolean allowFire       = area.getSettings().optBoolean("set_fire", true);
        boolean allowFireSpread = area.getSettings().optBoolean("fire_spread", true);
        boolean allowWaterFlow  = area.getSettings().optBoolean("water_flow", true);
        boolean allowLavaFlow   = area.getSettings().optBoolean("lava_flow", true);
        boolean allowMobSpawn   = area.getSettings().optBoolean("mob_spawning", true);
        boolean allowItemUse    = area.getSettings().optBoolean("item_use", true);
        boolean showTitle       = area.isShowTitle();
        
        // Build the edit form with area details and permission toggles.
        form.addElement(new ElementInput("Area Name", "Area name", area.getName()));
        form.addElement(new ElementInput("Priority", "Enter priority (integer)", defaultPriority));
        form.addElement(new ElementToggle("Show Title on Enter/Exit", showTitle));
        form.addElement(new ElementToggle("Allow Block Break", allowBreak));
        form.addElement(new ElementToggle("Allow Block Place", allowPlace));
        form.addElement(new ElementToggle("Allow Fall Damage", allowFall));
        form.addElement(new ElementToggle("Allow PvP", allowPvP));
        form.addElement(new ElementToggle("Allow TNT", allowTNT));
        form.addElement(new ElementToggle("Allow Hunger", allowHunger));
        form.addElement(new ElementToggle("Allow Projectile", allowProjectile));
        form.addElement(new ElementToggle("Allow Fire", allowFire));
        form.addElement(new ElementToggle("Allow Fire Spread", allowFireSpread));
        form.addElement(new ElementToggle("Allow Water Flow", allowWaterFlow));
        form.addElement(new ElementToggle("Allow Lava Flow", allowLavaFlow));
        form.addElement(new ElementToggle("Allow Mob Spawning", allowMobSpawn));
        form.addElement(new ElementToggle("Allow Item Use", allowItemUse));
        
        plugin.getFormIdMap().put(player.getName(), "edit_area");
        player.showFormWindow(form);
    }
}
