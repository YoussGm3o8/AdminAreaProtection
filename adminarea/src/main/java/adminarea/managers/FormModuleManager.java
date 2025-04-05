package adminarea.managers;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;
import adminarea.constants.FormIds;
import adminarea.form.handlers.*;
import adminarea.form.utils.FormUtils;
import cn.nukkit.Player;

/**
 * Manager for all MOT-style form handlers in the plugin.
 * This replaces the old FormRegistry and BaseFormHandler system.
 */
public class FormModuleManager {
    private final AdminAreaProtectionPlugin plugin;
    
    // Form handlers
    private final MainMenuHandler mainMenuHandler;
    private final CreateAreaHandler createAreaHandler;
    private final AreaEditListHandler areaEditListHandler;
    private final DeleteAreaHandler deleteAreaHandler;
    private final DeleteConfirmHandler deleteConfirmHandler;
    private final EditAreaHandler editAreaHandler;
    private final BasicSettingsHandler basicSettingsHandler;
    private final BuildingSettingsHandler buildingSettingsHandler;
    private final EnvironmentSettingsHandler environmentSettingsHandler;
    private final EntitySettingsHandler entitySettingsHandler;
    private final TechnicalSettingsHandler technicalSettingsHandler;
    private final SpecialSettingsHandler specialSettingsHandler;
    private final ItemsDropsHandler itemsDropsHandler;
    private final PotionEffectsHandler potionEffectsHandler;
    private final LuckPermsSettingsHandler luckPermsSettingsHandler;
    private final PlayerSettingsHandler playerSettingsHandler;
    private final GroupPermissionsHandler groupPermissionsHandler;
    private final PluginSettingsHandler pluginSettingsHandler;
    
    public FormModuleManager(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        
        // Initialize form utils first
        FormUtils.init(plugin);
        
        // Initialize handlers
        this.mainMenuHandler = new MainMenuHandler(plugin);
        this.createAreaHandler = new CreateAreaHandler(plugin);
        this.areaEditListHandler = new AreaEditListHandler(plugin);
        this.deleteAreaHandler = new DeleteAreaHandler(plugin);
        this.deleteConfirmHandler = new DeleteConfirmHandler(plugin);
        this.editAreaHandler = new EditAreaHandler(plugin);
        this.basicSettingsHandler = new BasicSettingsHandler(plugin);
        this.buildingSettingsHandler = new BuildingSettingsHandler(plugin);
        this.environmentSettingsHandler = new EnvironmentSettingsHandler(plugin);
        this.entitySettingsHandler = new EntitySettingsHandler(plugin);
        this.technicalSettingsHandler = new TechnicalSettingsHandler(plugin);
        this.specialSettingsHandler = new SpecialSettingsHandler(plugin);
        this.itemsDropsHandler = new ItemsDropsHandler(plugin);
        this.potionEffectsHandler = new PotionEffectsHandler(plugin);
        this.luckPermsSettingsHandler = new LuckPermsSettingsHandler(plugin);
        this.playerSettingsHandler = new PlayerSettingsHandler(plugin);
        this.groupPermissionsHandler = new GroupPermissionsHandler(plugin);
        this.pluginSettingsHandler = new PluginSettingsHandler(plugin);
    }
    
    /**
     * Open a form by its ID
     */
    public void openFormById(Player player, String formId, Area area) {
        try {
            switch (formId) {
                case FormIds.MAIN_MENU:
                    mainMenuHandler.open(player);
                    break;
                case FormIds.CREATE_AREA:
                    createAreaHandler.open(player);
                    break;
                case FormIds.EDIT_LIST:
                    areaEditListHandler.open(player);
                    break;
                case FormIds.EDIT_AREA:
                    if (area != null) {
                        editAreaHandler.open(player, area);
                    } else {
                        editAreaHandler.open(player);
                    }
                    break;
                case FormIds.DELETE_LIST:
                    deleteAreaHandler.open(player);
                    break;
                case FormIds.DELETE_CONFIRM:
                    if (area != null) {
                        deleteConfirmHandler.open(player, area);
                    } else {
                        mainMenuHandler.open(player);
                    }
                    break;
                case FormIds.BASIC_SETTINGS:
                    if (area != null) {
                        basicSettingsHandler.open(player, area);
                    } else {
                        basicSettingsHandler.open(player);
                    }
                    break;
                case FormIds.BUILDING_SETTINGS:
                    if (area != null) {
                        buildingSettingsHandler.open(player, area);
                    } else {
                        buildingSettingsHandler.open(player);
                    }
                    break;
                case FormIds.ENVIRONMENT_SETTINGS:
                    if (area != null) {
                        environmentSettingsHandler.open(player, area);
                    } else {
                        environmentSettingsHandler.open(player);
                    }
                    break;
                case FormIds.ENTITY_SETTINGS:
                    if (area != null) {
                        entitySettingsHandler.open(player, area);
                    } else {
                        entitySettingsHandler.open(player);
                    }
                    break;
                case FormIds.TECHNICAL_SETTINGS:
                    if (area != null) {
                        technicalSettingsHandler.open(player, area);
                    } else {
                        technicalSettingsHandler.open(player);
                    }
                    break;
                case FormIds.SPECIAL_SETTINGS:
                    if (area != null) {
                        specialSettingsHandler.open(player, area);
                    } else {
                        specialSettingsHandler.open(player);
                    }
                    break;
                case FormIds.ITEMS_DROPS_SETTINGS:
                    if (area != null) {
                        itemsDropsHandler.open(player, area);
                    } else {
                        itemsDropsHandler.open(player);
                    }
                    break;
                case FormIds.POTION_EFFECTS_SETTINGS:
                    if (area != null) {
                        potionEffectsHandler.open(player, area);
                    } else {
                        potionEffectsHandler.open(player);
                    }
                    break;
                case FormIds.LUCKPERMS_SETTINGS:
                    if (area != null) {
                        luckPermsSettingsHandler.open(player, area);
                    } else {
                        luckPermsSettingsHandler.open(player);
                    }
                    break;
                case FormIds.PLAYER_SETTINGS:
                    if (area != null) {
                        playerSettingsHandler.open(player, area);
                    } else {
                        playerSettingsHandler.open(player);
                    }
                    break;
                case FormIds.GROUP_PERMISSIONS:
                    if (area != null) {
                        groupPermissionsHandler.open(player, area);
                    } else {
                        groupPermissionsHandler.open(player);
                    }
                    break;
                case FormIds.PLUGIN_SETTINGS:
                    pluginSettingsHandler.open(player);
                    break;
                // Add more form handlers here as they are implemented
                default:
                    player.sendMessage(plugin.getLanguageManager().get("messages.form.notFound"));
                    FormUtils.cleanup(player);
                    break;
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error opening form: " + formId, e);
            player.sendMessage(plugin.getLanguageManager().get("messages.form.error.generic"));
            FormUtils.cleanup(player);
        }
    }
    
    /**
     * Open the main menu for a player
     */
    public void openMainMenu(Player player) {
        mainMenuHandler.open(player);
    }
    
    /**
     * Open the create area form for a player
     */
    public void openCreateForm(Player player) {
        createAreaHandler.open(player);
    }
    
    /**
     * Open the area edit list form for a player
     */
    public void openEditListForm(Player player) {
        areaEditListHandler.open(player);
    }
    
    /**
     * Open the edit area form for a player and area
     */
    public void openEditForm(Player player, Area area) {
        editAreaHandler.open(player, area);
    }
    
    /**
     * Open the basic settings form for a player and area
     */
    public void openBasicSettings(Player player, Area area) {
        basicSettingsHandler.open(player, area);
    }
    
    /**
     * Open the building settings form for a player and area
     */
    public void openBuildingSettings(Player player, Area area) {
        buildingSettingsHandler.open(player, area);
    }
    
    /**
     * Open the environment settings form for a player and area
     */
    public void openEnvironmentSettings(Player player, Area area) {
        environmentSettingsHandler.open(player, area);
    }
    
    /**
     * Open the entity settings form for a player and area
     */
    public void openEntitySettings(Player player, Area area) {
        entitySettingsHandler.open(player, area);
    }
    
    /**
     * Open the technical settings form for a player and area
     */
    public void openTechnicalSettings(Player player, Area area) {
        technicalSettingsHandler.open(player, area);
    }
    
    /**
     * Open the special settings form for a player and area
     */
    public void openSpecialSettings(Player player, Area area) {
        specialSettingsHandler.open(player, area);
    }
    
    /**
     * Open the items and drops settings form for a player and area
     */
    public void openItemsDropsSettings(Player player, Area area) {
        itemsDropsHandler.open(player, area);
    }
    
    /**
     * Open the potion effects settings form for a player and area
     */
    public void openPotionEffectsSettings(Player player, Area area) {
        potionEffectsHandler.open(player, area);
    }
    
    /**
     * Open the LuckPerms settings form for a player and area
     */
    public void openLuckPermsSettings(Player player, Area area) {
        luckPermsSettingsHandler.open(player, area);
    }
    
    /**
     * Open the player settings form for a player and area
     */
    public void openPlayerSettings(Player player, Area area) {
        playerSettingsHandler.open(player, area);
    }
    
    /**
     * Open the group permissions form for a player and area
     */
    public void openGroupPermissions(Player player, Area area) {
        groupPermissionsHandler.open(player, area);
    }
    
    /**
     * Open the plugin settings form for a player
     */
    public void openPluginSettings(Player player) {
        pluginSettingsHandler.open(player);
    }
    
    /**
     * Open the delete area list form for a player
     */
    public void openDeleteAreaForm(Player player) {
        deleteAreaHandler.open(player);
    }
    
    /**
     * Get the player settings handler
     */
    public PlayerSettingsHandler getPlayerSettingsHandler() {
        return playerSettingsHandler;
    }
    
    /**
     * Get the group permissions handler
     */
    public GroupPermissionsHandler getGroupPermissionsHandler() {
        return groupPermissionsHandler;
    }
    
    /**
     * Get the plugin settings handler
     */
    public PluginSettingsHandler getPluginSettingsHandler() {
        return pluginSettingsHandler;
    }
    
    /**
     * Get the edit area handler
     */
    public EditAreaHandler getEditAreaHandler() {
        return editAreaHandler;
    }
    
    /**
     * Get the area edit list handler
     */
    public AreaEditListHandler getAreaEditListHandler() {
        return areaEditListHandler;
    }
} 