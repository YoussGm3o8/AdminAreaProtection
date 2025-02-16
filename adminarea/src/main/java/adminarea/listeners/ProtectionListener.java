package adminarea.listeners;

import cn.nukkit.Player;
import cn.nukkit.block.Block;
import cn.nukkit.block.BlockID;
import cn.nukkit.entity.Entity;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.block.*;
import cn.nukkit.event.entity.*;
import cn.nukkit.event.player.*;
import cn.nukkit.inventory.ContainerInventory;
import cn.nukkit.item.Item;
import cn.nukkit.level.Position;
import cn.nukkit.utils.LogLevel;
import io.micrometer.core.instrument.Timer;
import cn.nukkit.event.inventory.InventoryOpenEvent;
import cn.nukkit.event.vehicle.VehicleDamageEvent;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import adminarea.AdminAreaProtectionPlugin;
import adminarea.area.Area;

/**
 * Handles all protection-related events and permission checks.
 */
public class ProtectionListener implements Listener {
    private final AdminAreaProtectionPlugin plugin;
    private final Map<String, Set<String>> temporaryPermissions;
    private final Map<Position, Boolean> protectionCache;
    private final Map<String, Long> lastWarningTime;
    private static final long WARNING_COOLDOWN = 3000; // 3 seconds
    private static final int CACHE_SIZE = 1000;
    private static final long CACHE_EXPIRY = TimeUnit.MINUTES.toMillis(5);
    private final Cache<String, Boolean> permissionCache;
    private static final long PERMISSION_CACHE_DURATION = TimeUnit.MINUTES.toMillis(5);

    public ProtectionListener(AdminAreaProtectionPlugin plugin) {
        this.plugin = plugin;
        this.temporaryPermissions = new ConcurrentHashMap<>();
        this.protectionCache = new LinkedHashMap<Position, Boolean>(CACHE_SIZE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Position, Boolean> eldest) {
                return size() > CACHE_SIZE;
            }
        };
        this.lastWarningTime = new ConcurrentHashMap<>();
        this.permissionCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            if (handleProtection(event.getBlock(), event.getPlayer(), "area.block.break")) {
                event.setCancelled(true);
                sendProtectionMessage(event.getPlayer(), plugin.getMsgBlockBreak());
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "block_break_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Player player = event.getPlayer();
            Block block = event.getBlock();
            Area area = plugin.getHighestPriorityArea(block.getLevel().getName(),
                block.getX(), block.getY(), block.getZ());
                
            if (handleProtection(event.getBlock(), event.getPlayer(), "area.block.place")) {
                if (player.hasPermission("adminarea.bypass") || plugin.isBypassing(player.getName())) {
                    return;
                }
                event.setCancelled(true);
                sendProtectionMessage(player, plugin.getMsgBlockPlace());
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "block_place_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Player player = event.getPlayer();
            Block block = event.getBlock();
            
            // Special handling for containers
            if (block != null && isContainer(block)) {
                if (handleProtection(block, player, "area.container.access")) {
                    event.setCancelled(true);
                    sendProtectionMessage(player, "§cYou cannot access containers in this area.");
                    return;
                }
            }

            // General interaction protection
            if (handleProtection(block, player, "area.interact")) {
                event.setCancelled(true);
                sendProtectionMessage(player, "§cThis area is protected.");
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "interact_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRedstone(BlockRedstoneEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Area area = plugin.getHighestPriorityArea(event.getBlock().getLevel().getName(),
                event.getBlock().getX(), event.getBlock().getY(), event.getBlock().getZ());
                
            if (area != null && !area.getSettings().optBoolean("area.redstone", true)) {
                event.setCancelled();
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "redstone_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Entity damager = event.getDamager();
            Entity victim = event.getEntity();
            
            if (damager instanceof Player player) {
                // PvP protection
                if (victim instanceof Player && handleProtection(victim, player, "allowPvP")) {
                    event.setCancelled(true);
                    sendProtectionMessage(player, plugin.getMsgPVP());
                    return;
                }
                
                // General entity protection
                if (handleProtection(victim, player, "allowEntityDamage")) {
                    event.setCancelled(true);
                    sendProtectionMessage(player, "§cYou cannot damage entities in this area.");
                }
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "entity_damage_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            event.setBlockList(event.getBlockList().stream()
                .filter(block -> {
                    Area area = plugin.getHighestPriorityArea(block.getLevel().getName(),
                        block.getX(), block.getY(), block.getZ());
                    return area == null || area.getSettings().optBoolean("allowExplosions", false);
                })
                .toList());
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "explosion_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        if (handleProtection(event.getBlock(), null, "allowFireSpread")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLiquidFlow(LiquidFlowEvent event) {
        if (handleProtection(event.getBlock(), null, "allowLiquidFlow")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Position from = event.getFrom();
        Position to = event.getTo();
        
        // Get areas for both positions
        Area fromArea = plugin.getHighestPriorityArea(from.getLevel().getName(),
            from.getX(), from.getY(), from.getZ());
        Area toArea = plugin.getHighestPriorityArea(to.getLevel().getName(),
            to.getX(), to.getY(), to.getZ());
        
        // If areas are different, handle enter/leave messages
        if (fromArea != toArea) {
            // Handle leaving area
            if (fromArea != null && fromArea.isShowTitle() && fromArea.getLeaveMessage() != null) {
                player.sendTitle(
                    "§e" + fromArea.getName(),
                    fromArea.getLeaveMessage(),
                    20, // fadeIn
                    40, // stay
                    20  // fadeOut
                );
            }
            
            // Handle entering area
            if (toArea != null && toArea.isShowTitle() && toArea.getEnterMessage() != null) {
                player.sendTitle(
                    "§e" + toArea.getName(),
                    toArea.getEnterMessage(),
                    20, // fadeIn
                    40, // stay
                    20  // fadeOut
                );
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            if (event.getPlayer() instanceof Player player) {
                if (handleProtection(player.getPosition(), player, "allowContainerAccess")) {
                    event.setCancelled(true);
                    sendProtectionMessage(player, "§cYou cannot access containers in this area.");
                }
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "inventory_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(ItemFrameDropItemEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            if (handleProtection(event.getItemFrame().getLocation(), null, "allowHangingBreak")) {
                event.setCancelled(true);
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "hanging_break_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicleDamage(VehicleDamageEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            Entity attacker = event.getAttacker();
            if (attacker instanceof Player player) {
                if (handleProtection(event.getVehicle().getPosition(), player, "allowVehicleDamage")) {
                    event.setCancelled(true);
                    sendProtectionMessage(player, "§cYou cannot damage vehicles in this area.");
                }
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "vehicle_damage_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            // Additional fire protection
            if (handleProtection(event.getBlock(), null, "allowFireStart")) {
                event.setCancelled(true);
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "block_ignite_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            // Additional block spread protection (vines, mushrooms, etc)
            if (handleProtection(event.getBlock(), null, "allowBlockSpread")) {
                event.setCancelled(true);
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "block_spread_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonChangeEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            // Check if piston affects protected area
            boolean cancel = false;
            if (handleProtection(event.getBlock(), null, "allowPistons")) {
                event.setCancelled(true);
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "piston_extend_check");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonChangeEvent event) {
        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            if (handleProtection(event.getBlock(), null, "allowPistons")) {
                event.setCancelled(true);
            }
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "piston_retract_check");
        }
    }

    private boolean handleProtection(Position pos, Player player, String permission) {
        if (player != null && plugin.isBypassing(player.getName())) {
            return false;
        }

        String cacheKey = buildCacheKey(pos, player, permission);
        Boolean cached = permissionCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }

        Timer.Sample sample = plugin.getPerformanceMonitor().startTimer();
        try {
            boolean result = checkProtection(pos, player, permission);
            permissionCache.put(cacheKey, result);
            return result;
        } finally {
            plugin.getPerformanceMonitor().stopTimer(sample, "protection_check");
        }
    }

    private boolean checkProtection(Position pos, Player player, String permission) {
        Area area = plugin.getHighestPriorityArea(pos.getLevel().getName(),
            pos.getX(), pos.getY(), pos.getZ());
        
        if (area == null) {
            return false;
        }

        // Check player-specific permissions first
        if (player != null) {
            // Check temporary permissions
            if (hasTemporaryPermission(player.getName(), permission, area.getName())) {
                return false;
            }

            // Check LuckPerms group permissions
            if (plugin.getLuckPermsApi() != null && 
                !plugin.hasGroupPermission(player, area, permission)) {
                return true;
            }
        }

        // Check area settings
        return !area.getSettings().optBoolean(permission, true);
    }

    private String buildCacheKey(Position pos, Player player, String permission) {
        StringBuilder key = new StringBuilder()
            .append(pos.getLevel().getName())
            .append(':')
            .append(pos.getFloorX())
            .append(':')
            .append(pos.getFloorY())
            .append(':')
            .append(pos.getFloorZ())
            .append(':')
            .append(permission);
        
        if (player != null) {
            key.append(':').append(player.getName());
        }
        
        return key.toString();
    }

    private boolean hasTemporaryPermission(String player, String permission, String area) {
        Set<String> perms = temporaryPermissions.get(player);
        return perms != null && perms.contains(permission + "." + area);
    }

    private Boolean getCachedResult(Position pos, String permission) {
        String cacheKey = pos.getLevel().getName() + ":" + pos.getFloorX() + ":" + 
                         pos.getFloorY() + ":" + pos.getFloorZ() + ":" + permission;
        synchronized (protectionCache) {
            return protectionCache.get(pos);
        }
    }

    private void cacheProtectionResult(Position pos, String permission, boolean result) {
        synchronized (protectionCache) {
            protectionCache.put(pos, result);
        }
    }

    private boolean isContainer(Block block) {
        switch (block.getId()) {
            case BlockID.CHEST:
            case BlockID.TRAPPED_CHEST:
            case BlockID.BARREL:
            case BlockID.FURNACE:
            case BlockID.BLAST_FURNACE:
            case BlockID.SMOKER:
            case BlockID.BREWING_STAND_BLOCK:
            case BlockID.DISPENSER:
            case BlockID.DROPPER:
            case BlockID.HOPPER_BLOCK:
            case BlockID.SHULKER_BOX:
                return true;
            default:
                return false;
        }
    }

    private void sendProtectionMessage(Player player, String message) {
        if (!plugin.isEnableMessages()) return;
        
        long now = System.currentTimeMillis();
        long lastWarning = lastWarningTime.getOrDefault(player.getName(), 0L);
        
        if (now - lastWarning >= WARNING_COOLDOWN) {
            player.sendMessage(message != null ? message : "§cThis area is protected.");
            lastWarningTime.put(player.getName(), now);
        }
    }

    public void addTemporaryPermission(String player, String permission, String area, long duration) {
        String fullPermission = permission + "." + area;
        temporaryPermissions.computeIfAbsent(player, k -> ConcurrentHashMap.newKeySet()).add(fullPermission);
        
        plugin.getServer().getScheduler().scheduleDelayedTask(plugin, () -> {
            Set<String> perms = temporaryPermissions.get(player);
            if (perms != null) {
                perms.remove(fullPermission);
                if (perms.isEmpty()) {
                    temporaryPermissions.remove(player);
                }
            }
        }, (int) (duration / 50)); // Convert milliseconds to ticks
    }

    public void cleanup() {
        protectionCache.clear();
        temporaryPermissions.clear();
        lastWarningTime.clear();
        permissionCache.invalidateAll();
    }
}
