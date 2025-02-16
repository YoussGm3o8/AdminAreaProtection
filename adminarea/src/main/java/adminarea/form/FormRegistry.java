package adminarea.form;

import java.util.HashMap;
import java.util.Map;

public class FormRegistry {
    private final Map<String, IFormHandler> handlers = new HashMap<>();

    public void registerHandler(IFormHandler handler) {
        if (handler != null && handler.getFormId() != null) {
            handlers.put(handler.getFormId(), handler);
        }
    }

    public IFormHandler getHandler(String formId) {
        return handlers.get(formId);
    }

    public void unregisterHandler(String formId) {
        handlers.remove(formId);
    }

    public void clearHandlers() {
        handlers.clear();
    }

    public boolean hasHandler(String formId) {
        return handlers.containsKey(formId);
    }
}
