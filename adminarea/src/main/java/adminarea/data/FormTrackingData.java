package adminarea.data;

public class FormTrackingData {
    private final String formId;
    private final long creationTime;

    public FormTrackingData(String formId, long creationTime) {
        this.formId = formId;
        this.creationTime = creationTime;
    }

    public String getFormId() {
        return formId;
    }

    public long getCreationTime() {
        return creationTime;
    }
}
