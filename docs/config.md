# Configuration

## Main Configuration (config.yml)

```yaml
# Debug settings
debug: false
enableDebugLog: false

# Protection settings
enableMessages: true
messagesCooldown: 3000  # milliseconds
allowRegularAreaCreation: false

# Wand settings
wandItemType: 369  # blaze rod
visualizationEnabled: true
visualizationDuration: 200  # ticks

# Area settings
maxPriority: 100
minPriority: 0
maxAreasPerPlayer: 10

# Default toggle states
defaultToggles:
  area.build: false
  area.interact: false
  area.pvp: false
  area.monster.spawn: true
  area.redstone: true

# LuckPerms integration
enableLuckPerms: true
luckpermsUpdateInterval: 300  # seconds

# Performance settings
cacheSize: 1000
cacheExpiry: 300  # seconds
useAsyncChunkLoading: true
```

## Language Configuration (messages.yml)

```yaml
messages:
  protection:
    blockBreak: "§cYou cannot break blocks here!"
    blockPlace: "§cYou cannot place blocks here!"
    interact: "§cYou cannot interact with this!"
    container: "§cYou cannot access containers here!"
    pvp: "§cPvP is disabled in this area!"
    vehicle: "§cYou cannot use vehicles here!"
    
  area:
    created: "§aArea {name} has been created!"
    edited: "§aArea {name} has been updated!"
    deleted: "§aArea {name} has been deleted!"
    
  toggle:
    enabled: "§aToggle {toggle} has been enabled for {area}"
    disabled: "§cToggle {toggle} has been disabled for {area}"
    error: "§cCannot set {toggle} due to conflicts!"
```

## Performance Tuning

The plugin includes several performance optimization options:

### Caching
- `cacheSize`: Number of protection checks to cache
- `cacheExpiry`: How long to keep cache entries (seconds)

### Async Processing
- `useAsyncChunkLoading`: Load chunks asynchronously
- `asyncVisualization`: Process area visualization async

### Memory Usage
- `minimizeMemoryUsage`: Optimize for memory over speed
- `unloadInactiveAreas`: Unload areas in inactive worlds
