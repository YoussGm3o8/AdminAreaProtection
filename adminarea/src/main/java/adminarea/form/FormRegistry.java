package adminarea.form;

import java.util.HashMap;
import java.util.Map;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.form.handlers.*;

public class FormRegistry {
    private final Map<String, IFormHandler> handlers = new HashMap<>();
    private final AdminAreaProtectionPlugin plugin;
    private volatile boolean initialized = false;

    public FormRegistry(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void initialize() {
        if (initialized) {
            return;
        }
        registerDefaultHandlers();
        initialized = true;
    }

    private void registerDefaultHandlers() {
        registerHandler(new MainMenuHandler(plugin));
        registerHandler(new CreateAreaHandler(plugin));
        registerHandler(new EditAreaHandler(plugin));
        registerHandler(new DeleteAreaHandler(plugin));
        registerHandler(new AreaListHandler(plugin));
        registerHandler(new LuckPermsOverrideHandler(plugin));
        registerHandler(new PermissionSettingsHandler(plugin));
    }

    public synchronized void registerHandler(IFormHandler handler) {
        if (handler == null || handler.getFormId() == null || handler.getFormId().trim().isEmpty()) {
            throw new IllegalArgumentException("Handler or its form ID is invalid");
        }
        
        String formId = handler.getFormId();
        if (handlers.containsKey(formId)) {
            plugin.getLogger().debug("Handler already exists for form ID: " + formId + ", skipping registration");
            return;
        }
        
        handlers.put(formId, handler);
        if (plugin.isDebugMode()) {
            plugin.debug("Registered form handler: " + handler.getClass().getSimpleName() + " for ID: " + formId);
        }
    }

    public IFormHandler getHandler(String formId) {
        return handlers.get(formId);
    }

    public void clearHandlers() {
        handlers.clear();
    }
}
