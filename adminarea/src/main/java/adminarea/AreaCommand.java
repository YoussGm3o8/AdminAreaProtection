package adminarea;

import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.command.data.CommandParameter;
import cn.nukkit.command.data.CommandParamType;
import cn.nukkit.Player;
import cn.nukkit.level.Position;

import java.util.List;
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
                if (!player.hasPermission("adminarea.command.area")) {
                    player.sendMessage("§cYou don't have permission to use this command.");
                    return true;
                }
                giveWandToPlayer(player);
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
        player.sendMessage("§e/area create §7- Create a new protected area");
        player.sendMessage("§e/area edit <name> §7- Edit an existing area");
        player.sendMessage("§e/area delete <name> §7- Delete an area");
        player.sendMessage("§e/area list §7- List all areas");
        player.sendMessage("§e/area bypass §7- Toggle bypass mode (OP only... unless you have perms.)");
        player.sendMessage("§e/area help §7- Show this help message");
    }

    // New helper method to handle position commands.
    private void handlePositionCommand(Player player, int index, String posLabel) {
        Position pos = player.getPosition();
        String playerName = player.getName();
        HashMap<String, Position[]> playerPositions = plugin.getPlayerPositions();
        // Get or create the two-element array
        Position[] positions = playerPositions.getOrDefault(playerName, new Position[2]);
        positions[index] = pos;
        playerPositions.put(playerName, positions);
        player.sendMessage("§aPosition " + posLabel + " set to: " +
            pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
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

    private void giveWandToPlayer(Player player) {
        // Give the player a stick named "Area Wand"
        cn.nukkit.item.Item wand = cn.nukkit.item.Item.get(280, 0, 1); // Stick
        wand.setCustomName("§aArea Wand");
        player.getInventory().addItem(wand);
        player.sendMessage("§eYou have received the Area Wand!");
    }
    
    // New helper method to open the create form for a global area (bypassing positions check)
    private void openGlobalCreateForm(Player player) {
        GuiManager gui = new GuiManager(plugin);
        gui.openCreateForm(player);
    }
}
