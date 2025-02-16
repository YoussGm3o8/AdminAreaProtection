# AdminAreaProtection

A powerful and flexible area protection plugin for Nukkit servers.

## Features

- **Area Management**
  - Create, edit, and delete protected areas
  - Set custom permissions per area
  - Priority system for overlapping areas
  - Global area protection option
  - Visual area boundaries

- **Permission System**
  - LuckPerms integration
  - Group-based permissions
  - Per-area permission overrides
  - Bypass mode for administrators

- **Protection Features**
  - Block breaking/placing protection
  - PvP control
  - Entity spawning control
  - Interaction control
  - Fire spread prevention
  - Liquid flow control
  - Explosion protection

- **User Interface**
  - In-game GUI for area management
  - Easy-to-use wand tool for area selection
  - Visual feedback for actions
  - Custom messages

- **Performance**
  - Efficient area checking
  - Caching system
  - Connection pooling
  - Performance monitoring

- **Developer API**
  - Comprehensive event system
  - Area management API
  - Custom permission handlers
  - Extension points

## Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/area` | Opens main GUI menu | adminarea.command.area |
| `/area create` | Create new area | adminarea.command.area.create |
| `/area edit <name>` | Edit existing area | adminarea.command.area.edit |
| `/area delete <name>` | Delete area | adminarea.command.area.delete |
| `/area list` | List all areas | adminarea.command.area.list |
| `/area wand` | Get selection wand | adminarea.command.area |
| `/area reload` | Reload configuration | adminarea.command.reload |

## Configuration

```yaml
# Enable/disable messages
enableMessages: true

# Allow regular players to create areas
allowRegularAreaCreation: false

# Messages
messages:
  blockBreak: "§cYou cannot break blocks in {area}."
  blockPlace: "§cYou cannot place blocks in {area}."
  pvp: "§cPVP is disabled in {area}."

# Title configurations
title:
  enter:
    main: "§aEntering {area}"
    subtitle: "Welcome to {area}!"
  leave:
    main: "§eLeaving {area}"
    subtitle: "Goodbye from {area}!"
```

## Developer API

### Adding the dependency

```groovy
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.username:AdminAreaProtection:version'
}
```

### Basic Usage

```java
// Get plugin instance
AdminAreaProtectionPlugin plugin = (AdminAreaProtectionPlugin) getServer()
    .getPluginManager().getPlugin("AdminAreaProtection");

// Create new area
Area area = Area.builder()
    .name("MyArea")
    .world("world")
    .coordinates(0, 100, 0, 100, 0, 100)
    .priority(1)
    .build();

plugin.addArea(area);

// Check if location is protected
Area protectedArea = plugin.getHighestPriorityArea(
    "world", x, y, z);
```

### Event Listening

```java
@EventHandler
public void onAreaEnter(AreaEnterEvent event) {
    Player player = event.getPlayer();
    Area area = event.getArea();
    // Handle area entry
}
```

## Performance Monitoring

The plugin includes built-in performance monitoring:

```java
PerformanceMonitor monitor = plugin.getPerformanceMonitor();
double avgTime = monitor.getAverageTime("area_check");
```

## Contributing

1. Fork the repository
2. Create your feature branch
3. Commit your changes
4. Push to the branch
5. Create a Pull Request

## License

This project is licensed under the MIT License - see the LICENSE file for details.
