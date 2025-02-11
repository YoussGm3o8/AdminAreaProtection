# AdminAreaProtection Plugin

## Description

The AdminAreaProtection plugin is a powerful tool for Nukkit-based Minecraft Pocket Edition (MCPE) servers, designed to provide administrators with comprehensive control over area permissions. This plugin allows you to define protected regions within your server, customize various settings, and manage player interactions, all through an intuitive graphical user interface (GUI) and command-line interface.

## Features

- **Area Creation and Management:** Define protected areas with custom names, priorities, and boundaries.
- **GUI-Based Configuration:** Easily manage area settings through interactive forms.
- **Permission Control:** Restrict or allow various actions within protected areas, including block breaking, block placing, PvP, TNT explosions, and more.
- **Title Notifications:** Display custom titles and subtitles when players enter or leave protected areas.
- **SQLite Persistence:** Store area configurations in a SQLite database for persistent protection.
- **Area Wand:** Use a designated item to quickly select area boundaries.
- **Global Area Protection:** Create areas that span the entire world using the "/area create global" command.
- **Customizable Messages:** Configure messages displayed to players when actions are blocked.
- **Area-Specific Titles:** Customize the titles displayed when entering or leaving specific areas.

## Commands

- **/area:** Base command for managing protected areas.
  - **/area create:** Opens a GUI to create a new protected area. Use "/area create global" to create a global area that bypasses the positions check.
  - **/area edit [name]:** Opens a GUI to edit an existing protected area.
  - **/area delete [name]:** Deletes the specified protected area.
  - **/area list:** Lists all protected areas on the server.
  - **/area wand:** Gives the player an Area Wand for selecting positions.
  - **/area pos1:** Sets the first position for area creation.
  - **/area pos2:** Sets the second position for area creation.
  - **/area bypass:** Toggles bypass mode if permissions are granted.
  - **/area help:** Displays a list of available commands.

## Permissions

- **adminarea.command.area:** Allows access to the base /area command.
  - **adminarea.command.area.create:** Allows creating new protected areas.
  - **adminarea.command.area.edit:** Allows editing existing protected areas.
  - **adminarea.command.area.delete:** Allows deleting protected areas.
  - **adminarea.command.area.list:** Allows listing protected areas.
  - **adminarea.command.area.bypass:** Allows toggling bypass mode.

## How to Use

### 1. Installation

1. Download the AdminAreaProtection.jar file.
2. Place the JAR file into the plugins folder of your Nukkit server.
3. Start or restart the server to load the plugin.

### 2. Creating a Protected Area

1. **Select Area Boundaries:**
   - Use the `/area wand` command to obtain an Area Wand (a stick).
   - Left-click a block to set the first position (Pos1) or use `/area pos1` to set your current location.
   - Right-click a block to set the second position (Pos2) or use `/area pos2` to set your current location.
2. **Open the Area Creation GUI:**
   - Type `/area create` in the chat to open the "Create Area" form.
   - Alternatively, type `/area create global` to create a global area that bypasses the position setup.
3. **Configure Area Settings:**
   - **Area Name:** Enter a unique name for the protected area.
   - **Priority:** Set the priority of the area (higher values take precedence in overlapping regions).
   - **Global area (full world):** Toggle to make the area apply to the entire world.
   - **Pos1 X, Pos1 Y, Pos1 Z:** (Read-only) Displays the coordinates of the first position.
   - **Pos2 X, Pos2 Y, Pos2 Z:** (Read-only) Displays the coordinates of the second position.
   - **Show Title on Enter/Exit:** Toggle to display a title when players enter or leave the area.
   - **Allow Block Break:** Toggle to allow or disallow block breaking within the area.
   - **Allow Block Place:** Toggle to allow or disallow block placing within the area.
   - **Allow Fall Damage:** Toggle to allow or disallow fall damage within the area.
   - **Allow PvP:** Toggle to allow or disallow player-versus-player combat within the area.
   - **Allow TNT:** Toggle to allow or disallow TNT explosions within the area.
   - **Allow Hunger:** Toggle to allow or disallow hunger within the area.
   - **Allow Projectile:** Toggle to allow or disallow projectile usage within the area.
   - **Allow Fire:** Toggle to allow or disallow setting fire within the area.
   - **Allow Fire Spread:** Toggle to allow or disallow fire spread within the area.
   - **Allow Water Flow:** Toggle to allow or disallow water flow within the area.
   - **Allow Lava Flow:** Toggle to allow or disallow lava flow within the area.
   - **Allow Mob Spawning:** Toggle to allow or disallow mob spawning within the area.
   - **Allow Item Use:** Toggle to allow or disallow item usage within the area.
4. **Submit the Form:**
   - Click the "Submit" button to create the protected area.

### 3. Editing a Protected Area

1. **Open the Area Selection GUI:**
   - Type `/area edit` in the chat to open a list of existing areas.
2. **Select an Area:**
   - Click the button corresponding to the area you want to edit. This will open the "Edit Area" form.
3. **Modify Area Settings:**
   - Adjust the settings as needed.
4. **Submit the Form:**
   - Click the "Submit" button to apply the changes.

### 4. Deleting a Protected Area

1. **Open the Area Selection GUI:**
   - Type `/area delete` in the chat to open a list of existing areas.
2. **Select an Area:**
   - Click the button corresponding to the area you want to delete. The area will be removed.

### 5. Configuration

The plugin's behavior can be customized through the `config.yml` file, located in the plugin's data folder.
