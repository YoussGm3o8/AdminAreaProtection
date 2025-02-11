[B]AdminAreaProtection Plugin[/B]

[SIZE=4][B]Description[/B][/SIZE]

The AdminAreaProtection plugin is a powerful tool for Nukkit-based Minecraft Pocket Edition (MCPE) servers, designed to provide administrators with comprehensive control over area permissions. This plugin allows you to define protected regions within your server, customize various settings, and manage player interactions, all through an intuitive graphical user interface (GUI) and command-line interface.

[SIZE=4][B]Features[/B][/SIZE]

*   [B]Area Creation and Management:[/B] Define protected areas with custom names, priorities, and boundaries.
*   [B]GUI-Based Configuration:[/B] Easily manage area settings through interactive forms.
*   [B]Permission Control:[/B] Restrict or allow various actions within protected areas, including block breaking, block placing, PvP, TNT explosions, and more.
*   [B]Title Notifications:[/B] Display custom titles and subtitles when players enter or leave protected areas.
*   [B]SQLite Persistence:[/B] Store area configurations in a SQLite database for persistent protection.
*   [B]Area Wand:[/B] Use a designated item to quickly select area boundaries.
*   [B]Global Area Protection:[/B] Create areas that span the entire world using the "/area create global" command.
*   [B]Customizable Messages:[/B] Configure messages displayed to players when actions are blocked.
*   [B]Area-Specific Titles:[/B] Customize the titles displayed when entering or leaving specific areas.

[SIZE=4][B]Commands[/B][/SIZE]

*   /area: Base command for managing protected areas.
    *   /area create: Opens a GUI to create a new protected area. Use "/area create global" to create a global area that bypasses the positions check.
    *   /area edit [name]: Opens a GUI to edit an existing protected area.
    *   /area delete [name]: Deletes the specified protected area.
    *   /area list: Lists all protected areas on the server.
    *   /area wand: Gives the player an Area Wand for selecting positions.
    *   /area pos1: Sets the first position for area creation.
    *   /area pos2: Sets the second position for area creation.
    *   /area bypass: Toggles bypass mode if permissions are granted.
    *   /area help: Displays a list of available commands.

[SIZE=4][B]Permissions[/B][/SIZE]

*   adminarea.command.area: Allows access to the base /area command.
    *   adminarea.command.area.create: Allows creating new protected areas.
    *   adminarea.command.area.edit: Allows editing existing protected areas.
    *   adminarea.command.area.delete: Allows deleting protected areas.
    *   adminarea.command.area.list: Allows listing protected areas.
    *   adminarea.command.area.bypass: Allows toggling bypass mode.

[SIZE=4][B]How to Use[/B][/SIZE]

[SIZE=3][B]1. Installation[/B][/SIZE]

1.  Download the AdminAreaProtection.jar file.
2.  Place the JAR file into the plugins folder of your Nukkit server.
3.  Start or restart the server to load the plugin.

[SIZE=3][B]2. Creating a Protected Area[/B][/SIZE]

1.  [B]Select Area Boundaries:[/B]
    *   Use the /area wand command to obtain an Area Wand (a stick).
    *   Left-click a block to set the first position (Pos1) or use /area pos1 to set your current location.
    *   Right-click a block to set the second position (Pos2) or use /area pos2 to set your current location.
2.  [B]Open the Area Creation GUI:[/B]
    *   Type /area create in the chat to open the "Create Area" form.
    *   Alternatively, type /area create global to create a global area that bypasses the position setup.
3.  [B]Configure Area Settings:[/B]
    *   [B]Area Name:[/B] Enter a unique name for the protected area.
    *   [B]Priority:[/B] Set the priority of the area (higher values take precedence in overlapping regions).
    *   [B]Global area (full world):[/B] Toggle to make the area apply to the entire world.
    *   [B]Pos1 X, Pos1 Y, Pos1 Z:[/B] (Read-only) Displays the coordinates of the first position.
    *   [B]Pos2 X, Pos2 Y, Pos2 Z:[/B] (Read-only) Displays the coordinates of the second position.
    *   [B]Show Title on Enter/Exit:[/B] Toggle to display a title when players enter or leave the area.
    *   [B]Allow Block Break:[/B] Toggle to allow or disallow block breaking within the area.
    *   [B]Allow Block Place:[/B] Toggle to allow or disallow block placing within the area.
    *   [B]Allow Fall Damage:[/B] Toggle to allow or disallow fall damage within the area.
    *   [B]Allow PvP:[/B] Toggle to allow or disallow player-versus-player combat within the area.
    *   [B]Allow TNT:[/B] Toggle to allow or disallow TNT explosions within the area.
    *   [B]Allow Hunger:[/B] Toggle to allow or disallow hunger within the area.
    *   [B]Allow Projectile:[/B] Toggle to allow or disallow projectile usage within the area.
    *   [B]Allow Fire:[/B] Toggle to allow or disallow setting fire within the area.
    *   [B]Allow Fire Spread:[/B] Toggle to allow or disallow fire spread within the area.
    *   [B]Allow Water Flow:[/B] Toggle to allow or disallow water flow within the area.
    *   [B]Allow Lava Flow:[/B] Toggle to allow or disallow lava flow within the area.
    *   [B]Allow Mob Spawning:[/B] Toggle to allow or disallow mob spawning within the area.
    *   [B]Allow Item Use:[/B] Toggle to allow or disallow item usage within the area.
4.  [B]Submit the Form:[/B]
    *   Click the "Submit" button to create the protected area.

[SIZE=3][B]3. Editing a Protected Area[/B][/SIZE]

1.  [B]Open the Area Selection GUI:[/B]
    *   Type /area edit in the chat to open a list of existing areas.
2.  [B]Select an Area:[/B]
    *   Click the button corresponding to the area you want to edit. This will open the "Edit Area" form.
3.  [B]Modify Area Settings:[/B]
    *   Adjust the settings as needed.
4.  [B]Submit the Form:[/B]
    *   Click the "Submit" button to apply the changes.

[SIZE=3][B]4. Deleting a Protected Area[/B][/SIZE]

1.  [B]Open the Area Selection GUI:[/B]
    *   Type /area delete in the chat to open a list of existing areas.
2.  [B]Select an Area:[/B]
    *   Click the button corresponding to the area you want to delete. The area will be removed.

[SIZE=3][B]5. Configuration[/B][/SIZE]

The plugin's behavior can be customized through the config.yml file, located in the plugin's data folder.

[SIZE=2][B]General Settings[/B][/SIZE]

*   enableMessages: (Boolean) Enables or disables sending messages to players when actions are blocked. Default: true.

[SIZE=2][B]Custom Messages[/B][/SIZE]

*   messages:
    *   blockBreak: (String) Message displayed when a player attempts to break a block in a protected area. Default: "§cYou cannot break blocks in {area}."
    *   blockPlace: (String) Message displayed when a player attempts to place a block in a protected area. Default: "§cYou cannot place blocks in {area}."
    *   pvp: (String) Message displayed when a player attempts to engage in PvP in a protected area. Default: "§cPVP is disabled in {area}."

[SIZE=2][B]Title Configuration[/B][/SIZE]

*   title:
    *   enter:
        *   main: (String) Main title displayed when a player enters a protected area. Default: "§aEntering {area}"
        *   subtitle: (String) Subtitle displayed when a player enters a protected area. Default: "Welcome to {area}!"
    *   leave:
        *   main: (String) Main title displayed when a player leaves a protected area. Default: "§eLeaving {area}"
        *   subtitle: (String) Subtitle displayed when a player leaves a protected area. Default: "Goodbye from {area}!"

[SIZE=2][B]Area-Specific Title Configurations[/B][/SIZE]

*   areaTitles:
    *   [AreaName]:
        *   enter:
            *   main: (String) Main title displayed when a player enters the specified area.
            *   subtitle: (String) Subtitle displayed when a player enters the specified area.
        *   leave:
            *   main: (String) Main title displayed when a player leaves the specified area.
            *   subtitle: (String) Subtitle displayed when a player leaves the specified area.

[SIZE=2][B]Placeholders[/B][/SIZE]

The following placeholders can be used in custom messages and titles:

*   {area}: Replaced with the name of the protected area.

[SIZE=2][B]Example Configuration[/B][/SIZE]

```yaml
# Enable or disable sending messages to players when events are blocked
enableMessages: true

# Custom messages for blocked events
messages:
  blockBreak: "§cYou cannot break blocks in {area}."
  blockPlace: "§cYou cannot place blocks in {area}."
  pvp: "§cPVP is disabled in {area}."

# Global title configuration for area enter/leave events:
title:
  enter:
    main: "§aEntering {area}"
    subtitle: "Welcome to {area}!"
  leave:
    main: "§eLeaving {area}"
    subtitle: "Goodbye from {area}!"

# Area-specific title configurations:
areaTitles:
  Spawn:
    enter:
      main: "§6You are entering Spawn!"
      subtitle: "Enjoy your stay at Spawn"
    leave:
      main: "§4You left Spawn!"
      subtitle: "Stay safe out there..."
```
