# Area Protection Toggles

Each protected area can have various toggles to control what is allowed or denied within its boundaries.

## Toggle Categories

### Building Permissions
- `area.block.break` - Controls block breaking
- `area.block.place` - Controls block placement 
- `area.build` - Master toggle for all building permissions
- `area.interact` - Controls general block interaction

### Environment Settings
- `area.fire.spread` - Controls fire spreading
- `area.fire.start` - Controls fire ignition
- `area.liquid.flow` - Controls liquid spreading
- `area.leaf.decay` - Controls leaf decay
- `area.soil.dry` - Controls farmland drying
- `area.crop.grow` - Controls crop growth
- `area.ice.change` - Controls ice formation/melting
- `area.snow.change` - Controls snow formation/melting

### Entity Controls
- `area.pvp` - Controls player vs player combat
- `area.monster.spawn` - Controls hostile mob spawning
- `area.animal.spawn` - Controls passive mob spawning
- `area.animal.breed` - Controls animal breeding
- `area.animal.tame` - Controls animal taming

### Redstone & Mechanics
- `area.redstone` - Controls redstone functionality
- `area.pistons` - Controls piston operation
- `area.dispenser` - Controls dispenser operation
- `area.hopper` - Controls hopper operation

### Special Permissions
- `area.item.drop` - Controls item dropping
- `area.item.pickup` - Controls item pickup
- `area.container.access` - Controls container access
- `area.vehicle.place` - Controls vehicle placement
- `area.vehicle.enter` - Controls vehicle entry
- `area.vehicle.damage` - Controls vehicle damage
- `area.portal.use` - Controls portal usage
- `area.bed.use` - Controls bed usage

## Toggle Dependencies
Some toggles require other toggles to be enabled:
- `area.block.break` requires `area.build`
- `area.block.place` requires `area.build`
- `area.container.access` requires `area.interact`
- `area.lever.use` requires both `area.interact` and `area.redstone`

## Toggle Conflicts
Some toggles cannot be enabled together:
- `area.pvp` conflicts with `area.invincible`
- `area.mob.spawning` conflicts with `area.peaceful`
- `area.fire.spread` conflicts with `area.fire.protection`
- `area.tnt.explode` conflicts with `area.explosion.protection`

## Examples

### Basic Protection
```yaml
# Prevent building but allow interaction
area.build: false
area.interact: true

# Allow container access despite building being disabled
area.container.access: true
```

### PvP Arena
```yaml
# Enable PvP and mob spawning
area.pvp: true
area.monster.spawn: true
area.animal.spawn: false

# Prevent building/breaking
area.build: false
area.block.break: false
area.block.place: false
```

### Farm Protection
```yaml
# Protect crops and animals
area.crop.grow: true
area.soil.dry: false
area.animal.breed: true
area.animal.tame: true

# Prevent unauthorized access
area.container.access: false
area.interact: false
```
