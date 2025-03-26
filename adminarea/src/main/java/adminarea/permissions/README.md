# Permission System Architecture

This directory contains the optimized permission system for AdminAreaProtection. The system has been refactored to improve performance, maintainability, and reliability.

## Key Components

### PermissionOverrideManager

The central facade that provides a unified API for all permission-related operations. It:
- Coordinates between the area objects and the database
- Manages caching to reduce database load
- Handles permission synchronization between in-memory and database
- Triggers appropriate events when permissions change

### PermissionDatabaseManager

Handles all direct database operations for permission storage and retrieval:
- Uses HikariCP for connection pooling
- Manages tables for player, group, and track permissions
- Provides transaction support for atomic operations
- Implements efficient batching for better performance

### PermissionCache

Implements efficient caching for permission data:
- Separate caches for player, group, and track permissions
- Automatic expiration to prevent stale data
- Efficient invalidation strategies for specific entities or areas

### PermissionChecker

Evaluates permissions for players in areas:
- Implements the permission hierarchy (player > group > track > area default)
- Uses caching to speed up repeated permission checks
- Integrates with LuckPerms for group inheritance logic

## Permission Structure

Permissions are stored in three separate categories:

1. **Player Permissions**: Permissions specific to individual players
   - Key: `areaName:playerName`
   - Value: Map of permission names to boolean values

2. **Group Permissions**: Permissions for LuckPerms groups
   - Key: `areaName:groupName`
   - Value: Map of permission names to boolean values

3. **Track Permissions**: Permissions for LuckPerms tracks (group collections)
   - Key: `areaName:trackName`
   - Value: Map of permission names to boolean values

## Usage

The system should be accessed via the PermissionOverrideManager, which provides a clean API
for all permission operations. The Area class also provides permissions methods that use 
this manager internally.

```java
// Get permissions for a player in an area
Map<String, Boolean> playerPerms = permissionOverrideManager.getPlayerPermissions("myArea", "playerName");

// Set permissions for a group
Map<String, Boolean> groupPerms = new HashMap<>();
groupPerms.put("adminarea.enter", true);
groupPerms.put("adminarea.build", false);
permissionOverrideManager.setGroupPermissions("myArea", "groupName", groupPerms);

// Synchronize all permissions for an area
permissionOverrideManager.synchronizeFromArea(area);
```

## Caching Strategy

The permission system uses a multi-level caching approach:
1. Memory cache in the PermissionCache class
2. Area object internal caches
3. PermissionChecker evaluation cache

Caches are automatically invalidated when:
- Permissions are updated
- Areas are modified
- Cache entries expire based on time

## Database Structure

The system uses three main tables:
- `player_permissions`: Stores player-specific permissions
- `group_permissions`: Stores group permissions
- `track_permissions`: Stores track permissions

Each table uses a composite primary key of (area_name, entity_name, permission) for efficient lookups.

## Performance Considerations

- The system uses batched operations for better database performance
- Permissions are synchronized in bulk where possible
- The SQLite WAL mode is used for better concurrency
- Connection pooling prevents connection overhead
- Indexes are used for efficient queries 