package adminarea.util;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.message.Message;

public class LogFilter extends AbstractFilter
{
    public static void registerFilter()
    {
        Logger logger = (Logger) LogManager.getRootLogger();
        logger.addFilter(new LogFilter());
    }

    @Override
    public Result filter(LogEvent event)
    {
        if(event == null)
        {
            return Result.NEUTRAL;
        }
        
        String loggerName = event.getLoggerName();
        if(loggerName == null) {
            return Result.NEUTRAL;
        }
        
        // Check for various Hikari-related logger names
        if(loggerName.contains("Hikari") || 
           loggerName.contains("HikariPool") || 
           loggerName.contains("connectionpool") ||
           loggerName.contains("zaxxer"))
        {
            return Result.DENY;
        }
        
        // Also check message content as a fallback
        if(event.getMessage() != null && event.getMessage().getFormattedMessage() != null) {
            String message = event.getMessage().getFormattedMessage();
            if(message.contains("HikariPool") || message.contains("Hikari")) {
                return Result.DENY;
            }
        }
        
        return Result.NEUTRAL;
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, Message msg, Throwable t)
    {
        if(logger != null && logger.getName() != null && 
          (logger.getName().contains("Hikari") || 
           logger.getName().contains("HikariPool") ||
           logger.getName().contains("zaxxer")))
        {
            return Result.DENY;
        }
        return Result.NEUTRAL;
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, String msg, Object... params)
    {
        if(logger != null && logger.getName() != null && 
          (logger.getName().contains("Hikari") || 
           logger.getName().contains("HikariPool") ||
           logger.getName().contains("zaxxer")))
        {
            return Result.DENY;
        }
        return Result.NEUTRAL;
    }

    @Override
    public Result filter(Logger logger, Level level, Marker marker, Object msg, Throwable t)
    {
        if(logger != null && logger.getName() != null && 
          (logger.getName().contains("Hikari") || 
           logger.getName().contains("HikariPool") ||
           logger.getName().contains("zaxxer")))
        {
            return Result.DENY;
        }
        return Result.NEUTRAL;
    }
}