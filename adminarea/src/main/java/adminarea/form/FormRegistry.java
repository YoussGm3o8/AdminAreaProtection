package adminarea.form;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.form.adapter.FormRegistryFactory;
import adminarea.form.adapter.FormResponseAdapter;
import adminarea.form.handlers.*;
import java.util.*;

public class FormRegistry {
    private final AdminAreaProtectionPlugin plugin;
    private final Map<String, IFormHandler> handlers = new HashMap<>();
    private FormResponseAdapter formResponseAdapter;

    public FormRegistry(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        this.formResponseAdapter = FormRegistryFactory.getFormResponseAdapter(plugin);
    }

    public void initialize() {
        plugin.debug("Initializing FormRegistry...");
        registerDefaultHandlers();
        registerHandler(new ItemsDropsHandler(plugin));
        registerHandler(new PotionEffectsHandler(plugin));
    }

    private void registerDefaultHandlers() {
        plugin.debug("Registerin    ndlers...");
        try {
            // Core handlers
            registerHandler(new MainMenuHandler(plugin));
            registerHandler(new CreateAreaHandler(plugin));
            registerHandler(new EditAreaHandler(plugin));
            registerHandler(new DeleteAreaHandler(plugin));
            registerHandler(new AreaEditListHandler(plugin));
            registerHandler(new DeleteConfirmHandler(plugin));
            
            // Settings handlers
            registerHandler(new BasicSettingsHandler(plugin));
            registerHandler(new BuildingSettingsHandler(plugin));
            registerHandler(new EnvironmentSettingsHandler(plugin));
            registerHandler(new EntitySettingsHandler(plugin));
            registerHandler(new TechnicalSettingsHandler(plugin));
            registerHandler(new SpecialSettingsHandler(plugin));
            registerHandler(new PluginSettingsHandler(plugin));
            
            // LuckPerms integration handlers
            registerHandler(new LuckPermsSettingsHandler(plugin));
            registerHandler(new PlayerSettingsHandler(plugin));
            registerHandler(new GroupPermissionsHandler(plugin));
            
            if (plugin.isDebugMode()) {
                plugin.debug("Registered handlers: " + handlers.keySet());
            }
        } catch (Exception e) {
            plugin.getLogger().error("Error registering handlers", e);
        }
    }

    private void registerHandler(IFormHandler handler) {
        String formId = handler.getFormId();
        if (formId == null || formId.trim().isEmpty()) {
            throw new IllegalStateException("Form handler must provide a non-null, non-empty formId");
        }
        handlers.put(formId, handler);
    }

    public IFormHandler getHandler(String formId) {
        return handlers.get(formId);
    }
    
    /**
     * Gets the form response adapter for consistent handling of form inputs.
     * 
     * @return The FormResponseAdapter instance
     */
    public FormResponseAdapter getFormResponseAdapter() {
        return formResponseAdapter;
    }

    @Override
    public String toString() {
        return "FormRegistry{" +
                "handlers=" + handlers.keySet() +
                '}';
    }
}
