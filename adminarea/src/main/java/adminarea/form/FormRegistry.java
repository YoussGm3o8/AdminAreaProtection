package adminarea.form;

import java.util.HashMap;
import java.util.Map;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.constants.AdminAreaConstants;
import adminarea.form.handlers.CreateAreaHandler;
import adminarea.form.handlers.EditAreaHandler;
import adminarea.form.handlers.DeleteAreaHandler;
import adminarea.form.handlers.AreaListHandler;
import adminarea.form.handlers.CategorySettingsHandler;

public class FormRegistry {
    private final Map<String, IFormHandler> handlers = new HashMap<>();
    private final AdminAreaProtectionPlugin plugin;

    public FormRegistry(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
    }

    public void registerHandler(IFormHandler handler) {
        if (handler == null || handler.getFormId() == null || handler.getFormId().trim().isEmpty()) {
            throw new IllegalArgumentException("Handler or its form ID is invalid");
        }
        if (handlers.containsKey(handler.getFormId())) {
            throw new IllegalStateException("Handler already registered for form ID: " + handler.getFormId());
        }
        handlers.put(handler.getFormId(), handler);
    }

    public IFormHandler getHandler(String formId) {
        return handlers.get(formId);
    }

    public void clearHandlers() {
        handlers.clear();
    }

    public void registerDefaultHandlers() {
        handlers.clear();
        // Use unified CreateAreaHandler and EditAreaHandler
        registerHandler(new CreateAreaHandler(plugin));
        registerHandler(new EditAreaHandler(plugin));
        registerHandler(new DeleteAreaHandler(plugin));
        registerHandler(new AreaListHandler(plugin));
        // Consolidated category settings for all settings types
        registerHandler(new CategorySettingsHandler(plugin, AdminAreaConstants.FORM_AREA_SETTINGS));
    }
}
