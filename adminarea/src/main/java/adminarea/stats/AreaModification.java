package adminarea.stats;

import java.time.Instant;

public record AreaModification(String areaId, String playerId, String modificationType, 
                       String details, Instant timestamp) {}

