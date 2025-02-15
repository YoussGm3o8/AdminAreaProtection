package adminarea;

import cn.nukkit.plugin.PluginBase;
import cn.nukkit.event.Listener;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.Player;
import cn.nukkit.level.Position;

import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;

public class AdminAreaProtectionPlugin extends PluginBase implements Listener {

    DatabaseManager dbManager;
    private List<Area> areas = new ArrayList<>();
    private AreaCommand areaCommand;
    private HashMap<String, Position[]> playerPositions = new HashMap<>(); // Store player positions
    private HashMap<String, String> formIdMap = new HashMap<>(); // Store form IDs
    private HashMap<String, Boolean> bypassingPlayers = new HashMap<>(); // Store players bypassing protection
    private boolean globalAreaProtection = false; // new flag
    private LuckPerms luckPermsApi; // New field for LuckPerms API

    private boolean enableMessages;
    private String msgBlockBreak;
    private String msgBlockPlace;
    private String msgPVP;

    @Override
    public void onEnable() {
        try {
            // Ensure data folder exists and copy config.yml if missing
            if (!getDataFolder().exists()) {
                getDataFolder().mkdirs();
            }
            saveResource("config.yml", false); // copies bundled config if it doesn't exist
            reloadConfig();       // reload config after saving

            // Log plugin enabling start
            getLogger().info("Enabling AdminAreaProtectionPlugin...");

            getLogger().info("Configuration loaded.");

            // Load toggles and messages
            enableMessages = getConfig().getBoolean("enableMessages", true);
            msgBlockBreak  = getConfig().getString("messages.blockBreak", "§cYou cannot break blocks in this area.");
            msgBlockPlace  = getConfig().getString("messages.blockPlace", "§cYou cannot place blocks in this area.");
            msgPVP         = getConfig().getString("messages.pvp", "§cPVP is disabled in this area.");

            // Initialize SQLite database
            dbManager = new DatabaseManager(this);
            dbManager.init();
            getLogger().info("Database initialized.");

            // Load areas from database
            areas = dbManager.loadAreas();
            getLogger().info("Areas loaded from database.");

            // Register events and commands
            getServer().getPluginManager().registerEvents(this, this);
            getServer().getPluginManager().registerEvents(new ProtectionListener(this), this);
            getServer().getPluginManager().registerEvents(new FormResponseListener(this), this);
            getServer().getPluginManager().registerEvents(new WandListener(this), this); // Register WandListener
            getLogger().info("FormResponseListener registered."); // Added log

            // Initialize AreaCommand
            areaCommand = new AreaCommand(this);

            // Register the command
            getServer().getCommandMap().register("area", areaCommand);
            getLogger().info("Area command registered.");

            // Check for LuckPerms presence and retrieve its API if available.
            if (getServer().getPluginManager().getPlugin("LuckPerms") != null) {
                luckPermsApi = LuckPermsProvider.get();
                getLogger().info("LuckPerms detected. Integration is enabled.");
                // ...optional LuckPerms-specific setup...
            } else {
                getLogger().info("LuckPerms not found. Proceeding without it.");
            }

            getLogger().info("AdminAreaProtectionPlugin enabled successfully.");

        } catch (Exception e) {
            getLogger().error("Failed to enable AdminAreaProtectionPlugin", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("area") || command.getName().equalsIgnoreCase("areaprotect") || command.getName().equalsIgnoreCase("areaadmin")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§cThis command can only be used by players!");
                return true;
            }
            return areaCommand.execute(sender, label, args);
        }
        return false;
    }

    @Override
    public void onDisable() {
        dbManager.close();
        getLogger().info("AdminAreaProtectionPlugin disabled.");
    }

    public List<Area> getAreas() {
        return areas;
    }

    public void addArea(Area area) {
        areas.add(area);
        dbManager.saveArea(area);
    }

    public void updateArea(Area area) {
        dbManager.updateArea(area);
    }

    public void removeArea(Area area) {
        areas.remove(area);
        dbManager.deleteArea(area.getName());
    }

    // This method returns the area (if any) with the highest priority at the given location.
    public Area getHighestPriorityArea(String world, double x, double y, double z) {
        Area highest = null;
        for (Area area : areas) {
            if (area.isInside(world, x, y, z)) {
                if (highest == null || area.getPriority() > highest.getPriority()) {
                    highest = area;
                }
            }
        }
        return highest;
    }

    // Getter and setter for player positions
    public HashMap<String, Position[]> getPlayerPositions() {
        return playerPositions;
    }

    // Getter and setter for form ID map
    public HashMap<String, String> getFormIdMap() {
        return formIdMap;
    }

    // Getters for the messages and toggle
    public boolean isEnableMessages() {
        return enableMessages;
    }

    public String getMsgBlockBreak() {
        return msgBlockBreak;
    }

    public String getMsgBlockPlace() {
        return msgBlockPlace;
    }

    public String getMsgPVP() {
        return msgPVP;
    }

    public boolean isBypassing(String playerName) {
        return bypassingPlayers.getOrDefault(playerName, false);
    }

    public void toggleBypass(String playerName) {
        bypassingPlayers.put(playerName, !isBypassing(playerName));
    }

    public boolean isGlobalAreaProtection() {
        return globalAreaProtection;
    }

    public void setGlobalAreaProtection(boolean global) {
        this.globalAreaProtection = global;
    }

    public void toggleGlobalAreaProtection() {
        this.globalAreaProtection = !this.globalAreaProtection;
    }

    // New getter for LuckPerms API.
    public LuckPerms getLuckPermsApi() {
        return luckPermsApi;
    }

    public DatabaseManager getDatabaseManager() {
        return dbManager;
    }
}
