package adminarea.area;

import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.command.data.CommandParameter;
import cn.nukkit.command.data.CommandParamType;
import cn.nukkit.Player;
import cn.nukkit.level.Position;

import java.util.List;
import java.util.Map;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.constants.AdminAreaConstants;
import adminarea.logging.PluginLogger;
import adminarea.managers.GuiManager;
import adminarea.permissions.PermissionToggle;
import adminarea.stats.AreaStatistics;
import adminarea.util.ValidationUtils;

import java.util.HashMap;

public class AreaCommand extends Command {

    private final AdminAreaProtectionPlugin plugin;
    public static final String CREATE_AREA_FORM_ID = "create_area_form";

    public AreaCommand(AdminAreaProtectionPlugin plugin) {
        super("area", "Manage protected areas", "/area <create|edit|delete|list|wand>");
        this.plugin = plugin;
        this.setAliases(new String[]{"areaprotect", "areaadmin"});
        // Define command parameters for built-in tab completion
        this.commandParameters.clear();
        this.commandParameters.put("default", new CommandParameter[]{
            CommandParameter.newEnum("subCommand", new String[]{"create", "list", "wand", "pos1", "pos2", "help", "bypass", "toggle"})
        });
        this.commandParameters.put("edit", new CommandParameter[]{
            CommandParameter.newEnum("subCommand", new String[]{"edit"}),
            CommandParameter.newType("areaName", CommandParamType.STRING)
        });
        this.commandParameters.put("delete", new CommandParameter[]{
            CommandParameter.newEnum("subCommand", new String[]{"delete"}),
            CommandParameter.newType("areaName", CommandParamType.STRING)
        });
        this.commandParameters.put("toggle", new CommandParameter[]{
            CommandParameter.newEnum("subCommand", new String[]{"toggle"}),
            CommandParameter.newEnum("action", new String[]{"on", "off", "list"}),
            CommandParameter.newType("toggleName", CommandParamType.STRING)
        });
    }

    @Override
    public String[] getAliases() {
        return new String[]{"areaprotect", "areaadmin"};
    }
    
    @Override
    public boolean execute(CommandSender sender, String commandLabel, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command can only be used by players!");
            return false;
        }

        Player player = (Player) sender;

        // Debug logging for command execution
        plugin.debug("Area command executed by " + player.getName() + ": /" + commandLabel + " " + String.join(" ", args));

        // Debug: Print the arguments received
        plugin.getLogger().info("Arguments received: " + String.join(", ", args));

        if (!player.hasPermission("adminarea.command.area")) {
            plugin.getLogger().info("Player " + player.getName() + " does not have adminarea.command.area permission.");
            player.sendMessage(plugin.getLanguageManager().get("messages.noPermission"));
            return true;
        }

        if (args.length == 0) {
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
                    player.sendMessage(plugin.getLanguageManager().get("messages.permissions.createArea"));
                    return true;
                }
                if (args.length > 1 && args[1].equalsIgnoreCase("global")) {
                    openGlobalCreateForm(player);
                    return true;
                }
                Position[] positions = plugin.getPlayerPositions().get(player.getName());
                if (positions == null || positions[0] == null || positions[1] == null) {
                    player.sendMessage(plugin.getLanguageManager().get("messages.wand.notBoth"));
                    return true;
                }
                try {
                    Area newArea = createArea(player, "NewArea", positions);
                    plugin.addArea(newArea);
                    player.sendMessage("Area created successfully!");
                } catch (IllegalStateException e) {
                    player.sendMessage("Error creating area: " + e.getMessage());
                }
                return true;
            case "edit":
                if (!player.hasPermission("adminarea.command.area.edit")) {
                    plugin.getLogger().info("Player " + player.getName() + " does not have adminarea.command.area.edit permission.");
                    player.sendMessage(plugin.getLanguageManager().get("messages.noPermission"));
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage(plugin.getLanguageManager().get("commands.edit"));
                    return true;
                }
                Area area = getAreaByName(args[1]);
                if (area == null) {
                    player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound", Map.of("area", args[1])));
                    return true;
                }
                plugin.getGuiManager().openAreaSettings(player, area);
                return true;
            case "delete":
                if (!player.hasPermission("adminarea.command.area.delete")) {
                    plugin.getLogger().info("Player " + player.getName() + " does not have adminarea.command.area.delete permission.");
                    player.sendMessage(plugin.getLanguageManager().get("messages.noPermission"));
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage(plugin.getLanguageManager().get("commands.delete"));
                    return true;
                }
                Area areaToDelete = getAreaByName(args[1]);
                if (areaToDelete == null) {
                    player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound", Map.of("area", args[1])));
                    return true;
                }
                plugin.removeArea(areaToDelete);
                player.sendMessage(plugin.getLanguageManager().get("area.delete.success", Map.of("area", args[1])));
                return true;
            case "list":
                if (!player.hasPermission("adminarea.command.area.list")) {
                    plugin.getLogger().info("Player " + player.getName() + " does not have adminarea.command.area.list permission.");
                    player.sendMessage(plugin.getLanguageManager().get("messages.permissions.listAreas"));
                    return true;
                }
                sendAreaList(player);
                return true;
            case "wand":
                if (!player.hasPermission("adminarea.wand")) {
                    player.sendMessage(plugin.getLanguageManager().get("messages.noPermission"));
                    return true;
                }
                int wandItemType = plugin.getConfigManager().getWandItemType();
                giveWandToPlayer(player, wandItemType);
                return true;
            case "bypass":
                if (!player.hasPermission("adminarea.command.area.bypass")) {
                    plugin.getLogger().info("Player " + player.getName() + " does not have adminarea.command.area.bypass permission.");
                    player.sendMessage(plugin.getLanguageManager().get("messages.permissions.bypass"));
                    return true;
                }
                // Prevent bypass from interfering with LuckPerms integration.
                if (plugin.getLuckPermsApi() != null) {
                    player.sendMessage(plugin.getLanguageManager().get("messages.bypassDisabled"));
                    return true;
                }
                plugin.toggleBypass(player.getName());
                boolean bypassing = plugin.isBypassing(player.getName());
                player.sendMessage(plugin.getLanguageManager().get("messages.bypassToggled", Map.of("status", bypassing ? "enabled" : "disabled")));
                return true;
            case "merge":
                if (args.length < 3) {
                    player.sendMessage(plugin.getLanguageManager().get("commands.merge"));
                    return true;
                }
                Area area1 = plugin.getArea(args[1]);
                Area area2 = plugin.getArea(args[2]);
                if (area1 == null || area2 == null) {
                    player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound"));
                    return true;
                }
                Area mergedArea = plugin.getAreaManager().mergeAreas(area1, area2);
                plugin.addArea(mergedArea);
                player.sendMessage(plugin.getLanguageManager().get("area.merge.success", Map.of("area", mergedArea.getName())));
                return true;

            case "visualize":
                if (args.length < 2) {
                    player.sendMessage(plugin.getLanguageManager().get("commands.visualize"));
                    return true;
                }
                Area visualArea = plugin.getArea(args[1]);
                if (visualArea == null) {
                    player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound"));
                    return true;
                }
                plugin.getAreaManager().visualizeArea(player, visualArea);
                player.sendMessage(plugin.getLanguageManager().get("area.visualize.success", Map.of("area", visualArea.getName())));
                return true;

            case "stats":
                if (args.length < 2) {
                    player.sendMessage(plugin.getLanguageManager().get("commands.stats"));
                    return true;
                }
                Area statsArea = plugin.getArea(args[1]);
                if (statsArea == null) {
                    player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound"));
                    return true;
                }

                // Handle subcommands
                if (args.length > 2) {
                    switch (args[2].toLowerCase()) {
                        case "export":
                            if (!player.hasPermission("adminarea.stats.export")) {
                                player.sendMessage(plugin.getLanguageManager().get("permissions.exportStats"));
                                return true;
                            }
                            plugin.getAreaManager().getAreaStats(statsArea.getName())
                                .exportStats(plugin.getDataFolder().toPath().resolve("stats_export"));
                            player.sendMessage(plugin.getLanguageManager().get("stats.export.success"));
                            return true;
                        case "reset":
                            if (!player.hasPermission("adminarea.stats.reset")) {
                                player.sendMessage(plugin.getLanguageManager().get("permissions.resetStats"));
                                return true;
                            }
                            plugin.getAreaManager().getAreaStats(statsArea.getName()).cleanup();
                            player.sendMessage(plugin.getLanguageManager().get("stats.reset.success"));
                            return true;
                    }
                }

                // Default to viewing stats
                if (!player.hasPermission("adminarea.stats.view")) {
                    player.sendMessage(plugin.getLanguageManager().get("messages.permissions.viewStats"));
                    return true;
                }
                sendAreaStats(player, statsArea);
                return true;
            case "undo":
                if (!player.hasPermission("adminarea.wand.undo")) {
                    player.sendMessage(plugin.getLanguageManager().get("permissions.undoSelection"));
                    return true;
                }
                ((AdminAreaProtectionPlugin) plugin.getServer().getPluginManager().getPlugin("AdminAreaProtection"))
                    .getWandListener().undo(player);
                return true;

            case "clear":
                if (!player.hasPermission("adminarea.wand.use")) {
                    player.sendMessage(plugin.getLanguageManager().get("permissions.clearSelection"));
                    return true;
                }
                plugin.getPlayerPositions().remove(player.getName());
                player.sendMessage(plugin.getLanguageManager().get("messages.selectionCleared"));
                return true;
            case "reload":
                if (!player.hasPermission("adminarea.command.reload")) {
                    player.sendMessage(plugin.getLanguageManager().get("permissions.reloadConfig"));
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
                    player.sendMessage(plugin.getLanguageManager().get("messages.permissions.createArea"));
                    return true;
                }
                Position pos = player.getPosition();
                handlePositionCommand(player, 0, "1");
                handlePositionCommand(player, 1, "2");
                player.sendMessage(plugin.getLanguageManager().get("messages.positionsSetToCurrent"));
                return true;

            case "expand":
                if (!player.hasPermission("adminarea.command.area.create")) {
                    player.sendMessage(plugin.getLanguageManager().get("messages.permissions.modifySelection"));
                    return true;
                }
                if (args.length < 3) {
                    player.sendMessage(plugin.getLanguageManager().get("commands.expand"));
                    return true;
                }
                expandSelection(player, args[1], Integer.parseInt(args[2]));
                return true;

            case "debug":
                if (!player.hasPermission("adminarea.debug")) {
                    player.sendMessage(plugin.getLanguageManager().get("messages.permissions.debugMode"));
                    return true;
                }
                boolean enable = args.length > 1 ? args[1].equalsIgnoreCase("on") : !plugin.isDebugMode();
                plugin.setDebugMode(enable);
                player.sendMessage(plugin.getLanguageManager().get("messages.debug.debugModeToggled", Map.of("status", enable ? "enabled" : "disabled")));
                return true;

            case "toggle":
                if (!player.hasPermission("adminarea.command.toggle")) {
                    plugin.getLogger().info("Player " + player.getName() + " lacks toggle permission");
                    player.sendMessage(plugin.getLanguageManager().get("messages.permissions.toggleArea"));
                    return true;
                }

                if (args.length < 3) {
                    player.sendMessage(plugin.getLanguageManager().get("commands.toggle"));
                    return true;
                }

                String toggleAction = args[1].toLowerCase();
                String toggleName = args[2];

                switch (toggleAction) {
                    case "list":
                        if (args.length > 2) {
                            // List toggles for specific category
                            String category = args[2].toUpperCase();
                            try {
                                sendCategoryToggles(player, PermissionToggle.Category.valueOf(category));
                            } catch (IllegalArgumentException e) {
                                player.sendMessage(plugin.getLanguageManager().get("messages.error.invalidCategory"));
                            }
                        } else {
                            // List all toggles by category
                            sendAllToggles(player);
                        }
                        return true;

                    case "on":
                    case "off":
                        if (args.length < 4) {
                            player.sendMessage(plugin.getLanguageManager().get("commands.toggleArea"));
                            return true;
                        }
                        String areaName = args[3];
                        Area areaN = plugin.getArea(areaName);
                        if (areaN == null) {
                            player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound"));
                            return true;
                        }
                        
                        boolean state = toggleAction.equals("on");
                        handleToggleCommand(player, areaN, toggleName, state);
                        return true;

                    default:
                        player.sendMessage(plugin.getLanguageManager().get("commands.toggle"));
                        return true;
                }

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
            player.sendMessage(plugin.getLanguageManager().get("messages.noProtectedAreas"));
            return;
        }
        
        player.sendMessage(plugin.getLanguageManager().get("messages.protectedAreasHeader"));
        for (Area area : areas) {
            player.sendMessage(String.format(plugin.getLanguageManager().get("messages.protectedAreaEntry"),
                area.getName(),
                area.getWorld(),
                area.getPriority()));
        }
    }

    private void sendHelp(Player player) {
        player.sendMessage(plugin.getLanguageManager().get("messages.helpHeader"));
        
        // Basic commands
        player.sendMessage(plugin.getLanguageManager().get("commands.pos1"));
        player.sendMessage(plugin.getLanguageManager().get("commands.pos2"));
        player.sendMessage(plugin.getLanguageManager().get("commands.wand"));
        player.sendMessage(plugin.getLanguageManager().get("commands.create"));
        player.sendMessage(plugin.getLanguageManager().get("commands.edit"));
        player.sendMessage(plugin.getLanguageManager().get("commands.delete"));
        player.sendMessage(plugin.getLanguageManager().get("commands.list"));
        
        // Area management
        player.sendMessage(plugin.getLanguageManager().get("commands.merge"));
        player.sendMessage(plugin.getLanguageManager().get("commands.visualize"));
        player.sendMessage(plugin.getLanguageManager().get("commands.here"));
        player.sendMessage(plugin.getLanguageManager().get("commands.expand"));

        // Permission toggles
        if (player.hasPermission("adminarea.command.toggle")) {
            player.sendMessage(plugin.getLanguageManager().get("commands.toggleHeader"));
            player.sendMessage(plugin.getLanguageManager().get("commands.toggleArea"));
            player.sendMessage(plugin.getLanguageManager().get("commands.toggleList"));
            player.sendMessage(plugin.getLanguageManager().get("commands.toggleCategory"));
        }

        // Advanced features
        if (player.hasPermission("adminarea.command.area.bypass")) {
            player.sendMessage(plugin.getLanguageManager().get("commands.bypass")); 
        }
        if (player.hasPermission("adminarea.stats.view")) {
            player.sendMessage(plugin.getLanguageManager().get("commands.stats"));
        }
        if (player.hasPermission("adminarea.debug")) {
            player.sendMessage(plugin.getLanguageManager().get("commands.debug"));
        }
    }

    // New helper method to handle position commands.
    private void handlePositionCommand(Player player, int index, String posLabel) {
        Position pos = player.getPosition();
        String playerName = player.getName();
        Position[] positions = plugin.getPlayerPositions().computeIfAbsent(playerName, k -> new Position[2]);
        positions[index] = pos;
        
        try {
            ValidationUtils.validateCoordinates(
                (int)pos.getX(), (int)pos.getY(), (int)pos.getZ()
            );
            
            player.sendMessage(String.format(plugin.getLanguageManager().get("messages.positionSet"), 
                posLabel, pos.getFloorX(), pos.getFloorY(), pos.getFloorZ()));
                
            // Check if both positions are set
            if (positions[0] != null && positions[1] != null) {
                plugin.getValidationUtils().validatePositions(positions);
                player.sendMessage(plugin.getLanguageManager().get("messages.bothPositionsSet"));
            }
        } catch (IllegalArgumentException e) {
            player.sendMessage(plugin.getLanguageManager().get("messages.error.invalidCoordinates"));
            plugin.getPlayerPositions().remove(playerName);
        }
    }

    private Area createArea(Player player, String name, Position[] positions) {
        if (positions == null || positions[0] == null || positions[1] == null) {
            throw new IllegalStateException("Positions not set");
        }

        try {
            // Validate area name
            plugin.getValidationUtils().validateAreaName(name);
            
            // Create area using builder
            return Area.builder()
                .name(name)
                .world(positions[0].getLevel().getName())
                .coordinates(
                    positions[0].getFloorX(), positions[1].getFloorX(),
                    positions[0].getFloorY(), positions[1].getFloorY(),
                    positions[0].getFloorZ(), positions[1].getFloorZ()
                )
                .applyDefaults() // Apply default permissions
                .build();

        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid area parameters: " + e.getMessage());
        }
    }

    private void openCreateForm(Player player) {
        // Validate player state first
        try {
            plugin.getValidationUtils().validatePlayerState(player, "create area");
        } catch (IllegalStateException e) {
            player.sendMessage(plugin.getLanguageManager().get("messages.error.playerOffline"));
            return;
        }

        // Check position selection
        Position[] positions = plugin.getPlayerPositions().get(player.getName());
        if (positions == null || positions[0] == null || positions[1] == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.error.selectPositions"));
            return;
        }

        try {
            // Validate positions
            plugin.getValidationUtils().validatePositions(positions);
            
            // Open creation form through GUI manager
            plugin.getGuiManager().openCreateForm(player);
            
        } catch (IllegalArgumentException e) {
            player.sendMessage(plugin.getLanguageManager().get("messages.error.invalidSelection"));
        }
    }

    private void openEditForm(Player player, Area area) {
        try {
            // Validate player and area state
            plugin.getValidationUtils().validatePlayerState(player, "edit area");
            if (area == null) {
                throw new IllegalArgumentException("Area cannot be null");
            }

            // Open edit form through GUI manager
            plugin.getGuiManager().openEditForm(player, area);
            
        } catch (IllegalStateException e) {
            player.sendMessage(plugin.getLanguageManager().get("messages.error.playerOffline"));
        } catch (IllegalArgumentException e) {
            player.sendMessage(plugin.getLanguageManager().get("messages.error.invalidArea"));
        }
    }

    private void handleAreaDeletion(Player player, String areaName) {
        try {
            Area area = plugin.getArea(areaName);
            if (area == null) {
                throw new IllegalArgumentException("Area not found");
            }

            // Validate player state
            plugin.getValidationUtils().validatePlayerState(player, "delete area");

            // Remove area
            plugin.removeArea(area);
            player.sendMessage(plugin.getLanguageManager().get("area.delete.success", 
                Map.of("area", areaName)));

        } catch (IllegalStateException e) {
            player.sendMessage(plugin.getLanguageManager().get("messages.error.playerOffline"));
        } catch (IllegalArgumentException e) {
            player.sendMessage(plugin.getLanguageManager().get("messages.error.areaNotFound"));
        }
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
            plugin.getLanguageManager().get("messages.wand.lore.line1"),
            plugin.getLanguageManager().get("messages.wand.lore.line2"),
            plugin.getLanguageManager().get("messages.wand.lore.line3"),
            plugin.getLanguageManager().get("messages.wand.lore.line4")
        );
        player.getInventory().addItem(wand);
        player.sendMessage(plugin.getLanguageManager().get("messages.wandGiven"));
    }
    
    // New helper method to open the create form for a global area (bypassing positions check)
    private void openGlobalCreateForm(Player player) {
        GuiManager gui = new GuiManager(plugin);
        gui.openCreateForm(player);
    }

    private void sendAreaStats(Player player, Area area) {
        AreaStatistics stats = plugin.getAreaManager().getAreaStats(area.getName());
        player.sendMessage(plugin.getLanguageManager().get("stats.header", Map.of("area", area.getName())));
        player.sendMessage(plugin.getLanguageManager().get("stats.uptime", Map.of("uptime", formatUptime(stats.getUptime()))));
        stats.getAllStats().forEach((event, count) -> 
            player.sendMessage(plugin.getLanguageManager().get("stats.entry", Map.of("event", event, "count", String.valueOf(count))))
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
            player.sendMessage(plugin.getLanguageManager().get("messages.error.selectPositions"));
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
                player.sendMessage(plugin.getLanguageManager().get("messages.error.invalidDirection"));
                return;
        }

        plugin.getPlayerPositions().put(player.getName(), positions);
        player.sendMessage(plugin.getLanguageManager().get("messages.success.expandSuccess", 
            Map.of("direction", direction, "amount", String.valueOf(amount))));
        
        // Update visualization
        plugin.getWandListener().updateVisualization(player);
    }

    private void handleToggleCommand(Player player, Area area, String toggleName, boolean state) {
        // Validate toggle exists
        PermissionToggle toggle = PermissionToggle.getToggle(toggleName);
        if (toggle == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.error.invalidToggle"));
            return;
        }

        // Check category-specific permission
        String categoryPerm = "adminarea.toggle." + toggle.getCategory().name().toLowerCase();
        if (!player.hasPermission(categoryPerm)) {
            player.sendMessage(plugin.getLanguageManager().get("messages.permissions.categoryToggle",
                Map.of("category", toggle.getCategory().getDisplayName())));
            return;
        }

        // Update toggle state
        area.setToggleState(toggleName, state);
        plugin.updateArea(area);

        // Send confirmation message
        player.sendMessage(plugin.getLanguageManager().get("messages.toggleUpdated",
            Map.of("toggle", toggle.getDisplayName(),
                  "area", area.getName(),
                  "state", state ? "enabled" : "disabled")));
    }

    private void sendToggleList(Player player) {
        player.sendMessage(plugin.getLanguageManager().get("messages.toggleList.header"));
        
        for (PermissionToggle.Category category : PermissionToggle.Category.values()) {
            List<PermissionToggle> toggles = PermissionToggle.getTogglesByCategory().get(category);
            if (toggles != null && !toggles.isEmpty()) {
                player.sendMessage(plugin.getLanguageManager().get("messages.toggleList.category", 
                    Map.of("category", category.getDisplayName())));
                
                for (PermissionToggle toggle : toggles) {
                    player.sendMessage(plugin.getLanguageManager().get("messages.toggleList.entry",
                        Map.of("name", toggle.getPermissionNode(),
                             "description", toggle.getDisplayName())));
                }
            }
        }
    }

    private void sendAllToggles(Player player) {
        player.sendMessage(plugin.getLanguageManager().get("messages.toggleList.header"));
        
        for (PermissionToggle.Category category : PermissionToggle.Category.values()) {
            player.sendMessage(plugin.getLanguageManager().get("messages.toggleList.categoryHeader", 
                Map.of("category", category.getDisplayName())));

            List<PermissionToggle> toggles = PermissionToggle.getTogglesByCategory().get(category);
            for (PermissionToggle toggle : toggles) {
                player.sendMessage(plugin.getLanguageManager().get("messages.toggleList.entry",
                    Map.of("name", toggle.getPermissionNode(),
                          "description", toggle.getDisplayName())));
            }
        }
    }

    private void sendCategoryToggles(Player player, PermissionToggle.Category category) {
        player.sendMessage(plugin.getLanguageManager().get("messages.toggleList.categoryHeader",
            Map.of("category", category.getDisplayName())));

        List<PermissionToggle> toggles = PermissionToggle.getTogglesByCategory().get(category);
        for (PermissionToggle toggle : toggles) {
            player.sendMessage(plugin.getLanguageManager().get("messages.toggleList.entry",
                Map.of("name", toggle.getPermissionNode(),
                      "description", toggle.getDisplayName())));
        }
    }

    private boolean isValidToggleName(String toggleName) {
        for (List<PermissionToggle> toggles : PermissionToggle.getTogglesByCategory().values()) {
            for (PermissionToggle toggle : toggles) {
                if (toggle.getPermissionNode().equals(toggleName)) {
                    return true;
                }
            }
        }
        return false;
    }
}
