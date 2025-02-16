package adminarea.area;

import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.command.data.CommandParameter;
import cn.nukkit.command.data.CommandParamType;
import cn.nukkit.Player;
import cn.nukkit.level.Position;

import java.util.List;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.constants.AdminAreaConstants;
import adminarea.logging.PluginLogger;
import adminarea.managers.GuiManager;
import adminarea.stats.AreaStatistics;

import java.util.HashMap;

public class AreaCommand extends Command {

    private final AdminAreaProtectionPlugin plugin;
    public static final String CREATE_AREA_FORM_ID = "create_area_form";

    public AreaCommand(AdminAreaProtectionPlugin plugin) {
        super("area", "Manage protected areas", "/area <create|edit|delete|list>");
        this.plugin = plugin;
        this.setAliases(new String[]{"areaprotect", "areaadmin"});
        // Define command parameters for built-in tab completion
        this.commandParameters.clear();
        this.commandParameters.put("default", new CommandParameter[]{
            CommandParameter.newEnum("subCommand", new String[]{"create", "list", "wand", "pos1", "pos2", "help", "bypass"})
        });
        this.commandParameters.put("edit", new CommandParameter[]{
            CommandParameter.newEnum("subCommand", new String[]{"edit"}),
            CommandParameter.newType("areaName", CommandParamType.STRING)
        });
        this.commandParameters.put("delete", new CommandParameter[]{
            CommandParameter.newEnum("subCommand", new String[]{"delete"}),
            CommandParameter.newType("areaName", CommandParamType.STRING)
        });
    }

    @Override
    public String[] getAliases() {
        return new String[]{"areaprotect", "areaadmin"};
    }
    
    @Override
    public boolean execute(CommandSender sender, String commandLabel, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command can only be used in-game.");
            return true;
        }

        Player player = (Player) sender;

        // Debug: Print the arguments received
        plugin.getLogger().info("Arguments received: " + String.join(", ", args));

        if (!player.hasPermission("adminarea.command.area")) {
            plugin.getLogger().info("Player " + player.getName() + " does not have adminarea.command.area permission.");
            player.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            // Instead of showing help, open a main menu GUI.
            openMainMenu(player);
            return true;
        }

        String subCommand = args[0].toLowerCase();
        switch (subCommand) {
            case "pos1":
                handlePositionCommand(player, 0, "1");
                return true;
            case "pos2":
                handlePositionCommand(player, 1, "2");
                return true;
            case "create":
                // Check for LuckPerms-driven permission or allow regular player creation via config.
                boolean allowRegularCreation = plugin.getConfig().getBoolean("allowRegularAreaCreation", false);
                if (!player.hasPermission("adminarea.command.area.create") && !allowRegularCreation) {
                    plugin.getLogger().info("Player " + player.getName() + " lacks permission to create areas.");
                    player.sendMessage("§cYou don't have permission to create areas.");
                    return true;
                }
                if (args.length > 1 && args[1].equalsIgnoreCase("global")) {
                    openGlobalCreateForm(player);
                    return true;
                }
                openCreateForm(player);
                return true;
            case "edit":
                if (!player.hasPermission("adminarea.command.area.edit")) {
                    plugin.getLogger().info("Player " + player.getName() + " does not have adminarea.command.area.edit permission.");
                    player.sendMessage("§cYou don't have permission to edit areas.");
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage("§cUsage: /area edit <areaName>");
                    return true;
                }
                Area area = getAreaByName(args[1]);
                if (area == null) {
                    player.sendMessage("§cArea not found: " + args[1]);
                    return true;
                }
                openEditForm(player, area);
                return true;
            case "delete":
                if (!player.hasPermission("adminarea.command.area.delete")) {
                    plugin.getLogger().info("Player " + player.getName() + " does not have adminarea.command.area.delete permission.");
                    player.sendMessage("§cYou don't have permission to delete areas.");
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage("§cUsage: /area delete <areaName>");
                    return true;
                }
                Area areaToDelete = getAreaByName(args[1]);
                if (areaToDelete == null) {
                    player.sendMessage("§cArea not found: " + args[1]);
                    return true;
                }
                plugin.removeArea(areaToDelete);
                player.sendMessage("§aArea '" + args[1] + "' deleted successfully.");
                return true;
            case "list":
                if (!player.hasPermission("adminarea.command.area.list")) {
                    plugin.getLogger().info("Player " + player.getName() + " does not have adminarea.command.area.list permission.");
                    player.sendMessage("§cYou don't have permission to list areas.");
                    return true;
                }
                sendAreaList(player);
                return true;
            case "wand":
                if (!player.hasPermission("adminarea.wand.use")) {
                    player.sendMessage("§cYou don't have permission to use the wand.");
                    return true;
                }
                int wandItemType = plugin.getConfigManager().getWandItemType();
                giveWandToPlayer(player, wandItemType);
                return true;
            case "bypass":
                if (!player.hasPermission("adminarea.command.area.bypass")) {
                    plugin.getLogger().info("Player " + player.getName() + " does not have adminarea.command.area.bypass permission.");
                    player.sendMessage("§cYou don't have permission to use the bypass command.");
                    return true;
                }
                // Prevent bypass from interfering with LuckPerms integration.
                if (plugin.getLuckPermsApi() != null) {
                    player.sendMessage("§cBypass mode disabled when LuckPerms integration is active.");
                    return true;
                }
                plugin.toggleBypass(player.getName());
                boolean bypassing = plugin.isBypassing(player.getName());
                player.sendMessage("§aBypass mode " + (bypassing ? "enabled" : "disabled") + ".");
                return true;
            case "merge":
                if (args.length < 3) {
                    player.sendMessage("§cUsage: /area merge <area1> <area2>");
                    return true;
                }
                Area area1 = plugin.getArea(args[1]);
                Area area2 = plugin.getArea(args[2]);
                if (area1 == null || area2 == null) {
                    player.sendMessage("§cOne or both areas not found!");
                    return true;
                }
                Area mergedArea = plugin.getAreaManager().mergeAreas(area1, area2);
                plugin.addArea(mergedArea);
                player.sendMessage("§aAreas merged successfully into: " + mergedArea.getName());
                return true;

            case "visualize":
                if (args.length < 2) {
                    player.sendMessage("§cUsage: /area visualize <areaName>");
                    return true;
                }
                Area visualArea = plugin.getArea(args[1]);
                if (visualArea == null) {
                    player.sendMessage("§cArea not found!");
                    return true;
                }
                plugin.getAreaManager().visualizeArea(player, visualArea);
                player.sendMessage("§aVisualization started for area: " + visualArea.getName());
                return true;

            case "stats":
                if (args.length < 2) {
                    player.sendMessage("§cUsage: /area stats <name> [export|reset]");
                    return true;
                }
                Area statsArea = plugin.getArea(args[1]);
                if (statsArea == null) {
                    player.sendMessage("§cArea not found!");
                    return true;
                }

                // Handle subcommands
                if (args.length > 2) {
                    switch (args[2].toLowerCase()) {
                        case "export":
                            if (!player.hasPermission("adminarea.stats.export")) {
                                player.sendMessage("§cYou don't have permission to export statistics!");
                                return true;
                            }
                            plugin.getAreaManager().getAreaStats(statsArea.getName())
                                .exportStats(plugin.getDataFolder().toPath().resolve("stats_export"));
                            player.sendMessage("§aStatistics exported successfully!");
                            return true;
                        case "reset":
                            if (!player.hasPermission("adminarea.stats.reset")) {
                                player.sendMessage("§cYou don't have permission to reset statistics!");
                                return true;
                            }
                            plugin.getAreaManager().getAreaStats(statsArea.getName()).cleanup();
                            player.sendMessage("§aStatistics reset successfully!");
                            return true;
                    }
                }

                // Default to viewing stats
                if (!player.hasPermission("adminarea.stats.view")) {
                    player.sendMessage("§cYou don't have permission to view statistics!");
                    return true;
                }
                sendAreaStats(player, statsArea);
                return true;
            case "undo":
                if (!player.hasPermission("adminarea.wand.undo")) {
                    player.sendMessage("§cYou don't have permission to undo selections.");
                    return true;
                }
                ((AdminAreaProtectionPlugin) plugin.getServer().getPluginManager().getPlugin("AdminAreaProtection"))
                    .getWandListener().undo(player);
                return true;

            case "clear":
                if (!player.hasPermission("adminarea.wand.use")) {
                    player.sendMessage("§cYou don't have permission to clear selections.");
                    return true;
                }
                plugin.getPlayerPositions().remove(player.getName());
                player.sendMessage("§aSelection points cleared.");
                return true;
            case "reload":
                if (!player.hasPermission("adminarea.command.reload")) {
                    player.sendMessage("§cYou don't have permission to reload the configuration.");
                    return true;
                }
                try {
                    plugin.getConfigManager().reload();
                    plugin.getWandListener().cleanup();
                    plugin.getAreaManager().reloadAreas();
                    player.sendMessage(AdminAreaConstants.MSG_CONFIG_RELOADED);
                } catch (Exception e) {
                    player.sendMessage(String.format(AdminAreaConstants.MSG_CONFIG_RELOAD_ERROR, e.getMessage()));
                    plugin.getLogger().error("Error reloading configuration", e);
                }
                return true;

            case "here":
                if (!player.hasPermission("adminarea.command.area.create")) {
                    player.sendMessage("§cYou don't have permission to create areas.");
                    return true;
                }
                Position pos = player.getPosition();
                handlePositionCommand(player, 0, "1");
                handlePositionCommand(player, 1, "2");
                player.sendMessage("§aSet both positions to current location.");
                return true;

            case "expand":
                if (!player.hasPermission("adminarea.command.area.create")) {
                    player.sendMessage("§cYou don't have permission to modify selections.");
                    return true;
                }
                if (args.length < 3) {
                    player.sendMessage("§cUsage: /area expand <up|down|north|south|east|west> <amount>");
                    return true;
                }
                expandSelection(player, args[1], Integer.parseInt(args[2]));
                return true;

            case "debug":
                if (!player.hasPermission("adminarea.debug")) {
                    player.sendMessage("§cYou don't have permission to use debug mode.");
                    return true;
                }
                boolean enable = args.length > 1 ? args[1].equalsIgnoreCase("on") : !plugin.isDebugMode();
                plugin.setDebugMode(enable);
                player.sendMessage("§aDebug mode " + (enable ? "enabled" : "disabled") + ".");
                return true;

            default:
                sendHelp(player);
                return true;
        }
    }

    private Area getAreaByName(String name) {
        for (Area area : plugin.getAreas()) {
            if (area.getName().equalsIgnoreCase(name)) {
                return area;
            }
        }
        return null;
    }

    public void sendAreaList(Player player) {
        List<Area> areas = plugin.getAreas();
        if (areas.isEmpty()) {
            player.sendMessage("§eThere are no protected areas.");
            return;
        }
        
        player.sendMessage("§6=== Protected Areas ===");
        for (Area area : areas) {
            player.sendMessage(String.format("§e- %s §7(World: %s, Priority: %d)",
                area.getName(),
                area.getWorld(),
                area.getPriority()));
        }
    }

    private void sendHelp(Player player) {
        player.sendMessage("§6=== Area Protection Commands ===");
        player.sendMessage("§e/area pos1 §7- Set Position 1");
        player.sendMessage("§e/area pos2 §7- Set Position 2");
        player.sendMessage("§e/area wand §7- Receive the Area Wand");
        player.sendMessage("§e/area create [global] §7- Create a new protected area");
        player.sendMessage("§e/area edit <name> §7- Edit an existing area");
        player.sendMessage("§e/area delete <name> §7- Delete an area");
        player.sendMessage("§e/area list §7- List all areas");
        player.sendMessage("§e/area bypass §7- Toggle bypass mode (OP only)");
        player.sendMessage("§e/area merge <area1> <area2> §7- Merge two areas");
        player.sendMessage("§e/area visualize <name> §7- Show area boundaries");
        player.sendMessage("§e/area stats <name> §7- View area statistics");
        player.sendMessage("§e/area reload §7- Reload plugin configuration");
        player.sendMessage("§e/area help §7- Show this help message");
        player.sendMessage("§e/area wand §7- Get the area selection wand");
        player.sendMessage("§e/area undo §7- Undo last selection");
        player.sendMessage("§e/area clear §7- Clear selection points");
        player.sendMessage("§e/area stats <name> [export|reset] §7- View or manage area statistics");
        player.sendMessage("§e/area here §7- Set both positions to current location");
        player.sendMessage("§e/area expand <direction> <amount> §7- Expand selection");
        player.sendMessage("§e/area stats <name> export §7- Export area statistics");
        player.sendMessage("§e/area stats <name> reset §7- Reset area statistics");
        if (player.hasPermission("adminarea.debug")) {
            player.sendMessage("§e/area debug [on|off] §7- Toggle debug logging");
        }
    }

    // New helper method to handle position commands.
    private void handlePositionCommand(Player player, int index, String posLabel) {
        Position pos = player.getPosition();
        String playerName = player.getName();
        HashMap<String, Position[]> playerPositions = plugin.getPlayerPositions();
        // Get or create the two-element array
        Position[] positions = playerPositions.computeIfAbsent(playerName, k -> new Position[2]);
        positions[index] = Position.fromObject(pos.floor(), pos.getLevel()); // Store as floor coordinates
        playerPositions.put(playerName, positions);
        player.sendMessage(String.format("§aPosition %s set to: %d, %d, %d", 
            posLabel, pos.getFloorX(), pos.getFloorY(), pos.getFloorZ()));
            
        // Check if both positions are set
        if (positions[0] != null && positions[1] != null) {
            player.sendMessage("§aBoth positions are set! Use §e/area create§a to create your area.");
        }
    }

    // Opens a form to create a new area.
    private void openCreateForm(Player player) {
         GuiManager gui = new GuiManager(plugin);
        // Check that both positions are set.
        HashMap<String, Position[]> playerPositions = plugin.getPlayerPositions();
        String playerName = player.getName();
        Position[] positions = playerPositions.getOrDefault(playerName, new Position[2]);

        if (positions[0] == null || positions[1] == null) {
            player.sendMessage("§cPlease set both positions using /area wand or /area pos1 and /area pos2 before creating an area. Or do /area create global to manually input coordinates or use /area and go through the gui.");
            return;
        }
       gui.openCreateForm(player);
    }

    // Opens a form to edit an existing area.
    private void openEditForm(Player player, Area area) {
        GuiManager gui = new GuiManager(plugin);
        gui.openEditForm(player, area);
    }

    // New method to open a main menu form.
   private void openMainMenu(Player player) {
        GuiManager gui = new GuiManager(plugin);
        gui.openMainMenu(player);
    }

    private void giveWandToPlayer(Player player, int itemId) {
        cn.nukkit.item.Item wand = cn.nukkit.item.Item.get(itemId, 0, 1);
        wand.setCustomName("§aArea Wand");
        wand.setLore(
            "§7Left click: Set position 1",
            "§7Right click: Set position 2",
            "§7Shift + Left click: Clear selection",
            "§7Shift + Right click: Quick create area"
        );
        player.getInventory().addItem(wand);
        player.sendMessage(plugin.getConfigManager().getMessage("messages.wandGiven"));
    }
    
    // New helper method to open the create form for a global area (bypassing positions check)
    private void openGlobalCreateForm(Player player) {
        GuiManager gui = new GuiManager(plugin);
        gui.openCreateForm(player);
    }

    private void sendAreaStats(Player player, Area area) {
        AreaStatistics stats = plugin.getAreaManager().getAreaStats(area.getName());
        player.sendMessage("§6=== Stats for " + area.getName() + " ===");
        player.sendMessage("§eUptime: " + formatUptime(stats.getUptime()));
        stats.getAllStats().forEach((event, count) -> 
            player.sendMessage("§e" + event + ": §f" + count)
        );
    }

    private String formatUptime(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        return String.format("%dd %dh %dm", days, hours % 24, minutes % 60);
    }

    private void expandSelection(Player player, String direction, int amount) {
        Position[] positions = plugin.getPlayerPositions().get(player.getName());
        if (positions == null || positions[0] == null || positions[1] == null) {
            player.sendMessage("§cYou must first select two positions!");
            return;
        }

        switch (direction.toLowerCase()) {
            case "up":
                positions[1].y += amount;
                break;
            case "down":
                positions[0].y -= amount;
                break;
            case "north":
                positions[0].z -= amount;
                break;
            case "south":
                positions[1].z += amount;
                break;
            case "east":
                positions[1].x += amount;
                break;
            case "west":
                positions[0].x -= amount;
                break;
            default:
                player.sendMessage("§cInvalid direction! Use: up, down, north, south, east, west");
                return;
        }

        plugin.getPlayerPositions().put(player.getName(), positions);
        player.sendMessage(String.format("§aExpanded selection %s by %d blocks", direction, amount));
        
        // Update visualization
        plugin.getWandListener().updateVisualization(player);
    }
}
