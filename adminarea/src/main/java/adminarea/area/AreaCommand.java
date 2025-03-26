package adminarea.area;

import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.command.data.CommandParameter;
import cn.nukkit.command.data.CommandParamType;
import cn.nukkit.Player;
import cn.nukkit.level.Position;
import cn.nukkit.item.Item;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.io.File;
import java.io.FileWriter;
import java.util.Stack;
import java.util.HashMap;
import java.util.ArrayList;
import org.json.JSONObject;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.constants.FormIds;
import adminarea.data.FormTrackingData;
import adminarea.permissions.PermissionToggle;

public class AreaCommand extends Command {

    private final AdminAreaProtectionPlugin plugin;
    public static final String CREATE_AREA_FORM_ID = "create_area_form";
    private final Map<String, Stack<Position[]>> selectionHistory = new HashMap<>();
    private static final int MAX_HISTORY = 10;

    public AreaCommand(AdminAreaProtectionPlugin plugin) {
        super("area", "Manage protected areas", "/area <create|edit|delete|list|wand>");
        this.plugin = plugin;
        this.setAliases(new String[]{"areaprotect", "areaadmin"});
        
        // Define command parameters for built-in tab completion
        this.commandParameters.clear();
        this.commandParameters.put("default", new CommandParameter[]{
            CommandParameter.newEnum("subCommand", new String[]{
                "create", "edit", "delete", "list", "wand", "pos1", "pos2", "help",
                "bypass", "merge", "visualize", "stats", "reload", "undo", "clear",
                "here", "expand", "debug", "reset", "cache-reload"
            })
        });
        this.commandParameters.put("edit", new CommandParameter[]{
            CommandParameter.newEnum("subCommand", new String[]{"edit"}),
            CommandParameter.newType("areaName", CommandParamType.STRING)
        });
        this.commandParameters.put("delete", new CommandParameter[]{
            CommandParameter.newEnum("subCommand", new String[]{"delete"}),
            CommandParameter.newType("areaName", CommandParamType.STRING)
        });
        this.commandParameters.put("merge", new CommandParameter[]{
            CommandParameter.newEnum("subCommand", new String[]{"merge"}),
            CommandParameter.newType("area1", CommandParamType.STRING),
            CommandParameter.newType("area2", CommandParamType.STRING)
        });
        this.commandParameters.put("visualize", new CommandParameter[]{
            CommandParameter.newEnum("subCommand", new String[]{"visualize"}),
            CommandParameter.newType("areaName", CommandParamType.STRING)
        });
        this.commandParameters.put("stats", new CommandParameter[]{
            CommandParameter.newEnum("subCommand", new String[]{"stats"}),
            CommandParameter.newType("areaName", CommandParamType.STRING),
            CommandParameter.newEnum("action", new String[]{"export", "reset"})
        });
        this.commandParameters.put("expand", new CommandParameter[]{
            CommandParameter.newEnum("subCommand", new String[]{"expand"}),
            CommandParameter.newEnum("direction", new String[]{"up", "down", "north", "south", "east", "west"}),
            CommandParameter.newType("amount", CommandParamType.INT)
        });
        this.commandParameters.put("debug", new CommandParameter[]{
            CommandParameter.newEnum("subCommand", new String[]{"debug"}),
            CommandParameter.newEnum("state", new String[]{"on", "off"})
        });
        this.commandParameters.put("reset", new CommandParameter[]{
            CommandParameter.newEnum("subCommand", new String[]{"reset"})
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
        if (plugin.isDebugMode()) {
            plugin.debug("Area command executed by " + player.getName() + ": /" + commandLabel + " " + String.join(" ", args));
        }

        if (!player.hasPermission("adminarea.command.area")) {
            player.sendMessage(plugin.getLanguageManager().get("messages.noPermission"));
            return true;
        }

        if (args.length == 0) {
            openMainMenu(player);
            return true;
        }

        String subCommand = args[0].toLowerCase();
        try {
            switch (subCommand) {
                // Position commands
                case "pos1":
                    return handlePositionCommand(player, 0, "1");
                case "pos2":
                    return handlePositionCommand(player, 1, "2");
                case "here":
                    return handleHereCommand(player);
                
                // Area creation and management
                case "create":
                    return handleCreateCommand(player, args);
                case "edit":
                    return handleEditCommand(player, args);
                case "delete":
                    return handleDeleteCommand(player, args);
                case "list":
                    return handleListCommand(player);
                
                // Selection tools
                case "wand":
                    return handleWandCommand(player);
                case "undo":
                    return handleUndoCommand(player);
                case "clear":
                    return handleClearCommand(player);
                case "expand":
                    return handleExpandCommand(player, args);
                
                // Area visualization and stats
                case "visualize":
                    return handleVisualizeCommand(player, args);
                case "stats":
                    return handleStatsCommand(player, args);
                case "merge":
                    return handleMergeCommand(player, args);
                
                // Admin commands
                case "bypass":
                    return handleBypassCommand(player);
                case "reload":
                    return handleReloadCommand(player);
                case "debug":
                    return handleDebugCommand(player, args);
                case "cache-reload":
                    return handleCacheReloadCommand(player, args);
                
                // Help command
                case "help":
                    sendHelp(player);
                    return true;
                case "reset":
                    handleResetCommand(player);
                    return true;
                default:
                    sendHelp(player);
                    return true;
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error executing area command", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.error.errorProcessingInput"));
            return false;
        }
    }

    private boolean handleHereCommand(Player player) {
        // No longer need to check for create permission since we're just showing info
        if (!player.hasPermission("adminarea.command.area.info")) {
            player.sendMessage(plugin.getLanguageManager().get("messages.noPermission"));
            return true;
        }
        
        Position pos = player.getPosition();
        
        // Get the area at the player's current position
        Area area = plugin.getAreaManager().getHighestPriorityAreaAtPosition(pos);
        
        if (area == null) {
            // No area found at the player's position - handle potential missing language key
            try {
                player.sendMessage(plugin.getLanguageManager().get("messages.noProtectedAreas") + 
                                  " " + plugin.getLanguageManager().get("messages.area.notInArea"));
            } catch (Exception e) {
                // If the new language key is missing, fall back to a simpler message
                player.sendMessage(plugin.getLanguageManager().get("messages.noProtectedAreas") + 
                                  " §7You are not in any protected area.");
            }
        } else {
            // Display area information
            String areaName = area.getName();
            String worldName = area.getWorld();
            int priority = area.getPriority();
            
            // Create a formatted message
            AreaDTO.Bounds bounds = area.getBounds();
            long sizeX = Math.abs(bounds.xMax() - bounds.xMin()) + 1;
            long sizeY = Math.abs(bounds.yMax() - bounds.yMin()) + 1;
            long sizeZ = Math.abs(bounds.zMax() - bounds.zMin()) + 1;
            String size = area.isGlobal() ? "global" : String.valueOf(sizeX * sizeY * sizeZ);
            
            try {
                // Use the protectedAreaEntry message format which already exists
                player.sendMessage(plugin.getLanguageManager().get("messages.area.currentLocation") +
                                String.format(plugin.getLanguageManager().get("messages.protectedAreaEntry"),
                                            areaName, worldName, priority) + 
                                "\n§7Size: " + size + " blocks" +
                                "\n§7Position: §7(" + pos.getFloorX() + ", " + pos.getFloorY() + ", " + pos.getFloorZ() + ")");
            } catch (Exception e) {
                // Fall back to a simpler message if the language key is missing
                player.sendMessage("§2You are currently in area: §f" + areaName + 
                                 "\n§7World: " + worldName + 
                                 "\n§7Priority: " + priority + 
                                 "\n§7Size: " + size + " blocks" +
                                 "\n§7Position: §7(" + pos.getFloorX() + ", " + pos.getFloorY() + ", " + pos.getFloorZ() + ")");
            }
        }
        
        return true;
    }

    private boolean handleExpandCommand(Player player, String[] args) {
        if (!player.hasPermission("adminarea.command.area.modify")) {
            player.sendMessage(plugin.getLanguageManager().get("messages.permissions.modifySelection"));
            return true;
        }

        if (args.length != 3) {
            player.sendMessage(plugin.getLanguageManager().get("messages.usage.expand"));
            return true;
        }

        String direction = args[1].toLowerCase();
        int amount;
        try {
            amount = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            player.sendMessage(plugin.getLanguageManager().get("messages.error.invalidDirection"));
            return true;
        }

        Position[] positions = plugin.getPlayerPositions().get(player.getName());
        if (positions == null || positions[0] == null || positions[1] == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.error.selectPositions"));
            return true;
        }

        // Expand the selection based on direction
        switch (direction) {
            case "up":
                positions[1].setY(positions[1].getY() + amount);
                break;
            case "down":
                positions[0].setY(positions[0].getY() - amount);
                break;
            case "north":
                positions[0].setZ(positions[0].getZ() - amount);
                break;
            case "south":
                positions[1].setZ(positions[1].getZ() + amount);
                break;
            case "east":
                positions[1].setX(positions[1].getX() + amount);
                break;
            case "west":
                positions[0].setX(positions[0].getX() - amount);
                break;
            default:
                player.sendMessage(plugin.getLanguageManager().get("messages.error.invalidDirection"));
                return true;
        }

        player.sendMessage(plugin.getLanguageManager().get("success.area.expand.bounds",
            Map.of("direction", direction, "amount", String.valueOf(amount))));
        return true;
    }

    private boolean handleMergeCommand(Player player, String[] args) {
        if (!player.hasPermission("adminarea.command.area.merge")) {
            player.sendMessage(plugin.getLanguageManager().get("messages.noPermission"));
            return true;
        }

        if (args.length != 3) {
            player.sendMessage(plugin.getLanguageManager().get("messages.usage.merge"));
            return true;
        }

        Area area1 = plugin.getAreaManager().getArea(args[1]);
        Area area2 = plugin.getAreaManager().getArea(args[2]);

        if (area1 == null || area2 == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound"));
            return true;
        }

        try {
            // Get DTOs for both areas
            AreaDTO dto1 = area1.toDTO();
            AreaDTO dto2 = area2.toDTO();

            // Check if areas are in the same world
            if (!dto1.world().equals(dto2.world())) {
                player.sendMessage(plugin.getLanguageManager().get("messages.error.differentWorlds"));
                return true;
            }
            
            // Check if either area is a global area (cannot merge global areas)
            if (area1.isGlobal() || area2.isGlobal()) {
                player.sendMessage(plugin.getLanguageManager().get("messages.error.cannotMergeGlobal"));
                return true;
            }

            // Create new area name
            String newName = area1.getName() + "_" + area2.getName();
            
            // Get bounds from both areas
            AreaDTO.Bounds bounds1 = dto1.bounds();
            AreaDTO.Bounds bounds2 = dto2.bounds();

            // Get permissions from both areas
            AreaDTO.Permissions perms1 = dto1.permissions();
            AreaDTO.Permissions perms2 = dto2.permissions();

            // Create merged area using builder
            AreaBuilder builder = Area.builder()
                .name(newName)
                .world(dto1.world())
                .coordinates(
                    Math.min(bounds1.xMin(), bounds2.xMin()),
                    Math.max(bounds1.xMax(), bounds2.xMax()),
                    Math.min(bounds1.yMin(), bounds2.yMin()),
                    Math.max(bounds1.yMax(), bounds2.yMax()),
                    Math.min(bounds1.zMin(), bounds2.zMin()),
                    Math.max(bounds1.zMax(), bounds2.zMax())
                )
                .priority(Math.max(dto1.priority(), dto2.priority()))
                .showTitle(dto1.showTitle() || dto2.showTitle());

            // Get toggle states from both areas
            Map<String, Boolean> toggleMap1 = perms1.toMap();
            Map<String, Boolean> toggleMap2 = perms2.toMap();
            
            // Get all permission toggles from the PermissionToggle class
            List<adminarea.permissions.PermissionToggle> allToggles = adminarea.permissions.PermissionToggle.getDefaultToggles();
            int mergedTogglesCount = 0;
            
            // Use most restrictive settings if configured
            boolean useMostRestrictive = plugin.getConfigManager().getBoolean("areaSettings.useMostRestrictiveMerge", true);
            
            // Process all toggle permissions based on the merge strategy
            for (adminarea.permissions.PermissionToggle toggle : allToggles) {
                String permKey = toggle.getPermissionNode();
                boolean value;
                
                if (useMostRestrictive) {
                    // For most restrictive, AND the values (false is more restrictive)
                    boolean value1 = toggleMap1.getOrDefault(permKey, toggle.getDefaultValue());
                    boolean value2 = toggleMap2.getOrDefault(permKey, toggle.getDefaultValue());
                    value = value1 && value2;
                } else {
                    // Use value from higher priority area, defaulting to the other area if not present
                    if (dto1.priority() >= dto2.priority()) {
                        value = toggleMap1.getOrDefault(permKey, toggleMap2.getOrDefault(permKey, toggle.getDefaultValue()));
                    } else {
                        value = toggleMap2.getOrDefault(permKey, toggleMap1.getOrDefault(permKey, toggle.getDefaultValue()));
                    }
                }
                
                // Set the permission in the new area
                builder.setPermission(permKey, value);
                mergedTogglesCount++;
            }

            // Build and save merged area
            Area mergedArea = builder.build();
            plugin.getAreaManager().addArea(mergedArea);
            
            // Delete original areas
            plugin.getAreaManager().removeArea(area1);
            plugin.getAreaManager().removeArea(area2);

            // Use appropriate success message based on the language file
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("area", mergedArea.getName());
            placeholders.put("count", String.valueOf(mergedTogglesCount));
            
            // Check if withFlags message exists, use it if available
            if (plugin.getLanguageManager().getRaw("success.area.merge.withFlags") != null) {
                player.sendMessage(plugin.getLanguageManager().get("success.area.merge.withFlags", placeholders));
            } else {
                player.sendMessage(plugin.getLanguageManager().get("success.area.merge.success", placeholders));
            }

            return true;
        } catch (Exception e) {
            plugin.getLogger().error("Error merging areas", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.error.mergeFailed"));
            return false;
        }
    }

    private boolean handleVisualizeCommand(Player player, String[] args) {
        if (!player.hasPermission("adminarea.command.area.visualize")) {
            player.sendMessage(plugin.getLanguageManager().get("messages.noPermission"));
            return true;
        }

        if (args.length != 2) {
            player.sendMessage(plugin.getLanguageManager().get("messages.commands.usage.visualize"));
            return true;
        }

        Area area = plugin.getAreaManager().getArea(args[1]);
        if (area == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound",
                Map.of("area", args[1])));
            return true;
        }

        try {
            // Check area volume first
            AreaDTO.Bounds bounds = area.toDTO().bounds();
            long volume = bounds.volume();
            if (volume > 1000000) {
                player.sendMessage(plugin.getLanguageManager().get("messages.area.visualize.tooLarge"));
                return true;
            }

            // Get visualization duration from config
            int duration = plugin.getConfigManager().getVisualizationDuration();
            
            // Start visualization
            plugin.getGuiManager().visualizeArea(player, area, duration);
            
            // Send success message
            player.sendMessage(plugin.getLanguageManager().get("messages.area.visualize.success",
                Map.of("area", area.getName())));

            return true;
        } catch (Exception e) {
            plugin.getLogger().error("Error visualizing area", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.error.visualizeFailed",
                Map.of("error", e.getMessage())));
            return false;
        }
    }

    private boolean handleStatsCommand(Player player, String[] args) {
        if (!player.hasPermission("adminarea.stats.view")) {
            player.sendMessage(plugin.getLanguageManager().get("messages.permissions.viewStats"));
            return true;
        }

        if (args.length < 2) {
            player.sendMessage(plugin.getLanguageManager().get("messages.commands.usage.stats"));
            return true;
        }

        Area area = plugin.getAreaManager().getArea(args[1]);
        if (area == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound",
                Map.of("area", args[1])));
            return true;
        }

        try {
            if (args.length == 3) {
                switch (args[2].toLowerCase()) {
                    case "export":
                        if (!player.hasPermission("adminarea.stats.export")) {
                            player.sendMessage(plugin.getLanguageManager().get("messages.permissions.exportStats"));
                            return true;
                        }
                        return exportStats(player, area);
                    
                    case "reset":
                        if (!player.hasPermission("adminarea.stats.reset")) {
                            player.sendMessage(plugin.getLanguageManager().get("messages.permissions.resetStats"));
                            return true;
                        }
                        return resetStats(player, area);
                    
                    default:
                        player.sendMessage(plugin.getLanguageManager().get("messages.commands.usage.stats"));
                        return true;
                }
            }

            // View stats
            AreaDTO dto = area.toDTO();
            
            // Get stats from area statistics rather than settings
            org.json.JSONObject stats;
            try {
                // Get stats from AreaStatistics
                stats = plugin.getAreaManager().getAreaStats(area.getName()).getAreaCommandStats(area.getName());
            } catch (Exception e) {
                plugin.getLogger().error("Error retrieving area statistics", e);
                // Fallback to settings if statistics system fails
                stats = dto.settings().optJSONObject("stats");
                if (stats == null) {
                    stats = new JSONObject();
                }
            }

            // Send stats header
            player.sendMessage(plugin.getLanguageManager().get("messages.area.stats.header",
                Map.of("area", area.getName())));

            // Send general stats
            player.sendMessage(plugin.getLanguageManager().get("messages.area.stats.general",
                Map.of(
                    "size", String.format("%d x %d x %d",
                        dto.bounds().xMax() - dto.bounds().xMin() + 1,
                        dto.bounds().yMax() - dto.bounds().yMin() + 1,
                        dto.bounds().zMax() - dto.bounds().zMin() + 1),
                    "priority", String.valueOf(dto.priority())
                )));

            // Send activity stats
            player.sendMessage(plugin.getLanguageManager().get("messages.area.stats.activity",
                Map.of(
                    "visits", String.valueOf(stats.optInt("visits", 0)),
                    "blocks_broken", String.valueOf(stats.optInt("blocks_broken", 0)),
                    "blocks_placed", String.valueOf(stats.optInt("blocks_placed", 0)),
                    "pvp_fights", String.valueOf(stats.optInt("pvp_fights", 0)),
                    "container_accesses", String.valueOf(stats.optInt("container_accesses", 0))
                )));

            return true;
        } catch (Exception e) {
            plugin.getLogger().error("Error handling stats command", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.error.statsError"));
            return false;
        }
    }

    private boolean exportStats(Player player, Area area) {
        try {
            AreaDTO dto = area.toDTO();
            JSONObject stats = dto.settings().optJSONObject("stats");
            if (stats == null) {
                stats = new JSONObject();
            }

            // Create stats export file
            String fileName = area.getName() + "_stats.json";
            File exportFile = new File(plugin.getDataFolder(), "stats/" + fileName);
            exportFile.getParentFile().mkdirs();

            // Add metadata to stats
            JSONObject export = new JSONObject();
            export.put("area_name", area.getName());
            export.put("world", dto.world());
            export.put("bounds", Map.of(
                "xMin", dto.bounds().xMin(),
                "xMax", dto.bounds().xMax(),
                "yMin", dto.bounds().yMin(),
                "yMax", dto.bounds().yMax(),
                "zMin", dto.bounds().zMin(),
                "zMax", dto.bounds().zMax()
            ));
            export.put("priority", dto.priority());
            export.put("stats", stats);
            export.put("export_time", System.currentTimeMillis());

            // Write to file
            try (FileWriter writer = new FileWriter(exportFile)) {
                writer.write(export.toString(4));
            }

            player.sendMessage(plugin.getLanguageManager().get("messages.success.statsExported",
                Map.of(
                    "area", area.getName(),
                    "file", fileName
                )));

            return true;
        } catch (Exception e) {
            plugin.getLogger().error("Error exporting stats", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.error.exportFailed"));
            return false;
        }
    }

    private boolean resetStats(Player player, Area area) {
        try {
            AreaDTO dto = area.toDTO();
            JSONObject settings = dto.settings();
            settings.put("stats", new JSONObject());

            // Create new area with reset stats
            Area updatedArea = Area.builder()
                .name(dto.name())
                .world(dto.world())
                .coordinates(
                    dto.bounds().xMin(), dto.bounds().xMax(),
                    dto.bounds().yMin(), dto.bounds().yMax(),
                    dto.bounds().zMin(), dto.bounds().zMax()
                )
                .priority(dto.priority())
                .showTitle(dto.showTitle())
                .settings(settings)
                .groupPermissions(dto.groupPermissions())
                .inheritedPermissions(dto.inheritedPermissions())
                .toggleStates(dto.toggleStates())
                .defaultToggleStates(dto.defaultToggleStates())
                .inheritedToggleStates(dto.inheritedToggleStates())
                .build();

            // Update area in manager
            plugin.getAreaManager().updateArea(updatedArea);

            player.sendMessage(plugin.getLanguageManager().get("messages.success.statsReset",
                Map.of("area", area.getName())));

            return true;
        } catch (Exception e) {
            plugin.getLogger().error("Error resetting stats", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.error.resetFailed"));
            return false;
        }
    }

    private boolean handleBypassCommand(Player player) {
        if (!player.hasPermission("adminarea.command.area.bypass")) {
            player.sendMessage(plugin.getLanguageManager().get("messages.permissions.bypass"));
            return true;
        }

        String playerName = player.getName();
        
        // Toggle bypass in the plugin instead of maintaining a separate set
        plugin.toggleBypass(playerName);
        boolean newState = plugin.isBypassing(playerName);

        player.sendMessage(plugin.getLanguageManager().get("messages.bypass.toggled",
            Map.of("status", newState ? "enabled" : "disabled")));
        return true;
    }

    private boolean handleReloadCommand(Player player) {
        if (!player.hasPermission("adminarea.command.reload")) {
            player.sendMessage(plugin.getLanguageManager().get("messages.permissions.reload"));
            return true;
        }

        try {
            plugin.reloadConfig();
            player.sendMessage(plugin.getLanguageManager().get("messages.reload"));
        } catch (Exception e) {
            plugin.getLogger().error("Error reloading configuration", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.usage.reloadError",
                Map.of("error", e.getMessage())));
        }
        return true;
    }

    private boolean handleDebugCommand(Player player, String[] args) {
        if (!player.hasPermission("adminarea.debug")) {
            player.sendMessage(plugin.getLanguageManager().get("messages.permissions.debugMode"));
            return true;
        }

        boolean newState;
        if (args.length > 1) {
            newState = args[1].equalsIgnoreCase("on");
        } else {
            newState = !plugin.isDebugMode();
        }

        // Set debug mode in plugin and save to config
        plugin.getConfigManager().set("debug", newState);
        
        // Send confirmation message
        player.sendMessage(plugin.getLanguageManager().get("messages.debug." + (newState ? "enabled" : "disabled")));
        
        if (newState) {
            // Log initial debug info
            plugin.debug("Debug mode enabled by " + player.getName());
            plugin.debug("Server version: " + plugin.getServer().getVersion());
            plugin.debug("Plugin version: " + plugin.getDescription().getVersion());
        }
        
        return true;
    }

    private boolean handleCreateCommand(Player player, String[] args) {
        if (!player.hasPermission("adminarea.command.area.create")) {
            player.sendMessage(plugin.getLanguageManager().get("messages.permissions.createArea"));
            return true;
        }
        
        if (args.length > 1 && args[1].equalsIgnoreCase("global")) {
            openCreateForm(player);
        } else {
            Position[] positions = plugin.getPlayerPositions().get(player.getName());
            if (positions == null || positions[0] == null || positions[1] == null) {
                player.sendMessage(plugin.getLanguageManager().get("messages.positionsNotSet"));
                return true;
            }
            openCreateForm(player);
        }
        return true;
    }

    private boolean handleEditCommand(Player player, String[] args) {
        if (!validateEditPermissions(player, args)) {
            return true;
        }
        Area area = getAreaByName(args[1]);
        if (area != null) {
            plugin.getGuiManager().openFormById(player, FormIds.EDIT_AREA, area);
        }
        return true;
    }

    private boolean handleDeleteCommand(Player player, String[] args) {
        if (!validateDeletePermissions(player, args)) {
            return true;
        }
        Area area = getAreaByName(args[1]);
        if (area != null) {
            plugin.getGuiManager().openFormById(player, FormIds.DELETE_CONFIRM, area);
        }
        return true;
    }

    private boolean handleListCommand(Player player) {
        if (!player.hasPermission("adminarea.command.area.list")) {
            player.sendMessage(plugin.getLanguageManager().get("messages.permissions.listAreas"));
            return true;
        }
        sendAreaList(player);
        return true;
    }

    private boolean handleWandCommand(Player player) {
        if (!player.hasPermission("adminarea.wand.use")) {
            player.sendMessage(plugin.getLanguageManager().get("messages.noPermission"));
            return true;
        }

        // Create and give wand item
        Item wand = Item.get(plugin.getConfigManager().getWandItemType());
        wand.setCustomName("§bArea Wand");
        wand.setLore(
            plugin.getLanguageManager().get("messages.wand.lore.line1"),
            plugin.getLanguageManager().get("messages.wand.lore.line2"),
            plugin.getLanguageManager().get("messages.wand.lore.line3"),
            plugin.getLanguageManager().get("messages.wand.lore.line4")
        );

        player.getInventory().addItem(wand);
        player.sendMessage(plugin.getLanguageManager().get("messages.selection.wandGiven"));
        return true;
    }

    private boolean handleUndoCommand(Player player) {
        if (!player.hasPermission("adminarea.command.area.undo")) {
            player.sendMessage(plugin.getLanguageManager().get("messages.permissions.undoSelection"));
            return true;
        }

        Stack<Position[]> history = selectionHistory.get(player.getName());
        if (history == null || history.isEmpty()) {
            player.sendMessage(plugin.getLanguageManager().get("messages.undoNoActions"));
            return true;
        }

        // Get current positions before undo
        Position[] currentPositions = plugin.getPlayerPositions().get(player.getName());
        if (currentPositions != null) {
            // Save current positions to history before undoing
            if (history.size() >= MAX_HISTORY) {
                history.remove(0); // Remove oldest entry if at max size
            }
            history.push(currentPositions.clone());
        }

        // Pop and restore previous positions
        Position[] previousPositions = history.pop();
        if (previousPositions != null) {
            plugin.getPlayerPositions().put(player.getName(), previousPositions);
            player.sendMessage(plugin.getLanguageManager().get("messages.wand.undoSuccess"));
        } else {
            player.sendMessage(plugin.getLanguageManager().get("messages.wand.noActionsToUndo"));
        }

        return true;
    }

    private void addToSelectionHistory(Player player, Position[] positions) {
        if (positions == null) return;

        Stack<Position[]> history = selectionHistory.computeIfAbsent(player.getName(), k -> new Stack<>());
        if (history.size() >= MAX_HISTORY) {
            history.remove(0); // Remove oldest entry if at max size
        }
        history.push(positions.clone());
    }

    private boolean handleClearCommand(Player player) {
        if (!player.hasPermission("adminarea.command.area.clear")) {
            player.sendMessage(plugin.getLanguageManager().get("messages.permissions.clearSelection"));
            return true;
        }

        // Save current positions to history before clearing
        Position[] currentPositions = plugin.getPlayerPositions().get(player.getName());
        if (currentPositions != null) {
            addToSelectionHistory(player, currentPositions.clone());
        }

        plugin.getPlayerPositions().remove(player.getName());
        player.sendMessage(plugin.getLanguageManager().get("messages.selectionCleared"));
        return true;
    }

    private void openMainMenu(Player player) {
        if (plugin.isDebugMode()) {
            plugin.debug("Opening main menu for player: " + player.getName());
        }
        plugin.getGuiManager().openFormById(player, FormIds.MAIN_MENU, null);
    }

    private void openCreateForm(Player player) {
        if (plugin.isDebugMode()) {
            plugin.debug("Opening create form for player: " + player.getName());
        }
        plugin.getFormIdMap().put(player.getName(),
            new FormTrackingData(FormIds.CREATE_AREA, System.currentTimeMillis()));
        plugin.getGuiManager().openFormById(player, FormIds.CREATE_AREA, null);
    }

    private boolean handlePositionCommand(Player player, int index, String posLabel) {
        if (!player.hasPermission("adminarea.command.area.position")) {
            player.sendMessage(plugin.getLanguageManager().get("messages.permissions.modifySelection"));
            return true;
        }

        Position[] positions = plugin.getPlayerPositions().computeIfAbsent(player.getName(), k -> new Position[2]);
        
        // Save current state to history before modifying
        addToSelectionHistory(player, positions.clone());

        // Update position
        positions[index] = player.getPosition().clone();
        
        // Send success message
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("position", posLabel);
        placeholders.put("x", String.valueOf(positions[index].getFloorX()));
        placeholders.put("y", String.valueOf(positions[index].getFloorY()));
        placeholders.put("z", String.valueOf(positions[index].getFloorZ()));
        player.sendMessage(plugin.getLanguageManager().get("messages.wand.positionSet", placeholders));

        // Check if both positions are set
        if (positions[0] != null && positions[1] != null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.wand.selectionComplete"));
        }

        return true;
    }

    private boolean validateEditPermissions(Player player, String[] args) {
        if (!player.hasPermission("adminarea.command.area.edit")) {
            player.sendMessage(plugin.getLanguageManager().get("messages.noPermission"));
            return false;
        }
        if (args.length < 2) {
            player.sendMessage(plugin.getLanguageManager().get("commands.edit"));
            return false;
        }
        Area area = getAreaByName(args[1]);
        if (area == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound", 
                Map.of("area", args[1])));
            return false;
        }
        return true;
    }

    private boolean validateDeletePermissions(Player player, String[] args) {
        if (!player.hasPermission("adminarea.command.area.delete")) {
            player.sendMessage(plugin.getLanguageManager().get("messages.noPermission")); 
            return false;
        }
        if (args.length < 2) {
            player.sendMessage(plugin.getLanguageManager().get("commands.delete"));
            return false;
        }
        Area area = getAreaByName(args[1]);
        if (area == null) {
            player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound", 
                Map.of("area", args[1])));
            return false;
        }
        return true;
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
            // Calculate area size
            AreaDTO.Bounds bounds = area.getBounds();
            String sizeText;
            if (area.isGlobal()) {
                sizeText = "global";
            } else {
                long sizeX = Math.abs(bounds.xMax() - bounds.xMin()) + 1;
                long sizeY = Math.abs(bounds.yMax() - bounds.yMin()) + 1;
                long sizeZ = Math.abs(bounds.zMax() - bounds.zMin()) + 1;
                sizeText = String.valueOf(sizeX * sizeY * sizeZ);
            }
            
            // Use the proper message format from the language file
            player.sendMessage(plugin.getLanguageManager().get("messages.area.list.entry", Map.of(
                "area", area.getName(),
                "world", area.getWorld(),
                "priority", String.valueOf(area.getPriority()),
                "size", sizeText
            )));
        }
    }

    private void sendHelp(Player player) {
        player.sendMessage(plugin.getLanguageManager().get("messages.commands.help.header"));
        
        // Basic commands
        player.sendMessage(plugin.getLanguageManager().get("messages.commands.help.pos1"));
        player.sendMessage(plugin.getLanguageManager().get("messages.commands.help.pos2"));
        player.sendMessage(plugin.getLanguageManager().get("messages.commands.help.wand"));
        player.sendMessage(plugin.getLanguageManager().get("messages.commands.help.create"));
        player.sendMessage(plugin.getLanguageManager().get("messages.commands.help.edit"));
        player.sendMessage(plugin.getLanguageManager().get("messages.commands.help.delete"));
        player.sendMessage(plugin.getLanguageManager().get("messages.commands.help.list"));
        
        // Area management
        player.sendMessage(plugin.getLanguageManager().get("messages.commands.help.merge"));
        player.sendMessage(plugin.getLanguageManager().get("messages.commands.help.visualize"));
        player.sendMessage(plugin.getLanguageManager().get("messages.commands.help.here"));
        player.sendMessage(plugin.getLanguageManager().get("messages.commands.help.expand"));

        // Stats
        if (player.hasPermission("adminarea.stats.view")) {
            player.sendMessage(plugin.getLanguageManager().get("messages.commands.help.stats"));
        }
        
        // Advanced features
        if (player.hasPermission("adminarea.command.area.bypass")) {
            player.sendMessage(plugin.getLanguageManager().get("messages.commands.help.bypass"));
        }
        
        // Admin commands
        if (player.hasPermission("adminarea.debug")) {
            player.sendMessage(plugin.getLanguageManager().get("messages.commands.help.debug"));
        }
        
        if (player.hasPermission("adminarea.command.area.admin")) {
            player.sendMessage(plugin.getLanguageManager().get("messages.commands.help.reload"));
            player.sendMessage(plugin.getLanguageManager().get("messages.commands.help.cachereload"));
        }
    }

    private boolean handleResetCommand(Player player) {
        if (!player.hasPermission("adminarea.command.area.reset")) {
            player.sendMessage(plugin.getLanguageManager().get("validation.form.error.stateReset"));
            return true;
        }

        // Clean up form tracking data
        plugin.getFormIdMap().remove(player.getName());
        plugin.getFormIdMap().remove(player.getName() + "_editing");
        
        player.sendMessage(plugin.getLanguageManager().get("validation.form.error.stateReset"));
        
        if (plugin.isDebugMode()) {
            plugin.debug("Form state reset for player: " + player.getName());
        }
        
        return true;
    }

    private boolean handleCacheReloadCommand(Player player, String[] args) {
        if (!player.hasPermission("adminarea.command.area.admin")) {
            player.sendMessage(plugin.getLanguageManager().get("messages.noPermission"));
            return true;
        }
        
        try {
            if (args.length >= 2) {
                // Reload specific area
                String areaName = args[1];
                Area area = plugin.getArea(areaName);
                
                if (area == null) {
                    player.sendMessage(plugin.getLanguageManager().get("messages.areaNotFound", 
                        Map.of("area", areaName)));
                    return true;
                }
                
                // Force reload the area from database
                try {
                    // Clear caches first
                    area.clearCaches();
                    area.emergencyClearCaches();
                    
                    // Reload from database
                    Area freshArea = plugin.getDatabaseManager().loadArea(areaName);
                    if (freshArea != null) {
                        player.sendMessage("§aSuccessfully reloaded area §e" + areaName + "§a from database.");
                        
                        // Show toggle states
                        player.sendMessage("§aToggle states:");
                        JSONObject toggles = freshArea.toDTO().toggleStates();
                        for (String key : toggles.keySet()) {
                            if (key.contains("TNT") || key.contains("Explo")) {
                                player.sendMessage("§e  " + key + "§a: §6" + toggles.get(key));
                            }
                        }
                        
                        // First remove the old area to prevent duplication
                        plugin.getAreaManager().removeArea(area);
                        
                        // Then add the fresh area
                        plugin.getAreaManager().addArea(freshArea);
                    } else {
                        player.sendMessage("§cFailed to reload area from database.");
                    }
                } catch (Exception e) {
                    player.sendMessage("§cError reloading area: " + e.getMessage());
                    plugin.getLogger().error("Error reloading area", e);
                }
            } else {
                // Reload all areas
                // Get a count of the current areas
                List<Area> currentAreas = new ArrayList<>(plugin.getAreas());
                int count = currentAreas.size();
                
                player.sendMessage("§aReloading all " + count + " areas from database...");
                
                try {
                    // Reload areas
                    plugin.getAreaManager().loadAreas();
                    
                    // Check if areas were actually loaded
                    if (plugin.getAreas().isEmpty() && !currentAreas.isEmpty()) {
                        player.sendMessage("§cWarning: No areas were loaded from the database! Restoring previous areas...");
                        
                        // Restore the areas that were previously in memory
                        for (Area area : currentAreas) {
                            plugin.getAreaManager().addArea(area);
                        }
                        
                        player.sendMessage("§eRestored " + currentAreas.size() + " areas from memory backup.");
                    } else {
                        player.sendMessage("§aSuccessfully reloaded " + plugin.getAreas().size() + " areas from database.");
                    }
                } catch (Exception e) {
                    player.sendMessage("§cError reloading areas from database: " + e.getMessage());
                    plugin.getLogger().error("Error reloading areas from database", e);
                    
                    // Restore the areas that were previously in memory
                    if (plugin.getAreas().isEmpty() && !currentAreas.isEmpty()) {
                        player.sendMessage("§eRestoring previous areas due to error...");
                        
                        for (Area area : currentAreas) {
                            plugin.getAreaManager().addArea(area);
                        }
                        
                        player.sendMessage("§eRestored " + currentAreas.size() + " areas from memory backup.");
                    }
                }
            }
            
            return true;
        } catch (Exception e) {
            player.sendMessage("§cError reloading cache: " + e.getMessage());
            plugin.getLogger().error("Error handling cache reload command", e);
            return false;
        }
    }
}
