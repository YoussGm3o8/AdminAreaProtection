package adminarea;

import cn.nukkit.entity.item.EntityPrimedTNT;
import cn.nukkit.event.Listener;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.block.*;
import cn.nukkit.event.entity.EntityDamageEvent;
import cn.nukkit.event.player.PlayerItemConsumeEvent;
import cn.nukkit.level.Position;
import cn.nukkit.event.entity.EntityExplodeEvent;
import cn.nukkit.event.entity.EntityShootBowEvent;
import cn.nukkit.event.player.PlayerFoodLevelChangeEvent;
import cn.nukkit.event.entity.CreatureSpawnEvent;
import org.json.JSONObject;
import cn.nukkit.event.player.PlayerMoveEvent;
import cn.nukkit.event.player.PlayerBucketEmptyEvent; // add import for bucket event
import cn.nukkit.item.Item;

import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.event.entity.EntityDamageEvent.DamageCause;

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

    // Helper method to get a boolean permission from area settings.
    private boolean isActionAllowed(Area area, String key) {
        JSONObject settings = area.getSettings();
        return settings.optBoolean(key, true);
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

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (plugin.isBypassing(player.getName())) {
            return; // Bypassing, so don't apply protection
        }
        if (isCoolingDown(player)) {
            event.setCancelled(true);
            return;
        }
        Area area = plugin.getHighestPriorityArea(player.getLevel().getFolderName(), player.x, player.y, player.z);
        if (area != null && !isActionAllowed(area, "break")) {
            event.setCancelled(true);
            if (plugin.isEnableMessages()) {
                player.sendMessage(plugin.getMsgBlockBreak().replace("{area}", area.getName()));
            }
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (plugin.isBypassing(player.getName())) {
            return; // Bypassing, so don't apply protection
        }
        if (isCoolingDown(player)) {
            event.setCancelled(true);
            return;
        }
        Area area = plugin.getHighestPriorityArea(player.getLevel().getFolderName(), player.x, player.y, player.z);
        if (area != null && !isActionAllowed(area, "place")) {
            event.setCancelled(true);
            if (plugin.isEnableMessages()) {
                player.sendMessage(plugin.getMsgBlockPlace().replace("{area}", area.getName()));
            }
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            Area area = plugin.getHighestPriorityArea(player.getLevel().getFolderName(), player.x, player.y, player.z);
            if (area != null) {
                if (event.getCause() == DamageCause.FALL && !isActionAllowed(area, "no_fall")) {
                    event.setCancelled(true);
                }
                if (event.getCause() == DamageCause.ENTITY_ATTACK && !isActionAllowed(area, "pvp")) {
                    event.setCancelled(true);
                    if (plugin.isEnableMessages()) {
                        player.sendMessage(plugin.getMsgPVP().replace("{area}", area.getName()));
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
            if (area != null && !isActionAllowed(area, "tnt")) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onPlayerFoodLevelChange(PlayerFoodLevelChangeEvent event) {
        Player player = event.getPlayer();
        Area area = plugin.getHighestPriorityArea(player.getLevel().getFolderName(), player.x, player.y, player.z);
        if (area != null && !isActionAllowed(area, "hunger")) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onEntityShootBow(EntityShootBowEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            Area area = plugin.getHighestPriorityArea(player.getLevel().getFolderName(), player.x, player.y, player.z);
            if (area != null && !isActionAllowed(area, "no_projectile")) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onBlockIgnite(BlockIgniteEvent event) {
        Block block = event.getBlock();
        Area area = plugin.getHighestPriorityArea(block.getLevel().getFolderName(), block.getX(), block.getY(), block.getZ());
        if (area != null && !isActionAllowed(area, "set_fire")) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockSpread(BlockSpreadEvent event) {
        Block block = event.getBlock();
        Area area = plugin.getHighestPriorityArea(block.getLevel().getFolderName(), block.getX(), block.getY(), block.getZ());
        if (area != null && !isActionAllowed(area, "fire_spread")) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockFromTo(BlockFromToEvent event) {
        Block block = event.getBlock();
        Area area = plugin.getHighestPriorityArea(block.getLevel().getFolderName(), block.getX(), block.getY(), block.getZ());
        if (area != null) {
            int id = block.getId();
            if ((id == 8 || id == 9) && !isActionAllowed(area, "water_flow")) {
                event.setCancelled(true);
            } else if ((id == 10 || id == 11) && !isActionAllowed(area, "lava_flow")) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        Position position = event.getPosition();
        Area area = plugin.getHighestPriorityArea(position.getLevel().getFolderName(), position.getX(), position.getY(), position.getZ());
        if (area != null && !isActionAllowed(area, "mob_spawning")) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerItemUse(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        Area area = plugin.getHighestPriorityArea(player.getLevel().getFolderName(), player.x, player.y, player.z);
        if (area != null && !isActionAllowed(area, "item_use")) {
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
        if (area != null && !isActionAllowed(area, "place")) {
            Item bucket = event.getBucket(); // bucket item
            if (bucket.getId() == 325 || bucket.getId() == 327) { // water bucket or lava bucket
                event.setCancelled(true);
                if (plugin.isEnableMessages()) {
                    player.sendMessage(plugin.getMsgBlockPlace().replace("{area}", area.getName()));
                }
            }
        }
    }
}
