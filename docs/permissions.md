# Permissions

## Admin Permissions
- `adminarea.admin` - Grants access to all plugin features
- `adminarea.command.area` - Allows use of /area commands
- `adminarea.command.area.create` - Allows creating areas
- `adminarea.command.area.edit` - Allows editing areas
- `adminarea.command.area.delete` - Allows deleting areas
- `adminarea.command.area.list` - Allows listing areas
- `adminarea.command.area.bypass` - Allows bypassing area restrictions
- `adminarea.command.reload` - Allows reloading the plugin configuration
- `adminarea.debug` - Allows access to debug commands

## Area Management
- `adminarea.wand` - Allows using the selection wand
- `adminarea.wand.use` - Allows making area selections
- `adminarea.wand.undo` - Allows undoing selections
- `adminarea.stats.view` - Allows viewing area statistics
- `adminarea.stats.export` - Allows exporting area statistics
- `adminarea.stats.reset` - Allows resetting area statistics

## LuckPerms Integration
- `adminarea.luckperms.edit` - Allows editing LuckPerms group permissions
- `adminarea.luckperms.view` - Allows viewing LuckPerms group permissions

## Toggle Management
- `adminarea.command.toggle` - Allows using toggle commands
- `adminarea.toggle.*` - Grants access to all toggle permissions
- `adminarea.toggle.<toggle>` - Grants access to specific toggle

## Example Permission Sets

### Basic Admin
```yaml
permissions:
  - adminarea.command.area
  - adminarea.command.area.create
  - adminarea.command.area.edit
  - adminarea.command.area.list
  - adminarea.wand.use
```

### Area Manager
```yaml
permissions:
  - adminarea.command.area.*
  - adminarea.wand.*
  - adminarea.stats.view
  - adminarea.toggle.*
```

### Senior Admin
```yaml
permissions:
  - adminarea.admin
  - adminarea.debug
  - adminarea.command.reload
  - adminarea.stats.*
  - adminarea.luckperms.*
```
