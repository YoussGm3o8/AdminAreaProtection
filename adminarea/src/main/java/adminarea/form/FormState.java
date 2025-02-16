package adminarea.form;

public record FormState(long startTime) {
    public static FormState create() {
        return new FormState(System.currentTimeMillis());
    }
}

