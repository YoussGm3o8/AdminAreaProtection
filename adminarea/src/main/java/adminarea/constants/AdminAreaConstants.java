package adminarea.constants;

public final class AdminAreaConstants {
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
