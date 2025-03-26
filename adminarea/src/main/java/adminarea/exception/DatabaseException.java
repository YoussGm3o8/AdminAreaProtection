package adminarea.exception;

/**
 * Custom exception for database operations in the AdminArea plugin.
 */
public class DatabaseException extends Exception {
    
    public DatabaseException(String message) {
        super(message);
    }

    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }

    public DatabaseException(Throwable cause) {
        super("Database operation failed", cause);
    }

    public static DatabaseException dataNotFound(String identifier) {
        return new DatabaseException("Data not found for identifier: " + identifier);
    }

    public static DatabaseException invalidData(String reason) {
        return new DatabaseException("Invalid data: " + reason);
    }

    public static DatabaseException connectionError(Throwable cause) {
        return new DatabaseException("Database connection error", cause);
    }

    public static DatabaseException operationFailed(String operation, Throwable cause) {
        return new DatabaseException("Database operation '" + operation + "' failed", cause);
    }
}
