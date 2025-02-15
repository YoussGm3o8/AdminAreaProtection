package adminarea;

import cn.nukkit.entity.item.EntityPrimedTNT;
import cn.nukkit.event.Listener;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.block.*;
import cn.nukkit.event.entity.EntityDamageEvent;
import cn.nukkit.event.player.PlayerItemConsumeEvent;
import cn.nukkit.level.Position;
import cn.nukkit.event.entity.EntityExplodeEvent;
import cn.nukkit.event.entity.EntityShootBowEvent;
import cn.nukkit.event.player.PlayerFoodLevelChangeEvent;
import cn.nukkit.event.entity.CreatureSpawnEvent;
import cn.nukkit.event.player.PlayerMoveEvent;
import cn.nukkit.event.player.PlayerBucketEmptyEvent; // add import for bucket event
import cn.nukkit.event.player.PlayerInteractEvent; // add import for interact event
import cn.nukkit.item.Item;

import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.event.entity.EntityDamageEvent.DamageCause;

import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;

public class ProtectionListener implements Listener {

    private final AdminAreaProtectionPlugin plugin;
    private final HashMap<String, Area> lastAreaMap = new HashMap<>();
    private final Map<String, Long> lastActionTime = new HashMap<>(); // Player name, last action time in millis
    private final long actionCooldown = 200; // Milliseconds

    public ProtectionListener(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
    }

    // Helper method: returns an override value if present; otherwise null.
    private Boolean getPlayerOverride(Area area, Player player, String actionKey) {
        String rank = "default";
        if (plugin.getLuckPermsApi() != null) {
            try {
                rank = plugin.getLuckPermsApi().getUserManager()
                        .getUser(player.getUniqueId())
                        .getPrimaryGroup();
            } catch(Exception e) {
                plugin.getLogger().warning("Failed to retrieve player's rank; defaulting.");
            }
        }
        JSONObject settings = area.getSettings();
        String key = "override." + rank + "." + actionKey;
        if (settings.has(key)) {
            return settings.getBoolean(key);
        }
        return null;
    }

    private boolean isCoolingDown(Player player) {
        long now = System.currentTimeMillis();
        long lastTime = lastActionTime.getOrDefault(player.getName(), 0L);
        if (now - lastTime < actionCooldown) {
            return true;
        }
        lastActionTime.put(player.getName(), now);
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        String world = player.getLevel().getFolderName();
        double x = event.getBlock().getX();
        double y = event.getBlock().getY();
        double z = event.getBlock().getZ();
        Area area = plugin.getHighestPriorityArea(world, x, y, z);
        if (area != null) {
            Boolean override = getPlayerOverride(area, player, "block.break");
            if (override != null) {
                if (!override) {
                    event.setCancelled(true);
                    player.sendMessage(plugin.getMsgBlockBreak());
                }
                return;
            }
            if (!area.getSettings().optBoolean("block.break", true)) {
                event.setCancelled(true);
                player.sendMessage(plugin.getMsgBlockBreak());
            }
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (plugin.isBypassing(player.getName())) {
            return; // Bypassing, so don't apply protection
        }
        // Use block's coordinates instead of player's position.
        Block placedBlock = event.getBlock();
        Area area = plugin.getHighestPriorityArea(player.getLevel().getFolderName(), placedBlock.getX(), placedBlock.getY(), placedBlock.getZ());
        if (area != null) {
            Boolean override = getPlayerOverride(area, player, "place");
            if (override != null) {
                if (!override) {
                    event.setCancelled(true);
                    if (plugin.isEnableMessages()) {
                        player.sendMessage(plugin.getMsgBlockPlace().replace("{area}", area.getName()));
                    }
                }
                return;
            }
            if (!area.getSettings().optBoolean("place", true)) {
                event.setCancelled(true);
                if (plugin.isEnableMessages()) {
                    player.sendMessage(plugin.getMsgBlockPlace().replace("{area}", area.getName()));
                }
            }
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            Area area = plugin.getHighestPriorityArea(player.getLevel().getFolderName(), player.x, player.y, player.z);
            if (area != null) {
                // For fall damage, use "no_fall" toggle (no override applied here)
                if (event.getCause() == DamageCause.FALL && !area.getSettings().optBoolean("no_fall", true)) {
                    event.setCancelled(true);
                    return;
                }
                // For PvP (ENTITY_ATTACK) events:
                if (event.getCause() == DamageCause.ENTITY_ATTACK) {
                    Boolean override = getPlayerOverride(area, player, "pvp");
                    if (override != null) {
                        if (!override) {
                            event.setCancelled(true);
                            if (plugin.isEnableMessages()) {
                                player.sendMessage(plugin.getMsgPVP().replace("{area}", area.getName()));
                            }
                        }
                        return;
                    }
                    if (!area.getSettings().optBoolean("pvp", true)) {
                        event.setCancelled(true);
                        if (plugin.isEnableMessages()) {
                            player.sendMessage(plugin.getMsgPVP().replace("{area}", area.getName()));
                        }
                    }
                }
            }
        }
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        if (event.getEntity() instanceof EntityPrimedTNT) {
            EntityPrimedTNT tnt = (EntityPrimedTNT) event.getEntity();
            Area area = plugin.getHighestPriorityArea(tnt.getLevel().getFolderName(), tnt.x, tnt.y, tnt.z);
            if (area != null && !area.getSettings().optBoolean("tnt", true)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onPlayerFoodLevelChange(PlayerFoodLevelChangeEvent event) {
        Player player = event.getPlayer();
        Area area = plugin.getHighestPriorityArea(player.getLevel().getFolderName(), player.x, player.y, player.z);
        if (area != null && !area.getSettings().optBoolean("hunger", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityShootBow(EntityShootBowEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            Area area = plugin.getHighestPriorityArea(player.getLevel().getFolderName(), player.x, player.y, player.z);
            if (area != null && !area.getSettings().optBoolean("no_projectile", true)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onBlockIgnite(BlockIgniteEvent event) {
        Block block = event.getBlock();
        Area area = plugin.getHighestPriorityArea(block.getLevel().getFolderName(), block.getX(), block.getY(), block.getZ());
        if (area != null && !area.getSettings().optBoolean("set_fire", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockSpread(BlockSpreadEvent event) {
        Block block = event.getBlock();
        Area area = plugin.getHighestPriorityArea(block.getLevel().getFolderName(), block.getX(), block.getY(), block.getZ());
        if (area != null && !area.getSettings().optBoolean("fire_spread", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockFromTo(BlockFromToEvent event) {
        Block block = event.getBlock();
        Area area = plugin.getHighestPriorityArea(block.getLevel().getFolderName(), block.getX(), block.getY(), block.getZ());
        if (area != null) {
            int id = block.getId();
            if ((id == 8 || id == 9) && !area.getSettings().optBoolean("water_flow", true)) {
                event.setCancelled(true);
            } else if ((id == 10 || id == 11) && !area.getSettings().optBoolean("lava_flow", true)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        Position position = event.getPosition();
        Area area = plugin.getHighestPriorityArea(position.getLevel().getFolderName(), position.getX(), position.getY(), position.getZ());
        if (area != null && !area.getSettings().optBoolean("mob_spawning", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerItemUse(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        Area area = plugin.getHighestPriorityArea(player.getLevel().getFolderName(), player.x, player.y, player.z);
        if (area != null && !area.getSettings().optBoolean("item_use", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Area currentArea = plugin.getHighestPriorityArea(
            player.getLevel().getFolderName(),
            player.getX(),
            player.getY(),
            player.getZ()
        );
        Area lastArea = lastAreaMap.get(player.getName());

        if (currentArea != lastArea) {
            // Leaving previous area
            if (lastArea != null && lastArea.isShowTitle()) {
                String basePath = "areaTitles." + lastArea.getName() + ".leave.";
                String leaveTitle;
                String leaveSubtitle;
                if (plugin.getConfig().get(basePath + "main") != null) {
                    leaveTitle = plugin.getConfig().getString(basePath + "main").replace("{area}", lastArea.getName());
                    leaveSubtitle = plugin.getConfig().getString(basePath + "subtitle", "").replace("{area}", lastArea.getName());
                } else {
                    leaveTitle = plugin.getConfig().getString("title.leave.main", "§eLeaving {area}")
                                    .replace("{area}", lastArea.getName());
                    leaveSubtitle = plugin.getConfig().getString("title.leave.subtitle", "").replace("{area}", lastArea.getName());
                }
                player.sendTitle(leaveTitle, leaveSubtitle, 10, 20, 10);
            }
            // Entering new area
            if (currentArea != null && currentArea.isShowTitle()) {
                String basePath = "areaTitles." + currentArea.getName() + ".enter.";
                String enterTitle;
                String enterSubtitle;
                if (plugin.getConfig().get(basePath + "main") != null) {
                    enterTitle = plugin.getConfig().getString(basePath + "main").replace("{area}", currentArea.getName());
                    enterSubtitle = plugin.getConfig().getString(basePath + "subtitle", "").replace("{area}", currentArea.getName());
                } else {
                    enterTitle = plugin.getConfig().getString("title.enter.main", "§aEntering {area}")
                                    .replace("{area}", currentArea.getName());
                    enterSubtitle = plugin.getConfig().getString("title.enter.subtitle", "").replace("{area}", currentArea.getName());
                }
                player.sendTitle(enterTitle, enterSubtitle, 10, 20, 10);
            }
            // Update last area for player
            if (currentArea == null) {
                lastAreaMap.remove(player.getName());
            } else {
                lastAreaMap.put(player.getName(), currentArea);
            }
        }
    }

    @EventHandler
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Player player = event.getPlayer();
        Area area = plugin.getHighestPriorityArea(player.getLevel().getFolderName(), player.getX(), player.getY(), player.getZ());
        if (area != null && !area.getSettings().optBoolean("place", true)) {
            Item bucket = event.getBucket(); // bucket item
            if (bucket.getId() == 325 || bucket.getId() == 327) { // water bucket or lava bucket
                event.setCancelled(true);
                if (plugin.isEnableMessages()) {
                    player.sendMessage(plugin.getMsgBlockPlace().replace("{area}", area.getName()));
                }
            }
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (plugin.isBypassing(player.getName())) {
            return; // Bypass enabled
        }
        if (isCoolingDown(player)) {
            event.setCancelled(true);
            return;
        }
        // Do not intercept if the player is holding a block (to avoid interfering with block placement)
        if (player.getInventory().getItemInHand().getId() < 256) {
            return;
        }
        Block block = event.getBlock();
        // Check only interactable blocks (example: chest, door, trapdoor)
        int id = block.getId();
        // You may adjust the ids below as needed.
        if (id == 54   // Chest
         || id == 146  // Ender Chest
         || id == 64   // Door
         || id == 71   // Wooden Door
         || id == 96   // Trapdoor
         || id == 107  // Iron Trapdoor
         || id == -203 // barrel
         || id == -198 // smoker
         || id == -196 // blast furnace
         || id == -194 // lectern
         || id == -195 // grindstone
         || id == -197 // stonecutter
         || id == 146 // trapped chest
        ) 
        {
            Area area = plugin.getHighestPriorityArea(block.getLevel().getFolderName(), block.getX(), block.getY(), block.getZ());
            if (area != null) {
                Boolean override = getPlayerOverride(area, player, "object.interact");
                if (override != null) {
                    if (!override) {
                        event.setCancelled(true);
                        if (plugin.isEnableMessages()) {
                            player.sendMessage("§cYou cannot interact with objects in this area.");
                        }
                    }
                    return;
                }
                if (!area.getSettings().optBoolean("object.interact", true)) {
                    event.setCancelled(true);
                    if (plugin.isEnableMessages()) {
                        player.sendMessage("§cYou cannot interact with objects in this area.");
                    }
                }
            }
        }
    }
}
