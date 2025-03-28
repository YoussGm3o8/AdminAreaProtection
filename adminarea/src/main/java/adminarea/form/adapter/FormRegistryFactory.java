package adminarea.form.adapter;

import adminarea.AdminAreaProtectionPlugin;

/**
 * Factory for creating and managing form-related components.
 */
public class FormRegistryFactory {
    private static FormResponseAdapter formResponseAdapter;
    
    /**
     * Gets or creates the FormResponseAdapter instance.
     * 
     * @param plugin The plugin instance
     * @return The FormResponseAdapter
     */
    public static FormResponseAdapter getFormResponseAdapter(AdminAreaProtectionPlugin plugin) {
        if (formResponseAdapter == null) {
            formResponseAdapter = new FormResponseAdapter(plugin);
        }
        return formResponseAdapter;
    }
} 