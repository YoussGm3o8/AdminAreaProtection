package adminarea.constants;

public final class AdminAreaConstants {
    // Form IDs
    public static final String FORM_MAIN_MENU = "main_menu";
    public static final String FORM_CREATE_AREA = "create_area_form";
    public static final String FORM_EDIT_AREA = "edit_area";
    public static final String FORM_EDIT_SELECT = "edit_area_select";
    public static final String FORM_DELETE_SELECT = "delete_area_select";
    public static final String FORM_PLAYER_MANAGE = "player_area_manage";
    public static final String FORM_EDIT_OPTIONS = "edit_options";
    public static final String FORM_OVERRIDE_TRACK = "override_track_select";
    public static final String FORM_OVERRIDE_GROUP = "override_group_select";
    public static final String FORM_OVERRIDE_EDIT = "override_edit";
    public static final String FORM_EDIT_LIST = "edit_area_list";
    public static final String FORM_DELETE_LIST = "delete_area_list";
    public static final String FORM_PLAYER_MANAGEMENT = "player_area_management";
    public static final String FORM_DELETE_CONFIRM = "delete_area_confirm";
    public static final String FORM_AREA_SETTINGS = "area_settings";
    public static final String FORM_ERROR = "error";
    public static final String FORM_BASIC_SETTINGS = "basic_settings";
    public static final String FORM_PROTECTION_SETTINGS = "protection_settings";
    public static final String FORM_LUCKPERMS_GROUPS = "luckperms_groups";
    public static final String FORM_BUILDING_SETTINGS = "form_building_settings";
    public static final String FORM_ENVIRONMENT_SETTINGS = "form_environment_settings";
    public static final String FORM_ENTITY_SETTINGS = "form_entity_settings";
    public static final String FORM_TECHNICAL_SETTINGS = "form_technical_settings";
    public static final String FORM_SPECIAL_SETTINGS = "form_special_settings";
    
    // LuckPerms Form Constants
    public static final String FORM_GROUP_SELECT = "group_select";
    public static final String FORM_GROUP_PERMISSIONS = "group_permissions";

    // LuckPerms Integration Constants
    public static final String FORM_TRACK_SELECT = "track_select";
    public static final String FORM_TRACK_GROUPS = "track_groups";
    public static final String FORM_GROUP_INHERITANCE = "group_inheritance";
    public static final String FORM_GROUP_WEIGHT = "group_weight";

    // Configuration related messages
    public static final String MSG_CONFIG_RELOADED = "§aConfiguration has been reloaded.";
    public static final String MSG_CONFIG_RELOAD_ERROR = "§cError reloading configuration: %s";

    // Form Titles
    public static final String TITLE_CREATE_AREA = "Create Area";
    public static final String TITLE_EDIT_AREA = "Edit Area - ";
    public static final String TITLE_MAIN_MENU = "Area Protection";
    
    // Messages
    public static final String MSG_AREA_CREATED = "§aArea %s created successfully.";
    public static final String MSG_AREA_UPDATED = "§aArea %s updated successfully.";
    public static final String MSG_AREA_DELETED = "§aArea '%s' has been deleted successfully.";
    public static final String MSG_NO_AREAS = "§eThere are no protected areas.";
    public static final String MSG_NO_PERMISSION = "§cYou don't have permission to manage player areas";
    public static final String MSG_FORM_CREATE_ERROR = "§cError creating delete form";
    public static final String MSG_FORM_ERROR = "§cError: Form could not be created";
    
    private AdminAreaConstants() {} // Prevent instantiation

    public static final class Permissions {
        // Base permissions
        public static final String ADMIN = "adminarea.admin";
        public static final String MANAGE = "adminarea.manage";
        
        // Area management 
        public static final String AREA_CREATE = "adminarea.area.create";
        public static final String AREA_EDIT = "adminarea.area.edit";
        public static final String AREA_DELETE = "adminarea.area.delete";
        public static final String AREA_LIST = "adminarea.area.list";
        
        // LuckPerms integration
        public static final String LUCKPERMS_EDIT = "adminarea.luckperms.edit";
        public static final String LUCKPERMS_VIEW = "adminarea.luckperms.view";
        
        // Settings
        public static final String SETTINGS_MANAGE = "adminarea.settings.manage";
        public static final String TOGGLE_MANAGE = "adminarea.toggle.manage";
    }
}
