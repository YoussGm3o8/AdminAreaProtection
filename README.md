# AdminAreaProtection

A powerful and flexible area protection plugin for Nukkit servers with advanced features, GUI management, and LuckPerms integration.

## Features

### Core Protection
- Advanced area management with priority system
- Custom permission systems with LuckPerms integration
- SQLite database with connection pooling
- Efficient caching system
- Performance monitoring and diagnostics
- Comprehensive event system
- Debug mode for troubleshooting

### Area Management
- Create, edit, and delete protected areas
- Global area protection option
- Priority system for overlapping areas
- Visual area boundaries with particles
- Area merging capability
- Undo/redo system for selections
- Custom enter/leave messages
- Title display configuration

### Protection Features
- Block breaking/placing control
- PvP toggle
- Entity spawning/damage control
- Container access control
- Redstone activation control
- Fire spread prevention
- Liquid flow control
- Explosion protection
- Vehicle damage protection
- TNT protection
- Projectile control
- Hunger toggle
- Fall damage toggle

### GUI System
- Intuitive in-game forms
- Area creation wizard
- Visual area editing
- Quick access menus
- Permission management interface
- Group permission editor
- Area list viewer

### Selection Tools
- Wand tool for area selection
- Position markers
- Visual feedback
- Selection expansion tools
- Quick area creation
- Position undo system
- Coordinate validation

### Permission System
- LuckPerms integration
- Group-based permissions
- Per-area permission overrides
- Temporary permissions
- Bypass mode
- Priority-based permission inheritance

## Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/area` | Opens main GUI menu | adminarea.command.area |
| `/area create [global]` | Create new area | adminarea.command.area.create |
| `/area edit <name>` | Edit existing area | adminarea.command.area.edit |
| `/area delete <name>` | Delete area | adminarea.command.area.delete |
| `/area list` | List all areas | adminarea.command.area.list |
| `/area wand` | Get selection wand | adminarea.wand.use |
| `/area pos1` | Set first position | adminarea.wand.use |
| `/area pos2` | Set second position | adminarea.wand.use |
| `/area merge <area1> <area2>` | Merge two areas | adminarea.command.area.merge |
| `/area visualize <name>` | Show area boundaries | adminarea.command.area.visualize |
| `/area stats <name>` | View area statistics | adminarea.stats.view |
| `/area reload` | Reload configuration | adminarea.command.reload |
| `/area undo` | Undo last selection | adminarea.wand.undo |
| `/area clear` | Clear selection points | adminarea.wand.use |
| `/area here` | Set both positions to current location | adminarea.command.area.create |
| `/area expand <direction> <amount>` | Expand selection | adminarea.command.area.create |
| `/area debug [on|off]` | Toggle debug mode | adminarea.debug |

## Permissions

### Core Permissions
```yaml
adminarea.*:
  description: Gives access to all AdminArea features
  children:
    adminarea.command.*: true
    adminarea.wand.*: true
    adminarea.stats.*: true
    adminarea.bypass: true
    adminarea.debug: true
    adminarea.luckperms.*: true

adminarea.luckperms.*:
  description: Access to all LuckPerms integration features
  children:
    adminarea.luckperms.view: true
    adminarea.luckperms.edit: true
    adminarea.luckperms.tracks: true

adminarea.command.*:
  description: Access to all commands
  children:
    adminarea.command.area: true
    adminarea.command.area.create: true
    adminarea.command.area.edit: true
    adminarea.command.area.delete: true
    adminarea.command.area.list: true
    adminarea.command.area.merge: true
    adminarea.command.reload: true

adminarea.wand.*:
  description: Access to all wand features
  children:
    adminarea.wand.use: true
    adminarea.wand.undo: true

adminarea.stats.*:
  description: Access to statistics features
  children:
    adminarea.stats.view: true
    adminarea.stats.export: true
    adminarea.stats.reset: true
```

## Configuration

```yaml
# Basic Settings
enableMessages: true
debug: false
allowRegularAreaCreation: false
maxAreaPriority: 100
wandItemType: 280  # Stick

# Performance Settings
cacheExpiry: 5  # minutes
undoHistorySize: 10
selectionCooldown: 250  # milliseconds
visualizationDuration: 10  # seconds

# LuckPerms Integration Settings
luckperms:
  enabled: true
  inheritPermissions: true
  updateInterval: 300  # seconds
  cacheExpiry: 60  # seconds
  defaultGroupWeight: 0
  weightInheritance: true
  trackUpdateInterval: 60  # seconds
  groupCacheSize: 100
  trackCacheSize: 20

# Area Settings
area:
  defaultSettings:
    showTitle: true
    priority: 0
  inheritance:
    enabled: true
    allowOverride: true
  messages:
    enter: "Welcome to {area}!"
    leave: "Goodbye from {area}!"

# Messages
messages:
  blockBreak: "§cYou cannot break blocks in {area}."
  blockPlace: "§cYou cannot place blocks in {area}."
  pvp: "§cPVP is disabled in {area}."
  interact: "§cYou cannot interact with that in {area}."
  container: "§cYou cannot access containers in {area}."
  noPermission: "§cYou don't have permission for that."
  areaCreated: "§aArea {area} created successfully."
  areaDeleted: "§aArea {area} deleted successfully."
  selectionComplete: "§aBoth positions set! Use /area create to create your area."
  wandGiven: "§eYou have received the Area Wand!"
  bypassEnabled: "§aBypass mode enabled."
  bypassDisabled: "§aBypass mode disabled."

# Title Settings
title:
  enter:
    main: "§aEntering {area}"
    subtitle: "Welcome to {area}!"
    fadeIn: 20
    stay: 40
    fadeOut: 20
  leave:
    main: "§eLeaving {area}"
    subtitle: "Goodbye from {area}!"
    fadeIn: 20
    stay: 40
    fadeOut: 20

# LuckPerms Integration
luckperms:
  enabled: true
  inheritPermissions: true
  updateInterval: 300  # seconds
```

## Developer API

### Maven Dependency

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>com.github.youssgm3o8</groupId>
        <artifactId>AdminAreaProtection</artifactId>
        <version>1.0.1</version>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

### Basic Usage

```java
// Get API instance
AdminAreaAPI api = plugin.getAPI();

// Create new area
Area area = api.createArea("MyArea", "world",
    x1, x2, y1, y2, z1, z2, priority);

// Check if location is protected
Area protectedArea = api.getProtectingArea(location);

// Get all areas at location
List<Area> areas = api.getApplicableAreas(location);

// Check permissions
boolean hasPermission = api.hasPermission(player, area, "block.break");

// Performance monitoring
PerformanceMonitor monitor = api.getPerformanceMonitor();
double avgTime = monitor.getAverageTime("area_check");
```

### Event Handling

```java
@EventHandler
public void onAreaEnter(AreaEnterEvent event) {
    Player player = event.getPlayer();
    Area area = event.getArea();
    // Handle area entry
}

@EventHandler
public void onAreaLeave(AreaLeaveEvent event) {
    Player player = event.getPlayer();
    Area area = event.getArea();
    // Handle area exit
}

@EventHandler
public void onAreaCreate(AreaCreateEvent event) {
    Area area = event.getArea();
    Player creator = event.getPlayer();
    // Handle area creation
}
```

## Performance Monitoring

The plugin includes built-in performance monitoring for various operations:

```java
PerformanceMonitor monitor = plugin.getPerformanceMonitor();

// Get average timings
double areaCheckTime = monitor.getAverageTime("area_check");
double databaseTime = monitor.getAverageTime("database_operation");
double protectionTime = monitor.getAverageTime("protection_check");

// Get operation counts
long totalChecks = monitor.getCount("area_check");
long cacheHits = monitor.getCount("cache_hit");
long cacheMisses = monitor.getCount("cache_miss");
```

## LuckPerms Integration

### Group Permissions
The plugin integrates with LuckPerms to provide advanced permission management:

```yaml
# Example group permissions in area
groups:
  admin:
    build: true
    break: true
    interact: true
    container: true
    pvp: true
  moderator:
    build: true
    break: true
    interact: true
    container: false
    pvp: false
  default:
    build: false
    break: false
    interact: true
    container: false
    pvp: false
```

### Track Management
You can manage permissions based on LuckPerms tracks:

```yaml
# Example track configuration
tracks:
  staff:
    groups:
      - admin
      - moderator
      - helper
    inheritance: true
    weight: true
```

### Permission Inheritance
Areas respect LuckPerms group inheritance:

```yaml
# Example inheritance
groups:
  admin:
    inherits:
      - moderator
  moderator:
    inherits:
      - helper
  helper:
    inherits:
      - default
```

## Usage Examples

### Managing Group Permissions
```
/area edit <areaname>
-> Select "Group Permissions"
-> Choose group
-> Configure permissions
```

### Track Management
```
/area edit <areaname>
-> Select "Track Management"
-> Choose track
-> Apply permissions to all groups
```

### Inheritance Management
```
/area edit <areaname>
-> Select "Group Permissions"
-> Choose group
-> View inherited permissions
```

## Contributing

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

## License

This project is licensed under the MIT License - see the LICENSE file for details.

# AdminAreaProtection Permission Fix

This fix addresses the issue where permissions (player, group, and track) were being reset to default values (false) during area updates. The solution implements direct methods to update permissions without triggering unnecessary area recreation.

## Key Changes Made

### 1. Direct Permission Update Methods

Added direct methods for all permission types:

- `directUpdatePlayerPermissions`: For player-specific permissions
- `directUpdateGroupPermissions`: For LuckPerms group permissions 
- `directUpdateTrackPermissions`: For LuckPerms track permissions

These methods:
- Mark permission operations to prevent interference from other systems
- Properly handle permission deletion before applying new permissions
- Use direct database operations when possible
- Update in-memory objects correctly
- Verify permission changes to ensure they were applied

### 2. Modified Permission Update Flow

Updated all permission update methods to:
- Use the direct approach for all permission updates
- Bypass the regular area update process that was causing permission resets
- Implement proper transaction boundaries for database operations
- Prevent unnecessary area recreation

### 3. Improved Error Handling

- Added comprehensive error handling for database operations
- Added fallback mechanisms when primary operations fail
- Added detailed debug logging to track permission operations

### 4. Cache Management

- Properly invalidates all relevant caches after permission updates
- Ensures that fresh data is loaded after operations

## How It Works

1. When permissions are updated, we mark the operation as a permission-only operation
2. We delete existing permissions for the entity (player, group, or track) in the specific area
3. We add the new permissions directly to both the database and in-memory objects
4. We invalidate all caches to ensure fresh data is loaded
5. We verify that the permissions were saved correctly

This approach prevents unnecessary area recreation during permission updates, which was the root cause of permission resets.

## Testing

To test this fix:
1. Try updating permissions for players, groups, and tracks in any area
2. Verify that the permissions are saved correctly and persist
3. Check the logs to ensure there's no unnecessary area recreation happening

## Cleanup Tasks

The following methods are now unused and should be removed:

1. `savePlayerPermissions(String areaName, String playerName, Map<String, Boolean> permissions)` - Lines ~516-555
   - Replaced by `directUpdatePlayerPermissions`

2. `handlePermissionOperationInUpdate(Area area)` - Lines ~2373-2410
   - No longer needed as permission operations are handled directly

3. `getActivePlayerPermissionsBeingUpdated(String areaName)` - Lines ~2410-2430
   - No longer needed as we use `isPermissionOperationInProgress` directly

4. `hasPlayerPermissions(Area area)` - Lines ~2430-2450
   - No longer needed with the direct approach

5. `getAllPlayerPermissions(Area area)` - Lines ~2450-2470
   - No longer needed with the direct approach

Additionally, the call to `handlePermissionOperationInUpdate` in the `updateArea` method has been removed, and the call to `getActivePlayerPermissionsBeingUpdated` in the `recreateArea` method has been replaced with a direct check using `isPermissionOperationInProgress`.
