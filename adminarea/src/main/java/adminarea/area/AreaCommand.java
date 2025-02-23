package adminarea.area;

import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.command.data.CommandParameter;
import cn.nukkit.command.data.CommandParamType;
import cn.nukkit.Player;
import cn.nukkit.level.Position;
import cn.nukkit.item.Item;
import cn.nukkit.level.particle.DustParticle;
import cn.nukkit.math.Vector3;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.Collections;
import java.util.Iterator;
import java.util.ArrayList;
import java.io.File;
import java.io.FileWriter;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.constants.FormIds;
import adminarea.data.FormTrackingData;
import adminarea.util.ValidationUtils;
import org.json.JSONObject;
import adminarea.constants.AdminAreaConstants;

public class AreaCommand extends Command {

    private final AdminAreaProtectionPlugin plugin;
    private final Set<String> bypassPlayers = new HashSet<>();
    public static final String CREATE_AREA_FORM_ID = "create_area_form";

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
                "here", "expand", "debug", "toggle"
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
                
                // Toggle commands
                case "toggle":
                    return handleToggleCommand(player, args);
                
                // Help command
                case "help":
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
        if (!player.hasPermission("adminarea.command.area.create")) {
            player.sendMessage(plugin.getLanguageManager().get("messages.permissions.createArea"));
            return true;
        }
        
        Position pos = player.getPosition();
        Position[] positions = plugin.getPlayerPositions().computeIfAbsent(player.getName(), k -> new Position[2]);
        positions[0] = pos;
        positions[1] = pos;
        
        player.sendMessage(plugin.getLanguageManager().get("messages.positionsSetToCurrent"));
        openCreateForm(player);
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

        player.sendMessage(plugin.getLanguageManager().get("messages.success.expandSuccess",
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

            // Use most restrictive settings if configured
            boolean useMostRestrictive = plugin.getConfigManager().getBoolean("areaSettings.useMostRestrictiveMerge", true);
            
            if (useMostRestrictive) {
                // Apply most restrictive permissions
                builder.setPermission("allowBlockBreak", perms1.allowBlockBreak() && perms2.allowBlockBreak())
                      .setPermission("allowBlockPlace", perms1.allowBlockPlace() && perms2.allowBlockPlace())
                      .setPermission("allowPvP", perms1.allowPvP() && perms2.allowPvP())
                      .setPermission("allowInteract", perms1.allowInteract() && perms2.allowInteract())
                      .setPermission("allowContainer", perms1.allowContainer() && perms2.allowContainer());
            } else {
                // Use settings from higher priority area
                AreaDTO.Permissions primary = dto1.priority() >= dto2.priority() ? perms1 : perms2;
                builder.setPermission("allowBlockBreak", primary.allowBlockBreak())
                      .setPermission("allowBlockPlace", primary.allowBlockPlace())
                      .setPermission("allowPvP", primary.allowPvP())
                      .setPermission("allowInteract", primary.allowInteract())
                      .setPermission("allowContainer", primary.allowContainer());
            }

            // Build and save merged area
            Area mergedArea = builder.build();
            plugin.getAreaManager().addArea(mergedArea);
            
            // Delete original areas
            plugin.getAreaManager().removeArea(area1);
            plugin.getAreaManager().removeArea(area2);

            player.sendMessage(plugin.getLanguageManager().get("messages.success.mergeComplete",
                Map.of("area", mergedArea.getName())));

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
            player.sendMessage(plugin.getLanguageManager().get("messages.usage.visualize"));
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
                player.sendMessage(plugin.getLanguageManager().get("messages.area.tooLargeToVisualize"));
                return true;
            }

            // Get visualization duration from config
            int duration = plugin.getConfigManager().getVisualizationDuration();
            
            // Start visualization
            plugin.getGuiManager().visualizeArea(player, area);
            
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
            player.sendMessage(plugin.getLanguageManager().get("messages.usage.stats"));
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
                        player.sendMessage(plugin.getLanguageManager().get("messages.usage.stats"));
                        return true;
                }
            }

            // View stats
            AreaDTO dto = area.toDTO();
            JSONObject stats = dto.settings().optJSONObject("stats");
            if (stats == null) {
                stats = new JSONObject();
            }

            // Send stats header
            player.sendMessage(plugin.getLanguageManager().get("messages.stats.header",
                Map.of("area", area.getName())));

            // Send general stats
            player.sendMessage(plugin.getLanguageManager().get("messages.stats.general",
                Map.of(
                    "size", String.format("%d x %d x %d",
                        dto.bounds().xMax() - dto.bounds().xMin() + 1,
                        dto.bounds().yMax() - dto.bounds().yMin() + 1,
                        dto.bounds().zMax() - dto.bounds().zMin() + 1),
                    "priority", String.valueOf(dto.priority())
                )));

            // Send activity stats
            player.sendMessage(plugin.getLanguageManager().get("messages.stats.activity",
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
        boolean newState = !bypassPlayers.contains(playerName);
        if (newState) {
            bypassPlayers.add(playerName);
        } else {
            bypassPlayers.remove(playerName);
        }

        player.sendMessage(plugin.getLanguageManager().get("messages.bypassToggled",
            Map.of("status", newState ? "enabled" : "disabled")));
        return true;
    }

    public boolean isBypassEnabled(Player player) {
        return bypassPlayers.contains(player.getName());
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

        plugin.setDebugMode(newState);
        player.sendMessage(plugin.getLanguageManager().get("messages.debug." + (newState ? "enabled" : "disabled")));
        return true;
    }

    private boolean handleToggleCommand(Player player, String[] args) {
        if (!player.hasPermission("adminarea.command.toggle")) {
            player.sendMessage(plugin.getLanguageManager().get("messages.permissions.toggleArea"));
            return true;
        }

        if (args.length == 1 || (args.length == 2 && args[1].equalsIgnoreCase("list"))) {
            // Show toggle list
            player.sendMessage(plugin.getLanguageManager().get("messages.toggleList.header"));
            
            // Get available toggles from config, or use defaults if section doesn't exist
            Map<String, Object> toggles;
            var togglesSection = plugin.getConfigManager().getSection("toggles");
            if (togglesSection == null) {
                // Use default toggles
                toggles = Map.of(
                    "pvp", true,
                    "build", true,
                    "interact", true,
                    "container", true,
                    "redstone", true,
                    "monsters", true
                );
                // Create toggles section in config
                for (Map.Entry<String, Object> entry : toggles.entrySet()) {
                    plugin.getConfigManager().set("toggles." + entry.getKey(), entry.getValue());
                }
                plugin.getConfigManager().getConfig().save();
            } else {
                toggles = togglesSection.getAllMap();
            }
            
            // Display each toggle with its description
            for (String toggle : toggles.keySet()) {
                String description = plugin.getLanguageManager().get("messages.toggleList." + toggle,
                    Map.of("toggle", toggle));
                player.sendMessage(description);
            }
            
            // Show usage
            player.sendMessage(plugin.getLanguageManager().get("messages.toggleList.usage"));
            return true;
        }

        if (args.length < 3) {
            player.sendMessage(plugin.getLanguageManager().get("messages.usage.toggle"));
            return true;
        }

        String action = args[1].toLowerCase();
        String toggleName = args[2];

        // Get toggles section, creating it if it doesn't exist
        var togglesSection = plugin.getConfigManager().getSection("toggles");
        if (togglesSection == null) {
            plugin.getConfigManager().set("toggles", new JSONObject());
            togglesSection = plugin.getConfigManager().getSection("toggles");
        }

        // Validate toggle name
        if (!togglesSection.exists(toggleName)) {
            player.sendMessage(plugin.getLanguageManager().get("messages.error.invalidToggle",
                Map.of("toggle", toggleName)));
            return true;
        }

        // Get toggle state
        boolean newState;
        switch (action) {
            case "on":
                newState = true;
                break;
            case "off":
                newState = false;
                break;
            default:
                player.sendMessage(plugin.getLanguageManager().get("messages.usage.toggle"));
                return true;
        }

        try {
            // Update toggle state in config
            plugin.getConfigManager().set("toggles." + toggleName, newState);
            plugin.getConfigManager().getConfig().save();

            // Notify player
            player.sendMessage(plugin.getLanguageManager().get("messages.toggleUpdated",
                Map.of(
                    "toggle", toggleName,
                    "state", newState ? "enabled" : "disabled"
                )));

            // Log toggle change
            if (plugin.isDebugMode()) {
                plugin.debug("Toggle " + toggleName + " set to " + newState + " by " + player.getName());
            }

            return true;
        } catch (Exception e) {
            plugin.getLogger().error("Error updating toggle state", e);
            player.sendMessage(plugin.getLanguageManager().get("messages.error.toggleUpdateFailed"));
            return false;
        }
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
        player.sendMessage(plugin.getLanguageManager().get("messages.wandGiven"));
        return true;
    }

    private boolean handleUndoCommand(Player player) {
        if (!player.hasPermission("adminarea.command.area.undo")) {
            player.sendMessage(plugin.getLanguageManager().get("messages.permissions.undoSelection"));
            return true;
        }
        // TODO: Implement undo logic
        player.sendMessage(plugin.getLanguageManager().get("messages.wand.undoSuccess"));
        return true;
    }

    private boolean handleClearCommand(Player player) {
        if (!player.hasPermission("adminarea.command.area.clear")) {
            player.sendMessage(plugin.getLanguageManager().get("messages.permissions.clearSelection"));
            return true;
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
        Position pos = player.getPosition();
        String playerName = player.getName();
        Position[] positions = plugin.getPlayerPositions().computeIfAbsent(playerName, k -> new Position[2]);
        positions[index] = pos;
        
        try {
            ValidationUtils validationUtils = new ValidationUtils();
            validationUtils.validateCoordinates(
                (int)pos.getX(), (int)pos.getY(), (int)pos.getZ()
            );
            
            player.sendMessage(String.format(plugin.getLanguageManager().get("messages.positionSet"), 
                posLabel, pos.getFloorX(), pos.getFloorY(), pos.getFloorZ()));
                
            // Check if both positions are set
            if (positions[0] != null && positions[1] != null) {
                plugin.getValidationUtils().validatePositions(positions);
                player.sendMessage(plugin.getLanguageManager().get("messages.bothPositionsSet"));
            }
            return true;
        } catch (IllegalArgumentException e) {
            player.sendMessage(plugin.getLanguageManager().get("messages.error.invalidCoordinates"));
            plugin.getPlayerPositions().remove(playerName);
            return false;
        }
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
}
