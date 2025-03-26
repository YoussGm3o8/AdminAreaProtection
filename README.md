# **AdminAreaProtection Plugin**

## **Description**

The AdminAreaProtection plugin is a powerful tool for Nukkit-based Minecraft Pocket Edition (MCPE) servers, designed to provide administrators with comprehensive control over area permissions. This plugin allows you to define protected regions within your server, customize various settings, and manage player interactions, all through an intuitive graphical user interface (GUI) and command-line interface.

## **Features**

- **Area Creation and Management:** Define protected areas with custom names, priorities, and boundaries.
- **Advanced Priority System:** Configure how overlapping areas interact using priorities and logical merging options.
- **GUI-Based Configuration:** Easily manage area settings through interactive forms.
- **Permission Control:** Restrict or allow various actions within protected areas, including block breaking, block placing, PvP, TNT explosions, and more.
- **Title Notifications:** Display custom titles and subtitles when players enter or leave protected areas.
- **SQLite Persistence:** Store area configurations in a SQLite database for persistent protection.
- **Selection Tools:** Use a designated wand item to quickly select area boundaries with visual feedback.
- **Global Area Protection:** Create areas that span the entire world using the "/area create global" command.
- **Customizable Messages:** Configure messages displayed to players when actions are blocked.
- **Area Visualization:** View area boundaries with particle effects.
- **Statistics Tracking:** Monitor area usage including player visits and interactions.
- **Debug Mode:** Troubleshoot issues with detailed logging capabilities.
- **Multi-language Support:** Available in English and Russian with easy language configuration.

### **Permission Toggles**

<details>
<summary><strong>All toggles (52 I think)</strong></summary>

**BUILDING Category:**
- Allow Building (allowBlockPlace) - Default: false
- Allow Breaking (allowBlockBreak) - Default: false
- Allow General Interaction (allowInteract) - Default: true
- Allow Container Access (allowContainer) - Default: false/true
- Allow Item Frame Rotation (allowItemRotation) - Default: false
- Allow Armor Stand Access (allowArmorStand) - Default: false
- Allow Hanging Break (allowHangingBreak) - Default: false
- Allow Door Interaction (allowDoors) - Default: false

**ENVIRONMENT Category:**
- Allow Fire Spread (allowFire) - Default: false
- Allow Liquid Flow (allowLiquid) - Default: true
- Allow Block Spread (allowBlockSpread) - Default: true
- Allow Plant Growth (allowPlantGrowth) - Default: true
- Allow Farmland Trampling (allowFarmlandTrampling) - Default: false
- Allow Leaf Decay (allowLeafDecay) - Default: true
- Allow Block Gravity (allowBlockGravity) - Default: true
- Allow Ice Form/Melt (allowIceForm) - Default: true
- Allow Snow Form/Melt (allowSnowForm) - Default: true

**ENTITY Category:**
- Allow PvP (allowPvP) - Default: false
- Allow Monster Spawning (allowMonsterSpawn) - Default: false
- Allow Animal Spawning (allowAnimalSpawn) - Default: true
- Allow Entity Damage (allowDamageEntities) - Default: false/true
- Allow Animal Breeding (allowBreeding) - Default: true
- Allow Animal Taming (allowTaming) - Default: true
- Allow Monster Target (allowMonsterTarget) - Default: false
- Allow Entity Leashing (allowLeashing) - Default: true
- Allow Shoot Projectile (allowShootProjectile) - Default: false

**ITEMS Category:**
- Allow Item Drops (allowItemDrop) - Default: true
- Allow Item Pickup (allowItemPickup) - Default: true
- Allow XP Drops (allowXPDrop) - Default: true
- Allow XP Pickup (allowXPPickup) - Default: true

**TECHNICAL Category:**
- Allow Redstone (allowRedstone) - Default: true
- Allow Pistons (allowPistons) - Default: true
- Allow Hoppers (allowHopper) - Default: true
- Allow Dispensers (allowDispenser) - Default: true

**SPECIAL Category:**
- Allow TNT (allowTNT) - Default: false
- Allow Creeper (allowCreeper) - Default: false
- Allow Bed Explosions (allowBedExplosion) - Default: false
- Allow Crystal Explosions (allowCrystalExplosion) - Default: false
- Allow Vehicle Place (allowVehiclePlace) - Default: true
- Allow Vehicle Break (allowVehicleBreak) - Default: false
- Allow Vehicle Enter (allowVehicleEnter) - Default: true
- Allow Vehicle Exit (allowVehicleExit) - Default: true
- Allow Fall Damage (allowFallDamage) - Default: true
- Allow Hunger (allowHunger) - Default: true
- Allow Flight (allowFlight) - Default: false
- Allow Ender Pearl (allowEnderPearl) - Default: false
- Allow Chorus Fruit (allowChorusFruit) - Default: false
- Show Effect Messages (showEffectMessages) - Default: true
</details>

### **Area Beacon/Potion Effects**
Applied on entry, removed on exit.

<details>
<summary><strong>All potion selections (up to level 10)</strong></summary>

**Potion Effects in AdminAreaProtection:**
- Speed (allowPotionSpeed)
- Slowness (allowPotionSlowness)
- Haste (allowPotionHaste)
- Mining Fatigue (allowPotionMiningFatigue)
- Strength (allowPotionStrength)
- Instant Health (allowPotionInstantHealth)
- Instant Damage (allowPotionInstantDamage)
- Jump Boost (allowPotionJumpBoost)
- Nausea (allowPotionNausea)
- Regeneration (allowPotionRegeneration)
- Resistance (allowPotionResistance)
- Fire Resistance (allowPotionFireResistance)
- Water Breathing (allowPotionWaterBreathing)
- Invisibility (allowPotionInvisibility)
- Blindness (allowPotionBlindness)
- Night Vision (allowPotionNightVision)
- Hunger (allowPotionHunger)
- Weakness (allowPotionWeakness)
- Poison (allowPotionPoison)
- Wither (allowPotionWither)
- Health Boost (allowPotionHealthBoost)
- Absorption (allowPotionAbsorption)
- Saturation (allowPotionSaturation)
- Levitation (allowPotionLevitation)
</details>

## **Commands**

- /area: Base command for managing protected areas.
  - /area create [global]: Opens a GUI to create a new protected area. Use "global" parameter to create a world-wide area.
  - /area edit <n>: Opens a GUI to edit an existing protected area.
  - /area delete <n>: Deletes the specified protected area.
  - /area list: Lists all protected areas on the server.
  - /area wand: Gives the player an Area Wand for selecting positions.
  - /area pos1: Sets the first position for area creation.
  - /area pos2: Sets the second position for area creation.
  - /area bypass: Toggles bypass mode if permissions are granted.
  - /area help: Displays a list of available commands.
  - /area merge <area1> <area2>: Merges two areas together.
  - /area visualize <n>: Shows area boundaries with particles.
  - /area stats <n>: Views statistics for the specified area.
  - /area here: Shows which area you're currently in.
  - /area expand <dir> <amount>: Expands the current selection.
  - /area undo: Undoes the last selection change.
  - /area clear: Clears selection points.
  - /area debug [on|off]: Toggles debug mode.
  - /area reload: Reloads the plugin configuration.

## **Permissions**

- adminarea.*: Gives access to all AdminArea features.
  - adminarea.command.*: Access to all commands.
    - adminarea.command.area: Allows access to the base /area command.
    - adminarea.command.area.create: Allows creating new protected areas.
    - adminarea.command.area.edit: Allows editing existing protected areas.
    - adminarea.command.area.delete: Allows deleting protected areas.
    - adminarea.command.area.list: Allows listing protected areas.
    - adminarea.command.area.bypass: Allows toggling bypass mode.
    - adminarea.command.reload: Allows reloading the plugin.
  - adminarea.wand.*: Access to all wand features.
    - adminarea.wand.use: Allows using the selection wand.
    - adminarea.wand.undo: Allows undoing selections.
  - adminarea.stats.*: Access to statistics features.
  - adminarea.debug: Allows toggling debug mode.
  - adminarea.bypass: Allows bypassing area restrictions.

## **How to Use**

### **1. Installation**

- Download the AdminAreaProtection.jar file.
- Place the JAR file into the plugins folder of your Nukkit server.
- Start or restart the server to load the plugin.

### **2. Creating a Protected Area**

- **Select Area Boundaries:**
  - Use the /area wand command to obtain an Area Wand (a stick).
  - Left-click a block to set the first position (Pos1) or use /area pos1 to set your current location.
  - Right-click a block to set the second position (Pos2) or use /area pos2 to set your current location.
- **Open the Area Creation GUI:**
  - Type /area create in the chat to open the "Create Area" form.
  - Alternatively, type /area create global to create an area that spans the entire world.
- **Configure Area Settings:**
  - **Area Name:** Enter a unique name for the protected area.
  - **Priority:** Set the priority of the area (higher values take precedence in overlapping regions).
  - **Toggle Protection Features:** Configure permissions for various actions like block breaking, PvP, etc.
- **Submit the Form:**
  - Click the "Submit" button to create the protected area.

## **3. Editing a Protected Area**

- **Open the Area Selection GUI:**
  - Type /area edit in the chat to open a list of existing areas.
- **Select an Area:**
  - Click the button corresponding to the area you want to edit. This will open the "Edit Area" form.
- **Modify Area Settings:**
  - Adjust the settings as needed.
- **Submit the Form:**
  - Click the "Submit" button to apply the changes.

### **4. Deleting a Protected Area**

- **Open the Area Selection GUI:**
  - Type /area delete in the chat to open a list of existing areas.
- **Select an Area:**
  - Click the button corresponding to the area you want to delete. The area will be removed.

## **Configuration**

The plugin's behavior can be customized through configuration files in the plugin's data folder:

### **config.yml**

- debug: Enables detailed logging for troubleshooting.
- language: Sets the language (en_US or ru_RU).
- wandItemType: Defines the item used as a selection wand (default: 280 = stick).
- selectionCooldown: Sets the cooldown between selection actions.
- visualizationDuration: Sets how long particle visualizations last.
- enableMessages: Enables or disables messages when events are blocked.
- particleVisualization: Toggles particle effects for area visualization.
- areaSettings.useMostRestrictiveMerge: Controls how overlapping areas interact.
- luckperms: Configuration for LuckPerms integration if available.

You can also configure the chat prefix in the language files! replace the prefix key with anything you want to customize the looks of the plugin!

### **areaTitles.yml**

Configure custom title messages shown when players enter or leave protected areas:

```
# Example area configuration:
# spawn:
#   enter:
#     main: "§a§lWelcome to Spawn"
#     subtitle: "§eThe central hub of our server!"
#   leave:
#     main: "§c§lLeaving Spawn"
#     subtitle: "§eReturn soon!"
```

## **Placeholders**

The following placeholders can be used in custom messages and titles:

- {area}: Replaced with the name of the protected area.
- {world}: Replaced with the world name.
- {player}: Replaced with the player name.

## **Additional Notes**

- The plugin supports LuckPerms integration for enhanced permission management for individual groups or tracks. Permission priority goes from Player to Group to Track to Area.
- The plugin also supports Mob Plugin integration to prevent creeper explosions, mob/animal spawning and attacking player or being attacked.
- Debugging can be enabled with /area debug on for troubleshooting issues.
- For large servers, caching options can be adjusted in the configuration for optimal performance.
- Player-specific permissions can be configured for special access to protected areas.