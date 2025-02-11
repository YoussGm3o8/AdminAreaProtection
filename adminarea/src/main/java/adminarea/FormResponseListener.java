package adminarea;

import cn.nukkit.event.Listener;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.player.PlayerFormRespondedEvent;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowSimple;
import cn.nukkit.level.Position;
import cn.nukkit.Player;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.response.FormResponseCustom;
import org.json.JSONObject;
import java.util.List;
import java.util.HashMap;

public class FormResponseListener implements Listener {

    private final AdminAreaProtectionPlugin plugin;
    private AreaCommand areaCommand;

    public FormResponseListener(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        this.areaCommand = new AreaCommand(plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFormResponded(PlayerFormRespondedEvent event) {
        Player player = event.getPlayer();
        FormWindow window = event.getWindow();
        String playerName = player.getName();
        HashMap<String, String> formIdMap = plugin.getFormIdMap();
        String storedId = formIdMap.get(playerName);
        plugin.getLogger().info("Stored form ID for player " + playerName + ": " + storedId);
        
        // If a custom form is received, ignore the stored ID and use the form title.
        if (window instanceof FormWindowCustom) {
            formIdMap.remove(playerName);
            String title = ((FormWindowCustom) window).getTitle();
            // Branch for the Create Area form.
            if (title.equals("Create Area")) {
                // Retrieve positions set via /area pos1 and /area pos2.
                Position[] positions = plugin.getPlayerPositions().remove(playerName);
                if (event.getResponse() == null) {
                    plugin.getLogger().info("Create Area form closed without response.");
                    return;
                }
                if (!(event.getResponse() instanceof FormResponseCustom)) {
                    plugin.getLogger().warning("Unexpected response type: " + event.getResponse().getClass().getName());
                    return;
                }
                FormResponseCustom response = (FormResponseCustom) event.getResponse();
                HashMap<Integer, Object> responses = response.getResponses();
                List<Object> data = new java.util.ArrayList<>(responses.values());
                plugin.getLogger().info("Create Area response data: " + data.toString());
                try {
                    String name = data.get(0).toString();
                    // Added check to prevent empty area name
                    if (name.trim().isEmpty()) {
                        player.sendMessage("§cArea name cannot be empty.");
                        return;
                    }

                    // Check if area name already exists
                    for (Area existingArea : plugin.getAreas()) {
                        if (existingArea.getName().equalsIgnoreCase(name)) {
                            player.sendMessage("§cAn area with the name '" + name + "' already exists.");
                            return; // Cancel area creation
                        }
                    }
                    // Adjusted indices:
                    int priority = Integer.parseInt(data.get(1).toString());
                    boolean globalArea = (Boolean) data.get(2);  // Global area toggle
                    // Indices 3-8 are for Pos1/Pos2 inputs (may be empty strings)
                    int xMin, xMax, yMin, yMax, zMin, zMax;
                    if (globalArea) {
                        // Define full world bounds
                        xMin = Integer.MIN_VALUE; xMax = Integer.MAX_VALUE;
                        yMin = 0; yMax = 256;
                        zMin = Integer.MIN_VALUE; zMax = Integer.MAX_VALUE;
                    } else {
                        if (positions == null || positions.length < 2 || positions[0] == null || positions[1] == null) {
                            // ...error handling...
                            player.sendMessage("§cPositions must be set if not a global area.");
                            return;
                        }
                        xMin = (int) Math.min(positions[0].getX(), positions[1].getX());
                        xMax = (int) Math.max(positions[0].getX(), positions[1].getX());
                        yMin = (int) Math.min(positions[0].getY(), positions[1].getY());
                        yMax = (int) Math.max(positions[0].getY(), positions[1].getY());
                        zMin = (int) Math.min(positions[0].getZ(), positions[1].getZ());
                        zMax = (int) Math.max(positions[0].getZ(), positions[1].getZ());
                    }

                    // Permission toggles now start at index 9.
                    boolean showTitle      = (Boolean) data.get(9);
                    boolean allowBreak     = (Boolean) data.get(10);
                    boolean allowPlace     = (Boolean) data.get(11);
                    boolean allowFall      = (Boolean) data.get(12);
                    boolean allowPvp       = (Boolean) data.get(13);
                    boolean allowTnt       = (Boolean) data.get(14);
                    boolean allowHunger    = (Boolean) data.get(15);
                    boolean allowProjectile= (Boolean) data.get(16);
                    boolean allowFire      = (Boolean) data.get(17);
                    boolean allowFireSpread= (Boolean) data.get(18);
                    boolean allowWaterFlow = (Boolean) data.get(19);
                    boolean allowLavaFlow  = (Boolean) data.get(20);
                    boolean allowMobSpawning = (Boolean) data.get(21);
                    boolean allowItemUse   = (Boolean) data.get(22);

                    String world = player.getLevel().getFolderName();
                    Area area = new Area(name, world, xMin, xMax, yMin, yMax, zMin, zMax, priority, showTitle);
                    JSONObject settings = new JSONObject();
                    settings.put("break", allowBreak);
                    settings.put("place", allowPlace);
                    settings.put("no_fall", allowFall);
                    settings.put("pvp", allowPvp);
                    settings.put("tnt", allowTnt);
                    settings.put("hunger", allowHunger);
                    settings.put("no_projectile", allowProjectile);
                    settings.put("set_fire", allowFire);
                    settings.put("fire_spread", allowFireSpread);
                    settings.put("water_flow", allowWaterFlow);
                    settings.put("lava_flow", allowLavaFlow);
                    settings.put("mob_spawning", allowMobSpawning);
                    settings.put("item_use", allowItemUse);
                    area.setSettings(settings);
                    plugin.getLogger().info("Adding area: " + area.getName());
                    plugin.addArea(area);

                    // Update config with default title settings for this new area if not already set.
                    String areaPath = "areaTitles." + name;
                    if (!plugin.getConfig().exists(areaPath + ".enter.main")) {
                        plugin.getConfig().set(areaPath + ".enter.main", plugin.getConfig().getString("title.enter.main", "§aEntering {area}"));
                        plugin.getConfig().set(areaPath + ".enter.subtitle", plugin.getConfig().getString("title.enter.subtitle", "Welcome to {area}!"));
                        plugin.getConfig().set(areaPath + ".leave.main", plugin.getConfig().getString("title.leave.main", "§eLeaving {area}"));
                        plugin.getConfig().set(areaPath + ".leave.subtitle", plugin.getConfig().getString("title.leave.subtitle", "Goodbye from {area}!"));
                        plugin.saveConfig();
                    }

                    player.sendMessage("Area " + name + " created successfully.");
                } catch(Exception e) {
                    player.sendMessage("Error creating area: " + e.getMessage());
                    e.printStackTrace();
                }
                return;
            }
            // Branch for the Edit Area form.
            else if (title.startsWith("Edit Area -")) {
                if (event.getResponse() == null) return;
                 if (!(event.getResponse() instanceof FormResponseCustom)) {
                    plugin.getLogger().warning("Unexpected response type: " + event.getResponse().getClass().getName());
                    return;
                }
                FormResponseCustom response = (FormResponseCustom) event.getResponse();
                 HashMap<Integer, Object> responses = response.getResponses();
                List<Object> data = new java.util.ArrayList<>(responses.values());
                try {
                    String originalName = title.replace("Edit Area - ", "");
                    Area area = null;
                    for (Area a : plugin.getAreas()) {
                        if (a.getName().equalsIgnoreCase(originalName)) {
                            area = a;
                            break;
                        }
                    }
                    if (area == null) {
                        player.sendMessage("Area not found.");
                        return;
                    }
                    String name = data.get(0).toString();
                    int priority = Integer.parseInt(data.get(1).toString());
                    boolean showTitle = (Boolean) data.get(2);
                    boolean allowBreak = (Boolean) data.get(3);
                    boolean allowPlace = (Boolean) data.get(4);
                    boolean allowFall = (Boolean) data.get(5);
                    boolean allowPvp = (Boolean) data.get(6);
                    boolean allowTnt = (Boolean) data.get(7);
                    boolean allowHunger = (Boolean) data.get(8);
                    boolean allowProjectile = (Boolean) data.get(9);
                    boolean allowFire = (Boolean) data.get(10);
                    boolean allowFireSpread = (Boolean) data.get(11);
                    boolean allowWaterFlow = (Boolean) data.get(12);
                    boolean allowLavaFlow = (Boolean) data.get(13);
                    boolean allowMobSpawning = (Boolean) data.get(14);
                    boolean allowItemUse = (Boolean) data.get(15);

                    area.setName(name);
                    area.setPriority(priority);
                    area.setShowTitle(showTitle);
                    JSONObject settings = new JSONObject();
                    settings.put("break", allowBreak);
                    settings.put("place", allowPlace);
                    settings.put("no_fall", allowFall);
                    settings.put("pvp", allowPvp);
                    settings.put("tnt", allowTnt);
                    settings.put("hunger", allowHunger);
                    settings.put("no_projectile", allowProjectile);
                    settings.put("set_fire", allowFire);
                    settings.put("fire_spread", allowFireSpread);
                    settings.put("water_flow", allowWaterFlow);
                    settings.put("lava_flow", allowLavaFlow);
                    settings.put("mob_spawning", allowMobSpawning);
                    settings.put("item_use", allowItemUse);
                    area.setSettings(settings);
                    plugin.updateArea(area);
                    player.sendMessage("Area " + name + " updated successfully.");
                } catch(Exception e) {
                    player.sendMessage("Error updating area: " + e.getMessage());
                    e.printStackTrace();
                }
                return;
            }
        }
        
        // Process main menu form (should be of type FormWindowSimple).
        if (window instanceof FormWindowSimple && "main_menu".equals(storedId)) {
            formIdMap.remove(playerName);
            if (event.getResponse() == null) {
                plugin.getLogger().info("Main menu closed without response.");
                return;
            }
            FormResponseSimple response = (FormResponseSimple) event.getResponse();
            int btnId = response.getClickedButtonId();
            plugin.getLogger().info("Main menu button clicked: " + btnId);
            GuiManager gui = new GuiManager(plugin);
            switch (btnId) {
                case 0:
                    gui.openCreateForm(player);
                    break;
                case 1:
                    // Edit functionality to be selected via /area edit <name>.
                    openEditList(player);
                    break;
                case 2:
                    // Delete functionality to be selected via /area delete <name>.
                    openDeleteList(player);
                    break;
                case 3:
                    plugin.getLogger().info("Listing areas.");
                    areaCommand.sendAreaList(player);
                    break;
                default:
                    player.sendMessage("Unrecognized option.");
            }
            return;
        }
        // Handle response from edit area selection list
        else if (window instanceof FormWindowSimple && "edit_area_select".equals(storedId)) {
            formIdMap.remove(playerName);
            FormResponseSimple response = (FormResponseSimple) event.getResponse();
            if (response != null) {
                int buttonId = response.getClickedButtonId();
                List<Area> areas = plugin.getAreas();
                if (buttonId >= 0 && buttonId < areas.size()) {
                    Area selectedArea = areas.get(buttonId);
                    GuiManager gui = new GuiManager(plugin);
                    gui.openEditForm(player, selectedArea);
                }
            }
        }
        // Handle response from delete area selection list
        else if (window instanceof FormWindowSimple && "delete_area_select".equals(storedId)) {
            formIdMap.remove(playerName);
            FormResponseSimple response = (FormResponseSimple) event.getResponse();
            if (response != null) {
                int buttonId = response.getClickedButtonId();
                List<Area> areas = plugin.getAreas();
                if (buttonId >= 0 && buttonId < areas.size()) {
                    Area areaToDelete = areas.get(buttonId);
                    plugin.removeArea(areaToDelete);
                    player.sendMessage("§aArea '" + areaToDelete.getName() + "' deleted successfully.");
                }
            }
        }
    }

    private void openEditList(Player player) {
        FormWindowSimple form = new FormWindowSimple("Edit Area", "Select an area to edit:");
        List<Area> areas = plugin.getAreas();
        if (areas.isEmpty()) {
            player.sendMessage("§eThere are no protected areas to edit.");
            return;
        }
        for (Area area : areas) {
            // Updated button label to include the world name in parentheses.
            form.addButton(new ElementButton(area.getName() + " (" + area.getWorld() + ")"));
        }
        plugin.getFormIdMap().put(player.getName(), "edit_area_select");
        player.showFormWindow(form);
    }

    private void openDeleteList(Player player) {
        FormWindowSimple form = new FormWindowSimple("Delete Area", "Select an area to delete:");
        List<Area> areas = plugin.getAreas();
        if (areas.isEmpty()) {
            player.sendMessage("§eThere are no protected areas to delete.");
            return;
        }
        for (Area area : areas) {
            form.addButton(new ElementButton(area.getName() + " (" + area.getWorld() + ")"));
        }
        plugin.getFormIdMap().put(player.getName(), "delete_area_select");
        player.showFormWindow(form);
    }
}